package interstellarTikiTiks.apexx.client.enemy;

import org.lwjgl.glfw.GLFW;

/**
 * EnemyKeybindManager — the enemy key, held in memory. It is persisted only in the user's own
 * cloud config blob (see ClientPrefs), so it survives restarts and config swaps; nothing is
 * written to disk.
 */
public class EnemyKeybindManager {
    private static final EnemyKeybindManager INSTANCE = new EnemyKeybindManager();

    private volatile int keyCode = -1;
    private volatile boolean binding = false;
    private volatile long bindStartTime = 0;

    private EnemyKeybindManager() {
    }

    public static EnemyKeybindManager getInstance() { return INSTANCE; }

    public int getKeyCode() { return keyCode; }

    public void setKeyCode(int keyCode) {
        int next = keyCode == 0 ? -1 : keyCode;
        if (next == this.keyCode) return;
        this.keyCode = next;
        interstellarTikiTiks.apexx.client.config.ClientPrefs.get().onKeybindChanged();
    }

    public boolean isBinding() { return binding; }

    public void setBinding(boolean binding) {
        this.binding = binding;
        if (binding) this.bindStartTime = System.currentTimeMillis();
    }

    public long getBindStartTime() { return bindStartTime; }
    public void setBindStartTime(long bindStartTime) { this.bindStartTime = bindStartTime; }

    public String getKeyDisplayName() {
        if (keyCode == -1 || keyCode == 0) return "NONE";
        if (keyCode < 0) {
            int button = interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(keyCode);
            return "MOUSE" + (button + 1);
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT: return "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return "RCTRL";
            case GLFW.GLFW_KEY_LEFT_CONTROL: return "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_ALT: return "RALT";
            case GLFW.GLFW_KEY_LEFT_ALT: return "LALT";
            case GLFW.GLFW_KEY_ESCAPE: return "ESC";
            case GLFW.GLFW_KEY_DELETE: return "DELETE";
            case GLFW.GLFW_KEY_INSERT: return "INSERT";
            case GLFW.GLFW_KEY_PAGE_UP: return "PAGE UP";
            case GLFW.GLFW_KEY_PAGE_DOWN: return "PAGE DOWN";
            case GLFW.GLFW_KEY_PRINT_SCREEN: return "PRINT SCREEN";
            default:
                String name = GLFW.glfwGetKeyName(keyCode, 0);
                return name == null ? "UNKNOWN" : name.toUpperCase();
        }
    }

    public boolean checkKeyPress(int pressedKey) {
        return !binding && keyCode != -1 && pressedKey == keyCode;
    }
}
