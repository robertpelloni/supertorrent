package io.supernode.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DHTDiscovery")
class DHTDiscoveryTest {
    
    private DHTDiscovery dht;
    
    @BeforeEach
    void setUp() {
        dht = new DHTDiscovery();
    }
    
    @AfterEach
    void tearDown() {
        if (dht != null && !dht.isDestroyed()) {
            dht.destroy();
        }
    }
    
    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {
        
        @Test
        @DisplayName("should start successfully")
        void shouldStartSuccessfully() throws Exception {
            AtomicBoolean readyFired = new AtomicBoolean(false);
            dht.setOnReady(v -> readyFired.set(true));
            
            CompletableFuture<Void> startFuture = dht.start();
            startFuture.get(5, TimeUnit.SECONDS);
            
            assertTrue(dht.isReady());
            assertTrue(readyFired.get());
        }
        
        @Test
        @DisplayName("should destroy cleanly")
        void shouldDestroyCleanly() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            AtomicBoolean destroyedFired = new AtomicBoolean(false);
            dht.setOnDestroyed(v -> destroyedFired.set(true));
            
            dht.destroy();
            
            assertTrue(dht.isDestroyed());
            assertTrue(destroyedFired.get());
        }
        
        @Test
        @DisplayName("should fail start after destroy")
        void shouldFailStartAfterDestroy() {
            dht.destroy();
            
            assertThrows(Exception.class, () -> {
                dht.start().get(5, TimeUnit.SECONDS);
            });
        }
    }
    
    @Nested
    @DisplayName("Node Identity")
    class NodeIdentityTests {
        
        @Test
        @DisplayName("should have 20-byte node ID")
        void shouldHave20ByteNodeId() {
            assertEquals(20, dht.getNodeId().length);
        }
        
        @Test
        @DisplayName("should use provided node ID")
        void shouldUseProvidedNodeId() {
            byte[] customId = new byte[20];
            Arrays.fill(customId, (byte) 0x42);
            
            DHTDiscovery customDht = new DHTDiscovery(new DHTDiscovery.DHTOptions(
                customId, null, 0, 0
            ));
            
            assertArrayEquals(customId, customDht.getNodeId());
            customDht.destroy();
        }
        
        @Test
        @DisplayName("should generate unique node IDs")
        void shouldGenerateUniqueNodeIds() {
            DHTDiscovery dht2 = new DHTDiscovery();
            
            assertFalse(Arrays.equals(dht.getNodeId(), dht2.getNodeId()));
            
            dht2.destroy();
        }
    }
    
    @Nested
    @DisplayName("Info Hash Conversion")
    class InfoHashTests {
        
        @Test
        @DisplayName("should convert blob ID to 20-byte info hash")
        void shouldConvertBlobIdToInfoHash() {
            byte[] infoHash = dht.blobIdToInfoHash("test-blob-id");
            
            assertEquals(20, infoHash.length);
        }
        
        @Test
        @DisplayName("should produce consistent info hashes")
        void shouldProduceConsistentInfoHashes() {
            byte[] hash1 = dht.blobIdToInfoHash("same-blob");
            byte[] hash2 = dht.blobIdToInfoHash("same-blob");
            
            assertArrayEquals(hash1, hash2);
        }
        
        @Test
        @DisplayName("should produce different hashes for different IDs")
        void shouldProduceDifferentHashes() {
            byte[] hash1 = dht.blobIdToInfoHash("blob-1");
            byte[] hash2 = dht.blobIdToInfoHash("blob-2");
            
            assertFalse(Arrays.equals(hash1, hash2));
        }
    }
    
    @Nested
    @DisplayName("Announce/Unannounce")
    class AnnounceTests {
        
        @Test
        @DisplayName("should emit announced event")
        void shouldEmitAnnouncedEvent() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            AtomicReference<DHTDiscovery.AnnouncedEvent> eventRef = new AtomicReference<>();
            dht.setOnAnnounced(eventRef::set);
            
            dht.announce("test-blob", 8080);
            
            Thread.sleep(100);
            
            assertNotNull(eventRef.get());
            assertEquals("test-blob", eventRef.get().blobId());
        }
        
        @Test
        @DisplayName("should track announced hashes in stats")
        void shouldTrackAnnouncedHashes() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            dht.announce("blob-1", 8080);
            dht.announce("blob-2", 8080);
            
            DHTDiscovery.DHTStats stats = dht.getStats();
            
            assertNotNull(stats);
            assertEquals(2, stats.announcedHashes());
        }
        
        @Test
        @DisplayName("should remove hash on unannounce")
        void shouldRemoveHashOnUnannounce() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            dht.announce("blob-1", 8080);
            assertEquals(1, dht.getStats().announcedHashes());
            
            dht.unannounce("blob-1");
            assertEquals(0, dht.getStats().announcedHashes());
        }
    }
    
    @Nested
    @DisplayName("Lookup")
    class LookupTests {
        
        @Test
        @DisplayName("should complete lookup")
        void shouldCompleteLookup() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            AtomicBoolean lookupCompleted = new AtomicBoolean(false);
            dht.setOnLookupComplete(event -> {
                assertEquals("test-blob", event.blobId());
                lookupCompleted.set(true);
            });
            
            dht.lookup("test-blob", null);
            
            Thread.sleep(200);
            
            assertTrue(lookupCompleted.get());
        }
        
        @Test
        @DisplayName("should find peers with timeout")
        void shouldFindPeersWithTimeout() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            CompletableFuture<List<DHTDiscovery.PeerInfo>> future = 
                dht.findPeers("test-blob", 1000);
            
            List<DHTDiscovery.PeerInfo> peers = future.get(5, TimeUnit.SECONDS);
            
            assertNotNull(peers);
        }
    }
    
    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {
        
        @Test
        @DisplayName("should use default bootstrap nodes")
        void shouldUseDefaultBootstrap() {
            assertEquals(3, DHTDiscovery.DEFAULT_BOOTSTRAP.size());
            assertTrue(DHTDiscovery.DEFAULT_BOOTSTRAP.contains("router.bittorrent.com:6881"));
        }
        
        @Test
        @DisplayName("should accept custom bootstrap nodes")
        void shouldAcceptCustomBootstrap() {
            List<String> customBootstrap = List.of("custom.node:6881");
            
            DHTDiscovery customDht = new DHTDiscovery(new DHTDiscovery.DHTOptions(
                null, customBootstrap, 0, 0
            ));
            
            customDht.destroy();
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return null stats when not ready")
        void shouldReturnNullStatsWhenNotReady() {
            assertNull(dht.getStats());
        }
        
        @Test
        @DisplayName("should return stats when ready")
        void shouldReturnStatsWhenReady() throws Exception {
            dht.start().get(5, TimeUnit.SECONDS);
            
            DHTDiscovery.DHTStats stats = dht.getStats();
            
            assertNotNull(stats);
            assertNotNull(stats.nodeId());
            assertEquals(40, stats.nodeId().length());
        }
    }
}
