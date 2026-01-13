package io.supernode.network.transport;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class I2PTransport implements Transport {

    private static final Pattern I2P_PATTERN = Pattern.compile(
        "^(i2p://|ws://)?([a-zA-Z0-9]+\\.i2p):?(\\d+)?(/.*)?$"
    );
    private static final Pattern B32_PATTERN = Pattern.compile(
        "^([a-z2-7]{52}\\.b32\\.i2p)$"
    );
    
    private static final int DEFAULT_SAM_PORT = 7656;
    
    private final I2POptions options;
    private final ConcurrentHashMap<String, I2PConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private Socket samControlSocket;
    private BufferedReader samReader;
    private PrintWriter samWriter;
    private String sessionId;
    private String localDestination;
    private String privateKey;
    private String localB32Address;
    private TransportAddress localAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private volatile boolean available = false;
    private ExecutorService samListenerExecutor;
    private TransportConfig config = TransportConfig.defaults();
    private Instant startTime;
    private volatile HealthStatus lastHealthStatus;
    private ScheduledFuture<?> healthCheckTask;
    private ScheduledFuture<?> keepaliveTask;
    
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();

    public I2PTransport() {
        this(I2POptions.defaults());
    }

    public I2PTransport(I2POptions options) {
        this.options = options;
        this.lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Not started", Instant.now(), 0, 0);
        checkI2PAvailability();
    }

    @Override
    public TransportType getType() {
        return TransportType.I2P;
    }

    @Override
    public String getName() {
        return "I2P (Invisible Internet Project)";
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
                    throw new IllegalStateException("I2P SAM bridge is not available");
                }
                
                initializeSamSession();
                localAddress = TransportAddress.i2p(localB32Address, options.localPort);
                running = true;
                startTime = Instant.now();
                lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, 0);
                
                startAcceptingConnections();
                startHealthCheckTask();
                if (options.enableKeepalive) {
                    startKeepaliveTask();
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

    private void startKeepaliveTask() {
        keepaliveTask = scheduler.scheduleAtFixedRate(
            this::sendKeepalive,
            options.keepaliveInterval.toMillis(),
            options.keepaliveInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void performHealthCheck() {
        long start = System.currentTimeMillis();
        try {
            boolean samAvailable = checkSamConnection();
            long latency = System.currentTimeMillis() - start;
            
            if (samAvailable && samControlSocket != null && !samControlSocket.isClosed()) {
                lastHealthStatus = new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, latency);
            } else {
                healthCheckFailures.incrementAndGet();
                lastHealthStatus = new HealthStatus(
                    HealthState.UNHEALTHY, 
                    "SAM connection lost", 
                    Instant.now(), 
                    healthCheckFailures.get(), 
                    latency
                );
                
                if (options.autoReconnect) {
                    attemptReconnect();
                }
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

    private boolean checkSamConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(options.samHost, options.samPort), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void sendKeepalive() {
        try {
            if (samWriter != null && samControlSocket != null && !samControlSocket.isClosed()) {
                samWriter.println("PING " + System.currentTimeMillis());
                samWriter.flush();
            }
        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }

    private void attemptReconnect() {
        try {
            reconnections.incrementAndGet();
            closeSamConnection();
            initializeSamSession();
            startAcceptingConnections();
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
            if (keepaliveTask != null) keepaliveTask.cancel(false);
            
            connections.values().forEach(conn -> conn.close().join());
            connections.clear();
            
            if (samListenerExecutor != null) {
                samListenerExecutor.shutdownNow();
            }
            
            closeSamConnection();
            scheduler.shutdown();
            
            lastHealthStatus = new HealthStatus(HealthState.STOPPED, "Stopped", Instant.now(), 0, 0);
        });
    }

    private void closeSamConnection() {
        try {
            if (samWriter != null && sessionId != null) {
                samWriter.println("SESSION REMOVE ID=" + sessionId);
            }
            if (samControlSocket != null) {
                samControlSocket.close();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions opts) {
        if (address.type() != TransportType.I2P) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Not an I2P address: " + address));
        }
        
        if (opts.autoReconnect()) {
            return connectWithAutoReconnect(address, opts, 0);
        }
        return connectOnce(address, opts);
    }

    private CompletableFuture<TransportConnection> connectWithAutoReconnect(
            TransportAddress address, ConnectionOptions opts, int attempt) {
        return connectOnce(address, opts).handle((conn, ex) -> {
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
                connectWithAutoReconnect(address, opts, attempt + 1)
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

    private CompletableFuture<TransportConnection> connectOnce(TransportAddress address, ConnectionOptions opts) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        CompletableFuture.runAsync(() -> {
            try {
                Socket streamSocket = new Socket();
                streamSocket.connect(new InetSocketAddress(options.samHost, options.samPort), 
                    (int) opts.connectTimeout().toMillis());
                streamSocket.setSoTimeout((int) opts.readTimeout().toMillis());
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(streamSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(streamSocket.getOutputStream(), true);
                
                writer.println("HELLO VERSION MIN=3.0 MAX=3.3");
                String response = reader.readLine();
                if (!response.contains("RESULT=OK")) {
                    throw new IOException("SAM handshake failed: " + response);
                }
                
                String destination = resolveDestination(address.host());
                
                StringBuilder connectCmd = new StringBuilder();
                connectCmd.append("STREAM CONNECT ID=").append(sessionId);
                connectCmd.append(" DESTINATION=").append(destination);
                connectCmd.append(" SILENT=false");
                
                writer.println(connectCmd.toString());
                response = reader.readLine();
                if (!response.contains("RESULT=OK")) {
                    throw new IOException("I2P stream connect failed: " + response);
                }
                
                String id = UUID.randomUUID().toString();
                I2PConnection connection = new I2PConnection(
                    id, streamSocket, reader, writer,
                    address, localAddress, opts,
                    bytesReceived, bytesSent, messagesReceived, messagesSent
                );
                connections.put(id, connection);
                connectionsOut.incrementAndGet();
                
                startConnectionReader(connection);
                future.complete(connection);
                
            } catch (Exception e) {
                errors.incrementAndGet();
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    @Override
    public TransportAddress parseAddress(String address) {
        Matcher matcher = I2P_PATTERN.matcher(address.toLowerCase());
        if (matcher.matches()) {
            String host = matcher.group(2);
            String portStr = matcher.group(3);
            int port = portStr != null ? Integer.parseInt(portStr) : 80;
            return TransportAddress.i2p(host, port);
        }
        
        Matcher b32Matcher = B32_PATTERN.matcher(address.toLowerCase());
        if (b32Matcher.matches()) {
            return TransportAddress.i2p(b32Matcher.group(1), 80);
        }
        
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        return address.toLowerCase().contains(".i2p");
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

    public I2PStats getI2PStats() {
        return new I2PStats(
            getStats(),
            localDestination,
            localB32Address,
            sessionId,
            options.tunnelLength,
            options.tunnelQuantity
        );
    }

    public String getLocalDestination() {
        return localDestination;
    }

    public String getLocalB32Address() {
        return localB32Address;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public CompletableFuture<Void> saveDestination(Path path) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (privateKey != null) {
                    Files.writeString(path, privateKey);
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private void checkI2PAvailability() {
        available = checkSamConnection();
    }

    private void initializeSamSession() throws IOException {
        samControlSocket = new Socket();
        samControlSocket.connect(new InetSocketAddress(options.samHost, options.samPort), 10000);
        samControlSocket.setSoTimeout(30000);
        samReader = new BufferedReader(new InputStreamReader(samControlSocket.getInputStream()));
        samWriter = new PrintWriter(samControlSocket.getOutputStream(), true);
        
        samWriter.println("HELLO VERSION MIN=3.0 MAX=3.3");
        String response = samReader.readLine();
        if (!response.contains("RESULT=OK")) {
            throw new IOException("SAM handshake failed: " + response);
        }
        
        sessionId = "supernode-" + UUID.randomUUID().toString().substring(0, 8);
        
        StringBuilder sessionCmd = new StringBuilder();
        sessionCmd.append("SESSION CREATE STYLE=STREAM ID=").append(sessionId);
        
        if (options.persistDestination && options.destinationKeyPath != null && 
            Files.exists(options.destinationKeyPath)) {
            String savedKey = Files.readString(options.destinationKeyPath);
            sessionCmd.append(" DESTINATION=").append(savedKey);
        } else if (options.destinationKey != null && !options.destinationKey.isEmpty()) {
            sessionCmd.append(" DESTINATION=").append(options.destinationKey);
        } else {
            sessionCmd.append(" DESTINATION=TRANSIENT");
        }
        
        sessionCmd.append(" inbound.length=").append(options.tunnelLength);
        sessionCmd.append(" outbound.length=").append(options.tunnelLength);
        sessionCmd.append(" inbound.quantity=").append(options.tunnelQuantity);
        sessionCmd.append(" outbound.quantity=").append(options.tunnelQuantity);
        
        if (options.tunnelBackupQuantity > 0) {
            sessionCmd.append(" inbound.backupQuantity=").append(options.tunnelBackupQuantity);
            sessionCmd.append(" outbound.backupQuantity=").append(options.tunnelBackupQuantity);
        }
        
        if (options.enableEncryption) {
            sessionCmd.append(" i2cp.encryptLeaseSet=true");
        }
        
        samWriter.println(sessionCmd.toString());
        response = samReader.readLine();
        
        if (!response.contains("RESULT=OK")) {
            throw new IOException("Failed to create SAM session: " + response);
        }
        
        if (response.contains("DESTINATION=")) {
            int destStart = response.indexOf("DESTINATION=") + "DESTINATION=".length();
            localDestination = response.substring(destStart).split(" ")[0];
        }
        
        samWriter.println("NAMING LOOKUP NAME=ME");
        response = samReader.readLine();
        if (response.contains("VALUE=")) {
            int valueStart = response.indexOf("VALUE=") + "VALUE=".length();
            String fullDest = response.substring(valueStart).split(" ")[0];
            localB32Address = computeB32Address(fullDest);
            privateKey = fullDest;
            
            if (options.persistDestination && options.destinationKeyPath != null) {
                Files.writeString(options.destinationKeyPath, fullDest);
            }
        } else {
            localB32Address = computeB32Address(localDestination);
            privateKey = localDestination;
        }
    }

    private String computeB32Address(String destination) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] destBytes = Base64.getDecoder().decode(
                destination.replace("-", "+").replace("~", "/")
            );
            byte[] hash = sha256.digest(destBytes);
            return encodeBase32(hash).toLowerCase() + ".b32.i2p";
        } catch (Exception e) {
            return destination.substring(0, Math.min(52, destination.length())).toLowerCase() + ".b32.i2p";
        }
    }

    private String encodeBase32(byte[] data) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        
        if (bitsLeft > 0) {
            result.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        
        return result.toString();
    }

    private String resolveDestination(String address) throws IOException {
        samWriter.println("NAMING LOOKUP NAME=" + address);
        String response = samReader.readLine();
        
        if (response.contains("RESULT=OK") && response.contains("VALUE=")) {
            int valueStart = response.indexOf("VALUE=") + "VALUE=".length();
            return response.substring(valueStart).split(" ")[0];
        }
        
        throw new IOException("Failed to resolve I2P address: " + address);
    }

    private void startAcceptingConnections() {
        samListenerExecutor = Executors.newSingleThreadExecutor();
        samListenerExecutor.submit(() -> {
            try {
                Socket acceptSocket = new Socket();
                acceptSocket.connect(new InetSocketAddress(options.samHost, options.samPort), 10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(acceptSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(acceptSocket.getOutputStream(), true);
                
                writer.println("HELLO VERSION MIN=3.0 MAX=3.3");
                reader.readLine();
                
                writer.println("STREAM ACCEPT ID=" + sessionId + " SILENT=false");
                
                while (running) {
                    String line = reader.readLine();
                    if (line == null) break;
                    
                    if (line.contains("STREAM STATUS RESULT=OK")) {
                        handleIncomingConnection(acceptSocket, reader, writer, line);
                        
                        acceptSocket = new Socket();
                        acceptSocket.connect(new InetSocketAddress(options.samHost, options.samPort), 10000);
                        reader = new BufferedReader(new InputStreamReader(acceptSocket.getInputStream()));
                        writer = new PrintWriter(acceptSocket.getOutputStream(), true);
                        writer.println("HELLO VERSION MIN=3.0 MAX=3.3");
                        reader.readLine();
                        writer.println("STREAM ACCEPT ID=" + sessionId + " SILENT=false");
                    }
                }
            } catch (IOException e) {
                if (running) {
                    errors.incrementAndGet();
                    if (onError != null) {
                        onError.accept(new TransportError(TransportType.I2P, e.getMessage(), e));
                    }
                }
            }
        });
    }

    private void handleIncomingConnection(Socket socket, BufferedReader reader, 
                                         PrintWriter writer, String statusLine) {
        String remoteDestination = "unknown";
        if (statusLine.contains("DESTINATION=")) {
            int destStart = statusLine.indexOf("DESTINATION=") + "DESTINATION=".length();
            remoteDestination = statusLine.substring(destStart).split(" ")[0];
        }
        
        String id = UUID.randomUUID().toString();
        TransportAddress remoteAddress = TransportAddress.i2p(computeB32Address(remoteDestination), 0);
        
        I2PConnection connection = new I2PConnection(
            id, socket, reader, writer,
            remoteAddress, localAddress, ConnectionOptions.defaults(),
            bytesReceived, bytesSent, messagesReceived, messagesSent
        );
        connections.put(id, connection);
        connectionsIn.incrementAndGet();
        
        startConnectionReader(connection);
        
        if (onConnection != null) {
            onConnection.accept(connection);
        }
    }

    private void startConnectionReader(I2PConnection connection) {
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while (connection.isOpen() && (line = connection.reader.readLine()) != null) {
                    connection.handleMessage(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                if (connection.isOpen()) {
                    connection.handleClose();
                    connections.remove(connection.getId());
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static class I2PConnection implements TransportConnection {
        private final String id;
        private final Socket socket;
        final BufferedReader reader;
        private final PrintWriter writer;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final ConnectionOptions opts;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong pingsSent = new AtomicLong();
        private final AtomicLong pongsReceived = new AtomicLong();
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile boolean open = true;
        private volatile ConnectionState state = ConnectionState.OPEN;
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<CloseReason> onCloseWithReason;
        private Consumer<Throwable> onError;

        I2PConnection(String id, Socket socket, BufferedReader reader, PrintWriter writer,
                     TransportAddress remoteAddress, TransportAddress localAddress,
                     ConnectionOptions opts,
                     AtomicLong bytesReceived, AtomicLong bytesSent,
                     AtomicLong messagesReceived, AtomicLong messagesSent) {
            this.id = id;
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.opts = opts;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = connectedAt;
        }

        @Override
        public String getId() { return id; }

        @Override
        public TransportType getTransportType() { return TransportType.I2P; }

        @Override
        public TransportAddress getRemoteAddress() { return remoteAddress; }

        @Override
        public TransportAddress getLocalAddress() { return localAddress; }

        @Override
        public boolean isOpen() { return open && !socket.isClosed() && state == ConnectionState.OPEN; }

        @Override
        public ConnectionState getState() { return state; }

        @Override
        public ConnectionOptions getOptions() { return opts; }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            return CompletableFuture.runAsync(() -> {
                writer.println(Base64.getEncoder().encodeToString(data));
                bytesSent.addAndGet(data.length);
                messagesSent.incrementAndGet();
                lastActivityAt = System.currentTimeMillis();
            });
        }

        @Override
        public CompletableFuture<Void> send(String message) {
            return send(message.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public CompletableFuture<Duration> ping() {
            long start = System.nanoTime();
            pingsSent.incrementAndGet();
            return send("PING:" + System.currentTimeMillis())
                .thenApply(v -> Duration.ofNanos(System.nanoTime() - start));
        }

        @Override
        public CompletableFuture<Void> close() {
            return close(1000, "Normal closure");
        }

        @Override
        public CompletableFuture<Void> close(int code, String reason) {
            return CompletableFuture.runAsync(() -> {
                state = ConnectionState.CLOSING;
                open = false;
                try {
                    socket.close();
                } catch (IOException ignored) {}
                state = ConnectionState.CLOSED;
            });
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
                0, 0, 0, 0
            );
        }

        void handleMessage(byte[] data) {
            bytesReceived.addAndGet(data.length);
            messagesReceived.incrementAndGet();
            lastActivityAt = System.currentTimeMillis();
            
            String msg = new String(data, StandardCharsets.UTF_8);
            if (msg.startsWith("PONG:")) {
                pongsReceived.incrementAndGet();
                return;
            }
            
            if (onMessage != null) onMessage.accept(data);
        }

        void handleClose() {
            state = ConnectionState.CLOSED;
            open = false;
            if (onCloseWithReason != null) {
                onCloseWithReason.accept(CloseReason.NORMAL);
            }
            if (onClose != null) onClose.accept(null);
        }
    }

    public record I2POptions(
        String samHost,
        int samPort,
        int localPort,
        boolean persistDestination,
        Path destinationKeyPath,
        String destinationKey,
        int tunnelLength,
        int tunnelQuantity,
        int tunnelBackupQuantity,
        boolean enableEncryption,
        boolean enableKeepalive,
        Duration keepaliveInterval,
        boolean autoReconnect
    ) {
        public static I2POptions defaults() {
            return new I2POptions(
                "127.0.0.1", DEFAULT_SAM_PORT, 8080,
                false, null, null,
                3, 2, 0,
                true, true, Duration.ofMinutes(1), true
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String samHost = "127.0.0.1";
            private int samPort = DEFAULT_SAM_PORT;
            private int localPort = 8080;
            private boolean persistDestination = false;
            private Path destinationKeyPath = null;
            private String destinationKey = null;
            private int tunnelLength = 3;
            private int tunnelQuantity = 2;
            private int tunnelBackupQuantity = 0;
            private boolean enableEncryption = true;
            private boolean enableKeepalive = true;
            private Duration keepaliveInterval = Duration.ofMinutes(1);
            private boolean autoReconnect = true;

            public Builder samHost(String host) { this.samHost = host; return this; }
            public Builder samPort(int port) { this.samPort = port; return this; }
            public Builder localPort(int port) { this.localPort = port; return this; }
            public Builder persistDestination(boolean persist) { this.persistDestination = persist; return this; }
            public Builder destinationKeyPath(Path path) { this.destinationKeyPath = path; return this; }
            public Builder destinationKey(String key) { this.destinationKey = key; return this; }
            public Builder tunnelLength(int length) { this.tunnelLength = length; return this; }
            public Builder tunnelQuantity(int quantity) { this.tunnelQuantity = quantity; return this; }
            public Builder tunnelBackupQuantity(int quantity) { this.tunnelBackupQuantity = quantity; return this; }
            public Builder enableEncryption(boolean enable) { this.enableEncryption = enable; return this; }
            public Builder enableKeepalive(boolean enable) { this.enableKeepalive = enable; return this; }
            public Builder keepaliveInterval(Duration interval) { this.keepaliveInterval = interval; return this; }
            public Builder autoReconnect(boolean reconnect) { this.autoReconnect = reconnect; return this; }

            public I2POptions build() {
                return new I2POptions(
                    samHost, samPort, localPort,
                    persistDestination, destinationKeyPath, destinationKey,
                    tunnelLength, tunnelQuantity, tunnelBackupQuantity,
                    enableEncryption, enableKeepalive, keepaliveInterval, autoReconnect
                );
            }
        }
    }

    public record I2PStats(
        TransportStats transportStats,
        String localDestination,
        String localB32Address,
        String sessionId,
        int tunnelLength,
        int tunnelQuantity
    ) {}
}
