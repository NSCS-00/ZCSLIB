package zcslib.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-tick sub-packet buffer that assembles the main packet.
 * <p>
 * Plugins call {@link ZCSNetwork#sendMain} during their tick logic,
 * which calls {@link #enqueue}. At tick-end, the kernel triggers
 * {@link #flush} to assemble and send the aggregated main packet.
 * <p>
 * <b>Merging:</b> Multiple {@code sendMain} calls from the same plugin
 * within one tick are merged into a single entry (last-write-wins).
 * <p>
 * <b>Heartbeat:</b> If no sub-packets are enqueued this tick, a
 * heartbeat-only packet is produced to keep the aggregator aware
 * of the client's online status.
 */
public class MainPacketAssembler {

    private final ZCSNetwork network;

    // Enqueued entries for current tick, thread-safe
    private final List<Entry> buffer = new CopyOnWriteArrayList<>();

    // Whether heartbeat was injected in last flush
    private boolean lastWasHeartbeat = false;

    MainPacketAssembler(ZCSNetwork network) {
        this.network = network;
    }

    /**
     * Enqueue a sub-packet for the current tick's main packet.
     * Same plugin + same packetName → merge (last-write-wins).
     */
    void enqueue(String packetName, Object data) {
        buffer.add(new Entry(packetName, data, System.currentTimeMillis()));
    }

    /**
     * Assemble the main packet payload and send.
     * Called by kernel at tick-end.
     */
    public void flush() {
        if (buffer.isEmpty()) {
            // Heartbeat
            if (!lastWasHeartbeat) {
                network.flushAndSend("[]", null); // empty packets + heartbeat marker
                lastWasHeartbeat = true;
            }
            return;
        }

        lastWasHeartbeat = false;

        // Deduplicate: per plugin+name, keep latest
        List<Entry> merged = deduplicate(buffer);

        // Sort by priority (lower number = higher priority) then by timestamp
        merged.sort((a, b) -> {
            int p = Integer.compare(a.priority, b.priority);
            return p != 0 ? p : Long.compare(a.timestamp, b.timestamp);
        });

        // Build JSON array of sub-packets
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(merged.get(i).toJson());
        }
        sb.append("]");

        // Clear buffer for next tick
        buffer.clear();

        network.flushAndSend(sb.toString(), null);
    }

    /**
     * Deduplicate entries: per (pluginId, packetName), keep latest.
     */
    private List<Entry> deduplicate(List<Entry> raw) {
        // Simple approach: iterate reversed, keep first occurrence of each key
        List<Entry> result = new ArrayList<>();
        java.util.Set<String> seen = java.util.HashSet.newHashSet(raw.size());

        for (int i = raw.size() - 1; i >= 0; i--) {
            Entry e = raw.get(i);
            String key = e.key();
            if (seen.add(key)) {
                result.add(e);
            }
        }

        Collections.reverse(result);
        return result;
    }

    /**
     * A single enqueued sub-packet entry.
     */
    static class Entry {
        final String name;
        final Object data;
        final long timestamp;
        final int priority;

        Entry(String name, Object data, long timestamp) {
            this.name = name;
            this.data = data;
            this.timestamp = timestamp;
            this.priority = 0; // default, can be extended later per-plugin
        }

        String key() {
            return name;
        }

        String toJson() {
            // Per ZCNET_PACKET_SPEC.md v1.1.0 sub-packet format:
            // default to HTTP POST with JSON body
            String path = "/api/zcnet/" + name;
            String body = data instanceof String s ? s : data.toString();
            // Escape quotes in body
            body = body.replace("\\", "\\\\").replace("\"", "\\\"");
            return String.format(
                    "{\"name\":\"%s\",\"protocol\":\"HTTP\",\"method\":\"POST\"," +
                    "\"path\":\"%s\",\"headers\":{\"Content-Type\":\"application/json\"}," +
                    "\"body\":%s}",
                    name, path, body);
        }
    }
}
