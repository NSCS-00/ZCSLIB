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
 * ├── global/            # kernel-wide shared data (PDC backend)
 * ├── shared_res/        # shared read-only resources
 * ├── external/          # external read-only resources
 * ├── studio/            # DLZstudio-specific shared assets
 * ├── cache/             # global cache
 * └── plugins/
 *     └── {plugin_id}/
 *         ├── config/
 *         └── data/
 * }</pre>
 *
 * <p>Virtual paths:
 * <ul>
 *   <li>{@code /config/*} — plugin-specific writable config (all trust levels)</li>
 *   <li>{@code /data/*}   — plugin-specific writable data (all trust levels)</li>
 *   <li>{@code /cache/*}  — auto-managed cache dir (all trust levels)</li>
 *   <li>{@code /shared/*} — read-only shared resources (N/R/A only)</li>
 *   <li>{@code /global/*} — read-only global config (N/R/A only)</li>
 *   <li>{@code /external/*} — read-only external mounts (N only)</li>
 *   <li>{@code /studio/*} — DLZstudio internal assets (N only)</li>
 * </ul>
 */
public class ZCSResourceManager {
    private final Path rootDir;
    private final Path globalDir;
    private final Path sharedDir;
    private final Path externalDir;
    private final Path studioDir;
    private final Path globalCacheDir;
    private final Path pluginsDir;

    private final ConcurrentMap<String, ResourceSandbox> sandboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DiskQuota> quotas = new ConcurrentHashMap<>();

    public ZCSResourceManager(Path gameDir) {
        this.rootDir = gameDir.resolve("config").resolve("DLZstudio").resolve("ZCSLIB");
        this.globalDir = rootDir.resolve("global");
        this.sharedDir = rootDir.resolve("shared_res");
        this.externalDir = rootDir.resolve("external");
        this.studioDir = rootDir.resolve("studio");
        this.globalCacheDir = rootDir.resolve("cache");
        this.pluginsDir = rootDir.resolve("plugins");
    }

    /** Create the full directory tree. Safe to call multiple times. */
    public void init() throws IOException {
        Files.createDirectories(globalDir);
        Files.createDirectories(sharedDir);
        Files.createDirectories(externalDir);
        Files.createDirectories(studioDir);
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

    /** Check if a path exists in the plugin's sandbox or shared directories. */
    public boolean exists(String pluginId, String virtualPath) {
        ResourceSandbox sandbox = sandboxes.get(pluginId);
        if (sandbox != null) {
            File f = sandbox.resolve(virtualPath);
            if (f.exists()) return true;
        }
        // Also check shared/global/external/studio
        Path p = mapSharedPath(virtualPath);
        return p != null && Files.exists(p);
    }

    /**
     * Get a shared read-only resource. Only N/R/A trust levels.
     * Searches: shared_res/ → global/ → external/ → studio/ in that order.
     *
     * @return InputStream or null if not found
     */
    public java.io.InputStream getSharedResource(String relativePath) throws IOException {
        Path p = mapSharedPath(relativePath);
        if (p != null && Files.isRegularFile(p)) {
            return Files.newInputStream(p);
        }
        return null;
    }

    /** Get the cache directory for a specific plugin. */
    public File getCacheDir(String pluginId) {
        Path cachePath = globalCacheDir.resolve(pluginId);
        cachePath.toFile().mkdirs();
        return cachePath.toFile();
    }

    /**
     * Map a relative path to the first matching shared directory.
     * Priority: shared_res/ → global/ → external/ → studio/
     */
    private Path mapSharedPath(String relativePath) {
        String safe = ResourceSandbox.normalizeShared(relativePath);
        Path safePath = Path.of(safe);
        Path[] dirs = { sharedDir, globalDir, externalDir, studioDir };
        for (Path dir : dirs) {
            Path candidate = dir.resolve(safePath).normalize();
            if (Files.exists(candidate)) return candidate;
        }
        return sharedDir.resolve(safePath); // first choice even if not exists
    }

    // ── Accessors ────────────────────────────────────────────

    public Path getGlobalDir()      { return globalDir; }
    public Path getSharedDir()      { return sharedDir; }
    public Path getExternalDir()    { return externalDir; }
    public Path getStudioDir()      { return studioDir; }
    public Path getGlobalCacheDir() { return globalCacheDir; }
    public Path getPluginsDir()     { return pluginsDir; }
    public Path getRootDir()        { return rootDir; }
}
