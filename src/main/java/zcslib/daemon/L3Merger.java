// ZCSLIB Daemon - L3 Merger
// Merge multiple L3 .zcsmem files into a universal L3
// Pure Java SE (java.base only)
package zcslib.daemon;

import zcslib.evolution.memory.L3Memory;
import zcslib.evolution.memory.L3Rule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Merges multiple L3 memory files into one universal combat manual.
 * <p>
 * Strategy: majority-vote for conflicting rules, confidence-weighted
 * resolution when no majority exists.
 */
public class L3Merger {

    /**
     * Merge multiple .zcsmem files into a universal output.
     *
     * @param filePaths paths to individual .zcsmem files
     * @param output    output universal .zcsmem file
     */
    public void merge(String[] filePaths, Path output) throws IOException {
        // Load all
        List<L3Memory> memories = new ArrayList<>();
        for (String path : filePaths) {
            Path p = Path.of(path);
            if (Files.exists(p)) {
                L3Memory mem = L3Memory.load(p);
                if (mem != null) memories.add(mem);
            }
        }

        if (memories.isEmpty()) {
            System.out.println("[DAEMON] L3Merger: no valid input files found.");
            return;
        }

        System.out.println("[DAEMON] L3Merger: loaded " + memories.size() + " memory file(s)");

        // Group rules by (plugin, pattern, actionType)
        Map<String, List<L3Rule>> ruleGroups = new LinkedHashMap<>();
        for (L3Memory mem : memories) {
            for (L3Rule rule : mem.rules()) {
                String key = rule.matchPlugin() + "::" + rule.matchPattern() + "::" + rule.actionType();
                ruleGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
            }
        }

        // Create output memory
        L3Memory merged = new L3Memory("universal", "merged_" + System.currentTimeMillis() % 100000);

        for (Map.Entry<String, List<L3Rule>> entry : ruleGroups.entrySet()) {
            List<L3Rule> group = entry.getValue();

            if (group.size() == 1) {
                // Uncontested — accept
                merged.addRule(cloneWithStatus(group.get(0), L3Rule.Status.VALIDATED));
            } else if (allAgree(group)) {
                // All agree — accept with average confidence
                double avgConf = group.stream()
                        .mapToDouble(L3Rule::confidence).average().orElse(0.5);
                L3Rule base = group.get(0);
                merged.addRule(new L3Rule(
                        base.ruleId(), base.matchPlugin(), base.matchMethod(),
                        base.matchPattern(), base.actionType(), base.actionParam(),
                        avgConf, "l3_merger", L3Rule.Status.VALIDATED));
            } else {
                // Conflict — majority vote
                L3Rule winner = majorityVote(group);
                if (winner != null) {
                    merged.addRule(cloneWithStatus(winner, L3Rule.Status.CANDIDATE));
                    System.out.println("[DAEMON]   Conflict resolved by majority: " +
                            winner.matchPattern() + " → " + winner.actionType());
                } else {
                    // Deadlock — keep most conservative (highest restriction)
                    L3Rule conservative = pickMostConservative(group);
                    merged.addRule(cloneWithStatus(conservative, L3Rule.Status.CANDIDATE));
                    System.out.println("[DAEMON]   Deadlock (conservative): " +
                            conservative.matchPattern() + " → " + conservative.actionType());
                }
            }
        }

        // Write
        merged.persist(output.getParent());
        // Rename to target filename if needed
        Path written = merged.file();
        if (!written.equals(output)) {
            Files.move(written, output);
        }

        System.out.println("[DAEMON] L3Merger: " + merged.ruleCount() +
                " rules written to " + output);
    }

    private boolean allAgree(List<L3Rule> group) {
        L3Rule first = group.get(0);
        for (L3Rule r : group) {
            if (r.actionType() != first.actionType()) return false;
        }
        return true;
    }

    private L3Rule majorityVote(List<L3Rule> group) {
        Map<L3Rule.ActionType, Integer> votes = new LinkedHashMap<>();
        Map<L3Rule.ActionType, L3Rule> sample = new LinkedHashMap<>();
        for (L3Rule r : group) {
            votes.merge(r.actionType(), 1, Integer::sum);
            sample.putIfAbsent(r.actionType(), r);
        }
        int max = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max > group.size() / 2) {
            for (Map.Entry<L3Rule.ActionType, Integer> e : votes.entrySet()) {
                if (e.getValue() == max) return sample.get(e.getKey());
            }
        }
        return null; // no majority
    }

    private L3Rule pickMostConservative(List<L3Rule> group) {
        // More restrictive action has higher ordinal
        return group.stream()
                .max(Comparator.comparingInt(r -> r.actionType().ordinal()))
                .orElse(group.get(0));
    }

    private L3Rule cloneWithStatus(L3Rule src, L3Rule.Status status) {
        return new L3Rule(src.ruleId(), src.matchPlugin(), src.matchMethod(),
                src.matchPattern(), src.actionType(), src.actionParam(),
                src.confidence(), src.source(), status);
    }
}
