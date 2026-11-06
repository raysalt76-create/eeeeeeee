package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Window backgrounds that are physical materials rather than patterns: glass, polished stone, woven
 * carbon, metal, sky and water. They all come from one shader ({@code gpuui_surface}) so they share a
 * single lighting model, and every one of them responds to the same cursor light and parallax as the
 * theme surfaces. {@link #THEME} leaves the menu on its theme material.
 */
public enum UiSurface {
    THEME("Theme", -1, false),
    FROSTED("Frosted Glass", 0, true),
    SMOKED("Smoked Glass", 1, true),
    OBSIDIAN("Obsidian", 2, false),
    CARBON("Carbon Fibre", 3, false),
    TITANIUM("Brushed Titanium", 4, false),
    MARQUINA("Nero Marquina", 5, false),
    AURORA("Aurora", 6, false),
    DEEP_SEA("Deep Sea", 7, false),
    NEBULA("Nebula", 8, false),
    MESH("Mesh Gradient", 9, false);

    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_surface");

    private final String displayName;
    private final int shaderMode;
    private final boolean seeThrough;

    UiSurface(String displayName, int shaderMode, boolean seeThrough) {
        this.displayName = displayName;
        this.shaderMode = shaderMode;
        this.seeThrough = seeThrough;
    }

    public String displayName() { return displayName; }

    /** True for the glass surfaces, which sit over the blurred scene instead of replacing it. */
    public boolean seeThrough() { return seeThrough; }

    public boolean isTheme() { return this == THEME; }

    public static String[] names() {
        return java.util.Arrays.stream(values()).map(UiSurface::displayName).toArray(String[]::new);
    }

    public static UiSurface byName(String name) {
        if (name == null) return THEME;
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (UiSurface surface : values()) {
            if (surface.displayName.toLowerCase(Locale.ROOT).equals(key)) return surface;
        }
        return THEME;
    }

    public static List<UiSurface> selectable() { return List.of(values()); }

    /**
     * @param detail      0..1 amount of fine structure (weave pitch, veining, fracture, sheen)
     * @param motion      0..2 drift speed of the surfaces that move (sky, water, gas)
     * @param cursorU     cursor over the painted rectangle; outside 0..1 disables light follow and parallax
     * @param cursorLight 0..1 how strongly the key light follows the pointer
     * @param depth       true to let the surface shift slightly against the pointer
     * @param accentRgb   0xRRGGBB, used sparingly to keep the surface related to the theme
     * @param grain       0..1 film grain / dither strength
     */
    public UiMaterial material(float detail, float motion, float cursorU, float cursorV, float cursorLight,
                               boolean depth, int accentRgb, float grain) {
        if (isTheme()) throw new IllegalStateException("The theme surface has no surface material");
        return new UiMaterial(SHADER, null, 0xffffffff,
                shaderMode, 1f, clamp(detail, 0f, 1f), clamp(motion, 0f, 2f),
                cursorU, cursorV, clamp(cursorLight, 0f, 1f), depth ? 1f : 0f,
                ((accentRgb >> 16) & 0xff) / 255f, ((accentRgb >> 8) & 0xff) / 255f, (accentRgb & 0xff) / 255f,
                clamp(grain, 0f, 1f));
    }

    /** Static swatch for pickers: neutral light, no pointer. */
    public UiMaterial preview(int accentRgb) {
        return material(0.6f, 0.6f, -2f, -2f, 0f, false, accentRgb, 0.35f);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
