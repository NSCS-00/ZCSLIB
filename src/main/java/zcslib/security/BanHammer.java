package zcslib.security;

import zcslib.api.TrustLevel;
import zcslib.loader.PluginDescriptor;
import zcslib.loader.PluginLoader;
import zcslib.log.AuditLogger;
import zcslib.log.ZCSLogger;
import zcslib.monitor.CrashGuard;
import zcslib.monitor.LagGuard;
import zcslib.monitor.LeakDetector;
import zcslib.security.NetworkAudit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P16 — 自动隔离引擎（BanHammer）。
 *
 * <p>签名黑名单 + 行为评分 + 自动隔离 S → BLACKLISTED。
 * 综合 5 条规则判断是否需要 ban。
 *
 * <p>线程模型：主线程 autoReview，命令线程 ban/unban。
 */
public class BanHammer {

    // ── 黑名单 ──
    private final Set<String> signatureBlacklist = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedPlugins      = ConcurrentHashMap.newKeySet();
    private final Map<String, String> banReasons  = new ConcurrentHashMap<>();

    // ── 原始信任级别（用于 unban 恢复） ──
    private final Map<String, TrustLevel> originalTrustLevels = new ConcurrentHashMap<>();

    // ── 行为评分（每个插件 0-100，越高越危险） ──
    private final Map<String, Long> behaviorScores = new ConcurrentHashMap<>();
    private static final long BAN_THRESHOLD = 80;
    private static final long SCORE_MAX = 100;

    // ── 评分规则权重 ──
    private static final long SCORE_CRASH_FREQUENT   = 30;  // >5 crash/60s
    private static final long SCORE_VIOLATION_REPEAT  = 25;  // 3+ violation 连续
    private static final long SCORE_CHUNK_LEAK        = 15;  // >50 chunk 泄漏
    private static final long SCORE_NETWORK_ANOMALY   = 20;  // burst + large payload
    private static final long SCORE_DREAMWORKER_FLAG  = 35;  // DreamWorker 标记

    // ── Ban 持久化 ──
    private final Path banFile;  // config/DLZstudio/ZCSLIB/security/bans.json

    // ── 依赖 ──
    private final CrashGuard crashGuard;
    private final LagGuard lagGuard;
    private final LeakDetector leakDetector;
    private final NetworkAudit networkAudit;
    private final AuditLogger audit;
    private final PluginLoader pluginLoader;
    private final ZCSLogger logger;

    // ── 构造 ──────────────────────────────────────────────

    public BanHammer(CrashGuard crashGuard, LagGuard lagGuard,
                     LeakDetector leakDetector, NetworkAudit networkAudit,
                     AuditLogger audit, PluginLoader pluginLoader,
                     ZCSLogger logger, Path zcsRoot) {
        this.crashGuard = crashGuard;
        this.lagGuard = lagGuard;
        this.leakDetector = leakDetector;
        this.networkAudit = networkAudit;
        this.audit = audit;
        this.pluginLoader = pluginLoader;
        this.logger = logger;
        this.banFile = zcsRoot.resolve("security").resolve("bans.json");
    }

    // ── 自动评审 ────────────────────────────────────────

    /**
     * 每 20 tick 由 ZCSKernel.onTick() 调用一次。
     * 遍历所有已加载插件，综合 5 条规则评分，超过阈值则 ban。
     */
    public void autoReview() {
        try {
            if (pluginLoader == null) return;

            for (PluginDescriptor pd : pluginLoader.getAllPlugins()) {
                String pluginId = pd.getPluginId();
                TrustLevel trust = pd.getTrustLevel();

                // 跳过已 ban 和内核插件
                if (trust == TrustLevel.BLACKLISTED) continue;
                if ("zcslib".equals(pluginId)) continue;

                long score = 0;
                StringBuilder reasonBuilder = new StringBuilder();

                // 条件 1: 频繁崩溃
                if (crashGuard != null) {
                    int crashCount = crashGuard.getCrashCount(pluginId);
                    if (crashCount > 5) {
                        score += SCORE_CRASH_FREQUENT;
                        reasonBuilder.append("crash(").append(crashCount).append(")");
                    }
                }

                // 条件 2: 连续违规
                if (lagGuard != null) {
                    int violationCount = lagGuard.getViolationCount(pluginId);
                    if (violationCount >= 3) {
                        score += SCORE_VIOLATION_REPEAT;
                        if (reasonBuilder.length() > 0) reasonBuilder.append("+");
                        reasonBuilder.append("violation(").append(violationCount).append(")");
                    }
                }

                // 条件 3: chunk 泄漏（使用每个插件的贡献，而非全局指标）
                if (leakDetector != null) {
                    Integer contrib = leakDetector.getPluginChunkContrib().get(pluginId);
                    int chunkContrib = (contrib != null) ? contrib : 0;
                    if (chunkContrib > 50) {
                        score += SCORE_CHUNK_LEAK;
                        if (reasonBuilder.length() > 0) reasonBuilder.append("+");
                        reasonBuilder.append("chunk(").append(chunkContrib).append(")");
                    }
                }

                // 条件 4: 网络异常
                if (networkAudit != null && networkAudit.detectAnomaly(pluginId)) {
                    score += SCORE_NETWORK_ANOMALY;
                    if (reasonBuilder.length() > 0) reasonBuilder.append("+");
                    reasonBuilder.append("network_anomaly");
                }

                // 条件 5: DreamWorker 标记
                if (isDreamWorkerFlagged(pluginId)) {
                    score += SCORE_DREAMWORKER_FLAG;
                    if (reasonBuilder.length() > 0) reasonBuilder.append("+");
                    reasonBuilder.append("dreamworker");
                }

                behaviorScores.put(pluginId, score);

                if (score >= BAN_THRESHOLD) {
                    String reason = reasonBuilder.length() > 0
                            ? reasonBuilder.toString()
                            : "score=" + score;
                    banPlugin(pluginId, reason);
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("BanHammer.autoReview() threw: %s", e.toString());
            }
        }
    }

    // ── 评分 ────────────────────────────────────────────

    public long getBehaviorScore(String pluginId) {
        return behaviorScores.getOrDefault(pluginId, 0L);
    }

    public void addScore(String pluginId, long points, String reason) {
        behaviorScores.merge(pluginId, points, Math::addExact);
        if (logger != null) {
            logger.debug("BanHammer: +%d score for %s (reason=%s total=%d)",
                    points, pluginId, reason, getBehaviorScore(pluginId));
        }
    }

    // ── Ban/Unban ───────────────────────────────────────

    /**
     * 执行 ban：
     * 1. 将插件 TrustLevel 改为 BLACKLISTED
     * 2. 写入 bans.json 持久化
     * 3. ZCSKernel 在 dispatch0 中拒绝 BLACKLISTED 插件的所有 order()
     * 4. PluginLoader 下次扫描时跳过被 ban 的 JAR
     * 5. 审计记录
     */
    public void banPlugin(String pluginId, String reason) {
        if (bannedPlugins.contains(pluginId)) return; // 已 ban

        bannedPlugins.add(pluginId);
        banReasons.put(pluginId, reason);

        // 保存原始信任级别（用于 unban 恢复）
        if (!originalTrustLevels.containsKey(pluginId) && pluginLoader != null) {
            PluginDescriptor pd = pluginLoader.getPlugin(pluginId);
            if (pd != null) {
                originalTrustLevels.put(pluginId, pd.getTrustLevel());
            }
        }

        // 通知 PluginLoader
        if (pluginLoader != null) {
            try {
                pluginLoader.demotePlugin(pluginId, TrustLevel.BLACKLISTED);
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("BanHammer: failed to demote %s: %s", pluginId, e.getMessage());
                }
            }
        }

        // 审计
        if (audit != null) {
            audit.log(TrustLevel.BLACKLISTED, pluginId, "BAN_EXECUTED",
                    "score=" + getBehaviorScore(pluginId) + " reason=" + reason);
        }

        if (logger != null) {
            logger.warn("BAN_EXECUTED: plugin=%s score=%d reason=%s",
                    pluginId, getBehaviorScore(pluginId), reason);
        }

        // 持久化
        saveBans();
    }

    /**
     * 解除 ban。
     */
    public void unbanPlugin(String pluginId) {
        bannedPlugins.remove(pluginId);
        banReasons.remove(pluginId);
        behaviorScores.remove(pluginId);

        // 恢复到原始信任级别（而非固定 S）
        TrustLevel restoreLevel = originalTrustLevels.getOrDefault(pluginId, TrustLevel.S);
        originalTrustLevels.remove(pluginId);

        if (pluginLoader != null) {
            try {
                pluginLoader.demotePlugin(pluginId, restoreLevel);
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("BanHammer: failed to unban %s: %s", pluginId, e.getMessage());
                }
            }
        }

        if (audit != null) {
            audit.log(TrustLevel.N, pluginId, "UNBAN_EXECUTED", "Ban lifted");
        }

        saveBans();
    }

    // ── 查询 ────────────────────────────────────────────

    public boolean isBanned(String pluginId) {
        return bannedPlugins.contains(pluginId);
    }

    public String getBanReason(String pluginId) {
        return banReasons.get(pluginId);
    }

    public Set<String> getBannedPlugins() {
        return Collections.unmodifiableSet(bannedPlugins);
    }

    // ── 签名黑名单 ──────────────────────────────────────

    public void addSignatureToBlacklist(String signature) {
        signatureBlacklist.add(signature);
    }

    public boolean isSignatureBlacklisted(String signature) {
        return signatureBlacklist.contains(signature);
    }

    // ── 持久化 ──────────────────────────────────────────

    /**
     * 写 bans.json（JSON 数组格式）。
     */
    public synchronized void saveBans() {
        try {
            Files.createDirectories(banFile.getParent());
            StringBuilder sb = new StringBuilder("{\n");
            // banned plugins array
            sb.append("  \"banned\": [\n");
            boolean first = true;
            for (String id : bannedPlugins) {
                if (!first) sb.append(",\n");
                first = false;
                String reason = banReasons.getOrDefault(id, "unknown");
                long score = behaviorScores.getOrDefault(id, 0L);
                String escapedId = id.replace("\\", "\\\\").replace("\"", "\\\"");
                String escapedReason = reason.replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append(String.format(
                        "    {\"pluginId\":\"%s\",\"score\":%d,\"reason\":\"%s\",\"timestamp\":%d}",
                        escapedId, score, escapedReason, System.currentTimeMillis()));
            }
            sb.append("\n  ],\n");
            // signature blacklist array
            sb.append("  \"signatures\": [\n");
            first = true;
            for (String sig : signatureBlacklist) {
                if (!first) sb.append(",\n");
                first = false;
                String escapedSig = sig.replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("    \"").append(escapedSig).append("\"");
            }
            sb.append("\n  ]\n}\n");
            Files.writeString(banFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (logger != null) {
                logger.error("BanHammer.saveBans() failed: %s", e.getMessage());
            }
        }
    }

    /**
     * 读 bans.json，恢复 bannedPlugins 集合。
     */
    public synchronized void loadBans() {
        if (!Files.exists(banFile)) return;
        try {
            String content = Files.readString(banFile, StandardCharsets.UTF_8);
            // 简单 JSON 解析（不依赖 Gson/Jackson）
            parseBansJson(content);
        } catch (IOException e) {
            if (logger != null) {
                logger.error("BanHammer.loadBans() failed: %s", e.getMessage());
            }
        }
    }

    private void parseBansJson(String json) {
        // 格式: {"banned": [{"pluginId":"x","score":n,"reason":"r","timestamp":t}, ...],
        //         "signatures": ["sig1", "sig2", ...]}
        // 兼容旧格式: [{"pluginId":"x","score":n,"reason":"r","timestamp":t}, ...]

        // 检测新格式
        if (json.trim().startsWith("{")) {
            parseBansJsonNew(json);
        } else {
            parseBansJsonLegacy(json);
        }
    }

    /**
     * 新 JSON 对象格式解析（含签名黑名单）。
     */
    private void parseBansJsonNew(String json) {
        // 提取 banned 数组
        int bannedStart = json.indexOf("\"banned\":");
        if (bannedStart > 0) {
            int arrStart = json.indexOf("[", bannedStart);
            int arrEnd = json.lastIndexOf("]");
            if (arrStart > 0 && arrEnd > arrStart) {
                String bannedSection = json.substring(arrStart + 1, arrEnd);
                parseBanEntries(bannedSection);
            }
        }
        // 提取 signatures 数组
        int sigStart = json.indexOf("\"signatures\":");
        if (sigStart > 0) {
            int arrStart = json.indexOf("[", sigStart);
            int arrEnd = json.indexOf("]", arrStart);
            if (arrStart > 0 && arrEnd > arrStart) {
                String sigSection = json.substring(arrStart + 1, arrEnd);
                for (String part : sigSection.split(",")) {
                    String sig = part.trim().replaceAll("^\"|\"$", "")
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                    if (!sig.isEmpty()) {
                        signatureBlacklist.add(sig);
                    }
                }
            }
        }
    }

    /**
     * 旧 JSON 数组格式解析（向后兼容）。
     */
    private void parseBansJsonLegacy(String json) {
        parseBanEntries(json);
    }

    private void parseBanEntries(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("//") || line.startsWith("#")) continue;
            if (line.equals("[") || line.equals("]")) continue;

            // 提取 pluginId
            int idStart = line.indexOf("\"pluginId\":\"");
            if (idStart < 0) continue;
            idStart += "\"pluginId\":\"".length();
            int idEnd = line.indexOf("\"", idStart);
            if (idEnd < 0) continue;
            String pluginId = line.substring(idStart, idEnd)
                    .replace("\\\"", "\"").replace("\\\\", "\\");

            // 提取 score (支持 long)
            long score = 0;
            int scoreStart = line.indexOf("\"score\":");
            if (scoreStart >= 0) {
                scoreStart += "\"score\":".length();
                int scoreEnd = line.indexOf(",", scoreStart);
                if (scoreEnd < 0) scoreEnd = line.indexOf("}", scoreStart);
                if (scoreEnd > scoreStart) {
                    try {
                        score = Long.parseLong(line.substring(scoreStart, scoreEnd).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 提取 reason
            String reason = "unknown";
            int reasonStart = line.indexOf("\"reason\":\"");
            if (reasonStart >= 0) {
                reasonStart += "\"reason\":\"".length();
                int reasonEnd = line.indexOf("\"", reasonStart);
                if (reasonEnd > reasonStart) {
                    reason = line.substring(reasonStart, reasonEnd)
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                }
            }

            bannedPlugins.add(pluginId);
            banReasons.put(pluginId, reason);
            behaviorScores.put(pluginId, score);

            // 保存原始级别（加载时只能从 PluginLoader 获取当前级别）
            if (pluginLoader != null) {
                PluginDescriptor pd = pluginLoader.getPlugin(pluginId);
                if (pd != null && !originalTrustLevels.containsKey(pluginId)) {
                    originalTrustLevels.put(pluginId, pd.getTrustLevel());
                }
                pluginLoader.demotePlugin(pluginId, TrustLevel.BLACKLISTED);
            }
        }
    }

    // ── DreamWorker 标记检测 ─────────────────────────────

    private boolean isDreamWorkerFlagged(String pluginId) {
        // DreamWorker 标记检测：检查 PEC metadata 或信任分类线索
        if (pluginLoader != null) {
            PluginDescriptor pd = pluginLoader.getPlugin(pluginId);
            if (pd != null) {
                // 线索 1：A 级（Auto-Adapt）无 PEC → 高度可疑
                if (pd.getTrustLevel() == TrustLevel.A && pd.getPec() == null) {
                    return true;
                }
                // 线索 2：PEC 中 contractSchema 标记为 "dreamworker"
                if (pd.getPec() != null
                        && pd.getPec().contractSchema != null
                        && pd.getPec().contractSchema.contains("dreamworker")) {
                    return true;
                }
                // 线索 3：fallbackLabel 中含有 "dream" 标记
                if (pd.getPec() != null
                        && pd.getPec().fallbackLabel != null
                        && pd.getPec().fallbackLabel.toLowerCase().contains("dream")) {
                    return true;
                }
            }
        }
        return false;
    }
}
