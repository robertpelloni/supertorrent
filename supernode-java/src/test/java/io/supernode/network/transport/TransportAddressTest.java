package io.supernode.network.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransportAddress")
class TransportAddressTest {
    
    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {
        
        @Test
        @DisplayName("should create clearnet address")
        void shouldCreateClearnetAddress() {
            TransportAddress addr = TransportAddress.clearnet("localhost", 8080);
            
            assertEquals(TransportType.CLEARNET, addr.type());
            assertEquals("localhost", addr.host());
            assertEquals(8080, addr.port());
            assertEquals("localhost:8080", addr.raw());
        }
        
        @Test
        @DisplayName("should create onion address")
        void shouldCreateOnionAddress() {
            TransportAddress addr = TransportAddress.onion("abc123xyz.onion", 80);
            
            assertEquals(TransportType.TOR, addr.type());
            assertEquals("abc123xyz.onion", addr.host());
            assertEquals(80, addr.port());
        }
        
        @Test
        @DisplayName("should create i2p address")
        void shouldCreateI2pAddress() {
            TransportAddress addr = TransportAddress.i2p("test.i2p", 7657);
            
            assertEquals(TransportType.I2P, addr.type());
            assertEquals("test.i2p", addr.host());
            assertEquals(7657, addr.port());
        }
        
        @Test
        @DisplayName("should create hyphanet address")
        void shouldCreateHyphanetAddress() {
            TransportAddress addr = TransportAddress.hyphanet("CHK@abc123");
            
            assertEquals(TransportType.HYPHANET, addr.type());
            assertEquals("CHK@abc123", addr.host());
            assertEquals(0, addr.port());
        }
        
        @Test
        @DisplayName("should create zeronet address")
        void shouldCreateZeronetAddress() {
            TransportAddress addr = TransportAddress.zeronet("1HeLLo4uzjaLetFx6NH3PMwFP3qbRbTf3D");
            
            assertEquals(TransportType.ZERONET, addr.type());
            assertEquals("1HeLLo4uzjaLetFx6NH3PMwFP3qbRbTf3D", addr.host());
        }
        
        @Test
        @DisplayName("should create ipfs address")
        void shouldCreateIpfsAddress() {
            TransportAddress addr = TransportAddress.ipfs("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG");
            
            assertEquals(TransportType.IPFS, addr.type());
            assertEquals("QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG", addr.host());
        }
    }
    
    @Nested
    @DisplayName("ToString")
    class ToStringTests {
        
        @Test
        @DisplayName("should return raw address from toString")
        void shouldReturnRawAddressFromToString() {
            TransportAddress addr = TransportAddress.clearnet("example.com", 443);
            
            assertEquals("example.com:443", addr.toString());
        }
        
        @Test
        @DisplayName("should return key for hyphanet toString")
        void shouldReturnKeyForHyphanetToString() {
            TransportAddress addr = TransportAddress.hyphanet("SSK@test/key");
            
            assertEquals("SSK@test/key", addr.toString());
        }
    }
    
    @Nested
    @DisplayName("Record Equality")
    class EqualityTests {
        
        @Test
        @DisplayName("should be equal for same values")
        void shouldBeEqualForSameValues() {
            TransportAddress addr1 = TransportAddress.clearnet("localhost", 8080);
            TransportAddress addr2 = TransportAddress.clearnet("localhost", 8080);
            
            assertEquals(addr1, addr2);
            assertEquals(addr1.hashCode(), addr2.hashCode());
        }
        
        @Test
        @DisplayName("should not be equal for different values")
        void shouldNotBeEqualForDifferentValues() {
            TransportAddress addr1 = TransportAddress.clearnet("localhost", 8080);
            TransportAddress addr2 = TransportAddress.clearnet("localhost", 9090);
            
            assertNotEquals(addr1, addr2);
        }
        
        @Test
        @DisplayName("should not be equal for different types")
        void shouldNotBeEqualForDifferentTypes() {
            TransportAddress addr1 = TransportAddress.clearnet("localhost", 80);
            TransportAddress addr2 = TransportAddress.onion("localhost", 80);
            
            assertNotEquals(addr1, addr2);
        }
    }
}
