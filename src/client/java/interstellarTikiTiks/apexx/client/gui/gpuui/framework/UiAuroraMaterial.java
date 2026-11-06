package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Waving northern-lights curtains, tinted by a colour. */
public final class UiAuroraMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_aurora");

    private UiAuroraMaterial() { }

    /**
     * @param tintRgb aurora tint, 0xRRGGBB
     * @param opacity overall strength 0..1
     */
    public static UiMaterial of(int tintRgb, float opacity) {
        return new UiMaterial(SHADER, null, 0xffffffff,
                ((tintRgb >> 16) & 0xff) / 255f, ((tintRgb >> 8) & 0xff) / 255f, (tintRgb & 0xff) / 255f,
                Math.max(0f, Math.min(1f, opacity)));
    }
}
