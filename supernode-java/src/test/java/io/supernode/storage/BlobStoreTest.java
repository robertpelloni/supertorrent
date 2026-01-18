package io.supernode.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BlobStore Enhanced API")
class BlobStoreTest {

    private InMemoryBlobStore blobStore;

    @BeforeEach
    void setUp() {
        blobStore = new InMemoryBlobStore();
    }

    @Test
    @DisplayName("should support async put and get")
    void asyncPutAndGet() throws Exception {
        String hash = "abc";
        byte[] data = "async data".getBytes();

        CompletableFuture<Void> putFuture = blobStore.putAsync(hash, data);
        putFuture.get(5, TimeUnit.SECONDS);

        assertTrue(blobStore.has(hash));

        CompletableFuture<Optional<byte[]>> getFuture = blobStore.getAsync(hash);
        Optional<byte[]> result = getFuture.get(5, TimeUnit.SECONDS);

        assertTrue(result.isPresent());
        assertArrayEquals(data, result.get());
    }

    @Test
    @DisplayName("should support streaming put and get")
    void streamingOps() throws IOException {
        String hash = "stream-hash";
        byte[] data = new byte[1024];
        Arrays.fill(data, (byte) 0xAA);
        ByteArrayInputStream input = new ByteArrayInputStream(data);

        blobStore.putStream(hash, input, data.length);
        assertTrue(blobStore.has(hash));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean found = blobStore.getToStream(hash, output);

        assertTrue(found);
        assertArrayEquals(data, output.toByteArray());
    }

    @Test
    @DisplayName("should support batch operations")
    void batchOperations() throws Exception {
        List<BlobStore.BlobEntry> entries = List.of(
            new BlobStore.BlobEntry("h1", "data1".getBytes()),
            new BlobStore.BlobEntry("h2", "data2".getBytes()),
            new BlobStore.BlobEntry("h3", "data3".getBytes())
        );

        BlobStore.BatchResult putResult = blobStore.putBatch(entries).get(5, TimeUnit.SECONDS);
        assertEquals(3, putResult.successCount());
        assertEquals(0, putResult.failedCount());

        List<String> hashes = List.of("h1", "h2", "h3", "missing");
        List<String> existing = blobStore.hasBatch(hashes).get(5, TimeUnit.SECONDS);
        assertEquals(3, existing.size());
        assertTrue(existing.containsAll(List.of("h1", "h2", "h3")));

        List<BlobStore.BlobEntry> retrieved = blobStore.getBatch(hashes).get(5, TimeUnit.SECONDS);
        assertEquals(3, retrieved.size());
    }

    @Test
    @DisplayName("should support chunking operations")
    void chunkingOperations() throws Exception {
        byte[] largeData = new byte[2 * 1024 * 1024 + 500]; // ~2MB
        Arrays.fill(largeData, (byte) 0xBB);
        
        AtomicInteger progressCalls = new AtomicInteger(0);
        BlobStore.ChunkResult chunkResult = blobStore.putChunked(largeData, progress -> {
            progressCalls.incrementAndGet();
        }).get(10, TimeUnit.SECONDS);

        assertTrue(chunkResult.chunkCount() >= 3); // 1MB chunks
        assertTrue(progressCalls.get() > 0);

        byte[] reassembled = blobStore.getChunked(chunkResult.chunkHashes(), null).get(10, TimeUnit.SECONDS);
        assertArrayEquals(largeData, reassembled);
    }

    @Test
    @DisplayName("should support verification")
    void verification() throws Exception {
        String hash = blobStore.computeHash("verify-me".getBytes());
        blobStore.put(hash, "verify-me".getBytes());

        assertTrue(blobStore.verify(hash));

        BlobStore.VerificationResult result = blobStore.verifyAll(null).get(5, TimeUnit.SECONDS);
        assertTrue(result.isHealthy());
        assertEquals(1, result.validCount());
    }

    @Test
    @DisplayName("should support caching layer")
    void cachingSupport() {
        AtomicBoolean cacheUsed = new AtomicBoolean(false);
        BlobStore.BlobCache mockCache = new BlobStore.BlobCache() {
            private final java.util.Map<String, byte[]> data = new java.util.HashMap<>();
            @Override public Optional<byte[]> get(String h) { cacheUsed.set(true); return Optional.ofNullable(data.get(h)); }
            @Override public void put(String h, byte[] d) { data.put(h, d); }
            @Override public boolean has(String h) { return data.containsKey(h); }
            @Override public void invalidate(String h) { data.remove(h); }
            @Override public void clear() { data.clear(); }
            @Override public BlobStore.CacheStats stats() { return BlobStore.CacheStats.empty(); }
        };

        blobStore.setCache(mockCache);
        blobStore.put("cached", "data".getBytes());
        
        Optional<byte[]> result = blobStore.get("cached");
        assertTrue(result.isPresent());
        assertTrue(cacheUsed.get());
    }
}
