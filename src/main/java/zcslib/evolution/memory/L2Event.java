// ZCSLIB Evolution - L2 Event
// Pure Java SE (java.base only) — no Minecraft/NeoForge imports
package zcslib.evolution.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single event from the L2 journal (.zcslog).
 * Each tick may produce multiple events per plugin.
 */
public class L2Event {

    public enum Result { OK, TIMEOUT, ERROR, REJECTED, BLOCKED, UNKNOWN }

    private final long tick;
    private final String pluginId;
    private final String subsystem;
    private final String action;
    private final Result result;
    private final long durationMs;
    private final List<String> stackTrace;

    public L2Event(long tick, String pluginId, String subsystem, String action,
                   Result result, long durationMs, List<String> stackTrace) {
        this.tick = tick;
        this.pluginId = Objects.requireNonNull(pluginId);
        this.subsystem = Objects.requireNonNull(subsystem);
        this.action = Objects.requireNonNull(action);
        this.result = Objects.requireNonNull(result);
        this.durationMs = durationMs;
        this.stackTrace = stackTrace != null
                ? Collections.unmodifiableList(new ArrayList<>(stackTrace))
                : Collections.emptyList();
    }

    public L2Event(long tick, String pluginId, String subsystem, String action,
                   Result result, long durationMs) {
        this(tick, pluginId, subsystem, action, result, durationMs, null);
    }

    // --- accessors ---
    public long tick() { return tick; }
    public String pluginId() { return pluginId; }
    public String subsystem() { return subsystem; }
    public String action() { return action; }
    public Result result() { return result; }
    public long durationMs() { return durationMs; }
    public List<String> stackTrace() { return stackTrace; }

    public boolean isAbnormal() {
        return result == Result.TIMEOUT || result == Result.ERROR
                || result == Result.REJECTED || result == Result.BLOCKED;
    }

    @Override
    public String toString() {
        return String.format("[T=%d][%s][%s:%s] -> %s (%dms)",
                tick, pluginId, subsystem, action, result, durationMs);
    }
}
