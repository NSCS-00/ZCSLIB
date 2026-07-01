package zcslib.sandbox;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory world snapshot for dry-run testing and crash rollback.
 *
 * <p>Captures a cuboid region of the real world into a detached,
 * in-memory copy.  All reads and writes operate on the snapshot —
 * the real world is never touched until an explicit commit.
 *
 * <p><b>DreamWorker-only.</b>  Plugins cannot instantiate this class
 * (package-private constructor) and PluginClassLoader denies
 * {@code zcslib.sandbox.*} imports.
 *
 * <h3>Typical lifecycle</h3>
 * <pre>{@code
 *   VirtualSave vs = VirtualSave.capture(level, a, b);
 *   // … plugin does stuff against vs …
 *   List<BlockChange> diff = vs.getChanges();
 *   vs.discard();  // or vs.commit(level) to apply
 * }</pre>
 */
public final class VirtualSave {

    /** Maximum snapshottable volume (blocks). 128³ = 2,097,152 blocks (~3 GB heap worst-case). */
    public static final int MAX_VOLUME = 128 * 128 * 128;

    private final BlockPos min, max;
    private final Map<BlockPos, BlockState> snapshot = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> original  = new ConcurrentHashMap<>();
    private final List<BlockChange> changelog = new ArrayList<>();

    /** Package-private: only kernel / DryRunContext can create. */
    VirtualSave(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
    }

    // ── Capture ────────────────────────────────────────────

    /**
     * Capture a cuboid region from the live world.
     * Iterates loaded chunks; skipped positions are treated as {@code AIR}.
     */
    public static VirtualSave capture(ServerLevel level, BlockPos a, BlockPos b) {
        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());

        // Capacity safety: reject oversized regions to prevent OOM
        long volume = (long)(x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > MAX_VOLUME) {
            throw new SecurityException(
                    "SANDBOX: Snapshot volume " + volume + " exceeds MAX_VOLUME " + MAX_VOLUME);
        }

        VirtualSave vs = new VirtualSave(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    cursor.set(x, y, z);
                    if (level.hasChunkAt(cursor)) {
                        BlockState state = level.getBlockState(cursor);
                        vs.snapshot.put(cursor.immutable(), state);
                        vs.original.put(cursor.immutable(), state);
                    } else {
                        // chunk not loaded — treat as air (conservative default)
                        vs.snapshot.put(cursor.immutable(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                        vs.original.put(cursor.immutable(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        return vs;
    }

    /** Small convenience: capture around a centre point with radius. */
    public static VirtualSave capture(ServerLevel level, BlockPos center, int radius) {
        return capture(level,
                center.offset(-radius, -radius, -radius),
                center.offset( radius,  radius,  radius));
    }

    // ── Block I/O ──────────────────────────────────────────

    /** Read a block state from the virtual world. Returns AIR for out-of-bounds. */
    public BlockState getBlock(BlockPos pos) {
        if (!inBounds(pos)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return snapshot.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    /** Write a block state into the virtual world. Silently ignored if out-of-bounds. */
    public void setBlock(BlockPos pos, BlockState state) {
        if (!inBounds(pos)) return;
        BlockState old = snapshot.put(pos.immutable(), state);
        if (old != null && !old.equals(state)) {
            changelog.add(new BlockChange(pos.immutable(), old, state));
        }
    }

    // ── Entity snapshot (lightweight) ───────────────────────

    /**
     * Collect entity types and positions currently within the captured region.
     * Does <b>not</b> deep-copy entity NBT — this is a positional census only.
     */
    public static List<String> entityCensus(ServerLevel level, BlockPos a, BlockPos b) {
        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());

        List<String> list = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            BlockPos p = e.blockPosition();
            if (p.getX() >= x1 && p.getX() <= x2
                    && p.getY() >= y1 && p.getY() <= y2
                    && p.getZ() >= z1 && p.getZ() <= z2) {
                list.add(String.format("%s@(%d,%d,%d)",
                        EntityType.getKey(e.getType()), p.getX(), p.getY(), p.getZ()));
            }
        }
        return list;
    }

    // ── Diff & lifecycle ───────────────────────────────────

    /** Ordered list of every block change since capture. */
    public List<BlockChange> getChanges() {
        return Collections.unmodifiableList(new ArrayList<>(changelog));
    }

    /** Number of blocks that differ from the original snapshot. */
    public int dirtyBlockCount() {
        return changelog.size();
    }

    /** Discard the virtual save entirely. */
    public void discard() {
        snapshot.clear();
        original.clear();
        changelog.clear();
    }

    /** Apply all changes back to the live world. */
    public void commit(ServerLevel level) {
        for (BlockChange c : changelog) {
            level.setBlock(c.pos(), c.newState(), 3);
        }
        // after commit this VirtualSave is spent — discard internal state
        discard();
    }

    /** Revert the live world to the original snapshot (for rollback). */
    public void revert(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : original.entrySet()) {
            BlockState current = level.getBlockState(e.getKey());
            if (!current.equals(e.getValue())) {
                level.setBlock(e.getKey(), e.getValue(), 3);
            }
        }
        discard();
    }

    // ── Internal ───────────────────────────────────────────

    private boolean inBounds(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public BlockPos min() { return min; }
    public BlockPos max() { return max; }
    public int volume() { return (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1); }

    // ── Change record ──────────────────────────────────────

    /** A single block change: position, old state, new state. */
    public record BlockChange(BlockPos pos, BlockState oldState, BlockState newState) {}
}
