package interstellarTikiTiks.apexx.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/** Lenient reads for cosmetics JSON that may come from another user: never throws. */
final class Json {
    private Json() { }

    static String str(JsonObject o, String key, String fallback) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    static int integer(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
            double d = e.getAsDouble();
            return Double.isFinite(d) ? (int) (long) d : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    static double number(JsonObject o, String key, double fallback) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
            double d = e.getAsDouble();
            return Double.isFinite(d) ? d : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
