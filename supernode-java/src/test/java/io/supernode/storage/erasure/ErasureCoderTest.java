package io.supernode.storage.erasure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ErasureCoder")
class ErasureCoderTest {
    
    private ErasureCoder coder;
    private static final int DATA_SHARDS = 4;
    private static final int PARITY_SHARDS = 2;
    
    @BeforeEach
    void setUp() {
        coder = new ErasureCoder(DATA_SHARDS, PARITY_SHARDS);
    }
    
    @Nested
    @DisplayName("Encoding")
    class EncodingTests {
        
        @Test
        @DisplayName("should produce correct number of shards")
        void correctNumberOfShards() {
            byte[] data = new byte[1024];
            new SecureRandom().nextBytes(data);
            
            ErasureCoder.EncodeResult result = coder.encode(data);
            
            assertEquals(DATA_SHARDS + PARITY_SHARDS, result.shards().length,
                "Should produce data + parity shards");
        }
        
        @Test
        @DisplayName("should produce equal-sized shards")
        void equalSizedShards() {
            byte[] data = new byte[1000];
            new SecureRandom().nextBytes(data);
            
            ErasureCoder.EncodeResult result = coder.encode(data);
            
            int expectedSize = result.shardSize();
            for (byte[] shard : result.shards()) {
                assertEquals(expectedSize, shard.length, "All shards should have same size");
            }
        }
        
        @Test
        @DisplayName("data shards should contain original data")
        void dataShardsContainOriginal() {
            byte[] data = new byte[800];
            new SecureRandom().nextBytes(data);
            
            ErasureCoder.EncodeResult result = coder.encode(data);
            
            byte[] reconstructed = new byte[result.shardSize() * DATA_SHARDS];
            for (int i = 0; i < DATA_SHARDS; i++) {
                System.arraycopy(result.shards()[i], 0, reconstructed, i * result.shardSize(), result.shardSize());
            }
            
            byte[] originalPadded = Arrays.copyOf(data, reconstructed.length);
            assertArrayEquals(originalPadded, reconstructed,
                "Data shards should contain padded original data");
        }
        
        @Test
        @DisplayName("parity shards should differ from data shards")
        void parityShardsAreDifferent() {
            byte[] data = new byte[1024];
            Arrays.fill(data, (byte) 0x42);
            
            ErasureCoder.EncodeResult result = coder.encode(data);
            
            for (int p = DATA_SHARDS; p < result.shards().length; p++) {
                boolean allSame = true;
                for (int d = 0; d < DATA_SHARDS; d++) {
                    if (!Arrays.equals(result.shards()[p], result.shards()[d])) {
                        allSame = false;
                        break;
                    }
                }
                assertFalse(allSame, "Parity shard " + p + " should differ from data shards");
            }
        }
    }
    
    @Nested
    @DisplayName("Decoding - Full Recovery")
    class FullRecoveryTests {
        
        @Test
        @DisplayName("should recover data with all shards present")
        void recoverWithAllShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] allIndices = new int[encoded.shards().length];
            for (int i = 0; i < allIndices.length; i++) allIndices[i] = i;
            
            byte[] recovered = coder.decode(
                encoded.shards(), 
                allIndices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover original with all shards");
        }
        
        @Test
        @DisplayName("should recover data with minimum data shards only")
        void recoverWithMinimumShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] dataOnlyIndices = new int[DATA_SHARDS];
            for (int i = 0; i < DATA_SHARDS; i++) dataOnlyIndices[i] = i;
            
            byte[] recovered = coder.decode(
                encoded.shards(), 
                dataOnlyIndices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover with data shards only");
        }
    }
    
    @Nested
    @DisplayName("Decoding - Lost Shards")
    class LostShardsTests {
        
        @Test
        @DisplayName("should recover with one lost data shard")
        void recoverWithOneLostDataShard() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {1, 2, 3, 4, 5};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            byte[] recovered = coder.decode(
                presentShards, 
                indices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover with one lost data shard");
        }
        
        @Test
        @DisplayName("should recover with two lost data shards")
        void recoverWithTwoLostDataShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {2, 3, 4, 5};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            byte[] recovered = coder.decode(
                presentShards, 
                indices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover with two lost data shards");
        }
        
        @Test
        @DisplayName("should recover with lost parity shards")
        void recoverWithLostParityShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {0, 1, 2, 3};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            byte[] recovered = coder.decode(
                presentShards, 
                indices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover with lost parity shards");
        }
        
        @Test
        @DisplayName("should recover with mixed lost shards")
        void recoverWithMixedLostShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {0, 2, 4, 5};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            byte[] recovered = coder.decode(
                presentShards, 
                indices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should recover with mixed lost shards");
        }
        
        @Test
        @DisplayName("should fail with too many lost shards")
        void failWithTooManyLostShards() {
            byte[] original = new byte[1024];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {0, 1, 2};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            assertThrows(Exception.class, () -> {
                coder.decode(presentShards, indices, encoded.originalSize(), encoded.shardSize());
            }, "Should fail when too many shards are lost");
        }
    }
    
    @Nested
    @DisplayName("Various Data Sizes")
    class DataSizeTests {
        
        @ParameterizedTest
        @CsvSource({
            "1",
            "100",
            "1000",
            "4096",
            "10000",
            "65536",
            "1048576"
        })
        @DisplayName("should handle various data sizes")
        void handleVariousSizes(int size) {
            byte[] original = new byte[size];
            new SecureRandom().nextBytes(original);
            
            ErasureCoder.EncodeResult encoded = coder.encode(original);
            
            int[] indices = {0, 1, 4, 5};
            byte[][] presentShards = new byte[encoded.shards().length][];
            for (int idx : indices) {
                presentShards[idx] = encoded.shards()[idx];
            }
            
            byte[] recovered = coder.decode(
                presentShards, 
                indices, 
                encoded.originalSize(), 
                encoded.shardSize()
            );
            
            assertArrayEquals(original, recovered, "Should handle size " + size);
        }
    }
    
    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {
        
        @Test
        @DisplayName("should support different shard configurations")
        void differentConfigurations() {
            int[][] configs = {{2, 1}, {4, 2}, {6, 3}, {8, 4}, {10, 4}};
            
            for (int[] config : configs) {
                int dataShards = config[0];
                int parityShards = config[1];
                
                ErasureCoder ec = new ErasureCoder(dataShards, parityShards);
                
                byte[] original = new byte[1024];
                new SecureRandom().nextBytes(original);
                
                ErasureCoder.EncodeResult encoded = ec.encode(original);
                assertEquals(dataShards + parityShards, encoded.shards().length);
                
                int[] indices = new int[dataShards];
                for (int i = 0; i < dataShards; i++) indices[i] = i;
                
                byte[] recovered = ec.decode(
                    encoded.shards(), 
                    indices, 
                    encoded.originalSize(), 
                    encoded.shardSize()
                );
                
                assertArrayEquals(original, recovered, 
                    "Config " + dataShards + "+" + parityShards + " should work");
            }
        }
        
        @Test
        @DisplayName("should report correct shard counts")
        void correctShardCounts() {
            assertEquals(DATA_SHARDS, coder.getDataShards());
            assertEquals(PARITY_SHARDS, coder.getParityShards());
            assertEquals(DATA_SHARDS + PARITY_SHARDS, coder.getTotalShards());
        }
    }
    
    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {
        
        @Test
        @DisplayName("encoding should be deterministic")
        void encodingDeterministic() {
            byte[] data = new byte[1024];
            Arrays.fill(data, (byte) 0x42);
            
            ErasureCoder.EncodeResult enc1 = coder.encode(data);
            ErasureCoder.EncodeResult enc2 = coder.encode(data);
            
            assertEquals(enc1.shards().length, enc2.shards().length);
            for (int i = 0; i < enc1.shards().length; i++) {
                assertArrayEquals(enc1.shards()[i], enc2.shards()[i],
                    "Shard " + i + " should be identical");
            }
        }
    }
    
    @Nested
    @DisplayName("GF(2^8) Math")
    class GaloisFieldTests {
        
        @Test
        @DisplayName("parity should change when data changes")
        void parityChangesWithData() {
            byte[] data1 = new byte[1024];
            byte[] data2 = new byte[1024];
            Arrays.fill(data1, (byte) 0x00);
            Arrays.fill(data2, (byte) 0xFF);
            
            ErasureCoder.EncodeResult enc1 = coder.encode(data1);
            ErasureCoder.EncodeResult enc2 = coder.encode(data2);
            
            for (int p = 0; p < enc1.shards().length; p++) {
                assertFalse(Arrays.equals(enc1.shards()[p], enc2.shards()[p]),
                    "All shards (data + parity) should differ for different data");
            }
        }
    }
}
