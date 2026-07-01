package zcslib.security;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P16 — 插件权限节点注册表。
 *
 * <p>为每个插件自动生成 MC PermissionAPI 节点，映射到命令、网络、控制台权限。
 *
 * <p>线程模型：初始化单线程写入，运行时多线程读取。
 */
public class PermissionNode {

    // ── 权限节点常量 ──
    public static final String ROOT        = "zcslib.plugin";
    public static final String OPERATOR    = "zcslib.operator";  // op 2+

    // ── 注册表 ──
    private final Set<String> registeredNodes = ConcurrentHashMap.newKeySet();

    // ── 插件节点映射 ──
    // pluginId → 该插件的权限集
    private final Map<String, PluginPermissions> pluginPerms = new ConcurrentHashMap<>();

    // ── 类型 ──

    public record PluginPermissions(
            String base,       // zcslib.plugin.<id>
            String network,    // zcslib.plugin.<id>.network
            String command,    // zcslib.plugin.<id>.command
            String console     // zcslib.plugin.<id>.console
    ) {}

    // ── 构造 ──────────────────────────────────────────────

    public PermissionNode() {
        // 注册根节点
        registeredNodes.add(ROOT);
        registeredNodes.add(OPERATOR);
    }

    // ── 注册 ────────────────────────────────────────────

    /**
     * 为插件生成权限节点并注册。
     * 由 PluginLoader 在加载插件时调用。
     */
    public PluginPermissions registerPlugin(String pluginId) {
        String base    = ROOT + "." + sanitize(pluginId);
        String network = base + ".network";
        String command = base + ".command";
        String console = base + ".console";

        PluginPermissions perms = new PluginPermissions(base, network, command, console);
        pluginPerms.put(pluginId, perms);

        registeredNodes.add(base);
        registeredNodes.add(network);
        registeredNodes.add(command);
        registeredNodes.add(console);

        return perms;
    }

    /**
     * 插件卸载时注销权限节点。
     */
    public void unregisterPlugin(String pluginId) {
        PluginPermissions perms = pluginPerms.remove(pluginId);
        if (perms != null) {
            registeredNodes.remove(perms.base());
            registeredNodes.remove(perms.network());
            registeredNodes.remove(perms.command());
            registeredNodes.remove(perms.console());
        }
    }

    // ── 查询 ────────────────────────────────────────────

    /**
     * 检查 CommandSourceStack 是否拥有指定权限节点。
     * 先验证节点是否已注册，再检查源是否有足够 op 等级。
     */
    public boolean hasPermission(CommandSourceStack src, String node) {
        if (src == null || node == null) return false;
        // 节点必须在注册表中
        if (!registeredNodes.contains(node)) return false;
        // MC 权限检查：op level 2+（或具体权限节点绑定）
        return src.hasPermission(2);
    }

    /**
     * 检查 CommandSourceStack 是否对指定插件的操作有权限。
     */
    public boolean hasPluginPermission(CommandSourceStack src, String pluginId, String action) {
        if (src == null) return false;
        PluginPermissions perms = pluginPerms.get(pluginId);
        if (perms == null) return false;

        String node = switch (action) {
            case "network" -> perms.network();
            case "command" -> perms.command();
            case "console" -> perms.console();
            default -> perms.base();
        };

        return hasPermission(src, node);
    }

    public PluginPermissions getPluginPermissions(String pluginId) {
        return pluginPerms.get(pluginId);
    }

    public Set<String> getAllNodes() {
        return Collections.unmodifiableSet(registeredNodes);
    }

    public Map<String, PluginPermissions> getAllPluginPermissions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pluginPerms));
    }

    // ── MC PermissionAPI 注册 ─────────────────────────────

    /**
     * 向 MC 权限系统注册所有节点。
     * 在 ServerStartedEvent 中由 ZCSKernel.initMcPort() 调用。
     *
     * <p>使用 Brigadier {@code requires()} 方式做权限检查，
     * 不依赖可能变动的 PermissionAPI 内部。
     */
    public void registerToMc(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Nodes are registered implicitly through Commands.literal().requires()
        // The actual enforcement happens in CommandAdapter and ZCSNetwork
        // via hasPermission() / hasPluginPermission() calls.
        // No explicit PermissionAPI registration needed for NeoForge 21.1.
    }

    // ── Internal ──────────────────────────────────────────

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }
}
