package io.supernode.network.transport;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Enhanced Hyphanet (Freenet) transport with request priorities, splitfile support,
 * progress callbacks, key persistence, and health monitoring.
 */
public class HyphanetTransport implements Transport {

    private static final Pattern CHK_PATTERN = Pattern.compile(
        "^(CHK@[A-Za-z0-9~-]+,[A-Za-z0-9~-]+,[A-Za-z0-9~-]+)$"
    );
    private static final Pattern SSK_PATTERN = Pattern.compile(
        "^(SSK@[A-Za-z0-9~-]+,[A-Za-z0-9~-]+,[A-Za-z0-9~-]+/.*)$"
    );
    private static final Pattern USK_PATTERN = Pattern.compile(
        "^(USK@[A-Za-z0-9~-]+,[A-Za-z0-9~-]+,[A-Za-z0-9~-]+/.*/\\d+/.*)$"
    );
    
    private static final int DEFAULT_FCP_PORT = 9481;
    private static final int SPLITFILE_THRESHOLD = 256 * 1024; // 256KB
    private static final int SPLITFILE_SEGMENT_SIZE = 32 * 1024; // 32KB segments
    
    // Priority classes as defined by Freenet FCP
    public static final int PRIORITY_MAXIMUM = 0;
    public static final int PRIORITY_INTERACTIVE = 1;
    public static final int PRIORITY_SEMI_INTERACTIVE = 2;
    public static final int PRIORITY_UPDATABLE = 3;
    public static final int PRIORITY_BULK = 4;
    public static final int PRIORITY_PREFETCH = 5;
    public static final int PRIORITY_MINIMUM = 6;
    
    private final HyphanetOptions options;
    private final String clientName;
    private final ConcurrentHashMap<String, HyphanetConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<QueuedRequest> requestQueue;
    private final ConcurrentHashMap<String, ProgressTracker> progressTrackers = new ConcurrentHashMap<>();
    
    private volatile Socket fcpSocket;
    private volatile BufferedReader fcpReader;
    private volatile PrintWriter fcpWriter;
    private volatile OutputStream fcpOutputStream;
    private String insertUri;
    private String requestUri;
    private TransportAddress localAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private volatile boolean available = false;
    private ExecutorService fcpListenerExecutor;
    private ExecutorService requestProcessorExecutor;
    private ScheduledExecutorService healthCheckExecutor;
    private ScheduledExecutorService reconnectExecutor;
    private TransportConfig config = TransportConfig.defaults();
    
    // Stats
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();
    private final AtomicLong requestsQueued = new AtomicLong();
    private final AtomicLong requestsCompleted = new AtomicLong();
    private final AtomicLong requestsFailed = new AtomicLong();
    private final AtomicLong splitfileUploads = new AtomicLong();
    private final AtomicLong splitfileDownloads = new AtomicLong();
    private volatile Instant startTime;
    private volatile Instant lastHealthCheck;
    private volatile HealthState healthState = HealthState.STOPPED;
    private volatile String healthMessage = "Not started";
    private final AtomicInteger consecutiveHealthFailures = new AtomicInteger(0);
    private volatile long lastHealthLatencyMs = 0;
    private final Object fcpLock = new Object();

    public HyphanetTransport() {
        this(HyphanetOptions.defaults());
    }

    public HyphanetTransport(HyphanetOptions options) {
        this.options = options;
        this.clientName = options.clientName != null ? options.clientName : 
            "supernode-" + UUID.randomUUID().toString().substring(0, 8);
        this.requestQueue = new PriorityBlockingQueue<>(100, 
            Comparator.comparingInt(QueuedRequest::effectivePriority));
        checkHyphanetAvailability();
    }

    @Override
    public TransportType getType() {
        return TransportType.HYPHANET;
    }

    @Override
    public String getName() {
        return "Hyphanet (Freenet)";
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
                    throw new IllegalStateException("Hyphanet FCP is not available at " + 
                        options.fcpHost + ":" + options.fcpPort);
                }
                
                initializeFcpConnection();
                loadOrGenerateKeypair();
                localAddress = TransportAddress.hyphanet(requestUri);
                running = true;
                startTime = Instant.now();
                healthState = HealthState.HEALTHY;
                healthMessage = "OK";
                
                startFcpListener();
                startRequestProcessor();
                startHealthCheckTask();
                
                return localAddress;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            running = false;
            healthState = HealthState.STOPPED;
            healthMessage = "Stopped";
            
            // Cancel pending requests
            pendingRequests.values().forEach(req -> 
                req.future.completeExceptionally(new IOException("Transport stopped")));
            pendingRequests.clear();
            requestQueue.clear();
            
            connections.values().forEach(conn -> conn.close().join());
            connections.clear();
            
            shutdownExecutor(fcpListenerExecutor, "FCP listener");
            shutdownExecutor(requestProcessorExecutor, "Request processor");
            shutdownExecutor(healthCheckExecutor, "Health check");
            shutdownExecutor(reconnectExecutor, "Reconnect");
            
            synchronized (fcpLock) {
                try {
                    if (fcpWriter != null) {
                        sendFcpMessage("Disconnect", Map.of());
                    }
                    if (fcpSocket != null) {
                        fcpSocket.close();
                    }
                } catch (IOException ignored) {}
            }
        });
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions options) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        if (address.type() != TransportType.HYPHANET) {
            future.completeExceptionally(new IllegalArgumentException("Not a Hyphanet address: " + address));
            return future;
        }
        
        String id = UUID.randomUUID().toString();
        HyphanetConnection connection = new HyphanetConnection(
            id, address, localAddress, this, options,
            bytesReceived, bytesSent, messagesReceived, messagesSent
        );
        connections.put(id, connection);
        connectionsOut.incrementAndGet();
        future.complete(connection);
        
        return future;
    }

    @Override
    public TransportAddress parseAddress(String address) {
        if (CHK_PATTERN.matcher(address).matches() ||
            SSK_PATTERN.matcher(address).matches() ||
            USK_PATTERN.matcher(address).matches()) {
            return TransportAddress.hyphanet(address);
        }
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        return address.startsWith("CHK@") || 
               address.startsWith("SSK@") || 
               address.startsWith("USK@");
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
    
    /**
     * Get Hyphanet-specific statistics.
     */
    public HyphanetStats getHyphanetStats() {
        return new HyphanetStats(
            requestsQueued.get(),
            requestsCompleted.get(),
            requestsFailed.get(),
            requestQueue.size(),
            pendingRequests.size(),
            splitfileUploads.get(),
            splitfileDownloads.get(),
            insertUri != null,
            options.persistKeys
        );
    }

    public String getInsertUri() {
        return insertUri;
    }

    public String getRequestUri() {
        return requestUri;
    }
    
    /**
     * Get data from a Hyphanet URI with default options.
     */
    public CompletableFuture<byte[]> getData(String uri) {
        return getData(uri, RequestOptions.defaults());
    }
    
    /**
     * Get data from a Hyphanet URI with specified options.
     */
    public CompletableFuture<byte[]> getData(String uri, RequestOptions options) {
        return getData(uri, options, null);
    }
    
    /**
     * Get data from a Hyphanet URI with progress callback.
     */
    public CompletableFuture<byte[]> getData(String uri, RequestOptions options, 
                                              Consumer<ProgressUpdate> progressCallback) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        String identifier = "get-" + UUID.randomUUID().toString();
        
        PendingRequest<byte[]> pending = new PendingRequest<>(identifier, future, progressCallback);
        pendingRequests.put(identifier, pending);
        
        if (progressCallback != null) {
            progressTrackers.put(identifier, new ProgressTracker());
        }
        
        QueuedRequest queuedRequest = new QueuedRequest(
            identifier, RequestType.GET, uri, null, null, options,
            System.currentTimeMillis(), progressCallback
        );
        
        requestQueue.offer(queuedRequest);
        requestsQueued.incrementAndGet();
        
        return future;
    }
    
    /**
     * Put data to Hyphanet with default options, returning the CHK URI.
     */
    public CompletableFuture<String> putData(byte[] data, String mimeType) {
        return putData(data, mimeType, RequestOptions.defaults());
    }
    
    /**
     * Put data to Hyphanet with specified options.
     */
    public CompletableFuture<String> putData(byte[] data, String mimeType, RequestOptions options) {
        return putData(data, mimeType, options, null);
    }
    
    /**
     * Put data to Hyphanet with progress callback.
     */
    public CompletableFuture<String> putData(byte[] data, String mimeType, RequestOptions options,
                                              Consumer<ProgressUpdate> progressCallback) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String identifier = "put-" + UUID.randomUUID().toString();
        
        PendingRequest<String> pending = new PendingRequest<>(identifier, future, progressCallback);
        pendingRequests.put(identifier, pending);
        
        if (progressCallback != null) {
            progressTrackers.put(identifier, new ProgressTracker());
        }
        
        // Check if we need splitfile handling
        boolean useSplitfile = data.length > SPLITFILE_THRESHOLD && options.allowSplitfile;
        if (useSplitfile) {
            splitfileUploads.incrementAndGet();
        }
        
        QueuedRequest queuedRequest = new QueuedRequest(
            identifier, RequestType.PUT, "CHK@", data, 
            mimeType != null ? mimeType : "application/octet-stream",
            options, System.currentTimeMillis(), progressCallback
        );
        
        requestQueue.offer(queuedRequest);
        requestsQueued.incrementAndGet();
        
        return future;
    }
    
    /**
     * Put data to an SSK path.
     */
    public CompletableFuture<String> putDataToSsk(byte[] data, String path, String mimeType) {
        return putDataToSsk(data, path, mimeType, RequestOptions.defaults());
    }
    
    /**
     * Put data to an SSK path with options.
     */
    public CompletableFuture<String> putDataToSsk(byte[] data, String path, String mimeType,
                                                   RequestOptions options) {
        return putDataToSsk(data, path, mimeType, options, null);
    }
    
    /**
     * Put data to an SSK path with progress callback.
     */
    public CompletableFuture<String> putDataToSsk(byte[] data, String path, String mimeType,
                                                   RequestOptions options,
                                                   Consumer<ProgressUpdate> progressCallback) {
        CompletableFuture<String> future = new CompletableFuture<>();
        String identifier = "put-ssk-" + UUID.randomUUID().toString();
        
        PendingRequest<String> pending = new PendingRequest<>(identifier, future, progressCallback);
        pendingRequests.put(identifier, pending);
        
        if (progressCallback != null) {
            progressTrackers.put(identifier, new ProgressTracker());
        }
        
        String targetUri = insertUri + path;
        
        QueuedRequest queuedRequest = new QueuedRequest(
            identifier, RequestType.PUT_SSK, targetUri, data,
            mimeType != null ? mimeType : "application/octet-stream",
            options, System.currentTimeMillis(), progressCallback
        );
        
        requestQueue.offer(queuedRequest);
        requestsQueued.incrementAndGet();
        
        return future;
    }
    
    /**
     * Generate a new SSK keypair.
     */
    public CompletableFuture<SskKeypair> generateKeypair() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                synchronized (fcpLock) {
                    String identifier = "keygen-" + System.currentTimeMillis();
                    sendFcpMessage("GenerateSSK", Map.of("Identifier", identifier));
                    
                    Map<String, String> response = readFcpMessage();
                    if (!"SSKKeypair".equals(response.get("_message"))) {
                        throw new IOException("Failed to generate SSK keypair: " + response.get("_message"));
                    }
                    
                    return new SskKeypair(response.get("InsertURI"), response.get("RequestURI"));
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private void checkHyphanetAvailability() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(options.fcpHost, options.fcpPort), 
                          (int) options.connectionTimeout.toMillis());
            available = true;
        } catch (IOException e) {
            available = false;
        }
    }

    private void initializeFcpConnection() throws IOException {
        synchronized (fcpLock) {
            fcpSocket = new Socket(options.fcpHost, options.fcpPort);
            fcpSocket.setSoTimeout((int) options.readTimeout.toMillis());
            fcpOutputStream = fcpSocket.getOutputStream();
            fcpReader = new BufferedReader(new InputStreamReader(fcpSocket.getInputStream()));
            fcpWriter = new PrintWriter(fcpOutputStream, true);
            
            sendFcpMessage("ClientHello", Map.of(
                "Name", clientName,
                "ExpectedVersion", "2.0"
            ));
            
            Map<String, String> response = readFcpMessage();
            if (!"NodeHello".equals(response.get("_message"))) {
                throw new IOException("Unexpected FCP response: " + response.get("_message"));
            }
        }
    }
    
    private void loadOrGenerateKeypair() throws IOException {
        if (options.persistKeys && options.keyPath != null) {
            Path keyFile = Path.of(options.keyPath);
            if (Files.exists(keyFile)) {
                try {
                    Properties props = new Properties();
                    try (InputStream is = Files.newInputStream(keyFile)) {
                        props.load(is);
                    }
                    insertUri = props.getProperty("insertUri");
                    requestUri = props.getProperty("requestUri");
                    if (insertUri != null && requestUri != null) {
                        return;
                    }
                } catch (IOException e) {
                    // Fall through to generate new keys
                }
            }
        }
        
        // Generate new keypair
        synchronized (fcpLock) {
            sendFcpMessage("GenerateSSK", Map.of(
                "Identifier", "keygen-" + System.currentTimeMillis()
            ));
            
            Map<String, String> response = readFcpMessage();
            if (!"SSKKeypair".equals(response.get("_message"))) {
                throw new IOException("Failed to generate SSK keypair: " + response.get("_message"));
            }
            
            insertUri = response.get("InsertURI");
            requestUri = response.get("RequestURI");
        }
        
        // Persist if configured
        if (options.persistKeys && options.keyPath != null) {
            try {
                Path keyFile = Path.of(options.keyPath);
                Files.createDirectories(keyFile.getParent());
                Properties props = new Properties();
                props.setProperty("insertUri", insertUri);
                props.setProperty("requestUri", requestUri);
                try (OutputStream os = Files.newOutputStream(keyFile)) {
                    props.store(os, "Hyphanet SSK Keypair");
                }
            } catch (IOException e) {
                // Log but don't fail
                if (onError != null) {
                    onError.accept(new TransportError(TransportType.HYPHANET, 
                        "Failed to persist keys: " + e.getMessage(), e));
                }
            }
        }
    }

    private void sendFcpMessage(String messageName, Map<String, String> fields) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(messageName).append("\n");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        sb.append("EndMessage\n");
        fcpWriter.print(sb.toString());
        fcpWriter.flush();
    }
    
    private void sendFcpMessageWithData(String messageName, Map<String, String> fields, 
                                        byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(messageName).append("\n");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        sb.append("DataLength=").append(data.length).append("\n");
        sb.append("Data\n");
        fcpWriter.print(sb.toString());
        fcpWriter.flush();
        fcpOutputStream.write(data);
        fcpOutputStream.flush();
        bytesSent.addAndGet(data.length);
    }

    private Map<String, String> readFcpMessage() throws IOException {
        Map<String, String> result = new HashMap<>();
        String line = fcpReader.readLine();
        if (line == null) {
            throw new IOException("Connection closed");
        }
        result.put("_message", line);
        
        while ((line = fcpReader.readLine()) != null) {
            if ("EndMessage".equals(line) || "Data".equals(line)) {
                break;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        
        return result;
    }

    private void startFcpListener() {
        fcpListenerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hyphanet-fcp-listener");
            t.setDaemon(true);
            return t;
        });
        
        fcpListenerExecutor.submit(() -> {
            while (running) {
                try {
                    Map<String, String> message;
                    synchronized (fcpLock) {
                        if (!running) break;
                        message = readFcpMessage();
                    }
                    handleFcpMessage(message);
                } catch (IOException e) {
                    if (running) {
                        errors.incrementAndGet();
                        healthState = HealthState.UNHEALTHY;
                        healthMessage = "FCP connection lost: " + e.getMessage();
                        
                        if (options.autoReconnect) {
                            scheduleReconnect();
                        } else if (onError != null) {
                            onError.accept(new TransportError(TransportType.HYPHANET, e.getMessage(), e));
                        }
                    }
                    break;
                }
            }
        });
    }
    
    private void startRequestProcessor() {
        requestProcessorExecutor = Executors.newFixedThreadPool(
            options.maxConcurrentRequests,
            r -> {
                Thread t = new Thread(r, "hyphanet-request-processor");
                t.setDaemon(true);
                return t;
            }
        );
        
        // Start worker threads
        for (int i = 0; i < options.maxConcurrentRequests; i++) {
            requestProcessorExecutor.submit(this::processRequestQueue);
        }
    }
    
    private void processRequestQueue() {
        while (running) {
            try {
                QueuedRequest request = requestQueue.poll(1, TimeUnit.SECONDS);
                if (request == null) continue;
                
                processRequest(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void processRequest(QueuedRequest request) {
        try {
            synchronized (fcpLock) {
                if (!running) {
                    failRequest(request.identifier, new IOException("Transport stopped"));
                    return;
                }
                
                switch (request.type) {
                    case GET -> processGetRequest(request);
                    case PUT, PUT_SSK -> processPutRequest(request);
                }
            }
        } catch (IOException e) {
            errors.incrementAndGet();
            failRequest(request.identifier, e);
        }
    }
    
    private void processGetRequest(QueuedRequest request) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("URI", request.uri);
        fields.put("Identifier", request.identifier);
        fields.put("Verbosity", String.valueOf(request.options.verbosity));
        fields.put("ReturnType", "direct");
        fields.put("MaxRetries", String.valueOf(request.options.maxRetries));
        fields.put("PriorityClass", String.valueOf(request.options.priorityClass));
        
        if (request.options.realTimeFlag) {
            fields.put("RealTimeFlag", "true");
        }
        
        sendFcpMessage("ClientGet", fields);
        messagesSent.incrementAndGet();
    }
    
    private void processPutRequest(QueuedRequest request) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("URI", request.uri);
        fields.put("Identifier", request.identifier);
        fields.put("Verbosity", String.valueOf(request.options.verbosity));
        fields.put("MaxRetries", String.valueOf(request.options.maxRetries));
        fields.put("PriorityClass", String.valueOf(request.options.priorityClass));
        fields.put("GetCHKOnly", "false");
        fields.put("DontCompress", request.options.compress ? "false" : "true");
        fields.put("Metadata.ContentType", request.mimeType);
        fields.put("UploadFrom", "direct");
        
        if (request.options.realTimeFlag) {
            fields.put("RealTimeFlag", "true");
        }
        
        sendFcpMessageWithData("ClientPut", fields, request.data);
        messagesSent.incrementAndGet();
    }
    
    private void failRequest(String identifier, Throwable cause) {
        PendingRequest<?> pending = pendingRequests.remove(identifier);
        if (pending != null) {
            pending.future.completeExceptionally(cause);
            requestsFailed.incrementAndGet();
        }
        progressTrackers.remove(identifier);
    }
    
    private void startHealthCheckTask() {
        if (!options.enableHealthCheck) return;
        
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hyphanet-health-check");
            t.setDaemon(true);
            return t;
        });
        
        healthCheckExecutor.scheduleAtFixedRate(
            () -> performHealthCheck().exceptionally(e -> false),
            config.healthCheckInterval().toMillis(),
            config.healthCheckInterval().toMillis(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private CompletableFuture<Boolean> performHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            lastHealthCheck = Instant.now();
            
            try {
                // Try to send a GetNode message to verify FCP is responding
                synchronized (fcpLock) {
                    if (fcpSocket == null || fcpSocket.isClosed()) {
                        throw new IOException("FCP socket not connected");
                    }
                    
                    sendFcpMessage("GetNode", Map.of(
                        "Identifier", "health-" + System.currentTimeMillis(),
                        "GiveOpennetRef", "false",
                        "WithPrivate", "false",
                        "WithVolatile", "false"
                    ));
                }
                
                lastHealthLatencyMs = System.currentTimeMillis() - startTime;
                consecutiveHealthFailures.set(0);
                healthState = HealthState.HEALTHY;
                healthMessage = "OK";
                return true;
                
            } catch (IOException e) {
                lastHealthLatencyMs = System.currentTimeMillis() - startTime;
                int failures = consecutiveHealthFailures.incrementAndGet();
                healthCheckFailures.incrementAndGet();
                
                if (failures >= config.healthCheckFailureThreshold()) {
                    healthState = HealthState.UNHEALTHY;
                    healthMessage = "Health check failed: " + e.getMessage();
                    
                    if (options.autoReconnect) {
                        scheduleReconnect();
                    }
                } else {
                    healthState = HealthState.DEGRADED;
                    healthMessage = "Health check failed (" + failures + "/" + 
                                   config.healthCheckFailureThreshold() + "): " + e.getMessage();
                }
                return false;
            }
        });
    }
    
    private void scheduleReconnect() {
        if (reconnectExecutor == null) {
            reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hyphanet-reconnect");
                t.setDaemon(true);
                return t;
            });
        }
        
        reconnectExecutor.schedule(this::attemptReconnect, 
            options.reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    private void attemptReconnect() {
        if (!running) return;
        
        int attempt = 0;
        long delay = options.reconnectDelay.toMillis();
        
        while (running && attempt < options.maxReconnectAttempts) {
            attempt++;
            try {
                synchronized (fcpLock) {
                    if (fcpSocket != null) {
                        try { fcpSocket.close(); } catch (IOException ignored) {}
                    }
                    initializeFcpConnection();
                }
                
                reconnections.incrementAndGet();
                healthState = HealthState.HEALTHY;
                healthMessage = "Reconnected after " + attempt + " attempt(s)";
                consecutiveHealthFailures.set(0);
                
                // Restart FCP listener
                if (fcpListenerExecutor != null && !fcpListenerExecutor.isShutdown()) {
                    fcpListenerExecutor.shutdownNow();
                }
                startFcpListener();
                
                return;
            } catch (IOException e) {
                errors.incrementAndGet();
                healthMessage = "Reconnect attempt " + attempt + " failed: " + e.getMessage();
                
                if (attempt < options.maxReconnectAttempts) {
                    try {
                        Thread.sleep(delay);
                        delay = Math.min(delay * 2, options.reconnectMaxDelay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        healthState = HealthState.UNHEALTHY;
        healthMessage = "Failed to reconnect after " + attempt + " attempts";
        
        if (onError != null) {
            onError.accept(new TransportError(TransportType.HYPHANET, 
                healthMessage, new IOException(healthMessage)));
        }
    }

    private void handleFcpMessage(Map<String, String> message) throws IOException {
        String messageType = message.get("_message");
        String identifier = message.get("Identifier");
        
        switch (messageType) {
            case "AllData" -> handleAllData(message, identifier);
            case "PutSuccessful" -> handlePutSuccessful(message, identifier);
            case "GetFailed", "PutFailed" -> handleRequestFailed(message, identifier);
            case "SimpleProgress" -> handleProgress(message, identifier);
            case "ProtocolError" -> handleProtocolError(message);
            case "NodeHello", "NodeData" -> {} // Ignore expected responses
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleAllData(Map<String, String> message, String identifier) throws IOException {
        PendingRequest<byte[]> pending = (PendingRequest<byte[]>) pendingRequests.remove(identifier);
        if (pending != null) {
            int dataLength = Integer.parseInt(message.get("DataLength"));
            byte[] data = new byte[dataLength];
            
            InputStream is = fcpSocket.getInputStream();
            int read = 0;
            while (read < dataLength) {
                int r = is.read(data, read, dataLength - read);
                if (r < 0) break;
                read += r;
            }
            
            bytesReceived.addAndGet(dataLength);
            messagesReceived.incrementAndGet();
            requestsCompleted.incrementAndGet();
            pending.future.complete(data);
        }
        progressTrackers.remove(identifier);
    }
    
    @SuppressWarnings("unchecked")
    private void handlePutSuccessful(Map<String, String> message, String identifier) {
        PendingRequest<String> pending = (PendingRequest<String>) pendingRequests.remove(identifier);
        if (pending != null) {
            String uri = message.get("URI");
            messagesReceived.incrementAndGet();
            requestsCompleted.incrementAndGet();
            pending.future.complete(uri);
        }
        progressTrackers.remove(identifier);
    }
    
    private void handleRequestFailed(Map<String, String> message, String identifier) {
        PendingRequest<?> pending = pendingRequests.remove(identifier);
        if (pending != null) {
            String codeDesc = message.get("CodeDescription");
            String code = message.get("Code");
            pending.future.completeExceptionally(
                new IOException("Request failed [" + code + "]: " + codeDesc)
            );
            requestsFailed.incrementAndGet();
        }
        errors.incrementAndGet();
        progressTrackers.remove(identifier);
    }
    
    private void handleProgress(Map<String, String> message, String identifier) {
        PendingRequest<?> pending = pendingRequests.get(identifier);
        ProgressTracker tracker = progressTrackers.get(identifier);
        
        if (pending != null && pending.progressCallback != null && tracker != null) {
            int total = parseIntOrDefault(message.get("Total"), 0);
            int required = parseIntOrDefault(message.get("Required"), 0);
            int succeeded = parseIntOrDefault(message.get("Succeeded"), 0);
            int failed = parseIntOrDefault(message.get("Failed"), 0);
            boolean finalizedTotal = "true".equals(message.get("FinalizedTotal"));
            
            tracker.update(total, required, succeeded, failed, finalizedTotal);
            
            ProgressUpdate update = new ProgressUpdate(
                identifier, total, required, succeeded, failed, 
                finalizedTotal, tracker.getProgressPercent()
            );
            
            try {
                pending.progressCallback.accept(update);
            } catch (Exception e) {
                // Don't let callback errors affect request processing
            }
        }
    }
    
    private void handleProtocolError(Map<String, String> message) {
        errors.incrementAndGet();
        if (onError != null) {
            onError.accept(new TransportError(
                TransportType.HYPHANET,
                "Protocol error: " + message.get("CodeDescription"),
                null
            ));
        }
    }
    
    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== Inner Classes ====================
    
    private static class HyphanetConnection implements TransportConnection {
        private final String id;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final HyphanetTransport transport;
        private final ConnectionOptions options;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong framesIn = new AtomicLong();
        private final AtomicLong framesOut = new AtomicLong();
        private final AtomicLong pingsIn = new AtomicLong();
        private final AtomicLong pingsOut = new AtomicLong();
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile boolean open = true;
        private volatile ConnectionState state = ConnectionState.OPEN;
        private volatile long latencyMs = 0;
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<Throwable> onError;

        HyphanetConnection(String id, TransportAddress remoteAddress, TransportAddress localAddress,
                          HyphanetTransport transport, ConnectionOptions options,
                          AtomicLong bytesReceived, AtomicLong bytesSent,
                          AtomicLong messagesReceived, AtomicLong messagesSent) {
            this.id = id;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.transport = transport;
            this.options = options;
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
        public TransportType getTransportType() { return TransportType.HYPHANET; }

        @Override
        public TransportAddress getRemoteAddress() { return remoteAddress; }

        @Override
        public TransportAddress getLocalAddress() { return localAddress; }

        @Override
        public boolean isOpen() { return open && state == ConnectionState.OPEN; }
        
        @Override
        public ConnectionState getState() { return state; }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            if (!isOpen()) {
                return CompletableFuture.failedFuture(new IOException("Connection closed"));
            }
            
            return transport.putData(data, "application/octet-stream")
                .thenAccept(uri -> {
                    bytesSent.addAndGet(data.length);
                    messagesSent.incrementAndGet();
                    framesOut.incrementAndGet();
                    lastActivityAt = System.currentTimeMillis();
                });
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
                open = false;
                state = ConnectionState.CLOSED;
                transport.connections.remove(id);
                if (onClose != null) onClose.accept(null);
            });
        }
        
        @Override
        public CompletableFuture<Duration> ping() {
            long start = System.currentTimeMillis();
            pingsOut.incrementAndGet();
            // Hyphanet doesn't have a true ping, so we just return current latency
            return CompletableFuture.completedFuture(Duration.ofMillis(latencyMs));
        }
        
        @Override
        public void pause() {
            // Not supported for Hyphanet connections
        }
        
        @Override
        public void resume() {
            // Not supported for Hyphanet connections
        }
        
        @Override
        public BackpressureStatus getBackpressureStatus() {
            return new BackpressureStatus(0, Long.MAX_VALUE, false);
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

        void fetchAndDeliver(String uri) {
            transport.getData(uri)
                .thenAccept(data -> {
                    bytesReceived.addAndGet(data.length);
                    messagesReceived.incrementAndGet();
                    framesIn.incrementAndGet();
                    lastActivityAt = System.currentTimeMillis();
                    if (onMessage != null) onMessage.accept(data);
                })
                .exceptionally(e -> {
                    if (onError != null) onError.accept(e);
                    return null;
                });
        }
    }
    
    private enum RequestType {
        GET, PUT, PUT_SSK
    }
    
    private record QueuedRequest(
        String identifier,
        RequestType type,
        String uri,
        byte[] data,
        String mimeType,
        RequestOptions options,
        long queuedAt,
        Consumer<ProgressUpdate> progressCallback
    ) {
        int effectivePriority() {
            // Lower number = higher priority
            // Factor in queue time to prevent starvation
            long waitTimeSeconds = (System.currentTimeMillis() - queuedAt) / 1000;
            int agingBonus = (int) Math.min(waitTimeSeconds / 60, options.priorityClass);
            return options.priorityClass - agingBonus;
        }
    }
    
    private static class PendingRequest<T> {
        final String identifier;
        final CompletableFuture<T> future;
        final Consumer<ProgressUpdate> progressCallback;
        
        PendingRequest(String identifier, CompletableFuture<T> future, 
                      Consumer<ProgressUpdate> progressCallback) {
            this.identifier = identifier;
            this.future = future;
            this.progressCallback = progressCallback;
        }
    }
    
    private static class ProgressTracker {
        private int total;
        private int required;
        private int succeeded;
        private int failed;
        private boolean finalized;
        
        void update(int total, int required, int succeeded, int failed, boolean finalized) {
            this.total = total;
            this.required = required;
            this.succeeded = succeeded;
            this.failed = failed;
            this.finalized = finalized;
        }
        
        double getProgressPercent() {
            if (required <= 0) return 0;
            return Math.min(100.0, (succeeded * 100.0) / required);
        }
    }
    
    // ==================== Public Records ====================
    
    /**
     * Progress update for ongoing requests.
     */
    public record ProgressUpdate(
        String identifier,
        int total,
        int required,
        int succeeded,
        int failed,
        boolean finalizedTotal,
        double progressPercent
    ) {}
    
    /**
     * SSK keypair containing insert and request URIs.
     */
    public record SskKeypair(String insertUri, String requestUri) {}
    
    /**
     * Hyphanet-specific statistics.
     */
    public record HyphanetStats(
        long requestsQueued,
        long requestsCompleted,
        long requestsFailed,
        int currentQueueSize,
        int pendingRequests,
        long splitfileUploads,
        long splitfileDownloads,
        boolean hasKeypair,
        boolean keysPersisted
    ) {}
    
    /**
     * Options for individual requests.
     */
    public record RequestOptions(
        int priority,
        int priorityClass,
        int maxRetries,
        boolean realTimeFlag,
        boolean compress,
        boolean allowSplitfile,
        int verbosity
    ) {
        public static RequestOptions defaults() {
            return new RequestOptions(0, PRIORITY_SEMI_INTERACTIVE, 3, false, true, true, 0);
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int priority = 0;
            private int priorityClass = PRIORITY_SEMI_INTERACTIVE;
            private int maxRetries = 3;
            private boolean realTimeFlag = false;
            private boolean compress = true;
            private boolean allowSplitfile = true;
            private int verbosity = 0;
            
            public Builder priority(int p) { this.priority = p; return this; }
            public Builder priorityClass(int pc) { this.priorityClass = pc; return this; }
            public Builder maxRetries(int r) { this.maxRetries = r; return this; }
            public Builder realTimeFlag(boolean rt) { this.realTimeFlag = rt; return this; }
            public Builder compress(boolean c) { this.compress = c; return this; }
            public Builder allowSplitfile(boolean sf) { this.allowSplitfile = sf; return this; }
            public Builder verbosity(int v) { this.verbosity = v; return this; }
            
            public RequestOptions build() {
                return new RequestOptions(priority, priorityClass, maxRetries, 
                    realTimeFlag, compress, allowSplitfile, verbosity);
            }
        }
    }
    
    /**
     * Configuration options for HyphanetTransport.
     */
    public record HyphanetOptions(
        String fcpHost,
        int fcpPort,
        String clientName,
        boolean persistKeys,
        String keyPath,
        boolean autoReconnect,
        int maxReconnectAttempts,
        Duration reconnectDelay,
        Duration reconnectMaxDelay,
        boolean enableHealthCheck,
        Duration connectionTimeout,
        Duration readTimeout,
        int maxConcurrentRequests
    ) {
        public static HyphanetOptions defaults() {
            return new HyphanetOptions(
                "127.0.0.1",
                DEFAULT_FCP_PORT,
                null,
                false,
                null,
                true,
                5,
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                true,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                4
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String fcpHost = "127.0.0.1";
            private int fcpPort = DEFAULT_FCP_PORT;
            private String clientName = null;
            private boolean persistKeys = false;
            private String keyPath = null;
            private boolean autoReconnect = true;
            private int maxReconnectAttempts = 5;
            private Duration reconnectDelay = Duration.ofSeconds(5);
            private Duration reconnectMaxDelay = Duration.ofMinutes(2);
            private boolean enableHealthCheck = true;
            private Duration connectionTimeout = Duration.ofSeconds(30);
            private Duration readTimeout = Duration.ofMinutes(5);
            private int maxConcurrentRequests = 4;
            
            public Builder fcpHost(String host) { this.fcpHost = host; return this; }
            public Builder fcpPort(int port) { this.fcpPort = port; return this; }
            public Builder clientName(String name) { this.clientName = name; return this; }
            public Builder persistKeys(boolean persist) { this.persistKeys = persist; return this; }
            public Builder keyPath(String path) { this.keyPath = path; return this; }
            public Builder autoReconnect(boolean auto) { this.autoReconnect = auto; return this; }
            public Builder maxReconnectAttempts(int max) { this.maxReconnectAttempts = max; return this; }
            public Builder reconnectDelay(Duration delay) { this.reconnectDelay = delay; return this; }
            public Builder reconnectMaxDelay(Duration max) { this.reconnectMaxDelay = max; return this; }
            public Builder enableHealthCheck(boolean enable) { this.enableHealthCheck = enable; return this; }
            public Builder connectionTimeout(Duration timeout) { this.connectionTimeout = timeout; return this; }
            public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
            public Builder maxConcurrentRequests(int max) { this.maxConcurrentRequests = max; return this; }
            
            public HyphanetOptions build() {
                return new HyphanetOptions(
                    fcpHost, fcpPort, clientName, persistKeys, keyPath,
                    autoReconnect, maxReconnectAttempts, reconnectDelay, reconnectMaxDelay,
                    enableHealthCheck, connectionTimeout, readTimeout, maxConcurrentRequests
                );
            }
        }
    }
}
