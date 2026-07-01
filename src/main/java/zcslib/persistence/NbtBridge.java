package zcslib.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Neutral NBT I/O and platform bridge.
 *
 * <p>All version-sensitive API calls are routed through this single file.
 * Compiled against NeoForge 21.1; runtime reflection handles 26.1+ deviations.
 */
public final class NbtBridge {
    /** True when FMLLoader.versionInfo() is available (21.1–25.x). */
    private static final boolean HAS_VERSION_INFO;

    static {
        boolean flag = false;
        try {
            // 21.1–25.x: FMLLoader.versionInfo() exists
            FMLLoader.versionInfo().neoForgeVersion();
            flag = true;
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // 26.1+: FMLLoader removed versionInfo() in FML 11.0+
            flag = false;
        }
        HAS_VERSION_INFO = flag;
    }

    private NbtBridge() {}

    // ── NBT I/O ────────────────────────────────────────────

    /** Gzip-compress and write a CompoundTag to {@code path}. */
    public static void writeCompressed(CompoundTag tag, Path path) throws IOException {
        NbtIo.writeCompressed(tag, path);
    }

    /** Read a gzip-compressed CompoundTag from {@code path}. */
    public static CompoundTag readCompressed(Path path) throws IOException {
        return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
    }

    /** Create an empty CompoundTag (safe default). */
    public static CompoundTag empty() {
        return new CompoundTag();
    }

    // ── Platform ───────────────────────────────────────────

    /** Return the game / server root directory. */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    // ── Version feature detection ──────────────────────────

    /** True when {@code FMLLoader.versionInfo()} is callable (21.1–25.x).
     *  False on 26.1+ where the API was removed (detected via NoSuchMethodError). */
    public static boolean hasVersionInfo() {
        return HAS_VERSION_INFO;
    }
}
