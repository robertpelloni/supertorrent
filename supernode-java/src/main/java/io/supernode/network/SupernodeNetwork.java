package io.supernode.network;

import io.supernode.network.transport.*;
import io.supernode.storage.BlobStore;
import io.supernode.storage.InMemoryBlobStore;
import io.supernode.storage.SupernodeStorage;
import io.supernode.storage.SupernodeStorage.IngestResult;
import io.supernode.storage.SupernodeStorage.RetrieveResult;
import io.supernode.storage.SupernodeStorage.StorageOptions;
import io.supernode.storage.mux.Manifest;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Unified Supernode network interface.
 * Combines storage, P2P blob network, DHT discovery, and manifest distribution.
 */
public class SupernodeNetwork {
    
    private final BlobStore blobStore;
    private final SupernodeStorage storage;
    private final BlobNetwork blobNetwork;
    private final DHTDiscovery dht;
    private final ManifestDistributor manifestDistributor;
    
    private final UnifiedNetwork unifiedNetwork;
    private final boolean multiTransportEnabled;
    
    private volatile boolean destroyed = false;
    private int port;
    private final SupernodeNetworkOptions options;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Event listeners
    private Consumer<ListeningEvent> onListening;
    private Consumer<PeerEvent> onPeer;
    private Consumer<DisconnectEvent> onDisconnect;
    private Consumer<FileIngestedEvent> onFileIngested;
    private Consumer<FileRetrievedEvent> onFileRetrieved;
    private Consumer<HealthChangeEvent> onHealthChange;
    private Consumer<Void> onDestroyed;
    
    public SupernodeNetwork() {
        this(SupernodeNetworkOptions.defaults());
    }
    
    public SupernodeNetwork(SupernodeNetworkOptions options) {
        this.options = options;
        this.blobStore = options.blobStore() != null ? options.blobStore() : new InMemoryBlobStore();
        this.multiTransportEnabled = options.multiTransport() != null;
        
        StorageOptions storageOpts = options.storageOptions() != null ? options.storageOptions() :
            (options.enableErasure() 
                ? StorageOptions.withErasure(options.dataShards(), options.parityShards())
                : StorageOptions.defaults());
        
        this.storage = new SupernodeStorage(blobStore, storageOpts);
        
        this.blobNetwork = new BlobNetwork(blobStore, BlobNetwork.BlobNetworkOptions.builder()
            .peerId(options.peerId())
            .maxConnections(options.maxConnections())
            .build()
        );
        
        this.dht = new DHTDiscovery(DHTDiscovery.DHTOptions.builder()
            .bootstrap(options.bootstrap())
            .build()
        );
        
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
                onPeer.accept(new PeerEvent(e.peerId(), e.host(), e.port(), TransportType.CLEARNET));
            }
        });
        
        blobNetwork.setOnDisconnect(e -> {
            if (onDisconnect != null) {
                onDisconnect.accept(new DisconnectEvent(e.peerId(), TransportType.CLEARNET));
            }
        });
        
        if (unifiedNetwork != null) {
            unifiedNetwork.setOnPeer(e -> {
                if (onPeer != null) {
                    onPeer.accept(new PeerEvent(e.peerId(), e.address().host(), e.address().port(), e.type()));
                }
            });
            
            unifiedNetwork.setOnDisconnect(e -> {
                if (onDisconnect != null) {
                    onDisconnect.accept(new DisconnectEvent(e.peerId(), e.type()));
                }
            });
        }
        
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
        
        blobNetwork.setOnHealthChange(e -> checkHealth());
        dht.setOnHealthChange(e -> checkHealth());
    }
    
    private void checkHealth() {
        if (onHealthChange != null) {
            onHealthChange.accept(new HealthChangeEvent(getHealthStatus()));
        }
    }
    
    public CompletableFuture<Integer> start() {
        return listen(options.port());
    }
    
    public CompletableFuture<Integer> listen(int port) {
        return blobNetwork.listen(port).thenCompose(p -> {
            this.port = p;
            manifestDistributor.setPort(p);
            return dht.start().thenApply(v -> p);
        }).thenCompose(p -> {
            if (multiTransportEnabled && unifiedNetwork != null) {
                return unifiedNetwork.start().thenApply(v -> p);
            }
            return CompletableFuture.completedFuture(p);
        });
    }
    
    public CompletableFuture<?> connect(String address) {
        if (address.startsWith("ws://") || address.startsWith("wss://")) {
            return blobNetwork.connect(address);
        } else if (unifiedNetwork != null) {
            return unifiedNetwork.connect(address);
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported address: " + address));
    }
    
    public CompletableFuture<?> connect(TransportAddress address) {
        if (address.type() == TransportType.CLEARNET) {
            return blobNetwork.connectViaTransport(address);
        } else if (unifiedNetwork != null) {
            return unifiedNetwork.connect(address);
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("Multi-transport not enabled"));
    }
    
    public CompletableFuture<IngestResult> ingestFileAsync(byte[] fileBuffer, String fileName, byte[] masterKey) {
        return ingestFileAsync(fileBuffer, fileName, masterKey, null);
    }
    
    public CompletableFuture<IngestResult> ingestFileAsync(byte[] fileBuffer, String fileName, byte[] masterKey, Consumer<SupernodeStorage.Progress> progress) {
        return storage.ingestAsync(fileBuffer, fileName, masterKey, progress).thenApply(result -> {
            for (String chunkHash : result.chunkHashes()) {
                dht.announce(chunkHash, port);
                blobNetwork.announceBlob(chunkHash);
                if (unifiedNetwork != null) {
                    unifiedNetwork.announceBlob(chunkHash);
                }
            }
            manifestDistributor.announceManifest(result.fileId());
            return result;
        });
    }
    
    public IngestResult ingestFile(byte[] fileBuffer, String fileName, byte[] masterKey) {
        try {
            return ingestFileAsync(fileBuffer, fileName, masterKey).get();
        } catch (Exception e) {
            throw new RuntimeException("Ingest failed", e);
        }
    }
    
    public CompletableFuture<RetrieveResult> retrieveFileAsync(String fileId, byte[] masterKey) {
        return retrieveFileAsync(fileId, masterKey, null);
    }
    
    public CompletableFuture<RetrieveResult> retrieveFileAsync(String fileId, byte[] masterKey, Consumer<SupernodeStorage.Progress> progress) {
        return storage.retrieveAsync(fileId, masterKey, progress);
    }
    
    public RetrieveResult retrieveFile(String fileId, byte[] masterKey) {
        try {
            return retrieveFileAsync(fileId, masterKey).get();
        } catch (Exception e) {
            throw new RuntimeException("Retrieve failed", e);
        }
    }
    
    public CompletableFuture<RetrieveResult> fetchFile(String fileId, Manifest manifest, byte[] masterKey) {
        return fetchFile(fileId, manifest, masterKey, null);
    }
    
    public CompletableFuture<RetrieveResult> fetchFile(String fileId, Manifest manifest, byte[] masterKey, Consumer<SupernodeStorage.Progress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            List<Manifest.Segment> segments = manifest.getSegments();
            for (Manifest.Segment segment : segments) {
                if (segment.shards() != null && !segment.shards().isEmpty()) {
                    for (Manifest.ShardInfo shard : segment.shards()) {
                        ensureBlobAvailable(shard.hash());
                    }
                } else {
                    ensureBlobAvailable(segment.chunkHash());
                }
            }
            return storage.retrieve(fileId, masterKey, progress);
        });
    }
    
    private void ensureBlobAvailable(String blobId) {
        if (!blobStore.has(blobId)) {
            try {
                waitForBlob(blobId, options.blobTimeout().toMillis()).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch blob: " + blobId, e);
            }
        }
    }
    
    public CompletableFuture<byte[]> waitForBlob(String blobId, long timeoutMs) {
        if (blobStore.has(blobId)) {
            return CompletableFuture.completedFuture(blobStore.get(blobId).orElse(null));
        }
        
        return dht.findPeers(blobId, timeoutMs / 2).thenCompose(dhtPeers -> {
            List<BlobNetwork.PeerConnection> networkPeers = blobNetwork.findPeersWithBlob(blobId);
            if (!networkPeers.isEmpty()) {
                return blobNetwork.requestBlob(blobId, networkPeers);
            }
            
            if (unifiedNetwork != null && unifiedNetwork.getPeerCount() > 0) {
                return unifiedNetwork.requestBlob(blobId);
            }
            
            if (!dhtPeers.isEmpty()) {
                DHTDiscovery.PeerInfo firstPeer = dhtPeers.get(0);
                String address = "ws://" + firstPeer.host() + ":" + firstPeer.port();
                return blobNetwork.connect(address).thenCompose(conn -> {
                    blobNetwork.queryBlob(blobId);
                    CompletableFuture<byte[]> waitFuture = new CompletableFuture<>();
                    scheduler.schedule(() -> {
                        List<BlobNetwork.PeerConnection> peers = blobNetwork.findPeersWithBlob(blobId);
                        if (!peers.isEmpty()) {
                            blobNetwork.requestBlob(blobId, peers).whenComplete((data, ex) -> {
                                if (ex != null) waitFuture.completeExceptionally(ex);
                                else waitFuture.complete(data);
                            });
                        } else {
                            waitFuture.completeExceptionally(new RuntimeException("Blob not found on peer"));
                        }
                    }, 500, TimeUnit.MILLISECONDS);
                    return waitFuture;
                });
            }
            
            return CompletableFuture.failedFuture(new RuntimeException("No peers found for blob: " + blobId));
        });
    }
    
    public CompletableFuture<byte[]> findManifest(String fileId, long timeoutMs) {
        Optional<byte[]> local = storage.getManifest(fileId);
        if (local.isPresent()) {
            return CompletableFuture.completedFuture(local.get());
        }
        
        return manifestDistributor.findManifestPeers(fileId, timeoutMs).thenCompose(peers -> {
            if (peers.isEmpty()) {
                return CompletableFuture.failedFuture(new RuntimeException("No peers found for manifest: " + fileId));
            }
            
            DHTDiscovery.PeerInfo firstPeer = peers.get(0);
            String address = "ws://" + firstPeer.host() + ":" + firstPeer.port();
            
            return blobNetwork.connect(address).thenCompose(conn -> {
                return manifestDistributor.requestManifest(fileId, request -> {}, timeoutMs);
            }).thenApply(ManifestDistributor.ManifestResult::manifest);
        });
    }
    
    public CompletableFuture<RetrieveResult> fetchFileByManifest(String fileId, byte[] masterKey, long timeoutMs) {
        return findManifest(fileId, timeoutMs).thenCompose(encryptedManifest -> {
            byte[] manifestKey = Manifest.deriveManifestKey(masterKey, fileId);
            Manifest manifest = Manifest.decrypt(encryptedManifest, manifestKey);
            storage.storeManifest(fileId, encryptedManifest);
            return fetchFile(fileId, manifest, masterKey);
        });
    }
    
    public void shareManifest(String fileId) {
        manifestDistributor.announceManifest(fileId);
    }
    
    public List<PeerEvent> getPeers() {
        List<PeerEvent> all = new ArrayList<>();
        blobNetwork.getPeers().forEach(p -> 
            all.add(new PeerEvent(p.peerId(), p.host(), p.port(), TransportType.CLEARNET)));
        if (unifiedNetwork != null) {
            unifiedNetwork.getPeers().forEach(p -> 
                all.add(new PeerEvent(p.peerId, p.connection.getRemoteAddress().host(), 
                    p.connection.getRemoteAddress().port(), p.connection.getTransportType())));
        }
        return all;
    }
    
    public Transport.HealthStatus getHealthStatus() {
        Transport.HealthStatus dhtHealth = dht.getHealthStatus();
        Transport.HealthStatus blobHealth = blobNetwork.getHealthStatus();
        
        Transport.HealthState combinedState = dhtHealth.state();
        if (blobHealth.state().ordinal() > combinedState.ordinal()) {
            combinedState = blobHealth.state();
        }
        
        if (unifiedNetwork != null) {
            Transport.HealthStatus unifiedHealth = unifiedNetwork.getTransportManager().getHealthStatus();
            if (unifiedHealth.state().ordinal() > combinedState.ordinal()) {
                combinedState = unifiedHealth.state();
            }
        }
        
        return new Transport.HealthStatus(
            combinedState,
            "DHT: " + dhtHealth.message() + " | Blob: " + blobHealth.message(),
            Instant.now(),
            dhtHealth.consecutiveFailures() + blobHealth.consecutiveFailures(),
            0
        );
    }
    
    public NetworkStats stats() {
        return new NetworkStats(
            storage.stats(),
            blobNetwork.getStats(),
            dht.getStats(),
            manifestDistributor.getStats(),
            unifiedNetwork != null ? unifiedNetwork.stats() : null
        );
    }
    
    public void destroy() {
        destroyed = true;
        scheduler.shutdown();
        manifestDistributor.destroy();
        dht.destroy();
        blobNetwork.destroy();
        if (unifiedNetwork != null) {
            unifiedNetwork.stop();
        }
        storage.shutdown();
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public CompletableFuture<Void> destroyAsync() {
        return CompletableFuture.runAsync(this::destroy);
    }
    
    // Getters
    public boolean isDestroyed() { return destroyed; }
    public int getPort() { return port; }
    public BlobStore getBlobStore() { return blobStore; }
    public SupernodeStorage getStorage() { return storage; }
    public BlobNetwork getBlobNetwork() { return blobNetwork; }
    public DHTDiscovery getDht() { return dht; }
    public ManifestDistributor getManifestDistributor() { return manifestDistributor; }
    public UnifiedNetwork getUnifiedNetwork() { return unifiedNetwork; }
    public SupernodeNetworkOptions getOptions() { return options; }
    
    // Setters
    public void setOnListening(Consumer<ListeningEvent> listener) { this.onListening = listener; }
    public void setOnPeer(Consumer<PeerEvent> listener) { this.onPeer = listener; }
    public void setOnDisconnect(Consumer<DisconnectEvent> listener) { this.onDisconnect = listener; }
    public void setOnFileIngested(Consumer<FileIngestedEvent> listener) { this.onFileIngested = listener; }
    public void setOnFileRetrieved(Consumer<FileRetrievedEvent> listener) { this.onFileRetrieved = listener; }
    public void setOnHealthChange(Consumer<HealthChangeEvent> listener) { this.onHealthChange = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    
    // Records
    public record SupernodeNetworkOptions(
        BlobStore blobStore,
        String peerId,
        List<String> bootstrap,
        int port,
        int maxConnections,
        boolean enableErasure,
        int dataShards,
        int parityShards,
        StorageOptions storageOptions,
        Duration blobTimeout,
        UnifiedNetwork.UnifiedNetworkOptions multiTransport
    ) {
        public static SupernodeNetworkOptions defaults() {
            return builder().build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private BlobStore blobStore;
            private String peerId;
            private List<String> bootstrap = DHTDiscovery.DEFAULT_BOOTSTRAP;
            private int port = 0;
            private int maxConnections = 50;
            private boolean enableErasure = false;
            private int dataShards = 4;
            private int parityShards = 2;
            private StorageOptions storageOptions;
            private Duration blobTimeout = Duration.ofSeconds(30);
            private UnifiedNetwork.UnifiedNetworkOptions multiTransport;
            
            public Builder blobStore(BlobStore store) { this.blobStore = store; return this; }
            public Builder peerId(String id) { this.peerId = id; return this; }
            public Builder bootstrap(List<String> b) { this.bootstrap = b; return this; }
            public Builder port(int p) { this.port = p; return this; }
            public Builder maxConnections(int max) { this.maxConnections = max; return this; }
            public Builder enableErasure(boolean enable) { this.enableErasure = enable; return this; }
            public Builder dataShards(int shards) { this.dataShards = shards; return this; }
            public Builder parityShards(int shards) { this.parityShards = shards; return this; }
            public Builder storageOptions(StorageOptions opts) { this.storageOptions = opts; return this; }
            public Builder blobTimeout(Duration timeout) { this.blobTimeout = timeout; return this; }
            public Builder multiTransport(UnifiedNetwork.UnifiedNetworkOptions multi) { this.multiTransport = multi; return this; }
            
            public SupernodeNetworkOptions build() {
                return new SupernodeNetworkOptions(
                    blobStore, peerId, bootstrap, port, maxConnections, 
                    enableErasure, dataShards, parityShards, storageOptions,
                    blobTimeout, multiTransport
                );
            }
        }
    }
    
    public record ListeningEvent(int port) {}
    public record PeerEvent(String peerId, String host, int port, TransportType type) {
        public PeerEvent(String peerId, String host, int port) {
            this(peerId, host, port, TransportType.CLEARNET);
        }
    }
    public record DisconnectEvent(String peerId, TransportType type) {
        public DisconnectEvent(String peerId) {
            this(peerId, TransportType.CLEARNET);
        }
    }
    public record FileIngestedEvent(String fileId, String fileName, long size) {}
    public record FileRetrievedEvent(String fileId, String fileName, long size) {}
    public record HealthChangeEvent(Transport.HealthStatus status) {}
    
    public record NetworkStats(
        SupernodeStorage.StorageStats storage,
        BlobNetwork.BlobNetworkStats blobNetwork,
        DHTDiscovery.DHTStats dht,
        ManifestDistributor.ManifestDistributorStats manifestDistributor,
        UnifiedNetwork.NetworkStats unifiedNetwork
    ) {}
}
