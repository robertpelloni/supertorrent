package io.supernode.storage.isoforge;

public enum SizePreset {
    NANO(256 * 1024 * 1024L),
    MICRO(512 * 1024 * 1024L),
    MINI(1024 * 1024 * 1024L),
    FULL(2048 * 1024 * 1024L);

    private final long bytes;

    SizePreset(long bytes) {
        this.bytes = bytes;
    }

    public long getBytes() {
        return bytes;
    }
}
