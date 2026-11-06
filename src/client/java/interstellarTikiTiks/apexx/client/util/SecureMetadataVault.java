package interstellarTikiTiks.apexx.client.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SecureMetadataVault {
    private static final Object LOCK = new Object();
    private static final String RESOURCE_PATH = "/META-INF/apexx/secure-meta.bin";
    private static final int BASE_KEY = 0x5E2A7C31;
    private static final int SALT = 0x13D94F8B;

    private static volatile Map<String, String> cache;

    private SecureMetadataVault() {
    }

    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String value = snapshot().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static void prefetch() {
        snapshot();
    }

    private static Map<String, String> snapshot() {
        Map<String, String> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            local = cache;
            if (local == null) {
                cache = local = load();
            }
        }
        return local;
    }

    private static Map<String, String> load() {
        try (InputStream stream = SecureMetadataVault.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return Map.of();
            }
            byte[] encrypted = stream.readAllBytes();
            byte[] decoded = crypt(encrypted);
            return parse(decoded);
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static Map<String, String> parse(byte[] decoded) {
        if (decoded == null || decoded.length == 0) {
            return Map.of();
        }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return Collections.unmodifiableMap(values);
    }

    private static byte[] crypt(byte[] input) {
        byte[] out = new byte[input.length];
        int state = mix(BASE_KEY ^ input.length, SALT ^ 0x6A09E667);
        for (int i = 0; i < input.length; i++) {
            state = nextState(state ^ i ^ Integer.rotateLeft(SALT, i & 15));
            int stream = state ^ Integer.rotateLeft(BASE_KEY, i & 31) ^ (i * 0x45D9F3B);
            out[i] = (byte) (input[i] ^ stream);
        }
        return out;
    }

    private static int mix(int left, int right) {
        int mixed = left ^ Integer.rotateLeft(right, 13) ^ 0x9E3779B9;
        return nextState(mixed);
    }

    private static int nextState(int value) {
        int x = value;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return x;
    }
}
