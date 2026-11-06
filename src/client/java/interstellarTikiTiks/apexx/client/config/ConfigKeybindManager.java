package interstellarTikiTiks.apexx.client.config;

import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

public class ConfigKeybindManager {
    private static final ConfigKeybindManager INSTANCE = new ConfigKeybindManager();
    public static ConfigKeybindManager getInstance() { return INSTANCE; }

    private final List<ConfigKeybind> keybinds = new ArrayList<>();

    public static class ConfigKeybind {
        public String configName = "";
        public int key1 = -1;
        public int key2 = -1;
        public transient boolean wasPressed = false;

        public String getDisplayName() {
            if (key1 == -1) return "NONE";
            String first = keyName(key1);
            if (key2 != -1) return first + " + " + keyName(key2);
            return first;
        }

        public static String keyName(int key) {
            if (key < 0) return "MOUSE" + (interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(key) + 1);
            switch (key) {
                case GLFW.GLFW_KEY_RIGHT_SHIFT: return "RSHIFT";
                case GLFW.GLFW_KEY_LEFT_SHIFT: return "LSHIFT";
                case GLFW.GLFW_KEY_RIGHT_CONTROL: return "RCTRL";
                case GLFW.GLFW_KEY_LEFT_CONTROL: return "LCTRL";
                case GLFW.GLFW_KEY_RIGHT_ALT: return "RALT";
                case GLFW.GLFW_KEY_LEFT_ALT: return "LALT";
                case GLFW.GLFW_KEY_ESCAPE: return "ESC";
                case GLFW.GLFW_KEY_DELETE: return "DELETE";
                default:
                    String n = GLFW.glfwGetKeyName(key, 0);
                    return n == null ? ("KEY" + key) : n.toUpperCase();
            }
        }
    }

    public List<ConfigKeybind> getKeybinds() { return new ArrayList<>(keybinds); }

    public ConfigKeybind getOrCreate(String configName) {
        for (ConfigKeybind kb : keybinds) {
            if (kb.configName.equals(configName)) return kb;
        }
        ConfigKeybind kb = new ConfigKeybind();
        kb.configName = configName;
        keybinds.add(kb);
        return kb;
    }

    public ConfigKeybind get(String configName) {
        for (ConfigKeybind kb : keybinds) {
            if (kb.configName.equals(configName)) return kb;
        }
        return null;
    }

    public void setKeybind(String configName, int key1, int key2) {
        if (key1 == -1) {
            keybinds.removeIf(k -> k.configName.equals(configName));
            return;
        }
        ConfigKeybind kb = getOrCreate(configName);
        kb.key1 = key1;
        kb.key2 = key2;
    }

    public void clear() {
        keybinds.clear();
    }
}
