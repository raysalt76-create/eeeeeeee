package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/**
 * The flowing-liquid atmosphere (the "Water" Atmosphere mode). A domain-warped fluid field coloured
 * across up to three user-picked colours that the cursor pushes aside and leaves a wake through.
 * Built fresh each frame (like {@link UiFogMaterial}) because its cursor and velocity change every frame.
 *
 * <p>Parameter layout mirrors {@code gpuui_water.fsh}: the renderer fills slot 0 with
 * {@code [width, height, time, delta]}; these custom vec4s become {@code params[1..5]}.
 */
public final class UiWaterMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_water");

    private UiWaterMaterial() { }

    /**
     * @param mu,mv         cursor position in the pane's 0..1 UV (clamped by the caller)
     * @param velX,velY     smoothed cursor velocity in UV units (drives the wake)
     * @param color1..3     ARGB liquid colours (alpha ignored; opacity is a separate control)
     * @param colourCount   how many colours to blend (1..3)
     * @param opacity       0..1 overall strength
     * @param flowSpeed     1.0 = default flow speed
     * @param mouseRipple   whether the cursor pushes the liquid
     */
    public static UiMaterial of(float mu, float mv, float velX, float velY,
                                int color1, int color2, int color3, int colourCount,
                                float opacity, float flowSpeed, boolean mouseRipple) {
        float[] c1 = rgb(color1), c2 = rgb(color2), c3 = rgb(color3);
        return new UiMaterial(SHADER, null, 0xffffffff,
                mu, mv, velX, velY,                                         // params[1]
                c1[0], c1[1], c1[2], clamp01(opacity),                      // params[2]
                c2[0], c2[1], c2[2], Math.max(1f, Math.min(3f, colourCount)),// params[3]
                c3[0], c3[1], c3[2], Math.max(0.05f, flowSpeed),            // params[4]
                mouseRipple ? 1f : 0f, 0f, 0f, 0f);                         // params[5]
    }

    private static float[] rgb(int argb) {
        return new float[] {
                ((argb >> 16) & 0xff) / 255f,
                ((argb >> 8) & 0xff) / 255f,
                (argb & 0xff) / 255f
        };
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
