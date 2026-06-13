package zcslib.kernel;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.config.ConfigManager;
import zcslib.event.ZCSLEventBus;
import zcslib.loader.PluginDescriptor;
import zcslib.loader.PluginLoader;
import zcslib.log.ZCSLogger;
import zcslib.persistence.PDCBackend;
import zcslib.resource.ZCSResourceManager;
import zcslib.scheduler.ZCSScheduler;
import zcslib.service.ServiceRegistry;
import zcslib.network.ZCSNetwork;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * ZCSLIB microkernel — the sole command dispatcher.
 *
 * <p>All plugin functionality flows through {@link #order(String, Object...)}.
 * Each subsystem registers its command prefix at construction time;
 * the kernel routes by prefix match and enforces trust-level gating.
 *
 * <p>Phase 7 (M3): event:* + service:* wired — inter-plugin comms.</p>
 */
public class ZCSKernel {
    private final ZCSLogger logger;
    private final PluginLoader pluginLoader;
    private final ZCSResourceManager resourceManager;
    private final ZCSScheduler scheduler;
    private final ConfigManager configManager;
    private final PDCBackend pdcBackend;
    private final ZCSLEventBus eventBus;
    private final ServiceRegistry serviceRegistry;
    private final ZCSNetwork network;

    public ZCSKernel(Path gameDir) {
        this.logger = new ZCSLogger("zcslib", TrustLevel.N,
                gameDir.resolve("logs").resolve("zcslib"));

        // Phase 4: Init resource directory tree
        this.resourceManager = new ZCSResourceManager(gameDir);
        try {
            resourceManager.init();
            logger.info("Resource directory tree created at %s", resourceManager.getRootDir());
        } catch (IOException e) {
            logger.error("Failed to create resource directory tree: %s", e.getMessage());
        }

        // Phase 5: Init scheduler
        this.scheduler = new ZCSScheduler(this.logger);

        // Phase 6: Init config + PDC
        this.configManager = new ConfigManager();
        this.pdcBackend = new PDCBackend();

        // Phase 7 (M3): Init event bus + service registry
        this.eventBus = new ZCSLEventBus(this.logger);
        this.serviceRegistry = new ServiceRegistry(this.logger);

        // Phase 9 (M4): Init network layer
        this.network = new ZCSNetwork(this);

        logger.info("Kernel core loaded (M4 — Network online, scanning plugins...)");

        // Phase 3: Discover and load plugins
        this.pluginLoader = new PluginLoader(this, gameDir, resourceManager);
        pluginLoader.scanAndLoad();

        logger.info("Kernel ready — %d plugin(s) loaded.", pluginLoader.getPluginCount());
    }

    /**
     * Primary command entry point.
     *
     * @param command dispatch string in {@code "subsystem:action"} form
     * @param args    variable arguments passed to the subsystem handler
     */
    public OrderResult order(String command, Object... args) {
        if (command == null) return OrderResult.fail("NULL_COMMAND");

        try {
            return dispatch0(command, args);
        } catch (Exception e) {
            logger.error("order('%s') threw unhandled exception: %s", command, e.toString());
            return OrderResult.fail("INTERNAL: " + e.getMessage());
        }
    }

    private OrderResult dispatch0(String command, Object... args) {

        // ── event:* ──────────────────────────────────────────
        if (command.startsWith("event:")) {
            return dispatchEvent(command, args);
        }

        // ── service:* ────────────────────────────────────────
        if (command.startsWith("service:")) {
            return dispatchService(command, args);
        }

        // ── config:* ────────────────────────────────────────
        if (command.startsWith("config:")) {
            return dispatchConfig(command, args);
        }

        // ── pdc:* ───────────────────────────────────────────
        if (command.startsWith("pdc:")) {
            return dispatchPdc(command, args);
        }

        // ── scheduler:* ─────────────────────────────────────
        if (command.startsWith("scheduler:")) {
            String sAction = command.substring("scheduler:".length());
            return scheduler.dispatch(sAction, args, TrustLevel.N);
        }

        // ── network:* ──────────────────────────────────────
        if (command.startsWith("network:")) {
            return dispatchNetwork(command, args);
        }

        // ── resource:* ───────────────────────────────────────
        if (command.startsWith("resource:")) {
            return dispatchResource(command, args);
        }

        logger.debug("order() called with unknown subsystem: %s", command);
        return OrderResult.fail("KERNEL_NO_SUBSYSTEMS: Unknown command prefix '" + command + "'");
    }

    // ── Subsystem dispatchers ────────────────────────────────

    private OrderResult dispatchEvent(String command, Object... args) {
        String action = command.substring("event:".length());
        return switch (action) {
            case "register" -> {
                if (args.length < 2) yield OrderResult.fail("event:register requires (pluginId, listener)");
                String pid = (String) args[0];
                Object listener = args[1];
                TrustLevel trust = resolveTrust(pid);
                eventBus.register(listener, pid, trust);
                yield OrderResult.success();
            }
            case "post" -> {
                if (args.length < 1) yield OrderResult.fail("event:post requires (event)");
                yield OrderResult.success(eventBus.post(args[0]));
            }
            case "unregister" -> {
                if (args.length < 1) yield OrderResult.fail("event:unregister requires (listener)");
                int removed = eventBus.unregister(args[0]);
                yield OrderResult.success(removed);
            }
            default -> OrderResult.fail("Unknown event action: " + action);
        };
    }

    private OrderResult dispatchService(String command, Object... args) {
        String action = command.substring("service:".length());
        return switch (action) {
            case "register" -> {
                if (args.length < 3)
                    yield OrderResult.fail("service:register requires (apiClass, impl, pluginId)");
                @SuppressWarnings("unchecked")
                Class<Object> api = (Class<Object>) args[0];
                Object impl = args[1];
                String pid = (String) args[2];
                TrustLevel trust = resolveTrust(pid);
                boolean ok = serviceRegistry.register(api, impl, pid, trust);
                yield ok ? OrderResult.success() : OrderResult.fail("Service registration blocked");
            }
            case "get" -> {
                if (args.length < 1) yield OrderResult.fail("service:get requires (apiClass)");
                Object svc = serviceRegistry.get((Class<?>) args[0]);
                yield OrderResult.success(svc);
            }
            case "get:meta" -> {
                if (args.length < 1) yield OrderResult.fail("service:get:meta requires (apiClass)");
                yield OrderResult.success(serviceRegistry.getMeta((Class<?>) args[0]));
            }
            case "list" -> {
                yield OrderResult.success(serviceRegistry.list());
            }
            default -> OrderResult.fail("Unknown service action: " + action);
        };
    }

    private OrderResult dispatchConfig(String command, Object... args) {
        String action = command.substring("config:".length());
        return switch (action) {
            case "load" -> {
                if (args.length < 2) yield OrderResult.fail("config:load requires (pluginId, filename)");
                String pid = (String) args[0];
                String fname = (String) args[1];
                File configDir = resourceManager.resolve(pid, "");
                Map<String, Object> result = configManager.loadAsMap(configDir, fname);
                yield OrderResult.success(result);
            }
            case "save" -> {
                if (args.length < 3) yield OrderResult.fail("config:save requires (pluginId, filename, data)");
                String pid = (String) args[0];
                String fname = (String) args[1];
                File configDir = resourceManager.resolve(pid, "");
                boolean ok = configManager.save(configDir, fname, args[2]);
                yield ok ? OrderResult.success() : OrderResult.fail("Failed to save config");
            }
            case "reload" -> {
                if (args.length < 2) yield OrderResult.fail("config:reload requires (pluginId, filename)");
                String pid = (String) args[0];
                String fname = (String) args[1];
                File configDir = resourceManager.resolve(pid, "");
                Map<String, Object> result = configManager.reload(configDir, fname, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                yield OrderResult.success(result);
            }
            default -> OrderResult.fail("Unknown config action: " + action);
        };
    }

    private OrderResult dispatchPdc(String command, Object... args) {
        String action = command.substring("pdc:".length());
        return switch (action) {
            case "save" -> {
                if (args.length < 3) yield OrderResult.fail("pdc:save requires (pluginId, key, compoundTag)");
                String pid = (String) args[0];
                String key = (String) args[1];
                if (!(args[2] instanceof net.minecraft.nbt.CompoundTag tag))
                    yield OrderResult.fail("pdc:save arg[2] must be CompoundTag");
                File dataDir = resourceManager.resolve(pid, "data");
                boolean ok = pdcBackend.save(dataDir, key, tag);
                yield ok ? OrderResult.success() : OrderResult.fail("Failed to save PDC");
            }
            case "load" -> {
                if (args.length < 2) yield OrderResult.fail("pdc:load requires (pluginId, key)");
                String pid = (String) args[0];
                String key = (String) args[1];
                File dataDir = resourceManager.resolve(pid, "data");
                net.minecraft.nbt.CompoundTag tag = pdcBackend.load(dataDir, key);
                yield OrderResult.success(tag);
            }
            case "delete" -> {
                if (args.length < 2) yield OrderResult.fail("pdc:delete requires (pluginId, key)");
                String pid = (String) args[0];
                String key = (String) args[1];
                File dataDir = resourceManager.resolve(pid, "data");
                boolean ok = pdcBackend.delete(dataDir, key);
                yield ok ? OrderResult.success() : OrderResult.fail("Key not found");
            }
            default -> OrderResult.fail("Unknown pdc action: " + action);
        };
    }

    private OrderResult dispatchResource(String command, Object... args) {
        String action = command.substring("resource:".length());

        return switch (action) {
            case "file" -> {
                if (args.length < 2) yield OrderResult.fail("resource:file requires (pluginId, path)");
                String pid = (String) args[0];
                String vpath = (String) args[1];
                try {
                    File f = resourceManager.resolve(pid, vpath);
                    yield OrderResult.success(f);
                } catch (SecurityException e) {
                    yield OrderResult.fail(e.getMessage());
                }
            }
            case "dir" -> {
                if (args.length < 1) yield OrderResult.fail("resource:dir requires (pluginId)");
                String pid = (String) args[0];
                if (resourceManager.getSandbox(pid) == null)
                    yield OrderResult.fail("Plugin '" + pid + "' not registered");
                yield OrderResult.success(resourceManager.getSandbox(pid).getRoot().toFile());
            }
            default ->
                OrderResult.fail("Unknown resource action: " + action);
        };
    }

    private OrderResult dispatchNetwork(String command, Object... args) {
        String action = command.substring("network:".length());
        TrustLevel trust = (args.length > 2 && args[args.length - 1] instanceof TrustLevel t)
                ? t : TrustLevel.N;
        return network.order(action, args);
    }

    // ── Internal helpers ─────────────────────────────────────

    /** Resolve a plugin's trust level from the loader registry. */
    private TrustLevel resolveTrust(String pluginId) {
        PluginDescriptor desc = pluginLoader.getPlugin(pluginId);
        return desc != null ? desc.getTrustLevel() : TrustLevel.S;
    }

    // ── Accessors ────────────────────────────────────────────

    public ZCSLogger getLogger()                        { return logger; }
    public ZCSResourceManager getResourceManager()      { return resourceManager; }
    public ZCSScheduler getScheduler()                   { return scheduler; }
    public ZCSLEventBus getEventBus()                    { return eventBus; }
    public ServiceRegistry getServiceRegistry()          { return serviceRegistry; }
    public ZCSNetwork getNetwork()                        { return network; }
    public PluginDescriptor getPlugin(String pluginId)  { return pluginLoader.getPlugin(pluginId); }
    public Collection<PluginDescriptor> getAllPlugins() { return pluginLoader.getAllPlugins(); }
    public int getPluginCount()                         { return pluginLoader.getPluginCount(); }
}
