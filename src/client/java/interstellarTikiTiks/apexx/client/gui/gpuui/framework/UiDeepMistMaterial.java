package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Layered, self-shadowed volumetric mist with cursor parallax, painted beneath the panes. */
public final class UiDeepMistMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_deep_mist");

    private UiDeepMistMaterial() { }

    /**
     * @param cursorX cursor U over the painted rectangle; outside 0..1 disables parallax
     * @param tintRgb mist tint, 0xRRGGBB
     */
    public static UiMaterial of(float cursorX, float cursorY, boolean parallax, int tintRgb) {
        return new UiMaterial(SHADER, null, 0xffffffff,
                1f, 0.9f, 2.6f, parallax ? 1f : 0f,
                cursorX, cursorY, 0f, 0f,
                ((tintRgb >> 16) & 0xff) / 255f, ((tintRgb >> 8) & 0xff) / 255f, (tintRgb & 0xff) / 255f, 0f);
    }
}
