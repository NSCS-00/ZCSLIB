package zcslib.scheduler;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.log.ZCSLogger;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous scheduler per ZCSLIB 第六章.
 *
 * <p>Provides L3 compute pooling, main-thread task dispatch,
 * tick-end batch merging, async IO, and per-plugin circuit breaking.
 *
 * <p>Dispatch actions:
 * <ul>
 *   <li>{@code task} — main-thread synchronous execution (all trust levels)
 *   <li>{@code compute} — L3 async compute pool (N/R/A only; S blocked)
 *   <li>{@code queueSync} — main-thread tick-end batch (all trust levels)
 *   <li>{@code io} — async IO thread pool (N/R/A only)
 * </ul>
 */
public class ZCSScheduler {
    private final ComputePool computePool;
    private final SyncQueue syncQueue;
    private final Bulkhead bulkhead;
    private final java.util.concurrent.ExecutorService ioPool;
    private final ZCSLogger log;

    public ZCSScheduler(ZCSLogger logger) {
        this.log = logger;
        int cores = Runtime.getRuntime().availableProcessors();
        this.computePool = new ComputePool(cores, logger);
        this.syncQueue = new SyncQueue(logger);
        this.bulkhead = new Bulkhead(logger);
        this.ioPool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(2, cores / 2),
                r -> {
                    Thread t = new Thread(r, "ZCSLIB-io-" + System.currentTimeMillis() % 10000);
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Dispatch a scheduler command. Called from {@link zcslib.kernel.ZCSKernel#order}.
     */
    public OrderResult dispatch(String action, Object[] args, TrustLevel trust) {
        return switch (action) {
            case "compute" -> {
                if (args.length < 2) yield OrderResult.fail("scheduler:compute requires (pluginId, Runnable)");
                String pid = (String) args[0];
                if (!(args[1] instanceof Runnable task))
                    yield OrderResult.fail("scheduler:compute arg[1] must be Runnable");

                if (trust == TrustLevel.S) {
                    yield OrderResult.fail("FORBIDDEN:S compute");
                }

                // Check circuit breaker
                if (bulkhead.isLocked(pid)) {
                    yield OrderResult.fail("BULKHEAD: Plugin '" + pid + "' L3 compute is locked");
                }

                CompletableFuture<Void> future = computePool.submit(pid, trust, () -> {
                    try {
                        task.run();
                        bulkhead.recordSuccess(pid);
                    } catch (Exception e) {
                        bulkhead.recordFailure(pid);
                        throw e;
                    }
                });

                yield OrderResult.success(future);
            }
            case "queueSync" -> {
                if (args.length < 2) yield OrderResult.fail("scheduler:queueSync requires (pluginId, Runnable)");
                String pid = (String) args[0];
                if (!(args[1] instanceof Runnable task))
                    yield OrderResult.fail("scheduler:queueSync arg[1] must be Runnable");

                syncQueue.enqueue(pid, task);
                yield OrderResult.success();
            }
            case "task" -> {
                // Main-thread synchronous execution — all trust levels
                if (args.length < 2) yield OrderResult.fail("scheduler:task requires (pluginId, Runnable)");
                String pid = (String) args[0];
                if (!(args[1] instanceof Runnable task))
                    yield OrderResult.fail("scheduler:task arg[1] must be Runnable");

                try {
                    task.run();
                    yield OrderResult.success();
                } catch (Exception ex) {
                    yield OrderResult.fail("scheduler:task failed: " + ex.getMessage());
                }
            }
            case "io" -> {
                // Async IO thread pool — blocked for S-level
                if (trust == TrustLevel.S)
                    yield OrderResult.fail("FORBIDDEN:S scheduler:io");

                if (args.length < 2) yield OrderResult.fail("scheduler:io requires (pluginId, Runnable)");
                String pid = (String) args[0];
                if (!(args[1] instanceof Runnable task))
                    yield OrderResult.fail("scheduler:io arg[1] must be Runnable");

                ioPool.submit(() -> {
                    try {
                        task.run();
                    } catch (Exception ex) {
                        log.error("scheduler:io for %s failed: %s", pid, ex.getMessage());
                    }
                });
                yield OrderResult.success();
            }
            default ->
                OrderResult.fail("Unknown scheduler action: " + action);
        };
    }

    /**
     * Flush the sync queue. Must be called from the main thread at tick end.
     */
    public void flushSync() {
        syncQueue.flush();
    }

    // ── Accessors ────────────────────────────────────────────

    public int getActiveComputeTasks(String pluginId) {
        return computePool.getActiveCount(pluginId);
    }

    public boolean isCircuitBreakerOpen(String pluginId) {
        return bulkhead.isLocked(pluginId);
    }

    public void shutdown() {
        computePool.shutdown();
        ioPool.shutdown();
    }
}
