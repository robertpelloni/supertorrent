package io.supernode.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Storage Layer Integration")
class StorageIntegrationTest {

    private InMemoryBlobStore blobStore;
    private SupernodeStorage storage;
    private byte[] masterKey;

    @BeforeEach
    void setUp() {
        blobStore = new InMemoryBlobStore();
        storage = new SupernodeStorage(blobStore, SupernodeStorage.StorageOptions.builder()
            .enableErasure(true)
            .dataShards(4)
            .parityShards(2)
            .build());
        masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
    }

    @Test
    @DisplayName("should perform full async ingest and retrieve cycle with progress")
    void fullAsyncCycleWithProgress() throws Exception {
        byte[] content = new byte[5 * 1024 * 1024]; // 5MB
        new SecureRandom().nextBytes(content);
        String fileName = "integration-test.bin";

        AtomicInteger progressCalls = new AtomicInteger(0);
        AtomicReference<Double> lastPercent = new AtomicReference<>(0.0);

        CompletableFuture<SupernodeStorage.IngestResult> ingestFuture = storage.ingestAsync(content, fileName, masterKey, p -> {
            progressCalls.incrementAndGet();
            lastPercent.set(p.percentComplete());
        });

        SupernodeStorage.IngestResult ingestResult = ingestFuture.get(20, TimeUnit.SECONDS);
        assertNotNull(ingestResult.fileId());
        assertTrue(progressCalls.get() >= 5);
        assertEquals(100.0, lastPercent.get(), 0.001);

        // Verify blob count (4 data + 2 parity per 1MB chunk = 6 * 5 = 30 blobs)
        SupernodeStorage.StorageStats stats = storage.stats();
        assertEquals(30, stats.blobCount());

        AtomicInteger retrieveProgressCalls = new AtomicInteger(0);
        CompletableFuture<SupernodeStorage.RetrieveResult> retrieveFuture = storage.retrieveAsync(ingestResult.fileId(), masterKey, p -> {
            retrieveProgressCalls.incrementAndGet();
        });

        SupernodeStorage.RetrieveResult retrieveResult = retrieveFuture.get(20, TimeUnit.SECONDS);
        assertArrayEquals(content, retrieveResult.data());
        assertEquals(fileName, retrieveResult.fileName());
        assertTrue(retrieveProgressCalls.get() >= 5);
    }

    @Test
    @DisplayName("should recover from partial shard loss using erasure coding")
    void erasureCodingRecovery() throws Exception {
        byte[] content = "Critical data that must survive loss".getBytes(StandardCharsets.UTF_8);
        SupernodeStorage.IngestResult result = storage.ingest(content, "survivor.txt", masterKey);

        // This should have 6 shards (4 data, 2 parity)
        List<String> shards = result.chunkHashes();
        assertEquals(6, shards.size());

        // Delete 2 shards (the max allowed for 4+2 config)
        blobStore.delete(shards.get(0));
        blobStore.delete(shards.get(1));

        assertFalse(blobStore.has(shards.get(0)));
        assertFalse(blobStore.has(shards.get(1)));

        // Retrieve should still succeed
        SupernodeStorage.RetrieveResult retrieved = storage.retrieve(result.fileId(), masterKey);
        assertArrayEquals(content, retrieved.data());
    }

    @Test
    @DisplayName("should fail recovery when too many shards are lost")
    void erasureCodingFailure() throws Exception {
        byte[] content = "Data that will be lost".getBytes(StandardCharsets.UTF_8);
        SupernodeStorage.IngestResult result = storage.ingest(content, "doomed.txt", masterKey);

        List<String> shards = result.chunkHashes();
        
        // Delete 3 shards (more than 2 allowed)
        blobStore.delete(shards.get(0));
        blobStore.delete(shards.get(1));
        blobStore.delete(shards.get(2));

        assertThrows(Exception.class, () -> {
            storage.retrieve(result.fileId(), masterKey);
        });
    }

    @Test
    @DisplayName("should track operations and cleanup correctly")
    void operationTracking() throws Exception {
        byte[] content = new byte[1024 * 1024];
        storage.ingestAsync(content, "test.bin", masterKey).get(5, TimeUnit.SECONDS);

        SupernodeStorage.StorageStats stats = storage.stats();
        assertTrue(stats.totalOperations() >= 1);
        
        // Active operations should be 0 since it completed
        assertEquals(0, storage.getActiveOperations().size());
        
        storage.cleanupCompletedOperations(java.time.Duration.ZERO);
        // This is hard to test exactly without sleeping, but we check it doesn't crash
    }
}
