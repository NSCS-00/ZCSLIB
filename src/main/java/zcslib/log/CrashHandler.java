package zcslib.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Global crash handler — catches unhandled exceptions and writes crash
 * reports to separate kernel vs plugin directories.
 * <p>
 * <b>Kernel crash:</b> {@code logs/zcslib/crashes/kernel/{timestamp}.log}<br>
 * <b>Plugin crash:</b> {@code logs/zcslib/crashes/plugins/{pluginId}/{timestamp}.log}
 * <p>
 * Kernel crashes are permanent (never rotated). Plugin crashes are capped
 * at 5 most recent per plugin (by LogRotator).
 * <p>
 * On kernel crash, the handler writes the report and re-throws
 * (let MC/JVM decide crash behaviour). On plugin crash, the handler
 * catches and swallows — the plugin is disabled but the kernel continues.
 */
public class CrashHandler {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Path CRASH_ROOT = Path.of("logs", "zcslib", "crashes");

    private static boolean installed = false;

    private CrashHandler() {} // utility

    /**
     * Install as the JVM-wide uncaught exception handler.
     * Safe to call multiple times (idempotent).
     */
    public static void install() {
        if (installed) return;
        installed = true;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                handleKernelCrash(throwable);
            } catch (Exception ignored) {
                // best-effort, don't compound failures
            }
            // Chain to previous handler if any
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Record a kernel-level crash. Writes file, then re-throws.
     */
    public static void handleKernelCrash(Throwable t) {
        String timestamp = LocalDateTime.now().format(TS);
        Path dir = CRASH_ROOT.resolve("kernel");
        Path file = dir.resolve(timestamp + ".log");

        writeCrashReport(file, "KERNEL", t);
        System.err.println("[ZCSLIB] Kernel crash written to " + file.toAbsolutePath());
    }

    /**
     * Record a plugin-level crash. Catches and swallows — kernel keeps running.
     *
     * @param pluginId  the plugin that crashed
     * @param t         the exception
     * @return true if report was written successfully
     */
    public static boolean handlePluginCrash(String pluginId, Throwable t) {
        String timestamp = LocalDateTime.now().format(TS);
        Path dir = CRASH_ROOT.resolve("plugins").resolve(pluginId);
        Path file = dir.resolve(timestamp + ".log");

        try {
            writeCrashReport(file, "PLUGIN:" + pluginId, t);
            return true;
        } catch (Exception e) {
            System.err.println("[ZCSLIB] Failed to write plugin crash report for " + pluginId + ": " + e.getMessage());
            return false;
        }
    }

    // —— Internal ——

    private static void writeCrashReport(Path file, String category, Throwable t) {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Cannot create crash directory: " + file.getParent(), e);
        }

        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            w.write("=== ZCSLIB CRASH REPORT ===");
            w.newLine();
            w.write("Category: " + category);
            w.newLine();
            w.write("Time:     " + LocalDateTime.now());
            w.newLine();
            w.write("Thread:   " + Thread.currentThread().getName());
            w.newLine();
            w.write("Exception:");
            w.newLine();

            // Full stack trace
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            w.write(sw.toString());
            w.newLine();

            // Cause chain
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth < 10) {
                w.write("--- Caused by (depth " + depth + ") ---");
                w.newLine();
                StringWriter csw = new StringWriter();
                cause.printStackTrace(new PrintWriter(csw));
                w.write(csw.toString());
                w.newLine();
                cause = cause.getCause();
                depth++;
            }

            w.write("=== END CRASH REPORT ===");
            w.newLine();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write crash report", e);
        }
    }

    /**
     * Get the crash root directory (for LogRotator).
     */
    public static Path getCrashRoot() {
        return CRASH_ROOT;
    }
}
