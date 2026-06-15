// ZCSLIB Evolution — Quarantine Decider
// Pure Java SE (java.base only) — no Minecraft/NeoForge imports
package zcslib.evolution.quarantine;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.evolution.memory.L3Memory;
import zcslib.evolution.memory.L3Rule;
import zcslib.evolution.memory.L4Instinct;
import zcslib.evolution.params.GlobalParams;
import zcslib.evolution.params.LocalParams;

import java.util.List;

/**
 * Joint quarantine decision engine — combines L3 (learned rules),
 * L4 (hardcoded instincts), global params (personality), and local
 * params (per-plugin bias) into a single ruling.
 *
 * <h3>Decision pipeline</h3>
 * <pre>
 * Event → L4 fast check (is it a known threat?)
 *       → L3 lookup (do we have a learned rule?)
 *       → Params weighting (personality bias)
 *       → Ruling: ALLOW | THROTTLE | STUB | KERNEL_CACHE | COLLATERAL | ROLLBACK
 * </pre>
 */
public class QuarantineDecider {

    /** What the decider tells the kernel to do. */
    public enum Ruling {
        ALLOW, THROTTLE, STUB, KERNEL_CACHE, COLLATERAL, ROLLBACK
    }

    /** A single decision with rationale. */
    public record Verdict(
            Ruling ruling,
            String reason,
            double confidence,
            String source
    ) {
        public static Verdict allow() {
            return new Verdict(Ruling.ALLOW, "no threat detected", 0.0, "DEFAULT");
        }
    }

    private final L4Instinct l4_;
    private final GlobalParams globalParams;

    public QuarantineDecider(L4Instinct l4Instinct, GlobalParams globalParams) {
        this.l4_ = l4Instinct;
        this.globalParams = globalParams;
    }

    // ── Decision pipeline ───────────────────────────────────

    public Verdict evaluate(String pluginId, TrustLevel trust,
                            String methodName,
                            L3Memory l3Memory, LocalParams localParams) {

        // Stage 1: L4 instinct check
        L4Instinct.Verdict l4V = l4_.match(methodName);
        if (l4V == L4Instinct.Verdict.BLOCK || l4V == L4Instinct.Verdict.STUB) {
            return new Verdict(Ruling.STUB, String.valueOf(l4V) + ": " + methodName, 1.0, "L4");
        }
        if (l4V == L4Instinct.Verdict.MONITOR) {
            double entropy = globalParams.get("entropy_tolerance");
            if (entropy < 0.3) {
                return new Verdict(Ruling.THROTTLE, "L4 MONITOR + conservative: " + methodName, 0.6, "L4");
            }
        }

        // Stage 2: L3 learned rules
        if (l3Memory != null) {
            List<L3Rule> matching = l3Memory.match(pluginId, methodName, "*");
            if (!matching.isEmpty()) {
                L3Rule best = matching.stream()
                        .filter(r -> r.status() == L3Rule.Status.ACTIVE
                                  || r.status() == L3Rule.Status.VALIDATED)
                        .max((a, b) -> Double.compare(a.confidence(), b.confidence()))
                        .orElse(null);
                if (best != null) {
                    return mapL3(best, localParams);
                }
            }
        }

        // Stage 3: Global params fallback
        double entropy = globalParams.get("entropy_tolerance");
        double scanSens = globalParams.get("scan_sensitivity");
        if (entropy < 0.2 && scanSens > 0.8) {
            return new Verdict(Ruling.THROTTLE,
                    "ultra-conservative personality", 0.3, "GLOBAL_PARAMS");
        }

        return Verdict.allow();
    }

    public Verdict evaluateBasic(String pluginId, TrustLevel trust, String methodName) {
        return evaluate(pluginId, trust, methodName, null, null);
    }

    // ── Mapping ─────────────────────────────────────────────

    private Verdict mapL3(L3Rule rule, LocalParams localParams) {
        double conf = rule.confidence();
        if (localParams != null) {
            double suppression = localParams.get("suppression_bias");
            if (suppression > 0.7) conf = Math.min(1.0, conf + 0.15);
        }

        Ruling r = switch (rule.actionType()) {
            case SOFT_THROTTLE           -> Ruling.THROTTLE;
            case KERNEL_CACHE            -> Ruling.KERNEL_CACHE;
            case STUB, DISABLE           -> Ruling.STUB;
            case QUARANTINE              -> Ruling.COLLATERAL;
            default                      -> Ruling.ALLOW;
        };

        return new Verdict(r, "L3:" + rule.ruleId(), conf, "L3:" + rule.ruleId());
    }

    /** Execute ruling — returns OrderResult for kernel dispatch. */
    public OrderResult executeRuling(Verdict v, String pluginId, String methodName) {
        return switch (v.ruling()) {
            case ALLOW        -> OrderResult.success(null);
            case THROTTLE     -> { Thread.onSpinWait(); yield OrderResult.success("throttled"); }
            case STUB         -> { StubReplacer replacer = new StubReplacer();
                                    replacer.block(pluginId, methodName);
                                    yield OrderResult.success(null); }
            case KERNEL_CACHE -> OrderResult.success("kernel_cache: delegate to daemon sandbox");
            case COLLATERAL   -> OrderResult.success("collateral: " + v.reason());
            case ROLLBACK     -> OrderResult.success("rollback: " + v.reason());
        };
    }
}
