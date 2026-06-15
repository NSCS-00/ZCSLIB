// ZCSLIB Evolution - Global Params
// System-level personality parameters, frozen → absolute lock
// Pure Java SE (java.base only)
package zcslib.evolution.params;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global personality parameters that determine system-wide behaviour.
 * <p>
 * Once {@link #freeze()} is called, all parameters are locked
 * and attempts to modify them are silently ignored.
 * <p>
 * Personality is multi-parameter, not a single "aggressive/conservative" axis.
 */
public class GlobalParams {

    private final Map<String, Double> params = new LinkedHashMap<>();
    private boolean frozen = false;

    public GlobalParams() {
        // Conservative defaults (start safe, earn trust)
        params.put("entropy_tolerance", 0.3);   // low → orderly
        params.put("self_healing_urgency", 0.4); // low → calm
        params.put("resource_hunger", 0.3);      // low → throttle plugins
        params.put("scan_sensitivity", 0.5);     // mid → known threats only
    }

    public double get(String key) { return params.getOrDefault(key, 0.5); }

    /**
     * Set a parameter. Ignored if frozen.
     * @return true if value was actually changed
     */
    public boolean set(String key, double value) {
        if (frozen) return false;
        params.put(key, clamp(value, 0.0, 1.0));
        return true;
    }

    /** Apply a delta with clamping. Ignored if frozen. */
    public boolean adjust(String key, double delta) {
        if (frozen) return false;
        double current = params.getOrDefault(key, 0.5);
        params.put(key, clamp(current + delta, 0.0, 1.0));
        return true;
    }

    public boolean isFrozen() { return frozen; }
    public void freeze() { this.frozen = true; }

    /** Snapshot for persistence. */
    public Map<String, Double> snapshot() { return new LinkedHashMap<>(params); }

    /** Restore from snapshot (before freeze — ignored after). */
    public void restore(Map<String, Double> snap) {
        if (frozen) return;
        params.clear();
        params.putAll(snap);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
