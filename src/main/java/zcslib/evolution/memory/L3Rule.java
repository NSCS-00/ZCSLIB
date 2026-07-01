// ZCSLIB Evolution - L3 Rule
// Single WHEN-THEN rule in the combat manual
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /** Compiled pattern cache — avoids repeated Pattern.compile() overhead. */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * Check if this rule matches a given signal.
     * <p>
     * Supports three match-pattern modes:
     * <ul>
     *   <li>{@code regex:...} — compile as Java regex, match via {@link Matcher#find()}</li>
     *   <li>{@code glob:...} — glob wildcard ({@code *} / {@code ?}) converted to regex</li>
     *   <li>plain text (default) — legacy {@link String#contains(CharSequence)} behaviour</li>
     * </ul>
     * Compiled patterns are cached in {@link #PATTERN_CACHE} to avoid recompilation.
     * Regex compilation failures result in {@code false} (graceful degradation).
     */
    public boolean matches(String pluginId, String method, String pattern) {
        if (!"*".equals(matchPlugin) && !matchPlugin.equals(pluginId)) return false;
        if (!"*".equals(matchMethod) && !matchMethod.equals(method)) return false;
        if (matchPattern.isEmpty()) return true;

        // —— Mode: regex ——
        if (matchPattern.startsWith("regex:")) {
            String regex = matchPattern.substring(6);
            try {
                Pattern compiled = PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
                return compiled.matcher(pattern).find();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        // —— Mode: glob ——
        if (matchPattern.startsWith("glob:")) {
            String glob = matchPattern.substring(5);
            try {
                Pattern compiled = PATTERN_CACHE.computeIfAbsent(glob, g -> {
                    // Escape regex meta-chars except * and ?, then convert glob → regex
                    String regex = "^" + g
                            .replace("\\", "\\\\")
                            .replace(".", "\\.")
                            .replace("+", "\\+")
                            .replace("$", "\\$")
                            .replace("^", "\\^")
                            .replace("{", "\\{")
                            .replace("}", "\\}")
                            .replace("[", "\\[")
                            .replace("]", "\\]")
                            .replace("(", "\\(")
                            .replace(")", "\\)")
                            .replace("|", "\\|")
                            .replace("*", ".*")
                            .replace("?", ".") + "$";
                    return Pattern.compile(regex);
                });
                return compiled.matcher(pattern).find();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        // —— Mode: plain text (backward compatible) ——
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
