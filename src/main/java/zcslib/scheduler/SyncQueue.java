package zcslib.scheduler;

import zcslib.log.ZCSLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick-end batch merge queue per ZCSLIB 6.3.
 *
 * <p>Tasks queued during a tick are merged per-plugin and executed
 * serially at the end of the tick. Multiple calls to {@code queueSync}
 * from the same plugin in the same tick are coalesced into one run.
 */
class SyncQueue {
    private final Map<String, List<Runnable>> queues = new LinkedHashMap<>();
    private final ZCSLogger logger;

    SyncQueue(ZCSLogger logger) {
        this.logger = logger;
    }

    /**
     * Enqueue a main-thread task for this tick.
     *
     * <p>Must be called from the main thread. Tasks are executed
     * when {@link #flush()} is called at tick end.
     */
    void enqueue(String pluginId, Runnable task) {
        queues.computeIfAbsent(pluginId, k -> new ArrayList<>()).add(task);
    }

    /**
     * Flush all queued tasks. Called at the end of each tick.
     *
     * <p>Per-plugin tasks are merged: if plugin A queued 3 tasks in this tick,
     * they run as a single merged batch.
     */
    void flush() {
        if (queues.isEmpty()) return;

        for (Map.Entry<String, List<Runnable>> entry : queues.entrySet()) {
            String pluginId = entry.getKey();
            List<Runnable> tasks = entry.getValue();
            if (tasks.isEmpty()) continue;

            try {
                for (Runnable task : tasks) {
                    task.run();
                }
            } catch (Exception e) {
                logger.error("Sync task for %s failed: %s", pluginId, e.getMessage());
            }
        }
        queues.clear();
    }

    /** @return number of pending task groups (not individual tasks) */
    int pendingGroups() {
        return queues.size();
    }
}
