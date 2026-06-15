// ZCSLIB Daemon - Main Entry
// Pure Java SE (java.base only) — no Minecraft/NeoForge imports
// Invoked via: java -jar ZCSLIB.jar --daemon dream|l3merge|pack|import|freeze
package zcslib.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Main entry point for the ZCSLIB daemon.
 * <p>
 * Activated via the {@code --daemon} flag on the ZCSLIB.jar command line.
 * All modes operate on the file system (no IPC) — they read L2/L3/L4 files,
 * produce output, and exit.
 */
public class ZCSDaemon {

    private static Path rootDir;
    private static Path l1Dir, l4Dir, trainingDir;

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        // --daemon <mode> [--root <path>] [...]
        String mode = args[1];
        rootDir = parseRoot(args);
        initDirs();

        System.out.println("[DAEMON] ZCSLIB Daemon v0.2.0");
        System.out.println("[DAEMON] Root: " + rootDir.toAbsolutePath());
        System.out.println("[DAEMON] Mode: " + mode);

        try {
            switch (mode) {
                case "dream"   -> runDream();
                case "l3merge" -> runL3Merge(args);
                case "pack"    -> runPack(args);
                case "import"  -> runImport(args);
                case "freeze"  -> runFreeze(args);
                default -> {
                    System.err.println("[DAEMON] Unknown mode: " + mode);
                    printUsage();
                }
            }
        } catch (Exception e) {
            System.err.println("[DAEMON] Fatal: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[DAEMON] Done.");
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar ZCSLIB.jar --daemon <mode> [--root <path>] [args]");
        System.out.println("Modes:");
        System.out.println("  dream    Run dream worker (L2 -> L3 + MC verification)");
        System.out.println("  l3merge  Merge multiple L3 .zcsmem files -> universal L3");
        System.out.println("  pack     Pack training set .zctsp");
        System.out.println("  import   Import training set .zctsp");
        System.out.println("  freeze   Freeze current personality params");
    }

    private static Path parseRoot(String[] args) {
        for (int i = 2; i < args.length - 1; i++) {
            if ("--root".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        // Default: current directory (MC server root)
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static void initDirs() {
        Path zcsRoot = rootDir.resolve("config/DLZstudio/ZCSLIB");
        l1Dir = zcsRoot.resolve("memory/l1");
        l4Dir = zcsRoot.resolve("memory/l4");
        trainingDir = zcsRoot.resolve("memory/training");
    }

    // —— Mode runners ——

    private static void runDream() throws Exception {
        Path pluginsDir = rootDir.resolve("config/DLZstudio/ZCSLIB/plugins");
        DreamWorker worker = new DreamWorker(pluginsDir, l4Dir, rootDir);
        worker.run();
    }

    private static void runL3Merge(String[] args) throws Exception {
        L3Merger merger = new L3Merger();
        String[] files = extractArgs(args, "--files");
        String output = extractSingleArg(args, "--output", "universal.zcsmem");
        if (files == null || files.length == 0) {
            System.err.println("[DAEMON] l3merge requires --files <path1,path2,...>");
            return;
        }
        Path outPath = trainingDir.resolve(output);
        merger.merge(files, outPath);
    }

    private static void runPack(String[] args) throws Exception {
        TrainingSetPacker packer = new TrainingSetPacker();
        String pluginId = extractSingleArg(args, "--plugin", "*");
        String output = extractSingleArg(args, "--output", "training_" + Instant.now().getEpochSecond() + ".zctsp");
        Path outPath = trainingDir.resolve("export").resolve(output);
        packer.pack(rootDir.resolve("config/DLZstudio/ZCSLIB"), pluginId, outPath);
    }

    private static void runImport(String[] args) throws Exception {
        TrainingSetImporter importer = new TrainingSetImporter();
        String file = extractSingleArg(args, "--file", null);
        if (file == null) {
            System.err.println("[DAEMON] import requires --file <path>");
            return;
        }
        importer.importPack(Path.of(file), rootDir.resolve("config/DLZstudio/ZCSLIB"));
    }

    private static void runFreeze(String[] args) throws Exception {
        ParamFreezer freezer = new ParamFreezer();
        String pluginId = extractSingleArg(args, "--plugin", "*");
        freezer.freeze(rootDir.resolve("config/DLZstudio/ZCSLIB"), pluginId);
    }

    // —— Argument helpers ——

    private static String extractSingleArg(String[] args, String flag, String defaultValue) {
        for (int i = 2; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return defaultValue;
    }

    private static String[] extractArgs(String[] args, String flag) {
        for (int i = 2; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1].split(",");
            }
        }
        return null;
    }
}
