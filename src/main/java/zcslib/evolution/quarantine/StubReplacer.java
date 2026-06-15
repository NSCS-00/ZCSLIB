// ZCSLIB Evolution - Stub Replacer
// Replace dangerous methods with safe stubs (return 0/null)
// Uses java.lang.reflect.Proxy + method interception
// Pure Java SE (java.base only)
package zcslib.evolution.quarantine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub replacement via dynamic proxy.
 * <p>
 * Wraps an object in a Proxy that intercepts blacklisted methods
 * and returns safe defaults instead of delegating to the real implementation.
 * <p>
 * Downside: only works for interface-based APIs. For concrete classes,
 * a full ASM bytecode transform would be needed (MC-dependent, Phase 12 later).
 */
public class StubReplacer {

    /**
     * A single stub rule: method signature → safe return value.
     */
    public record StubRule(String className, String methodName, Object defaultValue) {}

    private final Set<String> blockedSignatures = ConcurrentHashMap.newKeySet();

    /**
     * Register a stub rule.
     *
     * @param className  fully-qualified class name
     * @param methodName method to stub
     */
    public void block(String className, String methodName) {
        blockedSignatures.add(className + "::" + methodName);
    }

    public void unblock(String className, String methodName) {
        blockedSignatures.remove(className + "::" + methodName);
    }

    public boolean isBlocked(String className, String methodName) {
        return blockedSignatures.contains(className + "::" + methodName);
    }

    public int blockedCount() { return blockedSignatures.size(); }

    /**
     * Wrap an interface implementation with stub interception.
     *
     * @param iface    the interface class
     * @param delegate the real implementation
     * @param blocked  set of blocked method names for this interface
     * @return a proxy that stubs blocked methods
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> iface, T delegate, Set<String> blocked) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                new StubHandler(delegate, blocked)
        );
    }

    private record StubHandler(Object delegate, Set<String> blocked)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (blocked.contains(method.getName())) {
                // Return safe default
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class || rt == long.class || rt == short.class || rt == byte.class) return 0;
                if (rt == float.class) return 0.0f;
                if (rt == double.class) return 0.0;
                if (rt == char.class) return '\0';
                return null; // object types
            }
            return method.invoke(delegate, args);
        }
    }
}
