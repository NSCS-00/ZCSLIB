package zcslib.monitor;

import net.minecraft.server.MinecraftServer;
import zcslib.api.TrustLevel;
import zcslib.log.AuditLogger;
import zcslib.log.ZCSLogger;

/**
 * P15 — 定时/紧急世界存档。
 *
 * <p>定时 world save + 崩溃前紧急 forceSaveAll()。
 *
 * <p>线程模型：主线程调用，volatile 字段。
 */
public class AutoSave {

    // ── 配置 ──
    private volatile int saveIntervalTicks = 6000;  // 默认 5 分钟
    private volatile int tickCounter;
    private volatile long lastSaveTimestamp;

    // ── 紧急存盘检测 ──
    private volatile int tpsLowTickCount;
    private static final int TPS_EMERGENCY_THRESHOLD = 10;
    private static final int EMERGENCY_TICKS = 5;  // 连续 5 tick < 10 TPS → 紧急存盘
    private volatile boolean emergencySaved;

    // ── 依赖 ──
    private final MinecraftServer server;
    private final PerfMonitor perfMonitor;
    private final AuditLogger audit;
    private final ZCSLogger logger;

    // ── 构造 ──────────────────────────────────────────────

    /**
     * @param server       MC 服务器实例（可为 null，在 McPort 初始化后注入）
     * @param perfMonitor  性能监控器
     * @param audit        审计 logger
     * @param logger       内核 logger
     */
    public AutoSave(MinecraftServer server, PerfMonitor perfMonitor,
                    AuditLogger audit, ZCSLogger logger) {
        this.server = server;
        this.perfMonitor = perfMonitor;
        this.audit = audit;
        this.logger = logger;
    }

    // ── tick 钩子 ────────────────────────────────────────

    /**
     * 每个 tick 由 ZCSKernel.onTick() 调用。
     */
    public void tick() {
        try {
            tickCounter++;

            // 紧急存盘检测
            if (perfMonitor != null) {
                double tps = perfMonitor.getCurrentTPS();
                if (tps > 0 && tps < TPS_EMERGENCY_THRESHOLD) {
                    tpsLowTickCount++;
                    if (tpsLowTickCount >= EMERGENCY_TICKS && !emergencySaved) {
                        emergencySaved = true;
                        forceSave();
                        if (audit != null) {
                            audit.log(TrustLevel.N, "zcslib", "EMERGENCY_SAVE",
                                    String.format("TPS=%.1f < %d for %d consecutive ticks",
                                            tps, TPS_EMERGENCY_THRESHOLD, tpsLowTickCount));
                        }
                        if (logger != null) {
                            logger.warn("EMERGENCY_SAVE triggered — TPS=%.1f for %d ticks",
                                    tps, tpsLowTickCount);
                        }
                    }
                } else if (tps >= TPS_EMERGENCY_THRESHOLD || tps == 0) {
                    tpsLowTickCount = 0;
                    emergencySaved = false;
                }
            }

            // 定期存档（跳过 tick 0 避免启动时误触发）
            if (tickCounter > 0 && tickCounter % saveIntervalTicks == 0 && server != null) {
                forceSaveAll();
            }
        } catch (Exception e) {
            // monitor 类绝不传播异常
            if (logger != null) {
                logger.error("AutoSave.tick() threw: %s", e.toString());
            }
        }
    }

    // ── 存盘操作 ────────────────────────────────────────

    /**
     * 强制存盘所有维度 + flush 审计日志。
     */
    public void forceSave() {
        try {
            forceSaveAll();
            if (audit != null) {
                audit.flushAll();
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("AutoSave.forceSave() threw: %s", e.toString());
            }
        }
    }

    /**
     * 遍历所有维度 saveAllChunks()。
     */
    public void forceSaveAll() {
        if (server == null) return;
        try {
            // NeoForge 21.1: server.saveAllChunks(true, true, true)
            server.saveAllChunks(true, true, true);
            lastSaveTimestamp = System.currentTimeMillis();
            if (logger != null) {
                logger.debug("AutoSave: saved all chunks (tick=%d interval=%d)",
                        tickCounter, saveIntervalTicks);
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("AutoSave.forceSaveAll() threw: %s", e.toString());
            }
        }
    }

    // ── 配置 ────────────────────────────────────────────

    public void setInterval(int ticks) {
        this.saveIntervalTicks = Math.max(20, ticks); // 最少 20 ticks (1 秒)
    }

    public int getInterval() {
        return saveIntervalTicks;
    }

    // ── 状态 ────────────────────────────────────────────

    public long getLastSaveTimestamp() {
        return lastSaveTimestamp;
    }

    public boolean isEmergencySaved() {
        return emergencySaved;
    }

    public int getTickCounter() {
        return tickCounter;
    }
}
