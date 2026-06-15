// ZCSLIB Daemon - Dream Worker
// L2 -> L3 conversion with iterative MC verification + reward/punishment
// Pure Java SE (java.base only)
package zcslib.daemon;

import zcslib.evolution.memory.*;
import zcslib.evolution.params.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Dream worker: reads L2 journals, extracts patterns via L4 instinct matching,
 * generates candidate L3 rules, validates via real MC process,
 * and adjusts personality parameters via reward/punishment.
 * <p>
 * v0.2.0: manual trigger only ({@code --daemon dream}).
 */
public class DreamWorker {

    private final Path pluginsDir;
    private final Path l4Dir;
    private final Path mcRoot;

    private final GlobalParams globalParams = new GlobalParams();
    private final AttentionParams attentionParams = new AttentionParams();
    private L4Instinct l4Instinct;

    public DreamWorker(Path pluginsDir, Path l4Dir, Path mcRoot) {
        this.pluginsDir = pluginsDir;
        this.l4Dir = l4Dir;
        this.mcRoot = mcRoot;
    }

    /**
     * Run one complete dream cycle.
     */
    public void run() throws IOException {
        // Load L4 instinct
        loadInstinct();

        // Scan plugin directories for L2 journals
        List<Path> l2Files = scanL2Journals();
        if (l2Files.isEmpty()) {
            System.out.println("[DAEMON] DreamWorker: no L2 journals found. Nothing to do.");
            return;
        }

        System.out.println("[DAEMON] DreamWorker: scanning " + l2Files.size() + " L2 journal(s)");

        // Per-plugin L2 -> Candidate L3 rules
        for (Path l2File : l2Files) {
            String pluginId = extractPluginId(l2File);
            System.out.println("[DAEMON]   Plugin '" + pluginId + "': analysing...");

            // Parse L2 events
            List<L2Event> events = parseJournal(l2File);
            if (events.isEmpty()) {
                System.out.println("[DAEMON]     No events parsed, skipping.");
                continue;
            }

            // Sliding window feature extraction
            List<FeatureVector> features = extractFeatures(events);

            // Supplementary analysis: error types, subsystem breakdown
            PluginAnalysis analysis = analyseSupplement(events);

            // Match against L4 statistical danger patterns
            List<PatternMatch> statMatches = matchStatPatterns(pluginId, features);
            System.out.println("[DAEMON]     " + statMatches.size() + " statistical pattern(s) matched");

            // L4 instinct code-level scan (stack traces → dangerous method signatures)
            List<L3Rule> instinctRules = scanL4Instinct(pluginId, events);
            System.out.println("[DAEMON]     " + instinctRules.size() + " instinct rule(s) from L4");

            // Generate candidate L3 rules from statistical patterns
            List<L3Rule> candidates = generateRules(pluginId, statMatches);

            // Supplement: generate rules from error/tick patterns
            List<L3Rule> supplementRules = generateSupplementRules(pluginId, analysis);
            candidates.addAll(supplementRules);

            // Merge: L4 instinct rules take precedence (immediate action, high confidence)
            candidates.addAll(instinctRules);

            if (candidates.isEmpty()) continue;

            // Validate via MC
            System.out.println("[DAEMON]   Starting MC verification...");
            ValidationResult vr = validateRules(pluginId, candidates);

            // Reward or punish
            if (vr.stable) {
                System.out.println("[DAEMON]     MC stable — reward, writing rules");
                reward(pluginId, vr);
                writeL3(pluginId, candidates);
                attentionParams.decay();
                attentionParams.recordStable();
            } else {
                System.out.println("[DAEMON]     MC unstable — punishment, discarding rules");
                punish(pluginId, vr);
                attentionParams.recordCrash();
            }

            // Delete L2 journal (dream consumed it)
            Files.deleteIfExists(l2File);
        }

        attentionParams.autoTune();
        System.out.println("[DAEMON] Dream cycle complete.");
    }

    // —— L2 File I/O ——

    private List<Path> scanL2Journals() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(pluginsDir)) return files;
        try (Stream<Path> pluginDirs = Files.list(pluginsDir)) {
            pluginDirs.filter(Files::isDirectory).forEach(pluginDir -> {
                Path l2Dir = pluginDir.resolve("memory/l2");
                if (Files.exists(l2Dir)) {
                    try (Stream<Path> l2Files = Files.list(l2Dir)) {
                        l2Files.filter(f -> f.getFileName().toString().endsWith(".zcslog"))
                                .forEach(files::add);
                    } catch (IOException ignored) {}
                }
            });
        }
        return files;
    }

    private List<L2Event> parseJournal(Path file) throws IOException {
        List<L2Event> events = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            L2Event e = L2EventParser.parse(line);
            if (e != null) events.add(e);
        }
        return events;
    }

    private String extractPluginId(Path l2File) {
        // Path: plugins/{id}/memory/l2/{ts}.zcslog
        Path l2Dir = l2File.getParent(); // l2/
        Path memDir = l2Dir.getParent();  // memory/
        Path pluginDir = memDir.getParent(); // {id}/
        return pluginDir.getFileName().toString();
    }

    // —— Feature Extraction ——

    private List<FeatureVector> extractFeatures(List<L2Event> events) {
        final int WINDOW = 5;
        List<FeatureVector> vectors = new ArrayList<>();

        for (int i = 0; i < events.size(); i += WINDOW) {
            int end = Math.min(i + WINDOW, events.size());
            List<L2Event> window = events.subList(i, end);

            int total = window.size();
            int timeouts = 0;
            int errors = 0;
            long totalDuration = 0;
            List<Long> durations = new ArrayList<>();

            for (L2Event e : window) {
                if (e.result() == L2Event.Result.TIMEOUT) timeouts++;
                if (e.result() == L2Event.Result.ERROR) errors++;
                if (e.durationMs() > 0 && e.result() != L2Event.Result.TIMEOUT) {
                    totalDuration += e.durationMs();
                    durations.add(e.durationMs());
                }
            }

            double successRate = (double)(total - timeouts - errors) / total;
            double timeoutRate = (double) timeouts / total;
            double avgLatency = durations.isEmpty() ? 0 : (double) totalDuration / durations.size();

            // Latency trend (compare to previous window)
            double latencyTrend = 0;
            if (!vectors.isEmpty()) {
                FeatureVector prev = vectors.get(vectors.size() - 1);
                latencyTrend = avgLatency - prev.avgLatency;
            }

            // Variance
            double variance = 0;
            for (long d : durations) {
                variance += Math.pow(d - avgLatency, 2);
            }
            variance = durations.isEmpty() ? 0 : variance / durations.size();

            boolean perfDeg = latencyTrend > 10 && variance > 200;
            boolean intermittent = timeoutRate > 0.05 && timeoutRate < 0.20 && variance > 300;

            FeatureVector fv = new FeatureVector(
                    total, timeouts, successRate, timeoutRate,
                    avgLatency, latencyTrend, variance,
                    perfDeg, intermittent,
                    window.get(window.size() - 1).tick()
            );
            vectors.add(fv);
        }

        return vectors;
    }

    // —— Supplementary Analysis ——

    private PluginAnalysis analyseSupplement(List<L2Event> events) {
        Map<String, Integer> errorTypes = new HashMap<>();
        Map<String, Integer> subsystems = new HashMap<>();
        Map<String, Integer> subsystemsError = new HashMap<>();
        long totalDuration = 0;
        int totalOk = 0, totalTimeout = 0, totalError = 0, totalBlocked = 0;
        long firstAbnormalTick = Long.MAX_VALUE;
        long lastAbnormalTick = 0;
        int crashProximityCount = 0;

        for (L2Event e : events) {
            // Subsystem counts
            subsystems.merge(e.subsystem(), 1, Integer::sum);

            switch (e.result()) {
                case OK -> totalOk++;
                case TIMEOUT -> totalTimeout++;
                case ERROR -> {
                    totalError++;
                    subsystemsError.merge(e.subsystem(), 1, Integer::sum);
                    // Extract error type from stack trace
                    if (!e.stackTrace().isEmpty()) {
                        String firstFrame = e.stackTrace().get(0);
                        String errSig = extractErrorClass(firstFrame);
                        errorTypes.merge(errSig, 1, Integer::sum);
                    }
                }
                case REJECTED, BLOCKED -> totalBlocked++;
            }

            totalDuration += e.durationMs();

            if (e.isAbnormal()) {
                if (e.tick() < firstAbnormalTick) firstAbnormalTick = e.tick();
                if (e.tick() > lastAbnormalTick) lastAbnormalTick = e.tick();
                // Crash proximity: abnormal events in last 100 ticks of recording
                long maxTick = events.get(events.size() - 1).tick();
                if (e.tick() > maxTick - 100) crashProximityCount++;
            }
        }

        String dominantSubsystem = subsystems.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("unknown");

        String mostErrorProne = subsystemsError.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        return new PluginAnalysis(
                errorTypes, subsystems, subsystemsError,
                totalOk, totalTimeout, totalError, totalBlocked,
                totalDuration, firstAbnormalTick, lastAbnormalTick,
                crashProximityCount, dominantSubsystem, mostErrorProne
        );
    }

    private String extractErrorClass(String stackFrame) {
        // "at com.example.BadPlugin.crash(BadPlugin.java:42)" → "BadPlugin::crash"
        return normaliseFrame(stackFrame);
    }

    private List<L3Rule> generateSupplementRules(String pluginId, PluginAnalysis analysis) {
        List<L3Rule> rules = new ArrayList<>();

        // Rule: dominant error type repeating → isolate
        if (analysis.mostErrorProne != null && analysis.errorTypes.size() <= 2 && analysis.totalError > 10) {
            String mostFrequent = analysis.errorTypes.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).get().getKey();
            L3Rule r = new L3Rule(
                    "suppl_" + pluginId + "_error_flood",
                    pluginId, mostFrequent, "error_flood",
                    L3Rule.ActionType.SOFT_THROTTLE, "max_latency_ms=20",
                    0.70, "supplement_analysis", L3Rule.Status.CANDIDATE
            );
            rules.add(r);
            System.out.println("[DAEMON]     Supplement: repeated error " + mostFrequent +
                    " (" + analysis.errorTypes.get(mostFrequent) + "x) → SOFT_THROTTLE");
        }

        // Rule: crash-proximity concentration → pre-crash degradation
        if (analysis.crashProximityCount >= 5 && analysis.totalAbnormal() > 20) {
            L3Rule r = new L3Rule(
                    "suppl_" + pluginId + "_pre_crash",
                    pluginId, analysis.mostErrorProne != null ? analysis.mostErrorProne : "*",
                    "pre_crash_degradation",
                    L3Rule.ActionType.SOFT_THROTTLE, "max_latency_ms=30",
                    0.65, "supplement_analysis", L3Rule.Status.CANDIDATE
            );
            rules.add(r);
            System.out.println("[DAEMON]     Supplement: crash-proximity pattern (" +
                    analysis.crashProximityCount + " abnormal in last 100 ticks) → SOFT_THROTTLE");
        }

        // Rule: single subsystem dominant + problematic → focus isolation
        if (analysis.mostErrorProne != null && analysis.subsystemsError.getOrDefault(analysis.mostErrorProne, 0) > 5) {
            int errCount = analysis.subsystemsError.get(analysis.mostErrorProne);
            L3Rule r = new L3Rule(
                    "suppl_" + pluginId + "_subsystem_hotspot",
                    pluginId, "*", "subsystem_hotspot",
                    L3Rule.ActionType.SOFT_THROTTLE,
                    "target_subsystem=" + analysis.mostErrorProne, 0.60,
                    "supplement_analysis", L3Rule.Status.CANDIDATE
            );
            rules.add(r);
            System.out.println("[DAEMON]     Supplement: subsystem hotspot '" +
                    analysis.mostErrorProne + "' (" + errCount + " errors) → SOFT_THROTTLE");
        }

        return rules;
    }

    record PluginAnalysis(
            Map<String, Integer> errorTypes,
            Map<String, Integer> subsystems,
            Map<String, Integer> subsystemsError,
            int totalOk, int totalTimeout, int totalError, int totalBlocked,
            long totalDuration, long firstAbnormalTick, long lastAbnormalTick,
            int crashProximityCount, String dominantSubsystem, String mostErrorProne
    ) {
        int totalAbnormal() { return totalTimeout + totalError + totalBlocked; }
    }

    // —— Statistical Pattern Matching ——

    private List<PatternMatch> matchStatPatterns(String pluginId, List<FeatureVector> features) {
        List<PatternMatch> matches = new ArrayList<>();

        for (FeatureVector fv : features) {
            if (fv.timeoutRate < 0.05 && Math.abs(fv.latencyTrend) < 5) {
                matches.add(new PatternMatch("occasional_jitter", DangerLevel.LOW, fv));
            }
            if (fv.perfDeg) {
                matches.add(new PatternMatch("performance_degradation", DangerLevel.MEDIUM, fv));
            }
            if (fv.intermittent) {
                matches.add(new PatternMatch("intermittent_failure", DangerLevel.MEDIUM, fv));
            }
            if (fv.timeoutRate > 0.20) {
                matches.add(new PatternMatch("frequent_timeout", DangerLevel.HIGH, fv));
            }
            if (fv.latencyTrend > 20 && fv.timeoutRate > 0.10) {
                matches.add(new PatternMatch("avalanche_precursor", DangerLevel.HIGH, fv));
            }
        }
        return matches;
    }

    // —— L4 Instinct Code-Level Scan ——

    /**
     * Scan L2 events' stack traces for L4 instinct dangerous method signatures.
     * Produces immediate-action L3 rules (BLOCK/STUB/KERNEL_CACHE) with high confidence,
     * independent of statistical pattern matching.
     */
    private List<L3Rule> scanL4Instinct(String pluginId, List<L2Event> events) {
        if (l4Instinct == null) return List.of();

        List<L3Rule> rules = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();

        for (L2Event e : events) {
            if (e.stackTrace().isEmpty()) continue;

            for (String frame : e.stackTrace()) {
                // Extract method signature: e.g. "at java.lang.System.exit(System.java:514)"
                // Normalise to "System::exit" format matching L4 feature codes
                String signature = normaliseFrame(frame);
                if (signature == null || seenSignatures.contains(signature)) continue;

                L4Instinct.Verdict v = l4Instinct.match(signature);
                if (v == L4Instinct.Verdict.ALLOW) continue;

                L4Instinct.InstinctRule matchedRule = l4Instinct.matchWithReason(signature);
                String reason = matchedRule != null ? matchedRule.reason() : "L4 instinct match";

                L3Rule.ActionType action = switch (v) {
                    case BLOCK, KERNEL_CACHE -> L3Rule.ActionType.KERNEL_CACHE;
                    case STUB -> L3Rule.ActionType.SOFT_THROTTLE;
                    case MONITOR -> L3Rule.ActionType.SOFT_THROTTLE;
                    default -> null;
                };
                if (action == null) continue;

                String ruleId = "l4_" + pluginId + "_" + signature.replace(":", "_") + "_" +
                        System.currentTimeMillis() % 100000;

                L3Rule rule = new L3Rule(
                        ruleId, pluginId, "*", signature,
                        action, "max_latency_ms=10", 0.95,
                        "l4_instinct", L3Rule.Status.VALIDATED
                );
                rules.add(rule);
                seenSignatures.add(signature);

                System.out.println("[DAEMON]     L4 match: " + signature + " → " + v +
                        " (reason: " + reason + ")");
            }
        }

        return rules;
    }

    /**
     * Normalise a Java stack frame into L4 feature code format.
     * "at java.lang.System.exit(System.java:514)" → "System::exit"
     * "at com.example.MyPlugin.doSomething(MyPlugin.java:42)" → "MyPlugin::doSomething"
     */
    private String normaliseFrame(String frame) {
        // Strip "at " prefix if present
        String f = frame.strip();
        if (f.startsWith("at ")) f = f.substring(3);

        // Extract method: everything before '(' — e.g. "java.lang.System.exit"
        int paren = f.indexOf('(');
        if (paren < 0) return null;
        String qualifier = f.substring(0, paren);

        // Split into class and method
        int lastDot = qualifier.lastIndexOf('.');
        if (lastDot < 0) return null;
        String className = qualifier.substring(0, lastDot);
        String methodName = qualifier.substring(lastDot + 1);

        // Strip package from class: "java.lang.System" → "System"
        int classDot = className.lastIndexOf('.');
        String simpleClass = classDot >= 0 ? className.substring(classDot + 1) : className;

        return simpleClass + "::" + methodName;
    }

    // —— Rule Generation ——

    private List<L3Rule> generateRules(String pluginId, List<PatternMatch> matches) {
        List<L3Rule> rules = new ArrayList<>();

        // Only generate rules for MEDIUM and HIGH danger (LOW = monitor only)
        for (PatternMatch pm : matches) {
            if (pm.level == DangerLevel.LOW) continue;

            String ruleId = "auto_" + pluginId + "_" + pm.pattern + "_" +
                    System.currentTimeMillis() % 100000;

            L3Rule.ActionType action;
            String param = "";
            double confidence;

            if (pm.level == DangerLevel.HIGH) {
                action = L3Rule.ActionType.KERNEL_CACHE;
                param = "timeoutMs=5000";
                confidence = 0.85;
            } else {
                action = L3Rule.ActionType.SOFT_THROTTLE;
                param = "max_latency_ms=50";
                confidence = 0.60;
            }

            // Adjust confidence by attention weight
            String subsystem = extractSubsystem(pm.pattern);
            double attention = attentionParams.getAttention(subsystem);
            confidence = clamp(confidence * (0.7 + 0.3 * attention), 0.3, 0.95);

            L3Rule rule = new L3Rule(
                    ruleId, pluginId, "*", pm.pattern,
                    action, param, confidence,
                    "dream_worker_auto", L3Rule.Status.CANDIDATE
            );
            rules.add(rule);
        }

        // Deduplicate: keep highest-confidence rule per (plugin, pattern)
        Map<String, L3Rule> best = new LinkedHashMap<>();
        for (L3Rule r : rules) {
            String key = r.matchPlugin() + "::" + r.matchPattern();
            L3Rule existing = best.get(key);
            if (existing == null || r.confidence() > existing.confidence()) {
                best.put(key, r);
            }
        }

        return new ArrayList<>(best.values());
    }

    // —— MC Verification ——

    private ValidationResult validateRules(String pluginId, List<L3Rule> candidates) {
        // Phase 1: Estimate runtime
        long estimatedTime = estimateRuntime();
        System.out.println("[DAEMON]     Estimated verification time: " +
                estimatedTime + "ms");

        // Phase 2: Start MC process for verification
        // RestartEngine launches a real MC subprocess and monitors
        // stdout for crash signals, tick health, and freeze detection

        RestartEngine engine = new RestartEngine(mcRoot);
        boolean stable = engine.launchForVerification(estimatedTime);

        return new ValidationResult(stable, estimatedTime, List.of());
    }

    private long estimateRuntime() {
        // Fallback: measure MC startup time from previous runs
        // For initial run, use conservative default
        Path startupLog = mcRoot.resolve("config/DLZstudio/ZCSLIB/mc_startup_times.log");
        List<Long> times = readStartupTimes(startupLog);

        if (times.isEmpty()) {
            return 120_000; // conservative default: 120 seconds
        }

        double avg = times.stream().mapToLong(Long::longValue).average().orElse(120_000);
        return (long)(avg * 1.5); // 1.5x safety margin
    }

    private List<Long> readStartupTimes(Path log) {
        List<Long> times = new ArrayList<>();
        if (!Files.exists(log)) return times;
        try {
            for (String line : Files.readAllLines(log)) {
                try { times.add(Long.parseLong(line.trim())); }
                catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return times;
    }

    // —— Reward / Punishment ——

    private void reward(String pluginId, ValidationResult vr) {
        // Increase entropy tolerance (more permissive)
        globalParams.adjust("entropy_tolerance", 0.02);
        // Decrease urgency (less reactive)
        globalParams.adjust("self_healing_urgency", -0.02);
        // Bump attention for the subsystem
        attentionParams.bump(pluginId, 0.05);
        System.out.println("[DAEMON]     Reward: entropy+" +
                String.format("%.3f", globalParams.get("entropy_tolerance")) +
                " urgency" + String.format("%.3f", globalParams.get("self_healing_urgency")));
    }

    private void punish(String pluginId, ValidationResult vr) {
        // Decrease entropy tolerance (more conservative)
        globalParams.adjust("entropy_tolerance", -0.05);
        // Increase urgency (more reactive)
        globalParams.adjust("self_healing_urgency", 0.05);
        attentionParams.bump(pluginId, 0.1);
        System.out.println("[DAEMON]     Punish: entropy" +
                String.format("%.3f", globalParams.get("entropy_tolerance")) +
                " urgency+" + String.format("%.3f", globalParams.get("self_healing_urgency")));
    }

    // —— L3 Persist ——

    private void writeL3(String pluginId, List<L3Rule> candidates) throws IOException {
        Path l3Dir = pluginsDir.resolve(pluginId).resolve("memory/l3");
        Files.createDirectories(l3Dir);

        // Load existing L3
        L3Memory existing = null;
        try (Stream<Path> files = Files.list(l3Dir)) {
            Optional<Path> existingFile = files
                    .filter(f -> f.getFileName().toString().endsWith(".zcsmem"))
                    .findFirst();
            if (existingFile.isPresent()) {
                existing = L3Memory.load(existingFile.get());
            }
        } catch (IOException ignored) {}

        if (existing == null) {
            existing = new L3Memory(pluginId, computeEnvHash());
        }

        // Merge new rules into existing
        for (L3Rule c : candidates) {
            // Check if a rule with the same match already exists
            boolean duplicate = false;
            for (L3Rule e : existing.rules()) {
                if (e.matchPlugin().equals(c.matchPlugin()) &&
                        e.matchPattern().equals(c.matchPattern()) &&
                        e.actionType() == c.actionType()) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                existing.addRule(c);
            }
        }

        // Promote validated rules
        for (L3Rule r : existing.rules()) {
            if (r.status() == L3Rule.Status.CANDIDATE) {
                r.setStatus(L3Rule.Status.VALIDATED);
            }
        }

        // Copy personality snapshot
        for (Map.Entry<String, Double> e : globalParams.snapshot().entrySet()) {
            existing.setPersonality(e.getKey(), e.getValue());
        }

        existing.persist(l3Dir);
        System.out.println("[DAEMON]     L3 written to " + existing.file());
    }

    private String computeEnvHash() {
        // Simple hash: use directory structure + MC version info
        // Full implementation would hash hardware info + mod list
        String input = mcRoot.toAbsolutePath().toString();
        int hash = input.hashCode();
        return String.format("%08x", hash);
    }

    // —— L4 ——

    private void loadInstinct() throws IOException {
        Path instinctFile = l4Dir.resolve("instinct.zcsinst");
        if (Files.exists(instinctFile)) {
            l4Instinct = L4Instinct.load(instinctFile);
        } else {
            l4Instinct = new L4Instinct();
        }
    }

    private String extractSubsystem(String pattern) {
        if (pattern.contains("timeout")) return "scheduler";
        if (pattern.contains("degradation")) return "scheduler";
        if (pattern.contains("avalanche")) return "scheduler";
        return "scheduler"; // default
    }

    // —— Helpers ——

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // —— Inner types ——

    enum DangerLevel { LOW, MEDIUM, HIGH }

    record PatternMatch(String pattern, DangerLevel level, FeatureVector vector) {}

    record FeatureVector(int count, int timeouts, double successRate, double timeoutRate,
                         double avgLatency, double latencyTrend, double variance,
                         boolean perfDeg, boolean intermittent, long lastTick) {}

    record ValidationResult(boolean stable, long durationMs, List<String> crashLogs) {}
}
