package zcslib.service;

import zcslib.api.TrustLevel;
import zcslib.log.ZCSLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Plugin service registry per ZCSLIB 第九章.
 *
 * <p>A minimal service-locator pattern for inter-plugin dependency injection.
 * Plugins register implementations via {@code kernel.order("service:register", ...)}
 * and resolve them via {@code kernel.order("service:get", ...)}.
 *
 * <h3>Trust gating</h3>
 * S-level plugins are blocked from registering implementations for
 * interfaces whose name contains any core-security keyword.
 *
 * <h3>Usage via kernel.order()</h3>
 * <pre>{@code
 * kernel.order("service:register", MyApi.class, impl);
 * MyApi api = (MyApi) kernel.order("service:get", MyApi.class).data();
 * }</pre>
 */
public class ServiceRegistry {
    /** interface class → wrapper (provider id + impl + trust) */
    private final ConcurrentMap<Class<?>, ServiceEntry> services = new ConcurrentHashMap<>();

    /** Interface names that S-level plugins cannot register. */
    private static final List<String> CORE_KEYWORDS = List.of(
            "Kernel", "Admin", "PlayerData", "NetworkMain"
    );

    private final ZCSLogger logger;

    public ServiceRegistry(ZCSLogger logger) {
        this.logger = logger;
    }

    /**
     * Register a service implementation.
     *
     * @param api   the interface class
     * @param impl  the implementation instance
     * @param providerId  plugin ID that provides this service
     * @param trust       provider's trust level
     * @return true on success, false if registration is blocked by policy
     */
    @SuppressWarnings("unchecked")
    public <T> boolean register(Class<T> api, T impl, String providerId, TrustLevel trust) {
        if (impl == null || api == null) {
            logger.warn("SERVICE: register({}, {}) — null argument rejected", api, providerId);
            return false;
        }

        // S-level: check core keyword ban
        if (trust == TrustLevel.S && isCoreKeyword(api)) {
            logger.warn("FORBIDDEN:S service — %s cannot register core interface %s",
                    providerId, api.getSimpleName());
            return false;
        }

        ServiceEntry previous = services.put(api, new ServiceEntry(
                (Class<Object>) api, impl, providerId, trust, System.currentTimeMillis()));

        if (previous != null) {
            logger.info("SERVICE: %s replaced %s's implementation of %s",
                    providerId, previous.providerId(), api.getSimpleName());
        } else {
            logger.info("SERVICE: [%s] %s registered %s",
                    trust.name(), providerId, api.getSimpleName());
        }
        return true;
    }

    /**
     * Look up a service by interface.
     *
     * @return the implementing instance, or null if not registered
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> api) {
        ServiceEntry entry = services.get(api);
        return entry != null ? (T) entry.impl() : null;
    }

    /**
     * Typed lookup with cross-trust audit logging.
     * Audits caller trust vs provider trust:
     * <ul>
     *   <li>N → S: WARN (high-trust calling low-trust)</li>
     *   <li>S → N: SECURITY (low-trust calling high-trust)</li>
     *   <li>N → N: INFO</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> api, String callerId, TrustLevel callerTrust) {
        ServiceEntry entry = services.get(api);
        if (entry == null) return null;

        // Cross-trust audit
        TrustLevel providerTrust = entry.trust();
        if (callerTrust == TrustLevel.S && providerTrust.ordinal() < TrustLevel.S.ordinal()) {
            logger.warn("AUDIT: [SECURITY] S-level plugin '{}' called {} provided by [{}] '{}'",
                    callerId, api.getSimpleName(), providerTrust.name(), entry.providerId());
        } else if (callerTrust.ordinal() < TrustLevel.S.ordinal() && providerTrust == TrustLevel.S) {
            logger.warn("AUDIT: [WARN] [{}] plugin '{}' called {} provided by S-level '{}'",
                    callerTrust.name(), callerId, api.getSimpleName(), entry.providerId());
        } else {
            logger.info("AUDIT: [{}→{}] '{}' called {} provided by '{}'",
                    callerTrust.name(), providerTrust.name(),
                    callerId, api.getSimpleName(), entry.providerId());
        }

        return (T) entry.impl();
    }

    /**
     * Look up a service with provider metadata.
     *
     * @return wrapper containing impl + provider info, or null if not registered
     */
    public ServiceEntry getMeta(Class<?> api) {
        return services.get(api);
    }

    /**
     * List all registered service interface names.
     */
    public List<String> list() {
        List<String> names = new ArrayList<>(services.size());
        for (Class<?> api : services.keySet()) {
            names.add(api.getName());
        }
        return names;
    }

    /**
     * Remove all services registered by a specific plugin.
     *
     * @return number of services removed
     */
    public int unregisterAll(String providerId) {
        int removed = 0;
        var it = services.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().providerId().equals(providerId)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger.info("SERVICE: Unregistered %d service(s) from %s", removed, providerId);
        }
        return removed;
    }

    public int getCount() { return services.size(); }

    // ── internal ────────────────────────────────────────────

    private boolean isCoreKeyword(Class<?> api) {
        String name = api.getName().toLowerCase() + " " + api.getSimpleName().toLowerCase();
        for (String kw : CORE_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }
}
