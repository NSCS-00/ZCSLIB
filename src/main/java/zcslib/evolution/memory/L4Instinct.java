// ZCSLIB Evolution - L4 Instinct
// Minimal feature-code matcher, hardcoded + appendable .zcsinst
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * L4 instinct — last-line-of-defence feature-code blacklist.
 * <p>
 * Hardcoded rules cover the most universal dangerous patterns.
 * {@code .zcsinst} files can append additional patterns learned
 * from community training sets.
 * <p>
 * Matches run on the main thread → must be O(1).
 */
public class L4Instinct {

    /**
     * A single instinct rule: feature_code → verdict.
     */
    public enum Verdict {
        /** Allow execution normally. */
        ALLOW,
        /** Instrument with monitoring (log + timed). */
        MONITOR,
        /** Replace with safe stub (return 0/null). */
        STUB,
        /** Block execution entirely. */
        BLOCK,
        /** Route to kernel-cache sandbox. */
        KERNEL_CACHE
    }

    public record InstinctRule(String featureCode, Verdict verdict, String reason) {
        public String toLine() {
            return featureCode + "|" + verdict.name() + "|" + reason;
        }

        public static InstinctRule fromLine(String line) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2) return null;
            try {
                return new InstinctRule(parts[0], Verdict.valueOf(parts[1]),
                        parts.length > 2 ? parts[2] : "");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // —— Hardcoded rules (universal) ——

    private static final List<InstinctRule> HARDCODED = List.of(
            new InstinctRule("System::exit", Verdict.BLOCK, "JVM shutdown attempt"),
            new InstinctRule("Runtime::halt", Verdict.BLOCK, "JVM halt attempt"),
            new InstinctRule("ProcessBuilder::start", Verdict.MONITOR, "External process spawn"),
            new InstinctRule("ClassLoader::defineClass", Verdict.BLOCK, "Dynamic class injection"),
            new InstinctRule("Unsafe::allocateInstance", Verdict.MONITOR, "Unsafe allocation"),
            new InstinctRule("Reflection::setAccessible", Verdict.MONITOR, "Reflective access escalation"),
            new InstinctRule("Thread::stop", Verdict.BLOCK, "Deprecated Thread.stop()"),
            new InstinctRule("Socket::connect", Verdict.MONITOR, "Direct socket connection"),
            new InstinctRule("File::deleteOnExit", Verdict.MONITOR, "Delete-on-exit registration")
    );

    private final List<InstinctRule> dynamicRules = new ArrayList<>();
    private Path file;

    // —— Query ——

    /**
     * Match a feature code against all rules (hardcoded + dynamic).
     * Returns the strictest verdict found.
     * O(rules) but rules count is always small (< 200).
     */
    public Verdict match(String featureCode) {
        Verdict worst = Verdict.ALLOW;
        for (InstinctRule r : HARDCODED) {
            if (r.featureCode().equals(featureCode)) {
                if (r.verdict().ordinal() > worst.ordinal()) worst = r.verdict();
            }
        }
        for (InstinctRule r : dynamicRules) {
            if (r.featureCode().equals(featureCode)) {
                if (r.verdict().ordinal() > worst.ordinal()) worst = r.verdict();
            }
        }
        return worst;
    }

    /**
     * Match with reason — returns the matched rule for logging.
     */
    public InstinctRule matchWithReason(String featureCode) {
        for (InstinctRule r : dynamicRules) {
            if (r.featureCode().equals(featureCode)) return r;
        }
        for (InstinctRule r : HARDCODED) {
            if (r.featureCode().equals(featureCode)) return r;
        }
        return null;
    }

    /** All active rules (hardcoded + dynamic). */
    public List<InstinctRule> allRules() {
        List<InstinctRule> all = new ArrayList<>(HARDCODED);
        all.addAll(dynamicRules);
        return Collections.unmodifiableList(all);
    }

    // —— Dynamic rules ——

    public void addDynamic(InstinctRule rule) { dynamicRules.add(rule); }
    public void removeDynamic(String featureCode) {
        dynamicRules.removeIf(r -> r.featureCode().equals(featureCode));
    }
    public int dynamicCount() { return dynamicRules.size(); }

    // —— Persist ——

    public void persist(Path l4Dir) throws IOException {
        Files.createDirectories(l4Dir);
        this.file = l4Dir.resolve("instinct.zcsinst");

        StringBuilder sb = new StringBuilder();
        sb.append("# ZCSLIB L4 Instinct v1\n");
        sb.append("# Hardcoded: ").append(HARDCODED.size()).append(" rules\n");
        sb.append("# Dynamic: ").append(dynamicRules.size()).append(" rules\n");
        for (InstinctRule r : dynamicRules) {
            sb.append(r.toLine()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public static L4Instinct load(Path file) throws IOException {
        L4Instinct inst = new L4Instinct();
        if (!Files.exists(file)) return inst;
        inst.file = file;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            InstinctRule r = InstinctRule.fromLine(line);
            if (r != null) inst.addDynamic(r);
        }
        return inst;
    }

    public Path file() { return file; }
}
