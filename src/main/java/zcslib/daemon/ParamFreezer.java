// ZCSLIB Daemon - Param Freezer
// Freeze personality parameters into L3 file headers
// Pure Java SE (java.base only)
package zcslib.daemon;

import zcslib.evolution.memory.L3Memory;
import zcslib.evolution.params.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Freezes current personality parameters and writes them into
 * existing L3 memory files.
 * <p>
 * After freezing, global params are locked and local params get
 * baselines set — runtime still oscillates within +-10%.
 */
public class ParamFreezer {

    private final GlobalParams globalParams = new GlobalParams();

    /**
     * Freeze parameters for specified plugin(s).
     *
     * @param root     ZCSLIB root
     * @param pluginId plugin to freeze, or "*" for all
     */
    public void freeze(Path root, String pluginId) throws IOException {
        Path pluginsDir = root.resolve("plugins");
        if (!Files.exists(pluginsDir)) {
            System.out.println("[DAEMON] ParamFreezer: no plugins directory.");
            return;
        }

        // Freeze global params
        globalParams.freeze();
        System.out.println("[DAEMON] Global params frozen: " +
                "entropy=" + String.format("%.3f", globalParams.get("entropy_tolerance")) +
                " urgency=" + String.format("%.3f", globalParams.get("self_healing_urgency")) +
                " hunger=" + String.format("%.3f", globalParams.get("resource_hunger")) +
                " sens=" + String.format("%.3f", globalParams.get("scan_sensitivity")));

        // Walk plugin directories
        try (Stream<Path> dirs = Files.list(pluginsDir)) {
            dirs.filter(Files::isDirectory).forEach(pluginDir -> {
                String id = pluginDir.getFileName().toString();
                if (!"*".equals(pluginId) && !id.equals(pluginId)) return;

                try {
                    freezePlugin(pluginDir, id);
                } catch (IOException e) {
                    System.err.println("[DAEMON]   Failed to freeze " + id + ": " + e.getMessage());
                }
            });
        }

        System.out.println("[DAEMON] ParamFreezer: done.");
    }

    private void freezePlugin(Path pluginDir, String pluginId) throws IOException {
        Path l3Dir = pluginDir.resolve("memory/l3");
        if (!Files.exists(l3Dir)) {
            // Create new L3 with frozen params
            L3Memory mem = new L3Memory(pluginId, computeHash(pluginDir));
            writeFrozenParams(mem);
            mem.freeze();
            mem.persist(l3Dir);
            System.out.println("[DAEMON]   " + pluginId + ": new L3 created (frozen)");
            return;
        }

        // Update existing L3 files
        try (Stream<Path> files = Files.list(l3Dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".zcsmem"))
                    .forEach(f -> {
                        try {
                            L3Memory mem = L3Memory.load(f);
                            if (mem != null) {
                                writeFrozenParams(mem);
                                mem.freeze();
                                mem.persist(l3Dir);
                                System.out.println("[DAEMON]   " + pluginId +
                                        ": " + f.getFileName() + " frozen");
                            }
                        } catch (IOException e) {
                            System.err.println("[DAEMON]   Failed to freeze " +
                                    f.getFileName() + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void writeFrozenParams(L3Memory mem) {
        Map<String, Double> snap = globalParams.snapshot();
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            mem.setPersonality(e.getKey(), e.getValue());
        }
    }

    private String computeHash(Path dir) {
        return String.format("%08x", dir.toAbsolutePath().toString().hashCode());
    }
}
