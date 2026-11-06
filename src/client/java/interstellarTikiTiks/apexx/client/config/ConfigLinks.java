package interstellarTikiTiks.apexx.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which local configs were created from (or last uploaded to) which cloud config.
 *
 * <p>Local and cloud configs are separate things: downloading a cloud config writes an independent local file and
 * nothing is synchronised afterwards. This index only remembers the origin so that
 * <ul>
 *   <li>uploading a local config that came from the cloud updates that same cloud entry instead of creating a duplicate,</li>
 *   <li>downloading the same cloud config again refreshes the local copy when the user never edited it, and makes a
 *       new numbered copy when they did,</li>
 *   <li>the menu can show a Cloud badge next to the local profile.</li>
 * </ul>
 * Stored as {@code ~/.apexx/config_links.json}, outside the configs directory so it never lists as a profile.
 */
public final class ConfigLinks {
    /** One local config's origin. {@code hash} is the content hash at download/upload time, used to detect edits. */
    public record Link(String cloudId, String cloudName, int hash) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private ConfigLinks() {
    }

    private static Path file() {
        return ConfigManager.resolveSecureBaseDir().resolve("config_links.json");
    }

    /** Stable content hash of a config's modules/HUD, ignoring {@code meta} (its stamp changes on every save). */
    public static int contentHash(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject().deepCopy();
            root.remove(ConfigFormat.META);
            return root.hashCode();
        } catch (RuntimeException malformed) {
            return json == null ? 0 : json.hashCode();
        }
    }

    private static String key(String localName) {
        return localName == null ? "" : localName.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Link> read() {
        Map<String, Link> out = new LinkedHashMap<>();
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return out;
            JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return out;
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                String id = o.has("cloudId") ? o.get("cloudId").getAsString() : "";
                if (id.isBlank()) continue;
                out.put(e.getKey(), new Link(id, o.has("cloudName") ? o.get("cloudName").getAsString() : "",
                        o.has("hash") ? o.get("hash").getAsInt() : 0));
            }
        } catch (Exception unreadable) {
            // A damaged index only loses the origin hint; the configs themselves are untouched.
        }
        return out;
    }

    private static void write(Map<String, Link> links) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Link> e : links.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("cloudId", e.getValue().cloudId());
                o.addProperty("cloudName", e.getValue().cloudName());
                o.addProperty("hash", e.getValue().hash());
                root.add(e.getKey(), o);
            }
            Path f = file();
            Files.createDirectories(f.getParent());
            ConfigManager.writeAtomically(f, GSON.toJson(root));
        } catch (Exception failure) {
            // Same as read(): the index is a convenience, never a reason to fail a save or download.
        }
    }

    public static Link get(String localName) {
        synchronized (LOCK) {
            return read().get(key(localName));
        }
    }

    /** The local config linked to this cloud id, or null. */
    public static String localFor(String cloudId) {
        if (cloudId == null || cloudId.isBlank()) return null;
        synchronized (LOCK) {
            for (Map.Entry<String, Link> e : read().entrySet()) {
                if (cloudId.equals(e.getValue().cloudId())) return e.getKey();
            }
        }
        return null;
    }

    public static void put(String localName, Link link) {
        synchronized (LOCK) {
            Map<String, Link> links = read();
            links.put(key(localName), link);
            write(links);
        }
    }

    public static void remove(String localName) {
        synchronized (LOCK) {
            Map<String, Link> links = read();
            if (links.remove(key(localName)) != null) write(links);
        }
    }

    public static void rename(String oldName, String newName) {
        synchronized (LOCK) {
            Map<String, Link> links = read();
            Link link = links.remove(key(oldName));
            if (link != null) {
                links.put(key(newName), link);
                write(links);
            }
        }
    }
}
