package io.supernode.network;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * DHT-based peer discovery for Supernode.
 * Uses BitTorrent DHT protocol (BEP 5) to find peers holding specific blobs.
 * 
 * Note: This is a stub implementation. For production, integrate with
 * a proper DHT library like mldht (github.com/the8472/mldht).
 */
public class DHTDiscovery {
    
    public static final List<String> DEFAULT_BOOTSTRAP = List.of(
        "router.bittorrent.com:6881",
        "router.utorrent.com:6881",
        "dht.transmissionbt.com:6881"
    );
    
    private static final int ANNOUNCE_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
    
    private final byte[] nodeId;
    private final List<String> bootstrap;
    private int port;
    private final int announceInterval;
    
    private final Map<String, AnnouncedHash> announcedHashes = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<PeerInfo>>> lookupCallbacks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private volatile boolean ready = false;
    private volatile boolean destroyed = false;
    
    // Event listeners
    private Consumer<Void> onReady;
    private Consumer<Exception> onError;
    private Consumer<PeerEvent> onPeer;
    private Consumer<AnnounceEvent> onAnnounce;
    private Consumer<String> onWarning;
    private Consumer<ListeningEvent> onListening;
    private Consumer<AnnouncedEvent> onAnnounced;
    private Consumer<AnnounceErrorEvent> onAnnounceError;
    private Consumer<LookupCompleteEvent> onLookupComplete;
    private Consumer<LookupErrorEvent> onLookupError;
    private Consumer<Void> onDestroyed;
    
    public DHTDiscovery() {
        this(new DHTOptions());
    }
    
    public DHTDiscovery(DHTOptions options) {
        this.nodeId = options.nodeId() != null ? options.nodeId() : generateNodeId();
        this.bootstrap = options.bootstrap() != null ? options.bootstrap() : DEFAULT_BOOTSTRAP;
        this.port = options.port();
        this.announceInterval = options.announceInterval() > 0 ? options.announceInterval() : ANNOUNCE_INTERVAL_MS;
    }
    
    /**
     * Start the DHT node.
     * In production, this would connect to the DHT network.
     */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (destroyed) {
                throw new IllegalStateException("DHT has been destroyed");
            }
            
            // TODO: Integrate with actual DHT library (mldht)
            // For now, this is a stub that simulates ready state
            
            try {
                // Simulate network setup delay
                Thread.sleep(100);
                
                ready = true;
                
                if (onListening != null) {
                    onListening.accept(new ListeningEvent(port));
                }
                
                if (onReady != null) {
                    onReady.accept(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("DHT start interrupted", e);
            }
        });
    }
    
    /**
     * Convert a blob ID to a 20-byte info hash (SHA-1).
     */
    public byte[] blobIdToInfoHash(String blobId) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return sha1.digest(blobId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
    
    /**
     * Announce that we have a blob at the given WebSocket port.
     */
    public void announce(String blobId, int wsPort) {
        if (destroyed || !ready) return;
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        // TODO: Actual DHT announce via mldht
        // For now, just track it locally
        
        if (onAnnounced != null) {
            onAnnounced.accept(new AnnouncedEvent(blobId, hashHex));
        }
        
        // Set up periodic re-announce
        if (!announcedHashes.containsKey(hashHex)) {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    if (!destroyed && ready) {
                        // Re-announce
                        if (onAnnounced != null) {
                            onAnnounced.accept(new AnnouncedEvent(blobId, hashHex));
                        }
                    }
                },
                announceInterval,
                announceInterval,
                TimeUnit.MILLISECONDS
            );
            
            announcedHashes.put(hashHex, new AnnouncedHash(blobId, future, wsPort));
        }
    }
    
    /**
     * Stop announcing a blob.
     */
    public void unannounce(String blobId) {
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        AnnouncedHash entry = announcedHashes.remove(hashHex);
        if (entry != null && entry.future() != null) {
            entry.future().cancel(false);
        }
    }
    
    /**
     * Look up peers for a blob.
     */
    public void lookup(String blobId, Consumer<PeerInfo> callback) {
        if (destroyed || !ready) return;
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        if (callback != null) {
            lookupCallbacks.computeIfAbsent(hashHex, k -> ConcurrentHashMap.newKeySet()).add(callback);
        }
        
        // TODO: Actual DHT lookup via mldht
        // For now, just emit completion
        
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // Simulate network delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            if (callback != null) {
                Set<Consumer<PeerInfo>> callbacks = lookupCallbacks.get(hashHex);
                if (callbacks != null) {
                    callbacks.remove(callback);
                    if (callbacks.isEmpty()) {
                        lookupCallbacks.remove(hashHex);
                    }
                }
            }
            
            if (onLookupComplete != null) {
                onLookupComplete.accept(new LookupCompleteEvent(blobId, 0));
            }
        });
    }
    
    /**
     * Find peers for a blob with timeout.
     */
    public CompletableFuture<List<PeerInfo>> findPeers(String blobId, long timeoutMs) {
        CompletableFuture<List<PeerInfo>> future = new CompletableFuture<>();
        List<PeerInfo> peers = Collections.synchronizedList(new ArrayList<>());
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        Consumer<PeerInfo> onPeerCallback = peer -> {
            String addr = peer.host() + ":" + peer.port();
            boolean exists = peers.stream()
                .anyMatch(p -> (p.host() + ":" + p.port()).equals(addr));
            if (!exists) {
                peers.add(peer);
            }
        };
        
        // Set up timeout
        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            Set<Consumer<PeerInfo>> callbacks = lookupCallbacks.get(hashHex);
            if (callbacks != null) {
                callbacks.remove(onPeerCallback);
                if (callbacks.isEmpty()) {
                    lookupCallbacks.remove(hashHex);
                }
            }
            future.complete(new ArrayList<>(peers));
        }, timeoutMs, TimeUnit.MILLISECONDS);
        
        // Start lookup
        lookup(blobId, onPeerCallback);
        
        // Early completion if we find peers quickly
        scheduler.schedule(() -> {
            if (!peers.isEmpty() && !future.isDone()) {
                timeoutFuture.cancel(false);
                future.complete(new ArrayList<>(peers));
            }
        }, Math.min(timeoutMs / 2, 3000), TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    /**
     * Get DHT statistics.
     */
    public DHTStats getStats() {
        if (!ready) return null;
        
        return new DHTStats(
            bytesToHex(nodeId),
            port,
            0, // TODO: Get actual node count from DHT
            announcedHashes.size()
        );
    }
    
    /**
     * Destroy the DHT node.
     */
    public void destroy() {
        destroyed = true;
        
        // Cancel all announced hash timers
        for (AnnouncedHash entry : announcedHashes.values()) {
            if (entry.future() != null) {
                entry.future().cancel(false);
            }
        }
        announcedHashes.clear();
        lookupCallbacks.clear();
        
        scheduler.shutdown();
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public boolean isReady() { return ready; }
    public boolean isDestroyed() { return destroyed; }
    public byte[] getNodeId() { return nodeId.clone(); }
    public int getPort() { return port; }
    
    // Event listener setters
    public void setOnReady(Consumer<Void> listener) { this.onReady = listener; }
    public void setOnError(Consumer<Exception> listener) { this.onError = listener; }
    public void setOnPeer(Consumer<PeerEvent> listener) { this.onPeer = listener; }
    public void setOnAnnounce(Consumer<AnnounceEvent> listener) { this.onAnnounce = listener; }
    public void setOnWarning(Consumer<String> listener) { this.onWarning = listener; }
    public void setOnListening(Consumer<ListeningEvent> listener) { this.onListening = listener; }
    public void setOnAnnounced(Consumer<AnnouncedEvent> listener) { this.onAnnounced = listener; }
    public void setOnAnnounceError(Consumer<AnnounceErrorEvent> listener) { this.onAnnounceError = listener; }
    public void setOnLookupComplete(Consumer<LookupCompleteEvent> listener) { this.onLookupComplete = listener; }
    public void setOnLookupError(Consumer<LookupErrorEvent> listener) { this.onLookupError = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    
    // Utility methods
    private static byte[] generateNodeId() {
        byte[] id = new byte[20];
        new SecureRandom().nextBytes(id);
        return id;
    }
    
    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
    
    // Records
    public record DHTOptions(
        byte[] nodeId,
        List<String> bootstrap,
        int port,
        int announceInterval
    ) {
        public DHTOptions() {
            this(null, null, 0, 0);
        }
    }
    
    public record PeerInfo(String host, int port) {}
    public record PeerEvent(String address, int port, String infoHash, String from) {}
    public record AnnounceEvent(String address, int port, String infoHash) {}
    public record AnnouncedEvent(String blobId, String infoHash) {}
    public record AnnounceErrorEvent(String blobId, String error) {}
    public record ListeningEvent(int port) {}
    public record LookupCompleteEvent(String blobId, int peersFound) {}
    public record LookupErrorEvent(String blobId, String error) {}
    public record DHTStats(String nodeId, int port, int nodes, int announcedHashes) {}
    
    private record AnnouncedHash(String blobId, ScheduledFuture<?> future, int wsPort) {}
}
