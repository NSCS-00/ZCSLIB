package zcslib.mcapi;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * Read-only block state and tile entity queries.
 * Always converts to snapshots — never leaks raw MC objects.
 */
public final class BlockAPI {

    private final MinecraftServer server;

    BlockAPI(MinecraftServer server) {
        this.server = server;
    }

    // ── Block state ───────────────────────────────────────

    public WorldAPI.BlockStateSnapshot getBlock(BlockPos pos) {
        return WorldAPI.BlockStateSnapshot.from(pos,
                server.overworld().getBlockState(pos));
    }

    public Optional<TileEntitySnapshot> getTileEntity(BlockPos pos) {
        BlockEntity be = server.overworld().getBlockEntity(pos);
        return be != null
                ? Optional.of(TileEntitySnapshot.from(pos, be))
                : Optional.empty();
    }

    public boolean isLoaded(BlockPos pos) {
        return server.overworld().isLoaded(pos);
    }

    public int getRedstonePower(BlockPos pos) {
        return server.overworld().getBestNeighborSignal(pos);
    }

    public boolean isSolid(BlockPos pos) {
        return server.overworld().getBlockState(pos).isSolid();
    }

    // ── Snapshot ───────────────────────────────────────────

    public record TileEntitySnapshot(BlockPos pos, String type, CompoundTag nbt) {

        static TileEntitySnapshot from(BlockPos pos, BlockEntity be) {
            CompoundTag tag = be.saveWithFullMetadata(
                    be.getLevel().registryAccess());
            String typeKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(be.getType()).toString();
            return new TileEntitySnapshot(pos, typeKey, tag);
        }
    }
}
