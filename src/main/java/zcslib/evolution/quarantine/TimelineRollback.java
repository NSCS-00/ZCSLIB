// ZCSLIB Evolution - Timeline Rollback
// Use L1 snapshot to roll back world state on catastrophic failure
// MC-dependent: requires CompoundTag + MinecraftServer (NeoForge)
package zcslib.evolution.quarantine;

import zcslib.evolution.memory.L1Snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Timeline rollback using L1 snapshot data.
 * <p>
 * Full rollback requires MC world state save/load (CompoundTag),
 * which is MC-dependent. This class provides the non-MC scaffolding.
 * <p>
 * The MC-specific {@code rollbackWorld(CompoundTag)} method must be
 * implemented in the NeoForge integration layer.
 */
public class TimelineRollback {

    private final Path worldBackupDir;

    public TimelineRollback(Path worldBackupDir) {
        this.worldBackupDir = worldBackupDir;
    }

    /**
     * Create a world backup before attempting dangerous operations.
     * Called by QuarantineDecider before executing Stub/KernelCache.
     *
     * @param worldDir  the Minecraft world directory (e.g. saves/NewWorld/)
     * @param snapshot  the L1 snapshot for context
     * @return path to the backup directory
     */
    public Path backupWorld(Path worldDir, L1Snapshot snapshot) throws IOException {
        Files.createDirectories(worldBackupDir);
        String name = "rollback_" + snapshot.frozenAtEpochMs();
        Path backup = worldBackupDir.resolve(name);

        // Copy world to backup (exclude massive region files if too slow -
        // in practice, only save level.dat + player data)
        copyDir(worldDir, backup);

        return backup;
    }

    /**
     * Restore world from a previous backup.
     *
     * @param worldDir the world directory to restore to
     * @param backup   the backup directory
     */
    public void restoreWorld(Path worldDir, Path backup) throws IOException {
        if (!Files.exists(backup)) {
            throw new IOException("Backup not found: " + backup);
        }

        // Delete current world
        deleteDir(worldDir);
        // Restore from backup
        copyDir(backup, worldDir);
    }

    /**
     * List available backups sorted by recency.
     */
    public Path[] listBackups() throws IOException {
        if (!Files.exists(worldBackupDir)) return new Path[0];
        try (var stream = Files.list(worldBackupDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toArray(Path[]::new);
        }
    }

    /**
     * Prune old backups, keeping only the most recent N.
     */
    public void prune(int keepCount) throws IOException {
        Path[] backups = listBackups();
        for (int i = keepCount; i < backups.length; i++) {
            deleteDir(backups[i]);
        }
    }

    // —— Helpers ——

    private void copyDir(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(s -> {
                try {
                    Path d = dst.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(d);
                    } else {
                        Files.createDirectories(d.getParent());
                        Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }

    /**
     * Create a rollback log entry so operators know time was rewound.
     */
    public static String rollbackLog(L1Snapshot snapshot, String reason) {
        return String.format(
                "[TIMELINE ROLLBACK] at tick=%d (wall=%s) — reason: %s",
                snapshot.frozenAtTick(),
                Instant.ofEpochMilli(snapshot.frozenAtEpochMs()),
                reason
        );
    }
}
