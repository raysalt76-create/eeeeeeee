package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Volumetric-looking drifting fog layer (domain-warped fbm, two parallax depths). */
public final class UiFogMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_fog");

    private UiFogMaterial() { }

    /**
     * @param opacity fog strength 0..1
     * @param speed drift speed 0..2
     * @param scale noise frequency (about 1.5..4)
     * @param groundBias 0 = uniform fog, 1 = thick at the bottom with rising wisps
     * @param tintRgb fog tint, 0xRRGGBB
     */
    public static UiMaterial of(float opacity, float speed, float scale, float groundBias, int tintRgb) {
        return new UiMaterial(SHADER, null, 0xffffffff,
                clamp(opacity, 0f, 1f), clamp(speed, 0f, 2f), scale, clamp(groundBias, 0f, 1f),
                ((tintRgb >> 16) & 0xff) / 255f, ((tintRgb >> 8) & 0xff) / 255f, (tintRgb & 0xff) / 255f, 0f);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
