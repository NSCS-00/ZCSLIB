package zcslib.monitor;

import zcslib.log.AuditLogger;
import zcslib.log.ZCSLogger;
import zcslib.mcapi.TickAPI;
import zcslib.mcapi.TickAPI.TickHealth;
import zcslib.mcapi.WorldAPI;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P15 — 实时性能采集。
 *
 * <p>Tick-end 采集 TPS/MSPT/JVM heap/chunk 数/实体数，写入环形缓冲，
 * 超标时触发审计告警。插件级性能通过 {@link #recordPluginOrder} 追踪。
 *
 * <p>线程模型：主线程写入（tick），任意线程读取。
 */
public class PerfMonitor {

    // ── 环形缓冲（最近 300 个采样点 ≈ 15 秒 @ 20 TPS） ──
    private static final int BUFFER_SIZE = 300;
    private final long[] tpsRing   = new long[BUFFER_SIZE];  // TPS × 100
    private final long[] msptRing  = new long[BUFFER_SIZE];  // MSPT × 1e6 (ns)
    private final long[] heapRing  = new long[BUFFER_SIZE];  // heap used bytes
    private final int[]  chunkRing = new int[BUFFER_SIZE];
    private final int[]  entityRing = new int[BUFFER_SIZE];
    private volatile int ringIdx;
    private volatile int ringCount;

    // 环形缓冲读写锁（保证 sample() 写与 snapshot() 读的可见性）
    private final Object ringLock = new Object();

    // ── 当前 tick 快照 ──
    private volatile double currentTPS;
    private volatile double currentMSPT;
    private volatile long currentHeapUsed;
    private volatile long currentHeapMax;
    private volatile int currentChunks;
    private volatile int currentEntities;
    private volatile TickHealth currentHealth = TickHealth.HEALTHY;
    private volatile long lastSampleTick;

    // ── 超标阈值与统计 ──
    private double msptWarnThreshold  = 40.0;   // MSPT > 40ms 告警
    private double msptCriticalThreshold = 48.0; // MSPT > 48ms 严重
    private double tpsWarnThreshold   = 18.0;   // TPS < 18 告警
    private double tpsCriticalThreshold = 15.0;

    // 超标计数器（累积，不清零）
    private volatile long msptWarnCount;
    private volatile long msptCriticalCount;
    private volatile long tpsWarnCount;
    private volatile long tpsCriticalCount;

    // ── 插件级性能 ──
    private final Map<String, PluginPerf> pluginPerfMap = new ConcurrentHashMap<>();

    // ── 依赖 ──
    private final TickAPI tickAPI;
    private volatile WorldAPI worldAPI;  // nullable, set after McPort init
    private final ZCSLogger logger;
    private final AuditLogger audit;

    // ── 类型 ──

    /** 插件级性能快照。 */
    public record PluginPerf(
            double avgLatencyMs,
            int timeoutCount,
            int errorCount,
            long orderCount,
            long lastActivityTick
    ) {}

    /** 全量性能快照（用于 /zcslib debug perf）。 */
    public record PerfSnapshot(
            String reason,
            long timestamp,
            double currentTPS,
            double currentMSPT,
            long heapUsedMB,
            long heapMaxMB,
            int chunks,
            int entities,
            TickHealth health,
            long msptWarnCount,
            long msptCriticalCount,
            long tpsWarnCount,
            long tpsCriticalCount,
            Map<String, PluginPerf> pluginPerf,
            List<Double> tpsHistory,
            List<Double> msptHistory,
            List<Long> heapHistoryMB
    ) {}

    // ── 构造 ──────────────────────────────────────────────

    /**
     * @param tickAPI tick API（可为 null，在 McPort 初始化后注入）
     * @param logger  内核 logger
     * @param audit   审计 logger
     */
    public PerfMonitor(TickAPI tickAPI, ZCSLogger logger, AuditLogger audit) {
        this.tickAPI = tickAPI;
        this.logger = logger;
        this.audit = audit;
    }

    /** 注入 WorldAPI（McPort 初始化后由 ZCSKernel 调用）。 */
    public void setWorldAPI(WorldAPI worldAPI) {
        this.worldAPI = worldAPI;
    }

    // ── tick-end 采集 ────────────────────────────────────

    /**
     * 从 TickAPI + Runtime 采集当前快照，写入环形缓冲。
     * 由 {@code ZCSKernel.onTick()} 调用。
     */
    public void sample() {
        try {
            // TPS / MSPT from TickAPI
            if (tickAPI != null) {
                currentTPS = tickAPI.getAverageTPS();
                currentMSPT = tickAPI.getMSPT();
                currentHealth = tickAPI.getTickHealth();
            }

            // Heap from Runtime
            Runtime rt = Runtime.getRuntime();
            currentHeapUsed = rt.totalMemory() - rt.freeMemory();
            currentHeapMax = rt.maxMemory();

            // Chunks / Entities from WorldAPI
            if (worldAPI != null) {
                currentChunks = worldAPI.getLoadedChunkCount();
                currentEntities = 0; // entity count not directly exposed; track via other means
            }

            lastSampleTick = System.nanoTime();

            // 写入环形缓冲（同步块保证 snapshot() 读取一致性）
            synchronized (ringLock) {
                int idx = ringIdx;
                tpsRing[idx]   = (long) (currentTPS * 100.0);
                msptRing[idx]  = (long) (currentMSPT * 1_000_000.0); // ms → ns
                heapRing[idx]  = currentHeapUsed;
                chunkRing[idx] = currentChunks;
                entityRing[idx] = currentEntities;
                ringIdx = (idx + 1) % BUFFER_SIZE;
                if (ringCount < BUFFER_SIZE) ringCount++;
            }

            // 超标检测
            checkThresholds();
        } catch (Exception e) {
            // monitor 绝不传播异常
            if (logger != null) {
                logger.error("PerfMonitor.sample() threw: %s", e.toString());
            }
        }
    }

    // ── 阈值检测 ────────────────────────────────────────

    private void checkThresholds() {
        if (currentMSPT > msptCriticalThreshold) {
            msptCriticalCount++;
            if (audit != null) {
                audit.log(zcslib.api.TrustLevel.N, "zcslib", "PERF_CRITICAL",
                        String.format("MSPT=%.1fms > %.1fms critical threshold",
                                currentMSPT, msptCriticalThreshold));
            }
        } else if (currentMSPT > msptWarnThreshold) {
            msptWarnCount++;
            if (audit != null) {
                audit.log(zcslib.api.TrustLevel.N, "zcslib", "PERF_WARN",
                        String.format("MSPT=%.1fms > %.1fms warn threshold",
                                currentMSPT, msptWarnThreshold));
            }
        }

        if (currentTPS < tpsCriticalThreshold && currentTPS > 0) {
            tpsCriticalCount++;
            if (audit != null) {
                audit.log(zcslib.api.TrustLevel.N, "zcslib", "PERF_CRITICAL",
                        String.format("TPS=%.1f < %.1f critical threshold",
                                currentTPS, tpsCriticalThreshold));
            }
        } else if (currentTPS < tpsWarnThreshold && currentTPS > 0) {
            tpsWarnCount++;
            if (audit != null) {
                audit.log(zcslib.api.TrustLevel.N, "zcslib", "PERF_WARN",
                        String.format("TPS=%.1f < %.1f warn threshold",
                                currentTPS, tpsWarnThreshold));
            }
        }
    }

    // ── 只读查询 ────────────────────────────────────────

    public double getCurrentTPS()          { return currentTPS; }
    public double getCurrentMSPT()         { return currentMSPT; }
    public long   getCurrentHeapUsedMB()   { return currentHeapUsed / (1024 * 1024); }
    public long   getCurrentHeapMaxMB()    { return currentHeapMax / (1024 * 1024); }
    public int    getCurrentChunks()       { return currentChunks; }
    public int    getCurrentEntities()     { return currentEntities; }
    public TickHealth getCurrentHealth()   { return currentHealth; }

    public long getMsptWarnCount()         { return msptWarnCount; }
    public long getMsptCriticalCount()     { return msptCriticalCount; }
    public long getTpsWarnCount()          { return tpsWarnCount; }
    public long getTpsCriticalCount()      { return tpsCriticalCount; }

    // ── 插件级性能 ──────────────────────────────────────

    /**
     * 记录一次插件 order 调用耗时。
     * 由 {@code ZCSKernel.orderTraced()} 调用。
     */
    public void recordPluginOrder(String pluginId, long durationMs, boolean success) {
        pluginPerfMap.compute(pluginId, (id, existing) -> {
            PluginPerf base = (existing != null) ? existing
                    : new PluginPerf(0, 0, 0, 0, 0);
            long newCount = base.orderCount() + 1;
            double newAvg = (base.avgLatencyMs() * base.orderCount() + durationMs) / newCount;
            int newTimeouts = base.timeoutCount();
            int newErrors = base.errorCount() + (success ? 0 : 1);
            return new PluginPerf(newAvg, newTimeouts, newErrors, newCount,
                    System.currentTimeMillis());
        });
    }

    /** 记录 LagGuard 超时（由 LagGuard 或 ZCSKernel 调用）。 */
    public void recordPluginTimeout(String pluginId) {
        pluginPerfMap.compute(pluginId, (id, existing) -> {
            PluginPerf base = (existing != null) ? existing
                    : new PluginPerf(0, 0, 0, 0, 0);
            return new PluginPerf(base.avgLatencyMs(), base.timeoutCount() + 1,
                    base.errorCount() + 1, base.orderCount() + 1,
                    System.currentTimeMillis());
        });
    }

    public PluginPerf getPluginPerf(String pluginId) {
        return pluginPerfMap.get(pluginId);
    }

    public Map<String, PluginPerf> getAllPluginPerf() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pluginPerfMap));
    }

    // ── 快照导出 ────────────────────────────────────────

    /**
     * 全量导出当前缓冲 + 统计。
     */
    public PerfSnapshot snapshot(String reason) {
        int count;
        java.util.List<Double> tpsHist;
        java.util.List<Double> msptHist;
        java.util.List<Long> heapHist;

        synchronized (ringLock) {
            count = ringCount;
            tpsHist = new java.util.ArrayList<>(count);
            msptHist = new java.util.ArrayList<>(count);
            heapHist = new java.util.ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                int idx = (ringIdx - count + i + BUFFER_SIZE) % BUFFER_SIZE;
                tpsHist.add(tpsRing[idx] / 100.0);
                msptHist.add(msptRing[idx] / 1_000_000.0);
                heapHist.add(heapRing[idx] / (1024 * 1024));
            }
        }

        return new PerfSnapshot(
                reason, System.currentTimeMillis(),
                currentTPS, currentMSPT,
                getCurrentHeapUsedMB(), getCurrentHeapMaxMB(),
                currentChunks, currentEntities,
                currentHealth,
                msptWarnCount, msptCriticalCount,
                tpsWarnCount, tpsCriticalCount,
                Collections.unmodifiableMap(new LinkedHashMap<>(pluginPerfMap)),
                Collections.unmodifiableList(tpsHist),
                Collections.unmodifiableList(msptHist),
                Collections.unmodifiableList(heapHist)
        );
    }

    // ── 阈值配置 ────────────────────────────────────────

    public void setMsptWarnThreshold(double v)    { this.msptWarnThreshold = v; }
    public void setMsptCriticalThreshold(double v) { this.msptCriticalThreshold = v; }
    public void setTpsWarnThreshold(double v)      { this.tpsWarnThreshold = v; }
    public void setTpsCriticalThreshold(double v)   { this.tpsCriticalThreshold = v; }

    public double getMsptWarnThreshold()    { return msptWarnThreshold; }
    public double getMsptCriticalThreshold() { return msptCriticalThreshold; }
    public double getTpsWarnThreshold()     { return tpsWarnThreshold; }
    public double getTpsCriticalThreshold()  { return tpsCriticalThreshold; }
}
