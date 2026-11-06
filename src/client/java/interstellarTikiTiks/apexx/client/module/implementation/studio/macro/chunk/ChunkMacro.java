package interstellarTikiTiks.apexx.client.module.implementation.studio.macro.chunk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single chunk-based macro: a name, a bound GLFW key (or -1), and an ordered,
 * sanitized list of {@link MacroChunk}s. Parsed from one entry of the macros.json
 * "macros" array.
 */
public final class ChunkMacro {

    public static final int MAX_CHUNKS = 64;

    public final String name;
    public final int key; // glfw key code, or -1 for unbound
    public final List<MacroChunk> chunks;

    private ChunkMacro(String name, int key, List<MacroChunk> chunks) {
        this.name = name;
        this.key = key;
        this.chunks = Collections.unmodifiableList(chunks);
    }

    public boolean isBound() {
        return key != -1;
    }

    public boolean hasWork() {
        return isBound() && !chunks.isEmpty();
    }

    /**
     * Whether this macro holds an input indefinitely. Such macros run with HOLD
     * semantics — releasing the trigger key ends the run (and releases the hold);
     * macros without one run with PRESS semantics (the timeline runs to completion
     * regardless of when the key is released).
     */
    public boolean hasInfiniteHold() {
        for (MacroChunk c : chunks) {
            if (c.type == MacroChunk.Type.HOLD && c.holdMode == MacroChunk.HoldMode.INFINITE) {
                return true;
            }
        }
        return false;
    }

    public static ChunkMacro fromJson(JsonObject o) {
        if (o == null) return null;

        String name = "Macro";
        if (o.has("name") && !o.get("name").isJsonNull()) {
            try { name = o.get("name").getAsString(); } catch (Exception ignored) { /* Fallback to default name */ }
        }
        if (name == null || name.isBlank()) name = "Macro";
        if (name.length() > 48) name = name.substring(0, 48);

        int key = -1;
        if (o.has("key") && !o.get("key").isJsonNull()) {
            try { key = o.get("key").getAsInt(); } catch (Exception ignored) { /* Fallback to default key */ }
        }
        if (key == 0 || key < -1 || key > GLFW.GLFW_KEY_LAST) key = -1;

        List<MacroChunk> chunks = new ArrayList<>();
        if (o.has("chunks") && o.get("chunks").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("chunks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                MacroChunk chunk = MacroChunk.fromJson(el.getAsJsonObject());
                if (chunk != null) chunks.add(chunk);
                if (chunks.size() >= MAX_CHUNKS) break;
            }
        }

        return new ChunkMacro(name, key, chunks);
    }
}
