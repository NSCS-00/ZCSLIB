package zcslib.api;

/**
 * Plugin trust classification per ZCSLIB 第四章.
 *
 * <p>Determines API visibility, thread permissions, and audit intensity.
 */
public enum TrustLevel {
    /** Native Plugin — PEC present, full API access, high-performance scheduling. */
    N("Native"),

    /** Recognized Component — no PEC, but friendly package or API reference. */
    R("Recognized"),

    /** Auto-Adapt Mod — auto-adapt marker present, virtual PEC generated. */
    A("Auto-Adapt"),

    /** Suspicious Mod — no PEC, no friendly traits, sandboxed with heavy restrictions. */
    S("Suspicious");

    private final String label;

    TrustLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
