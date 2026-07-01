package zcslib.mcapi;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable player snapshots. Always returns independent copies —
 * no raw {@link ServerPlayer} references leak to plugin code.
 */
public final class PlayerAPI {

    private final MinecraftServer server;

    PlayerAPI(MinecraftServer server) {
        this.server = server;
    }

    public List<PlayerSnapshot> getOnlinePlayers() {
        List<PlayerSnapshot> list = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            list.add(PlayerSnapshot.from(sp));
        }
        return Collections.unmodifiableList(list);
    }

    public int getPlayerCount() {
        return server.getPlayerList().getPlayerCount();
    }

    public Optional<PlayerSnapshot> getPlayer(UUID uuid) {
        ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
        return sp != null ? Optional.of(PlayerSnapshot.from(sp)) : Optional.empty();
    }

    public Optional<PlayerSnapshot> getPlayer(String name) {
        ServerPlayer sp = server.getPlayerList().getPlayerByName(name);
        return sp != null ? Optional.of(PlayerSnapshot.from(sp)) : Optional.empty();
    }

    // ── Snapshot ───────────────────────────────────────────

    public record PlayerSnapshot(
            UUID uuid, String name, String displayName,
            double x, double y, double z,
            String dimension, float health, float maxHealth,
            int foodLevel, int experienceLevel,
            String gameMode, boolean isOp, boolean isAlive) {

        static PlayerSnapshot from(ServerPlayer sp) {
            return new PlayerSnapshot(
                    sp.getUUID(),
                    sp.getName().getString(),
                    sp.getDisplayName().getString(),
                    sp.getX(), sp.getY(), sp.getZ(),
                    sp.level().dimension().location().toString(),
                    sp.getHealth(),
                    sp.getMaxHealth(),
                    sp.getFoodData().getFoodLevel(),
                    sp.experienceLevel,
                    sp.gameMode.getGameModeForPlayer().getName(),
                    serverOpLevel(sp) >= 2,
                    sp.isAlive());
        }

        private static int serverOpLevel(ServerPlayer sp) {
            try {
                return sp.getServer().getPlayerList().getOps()
                        .get(sp.getGameProfile()).getLevel();
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
