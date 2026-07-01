package zcslib.sandbox;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dry-run execution wrapper — runs a plugin operation against a
 * {@link VirtualSave} snapshot and then discards all side effects.
 *
 * <p>The signature method is {@link #trial(ServerLevel, BlockPos, int, Consumer)}:
 * capture → execute → log diff → discard.
 *
 * <p><b>DreamWorker-only.</b>  Plugins cannot instantiate this.
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   DryRunResult result = DryRunContext.trial(level, center, 8, vs -> {
 *       vs.setBlock(pos1, Blocks.DIAMOND_BLOCK.defaultBlockState());
 *       vs.setBlock(pos2, Blocks.GOLD_BLOCK.defaultBlockState());
 *   });
 *   System.out.println(result);  // "dry-run ok — 2 blocks would change"
 * }</pre>
 */
public final class DryRunContext {

    private DryRunContext() {}

    // ── Trial methods ──────────────────────────────────────

    /**
     * Run an operation against a sandbox snapshot, capture the diff,
     * then discard.  The real world is never touched.
     *
     * @return summary of what <em>would</em> have changed
     */
    public static DryRunResult trial(ServerLevel level, BlockPos center, int radius,
                                     Consumer<VirtualSave> action) {
        VirtualSave vs = VirtualSave.capture(level, center, radius);
        String error = null;
        try {
            action.accept(vs);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        List<VirtualSave.BlockChange> changes = vs.getChanges();
        vs.discard();
        return new DryRunResult(error == null, error, changes.size(), List.copyOf(changes));
    }

    /** Cuboid variant. */
    public static DryRunResult trial(ServerLevel level, BlockPos a, BlockPos b,
                                     Consumer<VirtualSave> action) {
        VirtualSave vs = VirtualSave.capture(level, a, b);
        String error = null;
        try {
            action.accept(vs);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        List<VirtualSave.BlockChange> changes = vs.getChanges();
        vs.discard();
        return new DryRunResult(error == null, error, changes.size(), List.copyOf(changes));
    }

    /**
     * Run a function that produces a result, against a sandbox snapshot.
     * Discards the snapshot afterward.
     *
     * @param action function that takes a VirtualSave and returns a value
     * @return the function's return value (only meaningful when the function
     *         computes something from the snapshot rather than side effects)
     */
    public static <T> T trialCompute(ServerLevel level, BlockPos center, int radius,
                                     java.util.function.Function<VirtualSave, T> action) {
        VirtualSave vs = VirtualSave.capture(level, center, radius);
        try {
            return action.apply(vs);
        } finally {
            vs.discard();
        }
    }

    // ── Predicate helpers ──────────────────────────────────

    /**
     * Test whether an action would change <em>any</em> block.
     * Useful for pre-flight safety checks.
     */
    public static boolean wouldModify(ServerLevel level, BlockPos center, int radius,
                                      Consumer<VirtualSave> action) {
        DryRunResult r = trial(level, center, radius, action);
        return r.ok() && r.changeCount() > 0;
    }

    // ═══════════════════════════════════════════════════════
    //  Result record
    // ═══════════════════════════════════════════════════════

    public record DryRunResult(boolean ok, String error, int changeCount,
                               List<VirtualSave.BlockChange> changes) {}

    // ── Plural helpers ─────────────────────────────────────

    /** Collect multiple trials into one batch summary. */
    public static List<DryRunResult> trialBatch(ServerLevel level, BlockPos center,
                                                int radius,
                                                @SuppressWarnings("unchecked")
                                                Consumer<VirtualSave>... actions) {
        List<DryRunResult> results = new ArrayList<>(actions.length);
        for (Consumer<VirtualSave> a : actions) {
            results.add(trial(level, center, radius, a));
        }
        return results;
    }

    /** Collect multiple labelled trials — useful for test suites. */
    public static Map<String, DryRunResult> trialSuite(ServerLevel level,
                                                       BlockPos center, int radius,
                                                       Map<String, Consumer<VirtualSave>> suite) {
        Map<String, DryRunResult> results = new LinkedHashMap<>();
        for (var entry : suite.entrySet()) {
            results.put(entry.getKey(), trial(level, center, radius, entry.getValue()));
        }
        return results;
    }
}
