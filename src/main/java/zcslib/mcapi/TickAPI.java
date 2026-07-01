package zcslib.mcapi;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server tick health and TPS queries.
 *
 * <p>Maintains an internal 100-tick ring buffer of sampled MSPT
 * (from {@code System.nanoTime()} delta) instead of relying on
 * {@code MinecraftServer.tickTimes} which is private/renamed
 * across MC versions. Ring buffer is populated via
 * {@link #fireTickEnd()} called from the kernel tick hook.
 */
public final class TickAPI {

    private static final int BUFFER_SIZE = 100;

    private final MinecraftServer server;
    private final long[] ringMspt = new long[BUFFER_SIZE]; // nanoseconds
    private int ringIdx;
    private int ringCount;
    private long lastTickNano;
    private long tickCountBaseline;

    TickAPI(MinecraftServer server) {
        this.server = server;
        this.lastTickNano = System.nanoTime();
    }

    // ── Called by kernel on each tick end ──────────────────

    void fireTickEnd() {
        long now = System.nanoTime();
        long elapsed = now - lastTickNano;
        lastTickNano = now;
        ringMspt[ringIdx] = elapsed;
        ringIdx = (ringIdx + 1) % BUFFER_SIZE;
        if (ringCount < BUFFER_SIZE) ringCount++;
        // Fire user-registered hooks
        tickEndHooks.forEach(Runnable::run);
    }

    // ── Core metrics ───────────────────────────────────────

    /** Server tick counter (from MC). */
    public long getServerTickCount() {
        return server.getTickCount();
    }

    /** Average TPS over the ring buffer. */
    public double getAverageTPS() {
        double ms = getAverageMSPT_ns() * 1e-6;
        if (ms <= 0) return 20.0;
        return clamp(1000.0 / ms, 0.0, 20.0);
    }

    /** Average milliseconds per tick (MSPT). */
    public double getMSPT() {
        return getAverageMSPT_ns() * 1e-6;
    }

    /** 95th-percentile MSPT from the ring buffer. */
    public double getPercentile95MSPT() {
        if (ringCount == 0) return 0.0;
        List<Long> sorted = new ArrayList<>();
        for (int i = 0; i < ringCount; i++) {
            sorted.add(ringMspt[i]);
        }
        sorted.sort(null);
        int idx = (int) (ringCount * 0.95);
        return sorted.get(Math.min(idx, ringCount - 1)) * 1e-6;
    }

    /** TPS computed over the ring buffer (approximate 1-minute). */
    public double getMinuteTPS() {
        long sum = 0;
        if (ringCount == 0) return 20.0;
        for (int i = 0; i < ringCount; i++) sum += ringMspt[i];
        double ms = (double) sum / ringCount * 1e-6;
        if (ms <= 0) return 20.0;
        return clamp(1000.0 / ms, 0.0, 20.0);
    }

    // ── Health ─────────────────────────────────────────────

    public TickHealth getTickHealth() {
        int severe = 0;
        for (int i = 0; i < ringCount; i++) {
            if (ringMspt[i] > 200_000_000L) severe++; // >200ms
        }
        if (severe > 40) return TickHealth.FROZEN;
        if (severe > 10) return TickHealth.SKIPPING;
        if (severe > 3)  return TickHealth.BEHIND;
        return TickHealth.HEALTHY;
    }

    /** Ticks where MSPT exceeded 50ms (at least one tick skipped). */
    public long getSkippedTickCount() {
        int skipped = 0;
        for (int i = 0; i < ringCount; i++) {
            if (ringMspt[i] > 50_000_000L) skipped++;
        }
        return skipped;
    }

    /** Cumulative tick overshoot (ticks past 50ms threshold). */
    public long getTickOvershootCount() {
        long total = 0;
        for (int i = 0; i < ringCount; i++) {
            if (ringMspt[i] > 50_000_000L) {
                total += (ringMspt[i] - 50_000_000L) / 50_000_000L;
            }
        }
        return total;
    }

    // ── Tick hooks (for ZCSKernel to register) ─────────────

    private final List<Runnable> tickStartHooks  = new ArrayList<>();
    private final List<Runnable> tickEndHooks    = new ArrayList<>();
    private final List<Runnable> tickPreHooks    = new ArrayList<>();
    private final List<Runnable> tickPostHooks   = new ArrayList<>();

    public void onTickStart(Runnable r)  { tickStartHooks.add(r); }
    public void onTickEnd(Runnable r)    { tickEndHooks.add(r); }
    public void onServerTickPre(Runnable r)  { tickPreHooks.add(r); }
    public void onServerTickPost(Runnable r) { tickPostHooks.add(r); }

    void invokeTickStart()  { tickStartHooks.forEach(Runnable::run); }
    void invokeTickEnd()    { tickEndHooks.forEach(Runnable::run); }
    void invokeTickPre()    { tickPreHooks.forEach(Runnable::run); }
    void invokeTickPost()   { tickPostHooks.forEach(Runnable::run); }

    // ── Types ──────────────────────────────────────────────

    public enum TickHealth { HEALTHY, BEHIND, SKIPPING, FROZEN }

    // ── Internal ───────────────────────────────────────────

    private long getAverageMSPT_ns() {
        long sum = 0;
        if (ringCount == 0) return 0;
        for (int i = 0; i < ringCount; i++) sum += ringMspt[i];
        return sum / ringCount;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
