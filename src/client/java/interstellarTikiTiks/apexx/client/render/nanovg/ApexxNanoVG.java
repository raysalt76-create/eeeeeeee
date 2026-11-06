package interstellarTikiTiks.apexx.client.render.nanovg;

import interstellarTikiTiks.apexx.client.util.IApexxRenderer;
import interstellarTikiTiks.apexx.client.util.NanoVGRendererImpl;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;

/**
 * Small screen-scoped facade over the client's NanoVG renderer.
 *
 * <p>The GUI code only needs immediate-mode rounded rectangles, text, clipping, and measurement.
 * Keeping those calls here makes the rendering engine explicit while retaining the existing
 * vanilla fallback for Vulkan, Android/Pojav, or a missing native NanoVG library.</p>
 */
public final class ApexxNanoVG {
    private static IApexxRenderer renderer;
    private static NanoVGRendererImpl ownedNanoVG;
    private static DrawContext context;
    private static float width;
    private static float height;

    private ApexxNanoVG() {
    }

    public static boolean begin(DrawContext drawContext, float targetWidth, float targetHeight,
            float pixelRatio) {
        context = drawContext;
        width = targetWidth;
        height = targetHeight;
        ensureRenderer();
        if (renderer == null) return false;

        if (renderer.beginFrame(targetWidth, targetHeight, pixelRatio)) {
            return true;
        }

        // NanoVG can be unavailable after a driver/backend change. The fallback keeps the
        // control deck usable without replacing the supported-backend NanoVG path.
        IApexxRenderer fallback = Renderer.get();
        if (fallback != renderer) {
            renderer = fallback;
            return renderer.beginFrame(targetWidth, targetHeight, pixelRatio);
        }
        return false;
    }

    public static void end() {
        if (renderer != null) renderer.endFrame();
        context = null;
    }

    public static boolean ready() {
        ensureRenderer();
        return renderer != null;
    }

    private static void ensureRenderer() {
        if (renderer != null) return;

        if (NanoVGRendererImpl.isSupportedOnCurrentBackend()) {
            try {
                ownedNanoVG = new NanoVGRendererImpl();
                ownedNanoVG.init();
                if (ownedNanoVG.isInitialized()) {
                    renderer = ownedNanoVG;
                    return;
                }
            } catch (Throwable ignored) {
                if (ownedNanoVG != null) {
                    try {
                        ownedNanoVG.cleanup();
                    } catch (Throwable ignoredCleanup) {
                    }
                }
                ownedNanoVG = null;
            }
        }

        renderer = Renderer.get();
    }

    public static float width() {
        return width;
    }

    public static float height() {
        return height;
    }

    public static Font regular() {
        return Font.REGULAR;
    }

    public static Font semiBold() {
        return Font.SEMI_BOLD;
    }

    public static void rect(float x, float y, float w, float h, float radius, int argb) {
        if (renderer != null) renderer.drawRoundedRect(context, x, y, w, h, radius, color(argb));
    }

    public static void text(Font font, CharSequence value, float x, float y, float size, int argb) {
        if (renderer != null && value != null) {
            renderer.drawText(context, value.toString(), x, y, size, color(argb), false);
        }
    }

    public static float textWidth(Font font, CharSequence value, float size) {
        if (renderer == null || value == null || value.isEmpty()) return 0.0f;
        return renderer.getTextWidth(value.toString(), size);
    }

    public static float textHeight(Font font, float size) {
        return renderer == null ? size : renderer.getTextHeight(size);
    }

    public static void scissor(float x, float y, float w, float h) {
        if (renderer != null) renderer.scissor(context, x, y, w, h);
    }

    public static void resetScissor() {
        if (renderer != null) renderer.resetScissor();
    }

    public static void dispose() {
        if (ownedNanoVG != null) {
            try {
                ownedNanoVG.cleanup();
            } catch (Throwable ignored) {
            }
        }
        ownedNanoVG = null;
        renderer = null;
        context = null;
        width = 0.0f;
        height = 0.0f;
    }

    private static Color color(int argb) {
        return new Color(argb, true);
    }

    public static final class Font {
        private static final Font REGULAR = new Font();
        private static final Font SEMI_BOLD = new Font();

        private Font() {
        }

        public float getWidth(CharSequence value, float size) {
            return ApexxNanoVG.textWidth(this, value, size);
        }
    }
}
