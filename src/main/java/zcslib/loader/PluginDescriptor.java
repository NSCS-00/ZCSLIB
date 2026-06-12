package zcslib.loader;

import zcslib.api.PluginContext;
import zcslib.api.TrustLevel;
import zcslib.pec.PECSchema;

/**
 * Metadata for a loaded plugin.
 *
 * <p>Created by {@link PluginLoader} after successful loading.
 */
public class PluginDescriptor {
    private final String pluginId;
    private final String version;
    private final String displayName;
    private final TrustLevel trustLevel;
    private final Object mainInstance;
    private final PluginClassLoader classLoader;
    private final PECSchema pec;
    private final PluginContext context;

    public PluginDescriptor(String pluginId, String version, String displayName,
                            TrustLevel trustLevel, Object mainInstance,
                            PluginClassLoader classLoader, PECSchema pec,
                            PluginContext context) {
        this.pluginId = pluginId;
        this.version = version;
        this.displayName = displayName;
        this.trustLevel = trustLevel;
        this.mainInstance = mainInstance;
        this.classLoader = classLoader;
        this.pec = pec;
        this.context = context;
    }

    public String getPluginId()      { return pluginId; }
    public String getVersion()       { return version; }
    public String getDisplayName()   { return displayName; }
    public TrustLevel getTrustLevel(){ return trustLevel; }
    public Object getMainInstance()  { return mainInstance; }
    public PluginClassLoader getClassLoader() { return classLoader; }
    public PECSchema getPec()        { return pec; }
    public PluginContext getContext() { return context; }

    @Override
    public String toString() {
        return String.format("[%s/%s] %s v%s", trustLevel, pluginId, displayName, version);
    }
}
