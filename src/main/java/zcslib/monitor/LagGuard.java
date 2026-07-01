package zcslib.monitor;

import zcslib.api.TrustLevel;
import zcslib.log.AuditLogger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P15 — 插件超时中断。
 *
 * <p>包装 {@code orderTraced()}，对每个插件调用设置超时上限，
 * 超时则 {@code Thread.interrupt()} + 审计记录。
 *
 * <p>实现方案：watchdog 线程（推荐），不依赖 Future。
 *
 * <p>线程模型：主线程调用 guard()，watchdog 线程超时中断。
 */
public class LagGuard {

    /** 默认超时（毫秒）。 */
    public static final long DEFAULT_TIMEOUT_MS = 50;

    private final long timeoutMs;

    // ── 违规追踪（滑动窗口 60s） ──
    private final Map<String, Deque<Long>> violationTimes = new ConcurrentHashMap<>();
    private static final int MAX_VIOLATIONS = 3;
    private static final long VIOLATION_WINDOW_MS = 60_000;

    // ── 依赖 ──
    private final AuditLogger audit;

    // ── 构造 ──────────────────────────────────────────────

    public LagGuard(AuditLogger audit) {
        this(audit, DEFAULT_TIMEOUT_MS);
    }

    public LagGuard(AuditLogger audit, long timeoutMs) {
        this.audit = audit;
        this.timeoutMs = Math.max(1, Math.min(timeoutMs, 60_000)); // 1ms ~ 60s 安全范围
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    // ── 调用包装 ────────────────────────────────────────

    /**
     * 在超时保护下执行 action。
     *
     * <p>实现：启一个 daemon watchdog 线程，sleep(timeoutMs) 后检查 action
     * 是否完成；未完成则调用当前线程的 {@code interrupt()}。
     *
     * @param pluginId 调用插件 ID
     * @param command  命令名（用于审计）
     * @param action   要执行的业务逻辑
     * @return true = 正常完成，false = 超时被中断
     */
    public boolean guard(String pluginId, String command, Runnable action) {
        Thread currentThread = Thread.currentThread();
        boolean[] completed = { false };
        boolean[] timedOut = { false };

        // Watchdog 线程
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                // watchdog 自己被中断 → action 已完成
                return;
            }
            // 超时：检查 action 是否完成
            synchronized (completed) {
                if (!completed[0]) {
                    timedOut[0] = true;
                    currentThread.interrupt();
                }
            }
        }, "ZCSLIB-LagGuard-" + pluginId);
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            action.run();
            synchronized (completed) {
                completed[0] = true;
            }
            watchdog.interrupt(); // 通知 watchdog 停止等待
            return true;
        } catch (Exception e) {
            synchronized (completed) {
                completed[0] = true;
            }
            watchdog.interrupt();

            // 检查是否是超时引起的中断（必须在同步块中读取，确保可见性）
            boolean wasTimedOut;
            synchronized (completed) {
                wasTimedOut = timedOut[0];
            }
            if (wasTimedOut) {
                // 清除中断标志并记录
                Thread.interrupted();
                recordViolation(pluginId, command);
                return false;
            }
            // 其他异常重新抛出（由 CrashGuard 捕获）
            throw e;
        }
    }

    // ── 违规记录 ────────────────────────────────────────

    private void recordViolation(String pluginId, String command) {
        long now = System.currentTimeMillis();
        Deque<Long> times = violationTimes.computeIfAbsent(pluginId,
                k -> new ArrayDeque<>());
        synchronized (times) {
            // 清理过期条目
            while (!times.isEmpty() && now - times.peekFirst() > VIOLATION_WINDOW_MS) {
                times.pollFirst();
            }
            times.addLast(now);
        }

        if (audit != null) {
            audit.log(TrustLevel.S, pluginId, "LAG_TIMEOUT",
                    "timeout=" + timeoutMs + "ms cmd=" + command
                    + " violations=" + times.size() + "/" + VIOLATION_WINDOW_MS + "ms");
        }
    }

    // ── 违规查询 ────────────────────────────────────────

    /**
     * 60s 滑动窗口内违规次数是否超过阈值。
     */
    public boolean isViolating(String pluginId) {
        return getViolationCount(pluginId) >= MAX_VIOLATIONS;
    }

    /**
     * 返回 60s 滑动窗口内的违规次数。
     */
    public int getViolationCount(String pluginId) {
        Deque<Long> times = violationTimes.get(pluginId);
        if (times == null) return 0;
        long now = System.currentTimeMillis();
        synchronized (times) {
            times.removeIf(t -> now - t > VIOLATION_WINDOW_MS);
            return times.size();
        }
    }

    /**
     * 重置指定插件的违规记录。
     */
    public void resetViolations(String pluginId) {
        violationTimes.remove(pluginId);
    }

    // ── 查询 ────────────────────────────────────────────

    /** 所有违规插件的快照（不可变）。 */
    public Map<String, Integer> getAllViolationCounts() {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (String id : violationTimes.keySet()) {
            int c = getViolationCount(id);
            if (c > 0) result.put(id, c);
        }
        return Collections.unmodifiableMap(result);
    }
}
