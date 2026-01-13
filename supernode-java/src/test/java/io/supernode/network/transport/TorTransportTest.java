package io.supernode.network.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TorTransport")
class TorTransportTest {
    
    @Nested
    @DisplayName("Transport Properties")
    class PropertiesTests {
        
        @Test
        @DisplayName("should have TOR type")
        void shouldHaveTorType() {
            TorTransport transport = new TorTransport();
            assertEquals(TransportType.TOR, transport.getType());
        }
        
        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            TorTransport transport = new TorTransport();
            assertFalse(transport.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Address Handling")
    class AddressHandlingTests {
        
        @Test
        @DisplayName("should handle onion addresses")
        void shouldHandleOnionAddresses() {
            TorTransport transport = new TorTransport();
            assertTrue(transport.canHandle("tor://abc123xyz.onion:80"));
        }
        
        @Test
        @DisplayName("should handle addresses ending with .onion")
        void shouldHandleAddressesEndingWithOnion() {
            TorTransport transport = new TorTransport();
            assertTrue(transport.canHandle("test.onion:8080"));
        }
        
        @Test
        @DisplayName("should not handle clearnet addresses")
        void shouldNotHandleClearnetAddresses() {
            TorTransport transport = new TorTransport();
            assertFalse(transport.canHandle("ws://localhost:8080"));
        }
        
        @Test
        @DisplayName("should parse onion address")
        void shouldParseOnionAddress() {
            TorTransport transport = new TorTransport();
            TransportAddress addr = transport.parseAddress("tor://abc123.onion:80");
            
            assertNotNull(addr);
            assertEquals(TransportType.TOR, addr.type());
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() {
            TorTransport transport = new TorTransport();
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
        }
    }
}
