package io.supernode.network;

import io.supernode.network.transport.Transport;
import io.supernode.network.transport.TransportAddress;
import io.supernode.network.transport.TransportType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * DHT-based peer discovery for Supernode.
 * Uses BitTorrent DHT protocol (BEP 5) to find peers holding specific blobs.
 * 
 * Supports peer health tracking, reputation system, and network health metrics.
 */
public class DHTDiscovery {
    
    public static final List<String> DEFAULT_BOOTSTRAP = List.of(
        "router.bittorrent.com:6881",
        "router.utorrent.com:6881",
        "dht.transmissionbt.com:6881"
    );
    
    private final byte[] nodeId;
    private final List<String> bootstrap;
    private int port;
    private final DHTOptions options;
    
    private final Map<String, AnnouncedHash> announcedHashes = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<PeerInfo>>> lookupCallbacks = new ConcurrentHashMap<>();
    private final Map<String, PeerHealth> peerHealth = new ConcurrentHashMap<>();
    private final Map<String, PeerReputation> peerReputation = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private volatile boolean ready = false;
    private volatile boolean destroyed = false;
    private volatile Transport.HealthState healthState = Transport.HealthState.STOPPED;
    private volatile Instant lastHealthCheck = Instant.now();
    
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong successfulLookups = new AtomicLong();
    private final AtomicLong failedLookups = new AtomicLong();
    private final AtomicLong totalAnnounces = new AtomicLong();
    private final AtomicLong peersDiscovered = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    
    private Consumer<Void> onReady;
    private Consumer<Exception> onError;
    private Consumer<PeerEvent> onPeer;
    private Consumer<AnnounceEvent> onAnnounce;
    private Consumer<String> onWarning;
    private Consumer<ListeningEvent> onListening;
    private Consumer<AnnouncedEvent> onAnnounced;
    private Consumer<AnnounceErrorEvent> onAnnounceError;
    private Consumer<LookupCompleteEvent> onLookupComplete;
    private Consumer<LookupErrorEvent> onLookupError;
    private Consumer<Void> onDestroyed;
    private Consumer<HealthChangeEvent> onHealthChange;
    private Consumer<PeerHealthChangeEvent> onPeerHealthChange;
    
    public DHTDiscovery() {
        this(DHTOptions.defaults());
    }
    
    public DHTDiscovery(DHTOptions options) {
        this.options = options;
        this.nodeId = options.nodeId != null ? options.nodeId : generateNodeId();
        this.bootstrap = options.bootstrap != null ? options.bootstrap : DEFAULT_BOOTSTRAP;
        this.port = options.port;
        
        if (options.enableHealthMonitoring) {
            startHealthMonitoring();
        }
    }
    
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (destroyed) {
                throw new IllegalStateException("DHT has been destroyed");
            }
            
            try {
                Thread.sleep(100);
                
                ready = true;
                updateHealthState(Transport.HealthState.HEALTHY);
                
                if (onListening != null) {
                    onListening.accept(new ListeningEvent(port));
                }
                
                if (onReady != null) {
                    onReady.accept(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                updateHealthState(Transport.HealthState.UNHEALTHY);
                throw new RuntimeException("DHT start interrupted", e);
            }
        });
    }
    
    public byte[] blobIdToInfoHash(String blobId) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return sha1.digest(blobId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
    
    public void announce(String blobId, int wsPort) {
        if (destroyed || !ready) return;
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        totalAnnounces.incrementAndGet();
        messagesSent.incrementAndGet();
        
        if (onAnnounced != null) {
            onAnnounced.accept(new AnnouncedEvent(blobId, hashHex));
        }
        
        if (!announcedHashes.containsKey(hashHex)) {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    if (!destroyed && ready) {
                        totalAnnounces.incrementAndGet();
                        messagesSent.incrementAndGet();
                        if (onAnnounced != null) {
                            onAnnounced.accept(new AnnouncedEvent(blobId, hashHex));
                        }
                    }
                },
                options.announceInterval.toMillis(),
                options.announceInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
            
            announcedHashes.put(hashHex, new AnnouncedHash(blobId, future, wsPort, Instant.now()));
        }
    }
    
    public CompletableFuture<Void> announceAsync(String blobId, int wsPort) {
        return CompletableFuture.runAsync(() -> announce(blobId, wsPort));
    }
    
    public void unannounce(String blobId) {
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        AnnouncedHash entry = announcedHashes.remove(hashHex);
        if (entry != null && entry.future() != null) {
            entry.future().cancel(false);
        }
    }
    
    public void lookup(String blobId, Consumer<PeerInfo> callback) {
        if (destroyed || !ready) return;
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        totalLookups.incrementAndGet();
        messagesSent.incrementAndGet();
        
        if (callback != null) {
            lookupCallbacks.computeIfAbsent(hashHex, k -> ConcurrentHashMap.newKeySet()).add(callback);
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
                successfulLookups.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedLookups.incrementAndGet();
            }
            
            if (callback != null) {
                Set<Consumer<PeerInfo>> callbacks = lookupCallbacks.get(hashHex);
                if (callbacks != null) {
                    callbacks.remove(callback);
                    if (callbacks.isEmpty()) {
                        lookupCallbacks.remove(hashHex);
                    }
                }
            }
            
            if (onLookupComplete != null) {
                onLookupComplete.accept(new LookupCompleteEvent(blobId, 0));
            }
        });
    }
    
    public CompletableFuture<List<PeerInfo>> findPeers(String blobId, long timeoutMs) {
        return findPeers(blobId, Duration.ofMillis(timeoutMs));
    }
    
    public CompletableFuture<List<PeerInfo>> findPeers(String blobId, Duration timeout) {
        CompletableFuture<List<PeerInfo>> future = new CompletableFuture<>();
        List<PeerInfo> peers = Collections.synchronizedList(new ArrayList<>());
        
        byte[] infoHash = blobIdToInfoHash(blobId);
        String hashHex = bytesToHex(infoHash);
        
        Consumer<PeerInfo> onPeerCallback = peer -> {
            String addr = peer.host() + ":" + peer.port();
            boolean exists = peers.stream()
                .anyMatch(p -> (p.host() + ":" + p.port()).equals(addr));
            if (!exists) {
                peers.add(peer);
                peersDiscovered.incrementAndGet();
                recordPeerDiscovered(peer);
            }
        };
        
        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            Set<Consumer<PeerInfo>> callbacks = lookupCallbacks.get(hashHex);
            if (callbacks != null) {
                callbacks.remove(onPeerCallback);
                if (callbacks.isEmpty()) {
                    lookupCallbacks.remove(hashHex);
                }
            }
            future.complete(new ArrayList<>(peers));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        
        lookup(blobId, onPeerCallback);
        
        scheduler.schedule(() -> {
            if (!peers.isEmpty() && !future.isDone()) {
                timeoutFuture.cancel(false);
                future.complete(new ArrayList<>(peers));
            }
        }, Math.min(timeout.toMillis() / 2, 3000), TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    public CompletableFuture<List<PeerInfo>> findHealthyPeers(String blobId, Duration timeout) {
        return findPeers(blobId, timeout).thenApply(peers ->
            peers.stream()
                .filter(this::isPeerHealthy)
                .sorted((a, b) -> Double.compare(
                    getPeerScore(b.host() + ":" + b.port()),
                    getPeerScore(a.host() + ":" + a.port())
                ))
                .toList()
        );
    }
    
    private boolean isPeerHealthy(PeerInfo peer) {
        String peerId = peer.host() + ":" + peer.port();
        PeerHealth health = peerHealth.get(peerId);
        if (health == null) return true;
        return health.state() != PeerHealthState.BANNED && health.state() != PeerHealthState.UNHEALTHY;
    }
    
    private double getPeerScore(String peerId) {
        PeerReputation rep = peerReputation.get(peerId);
        PeerHealth health = peerHealth.get(peerId);
        
        double reputationScore = rep != null ? rep.score() : 50.0;
        double healthScore = health != null ? (health.successRate() * 100) : 50.0;
        double latencyScore = health != null ? Math.max(0, 100 - health.avgLatency().toMillis() / 10.0) : 50.0;
        
        return (reputationScore * 0.4) + (healthScore * 0.4) + (latencyScore * 0.2);
    }
    
    public void recordPeerSuccess(String peerId, Duration latency) {
        messagesReceived.incrementAndGet();
        
        peerHealth.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerHealth(peerId, 1, 0, latency, Instant.now(), 
                    PeerHealthState.HEALTHY, 1.0);
            }
            long successes = v.successes() + 1;
            long failures = v.failures();
            Duration avgLatency = Duration.ofMillis(
                (v.avgLatency().toMillis() * (successes - 1) + latency.toMillis()) / successes
            );
            double successRate = (double) successes / (successes + failures);
            PeerHealthState state = successRate > 0.9 ? PeerHealthState.HEALTHY
                : successRate > 0.5 ? PeerHealthState.DEGRADED : PeerHealthState.UNHEALTHY;
            
            PeerHealth newHealth = new PeerHealth(peerId, successes, failures, avgLatency, 
                Instant.now(), state, successRate);
            
            if (v.state() != state && onPeerHealthChange != null) {
                onPeerHealthChange.accept(new PeerHealthChangeEvent(peerId, v.state(), state));
            }
            
            return newHealth;
        });
        
        updatePeerReputation(peerId, 1.0);
    }
    
    public void recordPeerFailure(String peerId) {
        peerHealth.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerHealth(peerId, 0, 1, Duration.ZERO, Instant.now(), 
                    PeerHealthState.DEGRADED, 0.0);
            }
            long successes = v.successes();
            long failures = v.failures() + 1;
            double successRate = successes > 0 ? (double) successes / (successes + failures) : 0.0;
            PeerHealthState state = successRate > 0.9 ? PeerHealthState.HEALTHY
                : successRate > 0.5 ? PeerHealthState.DEGRADED 
                : successRate > 0.1 ? PeerHealthState.UNHEALTHY : PeerHealthState.BANNED;
            
            PeerHealth newHealth = new PeerHealth(peerId, successes, failures, v.avgLatency(), 
                Instant.now(), state, successRate);
            
            if (v.state() != state && onPeerHealthChange != null) {
                onPeerHealthChange.accept(new PeerHealthChangeEvent(peerId, v.state(), state));
            }
            
            return newHealth;
        });
        
        updatePeerReputation(peerId, -2.0);
    }
    
    private void recordPeerDiscovered(PeerInfo peer) {
        String peerId = peer.host() + ":" + peer.port();
        peerHealth.putIfAbsent(peerId, new PeerHealth(peerId, 0, 0, Duration.ZERO, 
            Instant.now(), PeerHealthState.UNKNOWN, 0.5));
        peerReputation.putIfAbsent(peerId, new PeerReputation(peerId, 50.0, 0, 0, Instant.now()));
    }
    
    private void updatePeerReputation(String peerId, double delta) {
        peerReputation.compute(peerId, (k, v) -> {
            if (v == null) {
                double score = Math.max(0, Math.min(100, 50 + delta));
                return new PeerReputation(peerId, score, delta > 0 ? 1 : 0, delta < 0 ? 1 : 0, Instant.now());
            }
            double newScore = Math.max(0, Math.min(100, v.score() + delta));
            long positive = v.positiveInteractions() + (delta > 0 ? 1 : 0);
            long negative = v.negativeInteractions() + (delta < 0 ? 1 : 0);
            return new PeerReputation(peerId, newScore, positive, negative, Instant.now());
        });
    }
    
    public void banPeer(String peerId, Duration duration) {
        peerHealth.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerHealth(peerId, 0, 0, Duration.ZERO, Instant.now(), 
                    PeerHealthState.BANNED, 0.0);
            }
            return new PeerHealth(peerId, v.successes(), v.failures(), v.avgLatency(), 
                Instant.now(), PeerHealthState.BANNED, 0.0);
        });
        
        peerReputation.compute(peerId, (k, v) -> {
            if (v == null) {
                return new PeerReputation(peerId, 0, 0, 1, Instant.now());
            }
            return new PeerReputation(peerId, 0, v.positiveInteractions(), v.negativeInteractions() + 1, Instant.now());
        });
        
        if (duration.toMillis() > 0) {
            scheduler.schedule(() -> unbanPeer(peerId), duration.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
    
    public void unbanPeer(String peerId) {
        peerHealth.computeIfPresent(peerId, (k, v) -> 
            new PeerHealth(peerId, 0, 0, Duration.ZERO, Instant.now(), PeerHealthState.UNKNOWN, 0.5));
        peerReputation.computeIfPresent(peerId, (k, v) ->
            new PeerReputation(peerId, 25.0, v.positiveInteractions(), v.negativeInteractions(), Instant.now()));
    }
    
    public Optional<PeerHealth> getPeerHealth(String peerId) {
        return Optional.ofNullable(peerHealth.get(peerId));
    }
    
    public Optional<PeerReputation> getPeerReputation(String peerId) {
        return Optional.ofNullable(peerReputation.get(peerId));
    }
    
    public List<PeerHealth> getAllPeerHealth() {
        return new ArrayList<>(peerHealth.values());
    }
    
    public List<PeerHealth> getHealthyPeers() {
        return peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.HEALTHY || h.state() == PeerHealthState.UNKNOWN)
            .toList();
    }
    
    public Transport.HealthStatus getHealthStatus() {
        double lookupSuccessRate = totalLookups.get() > 0 
            ? (double) successfulLookups.get() / totalLookups.get() : 1.0;
        
        int healthyPeers = (int) peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.HEALTHY).count();
        
        String message = String.format("DHT %s: %d healthy peers, %.1f%% lookup success rate",
            healthState, healthyPeers, lookupSuccessRate * 100);
        
        return new Transport.HealthStatus(healthState, message, lastHealthCheck, 
            failedLookups.get(), 0);
    }
    
    public NetworkHealth getNetworkHealth() {
        int totalPeers = peerHealth.size();
        int healthyCount = (int) peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.HEALTHY).count();
        int degradedCount = (int) peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.DEGRADED).count();
        int unhealthyCount = (int) peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.UNHEALTHY).count();
        int bannedCount = (int) peerHealth.values().stream()
            .filter(h -> h.state() == PeerHealthState.BANNED).count();
        
        double avgSuccessRate = peerHealth.values().stream()
            .mapToDouble(PeerHealth::successRate)
            .average()
            .orElse(1.0);
        
        Duration avgLatency = Duration.ofMillis((long) peerHealth.values().stream()
            .mapToLong(h -> h.avgLatency().toMillis())
            .average()
            .orElse(0));
        
        NetworkHealthState state;
        if (totalPeers == 0) {
            state = NetworkHealthState.UNKNOWN;
        } else if ((double) healthyCount / totalPeers > 0.7) {
            state = NetworkHealthState.HEALTHY;
        } else if ((double) (healthyCount + degradedCount) / totalPeers > 0.5) {
            state = NetworkHealthState.DEGRADED;
        } else {
            state = NetworkHealthState.UNHEALTHY;
        }
        
        return new NetworkHealth(
            state, totalPeers, healthyCount, degradedCount, unhealthyCount, bannedCount,
            avgSuccessRate, avgLatency, lastHealthCheck
        );
    }
    
    private void startHealthMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            if (destroyed) return;
            
            lastHealthCheck = Instant.now();
            
            NetworkHealth networkHealth = getNetworkHealth();
            Transport.HealthState newState = switch (networkHealth.state()) {
                case HEALTHY -> Transport.HealthState.HEALTHY;
                case DEGRADED -> Transport.HealthState.DEGRADED;
                case UNHEALTHY -> Transport.HealthState.UNHEALTHY;
                default -> ready ? Transport.HealthState.HEALTHY : Transport.HealthState.STOPPED;
            };
            
            updateHealthState(newState);
            
            cleanupStalePeers();
            
        }, options.healthCheckInterval.toMillis(), options.healthCheckInterval.toMillis(), 
            TimeUnit.MILLISECONDS);
    }
    
    private void cleanupStalePeers() {
        Instant cutoff = Instant.now().minus(options.peerStaleTimeout);
        
        peerHealth.entrySet().removeIf(e -> e.getValue().lastSeen().isBefore(cutoff));
        peerReputation.entrySet().removeIf(e -> e.getValue().lastUpdated().isBefore(cutoff));
    }
    
    private void updateHealthState(Transport.HealthState newState) {
        if (this.healthState != newState) {
            Transport.HealthState oldState = this.healthState;
            this.healthState = newState;
            lastHealthCheck = Instant.now();
            
            if (onHealthChange != null) {
                onHealthChange.accept(new HealthChangeEvent(oldState, newState));
            }
        }
    }
    
    public DHTStats getStats() {
        if (!ready) return null;
        
        return new DHTStats(
            bytesToHex(nodeId),
            port,
            peerHealth.size(),
            announcedHashes.size(),
            totalLookups.get(),
            successfulLookups.get(),
            failedLookups.get(),
            totalAnnounces.get(),
            peersDiscovered.get(),
            messagesSent.get(),
            messagesReceived.get(),
            healthState
        );
    }
    
    public DHTOptions getOptions() {
        return options;
    }
    
    public void destroy() {
        destroyed = true;
        updateHealthState(Transport.HealthState.STOPPED);
        
        for (AnnouncedHash entry : announcedHashes.values()) {
            if (entry.future() != null) {
                entry.future().cancel(false);
            }
        }
        announcedHashes.clear();
        lookupCallbacks.clear();
        peerHealth.clear();
        peerReputation.clear();
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (onDestroyed != null) {
            onDestroyed.accept(null);
        }
    }
    
    public CompletableFuture<Void> destroyAsync() {
        return CompletableFuture.runAsync(this::destroy);
    }
    
    public boolean isReady() { return ready; }
    public boolean isDestroyed() { return destroyed; }
    public byte[] getNodeId() { return nodeId.clone(); }
    public int getPort() { return port; }
    
    public void setOnReady(Consumer<Void> listener) { this.onReady = listener; }
    public void setOnError(Consumer<Exception> listener) { this.onError = listener; }
    public void setOnPeer(Consumer<PeerEvent> listener) { this.onPeer = listener; }
    public void setOnAnnounce(Consumer<AnnounceEvent> listener) { this.onAnnounce = listener; }
    public void setOnWarning(Consumer<String> listener) { this.onWarning = listener; }
    public void setOnListening(Consumer<ListeningEvent> listener) { this.onListening = listener; }
    public void setOnAnnounced(Consumer<AnnouncedEvent> listener) { this.onAnnounced = listener; }
    public void setOnAnnounceError(Consumer<AnnounceErrorEvent> listener) { this.onAnnounceError = listener; }
    public void setOnLookupComplete(Consumer<LookupCompleteEvent> listener) { this.onLookupComplete = listener; }
    public void setOnLookupError(Consumer<LookupErrorEvent> listener) { this.onLookupError = listener; }
    public void setOnDestroyed(Consumer<Void> listener) { this.onDestroyed = listener; }
    public void setOnHealthChange(Consumer<HealthChangeEvent> listener) { this.onHealthChange = listener; }
    public void setOnPeerHealthChange(Consumer<PeerHealthChangeEvent> listener) { this.onPeerHealthChange = listener; }
    
    private static byte[] generateNodeId() {
        byte[] id = new byte[20];
        new SecureRandom().nextBytes(id);
        return id;
    }
    
    private static String bytesToHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
    
    public static class DHTOptions {
        public final byte[] nodeId;
        public final List<String> bootstrap;
        public final int port;
        public final Duration announceInterval;
        public final Duration lookupTimeout;
        public final Duration healthCheckInterval;
        public final Duration peerStaleTimeout;
        public final boolean enableHealthMonitoring;
        public final int maxPeersPerLookup;
        public final int maxConcurrentLookups;
        public final double minReputationScore;
        
        private DHTOptions(Builder builder) {
            this.nodeId = builder.nodeId;
            this.bootstrap = builder.bootstrap;
            this.port = builder.port;
            this.announceInterval = builder.announceInterval;
            this.lookupTimeout = builder.lookupTimeout;
            this.healthCheckInterval = builder.healthCheckInterval;
            this.peerStaleTimeout = builder.peerStaleTimeout;
            this.enableHealthMonitoring = builder.enableHealthMonitoring;
            this.maxPeersPerLookup = builder.maxPeersPerLookup;
            this.maxConcurrentLookups = builder.maxConcurrentLookups;
            this.minReputationScore = builder.minReputationScore;
        }
        
        public static DHTOptions defaults() {
            return builder().build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public byte[] nodeId() { return nodeId; }
        public List<String> bootstrap() { return bootstrap; }
        public int port() { return port; }
        public Duration announceInterval() { return announceInterval; }
        
        public static class Builder {
            private byte[] nodeId = null;
            private List<String> bootstrap = DEFAULT_BOOTSTRAP;
            private int port = 0;
            private Duration announceInterval = Duration.ofMinutes(15);
            private Duration lookupTimeout = Duration.ofSeconds(30);
            private Duration healthCheckInterval = Duration.ofMinutes(1);
            private Duration peerStaleTimeout = Duration.ofHours(1);
            private boolean enableHealthMonitoring = true;
            private int maxPeersPerLookup = 50;
            private int maxConcurrentLookups = 10;
            private double minReputationScore = 10.0;
            
            public Builder nodeId(byte[] id) { this.nodeId = id; return this; }
            public Builder bootstrap(List<String> nodes) { this.bootstrap = nodes; return this; }
            public Builder port(int port) { this.port = port; return this; }
            public Builder announceInterval(Duration interval) { this.announceInterval = interval; return this; }
            public Builder lookupTimeout(Duration timeout) { this.lookupTimeout = timeout; return this; }
            public Builder healthCheckInterval(Duration interval) { this.healthCheckInterval = interval; return this; }
            public Builder peerStaleTimeout(Duration timeout) { this.peerStaleTimeout = timeout; return this; }
            public Builder enableHealthMonitoring(boolean enable) { this.enableHealthMonitoring = enable; return this; }
            public Builder maxPeersPerLookup(int max) { this.maxPeersPerLookup = max; return this; }
            public Builder maxConcurrentLookups(int max) { this.maxConcurrentLookups = max; return this; }
            public Builder minReputationScore(double min) { this.minReputationScore = min; return this; }
            
            public DHTOptions build() {
                return new DHTOptions(this);
            }
        }
    }
    
    public enum PeerHealthState { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY, BANNED }
    public enum NetworkHealthState { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY }
    
    public record PeerInfo(String host, int port) {}
    public record PeerEvent(String address, int port, String infoHash, String from) {}
    public record AnnounceEvent(String address, int port, String infoHash) {}
    public record AnnouncedEvent(String blobId, String infoHash) {}
    public record AnnounceErrorEvent(String blobId, String error) {}
    public record ListeningEvent(int port) {}
    public record LookupCompleteEvent(String blobId, int peersFound) {}
    public record LookupErrorEvent(String blobId, String error) {}
    public record HealthChangeEvent(Transport.HealthState oldState, Transport.HealthState newState) {}
    public record PeerHealthChangeEvent(String peerId, PeerHealthState oldState, PeerHealthState newState) {}
    
    public record PeerHealth(
        String peerId,
        long successes,
        long failures,
        Duration avgLatency,
        Instant lastSeen,
        PeerHealthState state,
        double successRate
    ) {}
    
    public record PeerReputation(
        String peerId,
        double score,
        long positiveInteractions,
        long negativeInteractions,
        Instant lastUpdated
    ) {}
    
    public record NetworkHealth(
        NetworkHealthState state,
        int totalPeers,
        int healthyPeers,
        int degradedPeers,
        int unhealthyPeers,
        int bannedPeers,
        double avgSuccessRate,
        Duration avgLatency,
        Instant lastChecked
    ) {}
    
    public record DHTStats(
        String nodeId,
        int port,
        int knownPeers,
        int announcedHashes,
        long totalLookups,
        long successfulLookups,
        long failedLookups,
        long totalAnnounces,
        long peersDiscovered,
        long messagesSent,
        long messagesReceived,
        Transport.HealthState healthState
    ) {
        public DHTStats(String nodeId, int port, int nodes, int announcedHashes) {
            this(nodeId, port, nodes, announcedHashes, 0, 0, 0, 0, 0, 0, 0, Transport.HealthState.STOPPED);
        }
    }
    
    private record AnnouncedHash(String blobId, ScheduledFuture<?> future, int wsPort, Instant announcedAt) {}
}
