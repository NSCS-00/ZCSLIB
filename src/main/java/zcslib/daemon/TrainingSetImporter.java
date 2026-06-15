// ZCSLIB Daemon - Training Set Importer
// Import .zctsp into local L2/L3 storage
// Pure Java SE (java.base only)
package zcslib.daemon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a .zctsp training set, extracting L2 journals and L3 memories
 * into the local ZCSLIB directory structure.
 */
public class TrainingSetImporter {

    /**
     * Import a training set.
     *
     * @param archive .zctsp file to import
     * @param root    ZCSLIB root (config/DLZstudio/ZCSLIB/)
     */
    public void importPack(Path archive, Path root) throws IOException {
        if (!Files.exists(archive)) {
            System.err.println("[DAEMON] Import: file not found: " + archive);
            return;
        }

        System.out.println("[DAEMON] Importing: " + archive.getFileName());

        Path pluginsDir = root.resolve("plugins");
        Files.createDirectories(pluginsDir);

        int l2Count = 0, l3Count = 0;

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.equals("manifest.json")) {
                    String manifest = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    System.out.println("[DAEMON]   Manifest: " +
                            manifest.replace("\n", " ").substring(0,
                                    Math.min(120, manifest.length())));
                } else if (name.startsWith("l2/")) {
                    // l2/{pluginId}/{filename}
                    String[] parts = name.split("/", 3);
                    if (parts.length == 3) {
                        String pluginId = parts[1];
                        Path dest = pluginsDir.resolve(pluginId)
                                .resolve("memory/l2").resolve(parts[2]);
                        extractEntry(zis, dest);
                        l2Count++;
                    }
                } else if (name.startsWith("l3/")) {
                    // l3/{pluginId}/{filename}
                    String[] parts = name.split("/", 3);
                    if (parts.length == 3) {
                        String pluginId = parts[1];
                        Path dest = pluginsDir.resolve(pluginId)
                                .resolve("memory/l3").resolve(parts[2]);
                        extractEntry(zis, dest);
                        l3Count++;
                    }
                }

                zis.closeEntry();
            }
        }

        System.out.println("[DAEMON] Import complete: " + l2Count +
                " L2 journals, " + l3Count + " L3 memories");
    }

    private void extractEntry(InputStream in, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        try (OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }
}
