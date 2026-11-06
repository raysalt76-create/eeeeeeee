package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.Map;
import java.util.TreeMap;

/** Registry-free helpers for {@code "namespace:block[k=v,...]"} block-state strings. */
public final class StateString {

    private StateString() {
    }

    /** Base id with namespace, e.g. {@code minecraft:oak_stairs}. */
    public static String base(String state) {
        return Schematic.baseName(state);
    }

    /** Path without namespace, e.g. {@code oak_stairs}. */
    public static String path(String state) {
        String base = base(state);
        int colon = base.indexOf(':');
        return colon >= 0 ? base.substring(colon + 1) : base;
    }

    /** Properties in key order; empty when the state has none. */
    public static Map<String, String> props(String state) {
        Map<String, String> out = new TreeMap<>();
        if (state == null) {
            return out;
        }
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return out;
        }
        String body = state.substring(open + 1, close);
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** Builds a state string with keys sorted, matching {@link SchematicIo}'s palette strings. */
    public static String compose(String base, Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }
}
