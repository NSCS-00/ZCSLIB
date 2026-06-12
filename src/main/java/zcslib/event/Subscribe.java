package zcslib.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler for the ZCSLIB event bus.
 *
 * <p>Handler methods must accept exactly one parameter — the event object.
 * Method visibility does not matter; the bus uses {@code setAccessible(true)}.
 *
 * <pre>{@code
 * @Subscribe
 * void onPlayerJoin(PlayerJoinEvent event) { }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Subscribe {
    /** Priority of this handler. */
    EventPriority priority() default EventPriority.NORMAL;

    /** If true, cancelable events that are already canceled will not invoke this handler. */
    boolean ignoreCancelled() default false;
}
