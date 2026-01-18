package io.supernode.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.supernode.network.transport.Transport;
import io.supernode.network.transport.TransportAddress;
import io.supernode.network.transport.TransportType;
import io.supernode.storage.BlobStore;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * P2P network layer for blob exchange using WebSockets over Netty.
 */
public class BlobNetwork {
    
    private static final int MAX_FRAME_SIZE = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private final BlobStore blobStore;
    private final String peerId;
    private final BlobNetworkOptions options;
    
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    
    private Channel serverChannel;
    private int port;
    
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> blobToPeers = new ConcurrentHashMap<>();
    private final Set<String> announcedBlobs = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<byte[]>> pendingRequests = new ConcurrentHashMap<>();
    
    private final Map<String, PeerHealth> peerHealth = new ConcurrentHashMap<>();
    private final AtomicLong totalBytesUploaded = new AtomicLong();
    private final AtomicLong totalBytesDownloaded = new AtomicLong();
    private final AtomicLong totalRequestsSent = new AtomicLong();
    private final AtomicLong totalRequestsReceived = new AtomicLong();
    private final AtomicLong successfulTransfers = new AtomicLong();
    private final AtomicLong failedTransfers = new AtomicLong();
    
    private volatile boolean destroyed = false;
    private volatile Transport.HealthState healthState = Transport.HealthState.STOPPED;
    private volatile Instant lastHealthCheck = Instant.now();
    
    private Consumer<ListeningEvent> onListening;
    private Consumer<PeerConnectedEvent> onPeer;
    private Consumer<PeerDisconnectedEvent> onDisconnect;
    private Consumer<UploadEvent> onUpload;
    private Consumer<DownloadEvent> onDownload;
    private Consumer<HaveEvent> onHave;
    private Consumer<Void> onDestroyed;
    private Consumer<HealthChangeEvent> onHealthChange;
    
    public BlobNetwork(BlobStore blobStore) {
        this(blobStore, BlobNetworkOptions.defaults());
    }
    
    public BlobNetwork(BlobStore blobStore, BlobNetworkOptions options) {
        this.blobStore = blobStore;
        this.options = options;
        this.peerId = options.peerId != null ? options.peerId : generatePeerId();
        this.port = options.port;
    }
    
    public CompletableFuture<Integer> listen(int port) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new WebSocketServerProtocolHandler("/", null, true, MAX_FRAME_SIZE));
                        pipeline.addLast(new BlobNetworkServerHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture bindFuture = bootstrap.bind(port);
            bindFuture.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    serverChannel = f.channel();
                    InetSocketAddress addr = (InetSocketAddress) serverChannel.localAddress();
                    this.port = addr.getPort();
                    updateHealthState(Transport.HealthState.HEALTHY);
                    
                    if (onListening != null) {
                        onListening.accept(new ListeningEvent(this.port));
                    }
                    future.complete(this.port);
                } else {
                    updateHealthState(Transport.HealthState.UNHEALTHY);
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            updateHealthState(Transport.HealthState.UNHEALTHY);
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    public CompletableFuture<PeerConnection> connect(String address) {
        return connect(address, null);
    }
    
    public CompletableFuture<PeerConnection> connect(String address, ConnectionOptions connOptions) {
        if (destroyed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Network destroyed"));
        }
        
        if (peers.size() >= options.maxConnections) {
            return CompletableFuture.failedFuture(new IllegalStateException("Max connections reached"));
        }
        
        ConnectionOptions opts = connOptions != null ? connOptions : ConnectionOptions.defaults();
        CompletableFuture<PeerConnection> future = new CompletableFuture<>();
        
        try {
            URI uri = new URI(address);
            String host = uri.getHost();
            int port = uri.getPort() != -1 ? uri.getPort() : 80;
            boolean ssl = "wss".equalsIgnoreCase(uri.getScheme());
            
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) opts.connectTimeout.toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        
                        if (ssl) {
                            SslContext sslCtx = SslContextBuilder.forClient().build();
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }
                        
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        
                        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                            uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders(), MAX_FRAME_SIZE);
                        
                        pipeline.addLast(new WebSocketClientProtocolHandler(handshaker));
                        pipeline.addLast(new BlobNetworkClientHandler(address, future));
                    }
                });
            
            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    future.completeExceptionally(f.cause());
                }
            });
            
            if (opts.connectTimeout.toMillis() > 0) {
                CompletableFuture.delayedExecutor(opts.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!future.isDone()) {
                            future.completeExceptionally(new TimeoutException("Connection timeout"));
                        }
                    });
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    public CompletableFuture<PeerConnection> connectViaTransport(TransportAddress address) {
        String host = "localhost".equals(address.host()) ? "127.0.0.1" : address.host();
        String wsAddress = switch (address.type()) {
            case CLEARNET -> "ws://" + host + ":" + address.port();
            case TOR -> "ws://" + host;
            case I2P -> "ws://" + host;
            default -> throw new IllegalArgumentException("Unsupported transport: " + address.type());
        };
        return connect(wsAddress);
    }
    
    public void announceBlob(String blobId) {
        if (blobId == null) return;
        announcedBlobs.add(blobId);
        Message haveMsg = new Message("have", Map.of("blobId", blobId));
        broadcastMessage(haveMsg);
    }
    
    public void queryBlob(String blobId) {
        if (blobId == null) return;
        Message queryMsg = new Message("query", Map.of("blobId", blobId));
        broadcastMessage(queryMsg);
        totalRequestsSent.incrementAndGet();
    }
    
    public CompletableFuture<byte[]> requestBlob(String blobId) {
        List<PeerConnection> peersWithBlob = findPeersWithBlob(blobId);
        if (peersWithBlob.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No peers have blob: " + blobId));
        }
        return requestBlob(blobId, peersWithBlob);
    }
    
    public CompletableFuture<byte[]> requestBlob(String blobId, List<PeerConnection> targetPeers) {
        if (blobId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("blobId is null"));
        
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingRequests.put(blobId, future);
        totalRequestsSent.incrementAndGet();
        
        List<PeerConnection> sortedPeers = selectPeersByHealth(targetPeers);
        
        for (PeerConnection peer : sortedPeers) {
            if (peer.channel().isActive()) {
                Message requestMsg = new Message("request", Map.of("blobId", blobId));
                sendMessage(peer.channel(), requestMsg);
                recordRequestSent(peer.peerId());
                break;
            }
        }
        
        long timeout = options.requestTimeout.toMillis();
        CompletableFuture.delayedExecutor(timeout, TimeUnit.MILLISECONDS).execute(() -> {
            if (!future.isDone()) {
                pendingRequests.remove(blobId);
                failedTransfers.incrementAndGet();
                future.completeExceptionally(new TimeoutException("Blob request timeout: " + blobId));
            }
        });
        
        return future;
    }
    
    public CompletableFuture<byte[]> requestBlobWithRetry(String blobId, int maxRetries) {
        return requestBlobWithRetryInternal(blobId, maxRetries, 0);
    }
    
    private CompletableFuture<byte[]> requestBlobWithRetryInternal(String blobId, int maxRetries, int attempt) {
        return requestBlob(blobId).exceptionallyCompose(ex -> {
            if (attempt < maxRetries) {
                long delay = (long) Math.pow(2, attempt) * 1000;
                return CompletableFuture.supplyAsync(() -> null, 
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                    .thenCompose(v -> requestBlobWithRetryInternal(blobId, maxRetries, attempt + 1));
            }
            return CompletableFuture.failedFuture(ex);
        });
    }
    
    private List<PeerConnection> selectPeersByHealth(List<PeerConnection> candidates) {
        return candidates.stream()
            .filter(p -> p.channel().isActive())
            .sorted((a, b) -> {
                PeerHealth healthA = peerHealth.get(a.peerId());
                PeerHealth healthB = peerHealth.get(b.peerId());
                if (healthA == null && healthB == null) return 0;
                if (healthA == null) return 1;
                if (healthB == null) return -1;
                return Double.compare(healthB.score(), healthA.score());
            })
            .toList();
    }
    
    public List<PeerConnection> findPeersWithBlob(String blobId) {
        if (blobId == null) return Collections.emptyList();
        Set<String> peerIds = blobToPeers.get(blobId);
        if (peerIds == null) return Collections.emptyList();
        
        return peerIds.stream()
            .map(peers::get)
            .filter(Objects::nonNull)
            .filter(p -> p.channel().isActive())
            .toList();
    }
    
    public List<PeerConnection> getPeers() {
        return new ArrayList<>(peers.values());
    }
    
    public List<PeerConnection> getHealthyPeers() {
        return peers.values().stream()
            .filter(p -> p.channel().isActive())
            .filter(p -> {
                PeerHealth h = peerHealth.get(p.peerId());
                return h == null || h.state() != PeerHealthState.UNHEALTHY;
            })
            .toList();
    }
    
    public Optional<PeerHealth> getPeerHealth(String peerId) {
        if (peerId == null) return Optional.empty();
        return Optional.ofNullable(peerHealth.get(peerId));
    }
    
    public Transport.HealthStatus getHealthStatus() {
        int activePeers = (int) peers.values().stream().filter(p -> p.channel().isActive()).count();
        double successRate = successfulTransfers.get() + failedTransfers.get() > 0
            ? (double) successfulTransfers.get() / (successfulTransfers.get() + failedTransfers.get())
            : 1.0;
        
        String message = switch (healthState) {
            case HEALTHY -> "Network operational with " + activePeers + " peers";
            case DEGRADED -> "Network degraded, success rate: " + String.format("%.1f%%", successRate * 100);
            case UNHEALTHY -> "Network unhealthy";
            default -> "Network status unknown";
        };
        
        return new Transport.HealthStatus(healthState, message, lastHealthCheck, failedTransfers.get(), 0);
    }
    
    public BlobNetworkStats getStats() {
        int activePeers = (int) peers.values().stream().filter(p -> p.channel().isActive()).count();
        
        return new BlobNetworkStats(
            peerId,
            port,
            peers.size(),
            activePeers,
            announcedBlobs.size(),
            totalBytesUploaded.get(),
            totalBytesDownloaded.get(),
            totalRequestsSent.get(),
            totalRequestsReceived.get(),
            successfulTransfers.get(),
            failedTransfers.get(),
            healthState
        );
    }
    
    public void destroy() {
        destroyed = true;
        updateHealthState(Transport.HealthState.STOPPED);
        
        if (serverChannel != null) {
            serverChannel.close();
        }
        
        for (PeerConnection peer : peers.values()) {
            peer.channel().close();
        }
        peers.clear();
        blobToPeers.clear();
        announcedBlobs.clear();
        peerHealth.clear();
        
        for (CompletableFuture<byte[]> future : pendingRequests.values()) {
            future.completeExceptionally(new RuntimeException("Network destroyed"));
        }
        pendingRequests.clear();
        
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public CompletableFuture<Void> destroyAsync() {
        return CompletableFuture.runAsync(this::destroy);
    }
    
    private void updateHealthState(Transport.HealthState newState) {
        if (this.healthState != newState) {
            Transport.HealthState oldState = this.healthState;
            this.healthState = newState;
            this.lastHealthCheck = Instant.now();
            
            if (onHealthChange != null) {
                onHealthChange.accept(new HealthChangeEvent(oldState, newState));
            }
        }
    }
    
    private void recordRequestSent(String peerId) {
        if (peerId == null) return;
        peerHealth.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerHealth(peerId, 0, 0, 0, Duration.ZERO, Instant.now(), PeerHealthState.UNKNOWN, 50.0);
            }
            return new PeerHealth(peerId, v.requestsSent() + 1, v.responsesReceived(), v.failures(),
                v.avgLatency(), Instant.now(), v.state(), v.score());
        });
    }
    
    private void recordResponseReceived(String peerId, Duration latency, boolean success) {
        if (peerId == null) return;
        peerHealth.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerHealth(peerId, 0, success ? 1 : 0, success ? 0 : 1, 
                    latency, Instant.now(), success ? PeerHealthState.HEALTHY : PeerHealthState.DEGRADED, 
                    success ? 75.0 : 25.0);
            }
            
            long responses = v.responsesReceived() + (success ? 1 : 0);
            long failures = v.failures() + (success ? 0 : 1);
            Duration avgLatency = v.avgLatency().isZero() ? latency 
                : Duration.ofMillis((v.avgLatency().toMillis() + latency.toMillis()) / 2);
            
            double successRate = (double) responses / (responses + failures);
            PeerHealthState state = successRate > 0.9 ? PeerHealthState.HEALTHY 
                : successRate > 0.5 ? PeerHealthState.DEGRADED : PeerHealthState.UNHEALTHY;
            double score = successRate * 100 * (1.0 / (1.0 + avgLatency.toMillis() / 1000.0));
            
            return new PeerHealth(peerId, v.requestsSent(), responses, failures, avgLatency, 
                Instant.now(), state, score);
        });
    }
    
    public boolean isDestroyed() { return destroyed; }
    public String getPeerId() { return peerId; }
    public int getPort() { return port; }
    public BlobNetworkOptions getOptions() { return options; }
    
    public void setOnListening(Consumer<ListeningEvent> listener) { this.onListening = listener; }
    public void setOnPeer(Consumer<PeerConnectedEvent> listener) { this.onPeer = listener; }
    public void setOnDisconnect(Consumer<PeerDisconnectedEvent> listener) { this.onDisconnect = listener; }
    public void setOnUpload(Consumer<UploadEvent> listener) { this.onUpload = listener; }
    public void setOnDownload(Consumer<DownloadEvent> listener) { this.onDownload = listener; }
    public void setOnHave(Consumer<HaveEvent> listener) { this.onHave = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    public void setOnHealthChange(Consumer<HealthChangeEvent> listener) { this.onHealthChange = listener; }
    
    private void handleMessage(Channel channel, String peerId, Message message) {
        switch (message.type()) {
            case "hello" -> handleHello(channel, peerId, message);
            case "have" -> handleHave(peerId, message);
            case "query" -> handleQuery(channel, message);
            case "request" -> handleRequest(channel, message);
            case "blob" -> handleBlob(peerId, message);
            case "ping" -> handlePing(channel);
            case "pong" -> handlePong(peerId);
        }
    }
    
    private void handleHello(Channel channel, String remotePeerId, Message message) {
        InetSocketAddress addr = (InetSocketAddress) channel.remoteAddress();
        PeerConnection peer = new PeerConnection(remotePeerId, channel, System.currentTimeMillis(), 
            TransportType.CLEARNET, null, addr.getHostString(), addr.getPort());
        peers.put(remotePeerId, peer);
        
        if (onPeer != null) {
            onPeer.accept(new PeerConnectedEvent(remotePeerId, addr.getHostString(), addr.getPort(), 
                TransportType.CLEARNET));
        }
        
        for (String blobId : announcedBlobs) {
            Message haveMsg = new Message("have", Map.of("blobId", blobId));
            sendMessage(channel, haveMsg);
        }
    }
    
    private void handleHave(String peerId, Message message) {
        if (peerId == null || message.payload() == null) return;
        String blobId = (String) message.payload().get("blobId");
        if (blobId == null) return;
        
        blobToPeers.computeIfAbsent(blobId, k -> ConcurrentHashMap.newKeySet()).add(peerId);
        
        if (onHave != null) {
            onHave.accept(new HaveEvent(peerId, blobId));
        }
    }
    
    private void handleQuery(Channel channel, Message message) {
        if (message.payload() == null) return;
        String blobId = (String) message.payload().get("blobId");
        if (blobId == null) return;
        
        totalRequestsReceived.incrementAndGet();
        
        if (blobStore.has(blobId) || announcedBlobs.contains(blobId)) {
            Message haveMsg = new Message("have", Map.of("blobId", blobId));
            sendMessage(channel, haveMsg);
        }
    }
    
    private void handleRequest(Channel channel, Message message) {
        if (message.payload() == null) return;
        String blobId = (String) message.payload().get("blobId");
        if (blobId == null) return;
        
        totalRequestsReceived.incrementAndGet();
        Optional<byte[]> data = blobStore.get(blobId);
        
        if (data.isPresent()) {
            String base64Data = Base64.getEncoder().encodeToString(data.get());
            Message blobMsg = new Message("blob", Map.of("blobId", blobId, "data", base64Data));
            sendMessage(channel, blobMsg);
            totalBytesUploaded.addAndGet(data.get().length);
            
            if (onUpload != null) {
                onUpload.accept(new UploadEvent(blobId, data.get().length));
            }
        }
    }
    
    private void handleBlob(String peerId, Message message) {
        if (peerId == null || message.payload() == null) return;
        String blobId = (String) message.payload().get("blobId");
        String base64Data = (String) message.payload().get("data");
        if (blobId == null || base64Data == null) return;
        
        byte[] data = Base64.getDecoder().decode(base64Data);
        
        blobStore.put(blobId, data);
        totalBytesDownloaded.addAndGet(data.length);
        successfulTransfers.incrementAndGet();
        
        recordResponseReceived(peerId, Duration.ofMillis(100), true);
        
        CompletableFuture<byte[]> pending = pendingRequests.remove(blobId);
        if (pending != null) {
            pending.complete(data);
        }
        
        if (onDownload != null) {
            onDownload.accept(new DownloadEvent(blobId, data.length));
        }
    }
    
    private void handlePing(Channel channel) {
        sendMessage(channel, new Message("pong", Map.of()));
    }
    
    private void handlePong(String peerId) {
        recordResponseReceived(peerId, Duration.ofMillis(50), true);
    }
    
    private void broadcastMessage(Message message) {
        for (PeerConnection peer : peers.values()) {
            if (peer.channel().isActive()) {
                sendMessage(peer.channel(), message);
            }
        }
    }
    
    private void sendMessage(Channel channel, Message message) {
        try {
            String json = MAPPER.writeValueAsString(message);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            // Log error
        }
    }
    
    private Message decodeMessage(String json) {
        try {
            return MAPPER.readValue(json, Message.class);
        } catch (Exception e) {
            return new Message("error", Map.of("error", e.getMessage()));
        }
    }
    
    private static String generatePeerId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    
    private class BlobNetworkServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private String remotePeerId;
        private boolean handshakeCompleted = false;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame textFrame) {
                Message msg = decodeMessage(textFrame.text());
                if ("hello".equals(msg.type())) {
                    if (!handshakeCompleted) {
                        handshakeCompleted = true;
                        remotePeerId = (String) msg.payload().get("peerId");
                        PeerConnection peer = new PeerConnection(remotePeerId, ctx.channel(),
                            System.currentTimeMillis(), TransportType.CLEARNET, null,
                            ((InetSocketAddress) ctx.channel().remoteAddress()).getHostString(),
                            ((InetSocketAddress) ctx.channel().remoteAddress()).getPort());
                        peers.put(remotePeerId, peer);

                        if (onPeer != null) {
                            onPeer.accept(new PeerConnectedEvent(remotePeerId,
                                ((InetSocketAddress) ctx.channel().remoteAddress()).getHostString(),
                                ((InetSocketAddress) ctx.channel().remoteAddress()).getPort(),
                                TransportType.CLEARNET));
                        }

                        Message helloReply = new Message("hello", Map.of("peerId", peerId));
                        sendMessage(ctx.channel(), helloReply);

                        for (String blobId : announcedBlobs) {
                            Message haveMsg = new Message("have", Map.of("blobId", blobId));
                            sendMessage(ctx.channel(), haveMsg);
                        }
                    } else {
                        remotePeerId = (String) msg.payload().get("peerId");
                    }
                }
                if (remotePeerId != null) {
                    handleMessage(ctx.channel(), remotePeerId, msg);
                }
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (remotePeerId != null) {
                peers.remove(remotePeerId);
                for (Set<String> peerSet : blobToPeers.values()) {
                    peerSet.remove(remotePeerId);
                }
                
                if (onDisconnect != null) {
                    onDisconnect.accept(new PeerDisconnectedEvent(remotePeerId));
                }
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    private class BlobNetworkClientHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final String address;
        private final CompletableFuture<PeerConnection> connectionFuture;
        private String remotePeerId;
        
        BlobNetworkClientHandler(String address, CompletableFuture<PeerConnection> future) {
            this.address = address;
            this.connectionFuture = future;
        }
        
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                Message hello = new Message("hello", Map.of("peerId", peerId));
                sendMessage(ctx.channel(), hello);
            }
            super.userEventTriggered(ctx, evt);
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame textFrame) {
                Message msg = decodeMessage(textFrame.text());
                if ("hello".equals(msg.type())) {
                    remotePeerId = (String) msg.payload().get("peerId");
                    InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
                    PeerConnection peer = new PeerConnection(remotePeerId, ctx.channel(), 
                        System.currentTimeMillis(), TransportType.CLEARNET, address,
                        addr.getHostString(), addr.getPort());
                    peers.put(remotePeerId, peer);
                    connectionFuture.complete(peer);
                    
                    if (onPeer != null) {
                        onPeer.accept(new PeerConnectedEvent(remotePeerId, addr.getHostString(), 
                            addr.getPort(), TransportType.CLEARNET));
                    }
                    
                    for (String blobId : announcedBlobs) {
                        Message haveMsg = new Message("have", Map.of("blobId", blobId));
                        sendMessage(ctx.channel(), haveMsg);
                    }
                }
                if (remotePeerId != null) {
                    handleMessage(ctx.channel(), remotePeerId, msg);
                }
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (remotePeerId != null) {
                peers.remove(remotePeerId);
                for (Set<String> peerSet : blobToPeers.values()) {
                    peerSet.remove(remotePeerId);
                }
                
                if (onDisconnect != null) {
                    onDisconnect.accept(new PeerDisconnectedEvent(remotePeerId));
                }
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!connectionFuture.isDone()) {
                connectionFuture.completeExceptionally(cause);
            }
            ctx.close();
        }
    }
    
    public static class BlobNetworkOptions {
        public final String peerId;
        public final int port;
        public final int maxConnections;
        public final Duration connectTimeout;
        public final Duration requestTimeout;
        public final boolean enableHealthTracking;
        public final int maxRetries;
        public final Duration retryDelay;
        public final boolean autoReconnect;
        public final Duration pingInterval;
        
        private BlobNetworkOptions(Builder builder) {
            this.peerId = builder.peerId;
            this.port = builder.port;
            this.maxConnections = builder.maxConnections;
            this.connectTimeout = builder.connectTimeout;
            this.requestTimeout = builder.requestTimeout;
            this.enableHealthTracking = builder.enableHealthTracking;
            this.maxRetries = builder.maxRetries;
            this.retryDelay = builder.retryDelay;
            this.autoReconnect = builder.autoReconnect;
            this.pingInterval = builder.pingInterval;
        }
        
        public static BlobNetworkOptions defaults() {
            return builder().build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public String peerId() { return peerId; }
        public int port() { return port; }
        public int maxConnections() { return maxConnections; }
        
        public static class Builder {
            private String peerId = null;
            private int port = 0;
            private int maxConnections = 50;
            private Duration connectTimeout = Duration.ofSeconds(30);
            private Duration requestTimeout = Duration.ofSeconds(30);
            private boolean enableHealthTracking = true;
            private int maxRetries = 3;
            private Duration retryDelay = Duration.ofSeconds(1);
            private boolean autoReconnect = true;
            private Duration pingInterval = Duration.ofSeconds(30);
            
            public Builder peerId(String peerId) { this.peerId = peerId; return this; }
            public Builder port(int port) { this.port = port; return this; }
            public Builder maxConnections(int max) { this.maxConnections = max; return this; }
            public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
            public Builder requestTimeout(Duration timeout) { this.requestTimeout = timeout; return this; }
            public Builder enableHealthTracking(boolean enable) { this.enableHealthTracking = enable; return this; }
            public Builder maxRetries(int retries) { this.maxRetries = retries; return this; }
            public Builder retryDelay(Duration delay) { this.retryDelay = delay; return this; }
            public Builder autoReconnect(boolean auto) { this.autoReconnect = auto; return this; }
            public Builder pingInterval(Duration interval) { this.pingInterval = interval; return this; }
            
            public BlobNetworkOptions build() {
                return new BlobNetworkOptions(this);
            }
        }
    }
    
    public static class ConnectionOptions {
        public final Duration connectTimeout;
        public final Duration readTimeout;
        public final boolean enableSsl;
        public final int maxRetries;
        
        private ConnectionOptions(Builder builder) {
            this.connectTimeout = builder.connectTimeout;
            this.readTimeout = builder.readTimeout;
            this.enableSsl = builder.enableSsl;
            this.maxRetries = builder.maxRetries;
        }
        
        public static ConnectionOptions defaults() {
            return builder().build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private Duration connectTimeout = Duration.ofSeconds(30);
            private Duration readTimeout = Duration.ofSeconds(30);
            private boolean enableSsl = false;
            private int maxRetries = 3;
            
            public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
            public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
            public Builder enableSsl(boolean ssl) { this.enableSsl = ssl; return this; }
            public Builder maxRetries(int retries) { this.maxRetries = retries; return this; }
            
            public ConnectionOptions build() {
                return new ConnectionOptions(this);
            }
        }
    }
    
    public enum PeerHealthState { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY }
    
    public record PeerConnection(String peerId, Channel channel, long connectedAt, 
                                  TransportType transportType, String address, String host, int port) {
        public PeerConnection(String peerId, Channel channel, long connectedAt) {
            this(peerId, channel, connectedAt, TransportType.CLEARNET, null, null, 0);
        }
        
        public PeerConnection(String peerId, Channel channel, long connectedAt, String host, int port) {
            this(peerId, channel, connectedAt, TransportType.CLEARNET, null, host, port);
        }

        public PeerConnection(String peerId, Channel channel, long connectedAt, TransportType type, String address) {
            this(peerId, channel, connectedAt, type, address, null, 0);
        }
    }
    
    public record PeerHealth(
        String peerId,
        long requestsSent,
        long responsesReceived,
        long failures,
        Duration avgLatency,
        Instant lastActivity,
        PeerHealthState state,
        double score
    ) {}
    
    public record Message(String type, Map<String, Object> payload) {
        public Message() { this(null, null); }
    }
    
    public record ListeningEvent(int port) {}
    public record PeerConnectedEvent(String peerId, String host, int port, TransportType transportType) {
        public PeerConnectedEvent(String peerId, String host, int port) {
            this(peerId, host, port, TransportType.CLEARNET);
        }
    }
    public record PeerDisconnectedEvent(String peerId) {}
    public record UploadEvent(String blobId, long size) {}
    public record DownloadEvent(String blobId, long size) {}
    public record HaveEvent(String peerId, String blobId) {}
    public record HealthChangeEvent(Transport.HealthState oldState, Transport.HealthState newState) {}
    
    public record BlobNetworkStats(
        String peerId,
        int port,
        int totalPeers,
        int activePeers,
        int announcedBlobs,
        long bytesUploaded,
        long bytesDownloaded,
        long requestsSent,
        long requestsReceived,
        long successfulTransfers,
        long failedTransfers,
        Transport.HealthState healthState
    ) {
        public BlobNetworkStats(String peerId, int port, int peerCount, int announcedBlobs) {
            this(peerId, port, peerCount, peerCount, announcedBlobs, 0, 0, 0, 0, 0, 0, 
                Transport.HealthState.STOPPED);
        }
    }
}
