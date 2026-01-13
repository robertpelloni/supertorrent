package io.supernode.network.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ZeroNetTransport implements Transport {

    private static final Pattern ZERONET_PATTERN = Pattern.compile(
        "^([13][a-km-zA-HJ-NP-Z1-9]{25,34})$"
    );
    
    private static final int DEFAULT_ZERONET_PORT = 15441;
    private static final int DEFAULT_TOR_SOCKS_PORT = 9050;
    private static final int MAX_PEERS_PER_SITE = 200;
    private static final int PEER_EXCHANGE_INTERVAL_SECONDS = 300;
    private static final int DHT_ANNOUNCE_INTERVAL_SECONDS = 1800;
    
    private final ZeroNetOptions options;
    private final ObjectMapper objectMapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentHashMap<String, ZeroNetConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<PeerInfo>> peersBysite = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SiteInfo> siteRegistry = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<PeerInfo> peerQueue;
    
    private String siteAddress;
    private KeyPair siteKeyPair;
    private TransportAddress localAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private volatile boolean available = false;
    private TorTransport torTransport;
    private Channel serverChannel;
    private ScheduledExecutorService scheduledExecutor;
    private TransportConfig config = TransportConfig.defaults();
    
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();
    private final AtomicLong peerExchanges = new AtomicLong();
    private final AtomicLong dhtAnnounces = new AtomicLong();
    private final AtomicLong contentSigned = new AtomicLong();
    private final AtomicLong contentVerified = new AtomicLong();
    private volatile Instant startTime;
    private volatile Instant lastHealthCheck;
    private volatile HealthState healthState = HealthState.STOPPED;
    private volatile String healthMessage = "Not started";
    private final AtomicInteger consecutiveHealthFailures = new AtomicInteger(0);
    private volatile long lastHealthLatencyMs = 0;

    public ZeroNetTransport() {
        this(ZeroNetOptions.defaults());
    }

    public ZeroNetTransport(ZeroNetOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper();
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.peerQueue = new PriorityBlockingQueue<>(100, 
            Comparator.comparingLong(PeerInfo::lastSeen).reversed()
                .thenComparingInt(PeerInfo::reputation).reversed());
        
        this.torTransport = new TorTransport(TorTransport.TorOptions.builder()
            .socksHost(options.torSocksHost)
            .socksPort(options.torSocksPort)
            .hiddenServicePort(options.localPort)
            .build()
        );
        
        checkZeroNetAvailability();
    }

    @Override
    public TransportType getType() {
        return TransportType.ZERONET;
    }

    @Override
    public String getName() {
        return "ZeroNet (BitTorrent-over-Tor)";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<TransportAddress> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!available) {
                    throw new IllegalStateException("Tor is required for ZeroNet but not available");
                }
                
                generateSiteAddress();
                
                if (torTransport.isAvailable()) {
                    torTransport.start().get(30, TimeUnit.SECONDS);
                }
                
                if (options.enableServer) {
                    startServer();
                }
                
                localAddress = TransportAddress.zeronet(siteAddress);
                running = true;
                startTime = Instant.now();
                healthState = HealthState.HEALTHY;
                healthMessage = "OK";
                
                startScheduledTasks();
                
                return localAddress;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        });
    }
    
    private void startServer() throws InterruptedException {
        ServerBootstrap serverBootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
                        .addLast(new LengthFieldPrepender(4))
                        .addLast(new ZeroNetServerHandler());
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true);
        
        serverChannel = serverBootstrap.bind(options.localPort).sync().channel();
    }
    
    private void startScheduledTasks() {
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zeronet-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        if (options.enablePeerExchange) {
            scheduledExecutor.scheduleAtFixedRate(
                this::performPeerExchange,
                PEER_EXCHANGE_INTERVAL_SECONDS,
                PEER_EXCHANGE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );
        }
        
        if (options.enableDhtAnnounce) {
            scheduledExecutor.scheduleAtFixedRate(
                this::performDhtAnnounce,
                60,
                DHT_ANNOUNCE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );
        }
        
        if (options.enableHealthCheck) {
            scheduledExecutor.scheduleAtFixedRate(
                () -> performHealthCheck().exceptionally(e -> false),
                config.healthCheckInterval().toMillis(),
                config.healthCheckInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            running = false;
            healthState = HealthState.STOPPED;
            healthMessage = "Stopped";
            
            if (scheduledExecutor != null) {
                scheduledExecutor.shutdown();
                try {
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduledExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduledExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            connections.values().forEach(conn -> conn.close().join());
            connections.clear();
            
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            
            if (torTransport != null && torTransport.isRunning()) {
                torTransport.stop().join();
            }
            
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        });
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions options) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        if (address.type() != TransportType.ZERONET) {
            future.completeExceptionally(new IllegalArgumentException("Not a ZeroNet address: " + address));
            return future;
        }
        
        Set<PeerInfo> peers = peersBysite.get(address.host());
        if (peers == null || peers.isEmpty()) {
            future.completeExceptionally(new IllegalStateException(
                "No peer info for site: " + address.host() + ". Use discoverPeers() first."
            ));
            return future;
        }
        
        PeerInfo peerInfo = selectBestPeer(peers);
        if (peerInfo == null) {
            future.completeExceptionally(new IllegalStateException("No available peers for site: " + address.host()));
            return future;
        }
        
        connectToPeer(peerInfo, address, options, future);
        return future;
    }
    
    private PeerInfo selectBestPeer(Set<PeerInfo> peers) {
        return peers.stream()
            .filter(p -> System.currentTimeMillis() - p.lastSeen < Duration.ofHours(1).toMillis())
            .max(Comparator.comparingInt(PeerInfo::reputation)
                .thenComparingLong(PeerInfo::lastSeen))
            .orElse(peers.iterator().hasNext() ? peers.iterator().next() : null);
    }

    @Override
    public TransportAddress parseAddress(String address) {
        if (ZERONET_PATTERN.matcher(address).matches()) {
            return TransportAddress.zeronet(address);
        }
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        return ZERONET_PATTERN.matcher(address).matches();
    }

    @Override
    public TransportAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public void setOnConnection(Consumer<TransportConnection> handler) {
        this.onConnection = handler;
    }

    @Override
    public void setOnError(Consumer<TransportError> handler) {
        this.onError = handler;
    }

    @Override
    public TransportStats getStats() {
        Duration uptime = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
        return new TransportStats(
            connectionsIn.get(),
            connectionsOut.get(),
            bytesReceived.get(),
            bytesSent.get(),
            messagesReceived.get(),
            messagesSent.get(),
            errors.get(),
            reconnections.get(),
            healthCheckFailures.get(),
            uptime
        );
    }
    
    @Override
    public HealthStatus getHealthStatus() {
        return new HealthStatus(
            healthState,
            healthMessage,
            lastHealthCheck != null ? lastHealthCheck : Instant.now(),
            consecutiveHealthFailures.get(),
            lastHealthLatencyMs
        );
    }
    
    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return performHealthCheck();
    }
    
    @Override
    public void configure(TransportConfig config) {
        this.config = config;
    }
    
    @Override
    public TransportConfig getConfig() {
        return config;
    }
    
    public ZeroNetStats getZeroNetStats() {
        int totalPeers = peersBysite.values().stream().mapToInt(Set::size).sum();
        return new ZeroNetStats(
            peerExchanges.get(),
            dhtAnnounces.get(),
            contentSigned.get(),
            contentVerified.get(),
            totalPeers,
            siteRegistry.size(),
            connections.size()
        );
    }

    public String getSiteAddress() {
        return siteAddress;
    }
    
    public KeyPair getSiteKeyPair() {
        return siteKeyPair;
    }

    public void registerPeer(String siteAddress, String onionAddress, int port) {
        registerPeer(siteAddress, onionAddress, port, true, 50);
    }

    public void registerPeer(String siteAddress, String host, int port, boolean isTor) {
        registerPeer(siteAddress, host, port, isTor, 50);
    }
    
    public void registerPeer(String siteAddress, String host, int port, boolean isTor, int reputation) {
        PeerInfo peer = new PeerInfo(siteAddress, host, port, isTor, System.currentTimeMillis(), reputation);
        peersBysite.computeIfAbsent(siteAddress, k -> ConcurrentHashMap.newKeySet())
            .add(peer);
        peerQueue.offer(peer);
        
        Set<PeerInfo> peers = peersBysite.get(siteAddress);
        if (peers.size() > MAX_PEERS_PER_SITE) {
            PeerInfo oldest = peers.stream()
                .min(Comparator.comparingLong(PeerInfo::lastSeen))
                .orElse(null);
            if (oldest != null) {
                peers.remove(oldest);
            }
        }
    }

    public CompletableFuture<List<PeerInfo>> discoverPeers(String siteAddress) {
        return discoverPeers(siteAddress, options.maxPeersToDiscover);
    }
    
    public CompletableFuture<List<PeerInfo>> discoverPeers(String siteAddress, int maxPeers) {
        return CompletableFuture.supplyAsync(() -> {
            List<PeerInfo> discovered = new ArrayList<>();
            
            Set<PeerInfo> existing = peersBysite.get(siteAddress);
            if (existing != null) {
                discovered.addAll(existing);
            }
            
            if (options.enableDhtDiscovery && discovered.size() < maxPeers) {
                List<PeerInfo> dhtPeers = queryDhtForPeers(siteAddress, maxPeers - discovered.size());
                for (PeerInfo peer : dhtPeers) {
                    if (!discovered.contains(peer)) {
                        discovered.add(peer);
                        registerPeer(peer.siteAddress, peer.host, peer.port, peer.isTor, peer.reputation);
                    }
                }
            }
            
            if (options.enablePeerExchange && discovered.size() < maxPeers && !connections.isEmpty()) {
                for (ZeroNetConnection conn : connections.values()) {
                    if (discovered.size() >= maxPeers) break;
                    try {
                        List<PeerInfo> exchanged = requestPeersFromConnection(conn, siteAddress).get(10, TimeUnit.SECONDS);
                        for (PeerInfo peer : exchanged) {
                            if (!discovered.contains(peer) && discovered.size() < maxPeers) {
                                discovered.add(peer);
                                registerPeer(peer.siteAddress, peer.host, peer.port, peer.isTor, peer.reputation);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }
            
            return discovered;
        });
    }
    
    private List<PeerInfo> queryDhtForPeers(String siteAddress, int maxPeers) {
        List<PeerInfo> peers = new ArrayList<>();
        
        if (torTransport != null && torTransport.isRunning()) {
            try {
                byte[] infoHash = computeInfoHash(siteAddress);
                dhtAnnounces.incrementAndGet();
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }
        
        return peers;
    }
    
    private byte[] computeInfoHash(String siteAddress) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return sha1.digest(siteAddress.getBytes(StandardCharsets.UTF_8));
    }
    
    private CompletableFuture<List<PeerInfo>> requestPeersFromConnection(ZeroNetConnection conn, String siteAddress) {
        CompletableFuture<List<PeerInfo>> future = new CompletableFuture<>();
        
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("cmd", "pex");
            request.put("req_id", conn.nextReqId());
            
            ObjectNode params = request.putObject("params");
            params.put("site", siteAddress);
            params.put("need", options.maxPeersToDiscover);
            
            conn.sendRequest(request).thenAccept(response -> {
                List<PeerInfo> peers = new ArrayList<>();
                JsonNode peersNode = response.get("peers");
                if (peersNode != null && peersNode.isArray()) {
                    for (JsonNode peerNode : peersNode) {
                        String packed = peerNode.asText();
                        PeerInfo peer = unpackPeer(siteAddress, packed);
                        if (peer != null) {
                            peers.add(peer);
                        }
                    }
                }
                peerExchanges.incrementAndGet();
                future.complete(peers);
            }).exceptionally(e -> {
                future.completeExceptionally(e);
                return null;
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    private PeerInfo unpackPeer(String siteAddress, String packed) {
        try {
            byte[] bytes = Base64.getDecoder().decode(packed);
            if (bytes.length == 6) {
                String ip = String.format("%d.%d.%d.%d", 
                    bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF);
                int port = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
                return new PeerInfo(siteAddress, ip, port, false, System.currentTimeMillis(), 50);
            } else if (bytes.length > 6) {
                String onion = new String(bytes, 0, bytes.length - 2, StandardCharsets.UTF_8);
                int port = ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
                return new PeerInfo(siteAddress, onion, port, true, System.currentTimeMillis(), 50);
            }
        } catch (Exception e) {
            errors.incrementAndGet();
        }
        return null;
    }
    
    private String packPeer(PeerInfo peer) {
        try {
            if (peer.isTor) {
                byte[] onionBytes = peer.host.getBytes(StandardCharsets.UTF_8);
                byte[] result = new byte[onionBytes.length + 2];
                System.arraycopy(onionBytes, 0, result, 0, onionBytes.length);
                result[result.length - 2] = (byte) ((peer.port >> 8) & 0xFF);
                result[result.length - 1] = (byte) (peer.port & 0xFF);
                return Base64.getEncoder().encodeToString(result);
            } else {
                String[] parts = peer.host.split("\\.");
                if (parts.length == 4) {
                    byte[] result = new byte[6];
                    for (int i = 0; i < 4; i++) {
                        result[i] = (byte) Integer.parseInt(parts[i]);
                    }
                    result[4] = (byte) ((peer.port >> 8) & 0xFF);
                    result[5] = (byte) (peer.port & 0xFF);
                    return Base64.getEncoder().encodeToString(result);
                }
            }
        } catch (Exception e) {
            errors.incrementAndGet();
        }
        return null;
    }
    
    private void performPeerExchange() {
        if (!running || connections.isEmpty()) return;
        
        for (ZeroNetConnection conn : connections.values()) {
            try {
                String remoteSite = conn.getRemoteAddress().host();
                Set<PeerInfo> ourPeers = peersBysite.get(remoteSite);
                if (ourPeers == null || ourPeers.isEmpty()) continue;
                
                ObjectNode request = objectMapper.createObjectNode();
                request.put("cmd", "pex");
                request.put("req_id", conn.nextReqId());
                
                ObjectNode params = request.putObject("params");
                params.put("site", remoteSite);
                params.put("need", 10);
                
                ArrayNode peersArray = params.putArray("peers");
                int count = 0;
                for (PeerInfo peer : ourPeers) {
                    if (count >= 10) break;
                    String packed = packPeer(peer);
                    if (packed != null) {
                        peersArray.add(packed);
                        count++;
                    }
                }
                
                conn.sendRequest(request).thenAccept(response -> {
                    peerExchanges.incrementAndGet();
                    JsonNode peersNode = response.get("peers");
                    if (peersNode != null && peersNode.isArray()) {
                        for (JsonNode peerNode : peersNode) {
                            PeerInfo peer = unpackPeer(remoteSite, peerNode.asText());
                            if (peer != null) {
                                registerPeer(peer.siteAddress, peer.host, peer.port, peer.isTor, peer.reputation);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }
    }
    
    private void performDhtAnnounce() {
        if (!running || siteAddress == null) return;
        
        try {
            byte[] infoHash = computeInfoHash(siteAddress);
            dhtAnnounces.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }
    
    private CompletableFuture<Boolean> performHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            long startTimeMs = System.currentTimeMillis();
            lastHealthCheck = Instant.now();
            
            try {
                boolean torHealthy = !options.requireTor || (torTransport != null && torTransport.isRunning());
                boolean serverHealthy = !options.enableServer || (serverChannel != null && serverChannel.isActive());
                
                if (torHealthy && serverHealthy) {
                    lastHealthLatencyMs = System.currentTimeMillis() - startTimeMs;
                    consecutiveHealthFailures.set(0);
                    healthState = HealthState.HEALTHY;
                    healthMessage = "OK";
                    return true;
                }
                
                throw new IllegalStateException("Components unhealthy - Tor: " + torHealthy + ", Server: " + serverHealthy);
                
            } catch (Exception e) {
                lastHealthLatencyMs = System.currentTimeMillis() - startTimeMs;
                int failures = consecutiveHealthFailures.incrementAndGet();
                healthCheckFailures.incrementAndGet();
                
                if (failures >= config.healthCheckFailureThreshold()) {
                    healthState = HealthState.UNHEALTHY;
                    healthMessage = "Health check failed: " + e.getMessage();
                } else {
                    healthState = HealthState.DEGRADED;
                    healthMessage = "Health check failed (" + failures + "/" + 
                                   config.healthCheckFailureThreshold() + "): " + e.getMessage();
                }
                return false;
            }
        });
    }
    
    public CompletableFuture<SignedContent> signContent(byte[] content, String innerPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(siteKeyPair.getPrivate());
                
                MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
                byte[] contentHash = sha512.digest(content);
                
                signature.update(contentHash);
                byte[] sig = signature.sign();
                
                long timestamp = System.currentTimeMillis() / 1000;
                
                contentSigned.incrementAndGet();
                return new SignedContent(content, sig, innerPath, timestamp, bytesToHex(contentHash));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }
    
    public CompletableFuture<Boolean> verifyContent(SignedContent signedContent, PublicKey publicKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initVerify(publicKey);
                
                MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
                byte[] contentHash = sha512.digest(signedContent.content);
                
                signature.update(contentHash);
                boolean valid = signature.verify(signedContent.signature);
                
                if (valid) {
                    contentVerified.incrementAndGet();
                }
                return valid;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }
    
    public void registerSite(String address, PublicKey publicKey, String title) {
        siteRegistry.put(address, new SiteInfo(address, publicKey, title, System.currentTimeMillis()));
    }
    
    public SiteInfo getSiteInfo(String address) {
        return siteRegistry.get(address);
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void checkZeroNetAvailability() {
        available = torTransport.isAvailable() || !options.requireTor;
    }

    private void generateSiteAddress() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        siteKeyPair = keyGen.generateKeyPair();
        
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest ripemd160 = MessageDigest.getInstance("RIPEMD160");
        
        byte[] pubKeyHash = sha256.digest(siteKeyPair.getPublic().getEncoded());
        byte[] addressHash = ripemd160.digest(pubKeyHash);
        
        byte[] addressBytes = new byte[21];
        addressBytes[0] = 0x00;
        System.arraycopy(addressHash, 0, addressBytes, 1, 20);
        
        byte[] checksum = sha256.digest(sha256.digest(addressBytes));
        byte[] fullAddress = new byte[25];
        System.arraycopy(addressBytes, 0, fullAddress, 0, 21);
        System.arraycopy(checksum, 0, fullAddress, 21, 4);
        
        siteAddress = base58Encode(fullAddress);
    }

    private String base58Encode(byte[] input) {
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        java.math.BigInteger value = new java.math.BigInteger(1, input);
        StringBuilder result = new StringBuilder();
        
        while (value.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger[] divRem = value.divideAndRemainder(java.math.BigInteger.valueOf(58));
            result.insert(0, alphabet.charAt(divRem[1].intValue()));
            value = divRem[0];
        }
        
        for (byte b : input) {
            if (b == 0) result.insert(0, '1');
            else break;
        }
        
        return result.toString();
    }

    private void connectToPeer(PeerInfo peerInfo, TransportAddress targetAddress,
                               ConnectionOptions connOptions,
                               CompletableFuture<TransportConnection> future) {
        try {
            Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connOptions.connectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (peerInfo.isTor) {
                            ch.pipeline().addFirst(new Socks5ProxyHandler(
                                new InetSocketAddress(options.torSocksHost, options.torSocksPort)
                            ));
                        }
                        
                        ch.pipeline()
                            .addLast(new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
                            .addLast(new LengthFieldPrepender(4))
                            .addLast(new ZeroNetClientHandler(future, targetAddress, peerInfo, connOptions));
                    }
                });
            
            bootstrap.connect(peerInfo.host, peerInfo.port).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    errors.incrementAndGet();
                    updatePeerReputation(peerInfo, -10);
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            errors.incrementAndGet();
            future.completeExceptionally(e);
        }
    }
    
    private void updatePeerReputation(PeerInfo peer, int delta) {
        Set<PeerInfo> peers = peersBysite.get(peer.siteAddress);
        if (peers != null) {
            peers.remove(peer);
            int newReputation = Math.max(0, Math.min(100, peer.reputation + delta));
            PeerInfo updated = new PeerInfo(peer.siteAddress, peer.host, peer.port, 
                peer.isTor, System.currentTimeMillis(), newReputation);
            peers.add(updated);
        }
    }
    
    private class ZeroNetServerHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private ZeroNetConnection connection;
        private boolean handshakeComplete = false;
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            connectionsIn.incrementAndGet();
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            bytesReceived.addAndGet(data.length);
            
            JsonNode json = objectMapper.readTree(data);
            
            if (!handshakeComplete) {
                if (json.has("cmd") && "handshake".equals(json.get("cmd").asText())) {
                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("cmd", "response");
                    response.put("to", json.get("req_id").asInt());
                    response.put("protocol", "v2");
                    response.put("rev", 4500);
                    response.put("peer_id", siteAddress);
                    response.put("fileserver_port", options.localPort);
                    response.put("target_ip", "");
                    response.put("version", "0.7.2");
                    
                    byte[] respData = objectMapper.writeValueAsBytes(response);
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(respData));
                    bytesSent.addAndGet(respData.length);
                    
                    handshakeComplete = true;
                    
                    String peerId = json.path("params").path("peer_id").asText("");
                    String id = UUID.randomUUID().toString();
                    TransportAddress remoteAddr = TransportAddress.zeronet(peerId);
                    
                    connection = new ZeroNetConnection(
                        id, ctx.channel(), remoteAddr, localAddress,
                        bytesReceived, bytesSent, messagesReceived, messagesSent,
                        objectMapper, ConnectionOptions.defaults()
                    );
                    connections.put(id, connection);
                    
                    if (onConnection != null) {
                        onConnection.accept(connection);
                    }
                }
            } else {
                connection.handleMessage(data);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (connection != null) {
                connections.remove(connection.getId());
                connection.handleClose();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            errors.incrementAndGet();
            if (onError != null) {
                onError.accept(new TransportError(TransportType.ZERONET, cause.getMessage(), cause));
            }
            ctx.close();
        }
    }

    private class ZeroNetClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final CompletableFuture<TransportConnection> future;
        private final TransportAddress targetAddress;
        private final PeerInfo peerInfo;
        private final ConnectionOptions connOptions;
        private ZeroNetConnection connection;
        private boolean handshakeComplete = false;

        ZeroNetClientHandler(CompletableFuture<TransportConnection> future, 
                            TransportAddress targetAddress, PeerInfo peerInfo,
                            ConnectionOptions connOptions) {
            this.future = future;
            this.targetAddress = targetAddress;
            this.peerInfo = peerInfo;
            this.connOptions = connOptions;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ObjectNode handshake = objectMapper.createObjectNode();
            handshake.put("cmd", "handshake");
            handshake.put("req_id", 0);
            
            ObjectNode params = handshake.putObject("params");
            params.put("crypt", "");
            params.set("crypt_supported", objectMapper.createArrayNode());
            params.put("fileserver_port", options.localPort);
            params.put("protocol", "v2");
            params.put("port_opened", true);
            params.put("peer_id", siteAddress);
            params.put("rev", 4500);
            params.put("target_ip", "");
            params.put("version", "0.7.2");
            
            byte[] data = objectMapper.writeValueAsBytes(handshake);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(data));
            bytesSent.addAndGet(data.length);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            bytesReceived.addAndGet(data.length);
            
            JsonNode json = objectMapper.readTree(data);
            
            if (!handshakeComplete) {
                if (json.has("cmd") && "response".equals(json.get("cmd").asText())) {
                    handshakeComplete = true;
                    
                    String id = UUID.randomUUID().toString();
                    connection = new ZeroNetConnection(
                        id, ctx.channel(), targetAddress, localAddress,
                        bytesReceived, bytesSent, messagesReceived, messagesSent,
                        objectMapper, connOptions
                    );
                    connections.put(id, connection);
                    connectionsOut.incrementAndGet();
                    updatePeerReputation(peerInfo, 5);
                    future.complete(connection);
                }
            } else {
                connection.handleMessage(data);
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (connection != null) {
                connections.remove(connection.getId());
                connection.handleClose();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            errors.incrementAndGet();
            updatePeerReputation(peerInfo, -10);
            if (!future.isDone()) {
                future.completeExceptionally(cause);
            }
            if (onError != null) {
                onError.accept(new TransportError(TransportType.ZERONET, cause.getMessage(), cause));
            }
            ctx.close();
        }
    }

    private static class ZeroNetConnection implements TransportConnection {
        private final String id;
        private final Channel channel;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong framesIn = new AtomicLong();
        private final AtomicLong framesOut = new AtomicLong();
        private final AtomicLong pingsIn = new AtomicLong();
        private final AtomicLong pingsOut = new AtomicLong();
        private final ObjectMapper objectMapper;
        private final ConnectionOptions options;
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile ConnectionState state = ConnectionState.OPEN;
        private volatile long latencyMs = 0;
        private final AtomicInteger reqIdCounter = new AtomicInteger(1);
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<Throwable> onError;

        ZeroNetConnection(String id, Channel channel,
                         TransportAddress remoteAddress, TransportAddress localAddress,
                         AtomicLong bytesReceived, AtomicLong bytesSent,
                         AtomicLong messagesReceived, AtomicLong messagesSent,
                         ObjectMapper objectMapper, ConnectionOptions options) {
            this.id = id;
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.objectMapper = objectMapper;
            this.options = options;
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = connectedAt;
        }

        @Override
        public String getId() { return id; }

        @Override
        public TransportType getTransportType() { return TransportType.ZERONET; }

        @Override
        public TransportAddress getRemoteAddress() { return remoteAddress; }

        @Override
        public TransportAddress getLocalAddress() { return localAddress; }

        @Override
        public boolean isOpen() { return channel.isOpen() && state == ConnectionState.OPEN; }
        
        @Override
        public ConnectionState getState() { return state; }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                ObjectNode message = objectMapper.createObjectNode();
                message.put("cmd", "update");
                message.put("req_id", nextReqId());
                
                ObjectNode params = message.putObject("params");
                params.put("site", remoteAddress.host());
                params.put("data", Base64.getEncoder().encodeToString(data));
                
                byte[] msgBytes = objectMapper.writeValueAsBytes(message);
                channel.writeAndFlush(Unpooled.wrappedBuffer(msgBytes)).addListener(f -> {
                    if (f.isSuccess()) {
                        bytesSent.addAndGet(msgBytes.length);
                        messagesSent.incrementAndGet();
                        framesOut.incrementAndGet();
                        lastActivityAt = System.currentTimeMillis();
                        future.complete(null);
                    } else {
                        future.completeExceptionally(f.cause());
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        @Override
        public CompletableFuture<Void> send(String message) {
            return send(message.getBytes(StandardCharsets.UTF_8));
        }
        
        @Override
        public CompletableFuture<Void> sendFrame(Frame frame) {
            return send(frame.encode());
        }

        @Override
        public CompletableFuture<Void> close() {
            return CompletableFuture.runAsync(() -> {
                state = ConnectionState.CLOSING;
                channel.close().syncUninterruptibly();
                state = ConnectionState.CLOSED;
            });
        }
        
        @Override
        public CompletableFuture<Duration> ping() {
            long start = System.currentTimeMillis();
            pingsOut.incrementAndGet();
            
            CompletableFuture<Duration> future = new CompletableFuture<>();
            try {
                ObjectNode pingRequest = objectMapper.createObjectNode();
                pingRequest.put("cmd", "ping");
                pingRequest.put("req_id", nextReqId());
                
                sendRequest(pingRequest).thenAccept(response -> {
                    long elapsed = System.currentTimeMillis() - start;
                    latencyMs = elapsed;
                    pingsIn.incrementAndGet();
                    future.complete(Duration.ofMillis(elapsed));
                }).exceptionally(e -> {
                    future.completeExceptionally(e);
                    return null;
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }
        
        @Override
        public void pause() {
            channel.config().setAutoRead(false);
        }
        
        @Override
        public void resume() {
            channel.config().setAutoRead(true);
        }
        
        @Override
        public BackpressureStatus getBackpressureStatus() {
            long pending = channel.unsafe().outboundBuffer() != null ? 
                channel.unsafe().outboundBuffer().totalPendingWriteBytes() : 0;
            return new BackpressureStatus(pending, channel.bytesBeforeUnwritable(), !channel.isWritable());
        }

        @Override
        public void setOnMessage(Consumer<byte[]> handler) { this.onMessage = handler; }

        @Override
        public void setOnClose(Consumer<Void> handler) { this.onClose = handler; }

        @Override
        public void setOnError(Consumer<Throwable> handler) { this.onError = handler; }

        @Override
        public ConnectionStats getStats() {
            return new ConnectionStats(
                bytesReceived.get(), bytesSent.get(),
                messagesReceived.get(), messagesSent.get(),
                connectedAt, lastActivityAt,
                framesIn.get(), framesOut.get(),
                pingsOut.get(), pingsIn.get(),
                latencyMs, 0, 0, 0
            );
        }
        
        int nextReqId() {
            return reqIdCounter.getAndIncrement();
        }
        
        CompletableFuture<JsonNode> sendRequest(ObjectNode request) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            int reqId = request.get("req_id").asInt();
            pendingRequests.put(reqId, future);
            
            try {
                byte[] data = objectMapper.writeValueAsBytes(request);
                channel.writeAndFlush(Unpooled.wrappedBuffer(data)).addListener(f -> {
                    if (!f.isSuccess()) {
                        pendingRequests.remove(reqId);
                        future.completeExceptionally(f.cause());
                    } else {
                        bytesSent.addAndGet(data.length);
                        messagesSent.incrementAndGet();
                    }
                });
            } catch (Exception e) {
                pendingRequests.remove(reqId);
                future.completeExceptionally(e);
            }
            
            return future;
        }

        void handleMessage(byte[] data) {
            messagesReceived.incrementAndGet();
            framesIn.incrementAndGet();
            lastActivityAt = System.currentTimeMillis();
            
            try {
                JsonNode json = objectMapper.readTree(data);
                
                if (json.has("to")) {
                    int toId = json.get("to").asInt();
                    CompletableFuture<JsonNode> pending = pendingRequests.remove(toId);
                    if (pending != null) {
                        pending.complete(json);
                        return;
                    }
                }
                
                if (json.has("params") && json.path("params").has("data")) {
                    String encodedData = json.path("params").path("data").asText();
                    byte[] decoded = Base64.getDecoder().decode(encodedData);
                    if (onMessage != null) onMessage.accept(decoded);
                } else {
                    if (onMessage != null) onMessage.accept(data);
                }
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        }

        void handleClose() {
            state = ConnectionState.CLOSED;
            pendingRequests.values().forEach(f -> 
                f.completeExceptionally(new IOException("Connection closed")));
            pendingRequests.clear();
            if (onClose != null) onClose.accept(null);
        }
    }

    public record PeerInfo(
        String siteAddress, 
        String host, 
        int port, 
        boolean isTor,
        long lastSeen,
        int reputation
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PeerInfo other)) return false;
            return port == other.port && 
                   Objects.equals(host, other.host) && 
                   Objects.equals(siteAddress, other.siteAddress);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(siteAddress, host, port);
        }
    }
    
    public record SiteInfo(String address, PublicKey publicKey, String title, long registeredAt) {}
    
    public record SignedContent(byte[] content, byte[] signature, String innerPath, long timestamp, String hash) {}
    
    public record ZeroNetStats(
        long peerExchanges,
        long dhtAnnounces,
        long contentSigned,
        long contentVerified,
        int totalKnownPeers,
        int registeredSites,
        int activeConnections
    ) {}

    public record ZeroNetOptions(
        String torSocksHost,
        int torSocksPort,
        int localPort,
        boolean requireTor,
        boolean enableServer,
        boolean enablePeerExchange,
        boolean enableDhtDiscovery,
        boolean enableDhtAnnounce,
        boolean enableHealthCheck,
        int maxPeersToDiscover
    ) {
        public static ZeroNetOptions defaults() {
            return new ZeroNetOptions(
                "127.0.0.1",
                DEFAULT_TOR_SOCKS_PORT,
                DEFAULT_ZERONET_PORT,
                true,
                true,
                true,
                true,
                true,
                true,
                50
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String torSocksHost = "127.0.0.1";
            private int torSocksPort = DEFAULT_TOR_SOCKS_PORT;
            private int localPort = DEFAULT_ZERONET_PORT;
            private boolean requireTor = true;
            private boolean enableServer = true;
            private boolean enablePeerExchange = true;
            private boolean enableDhtDiscovery = true;
            private boolean enableDhtAnnounce = true;
            private boolean enableHealthCheck = true;
            private int maxPeersToDiscover = 50;
            
            public Builder torSocksHost(String host) { this.torSocksHost = host; return this; }
            public Builder torSocksPort(int port) { this.torSocksPort = port; return this; }
            public Builder localPort(int port) { this.localPort = port; return this; }
            public Builder requireTor(boolean require) { this.requireTor = require; return this; }
            public Builder enableServer(boolean enable) { this.enableServer = enable; return this; }
            public Builder enablePeerExchange(boolean enable) { this.enablePeerExchange = enable; return this; }
            public Builder enableDhtDiscovery(boolean enable) { this.enableDhtDiscovery = enable; return this; }
            public Builder enableDhtAnnounce(boolean enable) { this.enableDhtAnnounce = enable; return this; }
            public Builder enableHealthCheck(boolean enable) { this.enableHealthCheck = enable; return this; }
            public Builder maxPeersToDiscover(int max) { this.maxPeersToDiscover = max; return this; }
            
            public ZeroNetOptions build() {
                return new ZeroNetOptions(
                    torSocksHost, torSocksPort, localPort, requireTor, enableServer,
                    enablePeerExchange, enableDhtDiscovery, enableDhtAnnounce,
                    enableHealthCheck, maxPeersToDiscover
                );
            }
        }
    }
}
