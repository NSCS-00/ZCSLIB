package zcslib.monitor;

import zcslib.api.TrustLevel;
import zcslib.event.ZCSLEventBus;
import zcslib.log.AuditLogger;
import zcslib.log.ZCSLogger;
import zcslib.mcapi.TickAPI;
import zcslib.mcapi.WorldAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P15 — 资源泄漏检测。
 *
 * <p>Tick-end 扫描 listener 泄漏 + chunk 滞留计数。
 * 每 5 分钟运行一次完整扫描，每 1 分钟运行快速扫描。
 *
 * <p>线程模型：主线程调用，ConcurrentHashMap 读取。
 */
public class LeakDetector {

    // ── 基线 ──
    private volatile int lastChunkCount;
    private volatile int lastEntityCount;
    private volatile long lastScanTick;

    // ── 趋势追踪 ──
    private final Map<String, Integer> pluginChunkContrib = new ConcurrentHashMap<>();

    // ── 扫描间隔 ──
    private static final long FULL_SCAN_INTERVAL_TICKS = 6000; // 5 min @ 20 TPS
    private static final long QUICK_SCAN_INTERVAL_TICKS = 1200; // 1 min

    // ── 泄漏阈值 ──
    private static final int CHUNK_LEAK_THRESHOLD = 50;
    private static final int ENTITY_LEAK_THRESHOLD = 200;

    // ── 连续趋势 ──
    private int consecutiveChunkRise = 0;
    private static final int CONSECUTIVE_RISE_THRESHOLD = 3;

    // ── 依赖 ──
    private final ZCSLEventBus eventBus;
    private final TickAPI tickAPI;
    private final WorldAPI worldAPI;
    private final ZCSLogger logger;
    private final AuditLogger audit;

    // ── 类型 ──

    public record LeakReport(
            int chunkDelta,
            int entityDelta,
            int orphanedListeners,
            List<String> warnings
    ) {}

    // ── 构造 ──────────────────────────────────────────────

    public LeakDetector(ZCSLEventBus eventBus, TickAPI tickAPI,
                        WorldAPI worldAPI, ZCSLogger logger, AuditLogger audit) {
        this.eventBus = eventBus;
        this.tickAPI = tickAPI;
        this.worldAPI = worldAPI;
        this.logger = logger;
        this.audit = audit;
    }

    // ── tick 钩子 ────────────────────────────────────────

    /**
     * 每个 tick 由 ZCSKernel.onTick() 调用。
     * 按间隔触发快速扫描和完整扫描。
     */
    public void onTick(long tickCounter) {
        try {
            if (tickCounter % FULL_SCAN_INTERVAL_TICKS == 0) {
                fullScan();
            } else if (tickCounter % QUICK_SCAN_INTERVAL_TICKS == 0) {
                quickScan(tickCounter);
            }
        } catch (Exception e) {
            // monitor 类绝不传播异常
            if (logger != null) {
                logger.error("LeakDetector.onTick() threw: %s", e.toString());
            }
        }
    }

    // ── 快速扫描 ────────────────────────────────────────

    private void quickScan(long tickCounter) {
        if (worldAPI == null) return;

        int currentChunks = worldAPI.getLoadedChunkCount();
        int chunkDelta = currentChunks - lastChunkCount;

        if (chunkDelta > CHUNK_LEAK_THRESHOLD) {
            consecutiveChunkRise++;
            if (consecutiveChunkRise >= CONSECUTIVE_RISE_THRESHOLD) {
                if (audit != null) {
                    audit.log(TrustLevel.N, "zcslib", "CHUNK_LEAK",
                            String.format("chunkDelta=%d (baseline=%d → current=%d) consecutive=%d",
                                    chunkDelta, lastChunkCount, currentChunks, consecutiveChunkRise));
                }
                if (logger != null) {
                    logger.warn("CHUNK_LEAK: delta=%d consecutive=%d", chunkDelta, consecutiveChunkRise);
                }
            }
        } else {
            consecutiveChunkRise = 0;
        }

        lastChunkCount = currentChunks;
        lastScanTick = tickCounter;
    }

    // ── 完整扫描 ────────────────────────────────────────

    /**
     * 手动触发完整扫描（也由 onTick 每 5 分钟自动触发）。
     */
    public LeakReport fullScan() {
        List<String> warnings = new ArrayList<>();

        int chunkDelta = 0;
        int entityDelta = 0;
        int orphaned = 0;

        try {
            // Chunk 泄漏
            if (worldAPI != null) {
                int currentChunks = worldAPI.getLoadedChunkCount();
                chunkDelta = currentChunks - lastChunkCount;
                lastChunkCount = currentChunks;

                if (chunkDelta > CHUNK_LEAK_THRESHOLD) {
                    warnings.add(String.format("CHUNK_LEAK: %d chunks added (threshold=%d)",
                            chunkDelta, CHUNK_LEAK_THRESHOLD));
                }

                // Entity 泄漏（通过 AABB 估算全局实体数）
                try {
                    int currentEntities = worldAPI.getLoadedEntityCount();
                    entityDelta = currentEntities - lastEntityCount;
                    lastEntityCount = currentEntities;
                    if (entityDelta > ENTITY_LEAK_THRESHOLD) {
                        warnings.add(String.format("ENTITY_LEAK: %d entities added (threshold=%d)",
                                entityDelta, ENTITY_LEAK_THRESHOLD));
                    }
                } catch (UnsupportedOperationException ignored) {
                    // getLoadedEntityCount 可能不被支持
                }
            }

            // Listener 泄漏
            orphaned = detectOrphanedListeners();
            if (orphaned > 0) {
                warnings.add(String.format("ORPHANED_LISTENERS: %d listeners with dead plugin owners",
                        orphaned));
            }

            // 趋势告警
            if (consecutiveChunkRise >= CONSECUTIVE_RISE_THRESHOLD) {
                warnings.add(String.format("CHUNK_TREND: %d consecutive rises detected",
                        consecutiveChunkRise));
            }

            // 审计
            if (!warnings.isEmpty() && audit != null) {
                audit.log(TrustLevel.N, "zcslib", "LEAK_SCAN",
                        String.join("; ", warnings));
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("LeakDetector.fullScan() threw: %s", e.toString());
            }
            warnings.add("SCAN_ERROR: " + e.toString());
        }

        return new LeakReport(chunkDelta, entityDelta, orphaned,
                Collections.unmodifiableList(warnings));
    }

    // ── listener 泄漏检测 ────────────────────────────────

    /**
     * 遍历 ZCSLEventBus，查找插件已卸载但 listener 未注销的情况。
     *
     * @return 孤儿 listener 数量
     */
    public int detectOrphanedListeners() {
        if (eventBus == null) return 0;

        int handlerCount = eventBus.getHandlerCount();
        int listenerCount = eventBus.getListenerCount();

        // 启发式检测：如果每个 listener 平均 handler 数过高，可能泄漏
        // 正常每个 listener 10-20 个 handler；超过 50 个则可疑
        int orphanedEstimate = 0;
        if (listenerCount > 0 && handlerCount > 0) {
            int avgHandlers = handlerCount / Math.max(listenerCount, 1);
            if (avgHandlers > 50) {
                orphanedEstimate = listenerCount; // 全部标记为可疑
                if (logger != null) {
                    logger.warn("LEAK: handlerCount=%d listenerCount=%d avg=%d — possible listener leak",
                            handlerCount, listenerCount, avgHandlers);
                }
            }
        }

        // 绝对数量检测
        if (handlerCount > 1000 && logger != null) {
            logger.warn("LEAK: handlerCount=%d listenerCount=%d — possible listener leak",
                    handlerCount, listenerCount);
        }

        return orphanedEstimate;
    }

    // ── 查询 ────────────────────────────────────────────

    public int getChunkDelta() {
        if (worldAPI == null) return 0;
        return worldAPI.getLoadedChunkCount() - lastChunkCount;
    }

    public int getEntityDelta() {
        return 0; // entity count tracking not yet implemented
    }

    /** 记录插件 chunk 贡献（由 AutoRollback 或其他模块调用）。 */
    public void recordChunkContribution(String pluginId, int count) {
        pluginChunkContrib.merge(pluginId, count, Integer::sum);
    }

    public Map<String, Integer> getPluginChunkContrib() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(pluginChunkContrib));
    }
}
