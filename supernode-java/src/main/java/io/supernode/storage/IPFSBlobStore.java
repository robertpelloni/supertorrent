package io.supernode.storage;

import io.supernode.network.transport.IPFSTransport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class IPFSBlobStore implements BlobStore {

    private static final int TIMEOUT_SECONDS = 30;
    
    private final IPFSTransport ipfs;
    private final Map<String, String> hashToCid = new ConcurrentHashMap<>();
    private final Map<String, String> cidToHash = new ConcurrentHashMap<>();
    private final boolean autoPin;
    private long totalBytes = 0;

    public IPFSBlobStore(IPFSTransport ipfs) {
        this(ipfs, true);
    }

    public IPFSBlobStore(IPFSTransport ipfs, boolean autoPin) {
        this.ipfs = ipfs;
        this.autoPin = autoPin;
    }

    @Override
    public void put(String hash, byte[] data) {
        try {
            String cid = ipfs.add(data).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            hashToCid.put(hash, cid);
            cidToHash.put(cid, hash);
            totalBytes += data.length;
            
            if (autoPin) {
                ipfs.pin(cid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store blob in IPFS", e);
        }
    }

    @Override
    public Optional<byte[]> get(String hash) {
        String cid = hashToCid.get(hash);
        if (cid == null) {
            return Optional.empty();
        }
        try {
            byte[] data = ipfs.cat(cid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean has(String hash) {
        return hashToCid.containsKey(hash);
    }

    @Override
    public boolean delete(String hash) {
        String cid = hashToCid.remove(hash);
        if (cid == null) {
            return false;
        }
        cidToHash.remove(cid);
        
        if (autoPin) {
            try {
                ipfs.unpin(cid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        return true;
    }

    @Override
    public BlobStoreStats stats() {
        return new BlobStoreStats(hashToCid.size(), totalBytes);
    }

    public CompletableFuture<String> putAsync(byte[] data) {
        return ipfs.add(data)
            .thenCompose(cid -> {
                if (autoPin) {
                    return ipfs.pin(cid).thenApply(v -> cid);
                }
                return CompletableFuture.completedFuture(cid);
            });
    }

    public CompletableFuture<byte[]> catByCid(String cid) {
        return ipfs.cat(cid);
    }

    public CompletableFuture<byte[]> getByCid(String cid) {
        return ipfs.cat(cid);
    }
    
    @Override
    public java.util.List<String> listHashes() {
        return new java.util.ArrayList<>(hashToCid.keySet());
    }

    public CompletableFuture<Void> pinCid(String cid) {
        return ipfs.pin(cid);
    }

    public CompletableFuture<Void> unpinCid(String cid) {
        return ipfs.unpin(cid);
    }

    public String getCidForHash(String hash) {
        return hashToCid.get(hash);
    }

    public String getHashForCid(String cid) {
        return cidToHash.get(cid);
    }

    public void registerMapping(String hash, String cid) {
        hashToCid.put(hash, cid);
        cidToHash.put(cid, hash);
    }

    public CompletableFuture<List<String>> listPinnedCids() {
        return ipfs.listPins();
    }

    public int getPinnedCount() {
        try {
            return ipfs.listPins().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
        } catch (Exception e) {
            return 0;
        }
    }
}
