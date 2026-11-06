package interstellarTikiTiks.apexx.client.resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RuntimeResourceLoader {
    private static final Map<String, byte[]> RESOURCE_CACHE = new HashMap<>();

    private RuntimeResourceLoader() {
    }

    public static synchronized boolean ensureInitialized() {
        // Legacy disk-ZIP resource bridge is retired. Current payload resources belong
        // to ApexxClassLoader; a system property must never reopen an archive on disk.
        return false;
    }

    public static InputStream getResourceAsStream(String path) {
        if (!ensureInitialized()) {
            return null;
        }

        byte[] data = RESOURCE_CACHE.get(path);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    public static byte[] getResource(String path) {
        if (!ensureInitialized()) {
            return null;
        }
        return RESOURCE_CACHE.get(path);
    }

    public static boolean hasResource(String path) {
        if (!ensureInitialized()) {
            return false;
        }
        return RESOURCE_CACHE.containsKey(path);
    }

    public static Set<String> getResourcePaths() {
        if (!ensureInitialized()) {
            return Set.of();
        }
        return new HashSet<>(RESOURCE_CACHE.keySet());
    }

    public static boolean hasNamespace(String namespace) {
        if (!ensureInitialized()) {
            return false;
        }

        String prefix = "assets/" + namespace + "/";
        for (String path : RESOURCE_CACHE.keySet()) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
