package zcslib.resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central resource manager — virtual-path to physical-path mapping per ZCSLIB 第五章.
 *
 * <p>Creates and enforces the directory hierarchy:
 * <pre>{@code
 * config/DLZstudio/ZCSLIB/
 * ├── global/            # kernel-wide (PDC backend)
 * ├── shared_res/        # shared read-only resources
 * ├── cache/             # global cache
 * └── plugins/
 *     └── {plugin_id}/
 *         ├── config/
 *         └── data/
 * }</pre>
 *
 * <p>Routes {@code kernel.order("resource:file", "/config/server.json")} calls
 * to the correct plugin sandbox.
 */
public class ZCSResourceManager {
    private final Path rootDir;
    private final Path globalDir;
    private final Path sharedDir;
    private final Path globalCacheDir;
    private final Path pluginsDir;

    private final ConcurrentMap<String, ResourceSandbox> sandboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DiskQuota> quotas = new ConcurrentHashMap<>();

    public ZCSResourceManager(Path gameDir) {
        this.rootDir = gameDir.resolve("config").resolve("DLZstudio").resolve("ZCSLIB");
        this.globalDir = rootDir.resolve("global");
        this.sharedDir = rootDir.resolve("shared_res");
        this.globalCacheDir = rootDir.resolve("cache");
        this.pluginsDir = rootDir.resolve("plugins");
    }

    /** Create the full directory tree. Safe to call multiple times. */
    public void init() throws IOException {
        Files.createDirectories(globalDir);
        Files.createDirectories(sharedDir);
        Files.createDirectories(globalCacheDir);
        Files.createDirectories(pluginsDir);
    }

    /**
     * Register a plugin's directories and create its ResourceSandbox + DiskQuota.
     */
    public ResourceSandbox registerPlugin(String pluginId, boolean isSLevel) {
        Path pluginDir = pluginsDir.resolve(pluginId);
        try {
            Files.createDirectories(pluginDir.resolve("config"));
            Files.createDirectories(pluginDir.resolve("data"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directories for plugin " + pluginId, e);
        }
        ResourceSandbox sandbox = new ResourceSandbox(pluginDir);
        DiskQuota quota = new DiskQuota(pluginId, isSLevel);
        sandboxes.put(pluginId, sandbox);
        quotas.put(pluginId, quota);
        return sandbox;
    }

    /** Resolve a virtual path for a specific plugin. */
    public File resolve(String pluginId, String virtualPath) {
        ResourceSandbox sandbox = sandboxes.get(pluginId);
        if (sandbox == null) {
            throw new SecurityException("Plugin '" + pluginId + "' has no sandbox — register it first");
        }
        return sandbox.resolve(virtualPath);
    }

    /** Get the plugin's sandbox. */
    public ResourceSandbox getSandbox(String pluginId) {
        return sandboxes.get(pluginId);
    }

    /** Get the plugin's DiskQuota. */
    public DiskQuota getQuota(String pluginId) {
        return quotas.get(pluginId);
    }

    // ── Accessors ────────────────────────────────────────────

    public Path getGlobalDir()      { return globalDir; }
    public Path getSharedDir()      { return sharedDir; }
    public Path getGlobalCacheDir() { return globalCacheDir; }
    public Path getPluginsDir()     { return pluginsDir; }
    public Path getRootDir()        { return rootDir; }
}
