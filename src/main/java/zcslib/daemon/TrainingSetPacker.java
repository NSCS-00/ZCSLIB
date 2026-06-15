// ZCSLIB Daemon - Training Set Packer
// Pack L2+L3 into .zctsp archive for federation exchange
// Pure Java SE (java.base only)
package zcslib.daemon;

import zcslib.evolution.memory.L3Memory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a plugin's L2 journals and L3 memories into a single .zctsp training set.
 * <p>
 * .zctsp = ZIP archive with:
 * <pre>
 *   manifest.json    — metadata (pluginId, version, timestamp, env_hash)
 *   l2/*.zcslog      — anonymised L2 journals
 *   l3/*.zcsmem      — L3 combat manual(s)
 * </pre>
 */
public class TrainingSetPacker {

    /**
     * Pack training set.
     *
     * @param root     ZCSLIB root (config/DLZstudio/ZCSLIB/)
     * @param pluginId plugin to pack, or "*" for all
     * @param output   output .zctsp file
     */
    public void pack(Path root, String pluginId, Path output) throws IOException {
        Files.createDirectories(output.getParent());

        Path pluginsDir = root.resolve("plugins");
        if (!Files.exists(pluginsDir)) {
            System.out.println("[DAEMON] TrainingSetPacker: plugins dir not found.");
            return;
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output)))) {

            // Manifest
            String manifest = buildManifest(pluginId, pluginsDir);
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // L2 journals
            int l2Count = packL2Journals(pluginsDir, pluginId, zos);

            // L3 memories
            int l3Count = packL3Memories(pluginsDir, pluginId, zos);

            System.out.println("[DAEMON] TrainingSetPacker: packed " +
                    l2Count + " L2 journals + " + l3Count + " L3 memories → " +
                    output);
        }
    }

    private String buildManifest(String pluginId, Path pluginsDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": \"zctsp/1.0\",\n");
        sb.append("  \"plugin_id\": \"").append(pluginId).append("\",\n");
        sb.append("  \"created_at\": \"").append(Instant.now()).append("\",\n");

        // Discover plugin IDs
        List<String> ids = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(pluginsDir)) {
            dirs.filter(Files::isDirectory).forEach(d -> ids.add(d.getFileName().toString()));
        }

        if ("*".equals(pluginId)) {
            sb.append("  \"plugins\": [");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(ids.get(i)).append("\"");
            }
            sb.append("]\n");
        } else {
            sb.append("  \"plugins\": [\"").append(pluginId).append("\"]\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private int packL2Journals(Path pluginsDir, String pluginId, ZipOutputStream zos) throws IOException {
        int count = 0;
        List<Path> pluginDirs = listPluginDirs(pluginsDir, pluginId);

        for (Path pluginDir : pluginDirs) {
            Path l2Dir = pluginDir.resolve("memory/l2");
            if (!Files.exists(l2Dir)) continue;

            try (Stream<Path> files = Files.list(l2Dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".zcslog")).toList()) {
                    String entry = "l2/" + pluginDir.getFileName() + "/" + f.getFileName();
                    zos.putNextEntry(new ZipEntry(entry));
                    Files.copy(f, zos);
                    zos.closeEntry();
                    count++;
                }
            }
        }
        return count;
    }

    private int packL3Memories(Path pluginsDir, String pluginId, ZipOutputStream zos) throws IOException {
        int count = 0;
        List<Path> pluginDirs = listPluginDirs(pluginsDir, pluginId);

        for (Path pluginDir : pluginDirs) {
            Path l3Dir = pluginDir.resolve("memory/l3");
            if (!Files.exists(l3Dir)) continue;

            try (Stream<Path> files = Files.list(l3Dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".zcsmem")).toList()) {
                    String entry = "l3/" + pluginDir.getFileName() + "/" + f.getFileName();
                    zos.putNextEntry(new ZipEntry(entry));
                    Files.copy(f, zos);
                    zos.closeEntry();
                    count++;
                }
            }
        }
        return count;
    }

    private List<Path> listPluginDirs(Path pluginsDir, String pluginId) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            stream.filter(Files::isDirectory).forEach(d -> {
                if ("*".equals(pluginId) || d.getFileName().toString().equals(pluginId)) {
                    dirs.add(d);
                }
            });
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
