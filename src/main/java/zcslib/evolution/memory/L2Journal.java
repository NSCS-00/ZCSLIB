// ZCSLIB Evolution - L2 Journal
// Single-run append-only event log, per-plugin
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only L2 journal for one plugin, one run cycle.
 * <p>
 * File: {@code plugins/{id}/memory/l2/{yyyy-MM-dd_HHmmss}.zcslog}
 * <p>
 * On dream start: read all events → analyse → delete file.
 * <p>
 * Thread-safe writer, single-reader on dream.
 */
public class L2Journal implements AutoCloseable {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
                    .withZone(ZoneId.systemDefault());

    private final String pluginId;
    private final Path file;
    private BufferedWriter writer;
    private boolean closed = false;

    /**
     * Create and open a new L2 journal.
     *
     * @param l2Dir  per-plugin {@code memory/l2/} directory (created if needed)
     */
    public L2Journal(String pluginId, Path l2Dir) throws IOException {
        this.pluginId = pluginId;
        Files.createDirectories(l2Dir);
        String name = FILE_TS.format(Instant.now()) + ".zcslog";
        this.file = l2Dir.resolve(name);
        this.writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Append one event.
     */
    public synchronized void append(L2Event event) throws IOException {
        if (closed) return;
        writer.write(event.toString());
        writer.newLine();
        writer.flush(); // flush each event; acceptable throughput for L2
    }

    /**
     * Read all events from this journal (for dream analysis).
     */
    public List<L2Event> readAll() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<L2Event> events = new ArrayList<>(lines.size());
        for (String line : lines) {
            L2Event e = L2EventParser.parse(line);
            if (e != null) events.add(e);
        }
        return events;
    }

    public Path file() { return file; }
    public String pluginId() { return pluginId; }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (writer != null) {
            writer.close();
        }
    }
}
