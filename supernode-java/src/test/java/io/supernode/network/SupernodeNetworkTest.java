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
                new SupernodeNetwork.SupernodeNetworkOptions(
                    customStore, null, null, 50, false, 4, 2, null
                );
            
            SupernodeNetwork customNetwork = new SupernodeNetwork(options);
            
            assertSame(customStore, customNetwork.getBlobStore());
            customNetwork.destroy();
        }
        
        @Test
        @DisplayName("should create with erasure coding enabled")
        void shouldCreateWithErasureCodingEnabled() {
            SupernodeNetwork.SupernodeNetworkOptions options = 
                SupernodeNetwork.SupernodeNetworkOptions.withErasure(4, 2);
            
            SupernodeNetwork erasureNetwork = new SupernodeNetwork(options);
            
            assertNotNull(erasureNetwork.getStorage());
            erasureNetwork.destroy();
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
            
            int port = network.listen(0).get(10, TimeUnit.SECONDS);
            
            assertTrue(listeningFired.get());
            assertTrue(port > 0);
            assertEquals(port, listeningPort.get());
            assertEquals(port, network.getPort());
        }
        
        @Test
        @DisplayName("should destroy cleanly")
        void shouldDestroyCleanly() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            AtomicBoolean destroyedFired = new AtomicBoolean(false);
            network.setOnDestroyed(v -> destroyedFired.set(true));
            
            network.destroy();
            
            assertTrue(network.isDestroyed());
            assertTrue(destroyedFired.get());
        }
        
        @Test
        @DisplayName("should destroy all subcomponents")
        void shouldDestroyAllSubcomponents() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            network.destroy();
            
            assertTrue(network.getBlobNetwork().isDestroyed());
            assertTrue(network.getDht().isDestroyed());
            assertTrue(network.getManifestDistributor().isDestroyed());
        }
    }
    
    @Nested
    @DisplayName("File Ingest")
    class FileIngestTests {
        
        @Test
        @DisplayName("should ingest file and return result")
        void shouldIngestFileAndReturnResult() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] fileData = "Hello, Supernode!".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            IngestResult result = network.ingestFile(fileData, "test.txt", masterKey);
            
            assertNotNull(result);
            assertNotNull(result.fileId());
            assertFalse(result.fileId().isEmpty());
            assertNotNull(result.chunkHashes());
            assertFalse(result.chunkHashes().isEmpty());
        }
        
        @Test
        @DisplayName("should emit file ingested event")
        void shouldEmitFileIngestedEvent() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            AtomicReference<SupernodeNetwork.FileIngestedEvent> eventRef = new AtomicReference<>();
            network.setOnFileIngested(eventRef::set);
            
            byte[] fileData = "Test data for event".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            network.ingestFile(fileData, "event-test.txt", masterKey);
            
            assertNotNull(eventRef.get());
            assertEquals("event-test.txt", eventRef.get().fileName());
            assertEquals(fileData.length, eventRef.get().size());
        }
        
        @Test
        @DisplayName("should ingest large file with multiple chunks")
        void shouldIngestLargeFileWithMultipleChunks() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] largeData = new byte[150_000];
            new SecureRandom().nextBytes(largeData);
            byte[] masterKey = generateMasterKey();
            
            IngestResult result = network.ingestFile(largeData, "large-file.bin", masterKey);
            
            assertNotNull(result);
            assertTrue(result.chunkHashes().size() > 1, 
                "Large file should produce multiple chunks");
        }
    }
    
    @Nested
    @DisplayName("File Retrieve")
    class FileRetrieveTests {
        
        @Test
        @DisplayName("should retrieve ingested file")
        void shouldRetrieveIngestedFile() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] originalData = "Content to retrieve".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            IngestResult ingestResult = network.ingestFile(originalData, "retrieve-test.txt", masterKey);
            
            RetrieveResult retrieveResult = network.retrieveFile(ingestResult.fileId(), masterKey);
            
            assertNotNull(retrieveResult);
            assertNotNull(retrieveResult.data());
            assertArrayEquals(originalData, retrieveResult.data());
        }
        
        @Test
        @DisplayName("should emit file retrieved event")
        void shouldEmitFileRetrievedEvent() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            AtomicReference<SupernodeNetwork.FileRetrievedEvent> eventRef = new AtomicReference<>();
            network.setOnFileRetrieved(eventRef::set);
            
            byte[] fileData = "Event data".getBytes(StandardCharsets.UTF_8);
            byte[] masterKey = generateMasterKey();
            
            IngestResult ingestResult = network.ingestFile(fileData, "retrieved-event.txt", masterKey);
            network.retrieveFile(ingestResult.fileId(), masterKey);
            
            assertNotNull(eventRef.get());
            assertEquals("retrieved-event.txt", eventRef.get().fileName());
        }
        
        @Test
        @DisplayName("should throw exception for non-existent file")
        void shouldThrowExceptionForNonExistentFile() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            byte[] masterKey = generateMasterKey();
            
            assertThrows(Exception.class, () -> 
                network.retrieveFile("non-existent-file-id", masterKey));
        }
    }
    
    @Nested
    @DisplayName("Peer Management")
    class PeerManagementTests {
        
        @Test
        @DisplayName("should start with no peers")
        void shouldStartWithNoPeers() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            assertTrue(network.getPeers().isEmpty());
        }
        
        @Test
        @DisplayName("should fail to connect to invalid address")
        void shouldFailToConnectToInvalidAddress() throws Exception {
            network.listen(0).get(10, TimeUnit.SECONDS);
            
            assertThrows(Exception.class, () -> 
                network.connect("ws://invalid-host:9999").get(5, TimeUnit.SECONDS)
            );
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
                new SupernodeNetwork.SupernodeNetworkOptions();
            
            assertNull(options.blobStore());
            assertNull(options.peerId());
            assertNull(options.bootstrap());
            assertEquals(50, options.maxConnections());
            assertFalse(options.enableErasure());
            assertEquals(4, options.dataShards());
            assertEquals(2, options.parityShards());
        }
        
        @Test
        @DisplayName("should create erasure options via factory method")
        void shouldCreateErasureOptionsViaFactoryMethod() {
            SupernodeNetwork.SupernodeNetworkOptions options = 
                SupernodeNetwork.SupernodeNetworkOptions.withErasure(6, 3);
            
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
