package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Translucent black Nero Portoro stone with restrained gold mineral veins. */
public final class UiPortoroMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_portoro");
    private static final UiMaterial DEFAULT = create(-1f, -1f, false, 0f);

    private UiPortoroMaterial() { }

    public static UiMaterial neroPortoro() { return DEFAULT; }

    /** Returns the stone surface with optional pointer-local parallax and vein illumination. */
    public static UiMaterial neroPortoro(float cursorX, float cursorY, boolean cursorDepth,
                                         float cursorLight) {
        return create(cursorX, cursorY, cursorDepth, cursorLight);
    }

    private static UiMaterial create(float cursorX, float cursorY, boolean cursorDepth,
                                    float cursorLight) {
        return new UiMaterial(SHADER, null, 0xffffffff,
                0.76f, // keep the dark stone legible while letting the blurred scene show through
                0.62f, // subtle polished-stone variation
                0.78f, // warm-gold fault paths remain legible through the glass layers
                0.34f, // slow glint travel along the mineral seams
                cursorX, cursorY, clamp(cursorLight, 0f, 1f), cursorDepth ? 1f : 0f);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(value, max)) : min;
    }
}
