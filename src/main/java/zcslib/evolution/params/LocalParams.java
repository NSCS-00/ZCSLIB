// ZCSLIB Evolution - Local Params
// Per-plugin singleton parameters, base-value frozen, runtime +-10%
// Pure Java SE (java.base only)
package zcslib.evolution.params;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-plugin local parameters.
 * <p>
 * After {@link #freeze()}, the base value is locked and runtime
 * value oscillates within +-10% of the base.
 */
public class LocalParams {

    private final Map<String, Double> baseValues = new LinkedHashMap<>();
    private final Map<String, Double> runtimeValues = new ConcurrentHashMap<>();
    private boolean frozen = false;

    public LocalParams() {
        baseValues.put("suppression_bias", 0.5);
        baseValues.put("privilege_bias", 0.5);
        baseValues.put("resource_weight", 1.0);
        syncRuntime();
    }

    /**
     * Get the effective (runtime) value, within +-10% of base.
     */
    public double get(String key) {
        return runtimeValues.getOrDefault(key, 0.5);
    }

    /**
     * Set base value. If frozen, ignored. If not frozen, syncs runtime.
     */
    public boolean setBase(String key, double value) {
        if (frozen) return false;
        baseValues.put(key, clamp(value, 0.0, 1.0));
        syncRuntime();
        return true;
    }

    /**
     * Nudge runtime value within +-10% of base.
     * Used by reward/punishment mechanism.
     */
    public void nudge(String key, double delta) {
        double base = baseValues.getOrDefault(key, 0.5);
        double current = runtimeValues.getOrDefault(key, base);
        double margin = base * 0.1;
        double lower = base - margin;
        double upper = base + margin;
        runtimeValues.put(key, clamp(current + delta, lower, upper));
    }

    /** Reset runtime to base. */
    public void resetRuntime() { syncRuntime(); }

    public boolean isFrozen() { return frozen; }

    /** Freeze base values. Runtime still oscillates within +-10%. */
    public void freeze() {
        this.frozen = true;
        // Reset runtime to frozen base
        syncRuntime();
    }

    public Map<String, Double> baseSnapshot() { return new LinkedHashMap<>(baseValues); }

    public void restoreBase(Map<String, Double> snap) {
        if (frozen) return;
        baseValues.clear();
        baseValues.putAll(snap);
        syncRuntime();
    }

    private void syncRuntime() {
        for (Map.Entry<String, Double> e : baseValues.entrySet()) {
            runtimeValues.put(e.getKey(), e.getValue());
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
