package zcslib.event;

/**
 * Event handler priority for ZCSLIB event bus dispatch order.
 */
public enum EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    /** Monitor-level handlers are called last and must not modify the event. */
    MONITOR
}
