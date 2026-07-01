package zcslib.mcapi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only world queries. Returns snapshots — never raw MC objects.
 * Built against {@link ServerLevel#overworld()}, dimension-aware via key.
 */
public final class WorldAPI {

    private final MinecraftServer server;

    WorldAPI(MinecraftServer server) {
        this.server = server;
    }

    private ServerLevel world() {
        return server.overworld();
    }

    /** Package-private: returns the raw ServerLevel for kernel-internal use (sandbox, etc.). */
    public ServerLevel getServerLevel() {
        return world();
    }

    // ── Chunk queries ──────────────────────────────────────

    /** Number of loaded chunks in the overworld. */
    public int getLoadedChunkCount() {
        return world().getChunkSource().getLoadedChunksCount();
    }

    /** Approximate loaded entity count (all dimensions). */
    public int getLoadedEntityCount() {
        int count = 0;
        for (var level : server.getAllLevels()) {
            for (var ignored : level.getEntities().getAll()) count++;
        }
        return count;
    }

    /** Force-loaded (forceload) chunk positions via public NeoForge API. */
    public java.util.Set<ChunkPos> getForceLoadedChunks() {
        java.util.Set<ChunkPos> set = new java.util.HashSet<>();
        for (long l : world().getForcedChunks()) {
            set.add(new ChunkPos(l));
        }
        return set;
    }

    public boolean isChunkLoaded(int x, int z) {
        return world().getChunkSource().hasChunk(x, z);
    }

    // ── Entity queries ─────────────────────────────────────

    /** Entities within an AABB, as snapshots. */
    public List<EntitySnapshot> getEntitiesInAABB(
            double x1, double y1, double z1,
            double x2, double y2, double z2) {
        List<EntitySnapshot> list = new ArrayList<>();
        var aabb = new net.minecraft.world.phys.AABB(x1, y1, z1, x2, y2, z2);
        for (var entity : world().getEntities(null, aabb)) {
            list.add(EntitySnapshot.from(entity));
        }
        return list;
    }

    /** Entities of a given type within radius of center. */
    public List<EntitySnapshot> getEntitiesByType(
            String entityType, int radius, BlockPos center) {
        List<EntitySnapshot> list = new ArrayList<>();
        var aabb = new net.minecraft.world.phys.AABB(
                center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                center.getX() + radius, center.getY() + radius, center.getZ() + radius);
        for (var entity : world().getEntities(null, aabb)) {
            String type = entity.getType().getDescriptionId();
            if (type.contains(entityType)) {
                list.add(EntitySnapshot.from(entity));
            }
        }
        return list;
    }

    // ── Block queries ──────────────────────────────────────

    public BlockStateSnapshot getBlockState(BlockPos pos) {
        BlockState bs = world().getBlockState(pos);
        return BlockStateSnapshot.from(pos, bs);
    }

    public boolean canSeeSky(BlockPos pos) {
        return world().canSeeSky(pos);
    }

    public int getLightLevel(BlockPos pos) {
        return world().getMaxLocalRawBrightness(pos);
    }

    // ── World metadata ─────────────────────────────────────

    public long getGameTime()      { return world().getGameTime(); }
    public long getDayTime()       { return world().getDayTime(); }
    public boolean isThundering()  { return world().isThundering(); }
    public boolean isRaining()     { return world().isRaining(); }
    public String getDimensionKey() { return world().dimension().location().toString(); }
    public long getSeed()          { return world().getSeed(); }

    // ── Snapshot types ─────────────────────────────────────

    public record EntitySnapshot(
            int id, String type, String name,
            double x, double y, double z,
            String dimension, boolean isAlive) {

        static EntitySnapshot from(net.minecraft.world.entity.Entity e) {
            return new EntitySnapshot(
                    e.getId(),
                    e.getType().getDescriptionId(),
                    e.getName().getString(),
                    e.getX(), e.getY(), e.getZ(),
                    e.level().dimension().location().toString(),
                    e.isAlive());
        }
    }

    public record BlockStateSnapshot(
            BlockPos pos, String blockId,
            boolean isSolid, boolean isAir,
            boolean requiresTool, float destroySpeed) {

        static BlockStateSnapshot from(BlockPos pos, BlockState bs) {
            return new BlockStateSnapshot(
                    pos,
                    bs.getBlock().getDescriptionId(),
                    bs.isSolid(),
                    bs.isAir(),
                    bs.requiresCorrectToolForDrops(),
                    bs.getDestroySpeed(null, pos));
        }
    }
}
