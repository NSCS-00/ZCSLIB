package zcslib.scheduler;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.log.ZCSLogger;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous scheduler per ZCSLIB 第六章.
 *
 * <p>Provides L3 compute pooling, tick-end batch merging, and
 * per-plugin circuit breaking. All scheduling goes through the
 * kernel's {@code order()} with {@code scheduler:compute} and
 * {@code scheduler:queueSync} commands.
 *
 * <p>Public API:
 * <ul>
 *   <li>{@code scheduler:compute (pluginId, task)} — L3 async compute
 *   <li>{@code scheduler:queueSync (pluginId, task)} — main-thread tick-end batch
 *   <li>{@code scheduler:flushSync} — called by kernel at tick end
 * </ul>
 */
public class ZCSScheduler {
    private final ComputePool computePool;
    private final SyncQueue syncQueue;
    private final Bulkhead bulkhead;

    public ZCSScheduler(ZCSLogger logger) {
        int cores = Runtime.getRuntime().availableProcessors();
        this.computePool = new ComputePool(cores, logger);
        this.syncQueue = new SyncQueue(logger);
        this.bulkhead = new Bulkhead(logger);
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
    }
}
