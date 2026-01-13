package io.supernode.storage.isoforge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

public class ISOForge {
    public static final int SECTOR_SIZE = 2048;
    private static final int SYSTEM_AREA_SECTORS = 16;
    private static final int PRIMARY_VOLUME_DESCRIPTOR_SECTOR = 16;
    private static final int VOLUME_DESCRIPTOR_SET_TERMINATOR = 255;

    private final boolean bootable;
    private final ElToritoExtension elTorito;

    public ISOForge() {
        this(false);
    }

    public ISOForge(boolean bootable) {
        this.bootable = bootable;
        this.elTorito = bootable ? new ElToritoExtension() : null;
    }

    public byte[] generate(byte[] seed, SizePreset size) {
        return generate(seed, size.getBytes());
    }

    public byte[] generate(byte[] seed, long targetSize) {
        if (targetSize < SECTOR_SIZE * 20) {
            throw new IllegalArgumentException("Invalid size. Must be at least 40KB");
        }

        SeededRNG rng = new SeededRNG(seed);
        int totalSectors = (int) (targetSize / SECTOR_SIZE);
        byte[] iso = new byte[totalSectors * SECTOR_SIZE];

        byte[] systemArea = generateSystemArea(rng.derive("system-area"));
        System.arraycopy(systemArea, 0, iso, 0, systemArea.length);

        byte[] pvd = createPrimaryVolumeDescriptor(rng.derive("pvd"), totalSectors);
        System.arraycopy(pvd, 0, iso, PRIMARY_VOLUME_DESCRIPTOR_SECTOR * SECTOR_SIZE, pvd.length);

        if (bootable && elTorito != null) {
            byte[] bootRecord = elTorito.generateBootRecord();
            System.arraycopy(bootRecord, 0, iso, 17 * SECTOR_SIZE, bootRecord.length);

            byte[] vdt = createVolumeDescriptorTerminator();
            System.arraycopy(vdt, 0, iso, 18 * SECTOR_SIZE, vdt.length);

            byte[] bootCatalog = elTorito.generateBootCatalog(rng.derive("boot-catalog"));
            System.arraycopy(bootCatalog, 0, iso, elTorito.getBootCatalogSector() * SECTOR_SIZE, bootCatalog.length);

            byte[] bootImage = elTorito.generateBootImage(rng.derive("boot-image"));
            System.arraycopy(bootImage, 0, iso, elTorito.getBootImageSector() * SECTOR_SIZE, bootImage.length);

            byte[] rootDir = generateRootDirectory(rng.derive("root-dir"));
            System.arraycopy(rootDir, 0, iso, 24 * SECTOR_SIZE, rootDir.length);

            SeededRNG contentRng = rng.derive("content");
            int contentStart = 25 * SECTOR_SIZE;
            int contentSize = totalSectors * SECTOR_SIZE - contentStart;
            byte[] content = contentRng.bytes(contentSize);
            System.arraycopy(content, 0, iso, contentStart, contentSize);
        } else {
            byte[] vdt = createVolumeDescriptorTerminator();
            System.arraycopy(vdt, 0, iso, 17 * SECTOR_SIZE, vdt.length);

            byte[] rootDir = generateRootDirectory(rng.derive("root-dir"));
            System.arraycopy(rootDir, 0, iso, 18 * SECTOR_SIZE, rootDir.length);

            SeededRNG contentRng = rng.derive("content");
            int contentStart = 19 * SECTOR_SIZE;
            int contentSize = totalSectors * SECTOR_SIZE - contentStart;
            byte[] content = contentRng.bytes(contentSize);
            System.arraycopy(content, 0, iso, contentStart, contentSize);
        }

        return iso;
    }

    public byte[] generateSector(byte[] seed, int sectorIndex, long totalSize) {
        int totalSectors = (int) (totalSize / SECTOR_SIZE);

        if (sectorIndex < 0 || sectorIndex >= totalSectors) {
            throw new IllegalArgumentException("Sector " + sectorIndex + " out of range [0, " + totalSectors + ")");
        }

        SeededRNG rng = new SeededRNG(seed);

        if (sectorIndex < SYSTEM_AREA_SECTORS) {
            byte[] systemArea = generateSystemArea(rng.derive("system-area"));
            byte[] sector = new byte[SECTOR_SIZE];
            System.arraycopy(systemArea, sectorIndex * SECTOR_SIZE, sector, 0, SECTOR_SIZE);
            return sector;
        }

        if (sectorIndex == PRIMARY_VOLUME_DESCRIPTOR_SECTOR) {
            return createPrimaryVolumeDescriptor(rng.derive("pvd"), totalSectors);
        }

        if (bootable && elTorito != null) {
            if (sectorIndex == 17) return elTorito.generateBootRecord();
            if (sectorIndex == 18) return createVolumeDescriptorTerminator();
            if (sectorIndex == elTorito.getBootCatalogSector()) {
                return elTorito.generateBootCatalog(rng.derive("boot-catalog"));
            }
            if (sectorIndex >= elTorito.getBootImageSector() && 
                sectorIndex < elTorito.getBootImageSector() + 4) {
                byte[] bootImage = elTorito.generateBootImage(rng.derive("boot-image"));
                int offset = (sectorIndex - elTorito.getBootImageSector()) * SECTOR_SIZE;
                byte[] sector = new byte[SECTOR_SIZE];
                System.arraycopy(bootImage, offset, sector, 0, SECTOR_SIZE);
                return sector;
            }
            if (sectorIndex == 24) return generateRootDirectory(rng.derive("root-dir"));

            SeededRNG contentRng = rng.derive("content");
            int sectorOffset = (sectorIndex - 25) * SECTOR_SIZE;
            contentRng.bytes(sectorOffset);
            return contentRng.bytes(SECTOR_SIZE);
        }

        if (sectorIndex == 17) return createVolumeDescriptorTerminator();
        if (sectorIndex == 18) return generateRootDirectory(rng.derive("root-dir"));

        SeededRNG contentRng = rng.derive("content");
        int sectorOffset = (sectorIndex - 19) * SECTOR_SIZE;
        contentRng.bytes(sectorOffset);
        return contentRng.bytes(SECTOR_SIZE);
    }

    public String getHash(byte[] seed, SizePreset size) {
        return getHash(seed, size.getBytes());
    }

    public String getHash(byte[] seed, long targetSize) {
        int totalSectors = (int) (targetSize / SECTOR_SIZE);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < totalSectors; i++) {
                byte[] sector = generateSector(seed, i, targetSize);
                digest.update(sector);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private byte[] generateSystemArea(SeededRNG rng) {
        byte[] systemArea = new byte[SYSTEM_AREA_SECTORS * SECTOR_SIZE];

        systemArea[510] = 0x55;
        systemArea[511] = (byte) 0xAA;

        byte[] bootCode = rng.bytes(SYSTEM_AREA_SECTORS * SECTOR_SIZE - 512);
        System.arraycopy(bootCode, 0, systemArea, 512, bootCode.length);

        return systemArea;
    }

    private byte[] createPrimaryVolumeDescriptor(SeededRNG rng, int totalSectors) {
        byte[] pvd = new byte[SECTOR_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(pvd);
        LocalDateTime date = generateDeterministicDate(rng);

        pvd[0] = 1;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, pvd, 1, 5);
        pvd[6] = 1;

        writeAsciiPadded(pvd, 8, "LINUX", 32);

        List<String> volumeNames = List.of(
            "SUPERNODE_LINUX", "MESH_LINUX", "FREEDOM_LINUX",
            "SOVEREIGN_LINUX", "LIBERTY_LINUX", "DECENTRALIX"
        );
        String volumeId = rng.pick(volumeNames) + "_" + 
            HexFormat.of().formatHex(rng.bytes(4)).toUpperCase();
        writeAsciiPadded(pvd, 40, volumeId.substring(0, Math.min(32, volumeId.length())), 32);

        writeBothEndian32(buf, 80, totalSectors);
        writeBothEndian16(buf, 120, 1);
        writeBothEndian16(buf, 124, 1);
        writeBothEndian16(buf, 128, SECTOR_SIZE);
        writeBothEndian32(buf, 132, 0);

        int rootDirSector = 18;
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(156 + 2, rootDirSector);
        buf.order(ByteOrder.BIG_ENDIAN).putInt(156 + 6, rootDirSector);

        byte[] rootDirRecord = createDirectoryRecord("\0", rootDirSector, SECTOR_SIZE, true, date);
        System.arraycopy(rootDirRecord, 0, pvd, 156, rootDirRecord.length);

        writeAsciiPadded(pvd, 190, "", 128);
        writeAsciiPadded(pvd, 318, "SUPERNODE PROJECT", 128);
        writeAsciiPadded(pvd, 446, "ISO FORGE", 128);
        writeAsciiPadded(pvd, 574, "SUPERNODE", 128);

        String dateStr = formatISODate(date);
        System.arraycopy(dateStr.getBytes(StandardCharsets.US_ASCII), 0, pvd, 813, 16);
        pvd[830] = 0;
        System.arraycopy(dateStr.getBytes(StandardCharsets.US_ASCII), 0, pvd, 831, 16);
        pvd[848] = 0;
        System.arraycopy("0000000000000000".getBytes(StandardCharsets.US_ASCII), 0, pvd, 849, 16);
        pvd[866] = 0;
        System.arraycopy(dateStr.getBytes(StandardCharsets.US_ASCII), 0, pvd, 867, 16);
        pvd[884] = 0;

        pvd[881] = 1;

        return pvd;
    }

    private byte[] createVolumeDescriptorTerminator() {
        byte[] vdt = new byte[SECTOR_SIZE];
        vdt[0] = (byte) VOLUME_DESCRIPTOR_SET_TERMINATOR;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, vdt, 1, 5);
        vdt[6] = 1;
        return vdt;
    }

    private byte[] generateRootDirectory(SeededRNG rng) {
        byte[] rootDir = new byte[SECTOR_SIZE];
        LocalDateTime date = generateDeterministicDate(rng);
        int offset = 0;

        byte[] selfRecord = createDirectoryRecord("\0", 18, SECTOR_SIZE, true, date);
        System.arraycopy(selfRecord, 0, rootDir, offset, selfRecord.length);
        offset += selfRecord.length;

        byte[] parentRecord = createDirectoryRecord("\1", 18, SECTOR_SIZE, true, date);
        System.arraycopy(parentRecord, 0, rootDir, offset, parentRecord.length);
        offset += parentRecord.length;

        String[] dirs = {"boot", "etc", "home", "usr", "var", "tmp"};
        for (int i = 0; i < dirs.length; i++) {
            if (offset + 34 + dirs[i].length() > SECTOR_SIZE) break;
            byte[] dirRecord = createDirectoryRecord(dirs[i].toUpperCase(), 19 + i, SECTOR_SIZE, true, date);
            System.arraycopy(dirRecord, 0, rootDir, offset, dirRecord.length);
            offset += dirRecord.length;
        }

        return rootDir;
    }

    private byte[] createDirectoryRecord(String name, int sector, int size, boolean isDir, LocalDateTime date) {
        int nameLen = name.length();
        int recordLen = 34 + nameLen + (nameLen % 2 == 0 ? 1 : 0);
        byte[] record = new byte[recordLen];
        ByteBuffer buf = ByteBuffer.wrap(record);

        record[0] = (byte) recordLen;
        record[1] = 0;

        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(2, sector);
        buf.order(ByteOrder.BIG_ENDIAN).putInt(6, sector);

        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(10, size);
        buf.order(ByteOrder.BIG_ENDIAN).putInt(14, size);

        record[18] = (byte) (date.getYear() - 1900);
        record[19] = (byte) date.getMonthValue();
        record[20] = (byte) date.getDayOfMonth();
        record[21] = (byte) date.getHour();
        record[22] = (byte) date.getMinute();
        record[23] = (byte) date.getSecond();
        record[24] = 0;

        record[25] = isDir ? (byte) 0x02 : (byte) 0x00;
        record[26] = 0;
        record[27] = 0;

        buf.order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 1);
        buf.order(ByteOrder.BIG_ENDIAN).putShort(30, (short) 1);

        record[32] = (byte) nameLen;
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, record, 33, nameLen);

        return record;
    }

    private LocalDateTime generateDeterministicDate(SeededRNG rng) {
        int year = 2020 + rng.nextInt(5);
        int month = 1 + rng.nextInt(12);
        int day = 1 + rng.nextInt(28);
        int hour = rng.nextInt(24);
        int minute = rng.nextInt(60);
        int second = rng.nextInt(60);
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    private String formatISODate(LocalDateTime date) {
        return String.format("%04d%02d%02d%02d%02d%02d00",
            date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
            date.getHour(), date.getMinute(), date.getSecond());
    }

    private void writeBothEndian32(ByteBuffer buf, int offset, int value) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
        buf.order(ByteOrder.BIG_ENDIAN).putInt(offset + 4, value);
    }

    private void writeBothEndian16(ByteBuffer buf, int offset, int value) {
        buf.order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
        buf.order(ByteOrder.BIG_ENDIAN).putShort(offset + 2, (short) value);
    }

    private void writeAsciiPadded(byte[] buffer, int offset, String text, int length) {
        byte[] padded = new byte[length];
        java.util.Arrays.fill(padded, (byte) ' ');
        byte[] src = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, padded, 0, Math.min(src.length, length));
        System.arraycopy(padded, 0, buffer, offset, length);
    }

    public static byte[] generateSeed() {
        return new java.security.SecureRandom().generateSeed(32);
    }
}
