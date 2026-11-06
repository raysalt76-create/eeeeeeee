package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Base for HUD widgets drawn into {@link GpuHudRenderer}'s shared display list (same glass material,
 * theme palette, editor preview and SS-Proof path as the built-in widgets). Subclasses lay out in layout
 * units at scale 1 from {@link #getX()}/{@link #getY()} and must call {@link #setWidth}/{@link #setHeight}
 * every frame so the editor hitbox matches. Widgets start disabled.
 */
public abstract class GpuWidget extends HUDElement {
    protected GpuWidget(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        super.setEnabled(false); // non-virtual: subclasses (Notifications) must not run overrides mid-construction
    }

    /**
     * Appends this widget. {@code opacity} is 1 when enabled and dimmed for a disabled editor preview;
     * {@code editorPreview} is true inside the HUD editor (draw placeholder data when there is no world).
     */
    public abstract void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                                   float opacity, boolean editorPreview);

    /** Drawing is batched by GpuHudRenderer. */
    @Override
    public final void render(DrawContext context, float delta) { }

    /** Records the measured size and draws the standard widget card (glass, or the dark "off" card). */
    protected final void panel(UiDisplayList.Builder b, float x, float y, float w, float h, float opacity) {
        setWidth(w);
        setHeight(h);
        GpuHudRenderer.apexxPanel(b, x, y, w, h, opacity, isEnabled());
    }

    /** Small uppercase section caption used across the HUD ("LIVE STATUS", "EQUIPMENT"...). */
    protected static void caption(UiDisplayList.Builder b, float x, float y, String text, float opacity) {
        b.text(x, y, 7.2f, GpuHudRenderer.tint(HudTheme.muted(), opacity), text, UiDrawCommand.TextWeight.SEMIBOLD);
    }

    protected static float textWidth(String text, float size) {
        return GpuUiRenderer.textWidth(text == null ? "" : text, size);
    }

    protected static int tint(int color, float alpha) {
        return GpuHudRenderer.tint(color, alpha);
    }
}
