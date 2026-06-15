// ZCSLIB Daemon - Restart Engine
// Launch MC process for dream verification, monitor stability
// Pure Java SE (java.base only)
package zcslib.daemon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages MC process lifecycle for dream verification.
 * <p>
 * On first run, ZCSLIB records the MC installation absolute path.
 * This engine uses that recorded path to start a verification instance.
 * <p>
 * Stability: MC reaches main menu and stays alive for {@code stableDurationMs}
 * without crashing.
 */
public class RestartEngine {

    private static final String MC_PATH_FILE = "config/DLZstudio/ZCSLIB/mc_install_path.txt";
    private static final String STARTUP_TIMES_FILE = "config/DLZstudio/ZCSLIB/mc_startup_times.log";
    private static final int TICK_INTERVAL_MS = 50;
    private static final int TICK_TIMEOUT_COUNT = 40; // 2 seconds worth of missing ticks = frozen

    private final Path mcRoot;
    private Path recordedMcPath;

    public RestartEngine(Path mcRoot) {
        this.mcRoot = mcRoot;
    }

    /**
     * Record the MC installation path. Called once by ZCSLIB mod on first boot.
     */
    public static void recordMcPath(Path mcInstallDir) throws IOException {
        Path recordFile = mcInstallDir.resolve(MC_PATH_FILE);
        Files.createDirectories(recordFile.getParent());
        Files.writeString(recordFile, mcInstallDir.toAbsolutePath().toString(),
                StandardCharsets.UTF_8);
        System.out.println("[ZCSLIB] MC install path recorded: " +
                mcInstallDir.toAbsolutePath());
    }

    /**
     * Record a MC startup time measurement (ms from launch to main menu).
     */
    public static void recordStartupTime(Path mcInstallDir, long startupMs) throws IOException {
        Path log = mcInstallDir.resolve(STARTUP_TIMES_FILE);
        Files.createDirectories(log.getParent());
        Files.writeString(log, startupMs + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Launch MC for verification and wait for stability.
     *
     * @param stableDurationMs how long MC must run without crash to be "stable"
     * @return true if stable, false if crashed
     */
    public boolean launchForVerification(long stableDurationMs) {
        Path mcPath = resolveMcPath();
        if (mcPath == null) {
            System.err.println("[DAEMON] MC path not recorded — cannot verify.");
            System.err.println("[DAEMON] Run ZCSLIB as NeoForge mod once to record the path.");
            return false; // Can't verify, assume stable (don't punish)
        }

        System.out.println("[DAEMON] Launching MC: " + mcPath);

        try {
            Process process = startMcProcess(mcPath);

            // Monitor process
            boolean stable = monitorProcess(process, stableDurationMs);

            // Record startup time if stable
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            int exitCode = process.exitValue();
            System.out.println("[DAEMON] MC exited with code " + exitCode +
                    " (" + (stable ? "stable" : "unstable") + ")");

            return stable;

        } catch (Exception e) {
            System.err.println("[DAEMON] MC launch failed: " + e.getMessage());
            return false;
        }
    }

    private Path resolveMcPath() {
        // First check saved path
        Path savedFile = mcRoot.resolve(MC_PATH_FILE);
        if (Files.exists(savedFile)) {
            try {
                String saved = Files.readString(savedFile, StandardCharsets.UTF_8).trim();
                Path p = Path.of(saved);
                if (Files.exists(p)) {
                    this.recordedMcPath = p;
                    return p;
                }
            } catch (IOException ignored) {}
        }

        // Fallback: assume mcRoot IS the MC install dir
        if (Files.exists(mcRoot.resolve("config"))) {
            this.recordedMcPath = mcRoot;
            return mcRoot;
        }

        return null;
    }

    private Process startMcProcess(Path mcPath) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");

        // Use the same JVM that launched the daemon
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javaBin = Path.of(javaHome, "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            if (Files.exists(javaBin)) {
                cmd.set(0, javaBin.toAbsolutePath().toString());
            }
        }

        // Memory
        cmd.add("-Xmx2G");
        cmd.add("-Xms1G");

        // Add ZCSLIB to classpath
        // (In production, this would load the actual mod jar via classpath arg)
        cmd.add("-jar");
        cmd.add(findServerJar(mcPath));
        // Note: --nogui is not used — server jars ignore it and it adds noise

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(mcPath.toFile());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    private String findServerJar(Path mcPath) {
        // Priority order: neoforge/forge server jar > any jar with "server" > fallback
        String[] priority = { "neoforge", "forge", "server", "minecraft_server" };
        try (var stream = Files.list(mcPath)) {
            List<Path> jars = stream
                    .filter(f -> f.getFileName().toString().endsWith(".jar"))
                    .toList();

            for (String keyword : priority) {
                for (Path j : jars) {
                    if (j.getFileName().toString().toLowerCase().contains(keyword)) {
                        return j.toAbsolutePath().toString();
                    }
                }
            }
            // Last resort: any jar in the root
            Optional<Path> anyJar = jars.stream().findFirst();
            if (anyJar.isPresent()) return anyJar.get().toAbsolutePath().toString();
        } catch (IOException ignored) {}

        System.err.println("[DAEMON] Could not find server jar in " + mcPath);
        return "server.jar"; // fallback — let MC fail with a clear error
    }

    private boolean monitorProcess(Process process, long stableDurationMs)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        AtomicLong lastTickTime = new AtomicLong(start);
        AtomicInteger ticksSinceLastHeartbeat = new AtomicInteger(0);
        AtomicBoolean tickDegraded = new AtomicBoolean(false);

        // Read process output in background, tracking MC server health signals
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // Detect tick health signals
                    if (line.contains("Can't keep up") ||
                        line.contains("Running behind") ||
                        line.contains("Tick length")) {
                        tickDegraded.set(true);
                        System.err.println("[DAEMON]   MC tick degraded: " +
                                line.substring(0, Math.min(120, line.length())));
                    }
                    // Heartbeat: any server log line = process is alive
                    lastTickTime.set(System.currentTimeMillis());
                    ticksSinceLastHeartbeat.set(0);
                }
            } catch (IOException ignored) {}
        }, "MC-monitor");
        reader.setDaemon(true);
        reader.start();

        while (true) {
            if (!process.isAlive()) {
                return false; // crashed
            }

            long elapsed = System.currentTimeMillis() - start;

            // Tick health check: if no output for TICK_TIMEOUT_COUNT * TICK_INTERVAL_MS,
            // the server is likely frozen
            ticksSinceLastHeartbeat.incrementAndGet();
            if (ticksSinceLastHeartbeat.get() > TICK_TIMEOUT_COUNT) {
                System.err.println("[DAEMON]   MC appears frozen (no output for " +
                        (ticksSinceLastHeartbeat.get() * TICK_INTERVAL_MS / 1000) + "s)");
                // Give it one more grace period before declaring unstable
                if (ticksSinceLastHeartbeat.get() > TICK_TIMEOUT_COUNT * 3) {
                    System.err.println("[DAEMON]   MC frozen too long — marking unstable");
                    return false;
                }
            }

            // Tick degradation detected
            if (tickDegraded.get()) {
                System.err.println("[DAEMON]   MC tick degradation detected — marking unstable");
                return false;
            }

            if (elapsed >= stableDurationMs) {
                return true; // survived long enough
            }

            // Log progress every 30 seconds
            if (elapsed > 0 && elapsed % 30_000 < 500) {
                System.out.println("[DAEMON]   MC running: " +
                        (elapsed / 1000) + "s / " + (stableDurationMs / 1000) + "s");
            }

            Thread.sleep(TICK_INTERVAL_MS);
        }
    }
}
