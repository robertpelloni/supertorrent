package io.supernode.network;

import io.supernode.network.transport.*;
import io.supernode.storage.BlobStore;
import io.supernode.storage.IPFSBlobStore;
import io.supernode.storage.InMemoryBlobStore;
import io.supernode.storage.SupernodeStorage;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class UnifiedNetwork {

    private final TransportManager transportManager;
    private final BlobStore primaryBlobStore;
    private final IPFSBlobStore ipfsBlobStore;
    private final SupernodeStorage storage;
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    
    private Consumer<ListeningEvent> onListening;
    private Consumer<PeerEvent> onPeer;
    private Consumer<DisconnectEvent> onDisconnect;
    private Consumer<Transport.TransportError> onError;
    private boolean destroyed = false;

    public UnifiedNetwork() {
        this(new UnifiedNetworkOptions());
    }

    public UnifiedNetwork(UnifiedNetworkOptions options) {
        this.transportManager = new TransportManager();
        
        if (options.enableClearnet) {
            transportManager.registerTransport(new ClearnetTransport(options.clearnetPort));
        }
        if (options.enableTor) {
            transportManager.registerTransport(new TorTransport(options.torOptions));
        }
        if (options.enableI2P) {
            transportManager.registerTransport(new I2PTransport(options.i2pOptions));
        }
        if (options.enableHyphanet) {
            transportManager.registerTransport(new HyphanetTransport(options.hyphanetOptions));
        }
        if (options.enableZeroNet) {
            transportManager.registerTransport(new ZeroNetTransport(options.zeroNetOptions));
        }
        if (options.enableIPFS) {
            IPFSTransport ipfsTransport = new IPFSTransport(options.ipfsOptions);
            transportManager.registerTransport(ipfsTransport);
            this.ipfsBlobStore = new IPFSBlobStore(ipfsTransport, true);
        } else {
            this.ipfsBlobStore = null;
        }
        
        this.primaryBlobStore = options.blobStore != null ? options.blobStore : new InMemoryBlobStore();
        
        SupernodeStorage.StorageOptions storageOptions = options.enableErasure
            ? SupernodeStorage.StorageOptions.withErasure(options.dataShards, options.parityShards)
            : SupernodeStorage.StorageOptions.defaults();
        this.storage = new SupernodeStorage(primaryBlobStore, storageOptions);
        
        setupEventHandlers();
    }

    public CompletableFuture<Map<TransportType, TransportAddress>> start() {
        return transportManager.startAll()
            .thenApply(addresses -> {
                Map<TransportType, TransportAddress> result = new EnumMap<>(TransportType.class);
                for (TransportAddress addr : addresses) {
                    result.put(addr.type(), addr);
                    if (onListening != null) {
                        onListening.accept(new ListeningEvent(addr.type(), addr));
                    }
                }
                return result;
            });
    }

    public CompletableFuture<Void> stop() {
        destroyed = true;
        return transportManager.stopAll();
    }

    public CompletableFuture<TransportConnection> connect(String address) {
        return transportManager.connect(address)
            .thenApply(conn -> {
                registerPeer(conn);
                return conn;
            });
    }

    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return transportManager.connect(address)
            .thenApply(conn -> {
                registerPeer(conn);
                return conn;
            });
    }

    public SupernodeStorage.IngestResult ingestFile(byte[] data, String fileName, byte[] masterKey) {
        SupernodeStorage.IngestResult result = storage.ingest(data, fileName, masterKey);
        
        if (ipfsBlobStore != null) {
            replicateToIPFS(result);
        }
        
        return result;
    }

    public SupernodeStorage.RetrieveResult retrieveFile(String fileId, byte[] masterKey) {
        try {
            return storage.retrieve(fileId, masterKey);
        } catch (Exception e) {
            if (ipfsBlobStore != null) {
                return retrieveFromIPFS(fileId, masterKey);
            }
            throw e;
        }
    }

    public CompletableFuture<Void> announceBlob(String hash) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (PeerInfo peer : peers.values()) {
            if (peer.connection.isOpen()) {
                futures.add(peer.connection.send(createAnnounceMessage(hash)));
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<byte[]> requestBlob(String hash) {
        for (PeerInfo peer : peers.values()) {
            if (peer.connection.isOpen()) {
                return requestBlobFromPeer(peer, hash);
            }
        }
        return CompletableFuture.failedFuture(new IllegalStateException("No peers available"));
    }

    public List<TransportAddress> getLocalAddresses() {
        return transportManager.getLocalAddresses();
    }

    public Map<TransportType, TransportAddress> getAddressesByType() {
        Map<TransportType, TransportAddress> result = new EnumMap<>(TransportType.class);
        for (TransportAddress addr : transportManager.getLocalAddresses()) {
            result.put(addr.type(), addr);
        }
        return result;
    }

    public Collection<PeerInfo> getPeers() {
        return Collections.unmodifiableCollection(peers.values());
    }

    public int getPeerCount() {
        return peers.size();
    }

    public int getPeerCountByTransport(TransportType type) {
        return (int) peers.values().stream()
            .filter(p -> p.connection.getTransportType() == type)
            .count();
    }

    public TransportManager getTransportManager() {
        return transportManager;
    }

    public BlobStore getPrimaryBlobStore() {
        return primaryBlobStore;
    }

    public IPFSBlobStore getIpfsBlobStore() {
        return ipfsBlobStore;
    }

    public SupernodeStorage getStorage() {
        return storage;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public NetworkStats stats() {
        return new NetworkStats(
            transportManager.getStats(),
            storage.stats(),
            peers.size(),
            getAddressesByType()
        );
    }

    public void setOnListening(Consumer<ListeningEvent> handler) {
        this.onListening = handler;
    }

    public void setOnPeer(Consumer<PeerEvent> handler) {
        this.onPeer = handler;
    }

    public void setOnDisconnect(Consumer<DisconnectEvent> handler) {
        this.onDisconnect = handler;
    }

    public void setOnError(Consumer<Transport.TransportError> handler) {
        this.onError = handler;
    }

    private void setupEventHandlers() {
        transportManager.setOnConnection(conn -> {
            registerPeer(conn);
        });
        
        transportManager.setOnError(error -> {
            if (onError != null) {
                onError.accept(error);
            }
        });
    }

    private void registerPeer(TransportConnection conn) {
        String peerId = conn.getId();
        PeerInfo peer = new PeerInfo(peerId, conn);
        peers.put(peerId, peer);
        
        conn.setOnClose(v -> {
            peers.remove(peerId);
            if (onDisconnect != null) {
                onDisconnect.accept(new DisconnectEvent(peerId, conn.getTransportType()));
            }
        });
        
        conn.setOnMessage(data -> handleMessage(peer, data));
        
        if (onPeer != null) {
            onPeer.accept(new PeerEvent(peerId, conn.getTransportType(), conn.getRemoteAddress()));
        }
    }

    private void handleMessage(PeerInfo peer, byte[] data) {
        // Message handling logic - delegate to appropriate handler based on message type
    }

    private String createAnnounceMessage(String hash) {
        return "{\"type\":\"have\",\"hash\":\"" + hash + "\"}";
    }

    private CompletableFuture<byte[]> requestBlobFromPeer(PeerInfo peer, String hash) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        
        peer.pendingRequests.put(hash, future);
        peer.connection.send("{\"type\":\"request\",\"hash\":\"" + hash + "\"}")
            .exceptionally(e -> {
                peer.pendingRequests.remove(hash);
                future.completeExceptionally(e);
                return null;
            });
        
        return future;
    }

    private void replicateToIPFS(SupernodeStorage.IngestResult result) {
        if (ipfsBlobStore == null) return;
        
        for (String chunkHash : result.chunkHashes()) {
            primaryBlobStore.get(chunkHash).ifPresent(data -> {
                try {
                    ipfsBlobStore.put(chunkHash, data);
                } catch (Exception ignored) {}
            });
        }
    }

    private SupernodeStorage.RetrieveResult retrieveFromIPFS(String fileId, byte[] masterKey) {
        throw new UnsupportedOperationException("IPFS retrieval not yet implemented");
    }

    public static class PeerInfo {
        public final String peerId;
        public final TransportConnection connection;
        public final long connectedAt;
        public final Map<String, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();

        PeerInfo(String peerId, TransportConnection connection) {
            this.peerId = peerId;
            this.connection = connection;
            this.connectedAt = System.currentTimeMillis();
        }
    }

    public record ListeningEvent(TransportType type, TransportAddress address) {}
    public record PeerEvent(String peerId, TransportType type, TransportAddress address) {}
    public record DisconnectEvent(String peerId, TransportType type) {}
    
    public record NetworkStats(
        TransportManager.AggregateStats transport,
        SupernodeStorage.StorageStats storage,
        int peerCount,
        Map<TransportType, TransportAddress> addresses
    ) {}

    public static class UnifiedNetworkOptions {
        public BlobStore blobStore;
        public String isoSeed;
        public long isoSize = 256 * 1024 * 1024;
        public int chunkSize = 64 * 1024;
        public boolean enableErasure = false;
        public int dataShards = 4;
        public int parityShards = 2;
        
        public boolean enableClearnet = true;
        public int clearnetPort = 0;
        
        public boolean enableTor = true;
        public TorTransport.TorOptions torOptions = TorTransport.TorOptions.defaults();
        
        public boolean enableI2P = true;
        public I2PTransport.I2POptions i2pOptions = I2PTransport.I2POptions.defaults();
        
        public boolean enableHyphanet = true;
        public HyphanetTransport.HyphanetOptions hyphanetOptions = HyphanetTransport.HyphanetOptions.defaults();
        
        public boolean enableZeroNet = true;
        public ZeroNetTransport.ZeroNetOptions zeroNetOptions = ZeroNetTransport.ZeroNetOptions.defaults();
        
        public boolean enableIPFS = true;
        public IPFSTransport.IPFSOptions ipfsOptions = IPFSTransport.IPFSOptions.defaults();

        public UnifiedNetworkOptions() {}

        public static UnifiedNetworkOptions allNetworks() {
            return new UnifiedNetworkOptions();
        }

        public static UnifiedNetworkOptions clearnetOnly() {
            UnifiedNetworkOptions options = new UnifiedNetworkOptions();
            options.enableTor = false;
            options.enableI2P = false;
            options.enableHyphanet = false;
            options.enableZeroNet = false;
            options.enableIPFS = false;
            return options;
        }

        public static UnifiedNetworkOptions anonymousOnly() {
            UnifiedNetworkOptions options = new UnifiedNetworkOptions();
            options.enableClearnet = false;
            return options;
        }

        public static UnifiedNetworkOptions withErasure(int dataShards, int parityShards) {
            UnifiedNetworkOptions options = new UnifiedNetworkOptions();
            options.enableErasure = true;
            options.dataShards = dataShards;
            options.parityShards = parityShards;
            return options;
        }
    }
}
