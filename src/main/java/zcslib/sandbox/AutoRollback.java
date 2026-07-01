package zcslib.sandbox;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-plugin rollback journal — captures block modifications for each
 * active plugin during a configurable trailing window (default 3 ticks).
 *
 * <p>When a plugin crashes (unhandled exception from {@code order()}
 * dispatch), the journal is replayed in reverse to restore originals.
 *
 * <p><b>DreamWorker-only.</b>  Plugins cannot instantiate this.
 *
 * <h3>Integration points</h3>
 * <ol>
 * <li>Before each plugin {@code order()} call, the kernel calls
 *     {@link #beginOperation(String)}.</li>
 * <li>When the plugin writes blocks (via permitted paths), the kernel
 *     calls {@link #record(String, BlockPos, BlockState, BlockState)}.</li>
 * <li>After the {@code order()} call returns (or throws), the kernel
 *     calls {@link #endOperation(String, boolean)}
 *     — {@code false} on exception triggers automatic rollback.</li>
 * <li>Every server tick, {@link #age()} discards entries older than
 *     the window.</li>
 * </ol>
 */
public final class AutoRollback {

    /** Rolling window in server ticks. */
    private static final int WINDOW_TICKS = 3;

    /**
     * Linear journal — oldest at head, newest at tail.
     * Each entry is stamped with the server tick counter.
     */
    private final ConcurrentLinkedDeque<Entry> journal = new ConcurrentLinkedDeque<>();

    /**
     * Active-modification tracking: which plugin is currently touching
     * which positions.  Cleared when the plugin's operation ends.
     */
    private final Map<String, List<BlockMod>> activeMutations = new ConcurrentHashMap<>();

    /** Track the current tick for aging. */
    private long currentTick = 0;

    /** Package-visible: only kernel creates. */
    public AutoRollback() {}

    // ── Tick lifecycle ─────────────────────────────────────

    /** Call once per server tick to age-out stale entries. */
    public void age() {
        currentTick++;
        while (!journal.isEmpty()) {
            Entry head = journal.peekFirst();
            if (head == null) break;
            if (currentTick - head.tick() > WINDOW_TICKS) {
                journal.pollFirst();
            } else {
                break;
            }
        }
    }

    // ── Operation lifecycle ─────────────────────────────────

    /** Mark the start of a plugin operation. */
    public void beginOperation(String pluginId) {
        activeMutations.put(pluginId, new ArrayList<>());
    }

    /**
     * Record a block modification made by a plugin.
     * Called by the kernel's write path.
     */
    public void record(String pluginId, BlockPos pos,
                       BlockState oldState, BlockState newState) {
        journal.addLast(new Entry(currentTick, pluginId, pos, oldState, newState));

        List<BlockMod> mods = activeMutations.get(pluginId);
        if (mods != null) {
            mods.add(new BlockMod(pos, oldState));
        }
    }

    /**
     * Mark the end of a plugin operation.
     *
     * @param success {@code false} if the operation threw an exception —
     *                triggers immediate rollback of <em>this operation's</em>
     *                recorded modifications
     */
    public void endOperation(String pluginId, boolean success, ServerLevel level) {
        if (!success) {
            rollbackOperation(pluginId, level);
        }
        activeMutations.remove(pluginId);
    }

    // ── Rollback ───────────────────────────────────────────

    /** Rollback the current operation's mutations in reverse order. */
    private void rollbackOperation(String pluginId, ServerLevel level) {
        List<BlockMod> mods = activeMutations.get(pluginId);
        if (mods == null || mods.isEmpty()) return;

        // Reverse to undo in correct order (topmost change first)
        for (int i = mods.size() - 1; i >= 0; i--) {
            BlockMod mod = mods.get(i);
            BlockState current = level.getBlockState(mod.pos);
            if (!current.equals(mod.oldState)) {
                level.setBlock(mod.pos, mod.oldState, 3);
            }
        }
        mods.clear();
    }

    /**
     * Full rollback — revert ALL modifications in the active window
     * for a specific plugin.  Used for plugin isolation/ban.
     */
    public int rollbackAll(String pluginId, ServerLevel level) {
        int count = 0;
        // Walk journal in reverse (newest first) to correctly undo
        Iterator<Entry> it = journal.descendingIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!e.pluginId().equals(pluginId)) continue;
            BlockState current = level.getBlockState(e.pos());
            if (!current.equals(e.oldState())) {
                level.setBlock(e.pos(), e.oldState(), 3);
                count++;
            }
        }
        return count;
    }

    // ── Stats ──────────────────────────────────────────────

    /** How many modifications are currently tracked in the journal. */
    public int pendingCount() {
        return journal.size();
    }

    /** How many modifications are tracked for a specific plugin. */
    public int pendingCount(String pluginId) {
        return (int) journal.stream()
                .filter(e -> e.pluginId().equals(pluginId))
                .count();
    }

    /** Plugins that have active modifications in the window. */
    public Set<String> activePlugins() {
        Set<String> set = new HashSet<>();
        for (Entry e : journal) set.add(e.pluginId());
        return set;
    }

    /** Clear everything (used on shutdown). */
    public void clear() {
        journal.clear();
        activeMutations.clear();
    }

    // ═══════════════════════════════════════════════════════
    //  Internal records
    // ═══════════════════════════════════════════════════════

    private record Entry(long tick, String pluginId, BlockPos pos,
                         BlockState oldState, BlockState newState) {}

    private record BlockMod(BlockPos pos, BlockState oldState) {}
}
