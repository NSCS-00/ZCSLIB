package zcslib.event;

import zcslib.api.TrustLevel;
import zcslib.log.ZCSLogger;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight plugin event bus per ZCSLIB 第十章.
 *
 * <p>Wires plugin-internal events between trust boundaries. Unlike NeoForge's
 * global bus, this bus is scoped to ZCSLIB-loaded plugins and enforces
 * trust-level gating on system events.
 *
 * <p>Thread model: synchronous dispatch on the calling thread. Async dispatch
 * is planned for a later iteration (via {@code scheduler:compute}).
 *
 * <h3>Usage via kernel.order()</h3>
 * <pre>{@code
 * kernel.order("event:register", myListener);       // scan @Subscribe
 * kernel.order("event:post", new MyEvent(data));    // dispatch
 * kernel.order("event:unregister", myListener);     // remove
 * }</pre>
 */
public class ZCSLEventBus {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Event type → sorted list of (listener, method) pairs. */
    private final Map<Class<?>, List<HandlerEntry>> handlers = new ConcurrentHashMap<>();

    /** Reverse index: listener → its registered event types (for unregister). */
    private final Map<Object, Set<Class<?>>> listenerToEvents = new ConcurrentHashMap<>();

    /** System event class names that S-level listeners are forbidden from receiving. */
    private static final Set<String> SYSTEM_EVENT_PACKAGES = Set.of(
            "net.neoforged.fml.",
            "net.neoforged.neoforge.event." + "server.",
            "zcslib.kernel.",
            "zcslib.event.ZCSLEventBus."
    );

    /** Player event class name prefixes — S-level may subscribe but audited. */
    private static final Set<String> PLAYER_EVENT_PACKAGES = Set.of(
            "net.neoforged.neoforge.event.entity.player.",
            "net.neoforged.neoforge.event.entity.living."
    );

    private final ZCSLogger logger;

    public ZCSLEventBus(ZCSLogger logger) {
        this.logger = logger;
    }

    // ── register ────────────────────────────────────────────

    /**
     * Scan an object for @Subscribe methods and register them.
     *
     * @param listener  object whose @Subscribe methods become handlers
     * @param ownerId   plugin identifier (for audit)
     * @param trust     trust level of the owning plugin
     */
    public void register(Object listener, String ownerId, TrustLevel trust) {
        Class<?> clazz = listener.getClass();
        int count = 0;

        for (Method method : clazz.getDeclaredMethods()) {
            Subscribe ann = method.getAnnotation(Subscribe.class);
            if (ann == null) continue;

            // Validate: exactly one parameter (the event)
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                logger.warn("EVENT: @Subscribe method %s.%s has %d params — expected 1. Skipped.",
                        clazz.getSimpleName(), method.getName(), params.length);
                continue;
            }

            Class<?> eventType = params[0];

            // Trust gating
            if (trust == TrustLevel.S && isSystemEvent(eventType)) {
                logger.warn("FORBIDDEN:S event — %s cannot subscribe to system event %s",
                        ownerId, eventType.getSimpleName());
                continue;
            }

            method.setAccessible(true);
            HandlerEntry entry = new HandlerEntry(listener, method, ann.priority(), ann.ignoreCancelled(), trust, ownerId);

            handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                    .add(entry);

            listenerToEvents.computeIfAbsent(listener, k -> ConcurrentHashMap.newKeySet())
                    .add(eventType);

            count++;
        }

        if (count > 0) {
            logger.info("EVENT: [%s] %s registered %d handler(s)", trust.name(), ownerId, count);
        }
    }

    // ── post ────────────────────────────────────────────────

    /**
     * Post an event to all registered handlers for its type.
     *
     * <p>Handlers are invoked in priority order (LOWEST → MONITOR).
     * If the event implements {@link Cancelable} and is canceled,
     * handlers with {@code ignoreCancelled=true} are skipped.
     *
     * @param event the event object to dispatch
     * @return the event (for chaining)
     */
    public <T> T post(T event) {
        Class<?> eventType = event.getClass();
        List<HandlerEntry> entries = handlers.get(eventType);
        if (entries == null || entries.isEmpty()) return event;

        boolean isCancelable = event instanceof Cancelable;
        boolean cancelled = isCancelable && ((Cancelable) event).isCanceled();

        // Sort by priority (stable: CopyOnWriteArrayList preserves insertion order
        // within same priority, but we re-sort each post since new handlers can arrive)
        List<HandlerEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> e.priority.ordinal()));

        boolean isPlayerEvent = isPlayerEvent(eventType);

        for (HandlerEntry entry : sorted) {
            if (cancelled && entry.ignoreCancelled) continue;

            // Audit: S-level listeners handling player events
            if (entry.trust == TrustLevel.S && isPlayerEvent) {
                logger.warn("AUDIT: [S] plugin '{}' handled player event {} via {}",
                        entry.ownerId, eventType.getSimpleName(), entry.method.getName());
            }

            try {
                entry.method.invoke(entry.listener, event);
            } catch (InvocationTargetException e) {
                logger.error("EVENT: Handler %s threw: %s",
                        entry.method.getName(), e.getCause().getMessage());
            } catch (IllegalAccessException e) {
                logger.error("EVENT: Handler %s inaccessible: %s",
                        entry.method.getName(), e.getMessage());
            }

            // Re-check cancel state after each handler
            if (isCancelable) cancelled = ((Cancelable) event).isCanceled();
        }

        return event;
    }

    // ── unregister ──────────────────────────────────────────

    /**
     * Remove all handlers registered by the given listener.
     *
     * @return number of handler entries removed
     */
    public int unregister(Object listener) {
        Set<Class<?>> eventTypes = listenerToEvents.remove(listener);
        if (eventTypes == null) return 0;

        int removed = 0;
        for (Class<?> eventType : eventTypes) {
            List<HandlerEntry> entries = handlers.get(eventType);
            if (entries != null) {
                int before = entries.size();
                entries.removeIf(e -> e.listener == listener);
                removed += before - entries.size();
                if (entries.isEmpty()) handlers.remove(eventType);
            }
        }

        if (removed > 0) {
            logger.info("EVENT: Unregistered %d handler(s) for %s",
                    removed, listener.getClass().getSimpleName());
        }
        return removed;
    }

    /**
     * Remove all handlers for a specific plugin.
     *
     * @return number of handler entries removed
     */
    public int unregisterAll(String ownerId) {
        int removed = 0;
        for (Map.Entry<Object, Set<Class<?>>> entry : listenerToEvents.entrySet()) {
            Object listener = entry.getKey();
            removed += unregister(listener);
        }
        return removed;
    }

    // ── query ───────────────────────────────────────────────

    public int getHandlerCount() {
        return handlers.values().stream().mapToInt(List::size).sum();
    }

    public int getListenerCount() {
        return listenerToEvents.size();
    }

    // ── internals ───────────────────────────────────────────

    private boolean isSystemEvent(Class<?> eventType) {
        String name = eventType.getName();
        for (String prefix : SYSTEM_EVENT_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean isPlayerEvent(Class<?> eventType) {
        String name = eventType.getName();
        for (String prefix : PLAYER_EVENT_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    // ── handler entry ───────────────────────────────────────

    private record HandlerEntry(
            Object listener,
            Method method,
            EventPriority priority,
            boolean ignoreCancelled,
            TrustLevel trust,
            String ownerId) {}
}
