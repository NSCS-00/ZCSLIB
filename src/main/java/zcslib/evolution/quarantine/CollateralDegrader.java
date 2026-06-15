// ZCSLIB Evolution - Collateral Degrader
// When two plugins conflict, sacrifice the lesser to save the greater
// Pure Java SE logic; MC service/event unregistration happens via kernel order()
package zcslib.evolution.quarantine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collateral degradation: when plugins A and B cannot coexist,
 * decide which one to degrade (or partially disable).
 * <p>
 * Decision factors (in priority order):
 * <ol>
 *   <li>Trust level (N > R > A > S)</li>
 *   <li>Priority in PEC (lower number = more important)</li>
 *   <li>Bilateral arbitration_bias parameter</li>
 *   <li>Service/event dependency graph depth</li>
 * </ol>
 * <p>
 * This class only computes the decision. Actual unregistration
 * happens through {@code kernel.order("service:unregister", ...)}
 * and {@code kernel.order("event:unregister", ...)}.
 */
public class CollateralDegrader {

    public enum DegradeAction {
        /** Plugin B fully disabled. */
        DISABLE_B,
        /** Plugin B partially degraded (certain features disabled). */
        PARTIAL_B,
        /** Both plugins coexist with mediation. */
        MEDIATE,
        /** Cannot resolve automatically — needs admin. */
        ESCALATE
    }

    public static final class DegradeResult {
        private final DegradeAction action;
        private final String sacrificialPlugin;
        private final Set<String> disabledFeatures;
        private final String reason;

        public DegradeResult(DegradeAction action, String sacrificialPlugin,
                             Set<String> disabledFeatures, String reason) {
            this.action = action;
            this.sacrificialPlugin = sacrificialPlugin;
            this.disabledFeatures = disabledFeatures == null ? Set.of() : Set.copyOf(disabledFeatures);
            this.reason = reason;
        }

        public DegradeAction action() { return action; }
        public String sacrificialPlugin() { return sacrificialPlugin; }
        public Set<String> disabledFeatures() { return disabledFeatures; }
        public String reason() { return reason; }

        @Override
        public String toString() {
            return String.format("[%s] sacrifice=%s features=%s — %s",
                    action, sacrificialPlugin, disabledFeatures, reason);
        }
    }

    private final Map<String, PluginProfile> profiles = new ConcurrentHashMap<>();

    /**
     * Register a plugin's profile for conflict resolution.
     */
    public void register(String pluginId, String trustLevel, int priority,
                         Set<String> serviceDeps, Set<String> eventDeps) {
        profiles.put(pluginId, new PluginProfile(
                pluginId, trustLevel, priority, serviceDeps, eventDeps));
    }

    public void unregister(String pluginId) {
        profiles.remove(pluginId);
    }

    /**
     * Resolve a conflict between two plugins.
     *
     * @param pluginA      first plugin id
     * @param pluginB      second plugin id
     * @param conflictDesc human-readable description of the conflict
     * @param arbitration  bilateral arbitration bias (0=preserve B, 1=preserve A)
     * @return the degradation decision
     */
    public DegradeResult resolve(String pluginA, String pluginB,
                                  String conflictDesc, double arbitration) {
        PluginProfile a = profiles.get(pluginA);
        PluginProfile b = profiles.get(pluginB);

        // Both unknown → escalate
        if (a == null && b == null) {
            return new DegradeResult(DegradeAction.ESCALATE, null, Set.of(),
                    "Both plugins unknown: " + conflictDesc);
        }
        if (a == null) {
            return new DegradeResult(DegradeAction.DISABLE_B, pluginB, Set.of(),
                    "Plugin A unknown, defaulting to preserve B: " + conflictDesc);
        }
        if (b == null) {
            return new DegradeResult(DegradeAction.DISABLE_B, pluginA, Set.of(),
                    "Plugin B unknown, defaulting to preserve A: " + conflictDesc);
        }

        // Same trust → use priority
        int trustCompare = compareTrust(a.trustLevel, b.trustLevel);
        if (trustCompare != 0) {
            String victim = trustCompare > 0 ? pluginB : pluginA;
            String survivor = trustCompare > 0 ? pluginA : pluginB;
            return new DegradeResult(DegradeAction.DISABLE_B, victim, Set.of(),
                    String.format("Trust mismatch (A=%s B=%s): %s",
                            a.trustLevel, b.trustLevel, conflictDesc));
        }

        // Trust equal → priority (lower = more important)
        if (a.priority != b.priority) {
            String victim = a.priority < b.priority ? pluginB : pluginA;
            return new DegradeResult(DegradeAction.DISABLE_B, victim, Set.of(),
                    String.format("Priority (A=%d B=%d): %s", a.priority, b.priority, conflictDesc));
        }

        // Priority equal → arbitration bias
        if (arbitration >= 0.7) {
            return new DegradeResult(DegradeAction.DISABLE_B, pluginB,
                    findConflictFeatures(b), "Arbitration favours A: " + conflictDesc);
        } else if (arbitration <= 0.3) {
            return new DegradeResult(DegradeAction.DISABLE_B, pluginA,
                    findConflictFeatures(a), "Arbitration favours B: " + conflictDesc);
        }

        // Unclear → partial mediation
        return new DegradeResult(DegradeAction.MEDIATE, null, Set.of(),
                "Mediation attempted: " + conflictDesc);
    }

    private Set<String> findConflictFeatures(PluginProfile p) {
        Set<String> features = new HashSet<>();
        features.addAll(p.serviceDeps);
        features.addAll(p.eventDeps);
        return features;
    }

    private int compareTrust(String a, String b) {
        return trustRank(a) - trustRank(b);
    }

    private int trustRank(String t) {
        return switch (t) {
            case "N" -> 3;
            case "R" -> 2;
            case "A" -> 1;
            case "S" -> 0;
            default -> -1;
        };
    }

    // —— Inner ——

    record PluginProfile(String id, String trustLevel, int priority,
                         Set<String> serviceDeps, Set<String> eventDeps) {}
}
