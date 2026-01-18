package io.supernode.storage.erasure;

import io.supernode.storage.erasure.ErasureCoder.EncodeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to investigate parity shard generation.
 * Tests whether parity shards actually differ when encoding different data patterns.
 */
class ErasureCoderParityDiagnosticTest {

    private static final int DATA_SHARDS = 4;
    private static final int PARITY_SHARDS = 2;

    @Test
    @DisplayName("should produce different parity for all-zero data")
    void allZeroData() {
        ErasureCoder coder = new ErasureCoder(DATA_SHARDS, PARITY_SHARDS);

        byte[] data1 = new byte[1024];
        Arrays.fill(data1, (byte) 0x00);

        byte[] data2 = new byte[1024];
        Arrays.fill(data2, (byte) 0xFF);

        EncodeResult enc1 = coder.encode(data1);
        EncodeResult enc2 = coder.encode(data2);

        System.out.println("Data 1 (all 0x00):");
        System.out.println("  Encoded: " + Arrays.toString(enc1.shards()));
        System.out.println("  Shard size: " + enc1.shardSize());
        System.out.println("  Data shards: " + DATA_SHARDS);

        System.out.println("\nData 2 (all 0xFF):");
        System.out.println("  Encoded: " + Arrays.toString(enc2.shards()));
        System.out.println("  Shard size: " + enc2.shardSize());
        System.out.println("  Data shards: " + DATA_SHARDS);

        System.out.println("\nChecking parity shards differ:");
        for (int p = DATA_SHARDS; p < enc1.shards().length; p++) {
            boolean equal = Arrays.equals(enc1.shards()[p], enc2.shards()[p]);
            System.out.println("  Shard " + p + " equal: " + equal);
        }

        // All parity shards should differ
        for (int p = DATA_SHARDS; p < enc1.shards().length; p++) {
            assertFalse(Arrays.equals(enc1.shards()[p], enc2.shards()[p]),
                "Parity shard " + p + " should differ for different data");
        }
    }

    @Test
    @DisplayName("should produce identical parity for identical data")
    void identicalData() {
        ErasureCoder coder = new ErasureCoder(DATA_SHARDS, PARITY_SHARDS);

        byte[] data1 = new byte[1024];
        new java.security.SecureRandom().nextBytes(data1);

        EncodeResult enc1 = coder.encode(data1);
        EncodeResult enc2 = coder.encode(data1);

        System.out.println("Identical data - checking parity shards:");
        for (int p = DATA_SHARDS; p < enc1.shards().length; p++) {
            boolean equal = Arrays.equals(enc1.shards()[p], enc2.shards()[p]);
            System.out.println("  Shard " + p + " equal: " + equal);
        }

        // All parity shards should be identical
        for (int p = DATA_SHARDS; p < enc1.shards().length; p++) {
            assertTrue(Arrays.equals(enc1.shards()[p], enc2.shards()[p]),
                "Parity shard " + p + " should be identical for same data");
        }
    }

    @Test
    @DisplayName("should show matrix generation details")
    void showMatrixDetails() {
        ErasureCoder coder = new ErasureCoder(DATA_SHARDS, PARITY_SHARDS);

        byte[] data1 = new byte[1024];
        Arrays.fill(data1, (byte) 0x00);

        byte[] data2 = new byte[1024];
        Arrays.fill(data2, (byte) 0xFF);

        EncodeResult enc1 = coder.encode(data1);
        EncodeResult enc2 = coder.encode(data2);

        System.out.println("\nMatrix coefficients for data1 (0x00):");
        for (int i = 0; i < enc1.shards().length; i++) {
            byte[] shard = enc1.shards()[i];
            System.out.println("  Shard " + i + ": " + (shard[0] & 0xFF));
        }

        System.out.println("\nMatrix coefficients for data2 (0xFF):");
        for (int i = 0; i < enc2.shards().length; i++) {
            byte[] shard = enc2.shards()[i];
            System.out.println("  Shard " + i + ": " + (shard[0] & 0xFF));
        }
    }
}
