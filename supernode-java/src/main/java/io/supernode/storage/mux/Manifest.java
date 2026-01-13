package io.supernode.storage.mux;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

public class Manifest {
    public static final int VERSION = 1;
    private static final int NONCE_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final String CIPHER_ALGO = "ChaCha20-Poly1305";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("version")
    private int version = VERSION;

    @JsonProperty("fileId")
    private String fileId;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("fileSize")
    private long fileSize;

    @JsonProperty("isoSeed")
    private String isoSeed;

    @JsonProperty("isoSize")
    private long isoSize;

    @JsonProperty("isoHash")
    private String isoHash;

    @JsonProperty("erasure")
    private ErasureConfig erasure;

    @JsonProperty("segments")
    private List<Segment> segments;

    @JsonProperty("createdAt")
    private long createdAt;

    public Manifest() {}

    public static Manifest create(ManifestOptions options) {
        Manifest m = new Manifest();
        m.version = VERSION;
        m.fileId = options.fileId();
        m.fileName = options.fileName();
        m.fileSize = options.fileSize();
        m.isoSeed = options.isoSeed() instanceof byte[] 
            ? HexFormat.of().formatHex((byte[]) options.isoSeed())
            : (String) options.isoSeed();
        m.isoSize = options.isoSize();
        m.isoHash = null;
        m.erasure = options.erasure();
        m.segments = options.segments();
        m.createdAt = System.currentTimeMillis();
        return m;
    }

    public static byte[] encrypt(Manifest manifest, byte[] key) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(manifest);
            byte[] nonce = new byte[NONCE_SIZE];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "ChaCha20"),
                new GCMParameterSpec(TAG_SIZE * 8, nonce));

            byte[] ciphertext = cipher.doFinal(json);

            ByteBuffer result = ByteBuffer.allocate(NONCE_SIZE + ciphertext.length);
            result.put(nonce);
            result.put(ciphertext);
            return result.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt manifest", e);
        }
    }

    public static Manifest decrypt(byte[] encrypted, byte[] key) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(encrypted);
            byte[] nonce = new byte[NONCE_SIZE];
            buf.get(nonce);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "ChaCha20"),
                new GCMParameterSpec(TAG_SIZE * 8, nonce));

            byte[] json = cipher.doFinal(ciphertext);
            return MAPPER.readValue(json, Manifest.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt manifest", e);
        }
    }

    public static byte[] deriveManifestKey(byte[] masterKey, String fileId) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            hmac.update(("manifest:" + fileId).getBytes());
            return hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    public int getVersion() { return version; }
    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public String getIsoSeed() { return isoSeed; }
    public long getIsoSize() { return isoSize; }
    public String getIsoHash() { return isoHash; }
    public ErasureConfig getErasure() { return erasure; }
    public List<Segment> getSegments() { return segments; }
    public long getCreatedAt() { return createdAt; }

    public void setIsoHash(String isoHash) { this.isoHash = isoHash; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
        @JsonProperty("chunkHash") String chunkHash,
        @JsonProperty("chunkKey") String chunkKey,
        @JsonProperty("isoSeed") String isoSeed,
        @JsonProperty("sectorStart") int sectorStart,
        @JsonProperty("sectorCount") int sectorCount,
        @JsonProperty("encryptedSize") int encryptedSize,
        @JsonProperty("originalSize") int originalSize,
        @JsonProperty("shards") List<ShardInfo> shards,
        @JsonProperty("muxedSize") Integer muxedSize,
        @JsonProperty("shardSize") Integer shardSize
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShardInfo(
        @JsonProperty("index") int index,
        @JsonProperty("hash") String hash,
        @JsonProperty("size") int size
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErasureConfig(
        @JsonProperty("dataShards") int dataShards,
        @JsonProperty("parityShards") int parityShards
    ) {}

    public record ManifestOptions(
        String fileId,
        String fileName,
        long fileSize,
        Object isoSeed,
        long isoSize,
        ErasureConfig erasure,
        List<Segment> segments
    ) {}
}
