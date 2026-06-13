package zcslib.log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Log rotation policy enforcement.
 * <p>
 * Call {@link #rotateAll()} on kernel startup to enforce retention limits:
 * <ul>
 *   <li><b>Audit logs:</b> delete entries older than 7 days</li>
 *   <li><b>Kernel crashes:</b> NEVER deleted (permanent)</li>
 *   <li><b>Plugin crashes:</b> keep only 5 most recent per plugin</li>
 * </ul>
 */
public class LogRotator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int AUDIT_RETENTION_DAYS = 7;
    private static final int PLUGIN_CRASH_MAX = 5;

    private LogRotator() {} // utility

    /**
     * Run all rotation policies. Call once at kernel startup.
     */
    public static void rotateAll() {
        rotateAuditLogs();
        rotatePluginCrashes();
        // Kernel crashes: no rotation (permanent)
    }

    // —— Audit rotation: delete files older than 7 days ——

    private static void rotateAuditLogs() {
        Path auditRoot = AuditLogger.getAuditRoot();
        if (!Files.isDirectory(auditRoot)) return;

        LocalDate cutoff = LocalDate.now().minusDays(AUDIT_RETENTION_DAYS);
        int deleted = 0;
        long freed = 0;

        try (DirectoryStream<Path> trustDirs = Files.newDirectoryStream(auditRoot)) {
            for (Path trustDir : trustDirs) {
                if (!Files.isDirectory(trustDir)) continue;

                try (DirectoryStream<Path> files = Files.newDirectoryStream(trustDir, "*.log")) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        // Filename format: yyyy-MM-dd.log
                        try {
                            String dateStr = name.substring(0, 10);
                            LocalDate fileDate = LocalDate.parse(dateStr, DATE_FMT);
                            if (fileDate.isBefore(cutoff)) {
                                long size = Files.size(file);
                                Files.delete(file);
                                deleted++;
                                freed += size;
                            }
                        } catch (Exception ignored) {
                            // skip malformed filenames
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ZCSLIB] LogRotator audit sweep failed: " + e.getMessage());
        }

        if (deleted > 0) {
            System.out.printf("[ZCSLIB] LogRotator: deleted %d audit files (freed %d KB)%n",
                    deleted, freed / 1024);
        }
    }

    // —— Plugin crash rotation: keep only 5 most recent per plugin ——

    private static void rotatePluginCrashes() {
        Path pluginsDir = CrashHandler.getCrashRoot().resolve("plugins");
        if (!Files.isDirectory(pluginsDir)) return;

        int deleted = 0;

        try (DirectoryStream<Path> pluginDirs = Files.newDirectoryStream(pluginsDir)) {
            for (Path pluginDir : pluginDirs) {
                if (!Files.isDirectory(pluginDir)) continue;

                List<Path> crashFiles = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginDir, "*.log")) {
                    ds.forEach(crashFiles::add);
                }

                if (crashFiles.size() <= PLUGIN_CRASH_MAX) continue;

                // Sort by filename (timestamp prefix) descending → oldest last
                crashFiles.sort(Comparator.reverseOrder());

                // Delete all but the 5 most recent
                for (int i = PLUGIN_CRASH_MAX; i < crashFiles.size(); i++) {
                    try {
                        Files.delete(crashFiles.get(i));
                        deleted++;
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("[ZCSLIB] LogRotator plugin crash sweep failed: " + e.getMessage());
        }

        if (deleted > 0) {
            System.out.printf("[ZCSLIB] LogRotator: trimmed %d old plugin crash reports%n", deleted);
        }
    }
}
