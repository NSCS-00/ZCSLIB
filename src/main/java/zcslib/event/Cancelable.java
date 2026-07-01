package zcslib.event;

import zcslib.api.TrustLevel;

/**
 * Interface for events that can be cancelled by a handler.
 *
 * <p>When a handler cancels a Cancelable event, subsequent handlers
 * with {@code ignoreCancelled=true} are skipped.
 *
 * <p><b>Trust gating:</b> Only kernel (null) and N-level plugins may cancel.
 * R/A/S-level plugins calling {@code setCanceled(true)} will be ignored.
 * Use {@link #setCanceled(boolean, TrustLevel)} for audited cancellation.
 */
public interface Cancelable {
    boolean isCanceled();

    /**
     * Unconditionally cancel. Only callable from kernel-internal code.
     * Plugin code should use {@link #setCanceled(boolean, TrustLevel)}.
     */
    void setCanceled(boolean canceled);

    /**
     * Cancel with trust-level gating.
     *
     * @param canceled true to cancel
     * @param trust caller's trust level (null = kernel)
     * @return true if cancellation was applied, false if denied
     */
    default boolean setCanceled(boolean canceled, TrustLevel trust) {
        if (canceled && trust != null && trust.ordinal() >= TrustLevel.R.ordinal()) {
            // R/A/S-level plugins cannot cancel events
            return false;
        }
        setCanceled(canceled);
        return true;
    }
}
