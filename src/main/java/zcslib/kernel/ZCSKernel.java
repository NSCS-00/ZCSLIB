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
import zcslib.mcapi.McPort;
import zcslib.mcapi.CommandAdapter;
import zcslib.sandbox.VirtualSave;
import zcslib.sandbox.DryRunContext;
import zcslib.sandbox.AutoRollback;
import zcslib.monitor.PerfMonitor;
import zcslib.monitor.LagGuard;
import zcslib.monitor.LeakDetector;
import zcslib.monitor.CrashGuard;
import zcslib.monitor.AutoSave;
import zcslib.security.PermissionNode;
import zcslib.security.CommandWhitelist;
import zcslib.security.NetworkAudit;
import zcslib.security.BanHammer;

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

    // ── M6: MC integration ────────────────────────────────
    private McPort mcPort;
    private CommandAdapter commandAdapter;
    private final AutoRollback autoRollback = new AutoRollback();

    // ── P15: Monitor ──────────────────────────────────────
    private PerfMonitor perfMonitor;
    private LagGuard lagGuard;
    private LeakDetector leakDetector;
    private CrashGuard crashGuard;
    private AutoSave autoSave;

    // ── P16: Security ─────────────────────────────────────
    private PermissionNode permissionNode;
    private CommandWhitelist commandWhitelist;
    private NetworkAudit networkAudit;
    private BanHammer banHammer;

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

        // Phase 3: Create plugin loader (must precede CrashGuard/BanHammer)
        this.pluginLoader = new PluginLoader(this, gameDir, resourceManager);

        // ── P15: Init monitor subsystem ──────────────────────
        this.perfMonitor = new PerfMonitor(null, logger, auditLogger); // TickAPI 在 McPort 初始化后注入
        this.lagGuard = new LagGuard(auditLogger);
        this.leakDetector = new LeakDetector(eventBus, null, null, logger, auditLogger);
        this.crashGuard = new CrashGuard(auditLogger, logger, pluginLoader);
        this.autoSave = new AutoSave(null, perfMonitor, auditLogger, logger);

        // ── P16: Init security subsystem ─────────────────────
        this.permissionNode = new PermissionNode();
        this.commandWhitelist = new CommandWhitelist(this);
        this.networkAudit = new NetworkAudit(auditLogger);
        this.banHammer = new BanHammer(crashGuard, lagGuard, leakDetector,
                networkAudit, auditLogger, pluginLoader, logger, zcsRoot);
        // NOTE: loadBans() is deferred to initMcPort() to avoid double-load

        logger.info("Kernel core loaded (M5 — Network online, L1/L2 memory ready, scanning plugins...)");

        // Phase 3: Discover and load plugins (scan after subsystems are wired)
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
     *
     * <p>P15: LagGuard timeout wrapper → CrashGuard isolation wrapper →
     * order() business logic. PerfMonitor records every call.</p>
     *
     * <p>P16: BLACKLISTED plugins are rejected immediately.</p>
     */
    public OrderResult orderTraced(String pluginId, String command, Object... args) {
        // P16: BLACKLISTED → immediate reject
        TrustLevel trust = resolveTrust(pluginId);
        if (trust == TrustLevel.BLACKLISTED) {
            auditLogger.log(TrustLevel.BLACKLISTED, pluginId, "BLOCKED",
                    "BLACKLISTED plugin attempted: " + command);
            OrderResult blocked = OrderResult.fail("FORBIDDEN:BLACKLISTED plugin '" + pluginId + "' is banned");
            recordOrderMetrics(pluginId, command, blocked, 0);
            return blocked;
        }

        long startNanos = System.nanoTime();

        // P15: LagGuard + CrashGuard + order() sandwich
        if (lagGuard != null && crashGuard != null) {
            // Full protection: LagGuard → CrashGuard → order()
            final OrderResult[] holder = new OrderResult[1];
            boolean completed = lagGuard.guard(pluginId, command, () -> {
                holder[0] = crashGuard.execute(pluginId, command,
                        () -> order(pluginId, command, args));
            });

            if (!completed) {
                // Timeout — LagGuard 已记录审计
                OrderResult timeoutResult = OrderResult.fail("TIMEOUT:" + pluginId
                        + " exceeded " + lagGuard.getTimeoutMs() + "ms");
                if (perfMonitor != null) {
                    perfMonitor.recordPluginTimeout(pluginId);
                }
                recordOrderMetrics(pluginId, command, timeoutResult,
                        (System.nanoTime() - startNanos) / 1_000_000);
                return timeoutResult;
            }

            OrderResult result = holder[0];
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (perfMonitor != null) {
                perfMonitor.recordPluginOrder(pluginId, durationMs, result.isOk());
            }
            recordOrderMetrics(pluginId, command, result, durationMs);
            return result;
        }

        if (crashGuard != null) {
            // CrashGuard only (no LagGuard)
            OrderResult result = crashGuard.execute(pluginId, command,
                    () -> order(pluginId, command, args));
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (perfMonitor != null) {
                perfMonitor.recordPluginOrder(pluginId, durationMs, result.isOk());
            }
            recordOrderMetrics(pluginId, command, result, durationMs);
            return result;
        }

        // Fallback: original path (no monitor/security)
        OrderResult result = order(pluginId, command, args);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (perfMonitor != null) {
            perfMonitor.recordPluginOrder(pluginId, durationMs, result.isOk());
        }
        recordOrderMetrics(pluginId, command, result, durationMs);
        return result;
    }

    /**
     * Record L1/L2 metrics for an order call.
     */
    private void recordOrderMetrics(String pluginId, String command,
                                    OrderResult result, long durationMs) {
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
    }

    /**
     * Core command entry (traced). Every plugin call flows through here.
     * pluginId is injected by SimplePluginContext — plugins never pass their own ID.
     */
    private OrderResult order(String pluginId, String command, Object... args) {
        if (command == null) return OrderResult.fail("NULL_COMMAND");

        try {
            return dispatch0(pluginId, command, args);
        } catch (Exception e) {
            logger.error("order('%s', '%s') threw: %s", pluginId, command, e.toString());
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
        // Tick health sampling (TickAPI ring buffer)
        if (mcPort != null) mcPort.fireTickEnd();
        // Sandbox rollback journal aging
        autoRollback.age();

        // P15: Monitor hooks
        if (perfMonitor != null) perfMonitor.sample();
        if (leakDetector != null) leakDetector.onTick(tickCounter);
        if (autoSave != null) autoSave.tick();
        if (crashGuard != null) crashGuard.setCurrentTick(tickCounter);

        // P16: BanHammer auto-review (every 20 ticks)
        if (banHammer != null && tickCounter % 20 == 0) {
            banHammer.autoReview();
        }

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

        // P15: Emergency save before shutdown
        if (autoSave != null) autoSave.forceSaveAll();

        // P16: Persist bans
        if (banHammer != null) banHammer.saveBans();

        // Stop network health check
        if (network != null) network.shutdown();

        freezeL1();
        for (L2Journal j : l2Journals.values()) {
            try { j.close(); } catch (IOException ignored) {}
        }
        l2Journals.clear();
        // Shutdown scheduler threads
        if (scheduler != null) scheduler.shutdown();
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

    private OrderResult dispatch0(String pluginId, String command, Object... args) {
        TrustLevel trust = resolveTrust(pluginId);

        // ── P16: BLACKLISTED → immediate reject ─────────────
        if (trust == TrustLevel.BLACKLISTED) {
            auditLogger.log(TrustLevel.BLACKLISTED, pluginId, "BLOCKED",
                    "BLACKLISTED plugin attempted: " + command);
            return OrderResult.fail("FORBIDDEN:BLACKLISTED plugin '" + pluginId + "' is banned");
        }

        // ── event:* ──────────────────────────────────────────
        if (command.startsWith("event:")) {
            return dispatchEvent(pluginId, trust, command, args);
        }

        // ── service:* ────────────────────────────────────────
        if (command.startsWith("service:")) {
            return dispatchService(pluginId, trust, command, args);
        }

        // ── config:* ────────────────────────────────────────
        if (command.startsWith("config:")) {
            return dispatchConfig(pluginId, trust, command, args);
        }

        // ── pdc:* ───────────────────────────────────────────
        if (command.startsWith("pdc:")) {
            return dispatchPdc(pluginId, trust, command, args);
        }

        // ── scheduler:* ─────────────────────────────────────
        if (command.startsWith("scheduler:")) {
            String sAction = command.substring("scheduler:".length());
            // Scheduler dispatch still uses args[0] as pluginId internally;
            // prepend it so plugin callers don't pass their own ID.
            Object[] schedArgs = new Object[args.length + 1];
            schedArgs[0] = pluginId;
            System.arraycopy(args, 0, schedArgs, 1, args.length);
            return scheduler.dispatch(sAction, schedArgs, trust);
        }

        // ── network:* ──────────────────────────────────────
        if (command.startsWith("network:")) {
            return dispatchNetwork(pluginId, trust, command, args);
        }

        // ── audit:* ──────────────────────────────────────
        if (command.startsWith("audit:")) {
            return dispatchAudit(pluginId, trust, command, args);
        }

        // ── memory:* ──────────────────────────────────────
        if (command.startsWith("memory:")) {
            return dispatchMemory(pluginId, trust, command, args);
        }

        // ── mcapi:* ────────────────────────────────────────
        if (command.startsWith("mcapi:")) {
            return dispatchMcapi(pluginId, trust, command, args);
        }

        // ── sandbox:* ──────────────────────────────────────
        if (command.startsWith("sandbox:")) {
            return dispatchSandbox(pluginId, trust, command, args);
        }

        // ── resource:* ───────────────────────────────────────
        if (command.startsWith("resource:")) {
            return dispatchResource(pluginId, trust, command, args);
        }

        // ── P15: monitor:* ──────────────────────────────────
        if (command.startsWith("monitor:")) {
            return dispatchMonitor(pluginId, trust, command, args);
        }

        // ── P16: security:* ─────────────────────────────────
        if (command.startsWith("security:")) {
            return dispatchSecurity(pluginId, trust, command, args);
        }

        logger.debug("order('%s', '%s'): unknown subsystem", pluginId, command);
        return OrderResult.fail("KERNEL_NO_SUBSYSTEMS: Unknown command prefix '" + command + "'");
    }

    // ── Subsystem dispatchers ────────────────────────────────

    private OrderResult dispatchEvent(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("event:".length());
        return switch (action) {
            case "register" -> {
                if (args.length < 1) yield OrderResult.fail("event:register requires (listener)");
                Object listener = args[0];
                eventBus.register(listener, pluginId, trust);
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

    private OrderResult dispatchService(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("service:".length());
        return switch (action) {
            case "register" -> {
                if (args.length < 2)
                    yield OrderResult.fail("service:register requires (apiClass, impl)");
                @SuppressWarnings("unchecked")
                Class<Object> api = (Class<Object>) args[0];
                Object impl = args[1];
                boolean ok = serviceRegistry.register(api, impl, pluginId, trust);
                if (!ok && trust == TrustLevel.S) {
                    auditLogger.log(TrustLevel.S, pluginId, "SERVICE_BLOCK",
                            "S-level plugin blocked from registering: " + api.getName());
                }
                yield ok ? OrderResult.success() : OrderResult.fail("Service registration blocked");
            }
            case "get" -> {
                if (args.length < 1) yield OrderResult.fail("service:get requires (apiClass)");
                Object svc = serviceRegistry.get((Class<?>) args[0], pluginId, trust);
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

    private OrderResult dispatchConfig(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("config:".length());
        return switch (action) {
            case "load" -> {
                if (args.length < 1) yield OrderResult.fail("config:load requires (filename)");
                String fname = (String) args[0];
                File configDir = resourceManager.resolve(pluginId, "");
                Map<String, Object> result = configManager.loadAsMap(configDir, fname);
                yield OrderResult.success(result);
            }
            case "save" -> {
                if (args.length < 2) yield OrderResult.fail("config:save requires (filename, data)");
                String fname = (String) args[0];
                File configDir = resourceManager.resolve(pluginId, "");
                boolean ok = configManager.save(configDir, fname, args[1]);
                yield ok ? OrderResult.success() : OrderResult.fail("Failed to save config");
            }
            case "reload" -> {
                if (args.length < 1) yield OrderResult.fail("config:reload requires (filename)");
                String fname = (String) args[0];
                File configDir = resourceManager.resolve(pluginId, "");
                Map<String, Object> result = configManager.reload(configDir, fname, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                yield OrderResult.success(result);
            }
            default -> OrderResult.fail("Unknown config action: " + action);
        };
    }

    private OrderResult dispatchPdc(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("pdc:".length());
        return switch (action) {
            case "save" -> {
                if (args.length < 2) yield OrderResult.fail("pdc:save requires (key, compoundTag)");
                String key = (String) args[0];
                if (!(args[1] instanceof net.minecraft.nbt.CompoundTag tag))
                    yield OrderResult.fail("pdc:save arg[1] must be CompoundTag");
                File dataDir = resourceManager.resolve(pluginId, "data");
                boolean ok = pdcBackend.save(dataDir, key, tag);
                yield ok ? OrderResult.success() : OrderResult.fail("Failed to save PDC");
            }
            case "load" -> {
                if (args.length < 1) yield OrderResult.fail("pdc:load requires (key)");
                String key = (String) args[0];
                File dataDir = resourceManager.resolve(pluginId, "data");
                net.minecraft.nbt.CompoundTag tag = pdcBackend.load(dataDir, key);
                yield OrderResult.success(tag);
            }
            case "delete" -> {
                if (args.length < 1) yield OrderResult.fail("pdc:delete requires (key)");
                String key = (String) args[0];
                File dataDir = resourceManager.resolve(pluginId, "data");
                boolean ok = pdcBackend.delete(dataDir, key);
                yield ok ? OrderResult.success() : OrderResult.fail("Key not found");
            }
            default -> OrderResult.fail("Unknown pdc action: " + action);
        };
    }

    private OrderResult dispatchResource(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("resource:".length());

        return switch (action) {
            case "file" -> {
                if (args.length < 1) yield OrderResult.fail("resource:file requires (path)");
                String vpath = (String) args[0];
                try {
                    File f = resourceManager.resolve(pluginId, vpath);
                    yield OrderResult.success(f);
                } catch (SecurityException e) {
                    yield OrderResult.fail(e.getMessage());
                }
            }
            case "dir" -> {
                if (resourceManager.getSandbox(pluginId) == null)
                    yield OrderResult.fail("Plugin '" + pluginId + "' not registered");
                yield OrderResult.success(resourceManager.getSandbox(pluginId).getRoot().toFile());
            }
            default ->
                OrderResult.fail("Unknown resource action: " + action);
        };
    }

    private OrderResult dispatchNetwork(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("network:".length());
        // Append trust + pluginId at tail; ZCSNetwork extracts by position-from-end
        Object[] netArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, netArgs, 0, args.length);
        netArgs[args.length] = trust;
        netArgs[args.length + 1] = pluginId;
        return network.order(action, netArgs);
    }

    // ── Phase 10: Audit dispatch ─────────────────────────────

    private OrderResult dispatchAudit(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("audit:".length());
        return switch (action) {
            case "log" -> {
                if (args.length < 2)
                    yield OrderResult.fail("audit:log requires (category, detail)");
                String cat = (String) args[0];
                String detail = (String) args[1];
                auditLogger.log(trust, pluginId, cat, detail);
                yield OrderResult.success();
            }
            case "cross" -> {
                if (args.length < 3)
                    yield OrderResult.fail("audit:cross requires (callee, category, detail)");
                TrustLevel calleeT = resolveTrust((String) args[0]);
                auditLogger.logTrusted(pluginId, (String) args[1], (String) args[2], trust, calleeT);
                yield OrderResult.success();
            }
            default -> OrderResult.fail("Unknown audit action: " + action);
        };
    }

    // ── Phase 12: Memory dispatch (L1/L2/L3/L4) ────────────────

    private OrderResult dispatchMemory(String pluginId, TrustLevel trust, String command, Object... args) {
        String action = command.substring("memory:".length());
        return switch (action) {
            case "l4-check" -> {
                if (args.length < 1) yield OrderResult.fail("memory:l4-check requires (methodName)");
                String method = (String) args[0];
                yield OrderResult.success(l4Instinct.match(method));
            }
            case "l3-query" -> {
                if (args.length < 1) yield OrderResult.fail("memory:l3-query requires (methodName)");
                String method = (String) args[0];
                L3Memory l3 = l3Memories.get(pluginId);
                if (l3 == null) yield OrderResult.success(java.util.Collections.emptyList());
                yield OrderResult.success(l3.match(pluginId, method, "*"));
            }
            case "l3-load" -> {
                try {
                    Path l3Dir = zcsRoot.resolve("plugins").resolve(pluginId).resolve("memory/l3");
                    if (!Files.isDirectory(l3Dir)) yield OrderResult.success(null);
                    Path[] files = Files.list(l3Dir)
                            .filter(p -> p.toString().endsWith(".zcsmem"))
                            .toArray(Path[]::new);
                    if (files.length == 0) yield OrderResult.success(null);
                    L3Memory l3 = L3Memory.load(files[0]);
                    l3Memories.put(pluginId, l3);
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
                L2Journal j = l2Journals.get(pluginId);
                yield j != null ? OrderResult.success(j.file().toString())
                        : OrderResult.fail("No L2 journal for " + pluginId);
            }
            case "decide" -> {
                if (args.length < 2) yield OrderResult.fail("memory:decide requires (trust, methodName)");
                TrustLevel decisionTrust = (TrustLevel) args[0];
                String method = (String) args[1];
                L3Memory l3 = l3Memories.get(pluginId);
                QuarantineDecider.Verdict v = quarantineDecider.evaluate(pluginId, decisionTrust, method, l3, null);
                yield OrderResult.success(v);
            }
            default -> OrderResult.fail("Unknown memory action: " + action
                    + " (valid: l1-freeze, l2-stats, l3-query, l3-load, l4-check, decide)");
        };
    }

    // ── M6: McPort initialisation ──────────────────────────

    /**
     * Initialise McPort and CommandAdapter once the MC server is ready.
     * Must be called from {@code ServerStartedEvent}.
     */
    public void initMcPort(net.minecraft.server.MinecraftServer server,
                           com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        this.mcPort = McPort.init(server);
        this.commandAdapter = new CommandAdapter(this);
        commandAdapter.registerRoot(dispatcher);

        // P15: Wire TickAPI + WorldAPI into PerfMonitor + LeakDetector + AutoSave
        this.perfMonitor = new PerfMonitor(mcPort.tick(), logger, auditLogger);
        this.perfMonitor.setWorldAPI(mcPort.world());
        this.leakDetector = new LeakDetector(eventBus, mcPort.tick(), mcPort.world(), logger, auditLogger);
        this.autoSave = new AutoSave(server, perfMonitor, auditLogger, logger);

        // P16: Register permissions to MC
        this.permissionNode.registerToMc(dispatcher);
        for (PluginDescriptor pd : pluginLoader.getAllPlugins()) {
            permissionNode.registerPlugin(pd.getPluginId());
        }

        // P16: Reload bans (block any already-banned plugins)
        banHammer.loadBans();

        logger.info("McPort online — /zcslib commands registered");
        auditLogger.log(TrustLevel.N, "zcslib", "MCPORT_READY",
                "MC API port initialised, commands registered");
    }

    // ── mcapi dispatch ──────────────────────────────────────

    private OrderResult dispatchMcapi(String pluginId, TrustLevel trust,
                                      String command, Object... args) {
        if (mcPort == null) return OrderResult.fail("MCAPI: McPort not yet initialised — wait for server start");
        String action = command.substring("mcapi:".length());
        return switch (action) {
            case "tps" -> {
                var t = mcPort.tick();
                var map = new java.util.LinkedHashMap<String, Object>();
                map.put("tps", t.getAverageTPS());
                map.put("mspt", t.getMSPT());
                map.put("p95mspt", t.getPercentile95MSPT());
                map.put("health", t.getTickHealth().name());
                map.put("skipped", t.getSkippedTickCount());
                map.put("tick", t.getServerTickCount());
                yield OrderResult.success(map);
            }
            case "players" -> {
                var list = mcPort.players().getOnlinePlayers();
                yield OrderResult.success(list);
            }
            case "world" -> {
                var w = mcPort.world();
                var map = new java.util.LinkedHashMap<String, Object>();
                map.put("chunks", w.getLoadedChunkCount());
                map.put("gametime", w.getGameTime());
                map.put("daytime", w.getDayTime());
                map.put("raining", w.isRaining());
                map.put("thundering", w.isThundering());
                map.put("dimension", w.getDimensionKey());
                map.put("seed", w.getSeed());
                yield OrderResult.success(map);
            }
            case "block" -> {
                if (args.length < 3)
                    yield OrderResult.fail("mcapi:block requires (x, y, z)");
                int x = ((Number) args[0]).intValue();
                int y = ((Number) args[1]).intValue();
                int z = ((Number) args[2]).intValue();
                var bs = mcPort.blocks().getBlock(new net.minecraft.core.BlockPos(x, y, z));
                yield OrderResult.success(bs);
            }
            case "entities" -> {
                if (args.length < 6)
                    yield OrderResult.fail("mcapi:entities requires (x1,y1,z1,x2,y2,z2)");
                double x1 = ((Number) args[0]).doubleValue();
                double y1 = ((Number) args[1]).doubleValue();
                double z1 = ((Number) args[2]).doubleValue();
                double x2 = ((Number) args[3]).doubleValue();
                double y2 = ((Number) args[4]).doubleValue();
                double z2 = ((Number) args[5]).doubleValue();
                var list = mcPort.world().getEntitiesInAABB(x1, y1, z1, x2, y2, z2);
                yield OrderResult.success(list);
            }
            default -> OrderResult.fail("Unknown mcapi action: " + action
                    + " (valid: tps, players, world, block, entities)");
        };
    }

    // ── sandbox dispatch ────────────────────────────────────

    private OrderResult dispatchSandbox(String pluginId, TrustLevel trust,
                                        String command, Object... args) {
        String action = command.substring("sandbox:".length());
        return switch (action) {
            case "snapshot" -> {
                if (args.length < 6)
                    yield OrderResult.fail("sandbox:snapshot requires (x1,y1,z1,x2,y2,z2)");
                int x1 = ((Number) args[0]).intValue();
                int y1 = ((Number) args[1]).intValue();
                int z1 = ((Number) args[2]).intValue();
                int x2 = ((Number) args[3]).intValue();
                int y2 = ((Number) args[4]).intValue();
                int z2 = ((Number) args[5]).intValue();
                var level = mcPort.world().getServerLevel();
                if (level == null) yield OrderResult.fail("sandbox:snapshot: server not ready");
                VirtualSave vs;
                try {
                    vs = VirtualSave.capture(level,
                            new net.minecraft.core.BlockPos(x1, y1, z1),
                            new net.minecraft.core.BlockPos(x2, y2, z2));
                } catch (SecurityException e) {
                    yield OrderResult.fail("SANDBOX: " + e.getMessage());
                }
                yield OrderResult.success(vs);
            }
            case "snapshot-radius" -> {
                if (args.length < 4)
                    yield OrderResult.fail("sandbox:snapshot-radius requires (cx,cy,cz,radius)");
                int cx = ((Number) args[0]).intValue();
                int cy = ((Number) args[1]).intValue();
                int cz = ((Number) args[2]).intValue();
                int r  = ((Number) args[3]).intValue();
                var level = mcPort.world().getServerLevel();
                if (level == null) yield OrderResult.fail("sandbox:snapshot-radius: server not ready");
                VirtualSave vs;
                try {
                    vs = VirtualSave.capture(level,
                            new net.minecraft.core.BlockPos(cx, cy, cz), r);
                } catch (SecurityException e) {
                    yield OrderResult.fail("SANDBOX: " + e.getMessage());
                }
                yield OrderResult.success(vs);
            }
            case "dryrun" -> {
                if (args.length < 6)
                    yield OrderResult.fail("sandbox:dryrun requires (x1,y1,z1,x2,y2,z2, runnable)");
                // args[0..5] = coords, args[6] = Runnable describing what to try
                // For now return a placeholder — real integration requires
                // the caller to pass an action descriptor the kernel can interpret.
                yield OrderResult.fail("sandbox:dryrun: use DryRunContext.trial() via DreamWorker");
            }
            case "discard" -> {
                if (args.length < 1 || !(args[0] instanceof VirtualSave vs))
                    yield OrderResult.fail("sandbox:discard requires (VirtualSave)");
                vs.discard();
                yield OrderResult.success();
            }
            case "changes" -> {
                if (args.length < 1 || !(args[0] instanceof VirtualSave vs))
                    yield OrderResult.fail("sandbox:changes requires (VirtualSave)");
                yield OrderResult.success(vs.getChanges());
            }
            case "dirty" -> {
                if (args.length < 1 || !(args[0] instanceof VirtualSave vs))
                    yield OrderResult.fail("sandbox:dirty requires (VirtualSave)");
                yield OrderResult.success(vs.dirtyBlockCount());
            }
            case "rollback" -> {
                if (args.length < 1) yield OrderResult.fail("sandbox:rollback requires (pluginId)");
                String targetId = (String) args[0];
                var level = mcPort.world().getServerLevel();
                if (level == null) yield OrderResult.fail("sandbox:rollback: server not ready");
                int count = autoRollback.rollbackAll(targetId, level);
                yield OrderResult.success(count);
            }
            case "rollback-pending" -> {
                yield OrderResult.success(autoRollback.pendingCount());
            }
            default -> OrderResult.fail("Unknown sandbox action: " + action
                    + " (valid: snapshot, snapshot-radius, dryrun, discard, changes, dirty, rollback, rollback-pending)");
        };
    }

    // ── P15: monitor dispatch ───────────────────────────────

    private OrderResult dispatchMonitor(String pluginId, TrustLevel trust,
                                        String command, Object... args) {
        // monitor:* 仅内核内部使用，插件不应能调用
        String action = command.substring("monitor:".length());
        return switch (action) {
            case "snapshot" -> {
                if (perfMonitor == null) yield OrderResult.fail("PerfMonitor not initialised");
                yield OrderResult.success(perfMonitor.snapshot("manual"));
            }
            case "leak-scan" -> {
                if (leakDetector == null) yield OrderResult.fail("LeakDetector not initialised");
                yield OrderResult.success(leakDetector.fullScan());
            }
            case "perf-stats" -> {
                if (perfMonitor == null) yield OrderResult.success(java.util.Collections.emptyMap());
                yield OrderResult.success(perfMonitor.getAllPluginPerf());
            }
            default -> OrderResult.fail("Unknown monitor action: " + action);
        };
    }

    // ── P16: security dispatch ──────────────────────────────

    private OrderResult dispatchSecurity(String pluginId, TrustLevel trust,
                                         String command, Object... args) {
        String action = command.substring("security:".length());
        return switch (action) {
            case "cmd-register" -> {
                if (args.length < 2)
                    yield OrderResult.fail("security:cmd-register requires (cmdName, handler)");
                if (commandWhitelist == null)
                    yield OrderResult.fail("CommandWhitelist not initialised");
                // P16 信任门控：A/S/BLACKLISTED/UNKNOWN 级别插件不允许注册命令
                if (!commandWhitelist.isCommandAllowed(pluginId, trust)) {
                    auditLogger.log(trust, pluginId, "CMD_REGISTER_BLOCKED",
                            "Trust level " + trust + " not allowed to register commands");
                    yield OrderResult.fail("FORBIDDEN: Trust level " + trust
                            + " not allowed to register commands");
                }
                commandWhitelist.registerCommand(pluginId, (String) args[0],
                        (CommandWhitelist.CommandHandler) args[1]);
                yield OrderResult.success();
            }
            case "cmd-unregister" -> {
                if (args.length < 1)
                    yield OrderResult.fail("security:cmd-unregister requires (cmdName)");
                if (commandWhitelist == null)
                    yield OrderResult.fail("CommandWhitelist not initialised");
                commandWhitelist.unregisterCommand(pluginId, (String) args[0]);
                yield OrderResult.success();
            }
            case "ban-list" -> {
                if (banHammer == null) yield OrderResult.success(java.util.Collections.emptySet());
                yield OrderResult.success(banHammer.getBannedPlugins());
            }
            case "ban-score" -> {
                if (banHammer == null) yield OrderResult.success(0);
                if (args.length < 1)
                    yield OrderResult.success(banHammer.getBehaviorScore(pluginId));
                yield OrderResult.success(banHammer.getBehaviorScore((String) args[0]));
            }
            default -> OrderResult.fail("Unknown security action: " + action
                    + " (valid: cmd-register, cmd-unregister, ban-list, ban-score)");
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
    public McPort getMcPort()                             { return mcPort; }
    public CommandAdapter getCommandAdapter()             { return commandAdapter; }
    public PluginDescriptor getPlugin(String pluginId)  { return pluginLoader.getPlugin(pluginId); }
    public Collection<PluginDescriptor> getAllPlugins() { return pluginLoader.getAllPlugins(); }
    public int getPluginCount()                         { return pluginLoader.getPluginCount(); }

    // ── P15: Monitor accessors ─────────────────────────────
    public PerfMonitor getPerfMonitor()         { return perfMonitor; }
    public LagGuard getLagGuard()               { return lagGuard; }
    public LeakDetector getLeakDetector()       { return leakDetector; }
    public CrashGuard getCrashGuard()           { return crashGuard; }
    public AutoSave getAutoSave()               { return autoSave; }

    // ── P16: Security accessors ────────────────────────────
    public PermissionNode getPermissionNode()         { return permissionNode; }
    public CommandWhitelist getCommandWhitelist()     { return commandWhitelist; }
    public NetworkAudit getNetworkAudit()             { return networkAudit; }
    public BanHammer getBanHammer()                   { return banHammer; }
}
