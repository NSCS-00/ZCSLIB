package zcslib.resource;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Disk quota enforcement per ZCSLIB 5.3.
 *
 * <p>S-level plugins are limited to 500 MB. Others get 2 GB.
 * Quota is checked against the partition's usable space — not
 * an exact byte counter (performance over precision).
 */
public class DiskQuota {
    private static final long S_LEVEL_LIMIT_MB = 500;
    private static final long DEFAULT_LIMIT_MB = 2048;

    private final long limitBytes;
    private final String pluginId;

    public DiskQuota(String pluginId, boolean isSLevel) {
        this.pluginId = pluginId;
        this.limitBytes = (isSLevel ? S_LEVEL_LIMIT_MB : DEFAULT_LIMIT_MB) * 1024 * 1024;
    }

    /**
     * Check whether writing {@code requestedBytes} would exceed the quota.
     *
     * @param dataRoot the plugin's data directory root
     * @param requestedBytes additional bytes to write
     * @return true if the write is within quota
     */
    public boolean canWrite(Path dataRoot, long requestedBytes) {
        long current = measureDir(dataRoot);
        return (current + requestedBytes) <= limitBytes;
    }

    /**
     * @return bytes remaining before hitting the hard limit
     */
    public long remainingBytes(Path dataRoot) {
        long current = measureDir(dataRoot);
        return Math.max(0, limitBytes - current);
    }

    public long getLimitBytes() {
        return limitBytes;
    }

    // ── Internal ─────────────────────────────────────────────

    private long measureDir(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (Exception e) { return 0; }
                    })
                    .sum();
        } catch (Exception e) {
            return 0; // fallback: allow write if we can't measure
        }
    }
}
