// ZCSLIB Evolution - Bilateral Params
// Plugin A vs Plugin B pairwise relationship parameters
// Pure Java SE (java.base only)
package zcslib.evolution.params;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pairwise bilateral parameters for conflict resolution between two plugins.
 * <p>
 * Key format: "pluginA::pluginB" (ordered alphabetically for lookup).
 */
public class BilateralParams {

    public static final class PairKey {
        private final String a, b, key;

        public PairKey(String pluginA, String pluginB) {
            if (pluginA.compareTo(pluginB) <= 0) {
                this.a = pluginA; this.b = pluginB;
            } else {
                this.a = pluginB; this.b = pluginA;
            }
            this.key = a + "::" + b;
        }

        public String a() { return a; }
        public String b() { return b; }
        public String key() { return key; }

        @Override public boolean equals(Object o) {
            return o instanceof PairKey pk && key.equals(pk.key);
        }
        @Override public int hashCode() { return key.hashCode(); }
        @Override public String toString() { return key; }
    }

    public static final class PairValues {
        private final Map<String, Double> values = new LinkedHashMap<>();

        public PairValues() {
            values.put("arbitration_bias", 0.5);   // 0=preserve B, 1=preserve A
            values.put("collateral_bias", 0.5);    // 0=never sacrifice B, 1=always sacrifice B
            values.put("alternative_preference", 0.5); // 0=force direct resolution, 1=prefer alternatives
        }

        public double get(String key) { return values.getOrDefault(key, 0.5); }
        public void set(String key, double v) { values.put(key, clamp(v, 0.0, 1.0)); }
        public void nudge(String key, double delta, boolean frozen) {
            if (frozen) return;
            double current = values.getOrDefault(key, 0.5);
            values.put(key, clamp(current + delta, 0.0, 1.0));
        }
        public Map<String, Double> snapshot() { return new LinkedHashMap<>(values); }

        private static double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private final Map<String, PairValues> pairs = new ConcurrentHashMap<>();
    private boolean frozen = false;

    public PairValues getOrCreate(PairKey key) {
        return pairs.computeIfAbsent(key.key(), k -> new PairValues());
    }

    public PairValues get(PairKey key) {
        return pairs.get(key.key());
    }

    public boolean isFrozen() { return frozen; }
    public void freeze() { this.frozen = true; }
    public int pairCount() { return pairs.size(); }

    /** Full snapshot for persistence. */
    public Map<String, Map<String, Double>> snapshot() {
        Map<String, Map<String, Double>> snap = new LinkedHashMap<>();
        for (Map.Entry<String, PairValues> e : pairs.entrySet()) {
            snap.put(e.getKey(), e.getValue().snapshot());
        }
        return snap;
    }
}
