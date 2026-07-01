package zcslib.security;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.kernel.ZCSKernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P16 — 插件命令白名单。
 *
 * <p>扩展 CommandAdapter，允许插件通过 {@code /zcslib run <plugin> <cmd>} 暴露命令。
 *
 * <p>线程模型：主线程注册/调度，ConcurrentHashMap。
 */
public class CommandWhitelist {

    // ── 白名单注册表 ──
    // pluginId → (commandName → handler)
    private final Map<String, Map<String, CommandHandler>> whitelist = new ConcurrentHashMap<>();

    // ── 依赖 ──
    private final ZCSKernel kernel;

    // ── 类型 ──

    @FunctionalInterface
    public interface CommandHandler {
        OrderResult execute(String[] args, CommandSourceStack src);
    }

    // 使用原始类以避免循环依赖
    public interface CommandSourceStack {
        boolean hasPermission(int level);
        void sendSuccess(ComponentSupplier supplier, boolean broadcast);
        void sendFailure(ComponentSupplier supplier);
    }

    /**
     * 从 Minecraft CommandSourceStack 创建适配桥。
     * 将 MC 的 net.minecraft.commands.CommandSourceStack 包装为本地接口。
     */
    public static CommandSourceStack wrap(net.minecraft.commands.CommandSourceStack mcSrc) {
        return new CommandSourceStack() {
            @Override
            public boolean hasPermission(int level) {
                return mcSrc.hasPermission(level);
            }
            @Override
            public void sendSuccess(ComponentSupplier supplier, boolean broadcast) {
                Object comp = supplier.get();
                if (comp instanceof net.minecraft.network.chat.Component mcComp) {
                    mcSrc.sendSuccess(() -> mcComp, broadcast);
                }
            }
            @Override
            public void sendFailure(ComponentSupplier supplier) {
                Object comp = supplier.get();
                if (comp instanceof net.minecraft.network.chat.Component mcComp) {
                    mcSrc.sendFailure(mcComp);
                }
            }
        };
    }

    @FunctionalInterface
    public interface ComponentSupplier {
        Object get(); // returns Component / TextComponent
    }

    // ── 构造 ──────────────────────────────────────────────

    public CommandWhitelist(ZCSKernel kernel) {
        this.kernel = kernel;
    }

    // ── 白名单管理 ──────────────────────────────────────

    /**
     * 注册插件命令。
     * 由插件通过 {@code kernel.order("security:cmd-register")} 调用。
     */
    public void registerCommand(String pluginId, String cmdName, CommandHandler handler) {
        whitelist.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>())
                .put(cmdName, handler);
    }

    /**
     * 注销单个命令。
     */
    public void unregisterCommand(String pluginId, String cmdName) {
        Map<String, CommandHandler> cmds = whitelist.get(pluginId);
        if (cmds != null) {
            cmds.remove(cmdName);
            if (cmds.isEmpty()) {
                whitelist.remove(pluginId);
            }
        }
    }

    /**
     * 注销插件的所有命令。
     */
    public void unregisterAll(String pluginId) {
        whitelist.remove(pluginId);
    }

    // ── 调度 ────────────────────────────────────────────

    /**
     * 调度插件命令。
     * 由 CommandAdapter 在 /zcslib run 中调用。
     */
    public OrderResult dispatch(String pluginId, String cmdName,
                                String[] args, CommandSourceStack src) {
        Map<String, CommandHandler> cmds = whitelist.get(pluginId);
        if (cmds == null) {
            return OrderResult.fail("No commands registered for plugin '" + pluginId + "'");
        }

        CommandHandler handler = cmds.get(cmdName);
        if (handler == null) {
            return OrderResult.fail("Unknown command '" + cmdName
                    + "' for plugin '" + pluginId + "'");
        }

        try {
            return handler.execute(args, src);
        } catch (Exception e) {
            return OrderResult.fail("COMMAND_ERROR: " + e.getClass().getSimpleName()
                    + ": " + (e.getMessage() != null ? e.getMessage() : ""));
        }
    }

    // ── 查询 ────────────────────────────────────────────

    public Set<String> getPluginCommands(String pluginId) {
        Map<String, CommandHandler> cmds = whitelist.get(pluginId);
        return cmds != null
                ? Collections.unmodifiableSet(cmds.keySet())
                : Collections.emptySet();
    }

    /**
     * 用于 /zcslib debug cmds 的全量查询。
     */
    public Map<String, Set<String>> getAllWhitelists() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var entry : whitelist.entrySet()) {
            result.put(entry.getKey(),
                    Collections.unmodifiableSet(entry.getValue().keySet()));
        }
        return Collections.unmodifiableMap(result);
    }

    // ── 信任门控 ────────────────────────────────────────

    /**
     * 检查指定信任级别的插件是否允许注册命令。
     */
    public boolean isCommandAllowed(String pluginId, TrustLevel trust) {
        return switch (trust) {
            case N, R -> true;
            case A -> false;  // Auto-Adapt 不允许注册对外命令
            case S, BLACKLISTED, UNKNOWN -> false;
        };
    }
}
