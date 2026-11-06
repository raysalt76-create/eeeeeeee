package interstellarTikiTiks.apexx.client.util;

import org.lwjgl.glfw.GLFW;

/**
 * Which windowing backend GLFW picked. Under Wayland an application may not warp the pointer
 * ({@code glfwSetCursorPos} only reports GLFW_FEATURE_UNAVAILABLE outside pointer lock) and may not read other
 * windows, so features built on those must step aside there instead of failing every frame.
 */
public final class GlfwPlatform {
    private static volatile int platform; // 0 = not known yet

    private GlfwPlatform() {}

    /** True when GLFW runs on its native Wayland backend (not XWayland). */
    public static boolean isWayland() {
        int p = platform();
        try {
            return p != 0 && p == GLFW.GLFW_PLATFORM_WAYLAND;
        } catch (Throwable missingConstant) {
            return false;
        }
    }

    /** Whether an application can move the pointer on this backend. */
    public static boolean cursorWarpSupported() {
        return !isWayland();
    }

    private static int platform() {
        int p = platform;
        if (p != 0) return p;
        // NEVER call into GLFW before Minecraft has initialised it. Modules are constructed during client init,
        // before RenderSystem.initBackendSystem runs glfwInit; a GLFW call then records GLFW_NOT_INITIALIZED, and
        // Minecraft's GLX._initGlfw turns that pending error into a fatal "GLFW error before init" crash.
        // The game window only exists after glfwInit, so its presence is the safe signal. Until then: "unknown".
        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc == null || mc.getWindow() == null) return 0;
        } catch (Throwable notReady) {
            return 0;
        }
        try {
            // GLFW 3.4 (LWJGL 3.3.2+). Safe here: GLFW is initialised once the game window exists.
            p = GLFW.glfwGetPlatform();
        } catch (Throwable olderGlfw) {
            // An LWJGL/GLFW without glfwGetPlatform: treat as "not Wayland" for good.
            p = -1;
        }
        if (p != 0) platform = p;
        return p;
    }
}
