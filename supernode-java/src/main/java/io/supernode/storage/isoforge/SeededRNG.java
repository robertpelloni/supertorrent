package io.supernode.storage.isoforge;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

public class SeededRNG {
    private static final int BUFFER_SIZE = 65536;
    
    private final byte[] seed;
    private long counter = 0;
    private byte[] buffer = new byte[0];
    private int offset = 0;

    public SeededRNG(byte[] seed) {
        if (seed.length != 32) {
            throw new IllegalArgumentException("Seed must be 256 bits (32 bytes)");
        }
        this.seed = seed.clone();
    }

    public SeededRNG(String hexSeed) {
        this(HexFormat.of().parseHex(hexSeed));
    }

    public byte[] bytes(int length) {
        byte[] result = new byte[length];
        int written = 0;

        while (written < length) {
            if (offset >= buffer.length) {
                refillBuffer();
            }
            int toCopy = Math.min(length - written, buffer.length - offset);
            System.arraycopy(buffer, offset, result, written, toCopy);
            offset += toCopy;
            written += toCopy;
        }

        return result;
    }

    public int nextInt(int max) {
        byte[] b = bytes(4);
        int value = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        return Math.abs(value % max);
    }

    public double nextFloat() {
        return (double) nextInt(0x10000000) / 0x10000000;
    }

    public <T> T pick(List<T> list) {
        return list.get(nextInt(list.size()));
    }

    public <T> T pick(T[] array) {
        return array[nextInt(array.length)];
    }

    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T temp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, temp);
        }
    }

    public SeededRNG derive(String label) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(seed, "HmacSHA256"));
            hmac.update(label.getBytes());
            return new SeededRNG(hmac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    private void refillBuffer() {
        try {
            byte[] nonce = new byte[16];
            ByteBuffer.wrap(nonce).putLong(8, counter++);

            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, 
                new SecretKeySpec(seed, "AES"),
                new IvParameterSpec(nonce));

            byte[] zeros = new byte[BUFFER_SIZE];
            buffer = cipher.doFinal(zeros);
            offset = 0;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("AES-256-CTR not available", e);
        }
    }

    public byte[] getSeed() {
        return seed.clone();
    }
}
