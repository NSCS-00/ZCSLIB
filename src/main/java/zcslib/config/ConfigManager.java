package zcslib.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Plugin config manager per ZCSLIB 7.1.
 *
 * <p>Loads/saves JSON configs from each plugin's config directory.
 * Supports hot-reload and atomic writes (tmp → rename).
 *
 * <p>Routed via {@code kernel.order("config:load", pluginId, filename)}
 * and {@code kernel.order("config:save", pluginId, filename, data)}.
 */
public class ConfigManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Load a JSON config file.
     *
     * @param configDir  the plugin's config directory
     * @param filename   e.g. {@code "server.json"}
     * @param type       expected type for deserialization (e.g. {@code Map.class})
     * @return parsed config, or {@code null} if the file doesn't exist
     */
    @SuppressWarnings("unchecked")
    public <T> T load(File configDir, String filename, java.lang.reflect.Type type) {
        Path filePath = configDir.toPath().resolve(filename);
        if (!Files.exists(filePath)) {
            LOGGER.debug("[ZCSLIB] Config file not found: {}", filePath);
            return null;
        }

        try {
            String json = Files.readString(filePath);
            T data = (type == null)
                    ? (T) GSON.fromJson(json, Map.class)
                    : GSON.fromJson(json, type);
            LOGGER.debug("[ZCSLIB] Config loaded: {} ({} bytes)", filePath, json.length());
            return data;
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("[ZCSLIB] Failed to load config {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Load a config file as a raw Map.
     */
    public Map<String, Object> loadAsMap(File configDir, String filename) {
        return (Map<String, Object>) load(configDir, filename, new TypeToken<Map<String, Object>>() {}.getType());
    }

    /**
     * Save data to a JSON config file with atomic write (tmp → rename).
     */
    public boolean save(File configDir, String filename, Object data) {
        Path filePath = configDir.toPath().resolve(filename);
        Path tmpPath = filePath.resolveSibling(filename + ".tmp");

        try {
            // Ensure directory exists
            Files.createDirectories(configDir.toPath());

            String json = GSON.toJson(data);
            Files.writeString(tmpPath, json);
            Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("[ZCSLIB] Config saved: {} ({} bytes)", filePath, json.length());
            return true;
        } catch (IOException e) {
            LOGGER.error("[ZCSLIB] Failed to save config {}: {}", filePath, e.getMessage());
            // Clean up tmp file if it exists
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            return false;
        }
    }

    /**
     * Hot-reload a config from cache (if reloaded externally).
     */
    public <T> T reload(File configDir, String filename, java.lang.reflect.Type type) {
        return load(configDir, filename, type);
    }
}
