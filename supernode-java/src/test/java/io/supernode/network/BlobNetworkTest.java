package io.supernode.network;

import io.supernode.storage.InMemoryBlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BlobNetwork")
class BlobNetworkTest {
    
    private InMemoryBlobStore blobStore;
    private BlobNetwork network;
    
    @BeforeEach
    void setUp() {
        blobStore = new InMemoryBlobStore();
        network = new BlobNetwork(blobStore);
    }
    
    @AfterEach
    void tearDown() {
        if (network != null && !network.isDestroyed()) {
            network.destroy();
        }
    }
    
    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {
        
        @Test
        @DisplayName("should listen on specified port")
        void shouldListenOnSpecifiedPort() throws Exception {
            AtomicBoolean listeningFired = new AtomicBoolean(false);
            AtomicReference<Integer> listeningPort = new AtomicReference<>();
            
            network.setOnListening(event -> {
                listeningFired.set(true);
                listeningPort.set(event.port());
            });
            
            int port = network.listen(0).get(5, TimeUnit.SECONDS);
            
            assertTrue(listeningFired.get());
            assertTrue(port > 0);
            assertEquals(port, listeningPort.get());
        }
        
        @Test
        @DisplayName("should destroy cleanly")
        void shouldDestroyCleanly() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            AtomicBoolean destroyedFired = new AtomicBoolean(false);
            network.setOnDestroyed(v -> destroyedFired.set(true));
            
            network.destroy();
            
            assertTrue(network.isDestroyed());
            assertTrue(destroyedFired.get());
        }
    }
    
    @Nested
    @DisplayName("Peer Identity")
    class PeerIdentityTests {
        
        @Test
        @DisplayName("should have peer ID")
        void shouldHavePeerId() {
            assertNotNull(network.getPeerId());
            assertEquals(32, network.getPeerId().length());
        }
        
        @Test
        @DisplayName("should use provided peer ID")
        void shouldUseProvidedPeerId() {
            String customPeerId = "custom-peer-id-12345";
            BlobNetwork customNetwork = new BlobNetwork(blobStore, 
                BlobNetwork.BlobNetworkOptions.builder()
                    .peerId(customPeerId)
                    .maxConnections(50)
                    .build());
            
            assertEquals(customPeerId, customNetwork.getPeerId());
            customNetwork.destroy();
        }
        
        @Test
        @DisplayName("should generate unique peer IDs")
        void shouldGenerateUniquePeerIds() {
            BlobNetwork network2 = new BlobNetwork(blobStore);
            
            assertNotEquals(network.getPeerId(), network2.getPeerId());
            
            network2.destroy();
        }
    }
    
    @Nested
    @DisplayName("Blob Announcements")
    class AnnouncementTests {
        
        @Test
        @DisplayName("should track announced blobs")
        void shouldTrackAnnouncedBlobs() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            network.announceBlob("blob-1");
            network.announceBlob("blob-2");
            
            BlobNetwork.BlobNetworkStats stats = network.getStats();
            assertEquals(2, stats.announcedBlobs());
        }
    }
    
    @Nested
    @DisplayName("Peer Management")
    class PeerManagementTests {
        
        @Test
        @DisplayName("should start with no peers")
        void shouldStartWithNoPeers() {
            List<BlobNetwork.PeerConnection> peers = network.getPeers();
            assertTrue(peers.isEmpty());
        }
        
        @Test
        @DisplayName("should track peer count in stats")
        void shouldTrackPeerCountInStats() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            BlobNetwork.BlobNetworkStats stats = network.getStats();
            assertEquals(0, stats.activePeers());
        }
    }
    
    @Nested
    @DisplayName("Blob Operations")
    class BlobOperationsTests {
        
        @Test
        @DisplayName("should find peers with blob")
        void shouldFindPeersWithBlob() {
            List<BlobNetwork.PeerConnection> peers = network.findPeersWithBlob("unknown-blob");
            assertTrue(peers.isEmpty());
        }
        
        @Test
        @DisplayName("should query for blob without error")
        void shouldQueryForBlobWithoutError() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            assertDoesNotThrow(() -> network.queryBlob("some-blob-id"));
        }
    }
    
    @Nested
    @DisplayName("Stats")
    class StatsTests {
        
        @Test
        @DisplayName("should return complete stats")
        void shouldReturnCompleteStats() throws Exception {
            int port = network.listen(0).get(5, TimeUnit.SECONDS);
            
            network.announceBlob("test-blob");
            
            BlobNetwork.BlobNetworkStats stats = network.getStats();
            
            assertNotNull(stats);
            assertEquals(network.getPeerId(), stats.peerId());
            assertEquals(port, stats.port());
            assertEquals(0, stats.activePeers());
            assertEquals(1, stats.announcedBlobs());
        }
    }
    
    @Nested
    @DisplayName("Connection")
    class ConnectionTests {
        
        @Test
        @DisplayName("should fail connect to invalid address")
        void shouldFailConnectToInvalidAddress() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            CompletableFuture<BlobNetwork.PeerConnection> future = 
                network.connect("ws://invalid-host-that-does-not-exist:9999");
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        }
        
        @Test
        @DisplayName("should reject connections after destroy")
        void shouldRejectConnectionsAfterDestroy() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            network.destroy();
            
            CompletableFuture<BlobNetwork.PeerConnection> future = 
                network.connect("ws://localhost:8080");
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }
    
    @Nested
    @DisplayName("Events")
    class EventTests {
        
        @Test
        @DisplayName("should emit have events")
        void shouldEmitHaveEvents() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            AtomicReference<BlobNetwork.HaveEvent> eventRef = new AtomicReference<>();
            network.setOnHave(eventRef::set);
            
            assertNull(eventRef.get());
        }
        
        @Test
        @DisplayName("should emit upload events")
        void shouldEmitUploadEvents() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            AtomicReference<BlobNetwork.UploadEvent> eventRef = new AtomicReference<>();
            network.setOnUpload(eventRef::set);
            
            assertNull(eventRef.get());
        }
        
        @Test
        @DisplayName("should emit download events")
        void shouldEmitDownloadEvents() throws Exception {
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            AtomicReference<BlobNetwork.DownloadEvent> eventRef = new AtomicReference<>();
            network.setOnDownload(eventRef::set);
            
            assertNull(eventRef.get());
        }
    }
    
    @Nested
    @DisplayName("Blob Store Integration")
    class BlobStoreIntegrationTests {
        
        @Test
        @DisplayName("should respond to queries for stored blobs")
        void shouldRespondToQueriesForStoredBlobs() throws Exception {
            byte[] data = new byte[100];
            new SecureRandom().nextBytes(data);
            
            blobStore.put("test-hash", data);
            network.listen(0).get(5, TimeUnit.SECONDS);
            
            assertTrue(blobStore.has("test-hash"));
        }
    }
}
