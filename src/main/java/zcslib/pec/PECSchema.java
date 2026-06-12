package zcslib.pec;

import java.util.List;

/**
 * Deserialized PEC (Plugin Execution Contract) from {@code PEC.json}.
 *
 * <p>Field names match the JSON schema exactly for Gson binding.
 */
public class PECSchema {

    public String contractSchema;
    public String pluginId;
    public String version;
    public String displayName;
    public List<String> authors;

    public Entrypoint entrypoint;
    public int priority;

    public Environment environment;
    public String onEnvironmentNotMet;
    public String fallbackLabel;

    // ── Nested types ────────────────────────────────────────

    public static class Entrypoint {
        public String mainClass;
    }

    public static class Environment {
        public ZCSLibVersion zcslib;
        public Platform platform;
        public List<Feature> features;
    }

    public static class ZCSLibVersion {
        public String versionRange;
    }

    public static class Platform {
        public String minecraft;
        public String loader;
        public String loaderVersionRange;
        public String javaVersionRange;
    }

    public static class Feature {
        public String type;
        public String modId;
        public String versionRange;
        public String cardinality;
    }
}
