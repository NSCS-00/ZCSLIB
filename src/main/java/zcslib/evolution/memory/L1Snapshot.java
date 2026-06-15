// ZCSLIB Evolution - L1 Snapshot
// Immutable crash-time snapshot from L1Buffer
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Immutable snapshot produced by {@link L1Buffer#freeze()}.
 * <p>
 * Persisted to {@code memory/l1/{timestamp}.zcsl1} as
 * human-readable text for forensic analysis.
 */
public class L1Snapshot {

    private final L1Buffer.CallFrame[] frames;
    private final long frozenAtTick;
    private final long frozenAtEpochMs;

    L1Snapshot(L1Buffer.CallFrame[] frames, long frozenAtTick) {
        this.frames = frames;
        this.frozenAtTick = frozenAtTick;
        this.frozenAtEpochMs = System.currentTimeMillis();
    }

    public int frameCount() { return frames.length; }
    public L1Buffer.CallFrame frame(int i) { return frames[i]; }
    public long frozenAtTick() { return frozenAtTick; }
    public long frozenAtEpochMs() { return frozenAtEpochMs; }

    /**
     * Persist to disk under the canonical L1 directory.
     *
     * @param l1Dir the global {@code memory/l1/} directory
     * @return the written file path
     */
    public Path persist(Path l1Dir) throws Exception {
        Files.createDirectories(l1Dir);
        String name = frozenAtEpochMs + ".zcsl1";
        Path file = l1Dir.resolve(name);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ZCSLIB L1 CRASH SNAPSHOT ===\n");
        sb.append("Frozen at: ").append(Instant.ofEpochMilli(frozenAtEpochMs)).append('\n');
        sb.append("Frozen tick: ").append(frozenAtTick).append('\n');
        sb.append("Frames: ").append(frames.length).append('\n');
        sb.append("---\n");
        for (L1Buffer.CallFrame f : frames) {
            sb.append(f.toString()).append('\n');
            for (String e : f.entries()) {
                sb.append("  ").append(e).append('\n');
            }
        }
        sb.append("=== END SNAPSHOT ===\n");

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }
}
