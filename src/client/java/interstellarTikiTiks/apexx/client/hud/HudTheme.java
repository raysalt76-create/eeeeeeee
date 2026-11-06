package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiGlass;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;

import java.awt.Color;

/**
 * The HUD palette for the current frame, resolved from the ClickGUI theme (HUD Colour Mode "Follow GUI
 * Theme", the default) or the HUD Style custom colours, and optionally swapped for one widget's own colours
 * while that widget draws ({@link #enter}/{@link #exit}).
 *
 * <p>Liquid Glass keeps the original white-glass HUD exactly (white accent/text, near-black body, white rim);
 * coloured themes tint the accent and the glass rim with the theme accent and take the theme's text/muted
 * colours. {@link #refresh()} runs once at the start of each HUD frame (so a theme change recolours the HUD on
 * the next frame) and allocates nothing unless a colour changed; everything else reads the resolved fields.
 */
public final class HudTheme {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int CLASSIC_MUTED = 0xFFB8B8B8;
    private static final int CLASSIC_SUBTLE = 0xFF858585;
    private static final int CLASSIC_AXIS_X = 0xFFFF7272;
    private static final int CLASSIC_AXIS_Y = 0xFF75D98E;
    private static final int CLASSIC_AXIS_Z = 0xFF75A8FF;

    private static int accent = WHITE;
    private static int text = WHITE;
    private static int muted = CLASSIC_MUTED;
    private static int subtle = CLASSIC_SUBTLE;
    private static int rim = WHITE;
    private static int bodyRgb = UiGlass.DEFAULT_BODY_RGB;
    private static float bodyStrength = 1f;
    private static float blur = UiGlass.DEFAULT_BLUR;
    private static int themeAxisY = WHITE;
    private static int themeAxisZ = CLASSIC_MUTED;
    private static boolean whiteGlass = true;

    // java.awt.Color views for the legacy Renderer path, re-created only when the colour changes.
    private static Color accentColor = Color.WHITE;
    private static Color textColor = Color.WHITE;
    private static Color mutedColor = new Color(CLASSIC_MUTED, true);

    // HUD-wide palette saved while one widget's own colours are swapped in (enter/exit; render thread only).
    private static boolean scoped;
    private static int sAccent, sText, sMuted, sSubtle, sRim, sBodyRgb, sAxisY, sAxisZ;
    private static float sBodyStrength;
    private static boolean sWhiteGlass;
    private static Color sAccentColor, sTextColor, sMutedColor;
    // Single-slot caches for the scoped Color views (no allocation while a widget's colours are unchanged).
    private static Color scopedAccentColor = Color.WHITE, scopedTextColor = Color.WHITE,
            scopedMutedColor = new Color(CLASSIC_MUTED, true);

    private HudTheme() { }

    public static int accent() { return accent; }
    public static int text() { return text; }
    public static int muted() { return muted; }
    public static int subtle() { return subtle; }
    public static int rim() { return rim; }
    public static float blur() { return blur; }
    /** RGB (no alpha) of the HUD background tint, for flat cards that are not glass (scoreboard, schematic). */
    public static int backgroundRgb() { return bodyRgb & 0xFFFFFF; }
    /** True when the HUD shows the original white liquid-glass palette. */
    public static boolean isWhiteGlass() { return whiteGlass; }
    public static Color accentColor() { return accentColor; }
    public static Color textColor() { return textColor; }
    public static Color mutedColor() { return mutedColor; }

    private static Color rimColorCache = new Color(0x46FFFFFF, true);

    /** Rim colour as a java.awt.Color with the given alpha (single-slot cache: no per-frame allocation). */
    public static Color rimColor(int alpha) {
        int argb = rim(alpha);
        Color cached = rimColorCache;
        if (cached.getRGB() != argb) rimColorCache = cached = new Color(argb, true);
        return cached;
    }

    /** Glass body tint for a pane; {@code strength} is the per-widget body multiplier (1 = HUD default). */
    public static int bodyTint(float strength) {
        return UiGlass.argb(0x44 * strength * bodyStrength, bodyRgb);
    }

    /** The rim colour with a caller alpha, for hairlines and dividers that should match the glass edge. */
    public static int rim(int alpha) {
        return ((alpha & 0xFF) << 24) | (rim & 0x00FFFFFF);
    }

    public static int axis(int index, boolean themed) {
        if (!themed) return index == 0 ? CLASSIC_AXIS_X : index == 1 ? CLASSIC_AXIS_Y : CLASSIC_AXIS_Z;
        return index == 0 ? accent : index == 1 ? themeAxisY : themeAxisZ;
    }

    /** Re-resolves the palette. Cheap and allocation-free unless a colour actually changed. */
    public static void refresh() {
        exit(); // never resolve on top of a widget's swapped-in colours
        HudStyleElement style = HUDManager.getInstance().getStyle();
        boolean follow = style == null || style.followTheme();

        UiThemeMaterial.Theme theme = UiThemeMaterial.LIQUID_GLASS;
        int highlight = 0;
        boolean customHighlight = false;
        try {
            if (ClickGUIModule.theme != null) theme = UiThemeMaterial.theme(ClickGUIModule.theme.getCurrentMode());
            if (ClickGUIModule.customHighlight != null && ClickGUIModule.customHighlight.isEnabled()
                    && ClickGUIModule.highlightColor1 != null && ClickGUIModule.highlightColor1.getValue() != null) {
                customHighlight = true;
                highlight = 0xFF000000 | ClickGUIModule.highlightColor1.getValue().getRGB();
            }
        } catch (Throwable ignored) {
            // ClickGUI settings not constructed yet: fall back to Liquid Glass.
        }

        int newAccent, newText, newMuted, newSubtle, newRim, newBody;
        float newStrength;
        boolean glassTheme = theme == UiThemeMaterial.LIQUID_GLASS;
        if (follow) {
            int themeAccent = 0xFF000000 | theme.accent();
            newAccent = customHighlight ? highlight : glassTheme ? WHITE : themeAccent;
            if (style != null && style.customAccent()) newAccent = argb(style.accentColour());
            newText = glassTheme ? WHITE : 0xFF000000 | theme.text();
            newMuted = glassTheme ? CLASSIC_MUTED : 0xFF000000 | theme.muted();
            newSubtle = glassTheme ? CLASSIC_SUBTLE : 0xFF000000 | mix(theme.muted2(), 0x000000, .12f);
            // Liquid Glass is the reference white glass; coloured themes carry their accent into the rim.
            newRim = glassTheme ? WHITE : 0xFF000000 | mix(0xFFFFFF, themeAccent, .45f);
            newBody = glassTheme ? UiGlass.DEFAULT_BODY_RGB
                    : mix(mix(UiGlass.DEFAULT_BODY_RGB, theme.panelBase(), .55f), themeAccent, .06f);
            newStrength = 1f;
        } else {
            newAccent = style == null ? WHITE : argb(style.accentColour());
            newText = style == null ? WHITE : argb(style.textColour());
            Color secondary = style == null ? null : style.secondaryColour();
            newMuted = secondary == null ? 0xFF000000 | mix(newText, 0x000000, .28f) : 0xFF000000 | secondary.getRGB();
            newSubtle = 0xFF000000 | mix(newMuted, 0x000000, .28f);
            newRim = style == null ? WHITE : argb(style.rimColour());
            Color tint = style == null ? null : style.glassTint();
            newBody = tint == null ? UiGlass.DEFAULT_BODY_RGB : tint.getRGB() & 0xFFFFFF;
            newStrength = tint == null ? 1f : tint.getAlpha() / (float) 0x44;
        }
        if (style != null) {
            newStrength *= Math.max(0f, style.glassOpacity());
            blur = Math.max(0f, style.glassBlur());
        } else {
            blur = UiGlass.DEFAULT_BLUR;
        }

        whiteGlass = follow && glassTheme && !customHighlight && (style == null || !style.customAccent());
        if (newAccent != accent) accentColor = new Color(newAccent, true);
        if (newText != text) textColor = new Color(newText, true);
        if (newMuted != muted) mutedColor = new Color(newMuted, true);
        accent = newAccent;
        text = newText;
        muted = newMuted;
        subtle = newSubtle;
        rim = newRim;
        bodyRgb = newBody & 0xFFFFFF;
        bodyStrength = newStrength;
        themeAxisY = 0xFF000000 | mix(newAccent, newText, .55f);
        themeAxisZ = 0xFF000000 | mix(newAccent, newMuted, .70f);
    }

    /**
     * Swaps in {@code element}'s own colours when its "Use Own Colours" (generic set) is on. Returns true when
     * it did; the caller must then call {@link #exit()} once the widget is drawn. Not nestable.
     */
    public static boolean enter(HUDElement element) {
        if (scoped || element == null || !element.usesGenericOwnColours()) return false;
        Color background = element.ownBackground();
        return enter(argb(element.ownAccent()), argb(element.ownText()), argb(element.ownSecondary()),
                background == null ? UiGlass.DEFAULT_BODY_RGB : background.getRGB(),
                background == null ? 1f : background.getAlpha() / (float) 0x44);
    }

    /**
     * Swaps in an explicit widget palette (used for a widget whose colour override lives in its module, e.g.
     * the array list's Custom colour mode). Same contract as {@link #enter(HUDElement)}.
     */
    public static boolean enter(int accentArgb, int textArgb, int secondaryArgb, int backgroundRgb, float backgroundStrength) {
        if (scoped) return false;
        sAccent = accent; sText = text; sMuted = muted; sSubtle = subtle; sRim = rim;
        sBodyRgb = bodyRgb; sBodyStrength = bodyStrength; sAxisY = themeAxisY; sAxisZ = themeAxisZ;
        sWhiteGlass = whiteGlass;
        sAccentColor = accentColor; sTextColor = textColor; sMutedColor = mutedColor;
        scoped = true;

        accent = 0xFF000000 | accentArgb;
        text = 0xFF000000 | textArgb;
        muted = 0xFF000000 | secondaryArgb;
        subtle = 0xFF000000 | mix(muted, 0x000000, .28f);
        rim = 0xFF000000 | mix(0xFFFFFF, accent, .45f);
        bodyRgb = backgroundRgb & 0xFFFFFF;
        bodyStrength = Math.max(0f, backgroundStrength);
        themeAxisY = 0xFF000000 | mix(accent, text, .55f);
        themeAxisZ = 0xFF000000 | mix(accent, muted, .70f);
        whiteGlass = false;
        if (scopedAccentColor.getRGB() != accent) scopedAccentColor = new Color(accent, true);
        if (scopedTextColor.getRGB() != text) scopedTextColor = new Color(text, true);
        if (scopedMutedColor.getRGB() != muted) scopedMutedColor = new Color(muted, true);
        accentColor = scopedAccentColor;
        textColor = scopedTextColor;
        mutedColor = scopedMutedColor;
        return true;
    }

    /** Restores the HUD-wide palette after {@link #enter}; a no-op when nothing is swapped in. */
    public static void exit() {
        if (!scoped) return;
        scoped = false;
        accent = sAccent; text = sText; muted = sMuted; subtle = sSubtle; rim = sRim;
        bodyRgb = sBodyRgb; bodyStrength = sBodyStrength; themeAxisY = sAxisY; themeAxisZ = sAxisZ;
        whiteGlass = sWhiteGlass;
        accentColor = sAccentColor; textColor = sTextColor; mutedColor = sMutedColor;
    }

    /** Readable label colour on top of an accent fill. */
    public static int onAccent(int fill) {
        int r = (fill >> 16) & 0xFF, g = (fill >> 8) & 0xFF, b = fill & 0xFF;
        float luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return luminance > 0.62f ? 0xFF101215 : WHITE;
    }

    static int argb(Color color) {
        return color == null ? WHITE : color.getRGB();
    }

    /** RGB blend of a and b (alpha ignored, result has no alpha). */
    public static int mix(int a, int b, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - u) + ((b >> 16) & 0xFF) * u);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - u) + ((b >> 8) & 0xFF) * u);
        int bl = Math.round((a & 0xFF) * (1 - u) + (b & 0xFF) * u);
        return (r << 16) | (g << 8) | bl;
    }
}
