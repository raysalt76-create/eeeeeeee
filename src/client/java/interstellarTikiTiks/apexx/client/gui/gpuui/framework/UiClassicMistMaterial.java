package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Moving vapor layer painted above the Classic palette and beneath its panes. */
public final class UiClassicMistMaterial {
    private static final Identifier SHADER = Identifier.of("dynamic_fps", "core/gpuui_classic_mist");
    private static final UiMaterial APEXX = new UiMaterial(SHADER, null, 0xffffffff, 0.76f, 1f);

    private UiClassicMistMaterial() { }

    public static UiMaterial apexx() { return APEXX; }
}
