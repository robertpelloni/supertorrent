package io.supernode.network.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("I2PTransport")
class I2PTransportTest {
    
    @Nested
    @DisplayName("Transport Properties")
    class PropertiesTests {
        
        @Test
        @DisplayName("should have I2P type")
        void shouldHaveI2pType() {
            I2PTransport transport = new I2PTransport();
            assertEquals(TransportType.I2P, transport.getType());
        }
        
        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            I2PTransport transport = new I2PTransport();
            assertFalse(transport.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Address Handling")
    class AddressHandlingTests {
        
        @Test
        @DisplayName("should handle i2p addresses")
        void shouldHandleI2pAddresses() {
            I2PTransport transport = new I2PTransport();
            assertTrue(transport.canHandle("i2p://test.i2p"));
        }
        
        @Test
        @DisplayName("should handle addresses ending with .i2p")
        void shouldHandleAddressesEndingWithI2p() {
            I2PTransport transport = new I2PTransport();
            assertTrue(transport.canHandle("test.i2p:7657"));
        }
        
        @Test
        @DisplayName("should handle b32 addresses")
        void shouldHandleB32Addresses() {
            I2PTransport transport = new I2PTransport();
            assertTrue(transport.canHandle("abc123.b32.i2p"));
        }
        
        @Test
        @DisplayName("should not handle clearnet addresses")
        void shouldNotHandleClearnetAddresses() {
            I2PTransport transport = new I2PTransport();
            assertFalse(transport.canHandle("ws://localhost:8080"));
        }
        
        @Test
        @DisplayName("should parse i2p address")
        void shouldParseI2pAddress() {
            I2PTransport transport = new I2PTransport();
            TransportAddress addr = transport.parseAddress("i2p://test.i2p:7657");
            
            assertNotNull(addr);
            assertEquals(TransportType.I2P, addr.type());
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() {
            I2PTransport transport = new I2PTransport();
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
        }
    }
}
