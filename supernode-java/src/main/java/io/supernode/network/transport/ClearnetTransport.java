package io.supernode.network.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClearnetTransport implements Transport {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "^(wss?://)?([^:/]+):?(\\d+)?(/.*)?$"
    );
    
    private final int port;
    private final boolean enableTls;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ConcurrentHashMap<String, ClearnetConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConnectionPool> connectionPools = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckExecutor = Executors.newSingleThreadScheduledExecutor();
    
    private Channel serverChannel;
    private TransportAddress localAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private Instant startTime;
    private TransportConfig config = TransportConfig.defaults();
    private SslContext serverSslContext;
    private SslContext clientSslContext;
    
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();
    private volatile HealthStatus lastHealthStatus;
    private ScheduledFuture<?> healthCheckTask;

    public ClearnetTransport() {
        this(0, false);
    }

    public ClearnetTransport(int port) {
        this(port, false);
    }

    public ClearnetTransport(int port, boolean enableTls) {
        this.port = port;
        this.enableTls = enableTls;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Not started", Instant.now(), 0, 0);
    }

    @Override
    public TransportType getType() {
        return TransportType.CLEARNET;
    }

    @Override
    public String getName() {
        return enableTls ? "Clearnet (WebSocket Secure)" : "Clearnet (WebSocket)";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void configure(TransportConfig config) {
        this.config = config;
    }

    @Override
    public TransportConfig getConfig() {
        return config;
    }

    @Override
    public CompletableFuture<TransportAddress> start() {
        CompletableFuture<TransportAddress> future = new CompletableFuture<>();
        
        try {
            if (enableTls) {
                SelfSignedCertificate ssc = new SelfSignedCertificate();
                serverSslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
                clientSslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            }

            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, config.listenBacklog())
                .option(ChannelOption.SO_REUSEADDR, config.reuseAddress())
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (serverSslContext != null) {
                            p.addLast(serverSslContext.newHandler(ch.alloc()));
                        }
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new WebSocketServerCompressionHandler());
                        p.addLast(new WebSocketServerProtocolHandler("/", null, true, 65536, false, true));
                        p.addLast(new IdleStateHandler(
                            (int) config.idleTimeout().toSeconds(), 0, 0
                        ));
                        p.addLast(new InboundHandler());
                    }
                });
            
            ChannelFuture bindFuture = bootstrap.bind(port);
            bindFuture.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    serverChannel = f.channel();
                    int boundPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
                    localAddress = TransportAddress.clearnet("0.0.0.0", boundPort);
                    running = true;
                    startTime = Instant.now();
                    lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, 0);
                    startHealthCheckTask();
                    future.complete(localAddress);
                } else {
                    errors.incrementAndGet();
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            errors.incrementAndGet();
            future.completeExceptionally(e);
        }
        
        return future;
    }

    private void startHealthCheckTask() {
        if (config.healthCheckInterval().toMillis() > 0) {
            healthCheckTask = healthCheckExecutor.scheduleAtFixedRate(
                this::performHealthCheck,
                config.healthCheckInterval().toMillis(),
                config.healthCheckInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void performHealthCheck() {
        long start = System.currentTimeMillis();
        try {
            boolean healthy = serverChannel != null && serverChannel.isActive();
            long latency = System.currentTimeMillis() - start;
            
            if (healthy) {
                lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, latency);
            } else {
                healthCheckFailures.incrementAndGet();
                lastHealthStatus = new HealthStatus(
                    HealthState.UNHEALTHY, 
                    "Server channel not active", 
                    Instant.now(), 
                    healthCheckFailures.get(), 
                    latency
                );
            }
        } catch (Exception e) {
            healthCheckFailures.incrementAndGet();
            lastHealthStatus = new HealthStatus(
                HealthState.UNHEALTHY, 
                e.getMessage(), 
                Instant.now(), 
                healthCheckFailures.get(), 
                System.currentTimeMillis() - start
            );
        }
    }

    @Override
    public HealthStatus getHealthStatus() {
        return lastHealthStatus;
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            performHealthCheck();
            return lastHealthStatus.isHealthy();
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        running = false;
        
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
        }
        
        connectionPools.values().forEach(ConnectionPool::close);
        connectionPools.clear();
        
        connections.values().forEach(conn -> conn.close().join());
        connections.clear();
        
        if (serverChannel != null) {
            serverChannel.close().addListener(f -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Stopped", Instant.now(), 0, 0);
                future.complete(null);
            });
        } else {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Stopped", Instant.now(), 0, 0);
            future.complete(null);
        }
        
        return future;
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions options) {
        if (options.autoReconnect()) {
            return connectWithAutoReconnect(address, options, 0);
        }
        return connectOnce(address, options);
    }

    private CompletableFuture<TransportConnection> connectWithAutoReconnect(
            TransportAddress address, ConnectionOptions options, int attempt) {
        return connectOnce(address, options).handle((conn, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(conn);
            }
            
            if (attempt >= options.maxReconnectAttempts()) {
                CompletableFuture<TransportConnection> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException(
                    "Max reconnection attempts reached: " + attempt, ex
                ));
                return failed;
            }
            
            reconnections.incrementAndGet();
            long delay = calculateBackoffDelay(options, attempt);
            
            CompletableFuture<TransportConnection> reconnectFuture = new CompletableFuture<>();
            healthCheckExecutor.schedule(() -> {
                connectWithAutoReconnect(address, options, attempt + 1)
                    .whenComplete((c, e) -> {
                        if (e != null) reconnectFuture.completeExceptionally(e);
                        else reconnectFuture.complete(c);
                    });
            }, delay, TimeUnit.MILLISECONDS);
            
            return reconnectFuture;
        }).thenCompose(f -> f);
    }

    private long calculateBackoffDelay(ConnectionOptions options, int attempt) {
        long delay = options.reconnectDelay().toMillis();
        for (int i = 0; i < attempt; i++) {
            delay = (long) (delay * options.reconnectBackoffMultiplier());
        }
        return Math.min(delay, options.reconnectMaxDelay().toMillis());
    }

    private CompletableFuture<TransportConnection> connectOnce(TransportAddress address, ConnectionOptions options) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        try {
            boolean useSsl = address.raw().startsWith("wss://") || enableTls;
            String scheme = useSsl ? "wss" : "ws";
            URI uri = new URI(scheme + "://" + address.host() + ":" + address.port() + "/");
            
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()
            );
            
            Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) options.connectTimeout().toMillis())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        if (useSsl) {
                            SslContext sslCtx = clientSslContext != null ? clientSslContext :
                                SslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .build();
                            p.addLast(sslCtx.newHandler(ch.alloc(), address.host(), address.port()));
                        }
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(WebSocketClientCompressionHandler.INSTANCE);
                        if (options.enableHeartbeat()) {
                            p.addLast(new IdleStateHandler(
                                (int) options.heartbeatTimeout().toSeconds(),
                                (int) options.heartbeatInterval().toSeconds(),
                                0
                            ));
                        }
                        p.addLast(new OutboundHandler(handshaker, future, address, options));
                    }
                });
            
            bootstrap.connect(address.host(), address.port()).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    errors.incrementAndGet();
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            errors.incrementAndGet();
            future.completeExceptionally(e);
        }
        
        return future;
    }

    public CompletableFuture<TransportConnection> acquirePooledConnection(TransportAddress address) {
        String key = address.host() + ":" + address.port();
        ConnectionPool pool = connectionPools.computeIfAbsent(key, 
            k -> new ConnectionPool(address, config.maxConnectionsPerPeer()));
        return pool.acquire();
    }

    public void releasePooledConnection(TransportConnection connection) {
        String key = connection.getRemoteAddress().host() + ":" + connection.getRemoteAddress().port();
        ConnectionPool pool = connectionPools.get(key);
        if (pool != null) {
            pool.release((ClearnetConnection) connection);
        }
    }

    @Override
    public TransportAddress parseAddress(String address) {
        Matcher matcher = ADDRESS_PATTERN.matcher(address);
        if (matcher.matches()) {
            String scheme = matcher.group(1);
            String host = matcher.group(2);
            String portStr = matcher.group(3);
            int port = portStr != null ? Integer.parseInt(portStr) : 
                (scheme != null && scheme.startsWith("wss") ? 443 : 80);
            return new TransportAddress(TransportType.CLEARNET, host, port, address);
        }
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        if (address.contains(".onion") || address.contains(".i2p")) {
            return false;
        }
        return ADDRESS_PATTERN.matcher(address).matches();
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

    public int getActiveConnectionCount() {
        return connections.size();
    }

    public List<TransportConnection> getActiveConnections() {
        return List.copyOf(connections.values());
    }

    private class ConnectionPool {
        private final TransportAddress address;
        private final int maxSize;
        private final ConcurrentLinkedQueue<ClearnetConnection> available = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);

        ConnectionPool(TransportAddress address, int maxSize) {
            this.address = address;
            this.maxSize = maxSize;
        }

        CompletableFuture<TransportConnection> acquire() {
            ClearnetConnection conn = available.poll();
            if (conn != null && conn.isOpen()) {
                return CompletableFuture.completedFuture(conn);
            }
            
            if (size.get() < maxSize) {
                size.incrementAndGet();
                return connectOnce(address, ConnectionOptions.defaults())
                    .whenComplete((c, e) -> {
                        if (e != null) size.decrementAndGet();
                    });
            }
            
            return CompletableFuture.failedFuture(
                new RuntimeException("Connection pool exhausted for " + address)
            );
        }

        void release(ClearnetConnection conn) {
            if (conn.isOpen() && available.size() < maxSize) {
                available.offer(conn);
            } else {
                conn.close();
                size.decrementAndGet();
            }
        }

        void close() {
            ClearnetConnection conn;
            while ((conn = available.poll()) != null) {
                conn.close();
            }
            size.set(0);
        }
    }

    private class InboundHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private ClearnetConnection connection;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            String id = UUID.randomUUID().toString();
            java.net.InetSocketAddress remote = (java.net.InetSocketAddress) ctx.channel().remoteAddress();
            TransportAddress remoteAddr = TransportAddress.clearnet(
                remote.getHostString(), remote.getPort()
            );
            
            connection = new ClearnetConnection(
                id, ctx.channel(), remoteAddr, localAddress,
                bytesReceived, bytesSent, messagesReceived, messagesSent,
                ConnectionOptions.defaults()
            );
            connections.put(id, connection);
            connectionsIn.incrementAndGet();
            
            if (onConnection != null) {
                onConnection.accept(connection);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idleEvent) {
                if (idleEvent.state() == IdleState.READER_IDLE) {
                    ctx.close();
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame textFrame) {
                byte[] data = textFrame.text().getBytes(StandardCharsets.UTF_8);
                connection.handleMessage(data);
            } else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
                ByteBuf content = binaryFrame.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                connection.handleMessage(data);
            } else if (frame instanceof PingWebSocketFrame pingFrame) {
                ByteBuf content = pingFrame.content().retain();
                ctx.writeAndFlush(new PongWebSocketFrame(content));
            } else if (frame instanceof PongWebSocketFrame) {
                connection.handlePong();
            } else if (frame instanceof CloseWebSocketFrame closeFrame) {
                connection.handleClose(closeFrame.statusCode(), closeFrame.reasonText());
                connections.remove(connection.getId());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            errors.incrementAndGet();
            if (connection != null) {
                connection.handleError(cause);
            }
            if (onError != null) {
                onError.accept(new TransportError(TransportType.CLEARNET, cause.getMessage(), cause));
            }
            ctx.close();
        }
    }

    private class OutboundHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;
        private final CompletableFuture<TransportConnection> future;
        private final TransportAddress remoteAddress;
        private final ConnectionOptions options;
        private ClearnetConnection connection;

        OutboundHandler(WebSocketClientHandshaker handshaker, 
                       CompletableFuture<TransportConnection> future,
                       TransportAddress remoteAddress,
                       ConnectionOptions options) {
            this.handshaker = handshaker;
            this.future = future;
            this.remoteAddress = remoteAddress;
            this.options = options;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idleEvent) {
                if (idleEvent.state() == IdleState.WRITER_IDLE && connection != null) {
                    connection.sendPing();
                } else if (idleEvent.state() == IdleState.READER_IDLE) {
                    ctx.close();
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                    
                    String id = UUID.randomUUID().toString();
                    connection = new ClearnetConnection(
                        id, ctx.channel(), remoteAddress, localAddress,
                        bytesReceived, bytesSent, messagesReceived, messagesSent,
                        options
                    );
                    connections.put(id, connection);
                    connectionsOut.incrementAndGet();
                    future.complete(connection);
                } catch (WebSocketHandshakeException e) {
                    errors.incrementAndGet();
                    future.completeExceptionally(e);
                }
                return;
            }

            if (msg instanceof WebSocketFrame frame) {
                if (frame instanceof TextWebSocketFrame textFrame) {
                    byte[] data = textFrame.text().getBytes(StandardCharsets.UTF_8);
                    connection.handleMessage(data);
                } else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
                    ByteBuf content = binaryFrame.content();
                    byte[] data = new byte[content.readableBytes()];
                    content.readBytes(data);
                    connection.handleMessage(data);
                } else if (frame instanceof PingWebSocketFrame pingFrame) {
                    ByteBuf content = pingFrame.content().retain();
                    ctx.writeAndFlush(new PongWebSocketFrame(content));
                } else if (frame instanceof PongWebSocketFrame) {
                    connection.handlePong();
                } else if (frame instanceof CloseWebSocketFrame closeFrame) {
                    connection.handleClose(closeFrame.statusCode(), closeFrame.reasonText());
                    connections.remove(connection.getId());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            errors.incrementAndGet();
            if (!future.isDone()) {
                future.completeExceptionally(cause);
            }
            if (connection != null) {
                connection.handleError(cause);
            }
            if (onError != null) {
                onError.accept(new TransportError(TransportType.CLEARNET, cause.getMessage(), cause));
            }
            ctx.close();
        }
    }

    private static class ClearnetConnection implements TransportConnection {
        private final String id;
        private final Channel channel;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong framesReceived = new AtomicLong();
        private final AtomicLong framesSent = new AtomicLong();
        private final AtomicLong pingsSent = new AtomicLong();
        private final AtomicLong pongsReceived = new AtomicLong();
        private final AtomicLong reconnectCount = new AtomicLong();
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile long lastPingSentAt;
        private volatile long avgLatencyMs = 0;
        private final ConnectionOptions options;
        private volatile ConnectionState state = ConnectionState.CONNECTING;
        private volatile boolean paused = false;
        private volatile long writeQueueBytes = 0;
        private long writeBufferLow = 32 * 1024;
        private long writeBufferHigh = 64 * 1024;
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<CloseReason> onCloseWithReason;
        private Consumer<Throwable> onError;

        ClearnetConnection(String id, Channel channel, 
                          TransportAddress remoteAddress, TransportAddress localAddress,
                          AtomicLong bytesReceived, AtomicLong bytesSent,
                          AtomicLong messagesReceived, AtomicLong messagesSent,
                          ConnectionOptions options) {
            this.id = id;
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.options = options;
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = connectedAt;
            this.state = ConnectionState.OPEN;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public TransportType getTransportType() {
            return TransportType.CLEARNET;
        }

        @Override
        public TransportAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public TransportAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen() && state == ConnectionState.OPEN;
        }

        @Override
        public ConnectionState getState() {
            return state;
        }

        @Override
        public ConnectionOptions getOptions() {
            return options;
        }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            if (paused) {
                return CompletableFuture.failedFuture(new RuntimeException("Connection paused"));
            }
            
            CompletableFuture<Void> future = new CompletableFuture<>();
            BinaryWebSocketFrame frame = new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data));
            writeQueueBytes += data.length;
            
            channel.writeAndFlush(frame).addListener(f -> {
                writeQueueBytes -= data.length;
                if (f.isSuccess()) {
                    bytesSent.addAndGet(data.length);
                    messagesSent.incrementAndGet();
                    framesSent.incrementAndGet();
                    lastActivityAt = System.currentTimeMillis();
                    future.complete(null);
                } else {
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        }

        @Override
        public CompletableFuture<Void> send(String message) {
            if (paused) {
                return CompletableFuture.failedFuture(new RuntimeException("Connection paused"));
            }
            
            CompletableFuture<Void> future = new CompletableFuture<>();
            TextWebSocketFrame frame = new TextWebSocketFrame(message);
            int len = message.getBytes(StandardCharsets.UTF_8).length;
            writeQueueBytes += len;
            
            channel.writeAndFlush(frame).addListener(f -> {
                writeQueueBytes -= len;
                if (f.isSuccess()) {
                    bytesSent.addAndGet(len);
                    messagesSent.incrementAndGet();
                    framesSent.incrementAndGet();
                    lastActivityAt = System.currentTimeMillis();
                    future.complete(null);
                } else {
                    future.completeExceptionally(f.cause());
                }
            });
            return future;
        }

        @Override
        public CompletableFuture<Duration> ping() {
            lastPingSentAt = System.nanoTime();
            pingsSent.incrementAndGet();
            CompletableFuture<Duration> future = new CompletableFuture<>();
            
            PingWebSocketFrame frame = new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[0]));
            channel.writeAndFlush(frame).addListener(f -> {
                if (!f.isSuccess()) {
                    future.completeExceptionally(f.cause());
                }
            });
            
            return future;
        }

        void sendPing() {
            lastPingSentAt = System.nanoTime();
            pingsSent.incrementAndGet();
            PingWebSocketFrame frame = new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[0]));
            channel.writeAndFlush(frame);
        }

        void handlePong() {
            pongsReceived.incrementAndGet();
            long latency = (System.nanoTime() - lastPingSentAt) / 1_000_000;
            avgLatencyMs = (avgLatencyMs + latency) / 2;
            lastActivityAt = System.currentTimeMillis();
        }

        @Override
        public void pause() {
            paused = true;
            channel.config().setAutoRead(false);
        }

        @Override
        public void resume() {
            paused = false;
            channel.config().setAutoRead(true);
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public BackpressureStatus getBackpressureStatus() {
            return new BackpressureStatus(writeQueueBytes, writeBufferHigh, writeQueueBytes > writeBufferHigh);
        }

        @Override
        public void setWriteBufferWatermarks(long low, long high) {
            this.writeBufferLow = low;
            this.writeBufferHigh = high;
            channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark((int) low, (int) high));
        }

        @Override
        public int getReconnectCount() {
            return (int) reconnectCount.get();
        }

        @Override
        public CompletableFuture<Void> close() {
            return close(1000, "Normal closure");
        }

        @Override
        public CompletableFuture<Void> close(int code, String reason) {
            state = ConnectionState.CLOSING;
            CompletableFuture<Void> future = new CompletableFuture<>();
            channel.writeAndFlush(new CloseWebSocketFrame(code, reason))
                .addListener(f -> channel.close().addListener(cf -> {
                    state = ConnectionState.CLOSED;
                    future.complete(null);
                }));
            return future;
        }

        @Override
        public void setOnMessage(Consumer<byte[]> handler) {
            this.onMessage = handler;
        }

        @Override
        public void setOnClose(Consumer<Void> handler) {
            this.onClose = handler;
        }

        @Override
        public void setOnCloseWithReason(Consumer<CloseReason> handler) {
            this.onCloseWithReason = handler;
        }

        @Override
        public void setOnError(Consumer<Throwable> handler) {
            this.onError = handler;
        }

        @Override
        public ConnectionStats getStats() {
            return new ConnectionStats(
                bytesReceived.get(), bytesSent.get(),
                messagesReceived.get(), messagesSent.get(),
                connectedAt, lastActivityAt,
                framesReceived.get(), framesSent.get(),
                pingsSent.get(), pongsReceived.get(),
                avgLatencyMs, reconnectCount.get(),
                0, writeQueueBytes
            );
        }

        void handleMessage(byte[] data) {
            bytesReceived.addAndGet(data.length);
            messagesReceived.incrementAndGet();
            framesReceived.incrementAndGet();
            lastActivityAt = System.currentTimeMillis();
            if (onMessage != null) {
                onMessage.accept(data);
            }
        }

        void handleClose(int code, String reason) {
            state = ConnectionState.CLOSED;
            if (onCloseWithReason != null) {
                onCloseWithReason.accept(new CloseReason(code, reason));
            }
            if (onClose != null) {
                onClose.accept(null);
            }
        }

        void handleError(Throwable cause) {
            if (onError != null) {
                onError.accept(cause);
            }
        }
    }
}
