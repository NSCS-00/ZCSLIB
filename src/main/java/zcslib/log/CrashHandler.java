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
 * Phase 12: on kernel crash, freezes L1 snapshot via kernel reference.
 */
public class CrashHandler {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Path CRASH_ROOT = Path.of("logs", "zcslib", "crashes");

    private static boolean installed = false;
    /** Optional kernel reference — if set, L1 is frozen on kernel crash. */
    private static volatile zcslib.kernel.ZCSKernel kernel;

    private CrashHandler() {} // utility

    /**
     * Wire kernel so crash handler can freeze L1 on kernel crash.
     * Called once by ZCSLIB @Mod after kernel construction.
     */
    public static void setKernel(zcslib.kernel.ZCSKernel k) {
        kernel = k;
    }

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
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Record a kernel-level crash. Freezes L1, then writes file.
     */
    public static void handleKernelCrash(Throwable t) {
        // Phase 12: Freeze L1 before writing crash report
        if (kernel != null) {
            try {
                zcslib.evolution.memory.L1Snapshot snap = kernel.freezeL1();
                System.err.println("[ZCSLIB] L1 snapshot frozen: " + snap.frameCount() + " frames");
            } catch (Exception ignored) {}
        }

        String timestamp = LocalDateTime.now().format(TS);
        Path dir = CRASH_ROOT.resolve("kernel");
        Path file = dir.resolve(timestamp + ".log");

        writeCrashReport(file, "KERNEL", t);
        System.err.println("[ZCSLIB] Kernel crash written to " + file.toAbsolutePath());
    }

    /**
     * Record a plugin-level crash. Catches and swallows — kernel keeps running.
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

            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            w.write(sw.toString());
            w.newLine();

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

    public static Path getCrashRoot() {
        return CRASH_ROOT;
    }
}
