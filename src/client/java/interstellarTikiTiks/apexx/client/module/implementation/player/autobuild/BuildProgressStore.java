package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers where each schematic was being built so a build can resume after a relog. Keyed by
 * {@code fileName:size|world|dimension}; holds the absolute origin, transform, offsets and the layer
 * reached. Kept small (least-recently used entries drop out) and serialised to a JSON string that the
 * module stores in a hidden setting, so it travels with the config and nothing is written to disk.
 * Not thread-safe: use from the client thread.
 */
public final class BuildProgressStore {

    public static final int CAPACITY = 24;

    public record Progress(int x, int y, int z, String rotation, String mirror, int offsetX, int offsetY,
                           int offsetZ, int layer) {
    }

    private final LinkedHashMap<String, Progress> entries = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Progress> eldest) {
            return size() > CAPACITY;
        }
    };

    public static String key(String fileName, long size, String world, String dimension) {
        return safe(fileName) + ":" + size + "|" + safe(world) + "|" + safe(dimension);
    }

    public Progress get(String key) {
        return key == null ? null : entries.get(key);
    }

    public void put(String key, Progress progress) {
        if (key == null || progress == null) return;
        entries.put(key, progress);
    }

    public boolean remove(String key) {
        return key != null && entries.remove(key) != null;
    }

    public int size() {
        return entries.size();
    }

    /** Replaces the contents from {@link #toJson()} output; malformed input leaves the store empty. */
    public void load(String json) {
        entries.clear();
        if (json == null || json.isBlank()) return;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                try {
                    entries.put(e.getKey(), new Progress(
                            o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                            str(o, "rot", "0"), str(o, "mirror", "None"),
                            num(o, "ox"), num(o, "oy"), num(o, "oz"), Math.max(0, num(o, "layer"))));
                } catch (RuntimeException ignored) {
                    // one bad entry is dropped, the rest load
                }
            }
        } catch (RuntimeException ignored) {
            entries.clear();
        }
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Progress> e : entries.entrySet()) {
            Progress p = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("rot", p.rotation());
            o.addProperty("mirror", p.mirror());
            o.addProperty("ox", p.offsetX());
            o.addProperty("oy", p.offsetY());
            o.addProperty("oz", p.offsetZ());
            o.addProperty("layer", p.layer());
            root.add(e.getKey(), o);
        }
        return root.toString();
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    private static int num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('|', '/');
    }
}
