package zcslib.monitor;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.loader.PluginLoader;
import zcslib.log.AuditLogger;
import zcslib.log.ZCSLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * P15 — 插件崩溃隔离。
 *
 * <p>单插件 try-catch 隔离，崩溃后自动降级为 S，审计记录，
 * 不中断其他插件。
 *
 * <p>不捕获 {@link ThreadDeath} / {@link OutOfMemoryError} —
 * 这些是无法恢复的 JVM 级错误。
 *
 * <p>线程模型：主线程调用，ConcurrentHashMap。
 */
public class CrashGuard {

    // ── 崩溃计数（滑动窗口 60s） ──
    private final Map<String, Deque<Long>> crashTimes = new ConcurrentHashMap<>();
    private static final int MAX_CRASHES_PER_WINDOW = 5;
    private static final long CRASH_WINDOW_MS = 60_000;

    // ── 自动降级记录 ──
    private final Set<String> autoDemoted = ConcurrentHashMap.newKeySet();

    // ── 最近崩溃记录 ──
    private static final int RECENT_CRASH_MAX = 50;
    private final CrashInfo[] recentCrashes = new CrashInfo[RECENT_CRASH_MAX];
    private volatile int recentIdx;
    private volatile int recentCount;

    // 当前 tick（由 ZCSKernel.onTick() 注入）
    private volatile long currentTick;

    // ── 依赖 ──
    private final AuditLogger audit;
    private final ZCSLogger logger;
    private final PluginLoader pluginLoader;

    // ── 类型 ──

    public record CrashInfo(
            String pluginId,
            String command,
            String exceptionType,
            String message,
            long tickCounter,
            long timestamp
    ) {}

    // ── 构造 ──────────────────────────────────────────────

    public CrashGuard(AuditLogger audit, ZCSLogger logger,
                      PluginLoader pluginLoader) {
        this.audit = audit;
        this.logger = logger;
        this.pluginLoader = pluginLoader;
    }

    /** 由 ZCSKernel.onTick() 注入当前 tick（用于 CrashInfo 记录）。 */
    public void setCurrentTick(long tick) {
        this.currentTick = tick;
    }

    // ── 执行包装 ────────────────────────────────────────

    /**
     * 把单个插件的 order() 包裹在 try-catch 中。
     *
     * @param pluginId 插件 ID
     * @param command  命令名
     * @param action   要执行的业务逻辑
     * @return 正常返回 action 结果；崩溃返回 fail
     */
    public OrderResult execute(String pluginId, String command,
                               Supplier<OrderResult> action) {
        try {
            return action.get();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Exception e) {
            // 记录崩溃
            recordCrash(pluginId, command, e);

            // 60s 内 > MAX_CRASHES_PER_WINDOW → 自动降为 S
            int crashCount = getCrashCount(pluginId);
            if (crashCount > MAX_CRASHES_PER_WINDOW) {
                autoDemote(pluginId);
            }

            return OrderResult.fail("CRASH_ISOLATED: " + e.getClass().getSimpleName()
                    + ": " + (e.getMessage() != null ? e.getMessage() : "(no message)"));
        }
    }

    // ── 崩溃记录 ────────────────────────────────────────

    private void recordCrash(String pluginId, String command, Exception e) {
        long now = System.currentTimeMillis();

        // 滑动窗口
        Deque<Long> times = crashTimes.computeIfAbsent(pluginId,
                k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CRASH_WINDOW_MS) {
                times.pollFirst();
            }
            times.addLast(now);
        }

        // 最近崩溃
        CrashInfo info = new CrashInfo(pluginId, command,
                e.getClass().getName(),
                e.getMessage() != null ? e.getMessage() : "(no message)",
                currentTick, now);
        recentCrashes[recentIdx] = info;
        recentIdx = (recentIdx + 1) % RECENT_CRASH_MAX;
        if (recentCount < RECENT_CRASH_MAX) recentCount++;

        // 审计
        if (audit != null) {
            audit.log(TrustLevel.S, pluginId, "CRASH_ISOLATED",
                    String.format("cmd=%s exception=%s: %s crashes=%d/%d",
                            command, e.getClass().getSimpleName(),
                            e.getMessage() != null ? e.getMessage() : "",
                            times.size(), MAX_CRASHES_PER_WINDOW));
        }

        if (logger != null) {
            logger.error("CRASH_ISOLATED: [%s] %s → %s: %s",
                    pluginId, command, e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "");
        }
    }

    // ── 自动降级 ────────────────────────────────────────

    private void autoDemote(String pluginId) {
        if (autoDemoted.contains(pluginId)) return; // 已降级

        autoDemoted.add(pluginId);
        if (pluginLoader != null) {
            try {
                pluginLoader.demotePlugin(pluginId, TrustLevel.S);
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("Failed to demote %s: %s", pluginId, e.getMessage());
                }
            }
        }

        if (audit != null) {
            audit.log(TrustLevel.S, pluginId, "AUTO_DEMOTE",
                    "TrustLevel → S (crashes > " + MAX_CRASHES_PER_WINDOW
                    + " in " + CRASH_WINDOW_MS + "ms)");
        }
    }

    // ── 崩溃查询 ────────────────────────────────────────

    public boolean isAutoDemoted(String pluginId) {
        return autoDemoted.contains(pluginId);
    }

    /**
     * 返回 60s 滑动窗口内的崩溃次数。
     */
    public int getCrashCount(String pluginId) {
        Deque<Long> times = crashTimes.get(pluginId);
        if (times == null) return 0;
        long now = System.currentTimeMillis();
        synchronized (times) {
            times.removeIf(t -> now - t > CRASH_WINDOW_MS);
            return times.size();
        }
    }

    /**
     * 返回最近 n 条崩溃记录。
     */
    public List<CrashInfo> getRecentCrashes(int n) {
        List<CrashInfo> list = new ArrayList<>();
        int count = Math.min(n, recentCount);
        int start = (recentIdx - count + RECENT_CRASH_MAX) % RECENT_CRASH_MAX;
        for (int i = 0; i < count; i++) {
            CrashInfo ci = recentCrashes[(start + i) % RECENT_CRASH_MAX];
            if (ci != null) list.add(ci);
        }
        return Collections.unmodifiableList(list);
    }

    // ── 手动恢复 ────────────────────────────────────────

    /**
     * 手动恢复插件的降级状态（/zcslib unban 命令）。
     */
    public void resetDemotion(String pluginId) {
        autoDemoted.remove(pluginId);
        crashTimes.remove(pluginId);
    }
}
