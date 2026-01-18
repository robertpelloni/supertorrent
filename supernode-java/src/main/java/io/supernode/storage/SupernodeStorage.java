package io.supernode.storage;

import io.supernode.storage.erasure.ErasureCoder;
import io.supernode.storage.isoforge.ISOForge;
import io.supernode.storage.isoforge.SizePreset;
import io.supernode.storage.mux.Manifest;
import io.supernode.storage.mux.Manifest.ErasureConfig;
import io.supernode.storage.mux.Manifest.Segment;
import io.supernode.storage.mux.Manifest.ShardInfo;
import io.supernode.storage.mux.MuxEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class SupernodeStorage {
    
    public static final int CHUNK_SIZE = 1024 * 1024;
    
    private final BlobStore blobStore;
    private final MuxEngine muxEngine;
    private final ISOForge isoForge;
    private final SizePreset isoSize;
    private final Map<String, byte[]> manifestStore;
    
    private final boolean enableErasure;
    private final ErasureCoder erasureCoder;
    private final StorageOptions options;
    
    private final ExecutorService executor;
    private final Map<String, OperationState> operations = new ConcurrentHashMap<>();
    private final AtomicLong operationCounter = new AtomicLong();
    
    private final AtomicLong totalBytesIngested = new AtomicLong();
    private final AtomicLong totalBytesRetrieved = new AtomicLong();
    private final AtomicLong totalFilesIngested = new AtomicLong();
    private final AtomicLong totalFilesRetrieved = new AtomicLong();
    
    private Consumer<ChunkIngestedEvent> onChunkIngested;
    private Consumer<FileIngestedEvent> onFileIngested;
    private Consumer<ErasureEncodedEvent> onErasureEncoded;
    private Consumer<ChunkRetrievedEvent> onChunkRetrieved;
    private Consumer<FileRetrievedEvent> onFileRetrieved;
    private Consumer<ErasureDecodedEvent> onErasureDecoded;
    
    public SupernodeStorage(BlobStore blobStore) {
        this(blobStore, StorageOptions.defaults());
    }
    
    public SupernodeStorage(BlobStore blobStore, StorageOptions options) {
        this.blobStore = blobStore;
        this.options = options;
        this.isoSize = options.isoSize;
        this.muxEngine = new MuxEngine(isoSize);
        this.isoForge = new ISOForge();
        this.manifestStore = new ConcurrentHashMap<>();
        
        this.enableErasure = options.enableErasure;
        if (enableErasure) {
            this.erasureCoder = new ErasureCoder(options.dataShards, options.parityShards);
        } else {
            this.erasureCoder = null;
        }
        
        this.executor = Executors.newFixedThreadPool(options.concurrency);
    }
    
    public IngestResult ingest(byte[] fileBuffer, String fileName, byte[] masterKey) {
        return ingest(fileBuffer, fileName, masterKey, null);
    }
    
    public IngestResult ingest(byte[] fileBuffer, String fileName, byte[] masterKey, Consumer<Progress> progress) {
        String operationId = generateOperationId();
        OperationState state = new OperationState(operationId, OperationType.INGEST, Instant.now());
        operations.put(operationId, state);
        
        try {
            String fileId = "sha256:" + sha256Hex(fileBuffer);
            List<Segment> segments = new ArrayList<>();
            List<String> chunkHashes = new ArrayList<>();
            
            int offset = 0;
            int chunkIndex = 0;
            int totalChunks = (int) Math.ceil((double) fileBuffer.length / CHUNK_SIZE);
            if (fileBuffer.length == 0) totalChunks = 1;
            
            do {
                if (state.cancelled) {
                    throw new CancellationException("Operation cancelled: " + operationId);
                }
                
                int end = Math.min(offset + CHUNK_SIZE, fileBuffer.length);
                byte[] chunk = Arrays.copyOfRange(fileBuffer, offset, end);
                
                byte[] chunkKey = generateEncryptionKey();
                byte[] isoSeed = generateISOSeed();
                
                MuxEngine.MuxResult muxResult = muxEngine.mux(chunk, chunkKey, isoSeed);
                
                Segment segment;
                if (enableErasure) {
                    segment = ingestWithErasure(muxResult.muxedData(), chunkKey, isoSeed, muxResult, chunk.length, chunkHashes);
                } else {
                    String chunkHash = sha256Hex(muxResult.muxedData());
                    blobStore.put(chunkHash, muxResult.muxedData());
                    chunkHashes.add(chunkHash);
                    
                    segment = new Segment(
                        chunkHash,
                        HexFormat.of().formatHex(chunkKey),
                        HexFormat.of().formatHex(isoSeed),
                        0,
                        muxResult.sectorCount(),
                        muxResult.encryptedSize(),
                        chunk.length,
                        null,
                        null,
                        null
                    );
                }
                
                segments.add(segment);
                offset = end;
                chunkIndex++;
                
                state.bytesProcessed = offset;
                state.progress = fileBuffer.length > 0 ? (double) offset / fileBuffer.length : 1.0;
                
                if (progress != null) {
                    progress.accept(new Progress(
                        operationId,
                        OperationType.INGEST,
                        offset,
                        fileBuffer.length,
                        state.progress * 100,
                        chunkIndex,
                        totalChunks,
                        fileId
                    ));
                }
                
                if (onChunkIngested != null) {
                    String hash = segment.chunkHash() != null ? segment.chunkHash() : segment.shards().get(0).hash();
                    onChunkIngested.accept(new ChunkIngestedEvent(fileId, hash, state.progress));
                }
            } while (offset < fileBuffer.length);
            
            Manifest manifest = Manifest.create(new Manifest.ManifestOptions(
                fileId,
                fileName,
                fileBuffer.length,
                segments.get(0).isoSeed(),
                isoSize.getBytes(),
                enableErasure ? new ErasureConfig(erasureCoder.getDataShards(), erasureCoder.getParityShards()) : null,
                segments
            ));
            
            byte[] manifestKey = Manifest.deriveManifestKey(masterKey, fileId);
            byte[] encryptedManifest = Manifest.encrypt(manifest, manifestKey);
            manifestStore.put(fileId, encryptedManifest);
            
            state.completed = true;
            state.completedAt = Instant.now();
            totalBytesIngested.addAndGet(fileBuffer.length);
            totalFilesIngested.incrementAndGet();
            
            if (onFileIngested != null) {
                onFileIngested.accept(new FileIngestedEvent(fileId, fileName, fileBuffer.length, chunkHashes.size()));
            }
            
            return new IngestResult(fileId, chunkHashes, encryptedManifest, operationId);
        } finally {
            if (!state.completed) {
                state.failed = true;
                state.completedAt = Instant.now();
            }
        }
    }
    
    public CompletableFuture<IngestResult> ingestAsync(byte[] fileBuffer, String fileName, byte[] masterKey) {
        return ingestAsync(fileBuffer, fileName, masterKey, null);
    }
    
    public CompletableFuture<IngestResult> ingestAsync(byte[] fileBuffer, String fileName, byte[] masterKey, Consumer<Progress> progress) {
        return CompletableFuture.supplyAsync(() -> ingest(fileBuffer, fileName, masterKey, progress), executor);
    }
    
    public IngestResult ingestStreaming(InputStream input, String fileName, byte[] masterKey, Consumer<Progress> progress) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return ingest(buffer.toByteArray(), fileName, masterKey, progress);
    }
    
    public CompletableFuture<IngestResult> ingestStreamingAsync(InputStream input, String fileName, byte[] masterKey, Consumer<Progress> progress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ingestStreaming(input, fileName, masterKey, progress);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    private Segment ingestWithErasure(byte[] muxedData, byte[] chunkKey, byte[] isoSeed, 
                                       MuxEngine.MuxResult muxResult, int originalSize,
                                       List<String> allChunkHashes) {
        ErasureCoder.EncodeResult encoded = erasureCoder.encode(muxedData);
        List<ShardInfo> shards = new ArrayList<>();
        
        for (int i = 0; i < encoded.shards().length; i++) {
            byte[] shard = encoded.shards()[i];
            String shardHash = sha256Hex(shard);
            blobStore.put(shardHash, shard);
            shards.add(new ShardInfo(i, shardHash, shard.length));
            allChunkHashes.add(shardHash);
        }
        
        if (onErasureEncoded != null) {
            onErasureEncoded.accept(new ErasureEncodedEvent(
                erasureCoder.getDataShards(),
                erasureCoder.getParityShards(),
                shards.size()
            ));
        }
        
        return new Segment(
            null,
            HexFormat.of().formatHex(chunkKey),
            HexFormat.of().formatHex(isoSeed),
            0,
            muxResult.sectorCount(),
            muxResult.encryptedSize(),
            originalSize,
            shards,
            muxedData.length,
            encoded.shardSize()
        );
    }
    
    public RetrieveResult retrieve(String fileId, byte[] masterKey) {
        return retrieve(fileId, masterKey, null);
    }
    
    public RetrieveResult retrieve(String fileId, byte[] masterKey, Consumer<Progress> progress) {
        String operationId = generateOperationId();
        OperationState state = new OperationState(operationId, OperationType.RETRIEVE, Instant.now());
        operations.put(operationId, state);
        
        try {
            byte[] encryptedManifest = manifestStore.get(fileId);
            if (encryptedManifest == null) {
                throw new IllegalArgumentException("Manifest not found for file: " + fileId);
            }
            
            byte[] manifestKey = Manifest.deriveManifestKey(masterKey, fileId);
            Manifest manifest = Manifest.decrypt(encryptedManifest, manifestKey);
            
            List<byte[]> parts = new ArrayList<>();
            List<Segment> segments = manifest.getSegments();
            long totalSize = manifest.getFileSize();
            long bytesRetrieved = 0;
            
            for (int i = 0; i < segments.size(); i++) {
                if (state.cancelled) {
                    throw new CancellationException("Operation cancelled: " + operationId);
                }
                
                Segment segment = segments.get(i);
                
                byte[] muxedData;
                if (segment.shards() != null && !segment.shards().isEmpty()) {
                    muxedData = retrieveWithErasure(segment, manifest.getErasure());
                } else {
                    muxedData = blobStore.get(segment.chunkHash())
                        .orElseThrow(() -> new IllegalStateException("Chunk not found: " + segment.chunkHash()));
                }
                
                byte[] chunkKey = HexFormat.of().parseHex(segment.chunkKey());
                byte[] isoSeed = HexFormat.of().parseHex(segment.isoSeed());
                
                byte[] chunk = muxEngine.demux(muxedData, chunkKey, isoSeed, segment.sectorStart(), segment.encryptedSize());
                parts.add(chunk);
                bytesRetrieved += chunk.length;
                
                state.bytesProcessed = bytesRetrieved;
                state.progress = (double) bytesRetrieved / totalSize;
                
                if (progress != null) {
                    progress.accept(new Progress(
                        operationId,
                        OperationType.RETRIEVE,
                        bytesRetrieved,
                        totalSize,
                        state.progress * 100,
                        i + 1,
                        segments.size(),
                        fileId
                    ));
                }
                
                if (onChunkRetrieved != null) {
                    String hash = segment.chunkHash() != null ? segment.chunkHash() : segment.shards().get(0).hash();
                    onChunkRetrieved.accept(new ChunkRetrievedEvent(fileId, hash, state.progress));
                }
            }
            
            int totalBytes = parts.stream().mapToInt(p -> p.length).sum();
            ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
            for (byte[] part : parts) {
                buffer.put(part);
            }
            byte[] fileBuffer = buffer.array();
            
            state.completed = true;
            state.completedAt = Instant.now();
            totalBytesRetrieved.addAndGet(fileBuffer.length);
            totalFilesRetrieved.incrementAndGet();
            
            if (onFileRetrieved != null) {
                onFileRetrieved.accept(new FileRetrievedEvent(fileId, manifest.getFileName(), fileBuffer.length));
            }
            
            return new RetrieveResult(manifest.getFileName(), manifest.getFileSize(), fileBuffer, operationId);
        } finally {
            if (!state.completed) {
                state.failed = true;
                state.completedAt = Instant.now();
            }
        }
    }
    
    public CompletableFuture<RetrieveResult> retrieveAsync(String fileId, byte[] masterKey) {
        return retrieveAsync(fileId, masterKey, null);
    }
    
    public CompletableFuture<RetrieveResult> retrieveAsync(String fileId, byte[] masterKey, Consumer<Progress> progress) {
        return CompletableFuture.supplyAsync(() -> retrieve(fileId, masterKey, progress), executor);
    }
    
    private byte[] retrieveWithErasure(Segment segment, ErasureConfig erasureConfig) {
        ErasureCoder coder = new ErasureCoder(erasureConfig.dataShards(), erasureConfig.parityShards());
        
        byte[][] shards = new byte[segment.shards().size()][];
        List<Integer> presentIndices = new ArrayList<>();
        
        for (ShardInfo shardInfo : segment.shards()) {
            Optional<byte[]> shardData = blobStore.get(shardInfo.hash());
            if (shardData.isPresent()) {
                shards[shardInfo.index()] = shardData.get();
                presentIndices.add(shardInfo.index());
            }
        }
        
        if (presentIndices.size() < coder.getDataShards()) {
            throw new IllegalStateException(
                "Not enough shards available. Need " + coder.getDataShards() + ", have " + presentIndices.size());
        }
        
        int[] indices = presentIndices.stream().mapToInt(Integer::intValue).toArray();
        byte[] muxedData = coder.decode(shards, indices, segment.muxedSize(), segment.shardSize());
        
        if (onErasureDecoded != null) {
            onErasureDecoded.accept(new ErasureDecodedEvent(
                presentIndices.size(),
                segment.shards().size(),
                presentIndices.size() < segment.shards().size()
            ));
        }
        
        return muxedData;
    }
    
    public boolean cancelOperation(String operationId) {
        OperationState state = operations.get(operationId);
        if (state != null && !state.completed && !state.failed) {
            state.cancelled = true;
            return true;
        }
        return false;
    }
    
    public Optional<OperationStatus> getOperationStatus(String operationId) {
        OperationState state = operations.get(operationId);
        if (state == null) {
            return Optional.empty();
        }
        
        OperationStatusType statusType;
        if (state.cancelled) {
            statusType = OperationStatusType.CANCELLED;
        } else if (state.failed) {
            statusType = OperationStatusType.FAILED;
        } else if (state.completed) {
            statusType = OperationStatusType.COMPLETED;
        } else {
            statusType = OperationStatusType.IN_PROGRESS;
        }
        
        Duration duration = state.completedAt != null 
            ? Duration.between(state.startedAt, state.completedAt)
            : Duration.between(state.startedAt, Instant.now());
        
        return Optional.of(new OperationStatus(
            operationId,
            state.type,
            statusType,
            state.progress * 100,
            state.bytesProcessed,
            state.startedAt,
            state.completedAt,
            duration
        ));
    }
    
    public List<OperationStatus> getActiveOperations() {
        return operations.values().stream()
            .filter(s -> !s.completed && !s.failed && !s.cancelled)
            .map(s -> getOperationStatus(s.operationId).orElse(null))
            .filter(Objects::nonNull)
            .toList();
    }
    
    public void cleanupCompletedOperations(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        operations.entrySet().removeIf(e -> 
            e.getValue().completedAt != null && e.getValue().completedAt.isBefore(cutoff));
    }
    
    public byte[] extractChunkAsISO(String chunkHash, Manifest manifest) {
        byte[] muxedData = blobStore.get(chunkHash)
            .orElseThrow(() -> new IllegalArgumentException("Chunk not found: " + chunkHash));
        
        Segment segment = manifest.getSegments().stream()
            .filter(s -> chunkHash.equals(s.chunkHash()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Segment metadata not found"));
        
        byte[] isoSeed = HexFormat.of().parseHex(segment.isoSeed());
        return isoForge.generate(isoSeed, isoSize);
    }
    
    public boolean verifyChunkAsISO(String chunkHash) {
        Optional<byte[]> muxedData = blobStore.get(chunkHash);
        if (muxedData.isEmpty()) return false;
        return muxedData.get().length >= 2048;
    }
    
    public Optional<byte[]> getManifest(String fileId) {
        return Optional.ofNullable(manifestStore.get(fileId));
    }
    
    public void storeManifest(String fileId, byte[] encryptedManifest) {
        manifestStore.put(fileId, encryptedManifest);
    }
    
    public StorageStats stats() {
        BlobStore.BlobStoreStats blobStats = blobStore.stats();
        ErasureStats erasureStats = null;
        
        if (enableErasure) {
            erasureStats = new ErasureStats(
                erasureCoder.getDataShards(),
                erasureCoder.getParityShards(),
                erasureCoder.getTotalShards()
            );
        }
        
        return new StorageStats(
            blobStats.blobCount(),
            blobStats.totalBytes(),
            manifestStore.size(),
            isoSize.name().toLowerCase(),
            erasureStats,
            totalBytesIngested.get(),
            totalBytesRetrieved.get(),
            totalFilesIngested.get(),
            totalFilesRetrieved.get(),
            operations.size(),
            getActiveOperations().size()
        );
    }
    
    public StorageOptions getOptions() {
        return options;
    }
    
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }
    
    public void setOnChunkIngested(Consumer<ChunkIngestedEvent> listener) { this.onChunkIngested = listener; }
    public void setOnFileIngested(Consumer<FileIngestedEvent> listener) { this.onFileIngested = listener; }
    public void setOnErasureEncoded(Consumer<ErasureEncodedEvent> listener) { this.onErasureEncoded = listener; }
    public void setOnChunkRetrieved(Consumer<ChunkRetrievedEvent> listener) { this.onChunkRetrieved = listener; }
    public void setOnFileRetrieved(Consumer<FileRetrievedEvent> listener) { this.onFileRetrieved = listener; }
    public void setOnErasureDecoded(Consumer<ErasureDecodedEvent> listener) { this.onErasureDecoded = listener; }
    
    private String generateOperationId() {
        return "op-" + operationCounter.incrementAndGet() + "-" + System.currentTimeMillis();
    }
    
    private static byte[] generateEncryptionKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
    
    private static byte[] generateISOSeed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }
    
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    public static class StorageOptions {
        public final SizePreset isoSize;
        public final boolean enableErasure;
        public final int dataShards;
        public final int parityShards;
        public final int concurrency;
        public final int chunkSize;
        public final boolean verifyOnIngest;
        public final boolean verifyOnRetrieve;
        public final Duration operationTimeout;
        public final int maxRetries;
        
        private StorageOptions(Builder builder) {
            this.isoSize = builder.isoSize;
            this.enableErasure = builder.enableErasure;
            this.dataShards = builder.dataShards;
            this.parityShards = builder.parityShards;
            this.concurrency = builder.concurrency;
            this.chunkSize = builder.chunkSize;
            this.verifyOnIngest = builder.verifyOnIngest;
            this.verifyOnRetrieve = builder.verifyOnRetrieve;
            this.operationTimeout = builder.operationTimeout;
            this.maxRetries = builder.maxRetries;
        }
        
        public static StorageOptions defaults() {
            return builder().build();
        }
        
        public static StorageOptions withErasure(int dataShards, int parityShards) {
            return builder()
                .enableErasure(true)
                .dataShards(dataShards)
                .parityShards(parityShards)
                .build();
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public SizePreset isoSize() { return isoSize; }
        public boolean enableErasure() { return enableErasure; }
        public int dataShards() { return dataShards; }
        public int parityShards() { return parityShards; }
        
        public static class Builder {
            private SizePreset isoSize = SizePreset.NANO;
            private boolean enableErasure = false;
            private int dataShards = 4;
            private int parityShards = 2;
            private int concurrency = Runtime.getRuntime().availableProcessors();
            private int chunkSize = CHUNK_SIZE;
            private boolean verifyOnIngest = true;
            private boolean verifyOnRetrieve = false;
            private Duration operationTimeout = Duration.ofMinutes(30);
            private int maxRetries = 3;
            
            public Builder isoSize(SizePreset size) { this.isoSize = size; return this; }
            public Builder enableErasure(boolean enable) { this.enableErasure = enable; return this; }
            public Builder dataShards(int shards) { this.dataShards = shards; return this; }
            public Builder parityShards(int shards) { this.parityShards = shards; return this; }
            public Builder concurrency(int concurrency) { this.concurrency = concurrency; return this; }
            public Builder chunkSize(int size) { this.chunkSize = size; return this; }
            public Builder verifyOnIngest(boolean verify) { this.verifyOnIngest = verify; return this; }
            public Builder verifyOnRetrieve(boolean verify) { this.verifyOnRetrieve = verify; return this; }
            public Builder operationTimeout(Duration timeout) { this.operationTimeout = timeout; return this; }
            public Builder maxRetries(int retries) { this.maxRetries = retries; return this; }
            
            public StorageOptions build() {
                return new StorageOptions(this);
            }
        }
    }
    
    public enum OperationType { INGEST, RETRIEVE }
    public enum OperationStatusType { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }
    
    private static class OperationState {
        final String operationId;
        final OperationType type;
        final Instant startedAt;
        volatile Instant completedAt;
        volatile boolean completed;
        volatile boolean failed;
        volatile boolean cancelled;
        volatile double progress;
        volatile long bytesProcessed;
        
        OperationState(String operationId, OperationType type, Instant startedAt) {
            this.operationId = operationId;
            this.type = type;
            this.startedAt = startedAt;
        }
    }
    
    public record IngestResult(String fileId, List<String> chunkHashes, byte[] encryptedManifest, String operationId) {
        public IngestResult(String fileId, List<String> chunkHashes, byte[] encryptedManifest) {
            this(fileId, chunkHashes, encryptedManifest, null);
        }
    }
    
    public record RetrieveResult(String fileName, long fileSize, byte[] data, String operationId) {
        public RetrieveResult(String fileName, long fileSize, byte[] data) {
            this(fileName, fileSize, data, null);
        }
    }
    
    public record Progress(
        String operationId,
        OperationType type,
        long bytesProcessed,
        long totalBytes,
        double percentComplete,
        int chunksProcessed,
        int totalChunks,
        String fileId
    ) {
        public boolean isComplete() {
            return percentComplete >= 100.0;
        }
    }
    
    public record OperationStatus(
        String operationId,
        OperationType type,
        OperationStatusType status,
        double percentComplete,
        long bytesProcessed,
        Instant startedAt,
        Instant completedAt,
        Duration elapsed
    ) {}
    
    public record StorageStats(
        int blobCount,
        long totalBytes,
        int manifestCount,
        String isoSize,
        ErasureStats erasure,
        long totalBytesIngested,
        long totalBytesRetrieved,
        long totalFilesIngested,
        long totalFilesRetrieved,
        int totalOperations,
        int activeOperations
    ) {
        public StorageStats(int blobCount, long totalBytes, int manifestCount, String isoSize, ErasureStats erasure) {
            this(blobCount, totalBytes, manifestCount, isoSize, erasure, 0, 0, 0, 0, 0, 0);
        }
    }
    
    public record ErasureStats(int dataShards, int parityShards, int totalShards) {}
    
    public record ChunkIngestedEvent(String fileId, String chunkHash, double progress) {}
    public record FileIngestedEvent(String fileId, String fileName, long size, int chunkCount) {}
    public record ErasureEncodedEvent(int dataShards, int parityShards, int shardCount) {}
    public record ChunkRetrievedEvent(String fileId, String chunkHash, double progress) {}
    public record FileRetrievedEvent(String fileId, String fileName, long size) {}
    public record ErasureDecodedEvent(int presentShards, int totalShards, boolean recovered) {}
}
