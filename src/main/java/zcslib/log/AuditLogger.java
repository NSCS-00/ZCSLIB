package zcslib.log;

import zcslib.api.TrustLevel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Trust-level separated audit log.
 * <p>
 * Writes to {@code logs/zcslib/audit/{N,R,A,S}/} so operators can
 * triage audit entries by sensitivity without filtering a single file.
 * <p>
 * Thread-safe (synchronized per file).
 */
public class AuditLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Path AUDIT_ROOT = Path.of("logs", "zcslib", "audit");

    private AuditLogger() {} // utility class

    /**
     * Write an audit entry to the trust-level bucket.
     *
     * @param trust    trust level → determines subdirectory
     * @param pluginId source plugin (or "kernel")
     * @param action   action name (e.g. "send:standard", "config:save")
     * @param detail   human-readable detail
     */
    public static void log(TrustLevel trust, String pluginId, String action, String detail) {
        String dir = trust.name(); // N, R, A, S
        String date = LocalDateTime.now().format(DATE);
        String timestamp = LocalDateTime.now().format(TS);

        Path file = AUDIT_ROOT.resolve(dir).resolve(date + ".log");
        String line = String.format("[%s] [%s] [%s] %s — %s%n",
                timestamp, trust.name(), pluginId, action, detail);

        try {
            Files.createDirectories(file.getParent());
            synchronized (AuditLogger.class) {
                try (BufferedWriter w = Files.newBufferedWriter(file,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(line);
                }
            }
        } catch (IOException e) {
            System.err.println("[ZCSLIB] AuditLogger write failed: " + e.getMessage());
        }
    }

    /**
     * Convenience overload using {@code "kernel"} as source.
     */
    public static void kernel(TrustLevel trust, String action, String detail) {
        log(trust, "kernel", action, detail);
    }

    /**
     * Get the root audit directory (for use by LogRotator).
     */
    public static Path getAuditRoot() {
        return AUDIT_ROOT;
    }
}
