package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The menu themes. Every theme is an opaque surface: Granite and Nero Portoro use their own stone
 * shaders and the rest share one brushed-metal shader tinted by the theme's base, shadow and sheen
 * colours. There is no cursor-follow light or parallax on any of them.
 */
public final class UiThemeMaterial {
    private static final Identifier METAL = Identifier.of("dynamic_fps", "core/gpuui_apexx_metal");
    private static final Identifier GRANITE_SHADER = Identifier.of("dynamic_fps", "core/gpuui_granite");
    private static final Identifier PORTORO_SHADER = Identifier.of("dynamic_fps", "core/gpuui_portoro");

    /**
     * @param accent      opaque ARGB accent
     * @param panelBase   RGB used as the base for generated module-list colors
     * @param deep        RGB of the near-black wash laid over the surface behind panes
     * @param tint        RGB of hairlines and borders
     * @param metalHigh   brushed-metal base at the lit corner (0..1 RGB) — unused by stone themes
     * @param metalLow    brushed-metal base in shadow — unused by stone themes
     * @param metalSheen  sheen color — unused by stone themes
     */
    public record Theme(String name, boolean stone, int accent, int panelBase, int deep, int tint,
                        int text, int muted, int muted2, int toggleOff, int outer,
                        int logoLight, int logoDark,
                        float[] metalHigh, float[] metalLow, float[] metalSheen) { }

    /** Clean, translucent and accent-driven — no mineral texture, no fixed palette. The default. */
    // Readable-bright text tiers to sit on the darkened frosted cards (see applyPalette's lg card fill).
    public static final Theme LIQUID_GLASS = new Theme("Liquid Glass", false, 0xff26e0c6, 0x0b0c0e, 0x050506, 0xffffff,
            0xffeef0f1, 0xffb0b5b9, 0xffa0a5a9, 0xff2a2c2f, 0x30ffffff, 0xffeef9f6, 0xff1fb89f,
            new float[] { 0.055f, 0.058f, 0.060f }, new float[] { 0.010f, 0.011f, 0.012f },
            new float[] { 0.065f, 0.095f, 0.090f });
    public static final Theme APEXX = new Theme("Apexx", false, 0xffdee2e6, 0x0c0d0f, 0x050506, 0xffffff,
            0xfff1f3f4, 0xff9ca1a6, 0xff93979a, 0xff2a2c2f, 0x38ffffff, 0xfff3f5f6, 0xff9aa1a6,
            new float[] { 0.060f, 0.062f, 0.066f }, new float[] { 0.012f, 0.012f, 0.014f },
            new float[] { 0.105f, 0.110f, 0.118f });
    // Granite's mineral colour comes from its shader, not this record; forTheme() tints its surface
    // with the live accent colour so changing the accent visibly recolours it. See forTheme().
    public static final Theme GRANITE = new Theme("Granite", true, 0xffd6b26a, 0x0e0c0a, 0x050505, 0xf5e6c8,
            0xfff5f1e8, 0xffa9a397, 0xff9a9690, 0xff2c2a26, 0x66d6b26a, 0xfff6e3b4, 0xffa6823e,
            new float[3], new float[3], new float[3]);
    // Same deal: its own shader supplies the stone, forTheme() tints the surface with the accent.
    public static final Theme NERO_PORTORO = new Theme("Nero Portoro", true, 0xffd6b26a, 0x0a0a0c, 0x030304, 0xe8dcc0,
            0xfff0eee8, 0xffa39f9a, 0xff969491, 0xff2a2927, 0x50d6b26a, 0xfff3e6c0, 0xffa6823e,
            new float[3], new float[3], new float[3]);
    public static final Theme TEAL = new Theme("Teal", false, 0xff2dd4bf, 0x061416, 0x020708, 0xbff7ee,
            0xffeafaf7, 0xff8fb4af, 0xff89a0a1, 0xff1d2b2c, 0x662dd4bf, 0xff8ff5e6, 0xff14958a,
            new float[] { 0.020f, 0.085f, 0.090f }, new float[] { 0.004f, 0.022f, 0.028f },
            new float[] { 0.055f, 0.150f, 0.150f });
    public static final Theme RUBY = new Theme("Ruby", false, 0xfff0486b, 0x18060b, 0x080204, 0xffc8d2,
            0xfffdecef, 0xffb89aa1, 0xffa18b91, 0xff2f1d22, 0x66f0486b, 0xffff9db0, 0xffa8143a,
            new float[] { 0.100f, 0.018f, 0.032f }, new float[] { 0.025f, 0.004f, 0.010f },
            new float[] { 0.190f, 0.055f, 0.075f });
    public static final Theme CATPPUCCIN = new Theme("Catppuccin", false, 0xffcba6f7, 0x1e1e2e, 0x11111b, 0xb4befe,
            0xffcdd6f4, 0xffa6adc8, 0xff989aaa, 0xff313244, 0x66cba6f7, 0xfff5c2e7, 0xffb07fe0,
            new float[] { 0.118f, 0.118f, 0.180f }, new float[] { 0.067f, 0.067f, 0.106f },
            new float[] { 0.100f, 0.095f, 0.150f });
    public static final Theme PURPLE = new Theme("Purple", false, 0xffa86eff, 0x0e071c, 0x05020a, 0xdcc8ff,
            0xfff3ecff, 0xffa99bc7, 0xff9a90b1, 0xff241b38, 0x66a86eff, 0xffd2b0ff, 0xff6a35c8,
            new float[] { 0.060f, 0.030f, 0.110f }, new float[] { 0.014f, 0.006f, 0.036f },
            new float[] { 0.140f, 0.085f, 0.235f });
    public static final Theme EMERALD = new Theme("Emerald", false, 0xff34d37d, 0x06140d, 0x020805, 0xc4f5d8,
            0xffebfaf1, 0xff8fb8a0, 0xff8aa194, 0xff1b2c22, 0x6634d37d, 0xff9bf5c2, 0xff138a4c,
            new float[] { 0.020f, 0.080f, 0.050f }, new float[] { 0.004f, 0.022f, 0.013f },
            new float[] { 0.060f, 0.150f, 0.100f });
    public static final Theme OCEAN = new Theme("Ocean", false, 0xff3b82f6, 0x070d18, 0x02040a, 0xc8d8f7,
            0xffeaf1fd, 0xff93a7c4, 0xff8a9ab4, 0xff1b2636, 0x663b82f6, 0xff9dc0ff, 0xff1f5fd6,
            new float[] { 0.020f, 0.045f, 0.100f }, new float[] { 0.004f, 0.012f, 0.028f },
            new float[] { 0.055f, 0.100f, 0.185f });
    public static final Theme SUNSET = new Theme("Sunset", false, 0xffff8c42, 0x18100a, 0x080402, 0xffe0c4,
            0xfffdf0e8, 0xffc4a894, 0xffb19a8a, 0xff2f241b, 0x66ff8c42, 0xffffc08a, 0xffd6611f,
            new float[] { 0.110f, 0.055f, 0.020f }, new float[] { 0.028f, 0.014f, 0.004f },
            new float[] { 0.200f, 0.110f, 0.055f });
    public static final Theme ROSE = new Theme("Rose", false, 0xffff5fa2, 0x18070f, 0x080205, 0xffcadb,
            0xfffdecf3, 0xffc39aab, 0xffb18b9b, 0xff2f1d26, 0x66ff5fa2, 0xffffa6c8, 0xffc81f68,
            new float[] { 0.110f, 0.025f, 0.060f }, new float[] { 0.028f, 0.006f, 0.016f },
            new float[] { 0.200f, 0.065f, 0.120f });
    public static final Theme MIDNIGHT = new Theme("Midnight", false, 0xff5b6cff, 0x08090f, 0x020206, 0xc8ccf7,
            0xffe9ebfd, 0xff9a9ec4, 0xff8f92b4, 0xff1c1e36, 0x665b6cff, 0xffaeb6ff, 0xff2f3fd6,
            new float[] { 0.030f, 0.034f, 0.100f }, new float[] { 0.008f, 0.009f, 0.028f },
            new float[] { 0.085f, 0.095f, 0.200f });
    public static final Theme GOLD = new Theme("Gold", false, 0xffe3b341, 0x14110a, 0x070502, 0xf0e2c0,
            0xfffaf4e6, 0xffbcae92, 0xffac9f87, 0xff2c261b, 0x66e3b341, 0xfff3dca0, 0xffb8891f,
            new float[] { 0.090f, 0.072f, 0.020f }, new float[] { 0.024f, 0.019f, 0.004f },
            new float[] { 0.180f, 0.145f, 0.055f });
    public static final Theme SLATE = new Theme("Slate", false, 0xff8aa0b4, 0x0b0d10, 0x040506, 0xd0d8e0,
            0xffeef1f4, 0xff9aa3ad, 0xff8f98a1, 0xff222831, 0x4c8aa0b4, 0xffc2cdd8, 0xff5a6b7a,
            new float[] { 0.045f, 0.052f, 0.062f }, new float[] { 0.011f, 0.013f, 0.016f },
            new float[] { 0.095f, 0.110f, 0.130f });

    // Liquid Glass first: ModeSetting defaults to the first entry, so this is the default theme.
    private static final List<Theme> THEMES =
            List.of(LIQUID_GLASS, APEXX, GRANITE, NERO_PORTORO, TEAL, RUBY, CATPPUCCIN, PURPLE, EMERALD,
                    OCEAN, SUNSET, ROSE, MIDNIGHT, GOLD, SLATE);

    private UiThemeMaterial() { }

    public static List<Theme> themes() { return THEMES; }

    public static String[] names() {
        return THEMES.stream().map(Theme::name).toArray(String[]::new);
    }

    /** Looks a theme up by name, falling back to Apexx for unknown or null names. */
    public static Theme theme(String name) {
        for (Theme theme : THEMES) if (theme.name().equalsIgnoreCase(name)) return theme;
        return APEXX;
    }

    public static boolean isStone(String theme) {
        return theme(theme).stone();
    }

    /** No cursor-follow light or parallax: every surface paints the same regardless of mouse position. */
    public static UiMaterial forTheme(String themeName) {
        Theme theme = theme(themeName);
        if (theme.stone()) {
            Identifier shader = themeName != null && themeName.equalsIgnoreCase(NERO_PORTORO.name())
                    ? PORTORO_SHADER : GRANITE_SHADER;
            // opacity, crystal detail, gold dust density, vein glow | cursor xy (disabled), light, depth
            return new UiMaterial(shader, null, 0xffffffff,
                    1f, 0.60f, 0.55f, 0.35f,
                    -2f, -2f, 0f, 0f);
        }
        // opacity, brushed grain, sheen strength, sheen drift | cursor xy (disabled), light, depth |
        // lit base rgb | shadow base rgb | sheen rgb
        float[] hi = theme.metalHigh(), lo = theme.metalLow(), sheen = theme.metalSheen();
        return new UiMaterial(METAL, null, 0xffffffff,
                1f, 0.70f, 0.80f, 0.40f,
                -2f, -2f, 0f, 0f,
                hi[0], hi[1], hi[2], 0f,
                lo[0], lo[1], lo[2], 0f,
                sheen[0], sheen[1], sheen[2], 0f);
    }

    /** Static swatch used for theme previews. */
    public static UiMaterial preview(String theme) {
        return forTheme(theme);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(value, max)) : min;
    }
}
