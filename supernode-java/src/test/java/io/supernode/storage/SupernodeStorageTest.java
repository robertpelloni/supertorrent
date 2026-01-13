package io.supernode.storage;

import io.supernode.storage.isoforge.SizePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SupernodeStorage")
class SupernodeStorageTest {
    
    private InMemoryBlobStore blobStore;
    private byte[] masterKey;
    
    @BeforeEach
    void setUp() {
        blobStore = new InMemoryBlobStore();
        masterKey = new byte[32];
        Arrays.fill(masterKey, (byte) 0x42);
    }
    
    @Nested
    @DisplayName("Basic Ingest/Retrieve")
    class BasicOperationsTests {
        
        @Test
        @DisplayName("should ingest and retrieve small file")
        void ingestAndRetrieveSmallFile() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = "Hello, Supernode!".getBytes(StandardCharsets.UTF_8);
            String fileName = "hello.txt";
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, fileName, masterKey);
            
            assertNotNull(ingestResult.fileId());
            assertTrue(ingestResult.fileId().startsWith("sha256:"));
            assertFalse(ingestResult.chunkHashes().isEmpty());
            assertNotNull(ingestResult.encryptedManifest());
            
            SupernodeStorage.RetrieveResult retrieveResult = storage.retrieve(ingestResult.fileId(), masterKey);
            
            assertEquals(fileName, retrieveResult.fileName());
            assertEquals(content.length, retrieveResult.fileSize());
            assertArrayEquals(content, retrieveResult.data());
        }
        
        @Test
        @DisplayName("should ingest and retrieve large file (multi-chunk)")
        void ingestAndRetrieveLargeFile() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = new byte[3 * 1024 * 1024];
            new SecureRandom().nextBytes(content);
            String fileName = "large-file.bin";
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, fileName, masterKey);
            
            assertTrue(ingestResult.chunkHashes().size() >= 3, "Should have at least 3 chunks for 3MB");
            
            SupernodeStorage.RetrieveResult retrieveResult = storage.retrieve(ingestResult.fileId(), masterKey);
            
            assertEquals(fileName, retrieveResult.fileName());
            assertArrayEquals(content, retrieveResult.data());
        }
        
        @Test
        @DisplayName("should handle empty file")
        void handleEmptyFile() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = new byte[0];
            String fileName = "empty.txt";
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, fileName, masterKey);
            SupernodeStorage.RetrieveResult retrieveResult = storage.retrieve(ingestResult.fileId(), masterKey);
            
            assertEquals(0, retrieveResult.data().length);
        }
        
        @Test
        @DisplayName("should handle file exactly chunk-sized")
        void handleExactChunkSize() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = new byte[SupernodeStorage.CHUNK_SIZE];
            new SecureRandom().nextBytes(content);
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, "exact.bin", masterKey);
            
            assertEquals(1, ingestResult.chunkHashes().size());
            
            SupernodeStorage.RetrieveResult retrieveResult = storage.retrieve(ingestResult.fileId(), masterKey);
            assertArrayEquals(content, retrieveResult.data());
        }
    }
    
    @Nested
    @DisplayName("Erasure Coding")
    class ErasureCodingTests {
        
        @Test
        @DisplayName("should ingest with erasure coding enabled")
        void ingestWithErasure() {
            SupernodeStorage.StorageOptions options = SupernodeStorage.StorageOptions.withErasure(4, 2);
            SupernodeStorage storage = new SupernodeStorage(blobStore, options);
            
            byte[] content = new byte[10000];
            new SecureRandom().nextBytes(content);
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, "erasure-test.bin", masterKey);
            
            assertTrue(ingestResult.chunkHashes().size() >= 6, "Should have shards (4 data + 2 parity)");
            
            SupernodeStorage.RetrieveResult retrieveResult = storage.retrieve(ingestResult.fileId(), masterKey);
            assertArrayEquals(content, retrieveResult.data());
        }
        
        @Test
        @DisplayName("stats should reflect erasure configuration")
        void statsReflectErasure() {
            SupernodeStorage.StorageOptions options = SupernodeStorage.StorageOptions.withErasure(4, 2);
            SupernodeStorage storage = new SupernodeStorage(blobStore, options);
            
            SupernodeStorage.StorageStats stats = storage.stats();
            
            assertNotNull(stats.erasure());
            assertEquals(4, stats.erasure().dataShards());
            assertEquals(2, stats.erasure().parityShards());
            assertEquals(6, stats.erasure().totalShards());
        }
    }
    
    @Nested
    @DisplayName("Events")
    class EventTests {
        
        @Test
        @DisplayName("should emit chunk ingested events")
        void emitChunkIngestedEvents() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            AtomicInteger chunkCount = new AtomicInteger(0);
            storage.setOnChunkIngested(event -> {
                assertNotNull(event.fileId());
                assertNotNull(event.chunkHash());
                assertTrue(event.progress() >= 0 && event.progress() <= 1);
                chunkCount.incrementAndGet();
            });
            
            byte[] content = new byte[2 * SupernodeStorage.CHUNK_SIZE + 1000];
            new SecureRandom().nextBytes(content);
            
            storage.ingest(content, "chunked.bin", masterKey);
            
            assertEquals(3, chunkCount.get());
        }
        
        @Test
        @DisplayName("should emit file ingested event")
        void emitFileIngestedEvent() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            AtomicInteger eventCount = new AtomicInteger(0);
            storage.setOnFileIngested(event -> {
                assertEquals("test.txt", event.fileName());
                assertTrue(event.size() > 0);
                assertTrue(event.chunkCount() > 0);
                eventCount.incrementAndGet();
            });
            
            storage.ingest("Test content".getBytes(), "test.txt", masterKey);
            
            assertEquals(1, eventCount.get());
        }
        
        @Test
        @DisplayName("should emit file retrieved event")
        void emitFileRetrievedEvent() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = "Retrieve test".getBytes();
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, "retrieve.txt", masterKey);
            
            AtomicInteger eventCount = new AtomicInteger(0);
            storage.setOnFileRetrieved(event -> {
                assertEquals("retrieve.txt", event.fileName());
                assertEquals(content.length, event.size());
                eventCount.incrementAndGet();
            });
            
            storage.retrieve(ingestResult.fileId(), masterKey);
            
            assertEquals(1, eventCount.get());
        }
    }
    
    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {
        
        @Test
        @DisplayName("should track blob count")
        void trackBlobCount() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            SupernodeStorage.StorageStats initialStats = storage.stats();
            assertEquals(0, initialStats.blobCount());
            
            storage.ingest("File 1".getBytes(), "file1.txt", masterKey);
            storage.ingest("File 2".getBytes(), "file2.txt", masterKey);
            
            SupernodeStorage.StorageStats finalStats = storage.stats();
            assertEquals(2, finalStats.blobCount());
        }
        
        @Test
        @DisplayName("should track manifest count")
        void trackManifestCount() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            SupernodeStorage.StorageStats initialStats = storage.stats();
            assertEquals(0, initialStats.manifestCount());
            
            storage.ingest("File 1".getBytes(), "file1.txt", masterKey);
            storage.ingest("File 2".getBytes(), "file2.txt", masterKey);
            
            SupernodeStorage.StorageStats finalStats = storage.stats();
            assertEquals(2, finalStats.manifestCount());
        }
        
        @Test
        @DisplayName("should report ISO size preset")
        void reportISOSizePreset() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            SupernodeStorage.StorageStats stats = storage.stats();
            assertEquals("nano", stats.isoSize());
        }
    }
    
    @Nested
    @DisplayName("Manifest Operations")
    class ManifestOperationsTests {
        
        @Test
        @DisplayName("should store and retrieve manifest")
        void storeAndRetrieveManifest() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            SupernodeStorage.IngestResult ingestResult = storage.ingest("Test".getBytes(), "test.txt", masterKey);
            
            assertTrue(storage.getManifest(ingestResult.fileId()).isPresent());
            assertArrayEquals(ingestResult.encryptedManifest(), 
                storage.getManifest(ingestResult.fileId()).get());
        }
        
        @Test
        @DisplayName("should return empty for unknown manifest")
        void emptyForUnknownManifest() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            assertTrue(storage.getManifest("sha256:unknown").isEmpty());
        }
        
        @Test
        @DisplayName("should allow external manifest storage")
        void allowExternalManifestStorage() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] fakeManifest = "fake-manifest-data".getBytes();
            storage.storeManifest("external-file-id", fakeManifest);
            
            assertTrue(storage.getManifest("external-file-id").isPresent());
            assertArrayEquals(fakeManifest, storage.getManifest("external-file-id").get());
        }
    }
    
    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("should fail retrieve with wrong master key")
        void failRetrieveWithWrongKey() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = "Secret data".getBytes();
            SupernodeStorage.IngestResult ingestResult = storage.ingest(content, "secret.txt", masterKey);
            
            byte[] wrongKey = new byte[32];
            Arrays.fill(wrongKey, (byte) 0xFF);
            
            assertThrows(Exception.class, () -> {
                storage.retrieve(ingestResult.fileId(), wrongKey);
            });
        }
        
        @Test
        @DisplayName("should fail retrieve for unknown file ID")
        void failRetrieveUnknownFileId() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            assertThrows(IllegalArgumentException.class, () -> {
                storage.retrieve("sha256:unknown", masterKey);
            });
        }
    }
    
    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {
        
        @Test
        @DisplayName("same content should produce same file ID")
        void sameContentSameFileId() {
            SupernodeStorage storage = new SupernodeStorage(blobStore);
            
            byte[] content = "Deterministic content".getBytes();
            
            SupernodeStorage.IngestResult result1 = storage.ingest(content.clone(), "file1.txt", masterKey);
            
            blobStore.clear();
            
            SupernodeStorage.IngestResult result2 = storage.ingest(content.clone(), "file2.txt", masterKey);
            
            assertEquals(result1.fileId(), result2.fileId(), "Same content should produce same file ID");
        }
    }
}
