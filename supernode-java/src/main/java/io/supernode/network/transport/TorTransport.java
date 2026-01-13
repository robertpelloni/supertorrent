package io.supernode.network.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TorTransport implements Transport {

    private static final Pattern ONION_PATTERN = Pattern.compile(
        "^(tor://|ws://)?([a-z2-7]{56}\\.onion):?(\\d+)?(/.*)?$"
    );
    
    private static final int DEFAULT_SOCKS_PORT = 9050;
    private static final int DEFAULT_CONTROL_PORT = 9051;
    
    private final TorOptions options;
    private final EventLoopGroup workerGroup;
    private final ConcurrentHashMap<String, TorConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HiddenService> hiddenServices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitInfo> circuits = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final SecureRandom random = new SecureRandom();
    
    private TransportAddress localAddress;
    private String primaryOnionAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private volatile boolean available = false;
    private TransportConfig config = TransportConfig.defaults();
    private Instant startTime;
    private volatile HealthStatus lastHealthStatus;
    private ScheduledFuture<?> healthCheckTask;
    private ScheduledFuture<?> circuitRotationTask;
    
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();
    private final AtomicLong circuitRotations = new AtomicLong();
    private final AtomicInteger streamIsolationCounter = new AtomicInteger(0);

    public TorTransport() {
        this(TorOptions.defaults());
    }

    public TorTransport(TorOptions options) {
        this.options = options;
        this.workerGroup = new NioEventLoopGroup();
        this.lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Not started", Instant.now(), 0, 0);
        checkTorAvailability();
    }

    @Override
    public TransportType getType() {
        return TransportType.TOR;
    }

    @Override
    public String getName() {
        return "Tor (Onion Routing)";
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
    public void configure(TransportConfig config) {
        this.config = config;
    }

    @Override
    public TransportConfig getConfig() {
        return config;
    }

    @Override
    public CompletableFuture<TransportAddress> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!available) {
                    throw new IllegalStateException("Tor is not available. Ensure Tor daemon is running.");
                }
                
                HiddenService hs = createHiddenService(
                    "primary",
                    options.hiddenServicePort,
                    options.persistHiddenService ? options.hiddenServiceKeyPath : null
                );
                hiddenServices.put("primary", hs);
                primaryOnionAddress = hs.onionAddress;
                localAddress = TransportAddress.onion(primaryOnionAddress, options.hiddenServicePort);
                
                running = true;
                startTime = Instant.now();
                lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, 0);
                
                startHealthCheckTask();
                if (options.enableCircuitRotation) {
                    startCircuitRotationTask();
                }
                
                return localAddress;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        });
    }

    private void startHealthCheckTask() {
        if (config.healthCheckInterval().toMillis() > 0) {
            healthCheckTask = scheduler.scheduleAtFixedRate(
                this::performHealthCheck,
                config.healthCheckInterval().toMillis(),
                config.healthCheckInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void startCircuitRotationTask() {
        circuitRotationTask = scheduler.scheduleAtFixedRate(
            this::rotateCircuits,
            options.circuitRotationInterval.toMillis(),
            options.circuitRotationInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void performHealthCheck() {
        long start = System.currentTimeMillis();
        try {
            boolean socksAvailable = checkSocksConnection();
            boolean controlAvailable = checkControlConnection();
            long latency = System.currentTimeMillis() - start;
            
            if (socksAvailable && controlAvailable) {
                lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, latency);
            } else if (socksAvailable) {
                lastHealthStatus = new HealthStatus(HealthState.DEGRADED, "Control port unavailable", Instant.now(), 0, latency);
            } else {
                healthCheckFailures.incrementAndGet();
                lastHealthStatus = new HealthStatus(HealthState.UNHEALTHY, "SOCKS proxy unavailable", Instant.now(), healthCheckFailures.get(), latency);
            }
        } catch (Exception e) {
            healthCheckFailures.incrementAndGet();
            lastHealthStatus = new HealthStatus(HealthState.UNHEALTHY, e.getMessage(), Instant.now(), healthCheckFailures.get(), System.currentTimeMillis() - start);
        }
    }

    private boolean checkSocksConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(options.socksHost, options.socksPort), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean checkControlConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(options.socksHost, options.controlPort), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void rotateCircuits() {
        try {
            sendControlCommand("SIGNAL NEWNYM");
            circuitRotations.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
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
        return CompletableFuture.runAsync(() -> {
            running = false;
            
            if (healthCheckTask != null) healthCheckTask.cancel(false);
            if (circuitRotationTask != null) circuitRotationTask.cancel(false);
            
            connections.values().forEach(conn -> conn.close().join());
            connections.clear();
            
            hiddenServices.values().forEach(hs -> {
                try {
                    removeHiddenService(hs.serviceId);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            hiddenServices.clear();
            
            workerGroup.shutdownGracefully();
            scheduler.shutdown();
            
            lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Stopped", Instant.now(), 0, 0);
        });
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions opts) {
        if (address.type() != TransportType.TOR) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not a Tor address: " + address));
        }
        
        StreamIsolation isolation = options.enableStreamIsolation 
            ? StreamIsolation.perConnection(streamIsolationCounter.incrementAndGet())
            : StreamIsolation.none();
        
        if (opts.autoReconnect()) {
            return connectWithAutoReconnect(address, opts, isolation, 0);
        }
        return connectOnce(address, opts, isolation);
    }

    private CompletableFuture<TransportConnection> connectWithAutoReconnect(
            TransportAddress address, ConnectionOptions opts, StreamIsolation isolation, int attempt) {
        return connectOnce(address, opts, isolation).handle((conn, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(conn);
            }
            
            if (attempt >= opts.maxReconnectAttempts()) {
                CompletableFuture<TransportConnection> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("Max reconnection attempts reached: " + attempt, ex));
                return failed;
            }
            
            reconnections.incrementAndGet();
            long delay = calculateBackoffDelay(opts, attempt);
            
            CompletableFuture<TransportConnection> reconnectFuture = new CompletableFuture<>();
            scheduler.schedule(() -> {
                connectWithAutoReconnect(address, opts, isolation, attempt + 1)
                    .whenComplete((c, e) -> {
                        if (e != null) reconnectFuture.completeExceptionally(e);
                        else reconnectFuture.complete(c);
                    });
            }, delay, TimeUnit.MILLISECONDS);
            
            return reconnectFuture;
        }).thenCompose(f -> f);
    }

    private long calculateBackoffDelay(ConnectionOptions opts, int attempt) {
        long delay = opts.reconnectDelay().toMillis();
        for (int i = 0; i < attempt; i++) {
            delay = (long) (delay * opts.reconnectBackoffMultiplier());
        }
        return Math.min(delay, opts.reconnectMaxDelay().toMillis());
    }

    private CompletableFuture<TransportConnection> connectOnce(
            TransportAddress address, ConnectionOptions opts, StreamIsolation isolation) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        try {
            String host = address.host();
            int port = address.port() > 0 ? address.port() : 80;
            
            Socks5ProxyHandler proxyHandler = createProxyHandler(isolation);
            
            Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) opts.connectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addFirst(proxyHandler);
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        if (opts.enableHeartbeat()) {
                            p.addLast(new IdleStateHandler(
                                (int) opts.heartbeatTimeout().toSeconds(),
                                (int) opts.heartbeatInterval().toSeconds(),
                                0
                            ));
                        }
                        p.addLast(new TorOutboundHandler(future, address, opts, isolation));
                    }
                });
            
            bootstrap.connect(host, port).addListener((ChannelFutureListener) f -> {
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

    private Socks5ProxyHandler createProxyHandler(StreamIsolation isolation) {
        InetSocketAddress proxyAddr = new InetSocketAddress(options.socksHost, options.socksPort);
        
        if (isolation.type == StreamIsolation.Type.NONE) {
            return new Socks5ProxyHandler(proxyAddr);
        }
        
        return new Socks5ProxyHandler(proxyAddr, isolation.username, isolation.password);
    }

    public CompletableFuture<TransportConnection> connectWithNewCircuit(TransportAddress address) {
        StreamIsolation isolation = StreamIsolation.unique();
        return connectOnce(address, ConnectionOptions.defaults(), isolation);
    }

    public CompletableFuture<String> createAdditionalHiddenService(String name, int port) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HiddenService hs = createHiddenService(name, port, null);
                hiddenServices.put(name, hs);
                return hs.onionAddress;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private HiddenService createHiddenService(String name, int port, Path keyPath) throws Exception {
        String privateKey = null;
        if (keyPath != null && Files.exists(keyPath)) {
            privateKey = Files.readString(keyPath);
        }
        
        StringBuilder hsCommand = new StringBuilder();
        hsCommand.append("ADD_ONION ");
        
        if (privateKey != null) {
            hsCommand.append(privateKey);
        } else {
            hsCommand.append("NEW:ED25519-V3");
        }
        
        hsCommand.append(" Port=").append(port).append(",127.0.0.1:").append(port);
        
        if (options.persistHiddenService && privateKey == null) {
            hsCommand.append(" Flags=Detach");
        }
        
        String response = sendControlCommand(hsCommand.toString());
        
        String serviceId = null;
        String newPrivateKey = null;
        
        for (String line : response.split("\n")) {
            if (line.startsWith("250-ServiceID=")) {
                serviceId = line.substring("250-ServiceID=".length()).trim();
            } else if (line.startsWith("250-PrivateKey=")) {
                newPrivateKey = line.substring("250-PrivateKey=".length()).trim();
            }
        }
        
        if (serviceId == null) {
            throw new IOException("Failed to create hidden service");
        }
        
        if (newPrivateKey != null && keyPath != null) {
            Files.writeString(keyPath, newPrivateKey);
        }
        
        return new HiddenService(name, serviceId, serviceId + ".onion", port, keyPath);
    }

    private void removeHiddenService(String serviceId) throws Exception {
        sendControlCommand("DEL_ONION " + serviceId);
    }

    private String sendControlCommand(String command) throws Exception {
        try (Socket socket = new Socket(options.socksHost, options.controlPort)) {
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            
            if (options.controlPassword != null && !options.controlPassword.isEmpty()) {
                writer.println("AUTHENTICATE \"" + options.controlPassword + "\"");
            } else {
                writer.println("AUTHENTICATE");
            }
            
            String authResponse = reader.readLine();
            if (!authResponse.startsWith("250")) {
                throw new IOException("Tor authentication failed: " + authResponse);
            }
            
            writer.println(command);
            
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
                if (line.startsWith("250 ") || line.startsWith("5")) {
                    break;
                }
            }
            
            writer.println("QUIT");
            
            return response.toString();
        }
    }

    public CompletableFuture<CircuitInfo> getCircuitInfo(String circuitId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = sendControlCommand("GETINFO circuit-status");
                return parseCircuitInfo(response, circuitId);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private CircuitInfo parseCircuitInfo(String response, String circuitId) {
        for (String line : response.split("\n")) {
            if (line.contains(circuitId)) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    return new CircuitInfo(
                        circuitId,
                        parts[1],
                        Arrays.asList(parts).subList(2, parts.length),
                        Instant.now()
                    );
                }
            }
        }
        return null;
    }

    public CompletableFuture<Void> requestNewCircuit() {
        return CompletableFuture.runAsync(() -> {
            try {
                sendControlCommand("SIGNAL NEWNYM");
                circuitRotations.incrementAndGet();
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public TransportAddress parseAddress(String address) {
        Matcher matcher = ONION_PATTERN.matcher(address.toLowerCase());
        if (matcher.matches()) {
            String onion = matcher.group(2);
            String portStr = matcher.group(3);
            int port = portStr != null ? Integer.parseInt(portStr) : 80;
            return TransportAddress.onion(onion, port);
        }
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        return address.toLowerCase().contains(".onion");
    }

    @Override
    public TransportAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public List<TransportAddress> getLocalAddresses() {
        List<TransportAddress> addresses = new ArrayList<>();
        for (HiddenService hs : hiddenServices.values()) {
            addresses.add(TransportAddress.onion(hs.onionAddress, hs.port));
        }
        return addresses;
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

    public TorStats getTorStats() {
        return new TorStats(
            getStats(),
            circuitRotations.get(),
            hiddenServices.size(),
            circuits.size(),
            primaryOnionAddress
        );
    }

    public String getOnionAddress() {
        return primaryOnionAddress;
    }

    public Map<String, HiddenService> getHiddenServices() {
        return Map.copyOf(hiddenServices);
    }

    private void checkTorAvailability() {
        available = checkSocksConnection();
    }

    private class TorOutboundHandler extends SimpleChannelInboundHandler<Object> {
        private final CompletableFuture<TransportConnection> future;
        private final TransportAddress remoteAddress;
        private final ConnectionOptions opts;
        private final StreamIsolation isolation;
        private WebSocketClientHandshaker handshaker;
        private TorConnection connection;

        TorOutboundHandler(CompletableFuture<TransportConnection> future, 
                          TransportAddress remoteAddress,
                          ConnectionOptions opts,
                          StreamIsolation isolation) {
            this.future = future;
            this.remoteAddress = remoteAddress;
            this.opts = opts;
            this.isolation = isolation;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            String wsUri = "ws://" + remoteAddress.host() + ":" + remoteAddress.port() + "/";
            handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                new java.net.URI(wsUri), WebSocketVersion.V13, null, true, new DefaultHttpHeaders()
            );
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
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                    
                    String id = UUID.randomUUID().toString();
                    connection = new TorConnection(
                        id, ctx.channel(), remoteAddress, localAddress,
                        bytesReceived, bytesSent, messagesReceived, messagesSent,
                        opts, isolation
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
                handleFrame(ctx, frame);
            }
        }

        private void handleFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame textFrame) {
                connection.handleMessage(textFrame.text().getBytes(StandardCharsets.UTF_8));
            } else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
                ByteBuf content = binaryFrame.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                connection.handleMessage(data);
            } else if (frame instanceof PingWebSocketFrame pingFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
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
            if (!future.isDone()) {
                future.completeExceptionally(cause);
            }
            if (connection != null) {
                connection.handleError(cause);
            }
            if (onError != null) {
                onError.accept(new TransportError(TransportType.TOR, cause.getMessage(), cause));
            }
            ctx.close();
        }
    }

    private static class TorConnection implements TransportConnection {
        private final String id;
        private final Channel channel;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong pingsSent = new AtomicLong();
        private final AtomicLong pongsReceived = new AtomicLong();
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile long lastPingSentAt;
        private volatile long avgLatencyMs = 0;
        private final ConnectionOptions opts;
        private final StreamIsolation isolation;
        private volatile ConnectionState state = ConnectionState.OPEN;
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<CloseReason> onCloseWithReason;
        private Consumer<Throwable> onError;

        TorConnection(String id, Channel channel,
                     TransportAddress remoteAddress, TransportAddress localAddress,
                     AtomicLong bytesReceived, AtomicLong bytesSent,
                     AtomicLong messagesReceived, AtomicLong messagesSent,
                     ConnectionOptions opts, StreamIsolation isolation) {
            this.id = id;
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.opts = opts;
            this.isolation = isolation;
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = connectedAt;
        }

        @Override
        public String getId() { return id; }

        @Override
        public TransportType getTransportType() { return TransportType.TOR; }

        @Override
        public TransportAddress getRemoteAddress() { return remoteAddress; }

        @Override
        public TransportAddress getLocalAddress() { return localAddress; }

        @Override
        public boolean isOpen() { return channel.isOpen() && state == ConnectionState.OPEN; }

        @Override
        public ConnectionState getState() { return state; }

        @Override
        public ConnectionOptions getOptions() { return opts; }

        public StreamIsolation getStreamIsolation() { return isolation; }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)))
                .addListener(f -> {
                    if (f.isSuccess()) {
                        bytesSent.addAndGet(data.length);
                        messagesSent.incrementAndGet();
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
            CompletableFuture<Void> future = new CompletableFuture<>();
            channel.writeAndFlush(new TextWebSocketFrame(message))
                .addListener(f -> {
                    if (f.isSuccess()) {
                        bytesSent.addAndGet(message.getBytes(StandardCharsets.UTF_8).length);
                        messagesSent.incrementAndGet();
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
            channel.writeAndFlush(new PingWebSocketFrame(Unpooled.EMPTY_BUFFER))
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        future.completeExceptionally(f.cause());
                    }
                });
            return future;
        }

        void sendPing() {
            lastPingSentAt = System.nanoTime();
            pingsSent.incrementAndGet();
            channel.writeAndFlush(new PingWebSocketFrame(Unpooled.EMPTY_BUFFER));
        }

        void handlePong() {
            pongsReceived.incrementAndGet();
            long latency = (System.nanoTime() - lastPingSentAt) / 1_000_000;
            avgLatencyMs = (avgLatencyMs + latency) / 2;
            lastActivityAt = System.currentTimeMillis();
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
        public void setOnMessage(Consumer<byte[]> handler) { this.onMessage = handler; }

        @Override
        public void setOnClose(Consumer<Void> handler) { this.onClose = handler; }

        @Override
        public void setOnCloseWithReason(Consumer<CloseReason> handler) { this.onCloseWithReason = handler; }

        @Override
        public void setOnError(Consumer<Throwable> handler) { this.onError = handler; }

        @Override
        public ConnectionStats getStats() {
            return new ConnectionStats(
                bytesReceived.get(), bytesSent.get(),
                messagesReceived.get(), messagesSent.get(),
                connectedAt, lastActivityAt,
                0, 0, pingsSent.get(), pongsReceived.get(),
                avgLatencyMs, 0, 0, 0
            );
        }

        void handleMessage(byte[] data) {
            bytesReceived.addAndGet(data.length);
            messagesReceived.incrementAndGet();
            lastActivityAt = System.currentTimeMillis();
            if (onMessage != null) onMessage.accept(data);
        }

        void handleClose(int code, String reason) {
            state = ConnectionState.CLOSED;
            if (onCloseWithReason != null) {
                onCloseWithReason.accept(new CloseReason(code, reason));
            }
            if (onClose != null) onClose.accept(null);
        }

        void handleError(Throwable cause) {
            if (onError != null) onError.accept(cause);
        }
    }

    public record TorOptions(
        String socksHost,
        int socksPort,
        int controlPort,
        String controlPassword,
        int hiddenServicePort,
        boolean persistHiddenService,
        Path hiddenServiceKeyPath,
        boolean enableStreamIsolation,
        boolean enableCircuitRotation,
        Duration circuitRotationInterval
    ) {
        public static TorOptions defaults() {
            return new TorOptions(
                "127.0.0.1", DEFAULT_SOCKS_PORT, DEFAULT_CONTROL_PORT,
                null, 8080, false, null, true, false, Duration.ofMinutes(10)
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String socksHost = "127.0.0.1";
            private int socksPort = DEFAULT_SOCKS_PORT;
            private int controlPort = DEFAULT_CONTROL_PORT;
            private String controlPassword = null;
            private int hiddenServicePort = 8080;
            private boolean persistHiddenService = false;
            private Path hiddenServiceKeyPath = null;
            private boolean enableStreamIsolation = true;
            private boolean enableCircuitRotation = false;
            private Duration circuitRotationInterval = Duration.ofMinutes(10);

            public Builder socksHost(String host) { this.socksHost = host; return this; }
            public Builder socksPort(int port) { this.socksPort = port; return this; }
            public Builder controlPort(int port) { this.controlPort = port; return this; }
            public Builder controlPassword(String password) { this.controlPassword = password; return this; }
            public Builder hiddenServicePort(int port) { this.hiddenServicePort = port; return this; }
            public Builder persistHiddenService(boolean persist) { this.persistHiddenService = persist; return this; }
            public Builder hiddenServiceKeyPath(Path path) { this.hiddenServiceKeyPath = path; return this; }
            public Builder enableStreamIsolation(boolean enable) { this.enableStreamIsolation = enable; return this; }
            public Builder enableCircuitRotation(boolean enable) { this.enableCircuitRotation = enable; return this; }
            public Builder circuitRotationInterval(Duration interval) { this.circuitRotationInterval = interval; return this; }

            public TorOptions build() {
                return new TorOptions(
                    socksHost, socksPort, controlPort, controlPassword,
                    hiddenServicePort, persistHiddenService, hiddenServiceKeyPath,
                    enableStreamIsolation, enableCircuitRotation, circuitRotationInterval
                );
            }
        }
    }

    public record HiddenService(String name, String serviceId, String onionAddress, int port, Path keyPath) {}

    public record CircuitInfo(String id, String status, List<String> path, Instant createdAt) {}

    public record TorStats(
        TransportStats transportStats,
        long circuitRotations,
        int hiddenServiceCount,
        int activeCircuits,
        String primaryOnionAddress
    ) {}

    public static class StreamIsolation {
        public enum Type { NONE, PER_CONNECTION, PER_DESTINATION, UNIQUE }
        
        final Type type;
        final String username;
        final String password;

        private StreamIsolation(Type type, String username, String password) {
            this.type = type;
            this.username = username;
            this.password = password;
        }

        public static StreamIsolation none() {
            return new StreamIsolation(Type.NONE, null, null);
        }

        public static StreamIsolation perConnection(int connectionId) {
            return new StreamIsolation(Type.PER_CONNECTION, "conn-" + connectionId, "isolation");
        }

        public static StreamIsolation perDestination(String destination) {
            return new StreamIsolation(Type.PER_DESTINATION, "dest-" + destination.hashCode(), "isolation");
        }

        public static StreamIsolation unique() {
            return new StreamIsolation(Type.UNIQUE, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }
    }
}
