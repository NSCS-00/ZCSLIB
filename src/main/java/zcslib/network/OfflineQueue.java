package zcslib.network;

import zcslib.log.ZCSLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Offline queue for main packet sub-packets when the aggregator is unreachable.
 * <p>
 * <b>Strategies:</b>
 * <ul>
 *   <li>{@code RETRY_LATER} — write to disk, drain on reconnect (default)</li>
 *   <li>{@code DISCARD} — silently drop, log ERROR</li>
 * </ul>
 * <p>
 * <b>Storage:</b> {@code config/DLZstudio/ZCSLIB/plugins/{pluginId}/offline_queue/}
 * <p>
 * <b>Limits:</b> 50 MB hard cap per plugin; exceeding forces DISCARD.
 * <p>
 * <b>Drain:</b> On aggregator reconnect, oldest-first replay.
 */
public class OfflineQueue {

    private static final ZCSLogger LOG = ZCSLogger.forKernel("offline-queue");
    private static final long MAX_QUEUE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB

    public enum Strategy { RETRY_LATER, DISCARD, DEGRADE_TO_STANDARD }

    private final ZCSNetwork network;
    private Strategy strategy = Strategy.RETRY_LATER;
    private final Path baseDir;

    OfflineQueue(ZCSNetwork network) {
        this.network = network;
        // Place under ZCSLIB data directory
        this.baseDir = Path.of("config", "DLZstudio", "ZCSLIB", "offline_queue");
    }

    void setStrategy(Strategy s) {
        this.strategy = s;
        LOG.info("Offline strategy set to {}", s);
    }

    Strategy getStrategy() {
        return strategy;
    }

    /**
     * Enqueue a failed main packet payload (JSON array of sub-packets) to disk.
     * Called when aggregator send fails.
     */
    void enqueue(String packetJson) {
        if (strategy == Strategy.DISCARD) {
            LOG.error("Offline strategy DISCARD — dropping packet");
            return;
        }

        try {
            long currentSize = queueSizeBytes();
            long packetSize = packetJson.getBytes(StandardCharsets.UTF_8).length;

            if (currentSize + packetSize > MAX_QUEUE_SIZE_BYTES) {
                LOG.error("Offline queue exceeded 50MB limit — dropping oldest entries");
                trimToLimit(packetSize);
            }

            Files.createDirectories(baseDir);

            // Filename: timestamp_seq.json for deterministic ordering
            String filename = String.format("%d_%d.json",
                    System.currentTimeMillis(), network.getSequence());
            Path file = baseDir.resolve(filename);
            Files.writeString(file, packetJson, StandardCharsets.UTF_8);

            LOG.info("Queued offline — {} ({} bytes, total ~{} MB)",
                    filename, packetSize, currentSize / 1024 / 1024);

        } catch (IOException e) {
            LOG.error("Failed to enqueue offline packet: {}", e.getMessage());
        }
    }

    /**
     * Drain the offline queue — send all stored packets oldest-first.
     * Called on aggregator reconnect.
     */
    void drain() {
        try {
            Files.createDirectories(baseDir);

            // Collect all .json files, sort by name (timestamp prefix)
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.json")) {
                ds.forEach(files::add);
            }

            if (files.isEmpty()) return;

            files.sort(Comparator.naturalOrder()); // filename = timestamp_seq, natural order = oldest first

            LOG.info("Draining {} offline packet(s)...", files.size());

            int sent = 0;
            int failed = 0;

            for (Path file : files) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    network.flushAndSend(content, null);
                    Files.delete(file);
                    sent++;
                } catch (Exception e) {
                    LOG.warn("Failed to drain {} — {}", file.getFileName(), e.getMessage());
                    failed++;
                    // Stop draining on first failure — aggregator might go down again
                    break;
                }
            }

            LOG.info("Drain complete — {} sent, {} failed, {} remaining",
                    sent, failed, files.size() - sent);

        } catch (IOException e) {
            LOG.error("Drain failed: {}", e.getMessage());
        }
    }

    // —— Size management ——

    private long queueSizeBytes() throws IOException {
        Files.createDirectories(baseDir);
        long total = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path p : ds) {
                total += Files.size(p);
            }
        }
        return total;
    }

    private void trimToLimit(long neededBytes) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.json")) {
            ds.forEach(files::add);
        }

        files.sort(Comparator.naturalOrder()); // oldest first

        long current = queueSizeBytes();
        long target = MAX_QUEUE_SIZE_BYTES - neededBytes;

        for (Path f : files) {
            if (current <= target) break;
            long size = Files.size(f);
            Files.delete(f);
            current -= size;
            LOG.info("Trimmed old offline packet: {}", f.getFileName());
        }
    }
}
