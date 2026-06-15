package zcslib.api;

import zcslib.kernel.ZCSKernel;
import zcslib.log.ZCSLogger;

import java.io.File;

/**
 * Every plugin's handle to the ZCSLIB kernel.
 *
 * <p>Injected once at construction. Seven methods — no more.
 *
 * <p>For all functional operations use {@link #kernel()}{@code .order(...)}.
 */
public interface PluginContext {

    /** PEC {@code pluginId}. Unique within a ZCSLIB instance. */
    String getPluginId();

    /** Writable data directory: {@code plugins/{id}/data/}. */
    File getDataFolder();

    /** Writable config directory: {@code plugins/{id}/config/}. */
    File getConfigFolder();

    /** Auto-managed cache directory: {@code cache/{id}/}. */
    File getCacheDir();

    /** Plugin-dedicated dual-track logger. */
    ZCSLogger getLogger();

    /** Trust level assigned by the kernel at load time. */
    TrustLevel getTrustLevel();

    /** Entry point for all functional {@code order()} commands. */
    ZCSKernel kernel();

    /**
     * Execute a command with automatic L1/L2 tracing.
     * Preferred entry point — captures timing, plugin identity, and result.
     */
    OrderResult order(String command, Object... args);
}
