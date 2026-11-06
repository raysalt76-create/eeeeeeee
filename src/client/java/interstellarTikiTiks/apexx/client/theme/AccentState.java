package interstellarTikiTiks.apexx.client.theme;

import java.awt.Color;

/**
 * The single source of truth for the client's live accent — so the in-game render
 * layer (arraylist, nametags, ESP, HUD) matches whatever theme is active in the
 * ClickGUI. The menu screen updates the current accent and style here; every HUD
 * and world renderer that uses the theme reads the same state.
 *
 * <p>The setters below only seed a fallback: while the ClickGUI settings exist, {@link #accentArgb()}
 * re-resolves accent, style and theme name from them (at most every 100 ms) and overwrites anything the
 * setters stored. ClickGUI settings are the source of truth.
 *
 * <p>Thread-safe (volatile primitives), zero-alloc on the hot read path.
 */
public final class AccentState {
    private AccentState() {}

    // ARGB. Defaults to the tactical armory accent (#26e0c6) until the GUI reports one.
    private static volatile int accentArgb = 0xFF26E0C6;
    // A coarse style hint the render layer can branch on for glassy vs flat looks.
    private static volatile String style = "flat";
    private static volatile String themeName = "tactical";

    /** Set when the GUI theme changes. hex may be #rrggbb or #aarrggbb. */
    public static void setAccentHex(String hex) {
        Integer argb = parseHex(hex);
        if (argb != null) accentArgb = argb;
    }

    public static void setStyle(String s) {
        if (s != null && !s.isBlank()) style = s.trim().toLowerCase();
    }

    public static void setThemeName(String n) {
        if (n != null && !n.isBlank()) themeName = n.trim().toLowerCase();
    }

    private static volatile long resolvedAtNanos;

    /** Current accent as packed ARGB int (what most Minecraft draw calls want). */
    public static int accentArgb() {
        // Nothing ever called setAccentHex, so the HUD stayed teal whatever theme was picked.
        // Resolve it from the ClickGUI settings (the same rule the menu uses), at most every
        // 100 ms, so it follows the theme even before the menu has been opened this session.
        resolveIfStale();
        return accentArgb;
    }

    private static void resolveIfStale() {
        long now = System.nanoTime();
        if (now - resolvedAtNanos > 100_000_000L) {
            resolvedAtNanos = now;
            resolveFromClickGui();
        }
    }

    /**
     * Accent, theme name and style from the ClickGUI theme. The style is "liquid-glass" for the Liquid
     * Glass theme, "stone" for the mineral themes and "metal" for the rest, so {@link #isGlass()} is
     * truthful for render modules that branch on it.
     */
    private static void resolveFromClickGui() {
        try {
            var custom = interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.customHighlight;
            var color = interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.highlightColor1;
            var theme = interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.theme;
            interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial.Theme material = null;
            if (theme != null) {
                String name = theme.getCurrentMode();
                material = interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial.theme(name);
                // Compare first: the theme rarely changes, so this rarely allocates.
                String resolvedName = material.name();
                if (!resolvedName.equalsIgnoreCase(themeName)) themeName = resolvedName.toLowerCase();
                String resolvedStyle = material == interstellarTikiTiks.apexx.client.gui.gpuui.framework
                        .UiThemeMaterial.LIQUID_GLASS ? "liquid-glass" : material.stone() ? "stone" : "metal";
                style = resolvedStyle;
            }
            if (custom != null && custom.isEnabled() && color != null && color.getValue() != null) {
                accentArgb = 0xFF000000 | color.getValue().getRGB();
            } else if (material != null) {
                accentArgb = 0xFF000000 | material.accent();
            }
        } catch (Throwable ignored) {
            // Settings not constructed yet: keep the last values.
        }
    }

    /** Current accent with a caller-supplied alpha (0-255) — for fills, glows, tints. */
    public static int accentArgb(int alpha) {
        return ((alpha & 0xFF) << 24) | (accentArgb() & 0x00FFFFFF);
    }

    public static Color accentColor() {
        return new Color(accentArgb(), true);
    }

    public static String style() {
        resolveIfStale();
        return style;
    }

    public static boolean isGlass() {
        resolveIfStale();
        return style.contains("glass") || style.contains("liquid") || themeName.contains("glass");
    }

    public static String themeName() {
        resolveIfStale();
        return themeName;
    }

    /**
     * Sleek animated Apexx Silver / Monochrome Chrome theme color.
     * Oscillates with a metallic shine across the white-to-dark silver spectrum.
     */
    public static Color apexxSilverColor() {
        return apexxSilverColor(0L);
    }

    public static Color apexxSilverColor(long offset) {
        double phase = ((System.currentTimeMillis() + offset) % 2500L) / 2500.0 * Math.PI * 2.0;
        float shimmer = (float) (Math.sin(phase) * 0.5 + 0.5);
        int base = (int) (185 + 70 * shimmer);
        int r = Math.min(255, base);
        int g = Math.min(255, (int) (base * 0.98f));
        int b = Math.min(255, (int) (base * 1.04f));
        return new Color(r, g, b);
    }

    public static int apexxSilverArgb(int alpha) {
        Color c = apexxSilverColor();
        return ((alpha & 0xFF) << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    /** Parse #rgb, #rrggbb, or #aarrggbb (with or without the leading '#') to ARGB. */
    private static Integer parseHex(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 3) {
                int r = Integer.parseInt(h.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(h.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(h.substring(2, 3), 16) * 17;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (h.length() == 6) {
                return 0xFF000000 | (int) (Long.parseLong(h, 16) & 0xFFFFFF);
            }
            if (h.length() == 8) {
                return (int) (Long.parseLong(h, 16) & 0xFFFFFFFFL);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
