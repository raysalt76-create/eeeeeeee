package interstellarTikiTiks.apexx.client.module.implementation.studio.macro.chunk;

import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * One chunk of a {@link ChunkMacro} timeline — the parsed, sanitized form of a
 * single entry in the macros.json "chunks" array.
 *
 * Wire shapes used by the macro editor:
 *   {"type":"offhand"}
 *   {"type":"swapTo","slot":N}                       (slot 1-9, user-facing)
 *   {"type":"click","button":"left|right","cps":N}
 *   {"type":"dragClick","button":"left|right","cps":N}
 *   {"type":"pause","ms":N}
 *   {"type":"hold","input":"attack|use|key","key":<glfw>,"mode":"timed|infinite","ms":N}
 *
 * This is an immutable value object — the scheduler reads it, never mutates it.
 */
public final class MacroChunk {

    public enum Type { OFFHAND, SWAP_TO, CLICK, DRAG_CLICK, PAUSE, HOLD }

    public enum Button { LEFT, RIGHT }

    public enum HoldInput { ATTACK, USE, KEY }

    public enum HoldMode { TIMED, INFINITE }

    public final Type type;

    // SWAP_TO — zero-based hotbar slot (0-8) after conversion from the 1-9 wire value.
    public final int slot;

    // CLICK / DRAG_CLICK
    public final Button button;
    public final int cps;

    // PAUSE / HOLD(timed)
    public final int ms;

    // HOLD
    public final HoldInput holdInput;
    public final HoldMode holdMode;
    public final int holdKey; // glfw key code, only for HOLD with input=KEY

    private MacroChunk(Type type, int slot, Button button, int cps, int ms,
                       HoldInput holdInput, HoldMode holdMode, int holdKey) {
        this.type = type;
        this.slot = slot;
        this.button = button;
        this.cps = cps;
        this.ms = ms;
        this.holdInput = holdInput;
        this.holdMode = holdMode;
        this.holdKey = holdKey;
    }

    /** Inter-click spacing in ms for CLICK/DRAG_CLICK. */
    public long clickIntervalMs() {
        return Math.max(1, 1000L / Math.max(1, cps));
    }

    /** Parse + sanitize one chunk object; returns null if the type is unknown. */
    public static MacroChunk fromJson(JsonObject o) {
        if (o == null) return null;
        String rawType = optString(o, "type", "").toLowerCase(Locale.ROOT);
        switch (rawType) {
            case "offhand":
                return new MacroChunk(Type.OFFHAND, 0, Button.LEFT, 0, 0, HoldInput.ATTACK, HoldMode.TIMED, -1);

            case "swapto": {
                int wireSlot = optInt(o, "slot", 1);
                int slot = clamp(wireSlot, 1, 9) - 1; // 1-9 → 0-8
                return new MacroChunk(Type.SWAP_TO, slot, Button.LEFT, 0, 0, HoldInput.ATTACK, HoldMode.TIMED, -1);
            }

            case "click": {
                Button b = parseButton(optString(o, "button", "left"));
                int cps = clamp(optInt(o, "cps", 12), 1, 30);
                return new MacroChunk(Type.CLICK, 0, b, cps, 0, HoldInput.ATTACK, HoldMode.TIMED, -1);
            }

            case "dragclick": {
                Button b = parseButton(optString(o, "button", "left"));
                int cps = clamp(optInt(o, "cps", 12), 1, 30);
                return new MacroChunk(Type.DRAG_CLICK, 0, b, cps, 0, HoldInput.ATTACK, HoldMode.TIMED, -1);
            }

            case "pause": {
                int ms = clamp(optInt(o, "ms", 50), 0, 10000);
                return new MacroChunk(Type.PAUSE, 0, Button.LEFT, 0, ms, HoldInput.ATTACK, HoldMode.TIMED, -1);
            }

            case "hold": {
                HoldInput input = parseHoldInput(optString(o, "input", "attack"));
                HoldMode mode = parseHoldMode(optString(o, "mode", "timed"));
                int ms = clamp(optInt(o, "ms", 200), 0, 10000);
                int key = optInt(o, "key", -1);
                if (key == 0 || key < -1 || key > GLFW.GLFW_KEY_LAST) key = -1;
                return new MacroChunk(Type.HOLD, 0, Button.LEFT, 0, ms, input, mode, key);
            }

            default:
                return null;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Button parseButton(String v) {
        return "right".equalsIgnoreCase(v) ? Button.RIGHT : Button.LEFT;
    }

    private static HoldInput parseHoldInput(String v) {
        if (v == null) return HoldInput.ATTACK;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "use" -> HoldInput.USE;
            case "key" -> HoldInput.KEY;
            default -> HoldInput.ATTACK;
        };
    }

    private static HoldMode parseHoldMode(String v) {
        return "infinite".equalsIgnoreCase(v) ? HoldMode.INFINITE : HoldMode.TIMED;
    }

    private static String optString(JsonObject o, String key, String def) {
        try {
            return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int optInt(JsonObject o, String key, int def) {
        try {
            return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
