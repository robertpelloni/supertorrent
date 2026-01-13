package io.supernode.storage.mux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Manifest")
class ManifestTest {

    @Nested
    @DisplayName("Creation")
    class CreationTests {

        @Test
        @DisplayName("should create manifest with all fields")
        void shouldCreateManifestWithAllFields() {
            Manifest.ManifestOptions options = new Manifest.ManifestOptions(
                "file-123",
                "test-document.pdf",
                1024 * 1024,
                "seed-abc123",
                512 * 1024 * 1024L,
                new Manifest.ErasureConfig(4, 2),
                List.of(
                    new Manifest.Segment(
                        "chunk-hash-1",
                        "chunk-key-1",
                        "iso-seed-1",
                        0,
                        100,
                        2048,
                        2000,
                        List.of(new Manifest.ShardInfo(0, "shard-hash-0", 512)),
                        2048,
                        512
                    )
                )
            );

            Manifest manifest = Manifest.create(options);

            assertEquals(Manifest.VERSION, manifest.getVersion());
            assertEquals("file-123", manifest.getFileId());
            assertEquals("test-document.pdf", manifest.getFileName());
            assertEquals(1024 * 1024, manifest.getFileSize());
            assertEquals("seed-abc123", manifest.getIsoSeed());
            assertEquals(512 * 1024 * 1024L, manifest.getIsoSize());
            assertNotNull(manifest.getErasure());
            assertEquals(4, manifest.getErasure().dataShards());
            assertEquals(2, manifest.getErasure().parityShards());
            assertNotNull(manifest.getSegments());
            assertEquals(1, manifest.getSegments().size());
            assertTrue(manifest.getCreatedAt() > 0);
        }

        @Test
        @DisplayName("should create manifest with byte array seed")
        void shouldCreateManifestWithByteArraySeed() {
            byte[] seedBytes = new byte[32];
            new SecureRandom().nextBytes(seedBytes);

            Manifest.ManifestOptions options = new Manifest.ManifestOptions(
                "file-456",
                "binary-seed.bin",
                2048,
                seedBytes,
                256 * 1024 * 1024L,
                null,
                List.of()
            );

            Manifest manifest = Manifest.create(options);

            assertNotNull(manifest.getIsoSeed());
            assertEquals(64, manifest.getIsoSeed().length());
        }

        @Test
        @DisplayName("should allow setting ISO hash after creation")
        void shouldAllowSettingIsoHashAfterCreation() {
            Manifest.ManifestOptions options = new Manifest.ManifestOptions(
                "file-789",
                "hash-test.txt",
                512,
                "seed",
                1024L,
                null,
                List.of()
            );

            Manifest manifest = Manifest.create(options);
            assertNull(manifest.getIsoHash());

            manifest.setIsoHash("abc123def456");
            assertEquals("abc123def456", manifest.getIsoHash());
        }
    }

    @Nested
    @DisplayName("Encryption and Decryption")
    class EncryptionTests {

        @Test
        @DisplayName("should encrypt and decrypt manifest successfully")
        void shouldEncryptAndDecryptManifestSuccessfully() {
            Manifest original = createTestManifest();
            byte[] key = generateKey();

            byte[] encrypted = Manifest.encrypt(original, key);
            Manifest decrypted = Manifest.decrypt(encrypted, key);

            assertEquals(original.getFileId(), decrypted.getFileId());
            assertEquals(original.getFileName(), decrypted.getFileName());
            assertEquals(original.getFileSize(), decrypted.getFileSize());
            assertEquals(original.getIsoSeed(), decrypted.getIsoSeed());
            assertEquals(original.getIsoSize(), decrypted.getIsoSize());
        }

        @Test
        @DisplayName("should produce different ciphertext each time due to random nonce")
        void shouldProduceDifferentCiphertextEachTime() {
            Manifest manifest = createTestManifest();
            byte[] key = generateKey();

            byte[] encrypted1 = Manifest.encrypt(manifest, key);
            byte[] encrypted2 = Manifest.encrypt(manifest, key);

            assertFalse(java.util.Arrays.equals(encrypted1, encrypted2));
        }

        @Test
        @DisplayName("should fail to decrypt with wrong key")
        void shouldFailToDecryptWithWrongKey() {
            Manifest manifest = createTestManifest();
            byte[] correctKey = generateKey();
            byte[] wrongKey = generateKey();

            byte[] encrypted = Manifest.encrypt(manifest, correctKey);

            assertThrows(RuntimeException.class, () -> 
                Manifest.decrypt(encrypted, wrongKey)
            );
        }

        @Test
        @DisplayName("should fail to decrypt corrupted data")
        void shouldFailToDecryptCorruptedData() {
            Manifest manifest = createTestManifest();
            byte[] key = generateKey();

            byte[] encrypted = Manifest.encrypt(manifest, key);
            encrypted[encrypted.length / 2] ^= 0xFF;

            assertThrows(RuntimeException.class, () -> 
                Manifest.decrypt(encrypted, key)
            );
        }

        @Test
        @DisplayName("should preserve all segment data through encryption")
        void shouldPreserveAllSegmentDataThroughEncryption() {
            Manifest.Segment segment = new Manifest.Segment(
                "chunk-hash-abc",
                "chunk-key-xyz",
                "iso-seed-123",
                100,
                50,
                4096,
                4000,
                List.of(
                    new Manifest.ShardInfo(0, "shard-0", 1024),
                    new Manifest.ShardInfo(1, "shard-1", 1024)
                ),
                4096,
                1024
            );

            Manifest.ManifestOptions options = new Manifest.ManifestOptions(
                "segment-test",
                "segments.dat",
                8192,
                "seed",
                1024L,
                new Manifest.ErasureConfig(4, 2),
                List.of(segment)
            );

            Manifest original = Manifest.create(options);
            byte[] key = generateKey();

            byte[] encrypted = Manifest.encrypt(original, key);
            Manifest decrypted = Manifest.decrypt(encrypted, key);

            assertEquals(1, decrypted.getSegments().size());
            Manifest.Segment decryptedSegment = decrypted.getSegments().get(0);
            
            assertEquals("chunk-hash-abc", decryptedSegment.chunkHash());
            assertEquals("chunk-key-xyz", decryptedSegment.chunkKey());
            assertEquals("iso-seed-123", decryptedSegment.isoSeed());
            assertEquals(100, decryptedSegment.sectorStart());
            assertEquals(50, decryptedSegment.sectorCount());
            assertEquals(4096, decryptedSegment.encryptedSize());
            assertEquals(4000, decryptedSegment.originalSize());
            assertEquals(2, decryptedSegment.shards().size());
        }
    }

    @Nested
    @DisplayName("Key Derivation")
    class KeyDerivationTests {

        @Test
        @DisplayName("should derive consistent key for same inputs")
        void shouldDeriveConsistentKeyForSameInputs() {
            byte[] masterKey = generateKey();
            String fileId = "file-123";

            byte[] derivedKey1 = Manifest.deriveManifestKey(masterKey, fileId);
            byte[] derivedKey2 = Manifest.deriveManifestKey(masterKey, fileId);

            assertArrayEquals(derivedKey1, derivedKey2);
        }

        @Test
        @DisplayName("should derive different keys for different file IDs")
        void shouldDeriveDifferentKeysForDifferentFileIds() {
            byte[] masterKey = generateKey();

            byte[] key1 = Manifest.deriveManifestKey(masterKey, "file-1");
            byte[] key2 = Manifest.deriveManifestKey(masterKey, "file-2");

            assertFalse(java.util.Arrays.equals(key1, key2));
        }

        @Test
        @DisplayName("should derive different keys for different master keys")
        void shouldDeriveDifferentKeysForDifferentMasterKeys() {
            byte[] masterKey1 = generateKey();
            byte[] masterKey2 = generateKey();
            String fileId = "same-file";

            byte[] key1 = Manifest.deriveManifestKey(masterKey1, fileId);
            byte[] key2 = Manifest.deriveManifestKey(masterKey2, fileId);

            assertFalse(java.util.Arrays.equals(key1, key2));
        }

        @Test
        @DisplayName("should derive 32-byte key")
        void shouldDerive32ByteKey() {
            byte[] masterKey = generateKey();
            byte[] derivedKey = Manifest.deriveManifestKey(masterKey, "any-file");

            assertEquals(32, derivedKey.length);
        }

        @Test
        @DisplayName("derived key should work for encryption")
        void derivedKeyShouldWorkForEncryption() {
            byte[] masterKey = generateKey();
            String fileId = "encryption-test";
            byte[] derivedKey = Manifest.deriveManifestKey(masterKey, fileId);

            Manifest manifest = createTestManifest();

            byte[] encrypted = Manifest.encrypt(manifest, derivedKey);
            Manifest decrypted = Manifest.decrypt(encrypted, derivedKey);

            assertEquals(manifest.getFileId(), decrypted.getFileId());
        }
    }

    @Nested
    @DisplayName("Records")
    class RecordTests {

        @Test
        @DisplayName("Segment record should have all fields")
        void segmentRecordShouldHaveAllFields() {
            Manifest.Segment segment = new Manifest.Segment(
                "hash", "key", "seed", 0, 10, 1024, 1000,
                List.of(), 1024, 256
            );

            assertEquals("hash", segment.chunkHash());
            assertEquals("key", segment.chunkKey());
            assertEquals("seed", segment.isoSeed());
            assertEquals(0, segment.sectorStart());
            assertEquals(10, segment.sectorCount());
            assertEquals(1024, segment.encryptedSize());
            assertEquals(1000, segment.originalSize());
            assertNotNull(segment.shards());
            assertEquals(1024, segment.muxedSize());
            assertEquals(256, segment.shardSize());
        }

        @Test
        @DisplayName("ShardInfo record should have all fields")
        void shardInfoRecordShouldHaveAllFields() {
            Manifest.ShardInfo shard = new Manifest.ShardInfo(5, "shard-hash", 2048);

            assertEquals(5, shard.index());
            assertEquals("shard-hash", shard.hash());
            assertEquals(2048, shard.size());
        }

        @Test
        @DisplayName("ErasureConfig record should have all fields")
        void erasureConfigRecordShouldHaveAllFields() {
            Manifest.ErasureConfig config = new Manifest.ErasureConfig(6, 3);

            assertEquals(6, config.dataShards());
            assertEquals(3, config.parityShards());
        }

        @Test
        @DisplayName("ManifestOptions record should have all fields")
        void manifestOptionsRecordShouldHaveAllFields() {
            Manifest.ManifestOptions options = new Manifest.ManifestOptions(
                "id", "name", 100, "seed", 200,
                new Manifest.ErasureConfig(4, 2),
                List.of()
            );

            assertEquals("id", options.fileId());
            assertEquals("name", options.fileName());
            assertEquals(100, options.fileSize());
            assertEquals("seed", options.isoSeed());
            assertEquals(200, options.isoSize());
            assertNotNull(options.erasure());
            assertNotNull(options.segments());
        }
    }

    @Nested
    @DisplayName("Version")
    class VersionTests {

        @Test
        @DisplayName("should have version constant")
        void shouldHaveVersionConstant() {
            assertEquals(1, Manifest.VERSION);
        }

        @Test
        @DisplayName("created manifest should have current version")
        void createdManifestShouldHaveCurrentVersion() {
            Manifest manifest = createTestManifest();
            assertEquals(Manifest.VERSION, manifest.getVersion());
        }
    }

    private Manifest createTestManifest() {
        return Manifest.create(new Manifest.ManifestOptions(
            "test-file-id",
            "test-file.txt",
            4096,
            "test-seed",
            1024 * 1024L,
            new Manifest.ErasureConfig(4, 2),
            List.of()
        ));
    }

    private byte[] generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
