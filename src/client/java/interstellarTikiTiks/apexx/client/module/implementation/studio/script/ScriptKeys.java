package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Key names for scripts ({@code key "R"}, {@code keyCode("SPACE")}, {@code ev.name}), mapped to the
 * module keybind encoding: GLFW key codes for keys, {@code -(button + 100)} for mouse buttons.
 * Pure data (no GLFW calls) so the parser and tests can use it.
 */
public final class ScriptKeys {
    private ScriptKeys() { }

    public static final int UNKNOWN = Integer.MIN_VALUE;

    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_CODE = new HashMap<>();

    private static void k(String name, int code) {
        BY_NAME.put(name, code);
        BY_CODE.putIfAbsent(code, name);
    }

    static {
        for (char c = 'A'; c <= 'Z'; c++) k(String.valueOf(c), c);
        for (char c = '0'; c <= '9'; c++) k(String.valueOf(c), c);
        for (int i = 1; i <= 25; i++) k("F" + i, 289 + i);
        k("SPACE", 32); k("APOSTROPHE", 39); k("COMMA", 44); k("MINUS", 45); k("PERIOD", 46);
        k("SLASH", 47); k("SEMICOLON", 59); k("EQUAL", 61); k("LEFT_BRACKET", 91); k("BACKSLASH", 92);
        k("RIGHT_BRACKET", 93); k("GRAVE", 96);
        k("ESCAPE", 256); k("ENTER", 257); k("TAB", 258); k("BACKSPACE", 259); k("INSERT", 260);
        k("DELETE", 261); k("RIGHT", 262); k("LEFT", 263); k("DOWN", 264); k("UP", 265);
        k("PAGE_UP", 266); k("PAGE_DOWN", 267); k("HOME", 268); k("END", 269); k("CAPS_LOCK", 280);
        k("SCROLL_LOCK", 281); k("NUM_LOCK", 282); k("PRINT_SCREEN", 283); k("PAUSE", 284);
        for (int i = 0; i <= 9; i++) k("KP_" + i, 320 + i);
        k("KP_DECIMAL", 330); k("KP_DIVIDE", 331); k("KP_MULTIPLY", 332); k("KP_SUBTRACT", 333);
        k("KP_ADD", 334); k("KP_ENTER", 335);
        k("LEFT_SHIFT", 340); k("LEFT_CONTROL", 341); k("LEFT_ALT", 342); k("LEFT_SUPER", 343);
        k("RIGHT_SHIFT", 344); k("RIGHT_CONTROL", 345); k("RIGHT_ALT", 346); k("RIGHT_SUPER", 347);
        k("MENU", 348);
        // Mouse buttons (module keybind encoding).
        k("MOUSE_LEFT", -100); k("MOUSE_RIGHT", -101); k("MOUSE_MIDDLE", -102);
        for (int b = 0; b <= 7; b++) k("MOUSE" + (b + 1), -(b + 100));
        k("MOUSE_X1", -103); k("MOUSE_X2", -104);
        // Common aliases.
        BY_NAME.put("SHIFT", 340); BY_NAME.put("CTRL", 341); BY_NAME.put("CONTROL", 341);
        BY_NAME.put("ALT", 342); BY_NAME.put("RSHIFT", 344); BY_NAME.put("RCTRL", 345);
        BY_NAME.put("RALT", 346); BY_NAME.put("ESC", 256); BY_NAME.put("RETURN", 257);
        BY_NAME.put("LMB", -100); BY_NAME.put("RMB", -101); BY_NAME.put("MMB", -102);
    }

    /** The key code for a name (case-insensitive, spaces/dashes as underscores), or {@link #UNKNOWN}. */
    public static int parse(String name) {
        if (name == null) return UNKNOWN;
        String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (n.startsWith("KEY_") && n.length() > 4) n = n.substring(4);
        Integer code = BY_NAME.get(n);
        return code == null ? UNKNOWN : code;
    }

    /** A display name for a key code, e.g. "R", "SPACE", "MOUSE4". */
    public static String name(int code) {
        String n = BY_CODE.get(code);
        return n != null ? n : (code < 0 ? "MOUSE" + (-code - 99) : "KEY" + code);
    }

    private static int[] keyboardCodes;

    /** Every valid GLFW keyboard code this table knows (safe to pass to glfwGetKey). */
    public static int[] keyboardCodes() {
        int[] cached = keyboardCodes;
        if (cached == null) {
            cached = BY_CODE.keySet().stream().filter(c -> c >= 32).mapToInt(Integer::intValue).sorted().toArray();
            keyboardCodes = cached;
        }
        return cached;
    }

    /** True for a mouse-button code in the module keybind encoding. */
    public static boolean isMouse(int code) {
        return code <= -100 && code >= -107;
    }
}
