package zcslib.pec;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans JAR files for PEC documents per ZCSLIB 3.0 priority order.
 *
 * <p>Scan order (first match wins):
 * <ol>
 *   <li>{@code /META-INF/zcslib/PEC.json}
 *   <li>{@code /zcslib.plugin.json}
 *   <li>{@code /zcslib.PEC.json}
 *   <li>{@code /PEC.json}
 * </ol>
 */
public class PECScanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final String[] PEC_PATHS = {
            "META-INF/zcslib/PEC.json",
            "zcslib.plugin.json",
            "zcslib.PEC.json",
            "PEC.json"
    };

    /**
     * Attempt to locate and parse a PEC inside a JAR.
     *
     * @param jarFile the plugin JAR
     * @return parsed PEC, or {@code null} if no PEC found in any path
     */
    public static PECSchema scan(JarFile jarFile) {
        for (String path : PEC_PATHS) {
            JarEntry entry = jarFile.getJarEntry(path);
            if (entry == null) continue;

            try (InputStreamReader reader = new InputStreamReader(jarFile.getInputStream(entry))) {
                PECSchema pec = GSON.fromJson(reader, PECSchema.class);
                if (pec != null && pec.pluginId != null) {
                    LOGGER.debug("[ZCSLIB] PEC found at {} in {}: pluginId={}", path, jarFile.getName(), pec.pluginId);
                    return pec;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[ZCSLIB] Failed to parse PEC at {} in {}: {}",
                        path, jarFile.getName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * Attempt to locate and parse a PEC from a plain file (non-JAR directory).
     */
    public static PECSchema scan(Path dir) {
        for (String path : PEC_PATHS) {
            Path pecFile = dir.resolve(path);
            if (!Files.exists(pecFile)) continue;

            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(pecFile))) {
                PECSchema pec = GSON.fromJson(reader, PECSchema.class);
                if (pec != null && pec.pluginId != null) {
                    LOGGER.debug("[ZCSLIB] PEC found at {} in {}: pluginId={}", path, dir, pec.pluginId);
                    return pec;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[ZCSLIB] Failed to parse PEC at {}: {}", pecFile, e.getMessage());
            }
        }
        return null;
    }
}
