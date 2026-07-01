package zcslib;

import zcslib.kernel.ZCSKernel;
import zcslib.log.CrashHandler;
import zcslib.log.LogRotator;
import zcslib.persistence.NbtBridge;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Version-neutral shared state: constants, kernel lifecycle, and bootstrap.
 *
 * <p>Called by the @Mod entry point ({@link ZCSLIB}) after version detection.
 * Contains no direct NeoForge API calls — all go through {@link NbtBridge}.
 */
public final class ZCSLIBCommon {
    public static final String MOD_ID = "zcslib";
    public static final String VERSION = "0.2.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static ZCSKernel kernel;

    private ZCSLIBCommon() {}

    // ── Bootstrap ──────────────────────────────────────────

    /**
     * Initialize the ZCSLIB kernel. Called once at mod construction.
     * Must be invoked after version detection is complete.
     */
    public static void bootstrap() {
        LOGGER.info("ZCSLIB Kernel v{} initializing...", VERSION);

        CrashHandler.install();
        LogRotator.rotateAll();

        kernel = new ZCSKernel(NbtBridge.gameDir());
        CrashHandler.setKernel(kernel);

        LOGGER.info("ZCSLIB Kernel v{} initialized — {} plugin(s) online.",
                VERSION, kernel.getPluginCount());
    }

    // ── Lifecycle ──────────────────────────────────────────

    /** Called every server tick (post phase). */
    public static void onTick() {
        if (kernel != null) kernel.onTick();
    }

    /** Called on server shutdown to freeze state. */
    public static void shutdown() {
        if (kernel != null) kernel.shutdown();
    }

    // ── Accessor ───────────────────────────────────────────

    public static ZCSKernel getKernel() {
        return kernel;
    }
}
