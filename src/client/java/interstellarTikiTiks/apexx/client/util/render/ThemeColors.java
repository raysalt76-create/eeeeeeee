package interstellarTikiTiks.apexx.client.util.render;

import interstellarTikiTiks.apexx.client.theme.AccentState;

import java.awt.Color;

/**
 * Colour-mode resolution shared by the world visuals: "Theme" follows the ClickGUI accent
 * ({@link AccentState}), "Custom" uses the module's colour setting and "Rainbow" cycles the hue.
 */
public final class ThemeColors {
    public static final String THEME = "Theme";
    public static final String CUSTOM = "Custom";
    public static final String RAINBOW = "Rainbow";
    public static final String GRADIENT = "Gradient";

    private ThemeColors() {
    }

    /** The GUI accent, opaque. */
    public static int accent() {
        return 0xFF000000 | (AccentState.accentArgb() & 0x00FFFFFF);
    }

    public static int opaque(Color color) {
        return color == null ? accent() : 0xFF000000 | (color.getRGB() & 0x00FFFFFF);
    }

    /** Seconds on a monotonic clock, wrapped so float precision holds for a long session. */
    public static double seconds() {
        return (System.nanoTime() / 1.0e9) % 100_000.0;
    }

    /**
     * Opaque colour for a mode. {@code phase} offsets the rainbow hue (0..1 is one full cycle) so different
     * points of one effect can differ; it is ignored by the flat modes. Unknown modes fall back to the theme.
     */
    public static int resolve(String mode, Color custom, float phase) {
        if (CUSTOM.equals(mode)) return opaque(custom);
        if (RAINBOW.equals(mode)) return TrailMath.hsv((float) (seconds() * 0.2) + phase, 0.72f, 1.0f);
        return accent();
    }
}
