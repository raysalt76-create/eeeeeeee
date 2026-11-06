package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

/**
 * "Menu Scale Mode: Auto". The menu's base size already follows the window height (so 1080p, 1440p and 4K
 * show it at the same fraction of the screen); this adds a correction from the monitor's physical size, so a
 * 14" laptop panel gets a somewhat larger share of its small screen and a 32" monitor a somewhat smaller one,
 * keeping the menu near the same physical size. On the reference screen (a 24" 16:9 monitor) the correction is 1.
 *
 * <p>The monitor is probed through GLFW at most once a second on the render thread; anything implausible (no
 * EDID size, a virtual display) falls back to the monitor's content scale, and failing that to no correction.
 */
public final class AutoMenuScale {
    /** Height of the reference screen's picture, in mm (24" at 16:9). */
    static final float REFERENCE_HEIGHT_MM = 299f;
    static final float EXPONENT = 0.3f;
    static final float MIN_CORRECTION = 0.85f, MAX_CORRECTION = 1.25f;
    /** Plausible framebuffer pixels per millimetre (about 38 to 1000 dpi). */
    static final float MIN_PX_PER_MM = 1.5f, MAX_PX_PER_MM = 40f;
    private static final float CSS_PX_PER_MM = 96f / 25.4f;
    private static final long PROBE_INTERVAL_NS = 1_000_000_000L;

    private static float cachedPxPerMm = Float.NaN;
    private static long probedAt = Long.MIN_VALUE / 2;

    private AutoMenuScale() { }

    /**
     * Correction factor for a window {@code windowHeightPx} framebuffer pixels tall on a screen with
     * {@code pxPerMm} framebuffer pixels per millimetre. 1 when either is unknown.
     */
    public static float correction(float windowHeightPx, float pxPerMm) {
        if (!(windowHeightPx > 0f) || !Float.isFinite(windowHeightPx) || !(pxPerMm > 0f) || !Float.isFinite(pxPerMm)) {
            return 1f;
        }
        float heightMm = windowHeightPx / pxPerMm;
        float k = (float) Math.pow(REFERENCE_HEIGHT_MM / heightMm, EXPONENT);
        if (!Float.isFinite(k)) return 1f;
        return Math.max(MIN_CORRECTION, Math.min(MAX_CORRECTION, k));
    }

    /**
     * Framebuffer pixels per mm from a monitor's mode height (screen coordinates), its physical height (mm), the
     * window's framebuffer-to-screen-coordinate ratio (2 on a Retina Mac, 1 on Windows/Linux) and its content
     * scale. NaN when nothing usable is known.
     */
    static float pxPerMm(int modeHeight, int physicalHeightMm, float framebufferRatio, float contentScale) {
        float ratio = framebufferRatio > 0f && Float.isFinite(framebufferRatio) ? framebufferRatio : 1f;
        if (modeHeight > 0 && physicalHeightMm >= 40 && physicalHeightMm <= 2000) {
            float v = modeHeight * ratio / physicalHeightMm;
            if (v >= MIN_PX_PER_MM && v <= MAX_PX_PER_MM) return v;
        }
        if (contentScale > 0f && Float.isFinite(contentScale)) {
            float v = CSS_PX_PER_MM * contentScale;
            if (v >= MIN_PX_PER_MM && v <= MAX_PX_PER_MM) return v;
        }
        return Float.NaN;
    }

    /** Pixels per mm of the monitor the game window is on (cached for a second); NaN when unknown. */
    public static float currentPxPerMm(MinecraftClient client) {
        long now = System.nanoTime();
        if (now - probedAt < PROBE_INTERVAL_NS) return cachedPxPerMm;
        probedAt = now;
        float value = Float.NaN;
        try {
            if (client != null && client.getWindow() != null) value = probe(client.getWindow().getHandle());
        } catch (Throwable unavailable) {
            value = Float.NaN;
        }
        cachedPxPerMm = value;
        return value;
    }

    private static float probe(long window) {
        if (window == 0L) return Float.NaN;
        long monitor = GLFW.glfwGetWindowMonitor(window);
        if (monitor == 0L) monitor = monitorUnderWindow(window);
        if (monitor == 0L) monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == 0L) return Float.NaN;
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        int[] wmm = new int[1], hmm = new int[1];
        GLFW.glfwGetMonitorPhysicalSize(monitor, wmm, hmm);
        float[] xs = new float[1], ys = new float[1];
        GLFW.glfwGetMonitorContentScale(monitor, xs, ys);
        int[] winW = new int[1], winH = new int[1], fbW = new int[1], fbH = new int[1];
        GLFW.glfwGetWindowSize(window, winW, winH);
        GLFW.glfwGetFramebufferSize(window, fbW, fbH);
        float ratio = winH[0] > 0 && fbH[0] > 0 ? fbH[0] / (float) winH[0] : 1f;
        return pxPerMm(mode == null ? 0 : mode.height(), hmm[0], ratio, ys[0]);
    }

    /** The monitor holding the centre of a windowed game window, or 0. */
    private static long monitorUnderWindow(long window) {
        // Wayland has no global window/monitor positions: glfwGetWindowPos raises a GLFW error every call.
        if (interstellarTikiTiks.apexx.client.util.GlfwPlatform.isWayland()) return 0L;
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) return 0L;
        int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowPos(window, wx, wy);
        GLFW.glfwGetWindowSize(window, ww, wh);
        int cx = wx[0] + ww[0] / 2, cy = wy[0] + wh[0] / 2;
        int[] mx = new int[1], my = new int[1];
        for (int i = 0; i < monitors.limit(); i++) {
            long m = monitors.get(i);
            GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
            if (mode == null) continue;
            GLFW.glfwGetMonitorPos(m, mx, my);
            if (cx >= mx[0] && cy >= my[0] && cx < mx[0] + mode.width() && cy < my[0] + mode.height()) return m;
        }
        return 0L;
    }
}
