package zcslib.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe audit logger — writes to trust-level-partitioned directories.
 * <p>
 * Directory layout:
 * <pre>
 * logs/zcslib/audit/
 *   N/{pluginId}.log    — native/friendly
 *   R/{pluginId}.log    — recognized
 *   A/{pluginId}.log    — auto-adapt
 *   S/{pluginId}.log    — suspicious (forced audit)
 * </pre>
 * <p>
 * Every cross-trust call, blocked operation, or security-relevant event
 * is written here. N/R/A entries are INFO-level (decorative). S-level
 * entries are WARN-level (mandatory for server admin review).
 */
public class AuditLogger {

    // ── Recent entries ring buffer (for /zcslib debug audit) ─
    public record AuditEntry(zcslib.api.TrustLevel trust, String pluginId,
                             String category, String detail) {}

    private static final int RECENT_MAX = 50;
    private final AuditEntry[] recent = new AuditEntry[RECENT_MAX];
    private int recentIdx = 0;
    private int recentCount = 0;
    private final Object recentLock = new Object();

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path root;
    private final Map<String, BufferedWriter> writers = new ConcurrentHashMap<>();

    public AuditLogger(Path root) {
        this.root = root;
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Record an audit event.
     *
     * @param trust     trust level of the subject plugin
     * @param pluginId  plugin that triggered the event
     * @param category  short category tag (e.g. "SERVICE_CROSS", "BLOCKED", "COMPRESS")
     * @param detail    human-readable description
     */
    public void log(zcslib.api.TrustLevel trust, String pluginId,
                    String category, String detail) {
        String dir = switch (trust) {
            case N  -> "N";
            case R  -> "R";
            case A  -> "A";
            case S  -> "S";
            case BLACKLISTED -> "BLACKLISTED";
            case UNKNOWN -> "UNKNOWN";
        };

        // Date-based filename: {pluginId}_{yyyy-MM-dd}.log
        String date = java.time.LocalDate.now().toString();
        String filename = sanitize(pluginId) + "_" + date + ".log";
        String key = dir + "/" + filename;
        String line = String.format("[%s] [%s] [%s] %s | %s%n",
                LocalDateTime.now().format(TS), trust.name(), pluginId,
                category, detail);

        try {
            BufferedWriter w = writers.computeIfAbsent(key, k -> {
                Path file = root.resolve(k);
                try {
                    Files.createDirectories(file.getParent());
                    return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    System.err.println("[ZCSLIB] AuditLogger: cannot open " + file + " — " + e.getMessage());
                    return null;
                }
            });
            if (w != null) {
                synchronized (w) {
                    w.write(line);
                    w.flush();
                }
            }
            // Ring buffer
            synchronized (recentLock) {
                recent[recentIdx] = new AuditEntry(trust, pluginId, category, detail);
                recentIdx = (recentIdx + 1) % RECENT_MAX;
                if (recentCount < RECENT_MAX) recentCount++;
            }
        } catch (Exception e) {
            System.err.println("[ZCSLIB] AuditLogger write failure: " + e.getMessage());
        }
    }

    /**
     * Convenience: log with auto-detected severity based on trust level.
     * S-level events get {@code [WARN]} prefix in the log line.
     */
    public void logTrusted(String pluginId, String category, String detail,
                           zcslib.api.TrustLevel caller, zcslib.api.TrustLevel callee) {
        String severity = (caller == zcslib.api.TrustLevel.S ||
                          callee == zcslib.api.TrustLevel.S) ? "WARN" : "INFO";
        String full = String.format("[%s] caller=%s callee=%s — %s",
                severity, caller.name(), callee.name(), detail);
        log(caller, pluginId, category, full);
    }

    /**
     * Return the most recent {@code n} audit entries (ring buffer).
     */
    public java.util.List<AuditEntry> getRecent(int n) {
        java.util.List<AuditEntry> list = new java.util.ArrayList<>();
        synchronized (recentLock) {
            int count = Math.min(n, recentCount);
            int start = (recentIdx - count + RECENT_MAX) % RECENT_MAX;
            for (int i = 0; i < count; i++) {
                AuditEntry e = recent[(start + i) % RECENT_MAX];
                if (e != null) list.add(e);
            }
        }
        return list;
    }

    /**
     * Flush all open writers. Called at shutdown.
     */
    public void flushAll() {
        for (Map.Entry<String, BufferedWriter> e : writers.entrySet()) {
            try { e.getValue().flush(); } catch (IOException ignored) {}
        }
    }

    /**
     * Close all writers. Called at shutdown.
     */
    public void closeAll() {
        for (Map.Entry<String, BufferedWriter> e : writers.entrySet()) {
            try { e.getValue().close(); } catch (IOException ignored) {}
        }
        writers.clear();
    }

    /** Canonical audit root (static, for LogRotator). */
    public static Path getAuditRoot() {
        return Path.of("logs", "zcslib", "audit");
    }

    // ── Internal ────────────────────────────────────────────

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
