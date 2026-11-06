package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Stateless drawing helpers shared by the menu views so every page uses the same controls. */
public final class UiKit {
    public static final int ON_ACCENT = 0xff101214;
    public static final int DANGER = 0xffff6b6b;
    public static final int SUCCESS = 0xff72e5b5;

    private UiKit() { }

    public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

    public static int argb(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | (rgb & 0x00ffffff);
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00ffffff);
    }

    /** Raised card with a hairline border. */
    public static void card(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s) {
        // Same card radius as the click GUI's own cards (GpuClickGuiScreen.CARD_RADIUS).
        float r = Math.round(12f * s * host.cornerScale());
        b.rect(x, y, w, h, r, host.cardFill());
        b.border(x, y, w, h, r, 1f, 0x14ffffff);
    }

    public static void divider(UiDisplayList.Builder b, UiHost host, float x, float y, float w) {
        b.rect(x, y, w, 1f, 0, host.lineColor());
    }

    public static void title(UiDisplayList.Builder b, UiHost host, String text, float x, float y, float s) {
        b.text(x, y, 16f * s, host.titleColor(), text, UiDrawCommand.TextWeight.SEMIBOLD);
    }

    public static void label(UiDisplayList.Builder b, UiHost host, String text, float x, float y, float s) {
        b.text(x, y, 9.5f * s, host.muted2Color(), text.toUpperCase(java.util.Locale.ROOT), UiDrawCommand.TextWeight.SEMIBOLD);
    }

    /** Pill button. Returns nothing; the click region is registered when enabled. */
    public static void button(UiDisplayList.Builder b, UiHost host, String label, float x, float y, float w, float h,
                              float s, Style style, boolean enabled, float mx, float my, Runnable action) {
        boolean hover = enabled && host.hovered(mx, my, x, y, w, h);
        // Controls are full capsules everywhere in the menu.
        float r = h * 0.5f * Math.min(1f, host.cornerScale());
        int accent = host.accent();
        int fill, border, text;
        switch (style) {
            case PRIMARY -> {
                fill = hover ? brighten(accent, 0.12f) : accent;
                border = 0;
                text = ON_ACCENT;
            }
            case DANGER -> {
                fill = withAlpha(DANGER, hover ? 0x40 : 0x24);
                border = withAlpha(DANGER, 0x60);
                text = 0xffff9a9a;
            }
            case GHOST -> {
                fill = hover ? host.controlFill(0x1a) : 0;
                border = 0x1effffff;
                text = host.textColor();
            }
            default -> {
                fill = host.controlFill(hover ? 0x30 : 0x1c);
                border = 0x1effffff;
                text = host.textColor();
            }
        }
        if (!enabled) {
            fill = style == Style.PRIMARY ? withAlpha(accent, 0x40) : host.controlFill(0x0c);
            text = style == Style.PRIMARY ? withAlpha(ON_ACCENT, 0x90) : host.muted2Color();
        }
        if ((fill >>> 24) != 0) b.rect(x, y, w, h, r, fill);
        if ((border >>> 24) != 0) b.border(x, y, w, h, r, 1f, border);
        float size = 10f * s;
        // Measured in the weight it is drawn in, so the label is truly centred.
        String shown = fit(label, Math.max(0f, w - 14f * s), size, UiDrawCommand.TextWeight.SEMIBOLD);
        float tw = GpuUiFontMetrics.textWidth(shown, size, UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + (w - tw) * 0.5f, GpuUiFontMetrics.topInBox(y, h, size), size, text, shown, UiDrawCommand.TextWeight.SEMIBOLD);
        if (enabled) host.hit(x, y, w, h, action);
    }

    /** Button width for a label at the standard size, with horizontal padding. */
    public static float buttonWidth(String label, float s) {
        return Math.round(GpuUiFontMetrics.textWidth(label, 10f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 28f * s);
    }

    /** Small rounded tag. Returns its width. */
    public static float chip(UiDisplayList.Builder b, UiHost host, String label, float x, float y, float s, int color) {
        float size = 8.5f * s;
        float w = Math.round(GpuUiFontMetrics.textWidth(label, size, UiDrawCommand.TextWeight.SEMIBOLD) + 16f * s);
        float h = Math.round(17f * s);
        b.rect(x, y, w, h, h * 0.5f * Math.min(1f, host.cornerScale()), withAlpha(color, 0x26));
        b.text(x + 8f * s, GpuUiFontMetrics.topInBox(y, h, size), size, color, label, UiDrawCommand.TextWeight.SEMIBOLD);
        return w;
    }

    /** Segmented control; the active segment is filled with the accent. */
    public static void segmented(UiDisplayList.Builder b, UiHost host, String[] labels, int selected,
                                 float x, float y, float w, float h, float s, float mx, float my, IntConsumer onSelect) {
        // Capsule tray with capsule segments: the tray radius is the segment's plus the inset, so the
        // gap between them is even all the way round (no square stamp inside a round tray).
        float inset = Math.round(3f * s);
        float segH = h - inset * 2f;
        float round = Math.min(1f, host.cornerScale());
        b.rect(x, y, w, h, (segH * 0.5f + inset) * round, host.controlFill(0x12));
        b.border(x, y, w, h, (segH * 0.5f + inset) * round, 1f, 0x14ffffff);
        float segW = (w - inset * 2f) / labels.length;
        // One accent pill glides to the active segment instead of snapping between them (keyed by position).
        if (labels.length > 0 && selected >= 0) {
            float sel = host.anim("ui_seg_" + Math.round(x) + "_" + Math.round(y), selected, 18f);
            float pillX = Math.round(x + inset + sel * segW);
            b.rect(pillX, y + inset, Math.round(segW), segH, segH * 0.5f * round, withAlpha(host.accent(), 0x34));
        }
        for (int i = 0; i < labels.length; i++) {
            float sx = Math.round(x + inset + i * segW);
            float sw = Math.round(x + inset + (i + 1) * segW) - sx;
            boolean active = i == selected;
            boolean hover = host.hovered(mx, my, sx, y, sw, h);
            if (!active && hover) b.rect(sx, y + inset, sw, segH, segH * 0.5f * round, host.controlFill(0x14));
            float size = 10f * s;
            String shown = fit(labels[i], Math.max(0f, sw - 12f * s), size, UiDrawCommand.TextWeight.SEMIBOLD);
            float tw = GpuUiFontMetrics.textWidth(shown, size, UiDrawCommand.TextWeight.SEMIBOLD);
            b.text(sx + (sw - tw) * 0.5f, GpuUiFontMetrics.topInBox(y, h, size), size,
                    active ? host.accent() : hover ? host.textColor() : host.mutedColor(), shown,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            final int index = i;
            host.hit(sx, y, sw, h, () -> onSelect.accept(index));
        }
    }

    /** On/off switch. The knob slides and the track colour fades between states (keyed by position). */
    public static void toggle(UiDisplayList.Builder b, UiHost host, float x, float y, float s, boolean on,
                              Runnable action) {
        // Same geometry as the click GUI's toggle: a 34x18 capsule with a circular knob 2px inside it.
        x = Math.round(x);
        y = Math.round(y);
        float w = Math.round(34f * s), h = Math.round(18f * s);
        float r = h * 0.5f;
        float a = host.anim("ui_tg_" + Math.round(x) + "_" + Math.round(y), on ? 1f : 0f, 22f);
        float trackR = r * Math.min(1f, host.cornerScale());
        // Off track, then the accent fading in over it as the switch turns on.
        b.rect(x, y, w, h, trackR, host.controlFill(0x28));
        if (a > 0.003f) b.rect(x, y, w, h, trackR, withAlpha(host.accent(), Math.round(255f * a)));
        float knobR = r - Math.max(1f, Math.round(2f * s));
        float kcx = x + r + (w - h) * a;
        b.circle(kcx, y + r + Math.max(1f, 0.5f * s), knobR, 0x30000000);
        b.circle(kcx, y + r, knobR, 0xfff4f6f8);
        host.hit(x, y, w, h, action);
    }

    /** Truncates with an ellipsis so the text fits {@code maxWidth} at the given font size (Regular weight). */
    public static String fit(String text, float maxWidth, float size) {
        return fit(text, maxWidth, size, UiDrawCommand.TextWeight.REGULAR);
    }

    /** As {@link #fit(String, float, float)}, but measured in the weight it will actually be drawn in --
     * Semibold runs wider than Regular at the same size, so fitting with the wrong weight under-truncates. */
    public static String fit(String text, float maxWidth, float size, UiDrawCommand.TextWeight weight) {
        if (text == null) return "";
        if (GpuUiFontMetrics.textWidth(text, size, weight) <= maxWidth) return text;
        String ellipsis = "...";
        float room = maxWidth - GpuUiFontMetrics.textWidth(ellipsis, size, weight);
        int end = text.length();
        while (end > 0 && GpuUiFontMetrics.textWidth(text.substring(0, end), size, weight) > room) end--;
        return text.substring(0, Math.max(0, end)).stripTrailing() + ellipsis;
    }

    /** Word-wraps to {@code maxWidth}; the last line is ellipsised when {@code maxLines} cuts the text. */
    public static List<String> wrap(String text, float maxWidth, float size, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxWidth <= 0f) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (GpuUiFontMetrics.textWidth(candidate, size) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (lines.size() == maxLines) break;
            }
        }
        if (lines.size() < maxLines && line.length() > 0) lines.add(line.toString());
        if (lines.size() >= maxLines) {
            int last = maxLines - 1;
            String joined = lines.get(last);
            boolean truncated = text.trim().length() > String.join(" ", lines).length();
            lines = new ArrayList<>(lines.subList(0, maxLines));
            if (truncated) lines.set(last, fit(joined + "...", maxWidth, size));
        }
        return lines;
    }

    public static void emptyState(UiDisplayList.Builder b, UiHost host, String headline, String detail,
                                  float x, float y, float w, float s) {
        float hs = 12f * s, ds = 10f * s;
        b.text(x + (w - GpuUiFontMetrics.textWidth(headline, hs, UiDrawCommand.TextWeight.SEMIBOLD)) * 0.5f, y, hs, host.mutedColor(), headline,
                UiDrawCommand.TextWeight.SEMIBOLD);
        if (detail != null && !detail.isEmpty()) {
            b.text(x + (w - GpuUiFontMetrics.textWidth(detail, ds)) * 0.5f, y + 18f * s, ds, host.muted2Color(), detail);
        }
    }

    public static int brighten(int color, float amount) {
        int a = color >>> 24;
        int r = Math.min(255, Math.round(((color >> 16) & 255) + 255 * amount));
        int g = Math.min(255, Math.round(((color >> 8) & 255) + 255 * amount));
        int bl = Math.min(255, Math.round((color & 255) + 255 * amount));
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }
}
