package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.hooks.HudRestyleProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.util.Formatting;

/**
 * Rounded translucent fills built from plain {@link DrawContext#fill} rows, so they layer exactly like
 * vanilla's own HUD quads (the restyled boss bar / scoreboard text is still drawn by vanilla on top).
 * Rows never overlap, so translucent colours stay even.
 */
public final class HudFill {
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    private HudFill() { }

    /** Snapshot of the enabled restyle settings, or null when the restyle module is off. */
    public record Style(boolean scoreboard, boolean bossBars, float opacity, int radius, boolean accentLine,
                        boolean accentBars) { }

    public static Style style() {
        // Torn down: no client visuals. SS Proof: the restyle is drawn into the game frame itself (it is
        // vanilla's own HUD layer), so keep the vanilla look rather than leave a client trace on capture.
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()
                || interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isSsProofOn()) {
            return null;
        }
        HudRestyleProvider provider = ModuleAccess.getEnabledHook(HudRestyleProvider.class);
        if (provider == null) return null;
        // The card look comes from the HUD editor's Colours, like every HUD widget: 100% Glass Opacity is
        // the restyle's classic 0.55 card.
        float opacity = Math.max(0f, Math.min(1f, CARD_OPACITY * hudGlassOpacity()));
        return ModuleCallbackGuard.call(provider, "HUD restyle hook", () -> new Style(
                provider.restyleScoreboard(), provider.restyleBossBars(), opacity, CARD_RADIUS,
                provider.restyleAccentLine(), provider.restyleAccentBossBars()), null);
    }

    private static final float CARD_OPACITY = 0.55f;
    private static final int CARD_RADIUS = 4;

    /** The HUD editor's Glass Opacity as a multiplier (1 = 100%). */
    public static float hudGlassOpacity() {
        HudStyleElement style = HUDManager.getInstance().getStyle();
        return style == null ? 1f : style.glassOpacity();
    }

    public static void rounded(DrawContext context, int x1, int y1, int x2, int y2, int radius, int argb) {
        int w = x2 - x1, h = y2 - y1;
        if (context == null || w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) {
            context.fill(x1, y1, x2, y2, argb);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * (double) r - dy * dy)));
            context.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, argb);
            context.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, argb);
        }
        if (h > 2 * r) context.fill(x1, y1 + r, x2, y2 - r, argb);
    }

    private static int background(float opacity) {
        // The HUD background colour (ClickGUI theme, or the HUD editor's Custom colours).
        return (Math.round(opacity * 255f) << 24) | HudTheme.backgroundRgb();
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Scoreboard sidebar card: one rounded panel covering the title band and body (vanilla's two fills),
     * plus an optional accent rule under the title. {@code titleBottom} is Integer.MIN_VALUE when unknown.
     */
    public static void scoreboard(DrawContext context, Style style, int x1, int top, int x2, int bottom,
                                  int titleBottom) {
        rounded(context, x1 - 1, top - 1, x2 + 1, bottom + 1, style.radius(), background(style.opacity()));
        if (style.accentLine() && titleBottom != Integer.MIN_VALUE && titleBottom > top && titleBottom < bottom) {
            context.fill(x1 + 2, titleBottom - 1, x2 - 2, titleBottom, withAlpha(HudTheme.accent(), 0xD0));
        }
    }

    /** Boss bar at vanilla's 182x5 slot: a rounded backdrop behind name + bar, a track and the progress. */
    public static void bossBar(DrawContext context, Style style, int x, int y, BossBar bar) {
        float percent = bar == null ? 0f : Math.max(0f, Math.min(1f, bar.getPercent()));
        int colour = HudTheme.accent();
        if (!style.accentBars() && bar != null && bar.getColor() != null) {
            Formatting format = bar.getColor().getTextFormat();
            Integer value = format == null ? null : format.getColorValue();
            if (value != null) colour = 0xFF000000 | value;
        }
        int r = Math.min(style.radius(), 3);
        rounded(context, x - 4, y - 11, x + BAR_WIDTH + 4, y + BAR_HEIGHT + 3, style.radius(),
                background(style.opacity()));
        rounded(context, x, y, x + BAR_WIDTH, y + BAR_HEIGHT, r, withAlpha(0x000000, 0x90));
        int fill = Math.round(BAR_WIDTH * percent);
        if (fill > 0) rounded(context, x, y, x + fill, y + BAR_HEIGHT, Math.min(r, fill / 2), withAlpha(colour, 0xFF));
    }
}
