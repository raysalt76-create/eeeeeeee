package interstellarTikiTiks.apexx.client.gui.mobile;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Drawing helpers for the touch screens. Everything goes through {@link DrawContext} fills and
 * Minecraft's own text renderer, so nothing here needs NanoVG, custom shaders, blur or an offscreen
 * target, which is what keeps the mobile UI working on the GL layers Android launchers provide.
 */
final class MobileUi {
    static final int BG = 0xF00B0C12;
    static final int PANEL = 0xFF16171F;
    static final int PANEL_HI = 0xFF1F2130;
    static final int TEXT = 0xFFF2F3F7;
    static final int MUTED = 0xFF9296A8;
    static final int OFF = 0xFF3A3D4F;
    static final int DANGER = 0xFFE5534B;

    private MobileUi() {}

    /** Filled rounded rectangle built from scanlines, so it needs only plain rectangle fills. */
    static void roundRect(DrawContext c, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            c.fill(x, y, x + w, y + h, argb);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * (double) r - dy * dy)));
            c.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
            c.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, argb);
        }
        c.fill(x, y + r, x + w, y + h - r, argb);
    }

    static void text(DrawContext c, TextRenderer tr, String s, float x, float y, float scale, int argb) {
        if (s == null || s.isEmpty()) return;
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        c.drawText(tr, s, 0, 0, argb, false);
        m.popMatrix();
    }

    static float width(TextRenderer tr, String s, float scale) {
        return s == null ? 0f : tr.getWidth(s) * scale;
    }

    /** Text vertically centred in a box whose top is {@code y} and height {@code h}. */
    static void textMiddle(DrawContext c, TextRenderer tr, String s, float x, float y, float h, float scale, int argb) {
        text(c, tr, s, x, y + (h - 8f * scale) / 2f, scale, argb);
    }

    static void textCentered(DrawContext c, TextRenderer tr, String s, float cx, float y, float h, float scale, int argb) {
        textMiddle(c, tr, s, cx - width(tr, s, scale) / 2f, y, h, scale, argb);
    }

    /** Shortens text with ".." so it fits {@code maxWidth} at the given scale. */
    static String fit(TextRenderer tr, String s, float maxWidth, float scale) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return "";
        if (width(tr, s, scale) <= maxWidth) return s;
        return tr.trimToWidth(s, Math.max(1, (int) (maxWidth / scale) - tr.getWidth(".."))) + "..";
    }

    static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    /** Pill switch: the large on/off control used everywhere in the touch menu. */
    static void toggle(DrawContext c, int x, int y, int w, int h, boolean on, int accent) {
        roundRect(c, x, y, w, h, h / 2, on ? accent : OFF);
        int pad = Math.max(2, h / 8);
        int knob = h - pad * 2;
        int kx = on ? x + w - pad - knob : x + pad;
        roundRect(c, kx, y + pad, knob, knob, knob / 2, 0xFFFFFFFF);
    }
}
