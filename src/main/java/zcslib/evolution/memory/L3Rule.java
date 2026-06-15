// ZCSLIB Evolution - L3 Rule
// Single WHEN-THEN rule in the combat manual
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single L3 rule: trigger condition → action + confidence.
 * <p>
 * Line format (persisted):
 * {@code STATUS|ruleId|plugin|method|pattern|actionType|actionParam|confidence|source}
 */
public class L3Rule {

    public enum Status { CANDIDATE, VALIDATED, ACTIVE, DEPRECATED }
    public enum ActionType { OBSERVE, SOFT_THROTTLE, KERNEL_CACHE, STUB, DISABLE, QUARANTINE }

    private final String ruleId;
    private final String matchPlugin;
    private final String matchMethod;
    private final String matchPattern;
    private final ActionType actionType;
    private final String actionParam;
    private final double confidence;
    private final String source;
    private volatile Status status;

    public L3Rule(String ruleId, String matchPlugin, String matchMethod,
                  String matchPattern, ActionType actionType, String actionParam,
                  double confidence, String source, Status status) {
        this.ruleId = Objects.requireNonNull(ruleId);
        this.matchPlugin = Objects.requireNonNull(matchPlugin);
        this.matchMethod = Objects.requireNonNull(matchMethod);
        this.matchPattern = Objects.requireNonNull(matchPattern);
        this.actionType = Objects.requireNonNull(actionType);
        this.actionParam = actionParam == null ? "" : actionParam;
        this.confidence = clamp(confidence, 0.0, 1.0);
        this.source = source == null ? "manual" : source;
        this.status = status == null ? Status.CANDIDATE : status;
    }

    /**
     * Check if this rule matches a given signal.
     */
    public boolean matches(String pluginId, String method, String pattern) {
        if (!"*".equals(matchPlugin) && !matchPlugin.equals(pluginId)) return false;
        if (!"*".equals(matchMethod) && !matchMethod.equals(method)) return false;
        if (matchPattern.isEmpty()) return true;
        return pattern.contains(matchPattern);
    }

    // —— Serialisation ——

    /** One-line persistence format, pipe-delimited. */
    public String toLine() {
        return String.join("|",
                status.name(), ruleId, matchPlugin, matchMethod, matchPattern,
                actionType.name(), actionParam,
                String.format("%.4f", confidence), source);
    }

    private static final Pattern LINE_RE = Pattern.compile(
            "^([A-Z_]+)\\|([^|]+)\\|([^|]+)\\|([^|]+)\\|([^|]*)\\|" +
            "([A-Z_]+)\\|([^|]*)\\|([0-9.]+)\\|(.*)$");

    public static L3Rule fromLine(String line) {
        Matcher m = LINE_RE.matcher(line.strip());
        if (!m.matches()) return null;
        try {
            return new L3Rule(
                    m.group(2),  // ruleId
                    m.group(3),  // plugin
                    m.group(4),  // method
                    m.group(5),  // pattern
                    ActionType.valueOf(m.group(6)),
                    m.group(7),  // param
                    Double.parseDouble(m.group(8)),
                    m.group(9),  // source
                    Status.valueOf(m.group(1))
            );
        } catch (Exception e) {
            return null;
        }
    }

    // —— accessors ——

    public String ruleId() { return ruleId; }
    public String matchPlugin() { return matchPlugin; }
    public String matchMethod() { return matchMethod; }
    public String matchPattern() { return matchPattern; }
    public ActionType actionType() { return actionType; }
    public String actionParam() { return actionParam; }
    public double confidence() { return confidence; }
    public String source() { return source; }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return String.format("[%s] %s :: %s/%s/%s → %s(%s) conf=%.2f",
                status, ruleId, matchPlugin, matchMethod, matchPattern,
                actionType, actionParam, confidence);
    }
}
