package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/**
 * The one liquid-glass recipe, shared by the HUD widgets and the ClickGUI so both look identical.
 *
 * <p>It is the HUD pill treatment that reads as "real" liquid glass: a light (~27%) dark body tint so the
 * refracted, blurred scene stays visible through the pane, a white sheen gradient, a top highlight and a
 * double rim (bright outer hairline + faint inner hairline). Anything painted on top should stay
 * translucent — an opaque fill over the pane hides the refraction and leaves only the rim.
 */
public final class UiGlass {

    /** HUD default: 16 blur, 0x44 alpha near-black body. */
    public static final float DEFAULT_BLUR = 16f;
    public static final int DEFAULT_BODY_RGB = 0x05080C;

    private UiGlass() {}

    /** Exactly the HUD pane ({@code strength} scales the body tint, {@code opacity} fades the whole pane). */
    public static void pane(UiDisplayList.Builder b, float x, float y, float w, float h,
                            float radius, float opacity, float strength) {
        pane(b, x, y, w, h, radius, opacity, DEFAULT_BLUR, argb(0x44 * strength, DEFAULT_BODY_RGB), 0xFFFFFFFF);
    }

    /**
     * Full-control pane.
     *
     * @param blur      backdrop blur strength
     * @param bodyTint  ARGB tint mixed into the refracted body (alpha = how much it covers the scene)
     * @param rimColor  colour of sheen + rims (normally white; an accent gives a coloured edge)
     */
    public static void pane(UiDisplayList.Builder b, float x, float y, float w, float h, float radius,
                            float opacity, float blur, int bodyTint, int rimColor) {
        pane(b, x, y, w, h, radius, opacity, blur, bodyTint, rimColor, true, true, 1f);
    }

    /**
     * Same recipe with the optional parts switchable (used by the ClickGUI's appearance settings).
     *
     * @param rims       draw the double rim hairlines (the "Outer stroke")
     * @param shadow     draw the soft drop shadow (off for panes that sit inside another pane)
     * @param rimAlpha   scales the sheen and rim intensity (1 = the HUD recipe)
     */
    public static void pane(UiDisplayList.Builder b, float x, float y, float w, float h, float radius,
                            float opacity, float blur, int bodyTint, int rimColor,
                            boolean rims, boolean shadow, float rimAlpha) {
        if (!(w > 0f) || !(h > 0f)) return;
        float o = Math.max(0f, Math.min(1f, opacity));
        float ro = o * Math.max(0f, Math.min(2f, rimAlpha));
        int rim = rimColor & 0x00FFFFFF;
        if (shadow) b.shadow(x, y + 3f, w, h, radius, 12f, 0, tint(0x50000000, o));
        b.glass(x, y, w, h, radius, blur, tint(bodyTint, o));
        b.gradient(x, y, w, h, radius,
                tint(0x30000000 | rim, ro), tint(0x18000000 | rim, ro), tint(0x04000000 | rim, ro), tint(0x0A000000 | rim, ro));
        b.gradient(x + 1f, y + 1f, w - 2f, Math.max(1f, h * .45f), Math.max(0f, radius - 1f),
                tint(0x1A000000 | rim, ro), tint(0x1A000000 | rim, ro), tint(rim, ro), tint(rim, ro));
        if (rims) {
            b.border(x, y, w, h, radius, 0.9f, tint(0x50000000 | rim, ro));
            b.border(x + 1f, y + 1f, w - 2f, h - 2f, Math.max(0f, radius - 1f), 0.6f, tint(0x12000000 | rim, ro));
        }
    }

    public static int argb(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | (rgb & 0xFFFFFF);
    }

    public static int tint(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
