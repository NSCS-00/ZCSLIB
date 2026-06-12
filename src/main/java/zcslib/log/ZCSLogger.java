package zcslib.log;

import zcslib.api.TrustLevel;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dual-track logger per ZCSLIB 第十章.
 *
 * <p>Track 1 — Minecraft console ({@code latest.log}): always carries {@code [ZCSLIB]} prefix.
 * <p>Track 2 — Independent file ({@code logs/zcslib/{plugin_id}.log}): no {@code [ZCSLIB]} prefix.
 *
 * <p>Format (both tracks):
 * <pre>{@code [{TIME}] [{LEVEL}] [{TRUST}/{PLUGIN_ID}] [{THREAD}]: {MESSAGE}}</pre>
 *
 * <p>Thread-safe: file writes are synchronized.
 */
public class ZCSLogger {
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Logger slf4j;
    private final TrustLevel trust;
    private final String pluginId;
    private final BufferedWriter fileOut;

    /**
     * @param pluginId  plugin identifier (PEC pluginId)
     * @param trust     trust level assigned by the kernel
     * @param logDir    directory where {@code {plugin_id}.log} will be written
     */
    public ZCSLogger(String pluginId, TrustLevel trust, Path logDir) {
        this.pluginId = pluginId;
        this.trust = trust;
        this.slf4j = LogUtils.getLogger();
        this.fileOut = openFileWriter(logDir);
    }

    // ── Public log methods ──────────────────────────────────────

    public void info(String msg, Object... args)  { log(Level.INFO,  msg, args); }
    public void warn(String msg, Object... args)  { log(Level.WARN,  msg, args); }
    public void error(String msg, Object... args) { log(Level.ERROR, msg, args); }
    public void debug(String msg, Object... args) { log(Level.DEBUG, msg, args); }

    // ── Lifecycle ───────────────────────────────────────────────

    /** Flush and close the file track. Safe to call multiple times. */
    public void close() {
        synchronized (fileOut) {
            try { fileOut.flush(); fileOut.close(); } catch (IOException ignored) {}
        }
    }

    // ── Internals ───────────────────────────────────────────────

    /**
     * Vararg-safe String.format that pre-stringifies args to avoid
     * Object[] → vararg aliasing bugs across classloader boundaries.
     */
    private static String formatSafe(String format, Object... args) {
        if (args.length == 0) return format;
        String[] strings = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            strings[i] = String.valueOf(args[i]);
        }
        String safeFormat = format.replaceAll("%[dfxX]", "%s");
        return String.format(safeFormat, (Object[]) strings);
    }

    private void log(Level level, String msg, Object... args) {
        String resolved = args.length > 0 ? formatSafe(msg, args) : msg;
        String threadName = Thread.currentThread().getName();
        String timestamp = LocalDateTime.now().format(TS_FORMAT);

        // Track 1: MC console — with [ZCSLIB] prefix
        String consoleLine = String.format("[%s] [%s] [ZCSLIB] [%s/%s] [%s]: %s",
                timestamp, level, trust.name(), pluginId, threadName, resolved);
        writeToSlf4j(level, consoleLine);

        // Track 2: independent file — no [ZCSLIB] prefix
        String fileLine = String.format("[%s] [%s] [%s/%s] [%s]: %s",
                timestamp, level, trust.name(), pluginId, threadName, resolved);
        writeToFile(fileLine);
    }

    private void writeToSlf4j(Level level, String line) {
        switch (level) {
            case INFO:  slf4j.info(line);  break;
            case WARN:  slf4j.warn(line);  break;
            case ERROR: slf4j.error(line); break;
            case DEBUG: slf4j.debug(line); break;
        }
    }

    private void writeToFile(String line) {
        synchronized (fileOut) {
            try {
                fileOut.write(line);
                fileOut.newLine();
                fileOut.flush();
            } catch (IOException e) {
                slf4j.warn("[ZCSLIB] Failed to write to plugin log: {}", e.getMessage());
            }
        }
    }

    private BufferedWriter openFileWriter(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(pluginId + ".log");
            return Files.newBufferedWriter(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            slf4j.warn("[ZCSLIB] Cannot open plugin log file for {}: {}", pluginId, e.getMessage());
            // Return a /dev/null writer so the plugin doesn't crash on log calls
            return new BufferedWriter(new java.io.OutputStreamWriter(java.io.OutputStream.nullOutputStream()));
        }
    }

    private enum Level { INFO, WARN, ERROR, DEBUG }
}
