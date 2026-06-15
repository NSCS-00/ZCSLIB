// ZCSLIB Evolution - L3 Memory (Combat Manual)
// Environment-specific long-term memory, persisted as .zcsmem
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Long-term combat manual — environment-specific rules indexed by trigger pattern.
 * <p>
 * File: {@code plugins/{id}/memory/l3/{env_hash}.zcsmem}
 * <p>
 * Structure:
 * <pre>
 *   Header: env_hash, personality snapshot, frozen_flag
 *   Rules:  one WHEN-THEN per line (simple DSL)
 *   Footer: checksum
 * </pre>
 * <p>
 * Loaded at kernel start; queried by QuarantineDecider.
 */
public class L3Memory {

    private final String pluginId;
    private final String envHash;
    private final List<L3Rule> rules = new ArrayList<>();
    private Path file;
    private boolean frozen = false;

    // Personality snapshot (global params frozen at this point)
    private final Map<String, Double> personality = new LinkedHashMap<>();

    public L3Memory(String pluginId, String envHash) {
        this.pluginId = pluginId;
        this.envHash = envHash;
    }

    // —— Rules ——

    public void addRule(L3Rule rule) { rules.add(rule); }
    public void removeRule(String ruleId) { rules.removeIf(r -> r.ruleId().equals(ruleId)); }
    public List<L3Rule> rules() { return Collections.unmodifiableList(rules); }

    /**
     * Find all active rules whose trigger pattern matches the given signal.
     */
    public List<L3Rule> match(String pluginId, String method, String pattern) {
        List<L3Rule> matched = new ArrayList<>();
        for (L3Rule r : rules) {
            if (r.status() == L3Rule.Status.ACTIVE
                    && r.matches(pluginId, method, pattern)) {
                matched.add(r);
            }
        }
        // Sort by confidence descending
        matched.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return matched;
    }

    // —— Personality ——

    public void setPersonality(String key, double value) { personality.put(key, value); }
    public double getPersonality(String key) { return personality.getOrDefault(key, 0.5); }
    public Map<String, Double> personality() { return Collections.unmodifiableMap(personality); }

    // —— Frozen ——

    public boolean isFrozen() { return frozen; }
    public void freeze() { this.frozen = true; }

    // —— Persist ——

    public void persist(Path l3Dir) throws IOException {
        Files.createDirectories(l3Dir);
        this.file = l3Dir.resolve(envHash + ".zcsmem");

        StringBuilder sb = new StringBuilder();
        sb.append("# ZCSLIB L3 Memory v1\n");
        sb.append("plugin: ").append(pluginId).append('\n');
        sb.append("env_hash: ").append(envHash).append('\n');
        sb.append("frozen: ").append(frozen).append('\n');
        sb.append("rule_count: ").append(rules.size()).append('\n');

        // Personality snapshot
        for (Map.Entry<String, Double> e : personality.entrySet()) {
            sb.append("param: ").append(e.getKey())
                    .append('=').append(e.getValue()).append('\n');
        }

        sb.append("--- rules ---\n");
        for (L3Rule r : rules) {
            sb.append(r.toLine()).append('\n');
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Load from file. Returns null if file missing or corrupt.
     */
    public static L3Memory load(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("# ZCSLIB L3")) return null;

        String pluginId = "unknown";
        String envHash = "unknown";
        boolean frozen = false;
        L3Memory mem = null;
        boolean inRules = false;

        for (String line : lines) {
            if (line.startsWith("#")) continue;
            if (line.equals("--- rules ---")) { inRules = true; continue; }
            if (!inRules) {
                if (line.startsWith("plugin: ")) {
                    pluginId = line.substring(8).trim();
                } else if (line.startsWith("env_hash: ")) {
                    envHash = line.substring(10).trim();
                } else if (line.startsWith("frozen: ")) {
                    frozen = Boolean.parseBoolean(line.substring(8).trim());
                } else if (line.startsWith("param: ")) {
                    if (mem == null) mem = new L3Memory(pluginId, envHash);
                    String kv = line.substring(7).trim();
                    int eq = kv.indexOf('=');
                    if (eq > 0) mem.setPersonality(kv.substring(0, eq), Double.parseDouble(kv.substring(eq + 1)));
                }
            } else {
                if (mem == null) mem = new L3Memory(pluginId, envHash);
                L3Rule r = L3Rule.fromLine(line);
                if (r != null) mem.addRule(r);
            }
        }
        if (mem == null) mem = new L3Memory(pluginId, envHash);
        mem.frozen = frozen;
        mem.file = file;
        return mem;
    }

    public Path file() { return file; }
    public int ruleCount() { return rules.size(); }
}
