package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;

import java.nio.charset.StandardCharsets;

/**
 * The user's macros and IDE scripts ride in the active cloud config blob as the top-level keys
 * {@code macros} and {@code scripts}. This class is the pure-JSON half of that: attaching them under a
 * size budget, stripping them from foreign configs, and detecting them for the privacy guard. It holds
 * no game state, so it is unit-testable.
 */
public final class UserContentBlob {
    public static final String MACROS = "macros";
    public static final String SCRIPTS = "scripts";
    /** The backend rejects blobs above 900,000 bytes; stay clear of it. */
    public static final int MAX_BLOB_BYTES = 850_000;

    private UserContentBlob() {}

    public static int byteSize(JsonObject root) {
        return root.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Sets {@code macros}/{@code scripts} on {@code root} (null leaves that key untouched). If the blob
     * would exceed {@code limit} bytes, scripts are dropped first, then macros, with a warning. Never throws.
     */
    public static void attach(JsonObject root, JsonArray macros, JsonArray scripts, int limit) {
        if (root == null) return;
        try {
            if (macros != null) root.add(MACROS, macros);
            if (scripts != null) root.add(SCRIPTS, scripts);
            if (byteSize(root) > limit && root.has(SCRIPTS)) {
                root.remove(SCRIPTS);
                ClientInternalLog.warn("ConfigSync", "Config too large for the cloud: scripts were left out of the save");
            }
            if (byteSize(root) > limit && root.has(MACROS)) {
                root.remove(MACROS);
                ClientInternalLog.warn("ConfigSync", "Config too large for the cloud: macros were left out of the save");
            }
        } catch (RuntimeException e) {
            root.remove(SCRIPTS);
            root.remove(MACROS);
        }
    }

    /** Removes both keys. Returns true if either was present. */
    public static boolean strip(JsonObject root) {
        if (root == null) return false;
        boolean had = root.has(MACROS) || root.has(SCRIPTS);
        root.remove(MACROS);
        root.remove(SCRIPTS);
        return had;
    }

    /** True when the blob carries at least one macro or script. */
    public static boolean hasContent(JsonObject root) {
        if (root == null) return false;
        return nonEmptyArray(root.get(MACROS)) || nonEmptyArray(root.get(SCRIPTS));
    }

    public static boolean hasContent(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() && hasContent(parsed.getAsJsonObject());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** The JSON text without the macros/scripts keys (unchanged text when it has none or is malformed). */
    public static String stripJson(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return json;
            JsonObject obj = parsed.getAsJsonObject();
            return strip(obj) ? obj.toString() : json;
        } catch (RuntimeException malformed) {
            return json;
        }
    }

    private static boolean nonEmptyArray(JsonElement el) {
        return el != null && el.isJsonArray() && el.getAsJsonArray().size() > 0;
    }
}
