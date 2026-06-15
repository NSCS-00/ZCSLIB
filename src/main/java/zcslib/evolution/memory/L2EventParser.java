// ZCSLIB Evolution - L2 Event Parser
// Parse L2Event.toString() output back into L2Event objects
// Pure Java SE (java.base only)
package zcslib.evolution.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@link L2Event#toString()} output.
 * <p>
 * Format: {@code [T=12345][pluginId][subsystem:action] -> RESULT (123ms)}
 * <p>
 * Graceful: returns null on parse failure, caller handles.
 */
public final class L2EventParser {

    // [T=114514][Plugin-B][scheduler:compute] -> TIMEOUT (48ms)
    private static final Pattern PATTERN = Pattern.compile(
            "\\[T=(\\d+)]\\[([^]]+)]\\[([^:]+):([^]]+)]\\s*->\\s*(\\S+)\\s*\\((\\d+)ms\\)");

    private L2EventParser() {} // utility

    public static L2Event parse(String line) {
        if (line == null || line.isBlank()) return null;
        Matcher m = PATTERN.matcher(line.strip());
        if (!m.matches()) return null;

        try {
            long tick = Long.parseLong(m.group(1));
            String pluginId = m.group(2);
            String subsystem = m.group(3);
            String action = m.group(4);
            L2Event.Result result = parseResult(m.group(5));
            long durationMs = Long.parseLong(m.group(6));
            return new L2Event(tick, pluginId, subsystem, action, result, durationMs);
        } catch (Exception e) {
            return null; // malformed entry
        }
    }

    private static L2Event.Result parseResult(String s) {
        try {
            return L2Event.Result.valueOf(s);
        } catch (IllegalArgumentException e) {
            return L2Event.Result.UNKNOWN;
        }
    }
}
