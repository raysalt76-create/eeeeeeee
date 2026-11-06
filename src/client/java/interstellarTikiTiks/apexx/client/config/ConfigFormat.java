package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;

/**
 * The one config file format, shared by local profiles, cloud configs and manual JSON import/export.
 *
 * <p>A config is a plain JSON object keyed by readable names: {@code modules -> <Module> -> settings -> <Setting>},
 * {@code hudElements}, {@code theme}, {@code atmosphere}. On top of that every saved or exported
 * blob carries a {@code meta} object so a config stays self-describing as the client grows:
 *
 * <pre>
 * "meta": { "format": "apexx-config", "schema": 2, "edition": "premium", "client": "1.4.0",
 *           "minecraft": "1.21.11", "name": "My Mace", "savedAt": "2026-10-15T12:00:00Z" }
 * </pre>
 *
 * <h2>Forward compatibility</h2>
 * <ul>
 *   <li>Readers ignore keys and modules they do not know and never fail on them, so an older or Free client opens a
 *       config written by a newer or Premium one and applies the part it understands.</li>
 *   <li>{@link ConfigManager} carries unknown modules and settings through every re-save, so opening a Premium
 *       config in Free and saving it does not erase the Premium-only entries.</li>
 *   <li>{@code meta.schema} is only a hint: a config with a higher schema than {@link #SCHEMA} is still applied.</li>
 *   <li>Free and Premium each keep their own cloud list (the service tags every config with its edition); Premium
 *       can read both, Free reads only its own plus public Free configs.</li>
 * </ul>
 */
public final class ConfigFormat {
    public static final String FORMAT = "apexx-config";
    public static final int SCHEMA = 2;
    public static final String META = "meta";
    /** The edition this client writes. The Free checkout changes this one constant. */
    public static final String EDITION = "premium";
    public static final String MINECRAFT = "1.21.11";

    /** Keys that describe the machine or the account rather than the config; never shared or exported. */
    private static final String[] PRIVATE_KEYS = {"webSyncCode", "webSyncAutoLoad"};

    private ConfigFormat() {
    }

    /** Result of reading text a user pasted or a file they dropped in. */
    public record Parsed(JsonObject config, String name, boolean newerSchema, String edition) {
    }

    /**
     * Accepts a bare config, a config with {@code meta}, or the website/service wrapper
     * {@code {"name": ..., "data": { ...config... }}}. Returns null when the text is not a config at all.
     */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(text.trim());
            if (el == null || !el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            String name = null;
            if (!looksLikeConfig(root)) {
                for (String wrapper : new String[]{"data", "config"}) {
                    JsonElement inner = root.get(wrapper);
                    if (inner != null && inner.isJsonObject() && looksLikeConfig(inner.getAsJsonObject())) {
                        name = text(root, "name");
                        root = inner.getAsJsonObject();
                        break;
                    }
                }
            }
            if (!looksLikeConfig(root)) return null;
            JsonObject config = root.deepCopy();
            JsonObject meta = config.has(META) && config.get(META).isJsonObject() ? config.getAsJsonObject(META) : null;
            if (name == null && meta != null) name = text(meta, "name");
            int schema = meta != null && meta.has("schema") && meta.get("schema").isJsonPrimitive()
                    ? safeInt(meta.get("schema")) : 0;
            return new Parsed(config, name, schema > SCHEMA, meta == null ? "" : text(meta, "edition"));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean looksLikeConfig(JsonObject root) {
        return ConfigManager.isValidConfigJson(root.toString());
    }

    private static int safeInt(JsonElement element) {
        try {
            return element.getAsInt();
        } catch (RuntimeException notNumber) {
            return 0;
        }
    }

    private static String text(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Stamp (or refresh) the {@code meta} object; unknown keys inside an existing meta are kept. Local saves skip the
     * timestamp so an unchanged config stays byte-identical and does not rewrite its backup on every save.
     */
    public static JsonObject stamp(JsonObject root, String name, boolean withTimestamp) {
        JsonObject meta = root.has(META) && root.get(META).isJsonObject() ? root.getAsJsonObject(META) : new JsonObject();
        meta.addProperty("format", FORMAT);
        meta.addProperty("schema", SCHEMA);
        meta.addProperty("edition", EDITION);
        String version = clientVersion();
        if (!version.isEmpty()) meta.addProperty("client", version);
        meta.addProperty("minecraft", MINECRAFT);
        if (name != null && !name.isBlank()) meta.addProperty("name", name);
        if (withTimestamp) meta.addProperty("savedAt", Instant.now().toString());
        else meta.remove("savedAt");
        root.add(META, meta);
        return root;
    }

    /**
     * A copy that is safe to hand to anyone: no machine/account keys, no config-switch hotkeys (those belong to the
     * recipient's own library) and, when {@code keepUserContent} is false, no macros or scripts.
     */
    public static JsonObject forSharing(JsonObject root, boolean keepUserContent) {
        JsonObject copy = root.deepCopy();
        for (String key : PRIVATE_KEYS) copy.remove(key);
        copy.remove("configKeybinds");
        if (!keepUserContent) UserContentBlob.strip(copy);
        if (!copy.has("modules") || !copy.get("modules").isJsonObject()) copy.add("modules", new JsonObject());
        return copy;
    }

    private static String clientVersion() {
        try {
            String v = interstellarTikiTiks.apexx.client.auth.BuildInfo.version();
            return v == null ? "" : v;
        } catch (Throwable unavailable) {
            return "";
        }
    }
}
