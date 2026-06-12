package zcslib.pec;

import zcslib.api.TrustLevel;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Environment validator for PEC contracts per ZCSLIB 3.2.
 *
 * <p>Checks ZCSLIB version, Minecraft version, loader, Java version,
 * and assigns a trust level based on PEC presence.
 */
public class PECValidator {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final String zcslibVersion;
    private final String mcVersion;
    private final String loaderName;
    private final String loaderVersion;
    private final String javaVersion;

    public PECValidator(String zcslibVersion, String mcVersion, String loaderName,
                         String loaderVersion, String javaVersion) {
        this.zcslibVersion = zcslibVersion;
        this.mcVersion = mcVersion;
        this.loaderName = loaderName;
        this.loaderVersion = loaderVersion;
        this.javaVersion = javaVersion;
    }

    /**
     * Validate a parsed PEC against the current environment.
     *
     * @return {@link PECVerdict#PASS}, {@code SOFT_FAIL}, or {@code HARD_FAIL}
     */
    public PECVerdict validate(PECSchema pec) {
        if (pec == null) return PECVerdict.HARD_FAIL;

        PECSchema.Environment env = pec.environment;
        if (env == null) return PECVerdict.PASS; // no constraints = pass

        // Check ZCSLIB version
        if (env.zcslib != null && env.zcslib.versionRange != null) {
            if (!versionSatisfies(zcslibVersion, env.zcslib.versionRange)) {
                LOGGER.warn("[ZCSLIB] PEC '{}' requires ZCSLIB {}, but running {}",
                        pec.pluginId, env.zcslib.versionRange, zcslibVersion);
                return applyOnFail(pec);
            }
        }

        // Check platform
        if (env.platform != null) {
            PECSchema.Platform p = env.platform;

            if (p.minecraft != null && !versionSatisfies(mcVersion, p.minecraft)) {
                LOGGER.warn("[ZCSLIB] PEC '{}' requires MC {}, but running {}",
                        pec.pluginId, p.minecraft, mcVersion);
                return applyOnFail(pec);
            }
            if (p.loader != null && !p.loader.equalsIgnoreCase(loaderName)) {
                LOGGER.warn("[ZCSLIB] PEC '{}' requires loader {}, but running {}",
                        pec.pluginId, p.loader, loaderName);
                return applyOnFail(pec);
            }
            if (p.loaderVersionRange != null && !versionSatisfies(loaderVersion, p.loaderVersionRange)) {
                LOGGER.warn("[ZCSLIB] PEC '{}' requires loader {}, but running {}",
                        pec.pluginId, p.loaderVersionRange, loaderVersion);
                return applyOnFail(pec);
            }
        }

        return PECVerdict.PASS;
    }

    /**
     * Determine trust level from PEC presence and package heuristics.
     *
     * <p>Per ZCSLIB 4.1:
     * <ul>
     *   <li>PEC present → {@code N} (Native Plugin)
     *   <li>No PEC, but {@code com.dlzstudio} package or {@code zcslib.api} ref → {@code R}
     *   <li>No PEC, but has {@code /META-INF/zcslib/auto-adapt} → {@code A}
     *   <li>Has {@code neoforge.mods.toml} but no friendly traits → {@code S}
     *   <li>No identifiable traits → {@code UNKNOWN} (reject)
     * </ul>
     *
     * @param pec          parsed PEC, or {@code null}
     * @param hasDlzPackage true if JAR contains {@code com.dlzstudio} classes
     * @param hasAutoAdapt true if JAR contains {@code /META-INF/zcslib/auto-adapt}
     * @param hasModsToml  true if JAR contains {@code neoforge.mods.toml}
     * @return assigned trust level, or {@code null} for UNKNOWN (reject)
     */
    /** @param pec parsed PEC, or {@code null} if no PEC found */
    public static TrustLevel classify(PECSchema pec,
                                       boolean hasDlzPackage,
                                       boolean hasAutoAdapt,
                                       boolean hasModsToml) {
        if (pec != null) return TrustLevel.N;
        if (hasDlzPackage) return TrustLevel.R;
        if (hasAutoAdapt) return TrustLevel.A;
        if (hasModsToml) return TrustLevel.S;
        return null; // UNKNOWN → reject
    }

    // ── Helpers ──────────────────────────────────────────────

    private PECVerdict applyOnFail(PECSchema pec) {
        if ("DEGRADE_OR_DISABLE".equals(pec.onEnvironmentNotMet)) {
            return PECVerdict.SOFT_FAIL;
        }
        return PECVerdict.HARD_FAIL;
    }

    /**
     * Minimal semver range check. Supports {@code >=X.Y.Z <A.B.C} and exact matches.
     */
    static boolean versionSatisfies(String actual, String range) {
        if (range == null || range.isEmpty()) return true;

        // Exact match (semantic, not string)
        if (compareVersions(actual, range) == 0) return true;

        // >=X <Y format
        String[] parts = range.split("\\s+");
        if (parts.length == 2) {
            String lower = parts[0];  // >=X.Y.Z
            String upper = parts[1];  // <A.B.C
            return versionGte(actual, lower.replace(">=", "").trim())
                    && versionLt(actual, upper.replace("<", "").trim());
        }

        // Single >= or >= only
        if (parts.length == 1) {
            String single = parts[0];
            if (single.startsWith(">=")) {
                return versionGte(actual, single.replace(">=", "").trim());
            }
            if (single.startsWith(">")) {
                return versionGt(actual, single.replace(">", "").trim());
            }
        }

        return true; // unrecognized format → pass
    }

    private static boolean versionGte(String a, String b) { return compareVersions(a, b) >= 0; }
    private static boolean versionGt(String a, String b)  { return compareVersions(a, b) > 0; }
    private static boolean versionLt(String a, String b)  { return compareVersions(a, b) < 0; }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parsePart(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
