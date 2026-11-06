package interstellarTikiTiks.apexx.client.util;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * Refresh rate of the monitor the game window is on, for render-frame polling caps. GLFW monitor
 * queries must run on the main (render) thread; the value is cached and re-queried every two seconds
 * so moving the window to another monitor is picked up. Falls back to 60 Hz.
 */
public final class MonitorRefreshRate {
    private static final int FALLBACK_HZ = 60;
    private static final long REQUERY_MS = 2000L;

    private static int cachedHz = FALLBACK_HZ;
    private static long nextQueryAt = 0L;

    private MonitorRefreshRate() {
    }

    public static int get(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now >= nextQueryAt) {
            nextQueryAt = now + REQUERY_MS;
            cachedHz = query(mc);
        }
        return cachedHz;
    }

    private static int query(MinecraftClient mc) {
        try {
            if (mc == null || mc.getWindow() == null) return FALLBACK_HZ;
            long window = mc.getWindow().getHandle();
            if (window == 0L) return FALLBACK_HZ;
            long monitor = GLFW.glfwGetWindowMonitor(window);
            if (monitor == 0L) monitor = monitorContaining(window);
            if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();
            if (monitor == 0L) return FALLBACK_HZ;
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            int hz = mode == null ? 0 : mode.refreshRate();
            return hz > 0 ? Math.max(30, Math.min(1000, hz)) : FALLBACK_HZ;
        } catch (Throwable t) {
            return FALLBACK_HZ;
        }
    }

    /** The monitor holding the centre of a windowed-mode window, or 0. */
    private static long monitorContaining(long window) {
        // Wayland has no global window/monitor positions: glfwGetWindowPos raises a GLFW error every call.
        if (GlfwPlatform.isWayland()) return 0L;
        int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowPos(window, wx, wy);
        GLFW.glfwGetWindowSize(window, ww, wh);
        int cx = wx[0] + ww[0] / 2, cy = wy[0] + wh[0] / 2;
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) return 0L;
        int[] mx = new int[1], my = new int[1];
        for (int i = 0; i < monitors.limit(); i++) {
            long m = monitors.get(i);
            GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
            if (mode == null) continue;
            GLFW.glfwGetMonitorPos(m, mx, my);
            if (cx >= mx[0] && cx < mx[0] + mode.width() && cy >= my[0] && cy < my[0] + mode.height()) {
                return m;
            }
        }
        return 0L;
    }
}
