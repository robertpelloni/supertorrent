package io.supernode.network;

import io.supernode.storage.SupernodeStorage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Distributes encrypted manifests via DHT and peer-to-peer messaging.
 */
public class ManifestDistributor {
    
    private final DHTDiscovery dht;
    private final SupernodeStorage storage;
    private int wsPort;
    
    private final Map<String, ManifestCacheEntry> manifestCache = new ConcurrentHashMap<>();
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private volatile boolean destroyed = false;
    
    // Event listeners
    private Consumer<AnnounceErrorEvent> onAnnounceError;
    private Consumer<ManifestAnnouncedEvent> onManifestAnnounced;
    private Consumer<ManifestServedEvent> onManifestServed;
    private Consumer<Void> onDestroyed;
    
    public ManifestDistributor(ManifestDistributorOptions options) {
        this.dht = options.dht();
        this.storage = options.storage();
        this.wsPort = options.wsPort();
    }
    
    public void setPort(int port) {
        this.wsPort = port;
    }
    
    /**
     * Convert a file ID to a 20-byte info hash for manifest lookups.
     */
    public byte[] fileIdToInfoHash(String fileId) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return sha1.digest(("manifest:" + fileId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
    
    /**
     * Announce that we have a manifest for a file.
     */
    public void announceManifest(String fileId) {
        if (destroyed || dht == null || wsPort == 0) return;
        
        byte[] infoHash = fileIdToInfoHash(fileId);
        String hashHex = bytesToHex(infoHash);
        
        dht.announce("manifest:" + fileId, wsPort);
        
        if (onManifestAnnounced != null) {
            onManifestAnnounced.accept(new ManifestAnnouncedEvent(fileId, hashHex));
        }
        
        if (!manifestCache.containsKey(fileId)) {
            manifestCache.put(fileId, new ManifestCacheEntry(System.currentTimeMillis(), hashHex));
        }
    }
    
    /**
     * Find peers that have a manifest for a file.
     */
    public CompletableFuture<List<DHTDiscovery.PeerInfo>> findManifestPeers(String fileId, long timeoutMs) {
        if (destroyed || dht == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        return dht.findPeers("manifest:" + fileId, timeoutMs);
    }
    
    /**
     * Create a manifest request message.
     */
    public ManifestRequest createManifestRequest(String fileId) {
        byte[] requestIdBytes = new byte[8];
        new SecureRandom().nextBytes(requestIdBytes);
        String requestId = bytesToHex(requestIdBytes);
        
        return new ManifestRequest(
            "manifest-request",
            fileId,
            requestId,
            System.currentTimeMillis()
        );
    }
    
    /**
     * Create a manifest response message.
     */
    public ManifestResponse createManifestResponse(String requestId, String fileId, byte[] encryptedManifest) {
        return new ManifestResponse(
            "manifest-response",
            requestId,
            fileId,
            encryptedManifest != null ? Base64.getEncoder().encodeToString(encryptedManifest) : null,
            encryptedManifest != null,
            System.currentTimeMillis()
        );
    }
    
    /**
     * Handle an incoming message. Returns true if the message was handled.
     */
    public boolean handleMessage(Object message, Consumer<Object> sendResponse) {
        if (message instanceof ManifestRequest request) {
            return handleManifestRequest(request, sendResponse);
        }
        if (message instanceof ManifestResponse response) {
            return handleManifestResponse(response);
        }
        return false;
    }
    
    private boolean handleManifestRequest(ManifestRequest request, Consumer<Object> sendResponse) {
        String fileId = request.fileId();
        String requestId = request.requestId();
        
        Optional<byte[]> encryptedManifest = storage.getManifest(fileId);
        
        ManifestResponse response = createManifestResponse(
            requestId,
            fileId,
            encryptedManifest.orElse(null)
        );
        sendResponse.accept(response);
        
        if (onManifestServed != null) {
            onManifestServed.accept(new ManifestServedEvent(fileId, encryptedManifest.isPresent(), requestId));
        }
        
        return true;
    }
    
    private boolean handleManifestResponse(ManifestResponse response) {
        String requestId = response.requestId();
        
        PendingRequest pending = pendingRequests.remove(requestId);
        if (pending == null) return false;
        
        pending.timeoutFuture().cancel(false);
        
        if (response.found() && response.manifest() != null) {
            byte[] manifestBuffer = Base64.getDecoder().decode(response.manifest());
            pending.future().complete(new ManifestResult(response.fileId(), manifestBuffer));
        } else {
            pending.future().complete(new ManifestResult(response.fileId(), null));
        }
        
        return true;
    }
    
    /**
     * Request a manifest from a peer.
     */
    public CompletableFuture<ManifestResult> requestManifest(String fileId, Consumer<ManifestRequest> sendRequest, long timeoutMs) {
        ManifestRequest request = createManifestRequest(fileId);
        CompletableFuture<ManifestResult> future = new CompletableFuture<>();
        
        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            pendingRequests.remove(request.requestId());
            future.completeExceptionally(new TimeoutException("Manifest request timeout: " + fileId));
        }, timeoutMs, TimeUnit.MILLISECONDS);
        
        pendingRequests.put(request.requestId(), new PendingRequest(fileId, future, timeoutFuture));
        
        sendRequest.accept(request);
        
        return future;
    }
    
    /**
     * Get all announced manifests.
     */
    public List<AnnouncedManifest> getAnnouncedManifests() {
        return manifestCache.entrySet().stream()
            .map(e -> new AnnouncedManifest(e.getKey(), e.getValue().announcedAt(), e.getValue().infoHash()))
            .toList();
    }
    
    /**
     * Get distributor statistics.
     */
    public ManifestDistributorStats getStats() {
        return new ManifestDistributorStats(manifestCache.size(), pendingRequests.size());
    }
    
    /**
     * Destroy the distributor.
     */
    public void destroy() {
        destroyed = true;
        
        for (PendingRequest pending : pendingRequests.values()) {
            pending.timeoutFuture().cancel(false);
            pending.future().completeExceptionally(new RuntimeException("Distributor destroyed"));
        }
        pendingRequests.clear();
        manifestCache.clear();
        
        scheduler.shutdown();
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public boolean isDestroyed() { return destroyed; }
    
    // Event listener setters
    public void setOnAnnounceError(Consumer<AnnounceErrorEvent> listener) { this.onAnnounceError = listener; }
    public void setOnManifestAnnounced(Consumer<ManifestAnnouncedEvent> listener) { this.onManifestAnnounced = listener; }
    public void setOnManifestServed(Consumer<ManifestServedEvent> listener) { this.onManifestServed = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    
    // Utility
    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
    
    // Records
    public record ManifestDistributorOptions(
        DHTDiscovery dht,
        SupernodeStorage storage,
        int wsPort
    ) {
        public ManifestDistributorOptions(DHTDiscovery dht, SupernodeStorage storage) {
            this(dht, storage, 0);
        }
    }
    
    public record ManifestRequest(String type, String fileId, String requestId, long timestamp) {}
    public record ManifestResponse(String type, String requestId, String fileId, String manifest, boolean found, long timestamp) {}
    public record ManifestResult(String fileId, byte[] manifest) {}
    
    public record AnnounceErrorEvent(String fileId, String error) {}
    public record ManifestAnnouncedEvent(String fileId, String infoHash) {}
    public record ManifestServedEvent(String fileId, boolean found, String requestId) {}
    public record ManifestDistributorStats(int announcedManifests, int pendingRequests) {}
    public record AnnouncedManifest(String fileId, long announcedAt, String infoHash) {}
    
    private record ManifestCacheEntry(long announcedAt, String infoHash) {}
    private record PendingRequest(String fileId, CompletableFuture<ManifestResult> future, ScheduledFuture<?> timeoutFuture) {}
}
