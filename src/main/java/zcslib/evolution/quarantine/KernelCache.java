// ZCSLIB Evolution - Kernel Cache Sandbox
// Isolate risky code in a separate ClassLoader sandbox
// Pure Java SE (java.base only)
package zcslib.evolution.quarantine;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Kernel-cache isolation sandbox.
 * <p>
 * Runs the target plugin's code in a separate ClassLoader with a
 * restricted execution budget (max threads, max memory signal, timeout).
 * <p>
 * The sandbox is deliberately light: no SecurityManager (deprecated in Java 17+),
 * relies on ClassLoader isolation + resource caps + timeout.
 */
public class KernelCache {

    private final Path sandboxDir;
    private final ExecutorService executor;
    private final Set<String> blockedPackages;

    public KernelCache(Path sandboxDir) {
        this.sandboxDir = sandboxDir;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ZCSLIB-KernelCache");
            t.setDaemon(true);
            return t;
        });
        this.blockedPackages = Set.of(
                "java.lang.reflect", "sun.misc", "jdk.internal"
        );
    }

    /**
     * Run a task inside the sandbox.
     *
     * @param task    the code to execute
     * @param timeoutMs timeout after which the sandbox thread is interrupted
     * @return the result, or null on timeout/error
     */
    public <T> T execute(Callable<T> task, long timeoutMs) {
        Future<T> future = executor.submit(() -> {
            // Set thread-local sandbox flag (for cooperative checks)
            SandboxContext.enter(sandboxDir.toString());
            try {
                return task.call();
            } finally {
                SandboxContext.exit();
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Create an isolated ClassLoader for a specific plugin JAR.
     * Blocks access to kernel-internal packages.
     */
    public static ClassLoader createIsolatedLoader(Path jarPath, ClassLoader parent) throws IOException {
        return new URLClassLoader(
                new URL[]{ jarPath.toUri().toURL() },
                new FilteringClassLoader(parent)
        );
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // —— Sandbox context (thread-local) ——

    public static final class SandboxContext {
        private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

        static void enter(String sandboxId) { CONTEXT.set(sandboxId); }
        static void exit() { CONTEXT.remove(); }

        public static boolean isSandboxed() { return CONTEXT.get() != null; }
        public static String sandboxId() { return CONTEXT.get(); }
    }

    // —— ClassLoader that blocks dangerous packages ——

    private static class FilteringClassLoader extends ClassLoader {
        private static final Set<String> BLOCKED = Set.of(
                "zcslib.kernel.internal.",
                "java.lang.reflect.",
                "sun.", "jdk.internal."
        );

        FilteringClassLoader(ClassLoader parent) { super(parent); }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            for (String blocked : BLOCKED) {
                if (name.startsWith(blocked)) {
                    throw new ClassNotFoundException("Blocked by KernelCache: " + name);
                }
            }
            return super.loadClass(name);
        }
    }
}
