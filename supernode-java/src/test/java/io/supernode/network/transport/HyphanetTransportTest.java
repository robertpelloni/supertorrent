package io.supernode.network.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HyphanetTransport")
class HyphanetTransportTest {
    
    @Nested
    @DisplayName("Transport Properties")
    class PropertiesTests {
        
        @Test
        @DisplayName("should have HYPHANET type")
        void shouldHaveHyphanetType() {
            HyphanetTransport transport = new HyphanetTransport();
            assertEquals(TransportType.HYPHANET, transport.getType());
        }
        
        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            HyphanetTransport transport = new HyphanetTransport();
            assertFalse(transport.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Address Handling")
    class AddressHandlingTests {
        
        @Test
        @DisplayName("should handle CHK keys")
        void shouldHandleChkKeys() {
            HyphanetTransport transport = new HyphanetTransport();
            assertTrue(transport.canHandle("CHK@abc123xyz"));
        }
        
        @Test
        @DisplayName("should handle SSK keys")
        void shouldHandleSskKeys() {
            HyphanetTransport transport = new HyphanetTransport();
            assertTrue(transport.canHandle("SSK@abc123/site"));
        }
        
        @Test
        @DisplayName("should handle USK keys")
        void shouldHandleUskKeys() {
            HyphanetTransport transport = new HyphanetTransport();
            assertTrue(transport.canHandle("USK@abc123/site/0"));
        }
        
        @Test
        @DisplayName("should handle freenet scheme")
        void shouldHandleFreenetScheme() {
            HyphanetTransport transport = new HyphanetTransport();
            assertTrue(transport.canHandle("freenet:CHK@abc123"));
        }
        
        @Test
        @DisplayName("should not handle clearnet addresses")
        void shouldNotHandleClearnetAddresses() {
            HyphanetTransport transport = new HyphanetTransport();
            assertFalse(transport.canHandle("ws://localhost:8080"));
        }
        
        @Test
        @DisplayName("should parse CHK address")
        void shouldParseChkAddress() {
            HyphanetTransport transport = new HyphanetTransport();
            TransportAddress addr = transport.parseAddress("CHK@abc123xyz");
            
            assertNotNull(addr);
            assertEquals(TransportType.HYPHANET, addr.type());
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() {
            HyphanetTransport transport = new HyphanetTransport();
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
        }
    }
}
