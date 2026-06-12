package zcslib.event;

/**
 * Interface for events that can be cancelled by a handler.
 *
 * <p>When a handler cancels a Cancelable event, subsequent handlers
 * with {@code ignoreCancelled=true} are skipped.
 */
public interface Cancelable {
    boolean isCanceled();
    void setCanceled(boolean canceled);
}
