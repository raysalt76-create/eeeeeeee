package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Starred config profiles shown in the Config Center.
 *
 * <p>Keys are opaque strings chosen by the UI ({@code own:<name>} for the user's profiles, {@code hub:<id>} for
 * community configs). The list is only written to disk while local config saving is enabled, so a cloud-only
 * session leaves nothing under the Apexx data directory; otherwise stars last for the session.
 */
public final class ConfigFavorites {
    private static final ConfigFavorites INSTANCE = new ConfigFavorites();

    private final Set<String> keys = new LinkedHashSet<>();
    private boolean loaded;

    private ConfigFavorites() {
    }

    public static ConfigFavorites getInstance() {
        return INSTANCE;
    }

    public synchronized boolean isFavorite(String key) {
        ensureLoaded();
        return key != null && keys.contains(key);
    }

    public synchronized int count() {
        ensureLoaded();
        return keys.size();
    }

    /** Flips the star and returns the new state. */
    public synchronized boolean toggle(String key) {
        ensureLoaded();
        if (key == null || key.isBlank()) return false;
        boolean now = keys.add(key);
        if (!now) keys.remove(key);
        persist();
        return now;
    }

    private static Path file() {
        return ConfigManager.resolveSecureBaseDir().resolve("favorites.json");
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = file();
            if (!Files.isRegularFile(file)) return;
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) return;
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonPrimitive()) keys.add(element.getAsString());
            }
        } catch (Throwable ignored) {
            // A corrupt favorites file only costs the stars.
        }
    }

    private void persist() {
        if (!ConfigManager.isSaveLocalConfig()) return;
        try {
            JsonArray array = new JsonArray();
            keys.forEach(array::add);
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, array.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // Stars stay in memory for this session.
        }
    }
}
