package zcslib.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * NBT-based key-value persistence per ZCSLIB 7.2.
 *
 * <p>Each key maps to its own {@code .dat} file in the plugin's data directory.
 * Supports standard Minecraft NBT types (CompoundTag, BlockPos, ItemStack, etc.)
 * via Minecraft's built-in NBT serialization.
 *
 * <p>Routed via {@code kernel.order("pdc:save", pluginId, key, compoundTag)}
 * and {@code kernel.order("pdc:load", pluginId, key)}.
 */
public class PDCBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Save a CompoundTag to disk.
     *
     * @param dataDir the plugin's data directory
     * @param key     persistence key (used as filename: {@code {key}.dat})
     * @param tag     the data to persist
     * @return true on success
     */
    public boolean save(File dataDir, String key, CompoundTag tag) {
        Path filePath = dataDir.toPath().resolve(sanitizeKey(key) + ".dat");
        Path tmpPath = dataDir.toPath().resolve(sanitizeKey(key) + ".tmp");

        try {
            Files.createDirectories(dataDir.toPath());
            NbtIo.writeCompressed(tag, tmpPath);
            Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("[ZCSLIB] PDC saved: key={}, size={} bytes", key, tag.sizeInBytes());
            return true;
        } catch (IOException e) {
            LOGGER.error("[ZCSLIB] Failed to save PDC key '{}': {}", key, e.getMessage());
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            return false;
        }
    }

    /**
     * Load a CompoundTag from disk.
     *
     * @return the deserialized tag, or an empty CompoundTag if the file doesn't exist
     */
    public CompoundTag load(File dataDir, String key) {
        Path filePath = dataDir.toPath().resolve(sanitizeKey(key) + ".dat");
        if (!Files.exists(filePath)) {
            return new CompoundTag();
        }

        try {
            CompoundTag tag = NbtIo.readCompressed(filePath, NbtAccounter.unlimitedHeap());
            LOGGER.debug("[ZCSLIB] PDC loaded: key={}, size={} bytes", key,
                    tag != null ? tag.sizeInBytes() : 0);
            return tag != null ? tag : new CompoundTag();
        } catch (IOException e) {
            LOGGER.error("[ZCSLIB] Failed to load PDC key '{}': {}", key, e.getMessage());
            return new CompoundTag();
        }
    }

    /**
     * Delete a persisted key.
     *
     * @return true if the key existed and was deleted
     */
    public boolean delete(File dataDir, String key) {
        Path filePath = dataDir.toPath().resolve(sanitizeKey(key) + ".dat");
        try {
            boolean existed = Files.deleteIfExists(filePath);
            if (existed) {
                LOGGER.debug("[ZCSLIB] PDC deleted: key={}", key);
            }
            return existed;
        } catch (IOException e) {
            LOGGER.error("[ZCSLIB] Failed to delete PDC key '{}': {}", key, e.getMessage());
            return false;
        }
    }

    /** Check if a key exists on disk. */
    public boolean exists(File dataDir, String key) {
        return Files.exists(dataDir.toPath().resolve(sanitizeKey(key) + ".dat"));
    }

    // ── Helpers ──────────────────────────────────────────────

    private String sanitizeKey(String key) {
        // Replace path separators and other dangerous chars
        return key.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
