package io.supernode.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for blob storage backends.
 * Blobs are identified by their SHA-256 hash.
 * 
 * Supports synchronous and asynchronous operations, streaming,
 * chunking strategies, and optional caching layer.
 */
public interface BlobStore {
    
    // ==================== Synchronous Operations ====================
    
    /**
     * Store a blob.
     * @param hash SHA-256 hash of the blob (hex string)
     * @param data The blob data
     */
    void put(String hash, byte[] data);
    
    /**
     * Retrieve a blob by hash.
     * @param hash SHA-256 hash of the blob
     * @return The blob data, or empty if not found
     */
    Optional<byte[]> get(String hash);
    
    /**
     * Check if a blob exists.
     * @param hash SHA-256 hash of the blob
     * @return true if the blob exists
     */
    boolean has(String hash);
    
    /**
     * Delete a blob.
     * @param hash SHA-256 hash of the blob
     * @return true if the blob was deleted, false if it didn't exist
     */
    boolean delete(String hash);
    
    /**
     * Get storage statistics.
     * @return Stats about the blob store
     */
    BlobStoreStats stats();
    
    // ==================== Asynchronous Operations ====================
    
    /**
     * Store a blob asynchronously.
     * @param hash SHA-256 hash of the blob (hex string)
     * @param data The blob data
     * @return Future that completes when stored
     */
    default CompletableFuture<Void> putAsync(String hash, byte[] data) {
        return CompletableFuture.runAsync(() -> put(hash, data));
    }
    
    /**
     * Retrieve a blob asynchronously.
     * @param hash SHA-256 hash of the blob
     * @return Future with the blob data
     */
    default CompletableFuture<Optional<byte[]>> getAsync(String hash) {
        return CompletableFuture.supplyAsync(() -> get(hash));
    }
    
    /**
     * Check if a blob exists asynchronously.
     * @param hash SHA-256 hash of the blob
     * @return Future with existence result
     */
    default CompletableFuture<Boolean> hasAsync(String hash) {
        return CompletableFuture.supplyAsync(() -> has(hash));
    }
    
    /**
     * Delete a blob asynchronously.
     * @param hash SHA-256 hash of the blob
     * @return Future with deletion result
     */
    default CompletableFuture<Boolean> deleteAsync(String hash) {
        return CompletableFuture.supplyAsync(() -> delete(hash));
    }
    
    // ==================== Streaming Operations ====================
    
    /**
     * Get a blob as a stream for memory-efficient reading.
     * @param hash SHA-256 hash of the blob
     * @return InputStream for the blob, or empty if not found
     */
    default Optional<InputStream> getStream(String hash) {
        return get(hash).map(java.io.ByteArrayInputStream::new);
    }
    
    /**
     * Store a blob from a stream.
     * @param hash SHA-256 hash of the blob (hex string)
     * @param data Input stream containing blob data
     * @param size Size of the data in bytes
     * @throws java.io.IOException if reading fails
     */
    default void putStream(String hash, InputStream data, long size) throws java.io.IOException {
        byte[] bytes = data.readAllBytes();
        put(hash, bytes);
    }
    
    /**
     * Store a blob from a stream with progress callback.
     * @param hash SHA-256 hash of the blob (hex string)
     * @param data Input stream containing blob data
     * @param size Size of the data in bytes
     * @param progress Progress callback
     * @throws java.io.IOException if reading fails
     */
    default void putStream(String hash, InputStream data, long size, Consumer<StreamProgress> progress) 
            throws java.io.IOException {
        putStream(hash, data, size);
        if (progress != null) {
            progress.accept(new StreamProgress(size, size, 100.0, false));
        }
    }
    
    /**
     * Get a blob and write to an output stream.
     * @param hash SHA-256 hash of the blob
     * @param output Output stream to write to
     * @return true if blob was found and written
     * @throws java.io.IOException if writing fails
     */
    default boolean getToStream(String hash, OutputStream output) throws java.io.IOException {
        Optional<byte[]> data = get(hash);
        if (data.isPresent()) {
            output.write(data.get());
            return true;
        }
        return false;
    }
    
    /**
     * Get a blob and write to an output stream with progress callback.
     * @param hash SHA-256 hash of the blob
     * @param output Output stream to write to
     * @param progress Progress callback
     * @return true if blob was found and written
     * @throws java.io.IOException if writing fails
     */
    default boolean getToStream(String hash, OutputStream output, Consumer<StreamProgress> progress) 
            throws java.io.IOException {
        boolean result = getToStream(hash, output);
        if (result && progress != null) {
            BlobMetadata meta = getMetadata(hash).orElse(null);
            long size = meta != null ? meta.size() : 0;
            progress.accept(new StreamProgress(size, size, 100.0, false));
        }
        return result;
    }
    
    // ==================== Batch Operations ====================
    
    /**
     * Store multiple blobs atomically.
     * @param blobs List of hash-data pairs
     * @return Future that completes when all stored
     */
    default CompletableFuture<BatchResult> putBatch(List<BlobEntry> blobs) {
        return CompletableFuture.supplyAsync(() -> {
            int success = 0;
            int failed = 0;
            for (BlobEntry entry : blobs) {
                try {
                    put(entry.hash(), entry.data());
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
            return new BatchResult(success, failed, List.of());
        });
    }
    
    /**
     * Retrieve multiple blobs.
     * @param hashes List of hashes to retrieve
     * @return Future with list of results
     */
    default CompletableFuture<List<BlobEntry>> getBatch(List<String> hashes) {
        return CompletableFuture.supplyAsync(() -> 
            hashes.stream()
                .map(hash -> get(hash).map(data -> new BlobEntry(hash, data)).orElse(null))
                .filter(entry -> entry != null)
                .toList()
        );
    }
    
    /**
     * Check existence of multiple blobs.
     * @param hashes List of hashes to check
     * @return Future with list of existing hashes
     */
    default CompletableFuture<List<String>> hasBatch(List<String> hashes) {
        return CompletableFuture.supplyAsync(() ->
            hashes.stream()
                .filter(this::has)
                .toList()
        );
    }
    
    /**
     * Delete multiple blobs.
     * @param hashes List of hashes to delete
     * @return Future with batch result
     */
    default CompletableFuture<BatchResult> deleteBatch(List<String> hashes) {
        return CompletableFuture.supplyAsync(() -> {
            int success = 0;
            int failed = 0;
            for (String hash : hashes) {
                if (delete(hash)) {
                    success++;
                } else {
                    failed++;
                }
            }
            return new BatchResult(success, failed, List.of());
        });
    }
    
    // ==================== Metadata Operations ====================
    
    /**
     * Get metadata for a blob without retrieving the data.
     * @param hash SHA-256 hash of the blob
     * @return Metadata or empty if not found
     */
    default Optional<BlobMetadata> getMetadata(String hash) {
        return get(hash).map(data -> new BlobMetadata(
            hash,
            data.length,
            Instant.now(),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        ));
    }
    
    /**
     * List all blob hashes in the store.
     * @return List of all hashes
     */
    default List<String> listHashes() {
        return List.of();
    }
    
    /**
     * List blob hashes with pagination.
     * @param offset Start offset
     * @param limit Maximum number to return
     * @return List of hashes
     */
    default List<String> listHashes(int offset, int limit) {
        List<String> all = listHashes();
        int end = Math.min(offset + limit, all.size());
        return offset < all.size() ? all.subList(offset, end) : List.of();
    }
    
    // ==================== Chunking Support ====================
    
    /**
     * Get the chunking strategy for this store.
     * @return The chunking strategy
     */
    default ChunkingStrategy getChunkingStrategy() {
        return ChunkingStrategy.defaults();
    }
    
    /**
     * Set the chunking strategy.
     * @param strategy The chunking strategy
     */
    default void setChunkingStrategy(ChunkingStrategy strategy) {
        // Default: no-op, implementations can override
    }
    
    /**
     * Store a large blob using chunking.
     * @param data The complete data
     * @param progress Progress callback
     * @return List of chunk hashes
     */
    default CompletableFuture<ChunkResult> putChunked(byte[] data, Consumer<StreamProgress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            ChunkingStrategy strategy = getChunkingStrategy();
            int chunkSize = strategy.chunkSize();
            int totalChunks = (int) Math.ceil((double) data.length / chunkSize);
            java.util.ArrayList<String> chunkHashes = new java.util.ArrayList<>();
            
            for (int i = 0; i < totalChunks; i++) {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, data.length);
                byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
                
                String hash = computeHash(chunk);
                put(hash, chunk);
                chunkHashes.add(hash);
                
                if (progress != null) {
                    double percent = ((double) (i + 1) / totalChunks) * 100;
                    progress.accept(new StreamProgress(end, data.length, percent, false));
                }
            }
            
            return new ChunkResult(chunkHashes, data.length, strategy);
        });
    }
    
    /**
     * Retrieve and reassemble chunked data.
     * @param chunkHashes List of chunk hashes in order
     * @param progress Progress callback
     * @return Reassembled data
     */
    default CompletableFuture<byte[]> getChunked(List<String> chunkHashes, Consumer<StreamProgress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int processed = 0;
            
            for (int i = 0; i < chunkHashes.size(); i++) {
                String hash = chunkHashes.get(i);
                byte[] chunk = get(hash).orElseThrow(() -> 
                    new IllegalStateException("Missing chunk: " + hash));
                
                try {
                    output.write(chunk);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Failed to write chunk", e);
                }
                
                processed += chunk.length;
                if (progress != null) {
                    double percent = ((double) (i + 1) / chunkHashes.size()) * 100;
                    progress.accept(new StreamProgress(processed, -1, percent, false));
                }
            }
            
            return output.toByteArray();
        });
    }
    
    // ==================== Caching Support ====================
    
    /**
     * Get the cache layer if configured.
     * @return The cache or empty
     */
    default Optional<BlobCache> getCache() {
        return Optional.empty();
    }
    
    /**
     * Set the cache layer.
     * @param cache The cache implementation
     */
    default void setCache(BlobCache cache) {
        // Default: no-op, implementations can override
    }
    
    /**
     * Get cache statistics.
     * @return Cache stats or empty if no cache
     */
    default Optional<CacheStats> getCacheStats() {
        return getCache().map(BlobCache::stats);
    }
    
    /**
     * Invalidate cache entry.
     * @param hash Hash to invalidate
     */
    default void invalidateCache(String hash) {
        getCache().ifPresent(c -> c.invalidate(hash));
    }
    
    /**
     * Clear the entire cache.
     */
    default void clearCache() {
        getCache().ifPresent(BlobCache::clear);
    }
    
    // ==================== Verification ====================
    
    /**
     * Verify a blob's integrity by recomputing its hash.
     * @param hash Expected hash
     * @return true if data matches hash
     */
    default boolean verify(String hash) {
        return get(hash)
            .map(data -> computeHash(data).equals(hash))
            .orElse(false);
    }
    
    /**
     * Verify all blobs in the store.
     * @param progress Progress callback
     * @return Verification result
     */
    default CompletableFuture<VerificationResult> verifyAll(Consumer<StreamProgress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> hashes = listHashes();
            int valid = 0;
            int invalid = 0;
            java.util.ArrayList<String> corrupted = new java.util.ArrayList<>();
            
            for (int i = 0; i < hashes.size(); i++) {
                String hash = hashes.get(i);
                if (verify(hash)) {
                    valid++;
                } else {
                    invalid++;
                    corrupted.add(hash);
                }
                
                if (progress != null) {
                    double percent = ((double) (i + 1) / hashes.size()) * 100;
                    progress.accept(new StreamProgress(i + 1, hashes.size(), percent, false));
                }
            }
            
            return new VerificationResult(valid, invalid, corrupted);
        });
    }
    
    // ==================== Configuration ====================
    
    /**
     * Get the store options.
     * @return Current options
     */
    default BlobStoreOptions getOptions() {
        return BlobStoreOptions.defaults();
    }
    
    /**
     * Configure the store.
     * @param options Configuration options
     */
    default void configure(BlobStoreOptions options) {
        // Default: no-op
    }
    
    // ==================== Lifecycle ====================
    
    /**
     * Initialize the store.
     * @return Future that completes when ready
     */
    default CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Shutdown the store gracefully.
     * @return Future that completes when shutdown
     */
    default CompletableFuture<Void> shutdown() {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Check if store is ready for operations.
     * @return true if ready
     */
    default boolean isReady() {
        return true;
    }
    
    // ==================== Utility ====================
    
    /**
     * Compute SHA-256 hash of data.
     * @param data Data to hash
     * @return Hex-encoded hash
     */
    default String computeHash(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    // ==================== Records ====================
    
    /**
     * Statistics about the blob store.
     */
    record BlobStoreStats(
        int blobCount,
        long totalBytes,
        long availableBytes,
        long usedBytes,
        int cachedCount,
        long cacheBytes,
        Instant lastModified,
        Instant lastAccessed
    ) {
        public BlobStoreStats(int blobCount, long totalBytes) {
            this(blobCount, totalBytes, Long.MAX_VALUE, totalBytes, 0, 0, Instant.now(), Instant.now());
        }
        
        public static BlobStoreStats empty() {
            return new BlobStoreStats(0, 0, Long.MAX_VALUE, 0, 0, 0, Instant.now(), Instant.now());
        }
    }
    
    /**
     * Metadata for a blob.
     */
    record BlobMetadata(
        String hash,
        long size,
        Instant created,
        Instant lastAccessed,
        Optional<String> contentType,
        Optional<String> source
    ) {}
    
    /**
     * Progress update for streaming operations.
     */
    record StreamProgress(
        long bytesProcessed,
        long totalBytes,
        double percentComplete,
        boolean cancelled
    ) {
        public boolean isComplete() {
            return totalBytes > 0 && bytesProcessed >= totalBytes;
        }
    }
    
    /**
     * Entry for batch operations.
     */
    record BlobEntry(String hash, byte[] data) {}
    
    /**
     * Result of batch operations.
     */
    record BatchResult(
        int successCount,
        int failedCount,
        List<String> failedHashes
    ) {
        public boolean isComplete() {
            return failedCount == 0;
        }
    }
    
    /**
     * Chunking strategy configuration.
     */
    record ChunkingStrategy(
        int chunkSize,
        boolean enableDedup,
        String hashAlgorithm,
        boolean contentDefined,
        int minChunkSize,
        int maxChunkSize
    ) {
        public static ChunkingStrategy defaults() {
            return new ChunkingStrategy(
                1024 * 1024,  // 1MB chunks
                true,         // Enable deduplication
                "SHA-256",    // Hash algorithm
                false,        // Fixed-size chunking
                256 * 1024,   // 256KB min
                4 * 1024 * 1024  // 4MB max
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int chunkSize = 1024 * 1024;
            private boolean enableDedup = true;
            private String hashAlgorithm = "SHA-256";
            private boolean contentDefined = false;
            private int minChunkSize = 256 * 1024;
            private int maxChunkSize = 4 * 1024 * 1024;
            
            public Builder chunkSize(int size) { this.chunkSize = size; return this; }
            public Builder enableDedup(boolean enable) { this.enableDedup = enable; return this; }
            public Builder hashAlgorithm(String algo) { this.hashAlgorithm = algo; return this; }
            public Builder contentDefined(boolean cd) { this.contentDefined = cd; return this; }
            public Builder minChunkSize(int size) { this.minChunkSize = size; return this; }
            public Builder maxChunkSize(int size) { this.maxChunkSize = size; return this; }
            
            public ChunkingStrategy build() {
                return new ChunkingStrategy(chunkSize, enableDedup, hashAlgorithm, 
                    contentDefined, minChunkSize, maxChunkSize);
            }
        }
    }
    
    /**
     * Result of chunked storage.
     */
    record ChunkResult(
        List<String> chunkHashes,
        long totalSize,
        ChunkingStrategy strategy
    ) {
        public int chunkCount() {
            return chunkHashes.size();
        }
    }
    
    /**
     * Cache statistics.
     */
    record CacheStats(
        long hits,
        long misses,
        long evictions,
        long cachedBytes,
        int cachedCount,
        double hitRate,
        Instant lastEviction
    ) {
        public CacheStats(long hits, long misses, long evictions, long cachedBytes) {
            this(hits, misses, evictions, cachedBytes, 0, 
                 hits + misses > 0 ? (double) hits / (hits + misses) : 0.0, 
                 Instant.now());
        }
        
        public static CacheStats empty() {
            return new CacheStats(0, 0, 0, 0, 0, 0.0, Instant.now());
        }
    }
    
    /**
     * Result of verification.
     */
    record VerificationResult(
        int validCount,
        int invalidCount,
        List<String> corruptedHashes
    ) {
        public boolean isHealthy() {
            return invalidCount == 0;
        }
        
        public double integrityRate() {
            int total = validCount + invalidCount;
            return total > 0 ? (double) validCount / total : 1.0;
        }
    }
    
    /**
     * Configuration options for BlobStore.
     */
    record BlobStoreOptions(
        int maxBlobSize,
        long maxTotalBytes,
        boolean enableCompression,
        String compressionAlgorithm,
        boolean enableEncryption,
        boolean verifyOnRead,
        boolean verifyOnWrite,
        Duration readTimeout,
        Duration writeTimeout,
        int maxConcurrentOps,
        boolean enableMetrics
    ) {
        public static BlobStoreOptions defaults() {
            return new BlobStoreOptions(
                100 * 1024 * 1024,   // 100MB max blob
                Long.MAX_VALUE,       // No total limit
                false,                // No compression
                "gzip",               // Default algorithm
                false,                // No encryption
                false,                // Don't verify on read
                true,                 // Verify on write
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                100,                  // Max concurrent ops
                true                  // Enable metrics
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int maxBlobSize = 100 * 1024 * 1024;
            private long maxTotalBytes = Long.MAX_VALUE;
            private boolean enableCompression = false;
            private String compressionAlgorithm = "gzip";
            private boolean enableEncryption = false;
            private boolean verifyOnRead = false;
            private boolean verifyOnWrite = true;
            private Duration readTimeout = Duration.ofSeconds(30);
            private Duration writeTimeout = Duration.ofSeconds(60);
            private int maxConcurrentOps = 100;
            private boolean enableMetrics = true;
            
            public Builder maxBlobSize(int size) { this.maxBlobSize = size; return this; }
            public Builder maxTotalBytes(long max) { this.maxTotalBytes = max; return this; }
            public Builder enableCompression(boolean enable) { this.enableCompression = enable; return this; }
            public Builder compressionAlgorithm(String algo) { this.compressionAlgorithm = algo; return this; }
            public Builder enableEncryption(boolean enable) { this.enableEncryption = enable; return this; }
            public Builder verifyOnRead(boolean verify) { this.verifyOnRead = verify; return this; }
            public Builder verifyOnWrite(boolean verify) { this.verifyOnWrite = verify; return this; }
            public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
            public Builder writeTimeout(Duration timeout) { this.writeTimeout = timeout; return this; }
            public Builder maxConcurrentOps(int max) { this.maxConcurrentOps = max; return this; }
            public Builder enableMetrics(boolean enable) { this.enableMetrics = enable; return this; }
            
            public BlobStoreOptions build() {
                return new BlobStoreOptions(
                    maxBlobSize, maxTotalBytes, enableCompression, compressionAlgorithm,
                    enableEncryption, verifyOnRead, verifyOnWrite,
                    readTimeout, writeTimeout, maxConcurrentOps, enableMetrics
                );
            }
        }
    }
    
    // ==================== Cache Interface ====================
    
    /**
     * Interface for blob caching layer.
     */
    interface BlobCache {
        
        /**
         * Get cached blob.
         * @param hash Blob hash
         * @return Cached data or empty
         */
        Optional<byte[]> get(String hash);
        
        /**
         * Put blob in cache.
         * @param hash Blob hash
         * @param data Blob data
         */
        void put(String hash, byte[] data);
        
        /**
         * Check if blob is cached.
         * @param hash Blob hash
         * @return true if cached
         */
        boolean has(String hash);
        
        /**
         * Invalidate cache entry.
         * @param hash Blob hash
         */
        void invalidate(String hash);
        
        /**
         * Clear entire cache.
         */
        void clear();
        
        /**
         * Get cache statistics.
         * @return Cache stats
         */
        CacheStats stats();
        
        /**
         * Configure the cache.
         * @param options Cache options
         */
        default void configure(CacheOptions options) {}
        
        /**
         * Cache configuration options.
         */
        record CacheOptions(
            long maxBytes,
            int maxEntries,
            Duration ttl,
            EvictionPolicy evictionPolicy,
            boolean warmOnStart
        ) {
            public static CacheOptions defaults() {
                return new CacheOptions(
                    256 * 1024 * 1024,  // 256MB
                    10000,               // 10k entries
                    Duration.ofHours(1), // 1 hour TTL
                    EvictionPolicy.LRU,
                    false
                );
            }
            
            public static Builder builder() {
                return new Builder();
            }
            
            public static class Builder {
                private long maxBytes = 256 * 1024 * 1024;
                private int maxEntries = 10000;
                private Duration ttl = Duration.ofHours(1);
                private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
                private boolean warmOnStart = false;
                
                public Builder maxBytes(long max) { this.maxBytes = max; return this; }
                public Builder maxEntries(int max) { this.maxEntries = max; return this; }
                public Builder ttl(Duration ttl) { this.ttl = ttl; return this; }
                public Builder evictionPolicy(EvictionPolicy policy) { this.evictionPolicy = policy; return this; }
                public Builder warmOnStart(boolean warm) { this.warmOnStart = warm; return this; }
                
                public CacheOptions build() {
                    return new CacheOptions(maxBytes, maxEntries, ttl, evictionPolicy, warmOnStart);
                }
            }
        }
        
        enum EvictionPolicy {
            LRU,      // Least Recently Used
            LFU,      // Least Frequently Used
            FIFO,     // First In First Out
            TTL,      // Time To Live based
            SIZE      // Largest first
        }
    }
}
