package zcslib.kernel;

import zcslib.api.OrderResult;
import zcslib.api.TrustLevel;
import zcslib.config.ConfigManager;
import zcslib.event.ZCSLEventBus;
import zcslib.evolution.memory.L1Buffer;
import zcslib.evolution.memory.L1Snapshot;
import zcslib.evolution.memory.L2Event;
import zcslib.evolution.memory.L2Journal;
import zcslib.evolution.memory.L3Memory;
import zcslib.evolution.memory.L4Instinct;
import zcslib.evolution.params.GlobalParams;
import zcslib.evolution.quarantine.QuarantineDecider;
import zcslib.loader.PluginDescriptor;
import zcslib.loader.PluginLoader;
import zcslib.log.AuditLogger;
import zcslib.log.LogRotator;
import zcslib.log.ZCSLogger;
import zcslib.persistence.PDCBackend;
import zcslib.resource.ZCSResourceManager;
import zcslib.scheduler.ZCSScheduler;
import zcslib.service.ServiceRegistry;
import zcslib.network.ZCSNetwork;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZCSLIB microkernel — the sole command dispatcher.
 *
 * <p>All plugin functionality flows through {@link #order(String, Object...)}.
 * Each subsystem registers its command prefix at construction time;
 * the kernel routes by prefix match and enforces trust-level gating.
 *
 * <p>Phase 12: L1/L2 memory collection — every {@code order()} call is
 * traced via {@link #orderTraced(String, String, Object...)}.</p>
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

    // ── Phase 10: Audit ───────────────────────────────────
    private final AuditLogger auditLogger;

    // ── Phase 12: L1/L2 memory ──────────────────────────────
    private final Path gameDir;
    private final Path zcsRoot;
    private final L1Buffer l1Buffer;
    private final Map<String, L2Journal> l2Journals = new ConcurrentHashMap<>();
    private final Map<String, L3Memory> l3Memories = new ConcurrentHashMap<>();
    private final L4Instinct l4Instinct;
    private final QuarantineDecider quarantineDecider;
    private long tickCounter = 0;

    public ZCSKernel(Path gameDir) {
        this.gameDir = gameDir;
        this.zcsRoot = gameDir.resolve("config/DLZstudio/ZCSLIB");
        this.l1Buffer = new L1Buffer();
        L4Instinct loaded;
        try {
            loaded = L4Instinct.load(gameDir.resolve("config/DLZstudio/ZCSLIB/memory/l4/instinct.zcsinst"));
        } catch (java.io.IOException e) {
            loaded = new L4Instinct();
        }
        this.l4Instinct = loaded;
        this.quarantineDecider = new QuarantineDecider(l4Instinct, new GlobalParams());

        this.auditLogger = new AuditLogger(gameDir.resolve("logs/zcslib/audit"));

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

        logger.info("Kernel core loaded (M4 — Network online, L1/L2 memory ready, scanning plugins...)");

        // Phase 3: Discover and load plugins
        this.pluginLoader = new PluginLoader(this, gameDir, resourceManager);
        pluginLoader.scanAndLoad();

        // Phase 10: Wire audit logger
        auditLogger.log(TrustLevel.N, "zcslib", "KERNEL_START",
                "ZCSLIB Kernel v0.2.0 booting — plugins=" + pluginLoader.getPluginCount());

        // Phase 12: Open L2 journals for all loaded plugins
        openAllL2Journals();

        logger.info("Kernel ready — %d plugin(s) loaded, L1/L2 memory active.",
                pluginLoader.getPluginCount());
        auditLogger.log(TrustLevel.N, "zcslib", "KERNEL_READY",
                pluginLoader.getPluginCount() + " plugin(s) online");

        // Phase 10: Run log rotation cleanup at startup
        LogRotator.rotateAll();
    }

    /**
     * Traced order — preferred entry for plugins.
     * Captures timing + result into L1/L2 automatically.
     */
    public OrderResult orderTraced(String pluginId, String command, Object... args) {
        long startNanos = System.nanoTime();
        OrderResult result = order(command, args);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        // L1: push call frame
        long tick = tickCounter;
        l1Buffer.push(tick, new String[]{
                pluginId + ":" + command + " → " +
                (result.isOk() ? "OK" : "FAIL") + " (" + durationMs + "ms)"
        });

        // L2: append journal event
        L2Event.Result l2Result;
        if (result.isOk()) {
            l2Result = L2Event.Result.OK;
        } else if (result.getError() != null && result.getError().contains("TIMEOUT")) {
            l2Result = L2Event.Result.TIMEOUT;
        } else if (result.getError() != null && result.getError().contains("FORBIDDEN")) {
            l2Result = L2Event.Result.BLOCKED;
        } else if (result.getError() != null && result.getError().contains("REJECTED")) {
            l2Result = L2Event.Result.REJECTED;
        } else {
            l2Result = result.isOk() ? L2Event.Result.OK : L2Event.Result.ERROR;
        }
        recordL2(pluginId, command, tick, durationMs, l2Result);

        return result;
    }

    /**
     * Primary command entry point (untraced — for internal use).
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

    // ── L1/L2 public API ────────────────────────────────────

    /**
     * Call each MC tick to push a snapshot of the current state.
     * Also records a lightweight tick frame in L1.
     */
    public void onTick() {
        tickCounter++;
        // Push a light tick frame (one per tick for temporal indexing)
        if (tickCounter % 20 == 0) { // every 20 ticks (~1s)
            l1Buffer.push(tickCounter, new String[]{
                    "TICK:" + tickCounter + " plugins=" + pluginLoader.getPluginCount()
            });
        }
    }

    /**
     * Open an L2 journal for a newly loaded plugin.
     */
    public void openL2Journal(String pluginId) {
        try {
            Path l2Dir = zcsRoot.resolve("plugins").resolve(pluginId).resolve("memory/l2");
            L2Journal journal = new L2Journal(pluginId, l2Dir);
            l2Journals.put(pluginId, journal);
            logger.debug("L2 journal opened for %s", pluginId);
        } catch (IOException e) {
            logger.error("Failed to open L2 journal for %s: %s", pluginId, e.getMessage());
        }
    }

    /**
     * L1 snapshot access for crash handler / inspector.
     */
    public L1Buffer getL1Buffer() { return l1Buffer; }

    /**
     * Freeze L1 and persist to memory/l1/.
     * Called on crash or graceful shutdown.
     */
    public L1Snapshot freezeL1() {
        L1Snapshot snap = l1Buffer.freeze();
        try {
            Path l1Dir = zcsRoot.resolve("memory/l1");
            Path file = snap.persist(l1Dir);
            logger.info("L1 snapshot frozen: %s (%d frames)", file, snap.frameCount());
        } catch (Exception e) {
            logger.error("L1 snapshot persist failed: %s", e.getMessage());
        }
        return snap;
    }

    /**
     * Graceful shutdown — close all L2 journals, freeze L1.
     */
    public void shutdown() {
        logger.info("Shutting down — freezing L1 + closing %d L2 journal(s)...", l2Journals.size());
        freezeL1();
        for (L2Journal j : l2Journals.values()) {
            try { j.close(); } catch (IOException ignored) {}
        }
        l2Journals.clear();
        auditLogger.flushAll();
        auditLogger.closeAll();
        logger.info("Shutdown complete.");
    }

    public long getTickCounter() { return tickCounter; }
    public Path getZcsRoot() { return zcsRoot; }

    // ── Internal ────────────────────────────────────────────

    private void openAllL2Journals() {
        for (PluginDescriptor desc : pluginLoader.getAllPlugins()) {
            openL2Journal(desc.getPluginId());
        }
    }

    private void recordL2(String pluginId, String command, long tick,
                          long durationMs, L2Event.Result result) {
        L2Journal journal = l2Journals.get(pluginId);
        if (journal == null) return; // plugin not registered or journal failed to open

        // Parse subsystem from command (prefix before first ':').
        // For compound args like "service:register", subsystem = "service", action = "register".
        String subsystem, action;
        int colon = command.indexOf(':');
        if (colon > 0) {
            subsystem = command.substring(0, colon);
            action = command.substring(colon + 1);
        } else {
            subsystem = "unknown";
            action = command;
        }

        L2Event event = new L2Event(tick, pluginId, subsystem, action, result, durationMs);
        try {
            journal.append(event);
        } catch (IOException e) {
            // Silently drop — don't let L2 failure affect the plugin
        }
    }

    // ── Dispatch ────────────────────────────────────────────

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

        // ── audit:* ──────────────────────────────────────
        if (command.startsWith("audit:")) {
            return dispatchAudit(command, args);
        }

        // ── memory:* ──────────────────────────────────────
        if (command.startsWith("memory:")) {
            return dispatchMemory(command, args);
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
                if (!ok && trust == TrustLevel.S) {
                    auditLogger.log(TrustLevel.S, pid, "SERVICE_BLOCK",
                            "S-level plugin blocked from registering: " + api.getName());
                }
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

    // ── Phase 10: Audit dispatch ─────────────────────────────

    private OrderResult dispatchAudit(String command, Object... args) {
        String action = command.substring("audit:".length());
        return switch (action) {
            case "log" -> {
                if (args.length < 3)
                    yield OrderResult.fail("audit:log requires (pluginId, category, detail)");
                String pid = (String) args[0];
                String cat = (String) args[1];
                String detail = (String) args[2];
                auditLogger.log(resolveTrust(pid), pid, cat, detail);
                yield OrderResult.success();
            }
            case "cross" -> {
                if (args.length < 4)
                    yield OrderResult.fail("audit:cross requires (caller, callee, category, detail)");
                TrustLevel callerT = resolveTrust((String) args[0]);
                TrustLevel calleeT = resolveTrust((String) args[1]);
                auditLogger.logTrusted((String) args[0], (String) args[2], (String) args[3], callerT, calleeT);
                yield OrderResult.success();
            }
            default -> OrderResult.fail("Unknown audit action: " + action);
        };
    }

    // ── Phase 12: Memory dispatch (L1/L2/L3/L4) ────────────────

    private OrderResult dispatchMemory(String command, Object... args) {
        String action = command.substring("memory:".length());
        return switch (action) {
            case "l4-check" -> {
                if (args.length < 1) yield OrderResult.fail("memory:l4-check requires (methodName)");
                String method = (String) args[0];
                yield OrderResult.success(l4Instinct.match(method));
            }
            case "l3-query" -> {
                if (args.length < 2) yield OrderResult.fail("memory:l3-query requires (pluginId, methodName)");
                String pid = (String) args[0];
                String method = (String) args[1];
                L3Memory l3 = l3Memories.get(pid);
                if (l3 == null) yield OrderResult.success(java.util.Collections.emptyList());
                yield OrderResult.success(l3.match(pid, method, "*"));
            }
            case "l3-load" -> {
                if (args.length < 1) yield OrderResult.fail("memory:l3-load requires (pluginId)");
                String pid = (String) args[0];
                try {
                    // Find any .zcsmem file in the plugin's l3 directory
                    Path l3Dir = zcsRoot.resolve("plugins").resolve(pid).resolve("memory/l3");
                    if (!Files.isDirectory(l3Dir)) yield OrderResult.success(null);
                    Path[] files = Files.list(l3Dir)
                            .filter(p -> p.toString().endsWith(".zcsmem"))
                            .toArray(Path[]::new);
                    if (files.length == 0) yield OrderResult.success(null);
                    L3Memory l3 = L3Memory.load(files[0]);
                    l3Memories.put(pid, l3);
                    yield OrderResult.success(l3);
                } catch (Exception e) {
                    yield OrderResult.fail("L3 load failed: " + e.getMessage());
                }
            }
            case "l1-freeze" -> {
                L1Snapshot snap = freezeL1();
                yield OrderResult.success(snap);
            }
            case "l2-stats" -> {
                if (args.length < 1) {
                    var stats = new java.util.HashMap<String, Object>();
                    for (var e : l2Journals.entrySet()) {
                        var m = new java.util.HashMap<String, String>();
                        m.put("pluginId", e.getValue().pluginId());
                        m.put("file", e.getValue().file().toString());
                        stats.put(e.getKey(), m);
                    }
                    yield OrderResult.success(stats);
                }
                String pid = (String) args[0];
                L2Journal j = l2Journals.get(pid);
                yield j != null ? OrderResult.success(j.file().toString())
                        : OrderResult.fail("No L2 journal for " + pid);
            }
            case "decide" -> {
                if (args.length < 3) yield OrderResult.fail("memory:decide requires (pluginId, trust, methodName)");
                String pid = (String) args[0];
                TrustLevel trust = (TrustLevel) args[1];
                String method = (String) args[2];
                L3Memory l3 = l3Memories.get(pid);
                QuarantineDecider.Verdict v = quarantineDecider.evaluate(pid, trust, method, l3, null);
                yield OrderResult.success(v);
            }
            default -> OrderResult.fail("Unknown memory action: " + action
                    + " (valid: l1-freeze, l2-stats, l3-query, l3-load, l4-check, decide)");
        };
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
    public AuditLogger getAuditLogger()                   { return auditLogger; }
    public PluginDescriptor getPlugin(String pluginId)  { return pluginLoader.getPlugin(pluginId); }
    public Collection<PluginDescriptor> getAllPlugins() { return pluginLoader.getAllPlugins(); }
    public int getPluginCount()                         { return pluginLoader.getPluginCount(); }
}
