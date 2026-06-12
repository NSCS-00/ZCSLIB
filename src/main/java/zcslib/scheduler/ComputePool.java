package zcslib.scheduler;

import zcslib.api.TrustLevel;
import zcslib.log.ZCSLogger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3 compute thread pool per ZCSLIB 6.2.
 *
 * <p>Fixed pool of {@code CPU cores × 2} threads. Per-plugin concurrency
 * is capped at min(cores, 4). S-level plugins are forbidden from L3 entirely.
 */
class ComputePool {
    private final ExecutorService executor;
    private final int maxConcurrent;
    private final int perPluginMax;
    private final ZCSLogger logger;

    /** Track active tasks per plugin. */
    private final ConcurrentMap<String, AtomicInteger> activeTasks = new ConcurrentHashMap<>();

    ComputePool(int cpuCores, ZCSLogger logger) {
        this.maxConcurrent = cpuCores * 2;
        this.perPluginMax = Math.min(cpuCores, 4);
        this.logger = logger;
        this.executor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "ZCSLIB-L3-Compute");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit a CPU-heavy task.
     *
     * @return future for the task, or {@code null} if the plugin is at concurrency limit
     */
    CompletableFuture<Void> submit(String pluginId, TrustLevel trust, Runnable task) {
        // S-level: block
        if (trust == TrustLevel.S) {
            logger.warn("FORBIDDEN:S compute — %s", pluginId);
            return CompletableFuture.failedFuture(
                    new SecurityException("FORBIDDEN:S compute"));
        }

        // Per-plugin concurrency cap
        AtomicInteger active = activeTasks.computeIfAbsent(pluginId, k -> new AtomicInteger(0));
        if (active.get() >= perPluginMax) {
            logger.warn("Plugin %s at compute concurrency limit (%d) — task rejected",
                    pluginId, perPluginMax);
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Plugin " + pluginId + " at concurrency limit"));
        }

        return CompletableFuture.runAsync(() -> {
            active.incrementAndGet();
            long start = System.currentTimeMillis();
            try {
                task.run();
            } finally {
                active.decrementAndGet();
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > 50) {
                    logger.warn("Compute task for %s took %dms (threshold 50ms)", pluginId, elapsed);
                }
            }
        }, executor);
    }

    int getActiveCount(String pluginId) {
        AtomicInteger c = activeTasks.get(pluginId);
        return c == null ? 0 : c.get();
    }

    void shutdown() {
        executor.shutdown();
    }
}
