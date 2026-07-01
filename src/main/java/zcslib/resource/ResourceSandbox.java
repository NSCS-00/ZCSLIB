package zcslib.resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Path sandbox — prevents directory traversal and access to sensitive paths.
 *
 * <p>Locks every plugin path under its designated root. Any attempt
 * to escape (e.g. {@code "../../saves"}) is caught and denied.
 */
public class ResourceSandbox {
    private final Path root;
    private static final Set<String> SENSITIVE_DIRS = Set.of(
            "saves", "world", "server.properties", "eula.txt",
            "ops.json", "banned-ips.json", "banned-players.json",
            "whitelist.json", "usercache.json"
    );

    public ResourceSandbox(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Resolve a virtual plugin path to a physical file, enforcing sandbox.
     *
     * @param virtualPath e.g. {@code "/config/server.json"} or {@code "data/cache.bin"}
     * @return validated physical file
     * @throws SecurityException if the path escapes the sandbox or targets a sensitive file
     */
    public File resolve(String virtualPath) throws SecurityException {
        // Strip leading / for safety
        String clean = virtualPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);

        Path resolved = root.resolve(clean).toAbsolutePath().normalize();

        // Must be under root
        if (!resolved.startsWith(root)) {
            throw new SecurityException(
                    "SANDBOX: Path escape denied — '" + virtualPath + "' resolves to '" + resolved + "'");
        }

        // Must not target a sensitive system directory
        for (String sensitive : SENSITIVE_DIRS) {
            if (resolved.toString().contains(File.separator + sensitive)
                    || resolved.getFileName().toString().equals(sensitive)) {
                throw new SecurityException(
                        "SANDBOX: Sensitive path denied — '" + virtualPath + "' touches '" + sensitive + "'");
            }
        }

        return resolved.toFile();
    }

    /**
     * Canonicalize: resolve and verify the file exists on disk.
     */
    public File canonicalize(String virtualPath) throws SecurityException {
        File file = resolve(virtualPath);
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new SecurityException("SANDBOX: Cannot canonicalize path: " + virtualPath, e);
        }
    }

    public Path getRoot() {
        return root;
    }

    /**
     * Normalize a shared resource path — strips leading slashes,
     * prevents directory traversal, returns the safe relative component.
     * Used by {@link ZCSResourceManager#getSharedResource(String)}.
     */
    public static String normalizeShared(String relativePath) {
        String clean = relativePath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        // Reject traversal attempts
        if (clean.contains("..") || clean.startsWith("~")) {
            throw new SecurityException("SANDBOX: Path escape denied — '" + relativePath + "'");
        }
        return clean;
    }
}
