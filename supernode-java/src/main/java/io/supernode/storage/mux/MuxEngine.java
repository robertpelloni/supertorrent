package io.supernode.storage.mux;

import io.supernode.storage.isoforge.ISOForge;
import io.supernode.storage.isoforge.SeededRNG;
import io.supernode.storage.isoforge.SizePreset;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public class MuxEngine {
    public static final int SECTOR_SIZE = 2048;
    private static final int NONCE_SIZE = 12;
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_SIZE = 128; // bits

    private final ISOForge isoForge;
    private final long isoSize;

    public MuxEngine() {
        this(SizePreset.NANO);
    }

    public MuxEngine(SizePreset sizePreset) {
        this(sizePreset.getBytes());
    }

    public MuxEngine(long isoSize) {
        this.isoForge = new ISOForge();
        this.isoSize = isoSize;
    }

    public MuxResult mux(byte[] plaintext, byte[] key, byte[] isoSeed) {
        return mux(plaintext, key, isoSeed, 0);
    }

    public MuxResult mux(byte[] plaintext, byte[] key, byte[] isoSeed, int sectorOffset) {
        byte[] encrypted = encrypt(plaintext, key);
        int paddedSize = ((encrypted.length + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE;
        byte[] padded = Arrays.copyOf(encrypted, paddedSize);

        int sectorCount = paddedSize / SECTOR_SIZE;
        byte[] muxed = new byte[paddedSize];

        for (int i = 0; i < sectorCount; i++) {
            int sectorIndex = sectorOffset + i;
            byte[] isoSector = isoForge.generateSector(isoSeed, sectorIndex, isoSize);
            for (int j = 0; j < SECTOR_SIZE; j++) {
                muxed[i * SECTOR_SIZE + j] = (byte) (padded[i * SECTOR_SIZE + j] ^ isoSector[j]);
            }
        }

        return new MuxResult(muxed, sectorOffset, sectorCount, encrypted.length);
    }

    public byte[] demux(byte[] muxed, byte[] key, byte[] isoSeed, int sectorStart, int encryptedSize) {
        int sectorCount = muxed.length / SECTOR_SIZE;
        byte[] demuxed = new byte[muxed.length];

        for (int i = 0; i < sectorCount; i++) {
            int sectorIndex = sectorStart + i;
            byte[] isoSector = isoForge.generateSector(isoSeed, sectorIndex, isoSize);
            for (int j = 0; j < SECTOR_SIZE; j++) {
                demuxed[i * SECTOR_SIZE + j] = (byte) (muxed[i * SECTOR_SIZE + j] ^ isoSector[j]);
            }
        }

        byte[] encrypted = Arrays.copyOf(demuxed, encryptedSize);
        return decrypt(encrypted, key);
    }

    private byte[] encrypt(byte[] plaintext, byte[] key) {
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_SIZE, nonce));

            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteBuffer result = ByteBuffer.allocate(NONCE_SIZE + ciphertext.length);
            result.put(nonce);
            result.put(ciphertext);
            return result.array();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] encrypted, byte[] key) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(encrypted);
            byte[] nonce = new byte[NONCE_SIZE];
            buf.get(nonce);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_SIZE, nonce));

            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public long getIsoSize() {
        return isoSize;
    }

    public record MuxResult(byte[] muxedData, int sectorStart, int sectorCount, int encryptedSize) {}
}
