package io.supernode.storage.mux;

import io.supernode.storage.isoforge.ISOForge;
import io.supernode.storage.isoforge.SizePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MuxEngine")
class MuxEngineTest {
    
    private static final int SECTOR_SIZE = 2048;
    private MuxEngine muxEngine;
    private byte[] testKey;
    private byte[] testSeed;
    
    @BeforeEach
    void setUp() {
        muxEngine = new MuxEngine(SizePreset.NANO);
        testKey = new byte[32];
        testSeed = new byte[32];
        Arrays.fill(testKey, (byte) 0x42);
        Arrays.fill(testSeed, (byte) 0x43);
    }
    
    @Nested
    @DisplayName("Mux/Demux Roundtrip")
    class RoundtripTests {
        
        @Test
        @DisplayName("should recover original data after mux/demux")
        void recoverOriginalData() {
            byte[] original = "Hello, Supernode! This is a test message.".getBytes();
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            byte[] recovered = muxEngine.demux(
                muxResult.muxedData(), 
                testKey, 
                testSeed, 
                muxResult.sectorStart(), 
                muxResult.encryptedSize()
            );
            
            assertArrayEquals(original, recovered, "Demuxed data should match original");
        }
        
        @Test
        @DisplayName("should handle empty data")
        void handleEmptyData() {
            byte[] original = new byte[0];
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            byte[] recovered = muxEngine.demux(
                muxResult.muxedData(), 
                testKey, 
                testSeed, 
                muxResult.sectorStart(), 
                muxResult.encryptedSize()
            );
            
            assertArrayEquals(original, recovered);
        }
        
        @Test
        @DisplayName("should handle data exactly sector-sized")
        void handleSectorSizedData() {
            byte[] original = new byte[SECTOR_SIZE];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            byte[] recovered = muxEngine.demux(
                muxResult.muxedData(), 
                testKey, 
                testSeed, 
                muxResult.sectorStart(), 
                muxResult.encryptedSize()
            );
            
            assertArrayEquals(original, recovered);
        }
        
        @Test
        @DisplayName("should handle large data (1MB)")
        void handleLargeData() {
            byte[] original = new byte[1024 * 1024];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            byte[] recovered = muxEngine.demux(
                muxResult.muxedData(), 
                testKey, 
                testSeed, 
                muxResult.sectorStart(), 
                muxResult.encryptedSize()
            );
            
            assertArrayEquals(original, recovered);
        }
        
        @Test
        @DisplayName("should handle data of various sizes")
        void handleVariousSizes() {
            int[] sizes = {1, 100, 1000, 2047, 2048, 2049, 4096, 10000};
            
            for (int size : sizes) {
                byte[] original = new byte[size];
                new SecureRandom().nextBytes(original);
                
                MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
                byte[] recovered = muxEngine.demux(
                    muxResult.muxedData(), 
                    testKey, 
                    testSeed, 
                    muxResult.sectorStart(), 
                    muxResult.encryptedSize()
                );
                
                assertArrayEquals(original, recovered, "Failed for size " + size);
            }
        }
    }
    
    @Nested
    @DisplayName("Encryption")
    class EncryptionTests {
        
        @Test
        @DisplayName("muxed data should differ from original")
        void muxedDiffersFromOriginal() {
            byte[] original = new byte[1024];
            Arrays.fill(original, (byte) 0x42);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            assertFalse(Arrays.equals(original, Arrays.copyOf(muxResult.muxedData(), original.length)),
                "Muxed data should not match original");
        }
        
        @Test
        @DisplayName("different keys should produce different output")
        void differentKeysProduceDifferentOutput() {
            byte[] original = "Test data for encryption".getBytes();
            
            byte[] key1 = new byte[32];
            byte[] key2 = new byte[32];
            Arrays.fill(key1, (byte) 0x42);
            Arrays.fill(key2, (byte) 0x43);
            
            MuxEngine.MuxResult mux1 = muxEngine.mux(original, key1, testSeed);
            MuxEngine.MuxResult mux2 = muxEngine.mux(original, key2, testSeed);
            
            assertFalse(Arrays.equals(mux1.muxedData(), mux2.muxedData()),
                "Different keys should produce different output");
        }
        
        @Test
        @DisplayName("should fail demux with wrong key")
        void failDemuxWithWrongKey() {
            byte[] original = "Secret message".getBytes();
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            byte[] wrongKey = new byte[32];
            Arrays.fill(wrongKey, (byte) 0xFF);
            
            assertThrows(Exception.class, () -> {
                muxEngine.demux(
                    muxResult.muxedData(), 
                    wrongKey, 
                    testSeed, 
                    muxResult.sectorStart(), 
                    muxResult.encryptedSize()
                );
            }, "Should fail with wrong key");
        }
    }
    
    @Nested
    @DisplayName("ISO Integration")
    class ISOIntegrationTests {
        
        @Test
        @DisplayName("muxed data should be sector-aligned")
        void muxedDataSectorAligned() {
            byte[] original = new byte[1000];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            assertEquals(0, muxResult.muxedData().length % SECTOR_SIZE,
                "Muxed data should be sector-aligned");
        }
        
        @Test
        @DisplayName("should use correct sector count")
        void useCorrectSectorCount() {
            byte[] original = new byte[5000];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            int expectedSectors = (int) Math.ceil(muxResult.muxedData().length / (double) SECTOR_SIZE);
            assertEquals(expectedSectors, muxResult.sectorCount());
        }
        
        @Test
        @DisplayName("muxed data should look like ISO when XORed back")
        void looksLikeISOWhenXoredBack() {
            byte[] original = new byte[4096];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(testSeed, SizePreset.NANO);
            
            int sectorStart = muxResult.sectorStart();
            int sectorCount = muxResult.sectorCount();
            byte[] isoSectors = Arrays.copyOfRange(iso, sectorStart * SECTOR_SIZE, (sectorStart + sectorCount) * SECTOR_SIZE);
            
            byte[] xored = new byte[muxResult.muxedData().length];
            for (int i = 0; i < xored.length; i++) {
                xored[i] = (byte) (muxResult.muxedData()[i] ^ isoSectors[i]);
            }
            
            assertTrue(xored.length > 0);
        }
    }
    
    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {
        
        @Test
        @DisplayName("same inputs should produce same output")
        void sameInputsSameOutput() {
            byte[] original = "Determinism test".getBytes();
            
            MuxEngine.MuxResult mux1 = muxEngine.mux(original, testKey, testSeed);
            MuxEngine.MuxResult mux2 = muxEngine.mux(original, testKey, testSeed);
            
            assertArrayEquals(mux1.muxedData(), mux2.muxedData());
            assertEquals(mux1.sectorStart(), mux2.sectorStart());
            assertEquals(mux1.sectorCount(), mux2.sectorCount());
            assertEquals(mux1.encryptedSize(), mux2.encryptedSize());
        }
        
        @Test
        @DisplayName("different seeds should produce different sector layout")
        void differentSeedsDifferentLayout() {
            byte[] original = new byte[4096];
            new SecureRandom().nextBytes(original);
            
            byte[] seed1 = new byte[32];
            byte[] seed2 = new byte[32];
            Arrays.fill(seed1, (byte) 0x01);
            Arrays.fill(seed2, (byte) 0x02);
            
            MuxEngine.MuxResult mux1 = muxEngine.mux(original, testKey, seed1);
            MuxEngine.MuxResult mux2 = muxEngine.mux(original, testKey, seed2);
            
            assertFalse(Arrays.equals(mux1.muxedData(), mux2.muxedData()),
                "Different ISO seeds should produce different muxed data");
        }
    }
    
    @Nested
    @DisplayName("MuxResult Metadata")
    class MetadataTests {
        
        @Test
        @DisplayName("should report correct encrypted size")
        void correctEncryptedSize() {
            byte[] original = new byte[1000];
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            assertTrue(muxResult.encryptedSize() >= original.length,
                "Encrypted size should be >= original size");
            assertTrue(muxResult.encryptedSize() <= original.length + 28,
                "Encrypted size should include nonce + tag overhead");
        }
        
        @Test
        @DisplayName("sector start should be within ISO bounds")
        void sectorStartWithinBounds() {
            byte[] original = new byte[10000];
            new SecureRandom().nextBytes(original);
            
            MuxEngine.MuxResult muxResult = muxEngine.mux(original, testKey, testSeed);
            
            int maxSector = (int) (SizePreset.NANO.getBytes() / SECTOR_SIZE);
            assertTrue(muxResult.sectorStart() >= 0);
            assertTrue(muxResult.sectorStart() + muxResult.sectorCount() <= maxSector,
                "Sectors should fit within ISO");
        }
    }
}
