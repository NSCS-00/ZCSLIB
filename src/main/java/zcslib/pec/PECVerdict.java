package zcslib.pec;

/**
 * Result of PEC environment validation per ZCSLIB 3.2.
 */
public enum PECVerdict {
    /** All checks passed. Normal lifecycle. */
    PASS,

    /** Load but inject failed-feature list; plugin self-degrades. */
    SOFT_FAIL,

    /** Disable plugin, log reason. */
    HARD_FAIL
}
