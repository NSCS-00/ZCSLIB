package zcslib.mcapi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import zcslib.api.TrustLevel;
import zcslib.kernel.ZCSKernel;
import zcslib.loader.PluginDescriptor;
import zcslib.monitor.PerfMonitor;
import zcslib.monitor.LeakDetector;
import zcslib.security.BanHammer;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Registers the {@code /zcslib} command root and subcommands.
 *
 * <p>Subcommands:
 * <ul>
 * <li>{@code /zcslib plugins} — list loaded plugins with trust + status</li>
 * <li>{@code /zcslib plugin <id> info} — show descriptor details</li>
 * <li>{@code /zcslib plugin <id> reload} — hot-reload a plugin</li>
 * <li>{@code /zcslib run <pluginId> <command> [args...]} — execute plugin command</li>
 * <li>{@code /zcslib debug tps} — live TPS + memory dump</li>
 * <li>{@code /zcslib debug audit} — recent audit entries (last 10)</li>
 * <li>{@code /zcslib debug perf} — P15 performance snapshot</li>
 * <li>{@code /zcslib debug leak} — P15 leak scan report</li>
 * <li>{@code /zcslib debug cmds} — list all registered plugin commands</li>
 * <li>{@code /zcslib ban <id>} — ban a plugin</li>
 * <li>{@code /zcslib unban <id>} — lift a ban</li>
 * </ul>
 *
 * <p>Requires operator level 2+ for all subcommands.
 */
public final class CommandAdapter {

    private final ZCSKernel kernel;

    public CommandAdapter(ZCSKernel kernel) {
        this.kernel = kernel;
    }

    /** Register all commands on the given dispatcher. */
    public void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("zcslib")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("plugins")
                        .executes(ctx -> listPlugins(ctx.getSource())))
                .then(Commands.literal("plugin")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.literal("info")
                                        .executes(ctx -> pluginInfo(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"))))
                                .then(Commands.literal("reload")
                                        .executes(ctx -> pluginReload(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("run")
                        .then(Commands.argument("plugin", StringArgumentType.word())
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> runCommand(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "plugin"),
                                                StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("tps")
                                .executes(ctx -> debugTps(ctx.getSource())))
                        .then(Commands.literal("audit")
                                .executes(ctx -> debugAudit(ctx.getSource())))
                        .then(Commands.literal("perf")
                                .executes(ctx -> debugPerf(ctx.getSource())))
                        .then(Commands.literal("leak")
                                .executes(ctx -> debugLeak(ctx.getSource())))
                        .then(Commands.literal("cmds")
                                .executes(ctx -> debugCmds(ctx.getSource()))))
                .then(Commands.literal("ban")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> banPlugin(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("unban")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> unbanPlugin(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id")))));

        dispatcher.register(root);
    }

    // ── Command implementations ────────────────────────────

    private int listPlugins(CommandSourceStack src) {
        Collection<PluginDescriptor> plugins = kernel.getAllPlugins();
        if (plugins.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7No plugins loaded."), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                "§6=== ZCSLIB Plugins §7(" + plugins.size() + " loaded) §6==="), false);
        for (PluginDescriptor pd : plugins) {
            TrustLevel t = pd.getTrustLevel();
            String color = switch (t) {
                case N -> "§a";  // green — native
                case R -> "§e";  // yellow — recognised
                case A -> "§6";  // gold — auto-adapt
                case S -> "§c";  // red — suspicious
                default -> "§7";
            };
            src.sendSuccess(() -> Component.literal(
                    "  " + color + "[" + t + "] §f" + pd.getPluginId()
                    + " §7v" + pd.getVersion()
                    + (pd.getDisplayName() != null && !pd.getDisplayName().isEmpty()
                            ? " §8— " + pd.getDisplayName() : "")), false);
        }
        return plugins.size();
    }

    private int pluginInfo(CommandSourceStack src, String pluginId) {
        PluginDescriptor pd = kernel.getPlugin(pluginId);
        if (pd == null) {
            src.sendFailure(Component.literal("§cPlugin '" + pluginId + "' not found."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6--- Plugin: " + pd.getPluginId() + " ---"), false);
        src.sendSuccess(() -> Component.literal("  §7ID: §f" + pd.getPluginId()), false);
        src.sendSuccess(() -> Component.literal("  §7Version: §f" + pd.getVersion()), false);
        src.sendSuccess(() -> Component.literal("  §7Display: §f" + pd.getDisplayName()), false);
        src.sendSuccess(() -> Component.literal("  §7Trust: §f" + pd.getTrustLevel()), false);
        return 1;
    }

    private int pluginReload(CommandSourceStack src, String pluginId) {
        PluginDescriptor pd = kernel.getPlugin(pluginId);
        if (pd == null) {
            src.sendFailure(Component.literal("§cPlugin '" + pluginId + "' not found."));
            return 0;
        }
        // TODO: implement actual hot-reload
        src.sendSuccess(() -> Component.literal(
                "§eHot-reload for '" + pluginId + "' is not yet implemented."), false);
        return 1;
    }

    private int debugTps(CommandSourceStack src) {
        var tickAPI = kernel.getMcPort().tick();
        double tps = tickAPI.getAverageTPS();
        double mspt = tickAPI.getMSPT();
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long heapMax = Runtime.getRuntime().maxMemory();

        src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: TPS ==="), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7TPS: §f%.1f §8(%.1f ms/tick)", tps, mspt)), false);
        src.sendSuccess(() -> Component.literal(
                "  §7Health: §f" + tickAPI.getTickHealth().name()), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7Heap: §f%d MB §8/ %d MB",
                        heapUsed / 1024 / 1024, heapMax / 1024 / 1024)), false);
        src.sendSuccess(() -> Component.literal(
                "  §7Plugins: §f" + kernel.getPluginCount()), false);
        src.sendSuccess(() -> Component.literal(
                "  §7Tick: §f#" + tickAPI.getServerTickCount()), false);
        return 1;
    }

    private int debugAudit(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: Audit (last 10) ==="), false);
        var audit = kernel.getAuditLogger();
        var entries = audit.getRecent(10);
        if (entries.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  §7No audit entries."), false);
            return 0;
        }
        for (var entry : entries) {
            String color = switch (entry.trust()) {
                case S -> "§c";
                case BLACKLISTED -> "§4";
                case A -> "§6";
                default -> "§7";
            };
            src.sendSuccess(() -> Component.literal(
                    color + "[" + entry.trust() + "][" + entry.pluginId() + "] "
                    + entry.category() + " — " + entry.detail()), false);
        }
        return entries.size();
    }

    // ── P15: debug perf ──────────────────────────────────────

    private int debugPerf(CommandSourceStack src) {
        PerfMonitor pm = kernel.getPerfMonitor();
        if (pm == null) {
            src.sendFailure(Component.literal("§cPerfMonitor not initialised."));
            return 0;
        }

        var snap = pm.snapshot("manual");
        src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: Performance Snapshot ==="), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7TPS: §f%.1f  §7MSPT: §f%.1fms  §7Health: §f%s",
                        snap.currentTPS(), snap.currentMSPT(), snap.health().name())), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7Heap: §f%d MB §8/ §f%d MB",
                        snap.heapUsedMB(), snap.heapMaxMB())), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7Chunks: §f%d  §7Entities: §f%d",
                        snap.chunks(), snap.entities())), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7Warnings — MSPT: §e%d §7(>%.0fms) §c%d §7(>%.0fms)  TPS: §e%d §7(<%.0f) §c%d §7(<%.0f)",
                        snap.msptWarnCount(), pm.getMsptWarnThreshold(),
                        snap.msptCriticalCount(), pm.getMsptCriticalThreshold(),
                        snap.tpsWarnCount(), pm.getTpsWarnThreshold(),
                        snap.tpsCriticalCount(), pm.getTpsCriticalThreshold())), false);

        // Plugin perf top 10
        src.sendSuccess(() -> Component.literal("  §6--- Plugin Performance (top 10) ---"), false);
        var pluginPerf = snap.pluginPerf();
        if (pluginPerf.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  §7No plugin perf data."), false);
        } else {
            var sorted = new java.util.ArrayList<>(pluginPerf.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue().avgLatencyMs(), a.getValue().avgLatencyMs()));
            int limit = Math.min(10, sorted.size());
            for (int j = 0; j < limit; j++) {
                var e = sorted.get(j);
                var pf = e.getValue();
                final int rank = j + 1;
                src.sendSuccess(() -> Component.literal(
                        String.format("  §7%d. §f%s §7avg=§f%.2fms §7calls=§f%d §7errs=§f%d",
                                rank, e.getKey(), pf.avgLatencyMs(), pf.orderCount(), pf.errorCount())), false);
            }
        }
        return 1;
    }

    // ── P15: debug leak ──────────────────────────────────────

    private int debugLeak(CommandSourceStack src) {
        LeakDetector ld = kernel.getLeakDetector();
        if (ld == null) {
            src.sendFailure(Component.literal("§cLeakDetector not initialised."));
            return 0;
        }

        var report = ld.fullScan();
        src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: Leak Scan ==="), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  §7Chunk Delta: §f%d  §7Entity Delta: §f%d  §7Orphaned Listeners: §f%d",
                        report.chunkDelta(), report.entityDelta(), report.orphanedListeners())), false);

        if (report.warnings().isEmpty()) {
            src.sendSuccess(() -> Component.literal("  §aNo leaks detected."), false);
        } else {
            for (String warning : report.warnings()) {
                src.sendSuccess(() -> Component.literal("  §c⚠ " + warning), false);
            }
        }
        return 1;
    }

    // ── P16: debug cmds ──────────────────────────────────────

    private int debugCmds(CommandSourceStack src) {
        var cw = kernel.getCommandWhitelist();
        if (cw == null) {
            src.sendFailure(Component.literal("§cCommandWhitelist not initialised."));
            return 0;
        }

        var all = cw.getAllWhitelists();
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: Plugin Commands === §7(none)"), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal("§6=== ZCSLIB Debug: Plugin Commands §7(" + all.size() + " plugins) ==="), false);
        for (var entry : all.entrySet()) {
            src.sendSuccess(() -> Component.literal(
                    "  §f" + entry.getKey() + " §7→ " + String.join(", ", entry.getValue())), false);
        }
        return all.size();
    }

    // ── P16: run command ─────────────────────────────────────

    private int runCommand(CommandSourceStack src, String pluginId, String cmdLine) {
        // Parse command and args from cmdLine
        String[] parts = cmdLine.split("\\s+", 2);
        String cmdName = parts[0];
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];

        var cw = kernel.getCommandWhitelist();
        if (cw == null) {
            src.sendFailure(Component.literal("§cCommandWhitelist not initialised."));
            return 0;
        }

        // Trust gating
        PluginDescriptor pd = kernel.getPlugin(pluginId);
        if (pd == null) {
            src.sendFailure(Component.literal("§cPlugin '" + pluginId + "' not found."));
            return 0;
        }

        if (!cw.isCommandAllowed(pluginId, pd.getTrustLevel())) {
            src.sendFailure(Component.literal("§cCommand execution not allowed for plugin '" + pluginId
                    + "' (trust=" + pd.getTrustLevel() + ")"));
            return 0;
        }

        // Build a simple CommandSourceStack wrapper
        var srcWrapper = new CommandWhitelistSrc(src);

        var result = cw.dispatch(pluginId, cmdName, args, srcWrapper);
        if (result.isOk()) {
            src.sendSuccess(() -> Component.literal("§a" + pluginId + "/" + cmdName + " → OK"), false);
            if (result.getData() != null) {
                src.sendSuccess(() -> Component.literal("  §7" + result.getData()), false);
            }
            return 1;
        } else {
            src.sendFailure(Component.literal("§c" + pluginId + "/" + cmdName + " → " + result.getError()));
            return 0;
        }
    }

    // ── ban / unban ───────────────────────────────────────────

    private int banPlugin(CommandSourceStack src, String pluginId) {
        BanHammer bh = kernel.getBanHammer();
        if (bh == null) {
            src.sendFailure(Component.literal("§cBanHammer not initialised."));
            return 0;
        }

        PluginDescriptor pd = kernel.getPlugin(pluginId);
        if (pd == null) {
            src.sendFailure(Component.literal("§cPlugin '" + pluginId + "' not found."));
            return 0;
        }

        bh.banPlugin(pluginId, "Manual ban by operator");
        src.sendSuccess(() -> Component.literal("§cPlugin '" + pluginId + "' has been BANNED."), true);
        return 1;
    }

    private int unbanPlugin(CommandSourceStack src, String pluginId) {
        BanHammer bh = kernel.getBanHammer();
        if (bh == null) {
            src.sendFailure(Component.literal("§cBanHammer not initialised."));
            return 0;
        }

        if (!bh.isBanned(pluginId)) {
            src.sendSuccess(() -> Component.literal("§ePlugin '" + pluginId + "' is not banned."), false);
            return 1;
        }

        bh.unbanPlugin(pluginId);
        src.sendSuccess(() -> Component.literal("§aPlugin '" + pluginId + "' has been UNBANNED."), true);
        return 1;
    }

    // ── CommandSourceStack wrapper for CommandWhitelist ───────

    /** Adapter to implement CommandWhitelist.CommandSourceStack interface. */
    private static class CommandWhitelistSrc implements zcslib.security.CommandWhitelist.CommandSourceStack {
        private final CommandSourceStack mcSrc;

        CommandWhitelistSrc(CommandSourceStack mcSrc) {
            this.mcSrc = mcSrc;
        }

        @Override
        public boolean hasPermission(int level) {
            return mcSrc.hasPermission(level);
        }

        @Override
        public void sendSuccess(zcslib.security.CommandWhitelist.ComponentSupplier supplier, boolean broadcast) {
            Object comp = supplier.get();
            if (comp instanceof Component c) {
                mcSrc.sendSuccess(() -> c, broadcast);
            }
        }

        @Override
        public void sendFailure(zcslib.security.CommandWhitelist.ComponentSupplier supplier) {
            Object comp = supplier.get();
            if (comp instanceof Component c) {
                mcSrc.sendFailure(c);
            }
        }
    }
}
