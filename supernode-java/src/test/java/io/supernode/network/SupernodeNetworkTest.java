package io.supernode.network;

import io.supernode.storage.InMemoryBlobStore;
import io.supernode.storage.SupernodeStorage.IngestResult;
import io.supernode.storage.SupernodeStorage.RetrieveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SupernodeNetwork")
class SupernodeNetworkTest {
    
    private SupernodeNetwork network;
    
    @BeforeEach
    void setUp() {
        network = new SupernodeNetwork();
    }
    
    @AfterEach
    void tearDown() {
        if (network != null && !network.isDestroyed()) {
            network.destroy();
        }
    }
    
    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        
        @Test
        @DisplayName("should create with default options")
        void shouldCreateWithDefaultOptions() {
            assertNotNull(network);
            assertNotNull(network.getBlobStore());
            assertNotNull(network.getStorage());
            assertNotNull(network.getBlobNetwork());
            assertNotNull(network.getDht());
            assertNotNull(network.getManifestDistributor());
            assertFalse(network.isDestroyed());
        }
        
        @Test
        @DisplayName("should create with custom blob store")
        void shouldCreateWithCustomBlobStore() {
            InMemoryBlobStore customStore = new InMemoryBlobStore();
            SupernodeNetwork.SupernodeNetworkOptions options = 
                SupernodeNetwork.SupernodeNetworkOptions.builder()
                    .blobStore(customStore)
                    .maxConnections(50)
                    .build();
        }
    }
    
    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {
        
        @Test
        @DisplayName("should return combined network stats")
        void shouldReturnCombinedNetworkStats() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            SupernodeNetwork.NetworkStats stats = network.stats();
            
            assertNotNull(stats);
            assertNotNull(stats.storage());
            assertNotNull(stats.blobNetwork());
            assertNotNull(stats.dht());
            assertNotNull(stats.manifestDistributor());
        }
        
        @Test
        @DisplayName("should track blob count after ingest")
        void shouldTrackBlobCountAfterIngest() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] fileData = "Stats test data".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            SupernodeNetwork.NetworkStats statsBefore = network.stats();
            int blobsBefore = statsBefore.storage().blobCount();
            
            network.ingestFile(fileData, "stats-test.txt", masterKey);
            
            SupernodeNetwork.NetworkStats statsAfter = network.stats();
            int blobsAfter = statsAfter.storage().blobCount();
            
            assertTrue(blobsAfter > blobsBefore, 
                "Blob count should increase after ingest");
        }
    }
    
    @Nested
    @DisplayName("Manifest Operations")
    class ManifestOperationsTests {
        
        @Test
        @DisplayName("should share manifest without error")
        void shouldShareManifestWithoutError() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] fileData = "Manifest test".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            IngestResult result = network.ingestFile(fileData, "manifest-test.txt", masterKey);
            
            assertDoesNotThrow(() -> network.shareManifest(result.fileId()));
        }
    }
    
    @Nested
    @DisplayName("Component Access")
    class ComponentAccessTests {
        
        @Test
        @DisplayName("should provide access to all components")
        void shouldProvideAccessToAllComponents() {
            assertNotNull(network.getBlobStore(), "BlobStore should be accessible");
            assertNotNull(network.getStorage(), "Storage should be accessible");
            assertNotNull(network.getBlobNetwork(), "BlobNetwork should be accessible");
            assertNotNull(network.getDht(), "DHT should be accessible");
            assertNotNull(network.getManifestDistributor(), "ManifestDistributor should be accessible");
        }
    }
    
    @Nested
    @DisplayName("Event Listeners")
    class EventListenerTests {
        
        @Test
        @DisplayName("should accept all event listeners")
        void shouldAcceptAllEventListeners() {
            assertDoesNotThrow(() -> {
                network.setOnListening(e -> {});
                network.setOnPeer(e -> {});
                network.setOnDisconnect(e -> {});
                network.setOnFileIngested(e -> {});
                network.setOnFileRetrieved(e -> {});
                network.setOnDestroyed(v -> {});
            });
        }
    }
    
    @Nested
    @DisplayName("Options Record")
    class OptionsRecordTests {
        
        @Test
        @DisplayName("should create default options")
        void shouldCreateDefaultOptions() {
            SupernodeNetwork.SupernodeNetworkOptions options = 
                SupernodeNetwork.SupernodeNetworkOptions.defaults();
            
            assertNull(options.blobStore());
            assertNull(options.peerId());
            assertNotNull(options.bootstrap());
            assertEquals(50, options.maxConnections());
            assertFalse(options.enableErasure());
            assertEquals(4, options.dataShards());
            assertEquals(2, options.parityShards());
        }
        
        @Test
        @DisplayName("should create erasure options via factory method")
        void shouldCreateErasureOptionsViaFactoryMethod() {
            SupernodeNetwork.SupernodeNetworkOptions options = 
                SupernodeNetwork.SupernodeNetworkOptions.builder()
                    .enableErasure(true)
                    .dataShards(6)
                    .parityShards(3)
                    .build();
            
            assertTrue(options.enableErasure());
            assertEquals(6, options.dataShards());
            assertEquals(3, options.parityShards());
        }
    }
    
    @Nested
    @DisplayName("Event Records")
    class EventRecordTests {
        
        @Test
        @DisplayName("should create ListeningEvent correctly")
        void shouldCreateListeningEventCorrectly() {
            SupernodeNetwork.ListeningEvent event = new SupernodeNetwork.ListeningEvent(8080);
            assertEquals(8080, event.port());
        }
        
        @Test
        @DisplayName("should create PeerEvent correctly")
        void shouldCreatePeerEventCorrectly() {
            SupernodeNetwork.PeerEvent event = 
                new SupernodeNetwork.PeerEvent("peer-123", "192.168.1.1", 9000);
            
            assertEquals("peer-123", event.peerId());
            assertEquals("192.168.1.1", event.host());
            assertEquals(9000, event.port());
        }
        
        @Test
        @DisplayName("should create DisconnectEvent correctly")
        void shouldCreateDisconnectEventCorrectly() {
            SupernodeNetwork.DisconnectEvent event = 
                new SupernodeNetwork.DisconnectEvent("peer-456");
            
            assertEquals("peer-456", event.peerId());
        }
        
        @Test
        @DisplayName("should create FileIngestedEvent correctly")
        void shouldCreateFileIngestedEventCorrectly() {
            SupernodeNetwork.FileIngestedEvent event = 
                new SupernodeNetwork.FileIngestedEvent("file-123", "test.txt", 1024);
            
            assertEquals("file-123", event.fileId());
            assertEquals("test.txt", event.fileName());
            assertEquals(1024, event.size());
        }
        
        @Test
        @DisplayName("should create FileRetrievedEvent correctly")
        void shouldCreateFileRetrievedEventCorrectly() {
            SupernodeNetwork.FileRetrievedEvent event = 
                new SupernodeNetwork.FileRetrievedEvent("file-456", "data.bin", 2048);
            
            assertEquals("file-456", event.fileId());
            assertEquals("data.bin", event.fileName());
            assertEquals(2048, event.size());
        }
        
        @Test
        @DisplayName("should create NetworkStats correctly")
        void shouldCreateNetworkStatsCorrectly() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            SupernodeNetwork.NetworkStats stats = network.stats();
            
            assertNotNull(stats.storage());
            assertNotNull(stats.blobNetwork());
            assertNotNull(stats.dht());
            assertNotNull(stats.manifestDistributor());
        }
    }
    
    private byte[] generateMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
