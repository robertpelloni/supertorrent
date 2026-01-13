package io.supernode.storage.isoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ISOForge")
class ISOForgeTest {
    
    private static final int SECTOR_SIZE = 2048;
    
    @Nested
    @DisplayName("ISO Structure")
    class ISOStructureTests {
        
        @Test
        @DisplayName("should generate valid ISO 9660 primary volume descriptor")
        void validPrimaryVolumeDescriptor() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            int pvdOffset = 16 * SECTOR_SIZE;
            
            assertEquals(0x01, iso[pvdOffset] & 0xFF, "PVD type should be 1");
            
            String identifier = new String(iso, pvdOffset + 1, 5, StandardCharsets.US_ASCII);
            assertEquals("CD001", identifier, "Standard identifier should be CD001");
            
            assertEquals(0x01, iso[pvdOffset + 6] & 0xFF, "Version should be 1");
        }
        
        @Test
        @DisplayName("should have correct system area with boot signature")
        void correctSystemArea() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            assertEquals((byte) 0x55, iso[510], "Boot signature byte 1");
            assertEquals((byte) 0xAA, iso[511], "Boot signature byte 2");
        }
        
        @Test
        @DisplayName("should have volume descriptor terminator")
        void hasVolumeDescriptorTerminator() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            boolean foundTerminator = false;
            for (int sector = 17; sector < 32; sector++) {
                int offset = sector * SECTOR_SIZE;
                if (iso[offset] == (byte) 0xFF) {
                    String id = new String(iso, offset + 1, 5, StandardCharsets.US_ASCII);
                    if ("CD001".equals(id)) {
                        foundTerminator = true;
                        break;
                    }
                }
            }
            
            assertTrue(foundTerminator, "Should have volume descriptor terminator (type 255)");
        }
        
        @ParameterizedTest
        @EnumSource(SizePreset.class)
        @DisplayName("should generate correct size for each preset")
        void correctSizeForPreset(SizePreset preset) {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, preset);
            
            assertEquals(preset.getBytes(), iso.length, 
                "ISO size should match preset " + preset.name());
        }
    }
    
    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {
        
        @Test
        @DisplayName("should produce identical output for same seed")
        void identicalOutputForSameSeed() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso1 = forge.generate(seed, SizePreset.NANO);
            byte[] iso2 = forge.generate(seed, SizePreset.NANO);
            
            assertArrayEquals(iso1, iso2, "Same seed should produce identical ISO");
        }
        
        @Test
        @DisplayName("should produce different output for different seeds")
        void differentOutputForDifferentSeeds() {
            byte[] seed1 = new byte[32];
            byte[] seed2 = new byte[32];
            Arrays.fill(seed1, (byte) 0x42);
            Arrays.fill(seed2, (byte) 0x43);
            
            ISOForge forge = new ISOForge();
            byte[] iso1 = forge.generate(seed1, SizePreset.NANO);
            byte[] iso2 = forge.generate(seed2, SizePreset.NANO);
            
            assertFalse(Arrays.equals(iso1, iso2), "Different seeds should produce different ISOs");
        }
        
        @Test
        @DisplayName("should be reproducible across instances")
        void reproducibleAcrossInstances() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge1 = new ISOForge();
            ISOForge forge2 = new ISOForge();
            
            byte[] iso1 = forge1.generate(seed, SizePreset.NANO);
            byte[] iso2 = forge2.generate(seed, SizePreset.NANO);
            
            assertArrayEquals(iso1, iso2, "Different instances should produce identical output");
        }
    }
    
    @Nested
    @DisplayName("Content Generation")
    class ContentGenerationTests {
        
        @Test
        @DisplayName("should contain recognizable file structure")
        void containsFileStructure() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            String isoStr = new String(iso, StandardCharsets.ISO_8859_1);
            
            boolean hasContent = isoStr.contains("README") || 
                               isoStr.contains("Linux") ||
                               isoStr.contains("config") ||
                               isoStr.contains(".txt");
            
            assertTrue(hasContent, "ISO should contain recognizable file content");
        }
        
        @Test
        @DisplayName("should fill entire preset size")
        void fillsEntireSize() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            int nonZeroCount = 0;
            int sampleSize = Math.min(iso.length, 1024 * 1024);
            
            for (int i = 0; i < sampleSize; i += 1024) {
                for (int j = i; j < Math.min(i + 64, sampleSize); j++) {
                    if (iso[j] != 0) {
                        nonZeroCount++;
                    }
                }
            }
            
            assertTrue(nonZeroCount > sampleSize / 32, 
                "ISO should have substantial non-zero content");
        }
    }
    
    @Nested
    @DisplayName("Sector Operations")
    class SectorOperationTests {
        
        @Test
        @DisplayName("should generate individual sectors correctly")
        void generateSectorsCorrectly() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            byte[] sector16 = forge.generateSector(seed, 16, SizePreset.NANO.getBytes());
            assertEquals(SECTOR_SIZE, sector16.length);
            
            byte[] expected = Arrays.copyOfRange(iso, 16 * SECTOR_SIZE, 17 * SECTOR_SIZE);
            assertArrayEquals(expected, sector16, "Generated sector should match ISO data");
        }
        
        @Test
        @DisplayName("should generate consistent sectors")
        void generateConsistentSectors() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            
            byte[] sector1 = forge.generateSector(seed, 16, SizePreset.NANO.getBytes());
            byte[] sector2 = forge.generateSector(seed, 16, SizePreset.NANO.getBytes());
            
            assertArrayEquals(sector1, sector2, "Same sector should be identical");
        }
        
        @Test
        @DisplayName("should handle sector offset correctly")
        void handleSectorOffset() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge();
            
            byte[] sector0 = forge.generateSector(seed, 0, SizePreset.NANO.getBytes());
            byte[] sector1 = forge.generateSector(seed, 1, SizePreset.NANO.getBytes());
            
            assertFalse(Arrays.equals(sector0, sector1), 
                "Different sectors should have different content");
        }
    }
    
    @Nested
    @DisplayName("El Torito Boot")
    class ElToritoBootTests {
        
        @Test
        @DisplayName("bootable ISO should have boot record")
        void bootableHasBootRecord() {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) 0x42);
            
            ISOForge forge = new ISOForge(true);
            byte[] iso = forge.generate(seed, SizePreset.NANO);
            
            int bootRecordOffset = 17 * SECTOR_SIZE;
            
            assertEquals(0x00, iso[bootRecordOffset] & 0xFF, "Boot record type should be 0");
            
            String bootId = new String(iso, bootRecordOffset + 1, 5, StandardCharsets.US_ASCII);
            assertEquals("CD001", bootId, "Boot record should have CD001 identifier");
            
            String elTorito = new String(iso, bootRecordOffset + 7, 23, StandardCharsets.US_ASCII).trim();
            assertTrue(elTorito.contains("EL TORITO"), "Should identify as El Torito");
        }
    }
}
