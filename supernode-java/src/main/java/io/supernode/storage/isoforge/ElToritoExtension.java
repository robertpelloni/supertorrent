package io.supernode.storage.isoforge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ElToritoExtension {
    public static final int SECTOR_SIZE = 2048;
    public static final int BOOT_RECORD_SECTOR = 17;
    
    private final int bootCatalogSector;
    private final int bootImageSector;
    private final int bootImageSectors;

    public ElToritoExtension() {
        this(19, 20, 4);
    }

    public ElToritoExtension(int bootCatalogSector, int bootImageSector, int bootImageSectors) {
        this.bootCatalogSector = bootCatalogSector;
        this.bootImageSector = bootImageSector;
        this.bootImageSectors = bootImageSectors;
    }

    public byte[] generateBootRecord() {
        byte[] record = new byte[SECTOR_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        
        record[0] = 0x00;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, record, 1, 5);
        record[6] = 0x01;
        System.arraycopy("EL TORITO SPECIFICATION".getBytes(StandardCharsets.US_ASCII), 0, record, 7, 23);
        buf.putInt(0x47, bootCatalogSector);
        
        return record;
    }

    public byte[] generateBootCatalog(SeededRNG rng) {
        byte[] catalog = new byte[SECTOR_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(catalog).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;

        catalog[offset] = 0x01;
        catalog[offset + 1] = 0x00; // Platform x86

        List<String> idStrings = List.of(
            "SUPERNODE BOOT", "MESH LINUX", "FREEDOM OS",
            "SOVEREIGN BOOT", "LIBERTY LINUX"
        );
        String idString = rng.pick(idStrings);
        byte[] idBytes = padString(idString, 24);
        System.arraycopy(idBytes, 0, catalog, offset + 4, 24);

        int checksum = calculateValidationChecksum(catalog, offset, 32);
        buf.putShort(offset + 28, (short) checksum);

        catalog[offset + 30] = 0x55;
        catalog[offset + 31] = (byte) 0xAA;

        offset += 32;

        catalog[offset] = (byte) 0x88;
        catalog[offset + 1] = 0x00; // No emulation
        buf.putShort(offset + 6, (short) bootImageSectors);
        buf.putInt(offset + 8, bootImageSector);

        return catalog;
    }

    public byte[] generateBootImage(SeededRNG rng, BootType type) {
        if (type == BootType.EFI) {
            return createEfiBootImage(rng);
        }
        return createBiosBootImage(rng);
    }

    public byte[] generateBootImage(SeededRNG rng) {
        return generateBootImage(rng, BootType.BIOS);
    }

    private byte[] createBiosBootImage(SeededRNG rng) {
        byte[] image = new byte[bootImageSectors * 512];
        
        byte[] bootCode = {
            (byte) 0xFA,
            0x31, (byte) 0xC0,
            (byte) 0x8E, (byte) 0xD8,
            (byte) 0x8E, (byte) 0xC0,
            (byte) 0x8E, (byte) 0xD0,
            (byte) 0xBC, 0x00, 0x7C,
            (byte) 0xFB,
            (byte) 0xBE, 0x30, 0x7C,
            (byte) 0xAC,
            0x08, (byte) 0xC0,
            0x74, 0x09,
            (byte) 0xB4, 0x0E,
            (byte) 0xBB, 0x07, 0x00,
            (byte) 0xCD, 0x10,
            (byte) 0xEB, (byte) 0xF2,
            (byte) 0xFA,
            (byte) 0xF4,
            (byte) 0xEB, (byte) 0xFC
        };
        System.arraycopy(bootCode, 0, image, 0, bootCode.length);

        List<String> messages = List.of(
            "Supernode Boot v1.0",
            "Mesh Linux Loader",
            "Freedom OS Starting",
            "Sovereign Boot Init",
            "Decentralized Boot"
        );
        String message = rng.pick(messages) + "\r\n";
        System.arraycopy(message.getBytes(StandardCharsets.US_ASCII), 0, image, 0x30, message.length());

        image[510] = 0x55;
        image[511] = (byte) 0xAA;

        byte[] padding = rng.bytes(image.length - 512);
        System.arraycopy(padding, 0, image, 512, padding.length);

        return image;
    }

    private byte[] createEfiBootImage(SeededRNG rng) {
        byte[] image = new byte[bootImageSectors * SECTOR_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);

        image[0] = 'M';
        image[1] = 'Z';
        buf.putShort(2, (short) 0x90);
        buf.putShort(4, (short) 3);
        buf.putShort(8, (short) 4);
        buf.putShort(12, (short) 0xFFFF);
        buf.putShort(16, (short) 0xB8);
        buf.putInt(60, 0x80);

        int peOffset = 0x80;
        System.arraycopy("PE\0\0".getBytes(StandardCharsets.US_ASCII), 0, image, peOffset, 4);
        buf.putShort(peOffset + 4, (short) 0x8664);
        buf.putShort(peOffset + 6, (short) 1);
        buf.putShort(peOffset + 20, (short) 0xF0);
        buf.putShort(peOffset + 22, (short) 0x22);

        byte[] padding = rng.bytes(image.length - 256);
        System.arraycopy(padding, 0, image, 256, padding.length);

        return image;
    }

    private int calculateValidationChecksum(byte[] data, int start, int length) {
        int sum = 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = start; i < start + length; i += 2) {
            sum += buf.getShort(i) & 0xFFFF;
        }
        return (0x10000 - (sum & 0xFFFF)) & 0xFFFF;
    }

    private byte[] padString(String s, int length) {
        byte[] result = new byte[length];
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        return result;
    }

    public int getBootCatalogSector() {
        return bootCatalogSector;
    }

    public int getBootImageSector() {
        return bootImageSector;
    }

    public int getBootImageSectors() {
        return bootImageSectors;
    }

    public enum BootType {
        BIOS, EFI
    }
}
