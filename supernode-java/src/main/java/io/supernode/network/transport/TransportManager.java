package io.supernode.network.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.supernode.network.transport.Transport.ConnectionOptions;

public class TransportManager {

    private final Map<TransportType, Transport> transports = new EnumMap<>(TransportType.class);
    private final Map<String, TransportConnection> connections = new ConcurrentHashMap<>();
    private final Map<TransportType, TransportHealth> transportHealth = new ConcurrentHashMap<>();
    private final Map<TransportType, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<TransportType> loadBalanceQueue;
    private final ManagerOptions options;
    private ScheduledExecutorService healthMonitor;
    private ScheduledExecutorService failoverMonitor;
    
    private Consumer<TransportConnection> onConnection;
    private Consumer<Transport.TransportError> onError;
    private Consumer<FailoverEvent> onFailover;
    private Consumer<HealthChangeEvent> onHealthChange;
    private volatile boolean running = false;
    private volatile Instant startTime;
    
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong failovers = new AtomicLong();
    private final AtomicLong circuitBreakerTrips = new AtomicLong();

    public TransportManager() {
        this(ManagerOptions.defaults());
    }
    
    public TransportManager(ManagerOptions options) {
        this.options = options;
        this.loadBalanceQueue = new PriorityBlockingQueue<>(10, 
            Comparator.comparingInt(this::getTransportScore).reversed());
    }

    public void registerTransport(Transport transport) {
        transports.put(transport.getType(), transport);
        transport.setOnConnection(this::handleIncomingConnection);
        transport.setOnError(this::handleError);
        
        transportHealth.put(transport.getType(), new TransportHealth(transport.getType()));
        circuitBreakers.put(transport.getType(), new CircuitBreaker(
            options.circuitBreakerThreshold,
            options.circuitBreakerTimeout
        ));
        
        if (transport.isAvailable()) {
            loadBalanceQueue.offer(transport.getType());
        }
    }
    
    public void unregisterTransport(TransportType type) {
        Transport transport = transports.remove(type);
        if (transport != null && transport.isRunning()) {
            transport.stop().join();
        }
        transportHealth.remove(type);
        circuitBreakers.remove(type);
        loadBalanceQueue.remove(type);
    }

    public Transport getTransport(TransportType type) {
        return transports.get(type);
    }

    public List<Transport> getAllTransports() {
        return new ArrayList<>(transports.values());
    }

    public List<Transport> getAvailableTransports() {
        return transports.values().stream()
            .filter(Transport::isAvailable)
            .toList();
    }
    
    public List<Transport> getHealthyTransports() {
        return transports.entrySet().stream()
            .filter(e -> e.getValue().isAvailable())
            .filter(e -> {
                TransportHealth health = transportHealth.get(e.getKey());
                return health != null && health.state != Transport.HealthState.UNHEALTHY;
            })
            .filter(e -> {
                CircuitBreaker cb = circuitBreakers.get(e.getKey());
                return cb == null || !cb.isOpen();
            })
            .map(Map.Entry::getValue)
            .toList();
    }

    public CompletableFuture<List<TransportAddress>> startAll() {
        running = true;
        startTime = Instant.now();
        
        List<CompletableFuture<TransportAddress>> futures = transports.values().stream()
            .filter(Transport::isAvailable)
            .map(transport -> transport.start()
                .whenComplete((addr, ex) -> {
                    if (ex == null) {
                        updateHealth(transport.getType(), Transport.HealthState.HEALTHY, "Started");
                    } else {
                        updateHealth(transport.getType(), Transport.HealthState.UNHEALTHY, ex.getMessage());
                    }
                }))
            .toList();
        
        startHealthMonitor();
        startFailoverMonitor();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .filter(f -> !f.isCompletedExceptionally())
                .map(CompletableFuture::join)
                .toList());
    }
    
    private void startHealthMonitor() {
        if (!options.enableHealthMonitoring) return;
        
        healthMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transport-health-monitor");
            t.setDaemon(true);
            return t;
        });
        
        healthMonitor.scheduleAtFixedRate(
            this::checkAllTransportHealth,
            options.healthCheckInterval.toMillis(),
            options.healthCheckInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private void startFailoverMonitor() {
        if (!options.enableFailover) return;
        
        failoverMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "transport-failover-monitor");
            t.setDaemon(true);
            return t;
        });
        
        failoverMonitor.scheduleAtFixedRate(
            this::checkFailoverConditions,
            options.failoverCheckInterval.toMillis(),
            options.failoverCheckInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private void checkAllTransportHealth() {
        for (Map.Entry<TransportType, Transport> entry : transports.entrySet()) {
            Transport transport = entry.getValue();
            TransportType type = entry.getKey();
            
            if (!transport.isRunning()) continue;
            
            transport.healthCheck().thenAccept(healthy -> {
                Transport.HealthStatus status = transport.getHealthStatus();
                TransportHealth health = transportHealth.get(type);
                
                if (health != null) {
                    Transport.HealthState oldState = health.state;
                    health.update(status);
                    
                    if (oldState != status.state()) {
                        notifyHealthChange(type, oldState, status.state(), status.message());
                        updateLoadBalanceQueue(type);
                    }
                    
                    CircuitBreaker cb = circuitBreakers.get(type);
                    if (cb != null) {
                        if (healthy) {
                            cb.recordSuccess();
                        } else {
                            cb.recordFailure();
                            if (cb.isOpen()) {
                                circuitBreakerTrips.incrementAndGet();
                            }
                        }
                    }
                }
            }).exceptionally(e -> {
                updateHealth(type, Transport.HealthState.UNHEALTHY, e.getMessage());
                return null;
            });
        }
    }
    
    private void checkFailoverConditions() {
        for (String connId : new ArrayList<>(connections.keySet())) {
            TransportConnection conn = connections.get(connId);
            if (conn == null) continue;
            
            if (!conn.isOpen()) {
                TransportType failedType = conn.getTransportType();
                TransportAddress remoteAddr = conn.getRemoteAddress();
                
                CircuitBreaker cb = circuitBreakers.get(failedType);
                if (cb != null) {
                    cb.recordFailure();
                }
                
                if (options.enableFailover) {
                    attemptFailover(remoteAddr, failedType);
                }
            }
        }
    }
    
    private void attemptFailover(TransportAddress originalAddress, TransportType failedType) {
        List<TransportType> alternatives = getFailoverCandidates(failedType);
        
        for (TransportType altType : alternatives) {
            Transport alt = transports.get(altType);
            if (alt == null || !alt.isAvailable() || !alt.isRunning()) continue;
            
            CircuitBreaker cb = circuitBreakers.get(altType);
            if (cb != null && cb.isOpen()) continue;
            
            TransportHealth health = transportHealth.get(altType);
            if (health != null && health.state == Transport.HealthState.UNHEALTHY) continue;
            
            failovers.incrementAndGet();
            notifyFailover(failedType, altType, originalAddress.raw(), "Connection lost");
            break;
        }
    }
    
    private List<TransportType> getFailoverCandidates(TransportType excluding) {
        return transports.keySet().stream()
            .filter(t -> t != excluding)
            .sorted(Comparator.comparingInt(this::getTransportScore).reversed())
            .toList();
    }
    
    private int getTransportScore(TransportType type) {
        Transport transport = transports.get(type);
        if (transport == null || !transport.isAvailable()) return 0;
        
        TransportHealth health = transportHealth.get(type);
        CircuitBreaker cb = circuitBreakers.get(type);
        
        int score = 100;
        
        if (health != null) {
            switch (health.state) {
                case HEALTHY -> score += 50;
                case DEGRADED -> score += 25;
                case UNHEALTHY -> score -= 100;
                case STOPPED -> score -= 200;
            }
            
            score -= (int) Math.min(50, health.latencyMs / 10);
            score -= health.consecutiveFailures * 10;
        }
        
        if (cb != null && cb.isOpen()) {
            score -= 500;
        }
        
        int priorityBonus = options.transportPriority.getOrDefault(type, 0);
        score += priorityBonus;
        
        return score;
    }
    
    private void updateLoadBalanceQueue(TransportType type) {
        loadBalanceQueue.remove(type);
        Transport transport = transports.get(type);
        if (transport != null && transport.isAvailable()) {
            loadBalanceQueue.offer(type);
        }
    }

    public CompletableFuture<Void> stopAll() {
        running = false;
        
        if (healthMonitor != null) {
            healthMonitor.shutdown();
        }
        if (failoverMonitor != null) {
            failoverMonitor.shutdown();
        }
        
        connections.values().forEach(conn -> conn.close().join());
        connections.clear();
        
        List<CompletableFuture<Void>> futures = transports.values().stream()
            .filter(Transport::isRunning)
            .map(Transport::stop)
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<TransportConnection> connect(String address) {
        return connect(address, ConnectionOptions.defaults());
    }
    
    public CompletableFuture<TransportConnection> connect(String address, ConnectionOptions connOptions) {
        for (Transport transport : transports.values()) {
            if (transport.isAvailable() && transport.canHandle(address)) {
                TransportAddress parsed = transport.parseAddress(address);
                if (parsed != null) {
                    return connectWithFailover(parsed, connOptions);
                }
            }
        }
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("No transport available for address: " + address)
        );
    }

    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }
    
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions connOptions) {
        return connectWithFailover(address, connOptions);
    }
    
    private CompletableFuture<TransportConnection> connectWithFailover(TransportAddress address, 
                                                                        ConnectionOptions connOptions) {
        Transport transport = transports.get(address.type());
        if (transport == null || !transport.isAvailable()) {
            if (options.enableFailover) {
                return attemptConnectionFailover(address, connOptions, address.type());
            }
            return CompletableFuture.failedFuture(
                new IllegalStateException("Transport not available: " + address.type())
            );
        }
        
        CircuitBreaker cb = circuitBreakers.get(address.type());
        if (cb != null && cb.isOpen()) {
            if (options.enableFailover) {
                return attemptConnectionFailover(address, connOptions, address.type());
            }
            return CompletableFuture.failedFuture(
                new IllegalStateException("Circuit breaker open for: " + address.type())
            );
        }
        
        return transport.connect(address, connOptions)
            .thenApply(conn -> {
                connections.put(conn.getId(), conn);
                conn.setOnClose(v -> connections.remove(conn.getId()));
                totalConnections.incrementAndGet();
                
                CircuitBreaker breaker = circuitBreakers.get(address.type());
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                
                return conn;
            })
            .exceptionally(ex -> {
                CircuitBreaker breaker = circuitBreakers.get(address.type());
                if (breaker != null) {
                    breaker.recordFailure();
                }
                throw new CompletionException(ex);
            });
    }
    
    private CompletableFuture<TransportConnection> attemptConnectionFailover(TransportAddress originalAddress,
                                                                              ConnectionOptions connOptions,
                                                                              TransportType failedType) {
        List<TransportType> candidates = getFailoverCandidates(failedType);
        
        if (candidates.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No failover transports available")
            );
        }
        
        CompletableFuture<TransportConnection> result = new CompletableFuture<>();
        tryNextFailoverCandidate(originalAddress, connOptions, candidates, 0, result, failedType);
        return result;
    }
    
    private void tryNextFailoverCandidate(TransportAddress originalAddress,
                                          ConnectionOptions connOptions,
                                          List<TransportType> candidates,
                                          int index,
                                          CompletableFuture<TransportConnection> result,
                                          TransportType originalType) {
        if (index >= candidates.size()) {
            result.completeExceptionally(new IllegalStateException("All failover attempts failed"));
            return;
        }
        
        TransportType candidateType = candidates.get(index);
        Transport candidate = transports.get(candidateType);
        
        if (candidate == null || !candidate.isAvailable() || !candidate.isRunning()) {
            tryNextFailoverCandidate(originalAddress, connOptions, candidates, index + 1, result, originalType);
            return;
        }
        
        CircuitBreaker cb = circuitBreakers.get(candidateType);
        if (cb != null && cb.isOpen()) {
            tryNextFailoverCandidate(originalAddress, connOptions, candidates, index + 1, result, originalType);
            return;
        }
        
        TransportAddress failoverAddress = new TransportAddress(
            candidateType, originalAddress.host(), originalAddress.port(), originalAddress.raw()
        );
        
        candidate.connect(failoverAddress, connOptions)
            .thenAccept(conn -> {
                connections.put(conn.getId(), conn);
                conn.setOnClose(v -> connections.remove(conn.getId()));
                totalConnections.incrementAndGet();
                failovers.incrementAndGet();
                
                notifyFailover(originalType, candidateType, originalAddress.raw(), "Primary unavailable");
                
                CircuitBreaker breaker = circuitBreakers.get(candidateType);
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                
                result.complete(conn);
            })
            .exceptionally(ex -> {
                CircuitBreaker breaker = circuitBreakers.get(candidateType);
                if (breaker != null) {
                    breaker.recordFailure();
                }
                tryNextFailoverCandidate(originalAddress, connOptions, candidates, index + 1, result, originalType);
                return null;
            });
    }
    
    public CompletableFuture<TransportConnection> connectLoadBalanced(List<TransportAddress> addresses) {
        return connectLoadBalanced(addresses, ConnectionOptions.defaults());
    }
    
    public CompletableFuture<TransportConnection> connectLoadBalanced(List<TransportAddress> addresses,
                                                                       ConnectionOptions connOptions) {
        if (addresses.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No addresses provided"));
        }
        
        List<TransportAddress> sorted = addresses.stream()
            .sorted(Comparator.comparingInt(a -> -getTransportScore(a.type())))
            .toList();
        
        CompletableFuture<TransportConnection> result = new CompletableFuture<>();
        tryLoadBalancedConnection(sorted, connOptions, 0, result);
        return result;
    }
    
    private void tryLoadBalancedConnection(List<TransportAddress> addresses,
                                           ConnectionOptions connOptions,
                                           int index,
                                           CompletableFuture<TransportConnection> result) {
        if (index >= addresses.size()) {
            result.completeExceptionally(new IllegalStateException("All connection attempts failed"));
            return;
        }
        
        TransportAddress addr = addresses.get(index);
        Transport transport = transports.get(addr.type());
        
        if (transport == null || !transport.isAvailable()) {
            tryLoadBalancedConnection(addresses, connOptions, index + 1, result);
            return;
        }
        
        CircuitBreaker cb = circuitBreakers.get(addr.type());
        if (cb != null && cb.isOpen()) {
            tryLoadBalancedConnection(addresses, connOptions, index + 1, result);
            return;
        }
        
        transport.connect(addr, connOptions)
            .thenAccept(conn -> {
                connections.put(conn.getId(), conn);
                conn.setOnClose(v -> connections.remove(conn.getId()));
                totalConnections.incrementAndGet();
                
                CircuitBreaker breaker = circuitBreakers.get(addr.type());
                if (breaker != null) {
                    breaker.recordSuccess();
                }
                
                result.complete(conn);
            })
            .exceptionally(ex -> {
                CircuitBreaker breaker = circuitBreakers.get(addr.type());
                if (breaker != null) {
                    breaker.recordFailure();
                }
                tryLoadBalancedConnection(addresses, connOptions, index + 1, result);
                return null;
            });
    }
    
    public TransportType selectBestTransport() {
        return loadBalanceQueue.peek();
    }
    
    public List<TransportType> getTransportsByScore() {
        return transports.keySet().stream()
            .filter(t -> transports.get(t).isAvailable())
            .sorted(Comparator.comparingInt(this::getTransportScore).reversed())
            .toList();
    }

    public TransportAddress parseAddress(String address) {
        for (Transport transport : transports.values()) {
            if (transport.canHandle(address)) {
                return transport.parseAddress(address);
            }
        }
        return null;
    }

    public List<TransportAddress> getLocalAddresses() {
        return transports.values().stream()
            .filter(Transport::isRunning)
            .map(Transport::getLocalAddress)
            .filter(Objects::nonNull)
            .toList();
    }

    public Map<String, TransportConnection> getConnections() {
        return Map.copyOf(connections);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public boolean isRunning() {
        return running;
    }

    public void setOnConnection(Consumer<TransportConnection> handler) {
        this.onConnection = handler;
    }

    public void setOnError(Consumer<Transport.TransportError> handler) {
        this.onError = handler;
    }
    
    public void setOnFailover(Consumer<FailoverEvent> handler) {
        this.onFailover = handler;
    }
    
    public void setOnHealthChange(Consumer<HealthChangeEvent> handler) {
        this.onHealthChange = handler;
    }
    
    public Transport.HealthStatus getTransportHealthStatus(TransportType type) {
        Transport transport = transports.get(type);
        if (transport != null) {
            return transport.getHealthStatus();
        }
        return null;
    }
    
    public Map<TransportType, Transport.HealthStatus> getAllHealthStatuses() {
        Map<TransportType, Transport.HealthStatus> result = new EnumMap<>(TransportType.class);
        for (Map.Entry<TransportType, Transport> entry : transports.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getHealthStatus());
        }
        return result;
    }
    
    public CircuitBreakerStatus getCircuitBreakerStatus(TransportType type) {
        CircuitBreaker cb = circuitBreakers.get(type);
        if (cb != null) {
            return new CircuitBreakerStatus(
                type,
                cb.isOpen(),
                cb.failures.get(),
                cb.lastFailure,
                cb.threshold,
                cb.timeout
            );
        }
        return null;
    }
    
    public void resetCircuitBreaker(TransportType type) {
        CircuitBreaker cb = circuitBreakers.get(type);
        if (cb != null) {
            cb.reset();
        }
    }

    public AggregateStats getStats() {
        long connectionsIn = 0, connectionsOut = 0;
        long bytesReceived = 0, bytesSent = 0;
        long messagesReceived = 0, messagesSent = 0;
        long errors = 0;
        long reconnections = 0;
        long healthCheckFailures = 0;
        
        for (Transport transport : transports.values()) {
            Transport.TransportStats stats = transport.getStats();
            connectionsIn += stats.connectionsIn();
            connectionsOut += stats.connectionsOut();
            bytesReceived += stats.bytesReceived();
            bytesSent += stats.bytesSent();
            messagesReceived += stats.messagesReceived();
            messagesSent += stats.messagesSent();
            errors += stats.errors();
            reconnections += stats.reconnections();
            healthCheckFailures += stats.healthCheckFailures();
        }
        
        Map<TransportType, Transport.TransportStats> byTransport = new EnumMap<>(TransportType.class);
        for (var entry : transports.entrySet()) {
            byTransport.put(entry.getKey(), entry.getValue().getStats());
        }
        
        Duration uptime = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
        
        return new AggregateStats(
            connectionsIn, connectionsOut,
            bytesReceived, bytesSent,
            messagesReceived, messagesSent,
            errors, reconnections, healthCheckFailures,
            totalConnections.get(), failovers.get(), circuitBreakerTrips.get(),
            byTransport, uptime
        );
    }

    private void handleIncomingConnection(TransportConnection conn) {
        connections.put(conn.getId(), conn);
        conn.setOnClose(v -> connections.remove(conn.getId()));
        totalConnections.incrementAndGet();
        if (onConnection != null) {
            onConnection.accept(conn);
        }
    }

    private void handleError(Transport.TransportError error) {
        CircuitBreaker cb = circuitBreakers.get(error.type());
        if (cb != null) {
            cb.recordFailure();
        }
        
        if (onError != null) {
            onError.accept(error);
        }
    }
    
    private void updateHealth(TransportType type, Transport.HealthState state, String message) {
        TransportHealth health = transportHealth.get(type);
        if (health != null) {
            Transport.HealthState oldState = health.state;
            health.state = state;
            health.message = message;
            health.lastCheck = Instant.now();
            
            if (oldState != state) {
                notifyHealthChange(type, oldState, state, message);
                updateLoadBalanceQueue(type);
            }
        }
    }
    
    private void notifyHealthChange(TransportType type, Transport.HealthState oldState,
                                    Transport.HealthState newState, String message) {
        if (onHealthChange != null) {
            onHealthChange.accept(new HealthChangeEvent(type, oldState, newState, message, Instant.now()));
        }
    }
    
    private void notifyFailover(TransportType from, TransportType to, String address, String reason) {
        if (onFailover != null) {
            onFailover.accept(new FailoverEvent(from, to, address, reason, Instant.now()));
        }
    }
    
    private static class TransportHealth {
        final TransportType type;
        volatile Transport.HealthState state = Transport.HealthState.STOPPED;
        volatile String message = "Not started";
        volatile Instant lastCheck;
        volatile int consecutiveFailures = 0;
        volatile long latencyMs = 0;
        
        TransportHealth(TransportType type) {
            this.type = type;
        }
        
        void update(Transport.HealthStatus status) {
            this.state = status.state();
            this.message = status.message();
            this.lastCheck = status.lastCheck();
            this.consecutiveFailures = (int) status.consecutiveFailures();
            this.latencyMs = status.latencyMs();
        }
    }
    
    private static class CircuitBreaker {
        final int threshold;
        final Duration timeout;
        final AtomicInteger failures = new AtomicInteger(0);
        volatile Instant lastFailure;
        volatile boolean open = false;
        
        CircuitBreaker(int threshold, Duration timeout) {
            this.threshold = threshold;
            this.timeout = timeout;
        }
        
        void recordFailure() {
            lastFailure = Instant.now();
            if (failures.incrementAndGet() >= threshold) {
                open = true;
            }
        }
        
        void recordSuccess() {
            failures.set(0);
            open = false;
        }
        
        boolean isOpen() {
            if (!open) return false;
            
            if (lastFailure != null && 
                Duration.between(lastFailure, Instant.now()).compareTo(timeout) > 0) {
                open = false;
                failures.set(0);
                return false;
            }
            return true;
        }
        
        void reset() {
            failures.set(0);
            open = false;
            lastFailure = null;
        }
    }

    public record AggregateStats(
        long connectionsIn,
        long connectionsOut,
        long bytesReceived,
        long bytesSent,
        long messagesReceived,
        long messagesSent,
        long errors,
        long reconnections,
        long healthCheckFailures,
        long totalConnections,
        long failovers,
        long circuitBreakerTrips,
        Map<TransportType, Transport.TransportStats> byTransport,
        Duration uptime
    ) {}
    
    public record FailoverEvent(
        TransportType from,
        TransportType to,
        String address,
        String reason,
        Instant timestamp
    ) {}
    
    public record HealthChangeEvent(
        TransportType transport,
        Transport.HealthState oldState,
        Transport.HealthState newState,
        String message,
        Instant timestamp
    ) {}
    
    public record CircuitBreakerStatus(
        TransportType transport,
        boolean open,
        int failures,
        Instant lastFailure,
        int threshold,
        Duration timeout
    ) {}
    
    public record ManagerOptions(
        boolean enableHealthMonitoring,
        boolean enableFailover,
        Duration healthCheckInterval,
        Duration failoverCheckInterval,
        int circuitBreakerThreshold,
        Duration circuitBreakerTimeout,
        Map<TransportType, Integer> transportPriority
    ) {
        public static ManagerOptions defaults() {
            return new ManagerOptions(
                true,
                true,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                5,
                Duration.ofMinutes(1),
                Map.of()
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private boolean enableHealthMonitoring = true;
            private boolean enableFailover = true;
            private Duration healthCheckInterval = Duration.ofSeconds(30);
            private Duration failoverCheckInterval = Duration.ofSeconds(10);
            private int circuitBreakerThreshold = 5;
            private Duration circuitBreakerTimeout = Duration.ofMinutes(1);
            private Map<TransportType, Integer> transportPriority = new EnumMap<>(TransportType.class);
            
            public Builder enableHealthMonitoring(boolean enable) { 
                this.enableHealthMonitoring = enable; return this; 
            }
            public Builder enableFailover(boolean enable) { 
                this.enableFailover = enable; return this; 
            }
            public Builder healthCheckInterval(Duration interval) { 
                this.healthCheckInterval = interval; return this; 
            }
            public Builder failoverCheckInterval(Duration interval) { 
                this.failoverCheckInterval = interval; return this; 
            }
            public Builder circuitBreakerThreshold(int threshold) { 
                this.circuitBreakerThreshold = threshold; return this; 
            }
            public Builder circuitBreakerTimeout(Duration timeout) { 
                this.circuitBreakerTimeout = timeout; return this; 
            }
            public Builder transportPriority(TransportType type, int priority) { 
                this.transportPriority.put(type, priority); return this; 
            }
            
            public ManagerOptions build() {
                return new ManagerOptions(
                    enableHealthMonitoring, enableFailover,
                    healthCheckInterval, failoverCheckInterval,
                    circuitBreakerThreshold, circuitBreakerTimeout,
                    Map.copyOf(transportPriority)
                );
            }
        }
    }
}
