package zcslib.security;

import zcslib.api.TrustLevel;
import zcslib.log.AuditLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P16 — 网络包审计。
 *
 * <p>拦截 ZCSNetwork 每个出/入包，记录 IP/插件/大小/耗时，
 * 检测突发流量和大载荷。
 *
 * <p>线程模型：网络线程写入，任意线程读取。
 */
public class NetworkAudit {

    // ── 环形审计缓冲 ──
    private static final int MAX_ENTRIES = 500;
    private final NetworkEntry[] entries = new NetworkEntry[MAX_ENTRIES];
    private volatile int entryIdx;
    private volatile int entryCount;

    // 环形缓冲读写锁
    private final Object ringLock = new Object();

    // ── 插件流量统计 ──
    private final Map<String, PluginNetworkStats> statsMap = new ConcurrentHashMap<>();

    // ── 突发检测 ──
    private static final int BURST_THRESHOLD = 10;     // 10 包/秒
    private static final long BURST_WINDOW_MS = 1000;
    private static final int LARGE_PAYLOAD_BYTES = 1024 * 1024; // 1 MB

    // ── 依赖 ──
    private final AuditLogger audit;

    // ── 类型 ──

    public enum Direction { OUTBOUND, INBOUND }

    public record NetworkEntry(
            Direction direction,
            String pluginId,
            String target,           // URL 或 IP
            int sizeBytes,
            long latencyMs,
            long timestamp,
            TrustLevel trust
    ) {}

    public record PluginNetworkStats(
            long totalBytesOut,
            long totalBytesIn,
            int requestCount,
            long lastRequestTime,
            int burstCount          // BURST_WINDOW_MS 内的请求数
    ) {}

    // ── 构造 ──────────────────────────────────────────────

    public NetworkAudit(AuditLogger audit) {
        this.audit = audit;
    }

    // ── 记录 ────────────────────────────────────────────

    /**
     * 记录出站请求。
     * 由 ZCSNetwork 在每个 HTTP 请求前后调用。
     */
    public void logOutbound(String pluginId, String target,
                            int sizeBytes, long latencyMs, TrustLevel trust) {
        NetworkEntry entry = new NetworkEntry(Direction.OUTBOUND, pluginId,
                target, sizeBytes, latencyMs, System.currentTimeMillis(), trust);
        writeEntry(entry);
        updateStats(pluginId, sizeBytes, 0, true);
        checkAnomalies(pluginId);
    }

    /**
     * 记录入站数据包。
     * 由 ZCSNetwork 在接收包时调用。
     */
    public void logInbound(String source, String packetType, int sizeBytes) {
        NetworkEntry entry = new NetworkEntry(Direction.INBOUND, source,
                packetType, sizeBytes, 0, System.currentTimeMillis(), TrustLevel.N);
        writeEntry(entry);
        updateStats(source, 0, sizeBytes, false);
    }

    private void writeEntry(NetworkEntry entry) {
        synchronized (ringLock) {
            int idx = entryIdx;
            entries[idx] = entry;
            entryIdx = (idx + 1) % MAX_ENTRIES;
            if (entryCount < MAX_ENTRIES) entryCount++;
        }
    }

    private void updateStats(String pluginId, long bytesOut, long bytesIn, boolean isOutbound) {
        statsMap.compute(pluginId, (id, existing) -> {
            PluginNetworkStats base = (existing != null) ? existing
                    : new PluginNetworkStats(0, 0, 0, 0, 0);
            long now = System.currentTimeMillis();

            // 突发计数：如果距上次请求在 BURST_WINDOW_MS 内，+1；否则重置
            int newBurst;
            if (base.lastRequestTime() > 0
                    && now - base.lastRequestTime() < BURST_WINDOW_MS) {
                newBurst = base.burstCount() + 1;
            } else {
                newBurst = 1;
            }

            return new PluginNetworkStats(
                    base.totalBytesOut() + bytesOut,
                    base.totalBytesIn() + bytesIn,
                    base.requestCount() + 1,
                    now,
                    newBurst
            );
        });
    }

    // ── 异常检测 ────────────────────────────────────────

    private void checkAnomalies(String pluginId) {
        if (detectBurst(pluginId)) {
            if (audit != null) {
                audit.log(TrustLevel.S, pluginId, "NETWORK_BURST",
                        "Burst detected: " + getStats(pluginId).burstCount()
                        + " requests in " + BURST_WINDOW_MS + "ms");
            }
        }
        if (detectLargePayload(pluginId)) {
            if (audit != null) {
                audit.log(TrustLevel.S, pluginId, "NETWORK_LARGE_PAYLOAD",
                        "Large payload detected: " + getStats(pluginId).totalBytesOut()
                        + " bytes out");
            }
        }
    }

    /**
     * 检测突发流量：BURST_WINDOW_MS 内 > BURST_THRESHOLD 次请求。
     */
    public boolean detectBurst(String pluginId) {
        PluginNetworkStats stats = statsMap.get(pluginId);
        return stats != null && stats.burstCount() > BURST_THRESHOLD;
    }

    /**
     * 检测大载荷：最近 entryCount 条记录中该插件是否有包超过 LARGE_PAYLOAD_BYTES。
     */
    public boolean detectLargePayload(String pluginId) {
        synchronized (ringLock) {
            for (int i = 0; i < entryCount; i++) {
                int idx = (entryIdx - 1 - i + MAX_ENTRIES) % MAX_ENTRIES;
                NetworkEntry e = entries[idx];
                if (e != null && e.pluginId().equals(pluginId)
                        && e.sizeBytes() > LARGE_PAYLOAD_BYTES) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 复合异常检测：burst + large payload。
     */
    public boolean detectAnomaly(String pluginId) {
        return detectBurst(pluginId) || detectLargePayload(pluginId);
    }

    // ── 统计查询 ────────────────────────────────────────

    public PluginNetworkStats getStats(String pluginId) {
        return statsMap.get(pluginId);
    }

    public Map<String, PluginNetworkStats> getAllStats() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(statsMap));
    }

    /**
     * 返回最近 n 条网络审计记录。
     */
    public List<NetworkEntry> getRecent(int n) {
        List<NetworkEntry> list = new ArrayList<>();
        synchronized (ringLock) {
            int count = Math.min(n, entryCount);
            int start = (entryIdx - count + MAX_ENTRIES) % MAX_ENTRIES;
            for (int i = 0; i < count; i++) {
                NetworkEntry e = entries[(start + i) % MAX_ENTRIES];
                if (e != null) list.add(e);
            }
        }
        return Collections.unmodifiableList(list);
    }
}
