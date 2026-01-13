package io.supernode.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryBlobStore implements BlobStore {
    
    private final Map<String, BlobData> blobs = new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private volatile BlobCache cache;
    private volatile ChunkingStrategy chunkingStrategy = ChunkingStrategy.defaults();
    private volatile BlobStoreOptions options = BlobStoreOptions.defaults();
    private volatile Instant lastModified = Instant.now();
    private volatile Instant lastAccessed = Instant.now();
    
    @Override
    public void put(String hash, byte[] data) {
        blobs.put(hash, new BlobData(data.clone(), Instant.now()));
        lastModified = Instant.now();
        
        if (cache != null) {
            cache.put(hash, data);
        }
    }
    
    @Override
    public Optional<byte[]> get(String hash) {
        lastAccessed = Instant.now();
        
        if (cache != null) {
            Optional<byte[]> cached = cache.get(hash);
            if (cached.isPresent()) {
                cacheHits.incrementAndGet();
                return cached;
            }
            cacheMisses.incrementAndGet();
        }
        
        BlobData data = blobs.get(hash);
        if (data != null) {
            byte[] result = data.data.clone();
            if (cache != null) {
                cache.put(hash, result);
            }
            return Optional.of(result);
        }
        return Optional.empty();
    }
    
    @Override
    public boolean has(String hash) {
        if (cache != null && cache.has(hash)) {
            return true;
        }
        return blobs.containsKey(hash);
    }
    
    @Override
    public boolean delete(String hash) {
        if (cache != null) {
            cache.invalidate(hash);
        }
        boolean removed = blobs.remove(hash) != null;
        if (removed) {
            lastModified = Instant.now();
        }
        return removed;
    }
    
    @Override
    public BlobStoreStats stats() {
        long totalBytes = blobs.values().stream()
            .mapToLong(b -> b.data.length)
            .sum();
        
        int cachedCount = 0;
        long cacheBytes = 0;
        if (cache != null) {
            CacheStats cs = cache.stats();
            cachedCount = cs.cachedCount();
            cacheBytes = cs.cachedBytes();
        }
        
        return new BlobStoreStats(
            blobs.size(), 
            totalBytes,
            Long.MAX_VALUE,
            totalBytes,
            cachedCount,
            cacheBytes,
            lastModified,
            lastAccessed
        );
    }
    
    @Override
    public Optional<InputStream> getStream(String hash) {
        return get(hash).map(ByteArrayInputStream::new);
    }
    
    @Override
    public Optional<BlobMetadata> getMetadata(String hash) {
        BlobData data = blobs.get(hash);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(new BlobMetadata(
            hash,
            data.data.length,
            data.created,
            lastAccessed,
            Optional.empty(),
            Optional.of("memory")
        ));
    }
    
    @Override
    public List<String> listHashes() {
        return new ArrayList<>(blobs.keySet());
    }
    
    @Override
    public ChunkingStrategy getChunkingStrategy() {
        return chunkingStrategy;
    }
    
    @Override
    public void setChunkingStrategy(ChunkingStrategy strategy) {
        this.chunkingStrategy = strategy;
    }
    
    @Override
    public Optional<BlobCache> getCache() {
        return Optional.ofNullable(cache);
    }
    
    @Override
    public void setCache(BlobCache cache) {
        this.cache = cache;
    }
    
    @Override
    public BlobStoreOptions getOptions() {
        return options;
    }
    
    @Override
    public void configure(BlobStoreOptions options) {
        this.options = options;
    }
    
    public void clear() {
        blobs.clear();
        if (cache != null) {
            cache.clear();
        }
        lastModified = Instant.now();
    }
    
    public long getCacheHits() {
        return cacheHits.get();
    }
    
    public long getCacheMisses() {
        return cacheMisses.get();
    }
    
    private record BlobData(byte[] data, Instant created) {}
}
