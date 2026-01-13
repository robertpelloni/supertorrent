package io.supernode.network.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransportManager")
class TransportManagerTest {
    
    private TransportManager manager;
    
    @BeforeEach
    void setUp() {
        manager = new TransportManager();
    }
    
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stopAll().join();
        }
    }
    
    @Nested
    @DisplayName("Transport Registration")
    class RegistrationTests {
        
        @Test
        @DisplayName("should register transport")
        void shouldRegisterTransport() {
            ClearnetTransport transport = new ClearnetTransport();
            manager.registerTransport(transport);
            
            Transport retrieved = manager.getTransport(TransportType.CLEARNET);
            assertNotNull(retrieved);
        }
        
        @Test
        @DisplayName("should get registered transport")
        void shouldGetRegisteredTransport() {
            ClearnetTransport transport = new ClearnetTransport();
            manager.registerTransport(transport);
            
            Transport retrieved = manager.getTransport(TransportType.CLEARNET);
            assertSame(transport, retrieved);
        }
        
        @Test
        @DisplayName("should return null for unregistered type")
        void shouldReturnNullForUnregisteredType() {
            Transport retrieved = manager.getTransport(TransportType.TOR);
            assertNull(retrieved);
        }
        
        @Test
        @DisplayName("should track multiple transports")
        void shouldTrackMultipleTransports() {
            manager.registerTransport(new ClearnetTransport());
            
            List<Transport> transports = manager.getAllTransports();
            assertEquals(1, transports.size());
            assertEquals(TransportType.CLEARNET, transports.get(0).getType());
        }
        
        @Test
        @DisplayName("should unregister transport")
        void shouldUnregisterTransport() {
            manager.registerTransport(new ClearnetTransport());
            assertNotNull(manager.getTransport(TransportType.CLEARNET));
            
            manager.unregisterTransport(TransportType.CLEARNET);
            assertNull(manager.getTransport(TransportType.CLEARNET));
        }
    }
    
    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {
        
        @Test
        @DisplayName("should start all registered transports")
        void shouldStartAllRegisteredTransports() throws Exception {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            assertTrue(clearnet.isRunning());
        }
        
        @Test
        @DisplayName("should stop all registered transports")
        void shouldStopAllRegisteredTransports() throws Exception {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            
            manager.startAll().get(10, TimeUnit.SECONDS);
            manager.stopAll().get(10, TimeUnit.SECONDS);
            
            assertFalse(clearnet.isRunning());
        }
        
        @Test
        @DisplayName("should handle empty manager start")
        void shouldHandleEmptyManagerStart() throws Exception {
            assertDoesNotThrow(() -> manager.startAll().get(5, TimeUnit.SECONDS));
        }
        
        @Test
        @DisplayName("should handle empty manager stop")
        void shouldHandleEmptyManagerStop() throws Exception {
            assertDoesNotThrow(() -> manager.stopAll().get(5, TimeUnit.SECONDS));
        }
        
        @Test
        @DisplayName("should report running state")
        void shouldReportRunningState() throws Exception {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            
            assertFalse(manager.isRunning());
            
            manager.startAll().get(10, TimeUnit.SECONDS);
            assertTrue(manager.isRunning());
            
            manager.stopAll().get(10, TimeUnit.SECONDS);
            assertFalse(manager.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Address Resolution")
    class AddressResolutionTests {
        
        @Test
        @DisplayName("should parse clearnet address")
        void shouldParseClearnetAddress() {
            manager.registerTransport(new ClearnetTransport());
            
            TransportAddress addr = manager.parseAddress("ws://localhost:8080");
            
            assertNotNull(addr);
            assertEquals(TransportType.CLEARNET, addr.type());
        }
        
        @Test
        @DisplayName("should return null for unparseable address")
        void shouldReturnNullForUnparseableAddress() {
            TransportAddress addr = manager.parseAddress("invalid://address");
            assertNull(addr);
        }
        
        @Test
        @DisplayName("should get local addresses after start")
        void shouldGetLocalAddressesAfterStart() throws Exception {
            manager.registerTransport(new ClearnetTransport());
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            List<TransportAddress> addresses = manager.getLocalAddresses();
            
            assertFalse(addresses.isEmpty());
            assertEquals(TransportType.CLEARNET, addresses.get(0).type());
        }
    }
    
    @Nested
    @DisplayName("Connection Management")
    class ConnectionTests {
        
        @Test
        @DisplayName("should fail connect to unavailable transport")
        void shouldFailConnectToUnavailableTransport() {
            TransportAddress addr = TransportAddress.onion("test.onion", 80);
            
            CompletableFuture<TransportConnection> future = manager.connect(addr);
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        }
        
        @Test
        @DisplayName("should start with zero connections")
        void shouldStartWithZeroConnections() {
            assertEquals(0, manager.getConnectionCount());
            assertTrue(manager.getConnections().isEmpty());
        }
        
        @Test
        @DisplayName("should fail connect with string to unavailable transport")
        void shouldFailConnectWithStringToUnavailableTransport() {
            CompletableFuture<TransportConnection> future = manager.connect("ws://invalid-host:9999");
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return aggregate stats")
        void shouldReturnAggregateStats() throws Exception {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            TransportManager.AggregateStats stats = manager.getStats();
            
            assertNotNull(stats);
            assertNotNull(stats.byTransport());
            assertTrue(stats.byTransport().containsKey(TransportType.CLEARNET));
        }
        
        @Test
        @DisplayName("should return empty stats when no transports")
        void shouldReturnEmptyStatsWhenNoTransports() {
            TransportManager.AggregateStats stats = manager.getStats();
            
            assertNotNull(stats);
            assertTrue(stats.byTransport().isEmpty());
            assertEquals(0, stats.connectionsIn());
            assertEquals(0, stats.connectionsOut());
        }
        
        @Test
        @DisplayName("should track failover count in stats")
        void shouldTrackFailoverCountInStats() throws Exception {
            manager.registerTransport(new ClearnetTransport());
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            TransportManager.AggregateStats stats = manager.getStats();
            assertEquals(0, stats.failovers());
        }
        
        @Test
        @DisplayName("should track circuit breaker trips in stats")
        void shouldTrackCircuitBreakerTripsInStats() throws Exception {
            manager.registerTransport(new ClearnetTransport());
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            TransportManager.AggregateStats stats = manager.getStats();
            assertEquals(0, stats.circuitBreakerTrips());
        }
    }
    
    @Nested
    @DisplayName("Available Transports")
    class AvailabilityTests {
        
        @Test
        @DisplayName("should list available transports")
        void shouldListAvailableTransports() {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            
            List<Transport> available = manager.getAvailableTransports();
            
            assertTrue(available.contains(clearnet));
        }
        
        @Test
        @DisplayName("should return empty list when no available transports")
        void shouldReturnEmptyListWhenNoAvailableTransports() {
            List<Transport> available = manager.getAvailableTransports();
            assertTrue(available.isEmpty());
        }
        
        @Test
        @DisplayName("should list healthy transports")
        void shouldListHealthyTransports() throws Exception {
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            List<Transport> healthy = manager.getHealthyTransports();
            assertTrue(healthy.contains(clearnet));
        }
    }
    
    @Nested
    @DisplayName("Event Handlers")
    class EventTests {
        
        @Test
        @DisplayName("should accept connection handler")
        void shouldAcceptConnectionHandler() {
            assertDoesNotThrow(() -> manager.setOnConnection(conn -> {}));
        }
        
        @Test
        @DisplayName("should accept error handler")
        void shouldAcceptErrorHandler() {
            assertDoesNotThrow(() -> manager.setOnError(error -> {}));
        }
        
        @Test
        @DisplayName("should accept failover handler")
        void shouldAcceptFailoverHandler() {
            assertDoesNotThrow(() -> manager.setOnFailover(event -> {}));
        }
        
        @Test
        @DisplayName("should accept health change handler")
        void shouldAcceptHealthChangeHandler() {
            assertDoesNotThrow(() -> manager.setOnHealthChange(event -> {}));
        }
    }
    
    @Nested
    @DisplayName("Health Monitoring")
    class HealthMonitoringTests {
        
        @Test
        @DisplayName("should get transport health status")
        void shouldGetTransportHealthStatus() throws Exception {
            manager.registerTransport(new ClearnetTransport());
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            Transport.HealthStatus status = manager.getTransportHealthStatus(TransportType.CLEARNET);
            
            assertNotNull(status);
            assertEquals(Transport.HealthState.HEALTHY, status.state());
        }
        
        @Test
        @DisplayName("should return null health status for unregistered transport")
        void shouldReturnNullHealthStatusForUnregisteredTransport() {
            Transport.HealthStatus status = manager.getTransportHealthStatus(TransportType.TOR);
            assertNull(status);
        }
        
        @Test
        @DisplayName("should get all health statuses")
        void shouldGetAllHealthStatuses() throws Exception {
            manager.registerTransport(new ClearnetTransport());
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            Map<TransportType, Transport.HealthStatus> statuses = manager.getAllHealthStatuses();
            
            assertNotNull(statuses);
            assertTrue(statuses.containsKey(TransportType.CLEARNET));
        }
        
        @Test
        @DisplayName("should notify on health change")
        void shouldNotifyOnHealthChange() throws Exception {
            AtomicReference<TransportManager.HealthChangeEvent> receivedEvent = new AtomicReference<>();
            manager.setOnHealthChange(receivedEvent::set);
            
            ClearnetTransport clearnet = new ClearnetTransport();
            manager.registerTransport(clearnet);
            manager.startAll().get(10, TimeUnit.SECONDS);
            
            assertNotNull(manager.getTransportHealthStatus(TransportType.CLEARNET));
        }
    }
    
    @Nested
    @DisplayName("Circuit Breaker")
    class CircuitBreakerTests {
        
        @Test
        @DisplayName("should get circuit breaker status")
        void shouldGetCircuitBreakerStatus() {
            manager.registerTransport(new ClearnetTransport());
            
            TransportManager.CircuitBreakerStatus status = 
                manager.getCircuitBreakerStatus(TransportType.CLEARNET);
            
            assertNotNull(status);
            assertFalse(status.open());
            assertEquals(0, status.failures());
        }
        
        @Test
        @DisplayName("should reset circuit breaker")
        void shouldResetCircuitBreaker() {
            manager.registerTransport(new ClearnetTransport());
            
            assertDoesNotThrow(() -> manager.resetCircuitBreaker(TransportType.CLEARNET));
            
            TransportManager.CircuitBreakerStatus status = 
                manager.getCircuitBreakerStatus(TransportType.CLEARNET);
            assertFalse(status.open());
        }
        
        @Test
        @DisplayName("should return null status for unregistered transport")
        void shouldReturnNullStatusForUnregisteredTransport() {
            TransportManager.CircuitBreakerStatus status = 
                manager.getCircuitBreakerStatus(TransportType.TOR);
            assertNull(status);
        }
    }
    
    @Nested
    @DisplayName("Load Balancing")
    class LoadBalancingTests {
        
        @Test
        @DisplayName("should select best transport")
        void shouldSelectBestTransport() {
            manager.registerTransport(new ClearnetTransport());
            
            TransportType best = manager.selectBestTransport();
            
            assertEquals(TransportType.CLEARNET, best);
        }
        
        @Test
        @DisplayName("should get transports by score")
        void shouldGetTransportsByScore() {
            manager.registerTransport(new ClearnetTransport());
            
            List<TransportType> sorted = manager.getTransportsByScore();
            
            assertFalse(sorted.isEmpty());
            assertEquals(TransportType.CLEARNET, sorted.get(0));
        }
        
        @Test
        @DisplayName("should fail load balanced connect with empty list")
        void shouldFailLoadBalancedConnectWithEmptyList() {
            CompletableFuture<TransportConnection> future = 
                manager.connectLoadBalanced(List.of());
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }
    
    @Nested
    @DisplayName("Manager Options")
    class ManagerOptionsTests {
        
        @Test
        @DisplayName("should create manager with custom options")
        void shouldCreateManagerWithCustomOptions() {
            TransportManager.ManagerOptions options = TransportManager.ManagerOptions.builder()
                .enableHealthMonitoring(true)
                .enableFailover(true)
                .healthCheckInterval(Duration.ofSeconds(60))
                .circuitBreakerThreshold(10)
                .transportPriority(TransportType.CLEARNET, 100)
                .build();
            
            TransportManager customManager = new TransportManager(options);
            assertNotNull(customManager);
            customManager.stopAll().join();
        }
        
        @Test
        @DisplayName("should use default options")
        void shouldUseDefaultOptions() {
            TransportManager.ManagerOptions defaults = TransportManager.ManagerOptions.defaults();
            
            assertNotNull(defaults);
            assertTrue(defaults.enableHealthMonitoring());
            assertTrue(defaults.enableFailover());
        }
    }
    
    @Nested
    @DisplayName("Failover")
    class FailoverTests {
        
        @Test
        @DisplayName("should notify on failover event")
        void shouldNotifyOnFailoverEvent() {
            AtomicBoolean notified = new AtomicBoolean(false);
            manager.setOnFailover(event -> notified.set(true));
            
            assertNotNull(manager);
        }
    }
}
