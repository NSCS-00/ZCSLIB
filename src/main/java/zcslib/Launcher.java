// ZCSLIB Launcher — dual-entry bootstrap
// Pure Java SE (java.base only) — no Minecraft/NeoForge imports
package zcslib;

import zcslib.daemon.ZCSDaemon;

/**
 * Single Main-Class entry point for the ZCSLIB fat JAR.
 * <p>
 * When loaded as a NeoForge mod (placed in {@code mods/}), NeoForge
 * discovers the {@code @Mod} annotation on {@link ZCSLIB} directly —
 * the {@code Main-Class} manifest attribute is irrelevant.
 * <p>
 * When invoked via {@code java -jar ZCSLIB.jar --daemon <mode>},
 * this launcher routes to {@link ZCSDaemon}.
 * <p>
 * Usage:
 * <pre>
 * java -jar ZCSLIB.jar --daemon dream     # run dream worker
 * java -jar ZCSLIB.jar --daemon l3merge   # merge L3 files
 * java -jar ZCSLIB.jar --daemon pack      # pack training set
 * java -jar ZCSLIB.jar --daemon import    # import training set
 * java -jar ZCSLIB.jar --daemon freeze    # freeze params
 * </pre>
 */
public class Launcher {
    public static void main(String[] args) {
        if (args.length >= 1 && "--daemon".equals(args[0])) {
            ZCSDaemon.main(args);
        } else {
            System.out.println("ZCSLIB v0.2.0 — DLZstudio");
            System.out.println();
            System.out.println("Modes:");
            System.out.println("  NeoForge   Place ZCSLIB.jar in the mods/ folder.");
            System.out.println("  Daemon     java -jar ZCSLIB.jar --daemon <mode>");
            System.out.println();
            System.out.println("Daemon subcommands:");
            System.out.println("  dream      L2 -> L3 + MC verification");
            System.out.println("  l3merge    Merge multiple L3 .zcsmem files");
            System.out.println("  pack       Export training set .zctsp");
            System.out.println("  import     Import training set .zctsp");
            System.out.println("  freeze     Freeze personality parameters");
        }
    }
}
