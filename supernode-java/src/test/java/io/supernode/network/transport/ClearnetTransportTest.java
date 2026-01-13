package io.supernode.network.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClearnetTransport")
class ClearnetTransportTest {
    
    private ClearnetTransport transport;
    
    @BeforeEach
    void setUp() {
        transport = new ClearnetTransport();
    }
    
    @AfterEach
    void tearDown() {
        if (transport != null && transport.isRunning()) {
            transport.stop().join();
        }
    }
    
    @Nested
    @DisplayName("Transport Properties")
    class PropertiesTests {
        
        @Test
        @DisplayName("should have CLEARNET type")
        void shouldHaveClearnetType() {
            assertEquals(TransportType.CLEARNET, transport.getType());
        }
        
        @Test
        @DisplayName("should always be available")
        void shouldAlwaysBeAvailable() {
            assertTrue(transport.isAvailable());
        }
        
        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            assertFalse(transport.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {
        
        @Test
        @DisplayName("should start successfully")
        void shouldStartSuccessfully() throws Exception {
            TransportAddress addr = transport.start().get(10, TimeUnit.SECONDS);
            
            assertNotNull(addr);
            assertEquals(TransportType.CLEARNET, addr.type());
            assertTrue(transport.isRunning());
        }
        
        @Test
        @DisplayName("should stop successfully")
        void shouldStopSuccessfully() throws Exception {
            transport.start().get(10, TimeUnit.SECONDS);
            
            transport.stop().get(10, TimeUnit.SECONDS);
            
            assertFalse(transport.isRunning());
        }
        
        @Test
        @DisplayName("should get local address after start")
        void shouldGetLocalAddressAfterStart() throws Exception {
            transport.start().get(10, TimeUnit.SECONDS);
            
            TransportAddress local = transport.getLocalAddress();
            
            assertNotNull(local);
            assertTrue(local.port() > 0);
        }
        
        @Test
        @DisplayName("should return null local address before start")
        void shouldReturnNullLocalAddressBeforeStart() {
            TransportAddress local = transport.getLocalAddress();
            assertNull(local);
        }
    }
    
    @Nested
    @DisplayName("Address Handling")
    class AddressHandlingTests {
        
        @Test
        @DisplayName("should handle ws scheme")
        void shouldHandleWsScheme() {
            assertTrue(transport.canHandle("ws://localhost:8080"));
        }
        
        @Test
        @DisplayName("should handle wss scheme")
        void shouldHandleWssScheme() {
            assertTrue(transport.canHandle("wss://localhost:8080"));
        }
        
        @Test
        @DisplayName("should not handle tor addresses")
        void shouldNotHandleTorAddresses() {
            assertFalse(transport.canHandle("tor://test.onion:80"));
        }
        
        @Test
        @DisplayName("should parse ws address")
        void shouldParseWsAddress() {
            TransportAddress addr = transport.parseAddress("ws://example.com:9000");
            
            assertNotNull(addr);
            assertEquals(TransportType.CLEARNET, addr.type());
            assertEquals("example.com", addr.host());
            assertEquals(9000, addr.port());
        }
        
        @Test
        @DisplayName("should parse wss address")
        void shouldParseWssAddress() {
            TransportAddress addr = transport.parseAddress("wss://secure.example.com:443");
            
            assertNotNull(addr);
            assertEquals("secure.example.com", addr.host());
            assertEquals(443, addr.port());
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() throws Exception {
            transport.start().get(10, TimeUnit.SECONDS);
            
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
            assertEquals(0, stats.connectionsIn());
            assertEquals(0, stats.connectionsOut());
        }
        
        @Test
        @DisplayName("should return stats before start")
        void shouldReturnStatsBeforeStart() {
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
        }
    }
    
    @Nested
    @DisplayName("Event Handlers")
    class EventTests {
        
        @Test
        @DisplayName("should accept connection handler")
        void shouldAcceptConnectionHandler() {
            assertDoesNotThrow(() -> transport.setOnConnection(conn -> {}));
        }
        
        @Test
        @DisplayName("should accept error handler")
        void shouldAcceptErrorHandler() {
            assertDoesNotThrow(() -> transport.setOnError(error -> {}));
        }
    }
}
