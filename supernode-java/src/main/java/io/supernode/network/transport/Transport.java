package io.supernode.network.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface Transport {

    TransportType getType();

    String getName();

    boolean isAvailable();

    boolean isRunning();

    CompletableFuture<TransportAddress> start();

    CompletableFuture<Void> stop();

    CompletableFuture<TransportConnection> connect(TransportAddress address);

    default CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions options) {
        return connect(address);
    }

    TransportAddress parseAddress(String address);

    boolean canHandle(String address);

    TransportAddress getLocalAddress();

    default List<TransportAddress> getLocalAddresses() {
        TransportAddress addr = getLocalAddress();
        return addr != null ? List.of(addr) : List.of();
    }

    void setOnConnection(Consumer<TransportConnection> handler);

    void setOnError(Consumer<TransportError> handler);

    TransportStats getStats();

    default HealthStatus getHealthStatus() {
        if (!isRunning()) {
            return new HealthStatus(HealthState.STOPPED, "Transport not running", Instant.now(), 0, 0);
        }
        return new HealthStatus(HealthState.HEALTHY, "OK", Instant.now(), 0, 0);
    }

    default CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.completedFuture(isRunning() && isAvailable());
    }

    default void configure(TransportConfig config) {}

    default TransportConfig getConfig() {
        return TransportConfig.defaults();
    }

    record TransportError(TransportType transportType, String message, Throwable cause) {
        public TransportType type() {
            return transportType;
        }
    }
    
    record TransportStats(
        long connectionsIn,
        long connectionsOut,
        long bytesReceived,
        long bytesSent,
        long messagesReceived,
        long messagesSent,
        long errors,
        long reconnections,
        long healthCheckFailures,
        Duration totalUptime
    ) {
        public TransportStats(
            long connectionsIn,
            long connectionsOut,
            long bytesReceived,
            long bytesSent,
            long messagesReceived,
            long messagesSent,
            long errors
        ) {
            this(connectionsIn, connectionsOut, bytesReceived, bytesSent, 
                 messagesReceived, messagesSent, errors, 0, 0, Duration.ZERO);
        }

        public static TransportStats empty() {
            return new TransportStats(0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO);
        }
    }

    enum HealthState {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        STOPPED
    }

    record HealthStatus(
        HealthState state,
        String message,
        Instant lastCheck,
        long consecutiveFailures,
        long latencyMs
    ) {
        public boolean isHealthy() {
            return state == HealthState.HEALTHY || state == HealthState.DEGRADED;
        }
    }

    record ConnectionOptions(
        Duration connectTimeout,
        Duration readTimeout,
        Duration writeTimeout,
        boolean autoReconnect,
        int maxReconnectAttempts,
        Duration reconnectDelay,
        Duration reconnectMaxDelay,
        double reconnectBackoffMultiplier,
        boolean enableHeartbeat,
        Duration heartbeatInterval,
        Duration heartbeatTimeout,
        boolean enableCompression,
        int priority
    ) {
        public static ConnectionOptions defaults() {
            return new ConnectionOptions(
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                true,
                5,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                2.0,
                true,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                false,
                0
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Duration connectTimeout = Duration.ofSeconds(30);
            private Duration readTimeout = Duration.ofSeconds(60);
            private Duration writeTimeout = Duration.ofSeconds(30);
            private boolean autoReconnect = true;
            private int maxReconnectAttempts = 5;
            private Duration reconnectDelay = Duration.ofSeconds(1);
            private Duration reconnectMaxDelay = Duration.ofSeconds(30);
            private double reconnectBackoffMultiplier = 2.0;
            private boolean enableHeartbeat = true;
            private Duration heartbeatInterval = Duration.ofSeconds(30);
            private Duration heartbeatTimeout = Duration.ofSeconds(10);
            private boolean enableCompression = false;
            private int priority = 0;

            public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
            public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
            public Builder writeTimeout(Duration timeout) { this.writeTimeout = timeout; return this; }
            public Builder autoReconnect(boolean enabled) { this.autoReconnect = enabled; return this; }
            public Builder maxReconnectAttempts(int max) { this.maxReconnectAttempts = max; return this; }
            public Builder reconnectDelay(Duration delay) { this.reconnectDelay = delay; return this; }
            public Builder reconnectMaxDelay(Duration max) { this.reconnectMaxDelay = max; return this; }
            public Builder reconnectBackoffMultiplier(double mult) { this.reconnectBackoffMultiplier = mult; return this; }
            public Builder enableHeartbeat(boolean enabled) { this.enableHeartbeat = enabled; return this; }
            public Builder heartbeatInterval(Duration interval) { this.heartbeatInterval = interval; return this; }
            public Builder heartbeatTimeout(Duration timeout) { this.heartbeatTimeout = timeout; return this; }
            public Builder enableCompression(boolean enabled) { this.enableCompression = enabled; return this; }
            public Builder priority(int p) { this.priority = p; return this; }

            public ConnectionOptions build() {
                return new ConnectionOptions(
                    connectTimeout, readTimeout, writeTimeout,
                    autoReconnect, maxReconnectAttempts, reconnectDelay, reconnectMaxDelay, reconnectBackoffMultiplier,
                    enableHeartbeat, heartbeatInterval, heartbeatTimeout,
                    enableCompression, priority
                );
            }
        }
    }

    record TransportConfig(
        int maxConnections,
        int maxConnectionsPerPeer,
        Duration idleTimeout,
        Duration healthCheckInterval,
        int healthCheckFailureThreshold,
        boolean enableMetrics,
        long maxBytesPerSecondIn,
        long maxBytesPerSecondOut,
        int listenBacklog,
        boolean reuseAddress
    ) {
        public static TransportConfig defaults() {
            return new TransportConfig(
                1000,
                10,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                3,
                true,
                0,
                0,
                128,
                true
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int maxConnections = 1000;
            private int maxConnectionsPerPeer = 10;
            private Duration idleTimeout = Duration.ofMinutes(5);
            private Duration healthCheckInterval = Duration.ofSeconds(30);
            private int healthCheckFailureThreshold = 3;
            private boolean enableMetrics = true;
            private long maxBytesPerSecondIn = 0;
            private long maxBytesPerSecondOut = 0;
            private int listenBacklog = 128;
            private boolean reuseAddress = true;

            public Builder maxConnections(int max) { this.maxConnections = max; return this; }
            public Builder maxConnectionsPerPeer(int max) { this.maxConnectionsPerPeer = max; return this; }
            public Builder idleTimeout(Duration timeout) { this.idleTimeout = timeout; return this; }
            public Builder healthCheckInterval(Duration interval) { this.healthCheckInterval = interval; return this; }
            public Builder healthCheckFailureThreshold(int threshold) { this.healthCheckFailureThreshold = threshold; return this; }
            public Builder enableMetrics(boolean enabled) { this.enableMetrics = enabled; return this; }
            public Builder maxBytesPerSecondIn(long max) { this.maxBytesPerSecondIn = max; return this; }
            public Builder maxBytesPerSecondOut(long max) { this.maxBytesPerSecondOut = max; return this; }
            public Builder listenBacklog(int backlog) { this.listenBacklog = backlog; return this; }
            public Builder reuseAddress(boolean reuse) { this.reuseAddress = reuse; return this; }

            public TransportConfig build() {
                return new TransportConfig(
                    maxConnections, maxConnectionsPerPeer, idleTimeout,
                    healthCheckInterval, healthCheckFailureThreshold, enableMetrics,
                    maxBytesPerSecondIn, maxBytesPerSecondOut, listenBacklog, reuseAddress
                );
            }
        }
    }
}
