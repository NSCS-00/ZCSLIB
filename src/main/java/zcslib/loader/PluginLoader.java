package zcslib.loader;

import zcslib.api.PluginContext;
import zcslib.api.TrustLevel;
import zcslib.ZCSLIB;
import zcslib.kernel.ZCSKernel;
import zcslib.log.ZCSLogger;
import zcslib.pec.PECScanner;
import zcslib.pec.PECSchema;
import zcslib.pec.PECValidator;
import zcslib.pec.PECVerdict;
import zcslib.resource.ZCSResourceManager;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Plugin discovery and loading orchestrator per ZCSLIB 第四章.
 *
 * <p>Scans {@code plugins/} for JARs, reads PEC contracts, classifies trust,
 * validates environment, creates isolated classloaders, and instantiates
 * Native (N) plugins with a {@link PluginContext}.
 */
public class PluginLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ZCSKernel kernel;
    private final PECValidator validator;
    /** JAR scan directory: config/DLZstudio/ZCSLIB/plugins/ */
    private final Path pluginsDir;
    private final Path gameDir;
    private final ZCSResourceManager resourceManager;

    /** Loaded plugins, keyed by pluginId. */
    private final Map<String, PluginDescriptor> plugins = new LinkedHashMap<>();
    private int pluginCount = 0;

    public PluginLoader(ZCSKernel kernel, Path gameDir, ZCSResourceManager resourceManager) {
        this.kernel = kernel;
        this.gameDir = gameDir;
        this.resourceManager = resourceManager;
        // Use the same directory tree as ZCSResourceManager, not gameDir/plugins
        this.pluginsDir = resourceManager.getPluginsDir();
        this.validator = new PECValidator(
                ZCSLIB.VERSION,
                "1.21.1",
                "neoforge",
                "21.1.228",
                System.getProperty("java.version")
        );
    }

    /**
     * Scan {@code plugins/} and load all recognized plugins.
     *
     * @return list of successfully loaded plugin descriptors
     */
    public List<PluginDescriptor> scanAndLoad() {
        List<PluginDescriptor> loaded = new ArrayList<>();

        if (!Files.isDirectory(pluginsDir)) {
            LOGGER.info("[ZCSLIB] plugins/ directory not found at {} — creating", pluginsDir);
            try { Files.createDirectories(pluginsDir); }
            catch (IOException e) {
                LOGGER.error("[ZCSLIB] Failed to create plugins/ directory: {}", e.getMessage());
                return loaded;
            }
        }

        File[] jarFiles = pluginsDir.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            LOGGER.info("[ZCSLIB] No JAR files found in plugins/");
            return loaded;
        }

        LOGGER.info("[ZCSLIB] Scanning {} JAR(s) in plugins/ ...", jarFiles.length);

        // Sort by priority: PEC priority first, then alphabetical
        // (PEC not parsed yet, so sort alphabetically; priority ordering applied post-load)
        Arrays.sort(jarFiles, Comparator.comparing(File::getName));

        for (File jarFile : jarFiles) {
            PluginDescriptor desc = loadJar(jarFile);
            if (desc != null) {
                plugins.put(desc.getPluginId(), desc);
                loaded.add(desc);
                pluginCount++;
            }
        }

        LOGGER.info("[ZCSLIB] Loaded {} plugin(s) from plugins/", pluginCount);
        return loaded;
    }

    // ── Per-JAR loading ──────────────────────────────────────

    private PluginDescriptor loadJar(File jarFile) {
        String jarName = jarFile.getName();
        LOGGER.info("[ZCSLIB] Examining {} ...", jarName);

        try (JarFile jar = new JarFile(jarFile)) {

            // Step 1: Scan for PEC
            PECSchema pec = PECScanner.scan(jar);

            // Step 2: Identify traits
            boolean hasDlzPackage = hasPackage(jar, "com/dlzstudio/");
            boolean hasAutoAdapt = jar.getJarEntry("META-INF/zcslib/auto-adapt") != null;
            boolean hasModsToml  = jar.getJarEntry("META-INF/neoforge.mods.toml") != null
                                || jar.getJarEntry("neoforge.mods.toml") != null;

            // Step 3: Classify trust
            TrustLevel trust = PECValidator.classify(pec, hasDlzPackage, hasAutoAdapt, hasModsToml);
            if (trust == null) {
                LOGGER.warn("[ZCSLIB] {} is UNKNOWN — no PEC, no friendly traits, no mods.toml. Rejected.", jarName);
                return null;
            }

            // Step 4: Validate environment (N-level only)
            if (pec != null) {
                PECVerdict verdict = validator.validate(pec);
                if (verdict == PECVerdict.HARD_FAIL) {
                    LOGGER.error("[ZCSLIB] {} HARD_FAIL — environment not met. Plugin disabled.", jarName);
                    return null;
                }
                if (verdict == PECVerdict.SOFT_FAIL) {
                    LOGGER.warn("[ZCSLIB] {} SOFT_FAIL — loading with degraded features.", jarName);
                }
            }

            // Step 5: Register with ResourceManager + create PluginContext
            String pluginId = pec != null ? pec.pluginId : deriveId(jarName);
            String version  = pec != null ? pec.version : "unknown";
            String display  = pec != null ? pec.displayName : jarName;

            // Phase 4: Register sandbox + quota via ResourceManager
            boolean isSLevel = trust == TrustLevel.S;
            resourceManager.registerPlugin(pluginId, isSLevel);

            PluginContext ctx = createContext(pluginId, trust);

            // Step 6: Load and instantiate (N-level only)
            Object mainInstance = null;
            PluginClassLoader cl = null;

            if (trust == TrustLevel.N && pec != null && pec.entrypoint != null
                    && pec.entrypoint.mainClass != null) {
                cl = new PluginClassLoader(
                        new URL[] { jarFile.toURI().toURL() },
                        PluginLoader.class.getClassLoader());
                mainInstance = instantiatePlugin(cl, pec.entrypoint.mainClass, ctx);
                if (mainInstance == null) {
                    LOGGER.error("[ZCSLIB] Failed to instantiate {} — skipping.", pluginId);
                    try { cl.close(); } catch (IOException ignored) {}
                    return null;
                }
            } else {
                cl = new PluginClassLoader(new URL[0], PluginLoader.class.getClassLoader());
            }

            PluginDescriptor desc = new PluginDescriptor(
                    pluginId, version, display, trust, mainInstance, cl, pec, ctx);
            LOGGER.info("[ZCSLIB] [{}] {} v{} loaded ({}).",
                    trust.name(), desc.getDisplayName(), version, jarName);
            return desc;

        } catch (IOException e) {
            LOGGER.error("[ZCSLIB] Failed to read JAR {}: {}", jarName, e.getMessage());
            return null;
        }
    }

    // ── Internal helpers ─────────────────────────────────────

    /** Create a PluginContext using the ResourceManager sandbox. */
    private PluginContext createContext(String pluginId, TrustLevel trust) {
        File dataFolder   = resourceManager.resolve(pluginId, "data");
        File configFolder = resourceManager.resolve(pluginId, "config");
        Path cachePath    = gameDir.resolve("config").resolve("DLZstudio").resolve("ZCSLIB")
                .resolve("cache").resolve(pluginId);
        File cacheDir = cachePath.toFile();
        cacheDir.mkdirs();

        ZCSLogger logger = new ZCSLogger(pluginId, trust,
                gameDir.resolve("logs").resolve("zcslib"));

        return new SimplePluginContext(pluginId, dataFolder, configFolder,
                cacheDir, logger, trust, kernel);
    }

    private Object instantiatePlugin(PluginClassLoader cl, String className, PluginContext ctx) {
        try {
            Class<?> clazz = cl.loadClass(className);
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor(PluginContext.class);
                ctor.setAccessible(true);
                return ctor.newInstance(ctx);
            } catch (NoSuchMethodException e) {
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            String detail = cause != null ? cause.toString() : e.toString();
            LOGGER.error("[ZCSLIB] Failed to instantiate {}: {}", className, detail, e);
            return null;
        }
    }

    private boolean hasPackage(JarFile jar, String prefix) {
        return jar.stream().anyMatch(e -> !e.isDirectory()
                && e.getName().endsWith(".class")
                && e.getName().startsWith(prefix));
    }

    private String deriveId(String jarName) {
        return jarName.replaceAll("\\.jar$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ── Accessors ────────────────────────────────────────────

    public PluginDescriptor getPlugin(String pluginId) {
        return plugins.get(pluginId);
    }

    public Collection<PluginDescriptor> getAllPlugins() {
        return Collections.unmodifiableCollection(plugins.values());
    }

    public int getPluginCount() {
        return pluginCount;
    }
}
