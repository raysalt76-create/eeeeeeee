package interstellarTikiTiks.apexx.client.render.msdf;

import net.minecraft.client.MinecraftClient;

/**
 * Entry point for MSDF text. Lazily initialises on first use from the render thread, and degrades
 * to a no-op if anything about the GL path fails — a font that will not load must never take the
 * game down with it, so every caller can treat {@link #ready()} as the only question worth asking.
 *
 * <h2>Usage</h2>
 * <pre>
 *   if (Msdf.begin()) {
 *       Msdf.text(Msdf.semiBold(), "AimAssist", 8, 8, 13, 0xFF26E0C6);
 *       Msdf.end();
 *   }
 * </pre>
 * Everything between {@code begin} and {@code end} is batched into a single draw call, and all GL
 * state touched in between is restored by {@code end}.
 */
public final class Msdf {

    private Msdf() {
    }

    private static MsdfFont regular;
    private static MsdfFont semiBold;
    private static final MsdfText BATCH = new MsdfText();
    private static final RectBatch RECTS = new RectBatch();

    private static boolean attempted;
    private static boolean available;

    /**
     * Which primitive was queued last. Text and rects are separate GL programs, so they cannot
     * share one draw call; switching between them flushes first. That is what preserves
     * painter's order — without it every rect would land on top of (or under) all text
     * regardless of the order the caller asked for.
     */
    private static int pending; // 0 = none, 1 = rects, 2 = text

    /** True once the fonts and GL program are loaded and usable. */
    public static boolean ready() {
        ensure();
        return available;
    }

    private static void ensure() {
        if (attempted) return;
        attempted = true;
        try {
            regular = new MsdfFont("inter");
            semiBold = new MsdfFont("inter_semi_bold");
            available = BATCH.init() && RECTS.init();
            if (!available) { destroyFonts(); try { BATCH.destroy(); RECTS.destroy(); } catch (Throwable ignored) { } }
        } catch (Throwable t) {
            available = false;
            destroyFonts();
        }
    }

    private static void destroyFonts() {
        try {
            if (regular != null) regular.destroy();
            if (semiBold != null) semiBold.destroy();
        } catch (Throwable ignored) {
        }
        regular = null;
        semiBold = null;
    }

    public static MsdfFont regular()  { ensure(); return regular; }
    public static MsdfFont semiBold() { ensure(); return semiBold; }

    /**
     * Open a batch sized to the current window. Returns false when MSDF is unavailable, in which
     * case the caller must fall back to whatever it drew before.
     */
    public static boolean begin() {
        if (!ready()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        // Real framebuffer pixels, not GUI-scaled units: the distance field resolves per screen
        // pixel, so drawing at native resolution is what keeps it sharp instead of blocky.
        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();
        if (!BATCH.begin(w, h)) return false;
        RECTS.setProjection(w, h);
        pending = 0;
        return true;
    }

    /** Screen width in the coordinate space {@link #begin()} set up. */
    public static int width() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getFramebufferWidth();
    }

    /** Screen height in the coordinate space {@link #begin()} set up. */
    public static int height() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getFramebufferHeight();
    }

    public static void rect(float x, float y, float w, float h, float radius, int argb) {
        if (!available) return;
        if (pending == 2) BATCH.flush();   // text queued before this rect must land underneath it
        pending = 1;
        RECTS.rect(x, y, w, h, radius, argb);
    }

    public static void text(MsdfFont font, CharSequence s, float x, float y, float size, int argb) {
        if (!available) return;
        if (pending == 1) RECTS.flush();   // rects queued before this text must land underneath it
        pending = 2;
        BATCH.text(font, s, x, y, size, argb);
    }

    public static void textWithShadow(MsdfFont font, CharSequence s, float x, float y, float size, int argb) {
        if (!available) return;
        if (pending == 1) RECTS.flush();
        pending = 2;
        BATCH.textWithShadow(font, s, x, y, size, argb);
    }

    public static void end() {
        if (!available) return;
        // Drain whichever batch still holds geometry, in the order it was queued, then let the
        // text batcher put all the GL state back the way vanilla left it.
        if (pending == 1) {
            RECTS.flush();
        } else if (pending == 2) {
            BATCH.flush();
        }
        RECTS.flush();
        pending = 0;
        BATCH.end();
    }

    public static float width(MsdfFont font, CharSequence s, float size) {
        return font == null ? 0.0f : font.getWidth(s, size);
    }

    public static void destroy() {
        try {
            BATCH.destroy();
            RECTS.destroy();
        } catch (Throwable ignored) {
        }
        destroyFonts();
        attempted = false;
        available = false;
    }
}
