package zcslib.loader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated classloader for a single plugin JAR.
 *
 * <p>Delegates to the parent (kernel) classloader for ZCSLIB API classes,
 * loads the plugin's own classes from its JAR. This prevents plugins from
 * seeing each other's classes while giving them access to the kernel API.
 *
 * <p>Blacklist enforcement (future): kernel internal packages are blocked.
 */
public class PluginClassLoader extends URLClassLoader {

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Delegate ZCSLIB API and Java stdlib to parent
        if (name.startsWith("zcslib.api.") ||
            name.startsWith("zcslib.log.") ||
            name.startsWith("java.") ||
            name.startsWith("javax.")) {
            return super.loadClass(name, resolve);
        }

        // Block access to kernel internals
        if (name.startsWith("zcslib.kernel.internal.")) {
            throw new ClassNotFoundException(
                    "Access denied: kernel internal package — " + name);
        }

        // Try loading from this classloader's JAR first
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return super.loadClass(name, resolve);
        } catch (LinkageError e) {
            throw new ClassNotFoundException(
                    "Linkage error loading " + name + ": " + e.getMessage(), e);
        }
    }
}
