// ZCSLIB Evolution - Attention Params
// Dynamic, never frozen — attention weight & forget rate
// Pure Java SE (java.base only)
package zcslib.evolution.params;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attention mechanism — never frozen, always dynamic.
 * <p>
 * Attention weight scales with crash frequency.
 * Forget rate determines how fast old observations decay.
 */
public class AttentionParams {

    private final Map<String, Double> attentionWeight = new ConcurrentHashMap<>();
    private double forgetRate = 0.05; // default: decay 5% per dream cycle
    private int crashCount = 0;
    private int stableCycleCount = 0;

    public AttentionParams() {
        attentionWeight.put("plugin", 0.5);
        attentionWeight.put("service", 0.5);
        attentionWeight.put("event", 0.5);
        attentionWeight.put("scheduler", 0.5);
        attentionWeight.put("network", 0.5);
    }

    // —— Attention weight ——

    public double getAttention(String subsystem) {
        return attentionWeight.getOrDefault(subsystem, 0.5);
    }

    /**
     * Bump attention for a subsystem (after crash/error).
     */
    public void bump(String subsystem, double delta) {
        attentionWeight.compute(subsystem, (k, v) -> {
            double current = v == null ? 0.5 : v;
            return Math.min(1.0, current + delta);
        });
    }

    /**
     * Decay attention across all subsystems (on stable cycle).
     */
    public void decay() {
        for (String key : attentionWeight.keySet()) {
            attentionWeight.computeIfPresent(key, (k, v) -> {
                double decayed = v * (1.0 - forgetRate);
                return Math.max(0.1, decayed); // floor at 0.1
            });
        }
    }

    /**
     * Admin-forced attention (bypasses dynamic logic).
     */
    public void force(String subsystem, double level) {
        attentionWeight.put(subsystem, clamp(level, 0.1, 1.0));
    }

    // —— Forget rate ——

    public double getForgetRate() { return forgetRate; }
    public void setForgetRate(double rate) {
        this.forgetRate = clamp(rate, 0.01, 0.50);
    }

    // —— Crash / stable tracking ——

    public int crashCount() { return crashCount; }
    public void recordCrash() { crashCount++; stableCycleCount = 0; }
    public int stableCycleCount() { return stableCycleCount; }
    public void recordStable() { stableCycleCount++; }

    /**
     * Auto-adjust forget rate based on stability.
     * More stable → forget slower (keep good memories).
     * More crashes → forget faster (purge noisy L2 quickly).
     */
    public void autoTune() {
        if (crashCount > 5) {
            forgetRate = Math.min(0.50, forgetRate + 0.02);
        } else if (stableCycleCount > 20) {
            forgetRate = Math.max(0.01, forgetRate - 0.01);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
