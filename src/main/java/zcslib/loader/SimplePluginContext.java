package zcslib.loader;

import zcslib.api.PluginContext;
import zcslib.api.TrustLevel;
import zcslib.kernel.ZCSKernel;
import zcslib.log.ZCSLogger;

import java.io.File;

/**
 * Concrete PluginContext injected into every loaded plugin.
 */
class SimplePluginContext implements PluginContext {
    private final String pluginId;
    private final File dataFolder;
    private final File configFolder;
    private final File cacheDir;
    private final ZCSLogger logger;
    private final TrustLevel trustLevel;
    private final ZCSKernel kernel;

    SimplePluginContext(String pluginId, File dataFolder, File configFolder,
                        File cacheDir, ZCSLogger logger, TrustLevel trustLevel,
                        ZCSKernel kernel) {
        this.pluginId = pluginId;
        this.dataFolder = dataFolder;
        this.configFolder = configFolder;
        this.cacheDir = cacheDir;
        this.logger = logger;
        this.trustLevel = trustLevel;
        this.kernel = kernel;
    }

    @Override public String getPluginId()     { return pluginId; }
    @Override public File getDataFolder()     { return dataFolder; }
    @Override public File getConfigFolder()   { return configFolder; }
    @Override public File getCacheDir()       { return cacheDir; }
    @Override public ZCSLogger getLogger()    { return logger; }
    @Override public TrustLevel getTrustLevel(){ return trustLevel; }
    @Override public ZCSKernel kernel()       { return kernel; }
}
