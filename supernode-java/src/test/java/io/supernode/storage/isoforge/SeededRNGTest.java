package io.supernode.storage.isoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SeededRNG - AES-256-CTR based deterministic PRNG.
 */
@DisplayName("SeededRNG")
class SeededRNGTest {
    
    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {
        
        @Test
        @DisplayName("should produce identical output for same seed")
        void identicalOutputForSameSeed() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            SeededRNG rng1 = new SeededRNG(seed);
            SeededRNG rng2 = new SeededRNG(seed);
            
            byte[] output1 = rng1.bytes(1024);
            byte[] output2 = rng2.bytes(1024);
            
            assertArrayEquals(output1, output2, "Same seed should produce identical output");
        }
        
        @Test
        @DisplayName("should produce different output for different seeds")
        void differentOutputForDifferentSeeds() {
            byte[] seed1 = new byte[32];
            byte[] seed2 = new byte[32];
            Arrays.fill(seed1, (byte) 0x42);
            Arrays.fill(seed2, (byte) 0x43);
            
            SeededRNG rng1 = new SeededRNG(seed1);
            SeededRNG rng2 = new SeededRNG(seed2);
            
            byte[] output1 = rng1.bytes(1024);
            byte[] output2 = rng2.bytes(1024);
            
            assertFalse(Arrays.equals(output1, output2), "Different seeds should produce different output");
        }
        
        @Test
        @DisplayName("should maintain state across multiple calls")
        void maintainStateAcrossCalls() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            SeededRNG rng1 = new SeededRNG(seed);
            byte[] first1 = rng1.bytes(100);
            byte[] second1 = rng1.bytes(100);
            
            SeededRNG rng2 = new SeededRNG(seed);
            byte[] first2 = rng2.bytes(100);
            byte[] second2 = rng2.bytes(100);
            
            assertArrayEquals(first1, first2, "First chunks should match");
            assertArrayEquals(second1, second2, "Second chunks should match");
            assertFalse(Arrays.equals(first1, second1), "Sequential chunks should differ");
        }
        
        @Test
        @DisplayName("should produce consistent output regardless of chunk size")
        void consistentOutputRegardlessOfChunkSize() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            // Get 256 bytes in one call
            SeededRNG rng1 = new SeededRNG(seed);
            byte[] oneChunk = rng1.bytes(256);
            
            // Get 256 bytes in multiple calls
            SeededRNG rng2 = new SeededRNG(seed);
            byte[] multiChunk = new byte[256];
            byte[] chunk1 = rng2.bytes(64);
            byte[] chunk2 = rng2.bytes(64);
            byte[] chunk3 = rng2.bytes(64);
            byte[] chunk4 = rng2.bytes(64);
            
            System.arraycopy(chunk1, 0, multiChunk, 0, 64);
            System.arraycopy(chunk2, 0, multiChunk, 64, 64);
            System.arraycopy(chunk3, 0, multiChunk, 128, 64);
            System.arraycopy(chunk4, 0, multiChunk, 192, 64);
            
            assertArrayEquals(oneChunk, multiChunk, "Output should be identical regardless of chunking");
        }
    }
    
    @Nested
    @DisplayName("Integer Generation")
    class IntegerGenerationTests {
        
        @Test
        @DisplayName("should generate integers within range")
        void integersWithinRange() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            SeededRNG rng = new SeededRNG(seed);
            
            for (int i = 0; i < 1000; i++) {
                int value = rng.nextInt(100);
                assertTrue(value >= 0 && value < 100, 
                    "Value " + value + " should be in range [0, 100)");
            }
        }
        
        @Test
        @DisplayName("should produce uniform distribution")
        void uniformDistribution() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            SeededRNG rng = new SeededRNG(seed);
            
            int[] buckets = new int[10];
            int samples = 10000;
            
            for (int i = 0; i < samples; i++) {
                int value = rng.nextInt(10);
                buckets[value]++;
            }
            
            // Each bucket should have roughly samples/10 = 1000 entries
            // Allow 20% variance
            int expected = samples / 10;
            int tolerance = expected / 5;
            
            for (int i = 0; i < 10; i++) {
                assertTrue(Math.abs(buckets[i] - expected) < tolerance,
                    "Bucket " + i + " has " + buckets[i] + " entries, expected ~" + expected);
            }
        }
        
        @Test
        @DisplayName("should be deterministic for integers too")
        void deterministicIntegers() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            SeededRNG rng1 = new SeededRNG(seed);
            SeededRNG rng2 = new SeededRNG(seed);
            
            for (int i = 0; i < 100; i++) {
                assertEquals(rng1.nextInt(1000), rng2.nextInt(1000),
                    "Integer " + i + " should match");
            }
        }
    }
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("should handle zero-length request")
        void handleZeroLength() {
            byte[] seed = new byte[32];
            SeededRNG rng = new SeededRNG(seed);
            
            byte[] result = rng.bytes(0);
            assertEquals(0, result.length, "Zero-length request should return empty array");
        }
        
        @Test
        @DisplayName("should handle large requests")
        void handleLargeRequests() {
            byte[] seed = new byte[32];
            SeededRNG rng = new SeededRNG(seed);
            
            // Request 1MB
            byte[] result = rng.bytes(1024 * 1024);
            assertEquals(1024 * 1024, result.length, "Should return requested size");
            
            // Verify it's not all zeros
            boolean hasNonZero = false;
            for (byte b : result) {
                if (b != 0) {
                    hasNonZero = true;
                    break;
                }
            }
            assertTrue(hasNonZero, "Output should contain non-zero bytes");
        }
        
        @Test
        @DisplayName("should work with all-zero seed")
        void allZeroSeed() {
            byte[] seed = new byte[32]; // All zeros
            SeededRNG rng = new SeededRNG(seed);
            
            byte[] output = rng.bytes(256);
            
            // Should still produce output (not throw)
            assertEquals(256, output.length);
            
            // Verify determinism with all-zero seed
            SeededRNG rng2 = new SeededRNG(new byte[32]);
            assertArrayEquals(output, rng2.bytes(256));
        }
        
        @Test
        @DisplayName("should work with all-ones seed")
        void allOnesSeed() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0xFF);
            
            SeededRNG rng = new SeededRNG(seed);
            byte[] output = rng.bytes(256);
            
            assertEquals(256, output.length);
            
            // Verify it differs from all-zero seed
            SeededRNG rngZero = new SeededRNG(new byte[32]);
            assertFalse(Arrays.equals(output, rngZero.bytes(256)));
        }
    }
    
    @Nested
    @DisplayName("Statistical Quality")
    class StatisticalQualityTests {
        
        @Test
        @DisplayName("should have reasonable entropy")
        void reasonableEntropy() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            SeededRNG rng = new SeededRNG(seed);
            
            byte[] output = rng.bytes(10000);
            
            // Count unique bytes (should see most of 256 possible values)
            Set<Byte> uniqueBytes = new HashSet<>();
            for (byte b : output) {
                uniqueBytes.add(b);
            }
            
            // With 10000 bytes, we should see at least 250 of 256 possible values
            assertTrue(uniqueBytes.size() >= 250,
                "Should see at least 250 unique byte values, got " + uniqueBytes.size());
        }
        
        @Test
        @DisplayName("should have balanced bit distribution")
        void balancedBitDistribution() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            SeededRNG rng = new SeededRNG(seed);
            
            byte[] output = rng.bytes(10000);
            
            int ones = 0;
            int total = output.length * 8;
            
            for (byte b : output) {
                ones += Integer.bitCount(b & 0xFF);
            }
            
            // Should be close to 50% ones
            double ratio = (double) ones / total;
            assertTrue(ratio > 0.48 && ratio < 0.52,
                "Bit ratio should be close to 0.5, got " + ratio);
        }
    }
}
