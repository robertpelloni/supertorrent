package io.supernode.network.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IPFSTransport")
class IPFSTransportTest {
    
    @Nested
    @DisplayName("Transport Properties")
    class PropertiesTests {
        
        @Test
        @DisplayName("should have IPFS type")
        void shouldHaveIpfsType() {
            IPFSTransport transport = new IPFSTransport();
            assertEquals(TransportType.IPFS, transport.getType());
        }
        
        @Test
        @DisplayName("should not be running initially")
        void shouldNotBeRunningInitially() {
            IPFSTransport transport = new IPFSTransport();
            assertFalse(transport.isRunning());
        }
    }
    
    @Nested
    @DisplayName("Address Handling")
    class AddressHandlingTests {
        
        @Test
        @DisplayName("should handle ipfs scheme")
        void shouldHandleIpfsScheme() {
            IPFSTransport transport = new IPFSTransport();
            assertTrue(transport.canHandle("ipfs://QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"));
        }
        
        @Test
        @DisplayName("should handle /ipfs/ paths")
        void shouldHandleIpfsPaths() {
            IPFSTransport transport = new IPFSTransport();
            assertTrue(transport.canHandle("/ipfs/QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG"));
        }
        
        @Test
        @DisplayName("should handle /p2p/ multiaddrs")
        void shouldHandleP2pMultiaddrs() {
            IPFSTransport transport = new IPFSTransport();
            assertTrue(transport.canHandle("/p2p/12D3KooWxxxxxx"));
        }
        
        @Test
        @DisplayName("should not handle clearnet addresses")
        void shouldNotHandleClearnetAddresses() {
            IPFSTransport transport = new IPFSTransport();
            assertFalse(transport.canHandle("ws://localhost:8080"));
        }
        
        @Test
        @DisplayName("should parse ipfs address")
        void shouldParseIpfsAddress() {
            IPFSTransport transport = new IPFSTransport();
            TransportAddress addr = transport.parseAddress("ipfs://QmTest123");
            
            assertNotNull(addr);
            assertEquals(TransportType.IPFS, addr.type());
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return stats")
        void shouldReturnStats() {
            IPFSTransport transport = new IPFSTransport();
            Transport.TransportStats stats = transport.getStats();
            
            assertNotNull(stats);
        }
    }
}
