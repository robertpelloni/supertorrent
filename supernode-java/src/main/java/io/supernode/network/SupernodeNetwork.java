package io.supernode.network;

import io.supernode.network.transport.*;
import io.supernode.storage.BlobStore;
import io.supernode.storage.InMemoryBlobStore;
import io.supernode.storage.SupernodeStorage;
import io.supernode.storage.SupernodeStorage.IngestResult;
import io.supernode.storage.SupernodeStorage.RetrieveResult;
import io.supernode.storage.SupernodeStorage.StorageOptions;
import io.supernode.storage.mux.Manifest;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Unified Supernode network interface.
 * Combines storage, P2P blob network, DHT discovery, and manifest distribution.
 * 
 * Can operate in two modes:
 * 1. Legacy mode: Uses BlobNetwork with WebSocket-only connections
 * 2. Multi-transport mode: Uses UnifiedNetwork for Tor, I2P, Hyphanet, ZeroNet, IPFS support
 */
public class SupernodeNetwork {
    
    private static final long DEFAULT_LOOKUP_TIMEOUT = 10000;
    private static final long DEFAULT_BLOB_TIMEOUT = 30000;
    
    private final BlobStore blobStore;
    private final SupernodeStorage storage;
    private final BlobNetwork blobNetwork;
    private final DHTDiscovery dht;
    private final ManifestDistributor manifestDistributor;
    
    private final UnifiedNetwork unifiedNetwork;
    private final boolean multiTransportEnabled;
    
    private volatile boolean destroyed = false;
    private int port;
    
    // Event listeners
    private Consumer<ListeningEvent> onListening;
    private Consumer<PeerEvent> onPeer;
    private Consumer<DisconnectEvent> onDisconnect;
    private Consumer<FileIngestedEvent> onFileIngested;
    private Consumer<FileRetrievedEvent> onFileRetrieved;
    private Consumer<Void> onDestroyed;
    
    public SupernodeNetwork() {
        this(new SupernodeNetworkOptions());
    }
    
    public SupernodeNetwork(SupernodeNetworkOptions options) {
        this.blobStore = options.blobStore() != null ? options.blobStore() : new InMemoryBlobStore();
        this.multiTransportEnabled = options.multiTransport() != null;
        
        StorageOptions storageOpts = options.enableErasure()
            ? StorageOptions.withErasure(options.dataShards(), options.parityShards())
            : StorageOptions.defaults();
        this.storage = new SupernodeStorage(blobStore, storageOpts);
        
        this.blobNetwork = new BlobNetwork(blobStore, BlobNetwork.BlobNetworkOptions.builder()
            .peerId(options.peerId())
            .maxConnections(options.maxConnections())
            .build()
        );
        
        this.dht = new DHTDiscovery(new DHTDiscovery.DHTOptions(
            null, options.bootstrap(), 0, 0
        ));
        
        this.manifestDistributor = new ManifestDistributor(
            new ManifestDistributor.ManifestDistributorOptions(dht, storage)
        );
        
        if (multiTransportEnabled) {
            UnifiedNetwork.UnifiedNetworkOptions unifiedOpts = options.multiTransport();
            unifiedOpts.blobStore = this.blobStore;
            unifiedOpts.enableErasure = options.enableErasure();
            unifiedOpts.dataShards = options.dataShards();
            unifiedOpts.parityShards = options.parityShards();
            this.unifiedNetwork = new UnifiedNetwork(unifiedOpts);
        } else {
            this.unifiedNetwork = null;
        }
        
        setupEventForwarding();
    }
    
    private void setupEventForwarding() {
        blobNetwork.setOnListening(e -> {
            if (onListening != null) {
                onListening.accept(new ListeningEvent(e.port()));
            }
        });
        
        blobNetwork.setOnPeer(e -> {
            if (onPeer != null) {
                onPeer.accept(new PeerEvent(e.peerId(), e.host(), e.port()));
            }
        });
        
        blobNetwork.setOnDisconnect(e -> {
            if (onDisconnect != null) {
                onDisconnect.accept(new DisconnectEvent(e.peerId()));
            }
        });
        
        storage.setOnFileIngested(e -> {
            if (onFileIngested != null) {
                onFileIngested.accept(new FileIngestedEvent(e.fileId(), e.fileName(), e.size()));
            }
        });
        
        storage.setOnFileRetrieved(e -> {
            if (onFileRetrieved != null) {
                onFileRetrieved.accept(new FileRetrievedEvent(e.fileId(), e.fileName(), e.size()));
            }
        });
    }
    
    /**
     * Start listening on a port.
     */
    public CompletableFuture<Integer> listen(int port) {
        return blobNetwork.listen(port).thenCompose(p -> {
            this.port = p;
            manifestDistributor.setPort(p);
            return dht.start().thenApply(v -> p);
        });
    }
    
    /**
     * Connect to a peer.
     */
    public CompletableFuture<BlobNetwork.PeerConnection> connect(String address) {
        return blobNetwork.connect(address);
    }
    
    /**
     * Ingest a file into local storage and announce it.
     */
    public IngestResult ingestFile(byte[] fileBuffer, String fileName, byte[] masterKey) {
        IngestResult result = storage.ingest(fileBuffer, fileName, masterKey);
        
        // Announce all chunk blobs via DHT
        for (String chunkHash : result.chunkHashes()) {
            dht.announce(chunkHash, port);
            blobNetwork.announceBlob(chunkHash);
        }
        
        // Announce manifest
        manifestDistributor.announceManifest(result.fileId());
        
        return result;
    }
    
    /**
     * Retrieve a file from local storage.
     */
    public RetrieveResult retrieveFile(String fileId, byte[] masterKey) {
        return storage.retrieve(fileId, masterKey);
    }
    
    /**
     * Fetch a file from the network using an already-decrypted manifest.
     */
    public CompletableFuture<RetrieveResult> fetchFile(String fileId, Manifest manifest, byte[] masterKey) {
        return CompletableFuture.supplyAsync(() -> {
            List<Manifest.Segment> segments = manifest.getSegments();
            
            for (Manifest.Segment segment : segments) {
                if (segment.shards() != null && !segment.shards().isEmpty()) {
                    // Erasure-coded segment - need enough shards
                    for (Manifest.ShardInfo shard : segment.shards()) {
                        if (!blobStore.has(shard.hash())) {
                            try {
                                waitForBlob(shard.hash(), DEFAULT_BLOB_TIMEOUT).get();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to fetch shard: " + shard.hash(), e);
                            }
                        }
                    }
                } else {
                    // Single chunk
                    String chunkHash = segment.chunkHash();
                    if (!blobStore.has(chunkHash)) {
                        try {
                            waitForBlob(chunkHash, DEFAULT_BLOB_TIMEOUT).get();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to fetch chunk: " + chunkHash, e);
                        }
                    }
                }
            }
            
            // All blobs fetched, retrieve from storage
            return storage.retrieve(fileId, masterKey);
        });
    }
    
    /**
     * Wait for a blob to become available (via network).
     */
    private CompletableFuture<byte[]> waitForBlob(String blobId, long timeoutMs) {
        // First check if we already have it
        if (blobStore.has(blobId)) {
            return CompletableFuture.completedFuture(blobStore.get(blobId).orElse(null));
        }
        
        // Query DHT for peers
        return dht.findPeers(blobId, timeoutMs / 2).thenCompose(dhtPeers -> {
            // Also check blob network
            List<BlobNetwork.PeerConnection> networkPeers = blobNetwork.findPeersWithBlob(blobId);
            
            if (!networkPeers.isEmpty()) {
                return blobNetwork.requestBlob(blobId, networkPeers);
            }
            
            // Try connecting to DHT peers
            if (!dhtPeers.isEmpty()) {
                DHTDiscovery.PeerInfo firstPeer = dhtPeers.get(0);
                String address = "ws://" + firstPeer.host() + ":" + firstPeer.port();
                return blobNetwork.connect(address).thenCompose(conn -> {
                    blobNetwork.queryBlob(blobId);
                    
                    CompletableFuture<byte[]> delayedRequest = new CompletableFuture<>();
                    CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> {
                        List<BlobNetwork.PeerConnection> peers = blobNetwork.findPeersWithBlob(blobId);
                        if (!peers.isEmpty()) {
                            blobNetwork.requestBlob(blobId, peers)
                                .whenComplete((data, err) -> {
                                    if (err != null) delayedRequest.completeExceptionally(err);
                                    else delayedRequest.complete(data);
                                });
                        } else {
                            delayedRequest.completeExceptionally(
                                new RuntimeException("No peers have blob: " + blobId));
                        }
                    });
                    return delayedRequest;
                });
            }
            
            return CompletableFuture.failedFuture(new RuntimeException("No peers found for blob: " + blobId));
        });
    }
    
    /**
     * Find a manifest from the network.
     */
    public CompletableFuture<byte[]> findManifest(String fileId, long timeoutMs) {
        // Check local first
        Optional<byte[]> local = storage.getManifest(fileId);
        if (local.isPresent()) {
            return CompletableFuture.completedFuture(local.get());
        }
        
        // Find peers with manifest via DHT
        return manifestDistributor.findManifestPeers(fileId, timeoutMs).thenCompose(peers -> {
            if (peers.isEmpty()) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("No peers found with manifest: " + fileId));
            }
            
            // Connect and request from first peer
            DHTDiscovery.PeerInfo firstPeer = peers.get(0);
            String address = "ws://" + firstPeer.host() + ":" + firstPeer.port();
            
            return blobNetwork.connect(address).thenCompose(conn -> {
                return manifestDistributor.requestManifest(fileId, request -> {
                    // Send request via blob network connection
                    // In real implementation, would serialize to JSON and send
                }, timeoutMs);
            }).thenApply(result -> result.manifest());
        });
    }
    
    /**
     * Fetch a file by finding its manifest first.
     */
    public CompletableFuture<RetrieveResult> fetchFileByManifest(String fileId, byte[] masterKey, long timeoutMs) {
        return findManifest(fileId, timeoutMs).thenCompose(encryptedManifest -> {
            byte[] manifestKey = Manifest.deriveManifestKey(masterKey, fileId);
            Manifest manifest = Manifest.decrypt(encryptedManifest, manifestKey);
            
            // Store manifest locally
            storage.storeManifest(fileId, encryptedManifest);
            
            return fetchFile(fileId, manifest, masterKey);
        });
    }
    
    /**
     * Share a manifest with peers.
     */
    public void shareManifest(String fileId) {
        manifestDistributor.announceManifest(fileId);
    }
    
    /**
     * Get all connected peers.
     */
    public List<BlobNetwork.PeerConnection> getPeers() {
        return blobNetwork.getPeers();
    }
    
    /**
     * Get combined network statistics.
     */
    public NetworkStats stats() {
        return new NetworkStats(
            storage.stats(),
            blobNetwork.getStats(),
            dht.getStats(),
            manifestDistributor.getStats()
        );
    }
    
    /**
     * Destroy the network.
     */
    public void destroy() {
        destroyed = true;
        
        manifestDistributor.destroy();
        dht.destroy();
        blobNetwork.destroy();
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public boolean isDestroyed() { return destroyed; }
    public int getPort() { return port; }
    public BlobStore getBlobStore() { return blobStore; }
    public SupernodeStorage getStorage() { return storage; }
    public BlobNetwork getBlobNetwork() { return blobNetwork; }
    public DHTDiscovery getDht() { return dht; }
    public ManifestDistributor getManifestDistributor() { return manifestDistributor; }
    
    // Event listener setters
    public void setOnListening(Consumer<ListeningEvent> listener) { this.onListening = listener; }
    public void setOnPeer(Consumer<PeerEvent> listener) { this.onPeer = listener; }
    public void setOnDisconnect(Consumer<DisconnectEvent> listener) { this.onDisconnect = listener; }
    public void setOnFileIngested(Consumer<FileIngestedEvent> listener) { this.onFileIngested = listener; }
    public void setOnFileRetrieved(Consumer<FileRetrievedEvent> listener) { this.onFileRetrieved = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    
    // Records
    public record SupernodeNetworkOptions(
        BlobStore blobStore,
        String peerId,
        List<String> bootstrap,
        int maxConnections,
        boolean enableErasure,
        int dataShards,
        int parityShards,
        UnifiedNetwork.UnifiedNetworkOptions multiTransport
    ) {
        public SupernodeNetworkOptions() {
            this(null, null, null, 50, false, 4, 2, null);
        }
        
        public static SupernodeNetworkOptions withErasure(int dataShards, int parityShards) {
            return new SupernodeNetworkOptions(null, null, null, 50, true, dataShards, parityShards, null);
        }
        
        public static SupernodeNetworkOptions withMultiTransport() {
            return new SupernodeNetworkOptions(null, null, null, 50, false, 4, 2, 
                UnifiedNetwork.UnifiedNetworkOptions.allNetworks());
        }
        
        public static SupernodeNetworkOptions withMultiTransport(UnifiedNetwork.UnifiedNetworkOptions opts) {
            return new SupernodeNetworkOptions(null, null, null, 50, false, 4, 2, opts);
        }
    }
    
    public record ListeningEvent(int port) {}
    public record PeerEvent(String peerId, String host, int port) {}
    public record DisconnectEvent(String peerId) {}
    public record FileIngestedEvent(String fileId, String fileName, long size) {}
    public record FileRetrievedEvent(String fileId, String fileName, long size) {}
    
    public record NetworkStats(
        SupernodeStorage.StorageStats storage,
        BlobNetwork.BlobNetworkStats blobNetwork,
        DHTDiscovery.DHTStats dht,
        ManifestDistributor.ManifestDistributorStats manifestDistributor
    ) {}
}
