// ZCSLIB Evolution - L1 Ring Buffer
// 500 tick sliding window, heap only, never persisted
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.util.Arrays;

/**
 * 500-tick ring buffer that records raw call-frame snapshots.
 * <p>
 * On crash, {@link #freeze()} produces an {@link L1Snapshot}
 * for forensic analysis.
 * <p>
 * Thread-safe: designed for single-writer (MC main thread),
 * multi-reader (crash handler / inspector).
 */
public class L1Buffer {

    private static final int WINDOW = 500;

    private final CallFrame[] buffer = new CallFrame[WINDOW];
    private long headTick = -1;
    private int headIndex = 0;
    private int size = 0;

    /**
     * Push one tick worth of observations.
     */
    public synchronized void push(long tick, String[] entries) {
        buffer[headIndex] = new CallFrame(tick, entries);
        headTick = tick;
        headIndex = (headIndex + 1) % WINDOW;
        if (size < WINDOW) size++;
    }

    /**
     * Freeze current buffer contents into an immutable snapshot.
     * Used on crash or at shutdown.
     */
    public synchronized L1Snapshot freeze() {
        CallFrame[] copy = new CallFrame[size];
        int start = (size < WINDOW) ? 0 : headIndex;
        for (int i = 0; i < size; i++) {
            copy[i] = buffer[(start + i) % WINDOW];
        }
        return new L1Snapshot(copy, headTick);
    }

    /**
     * Clear the buffer (e.g. after freeze + persist).
     */
    public synchronized void clear() {
        Arrays.fill(buffer, null);
        headTick = -1;
        headIndex = 0;
        size = 0;
    }

    public synchronized int size() { return size; }
    public synchronized long latestTick() { return headTick; }

    /**
     * Read a frame at logical index (0 = oldest in window).
     */
    public synchronized CallFrame get(int idx) {
        if (idx < 0 || idx >= size) throw new IndexOutOfBoundsException(idx);
        int start = (size < WINDOW) ? 0 : headIndex;
        return buffer[(start + idx) % WINDOW];
    }

    // —— inner types ——

    public record CallFrame(long tick, String[] entries) {
        @Override
        public String toString() {
            return String.format("[T=%d] %d entries", tick, entries.length);
        }
    }
}
