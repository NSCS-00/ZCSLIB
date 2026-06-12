package zcslib.scheduler;

import zcslib.log.ZCSLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-plugin circuit breaker per ZCSLIB 6.4.
 *
 * <p>Tracks consecutive timeout/error count for each plugin's L3 compute
 * tasks. After 3 consecutive failures the plugin is locked out of L3 for
 * 30 seconds. A successful task resets the counter.
 */
class Bulkhead {
    private static final int FAILURE_THRESHOLD = 3;
    private static final long LOCKOUT_MS = 30_000;

    private final ConcurrentMap<String, BreakerState> states = new ConcurrentHashMap<>();
    private final ZCSLogger logger;

    Bulkhead(ZCSLogger logger) {
        this.logger = logger;
    }

    /** Record a task failure. Trips the breaker if threshold reached. */
    void recordFailure(String pluginId) {
        BreakerState state = states.computeIfAbsent(pluginId, k -> new BreakerState());
        if (state.trip()) {
            logger.error("BULKHEAD: Plugin %s L3 compute tripped — locked for %ds",
                    pluginId, LOCKOUT_MS / 1000);
        }
    }

    /** Record a task success. Resets the failure counter. */
    void recordSuccess(String pluginId) {
        BreakerState state = states.get(pluginId);
        if (state != null) state.reset();
    }

    /** @return true if the plugin's L3 compute is currently locked */
    boolean isLocked(String pluginId) {
        BreakerState state = states.get(pluginId);
        return state != null && state.isLocked();
    }

    void remove(String pluginId) {
        states.remove(pluginId);
    }

    // ── Internal state machine ─────────────────────────────

    private static class BreakerState {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile long lockedUntil = 0;

        synchronized boolean trip() {
            int count = consecutiveFailures.incrementAndGet();
            if (count >= FAILURE_THRESHOLD) {
                lockedUntil = System.currentTimeMillis() + LOCKOUT_MS;
                return true;
            }
            return false;
        }

        synchronized void reset() {
            consecutiveFailures.set(0);
        }

        boolean isLocked() {
            long lockEnd = lockedUntil;
            if (lockEnd == 0) return false;
            if (System.currentTimeMillis() > lockEnd) {
                lockedUntil = 0;
                consecutiveFailures.set(0);
                return false;
            }
            return true;
        }
    }
}
