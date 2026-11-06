package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.gui.DrawContext;

/**
 * Replaces the vanilla 2D crosshair. Called from {@code InGameHud.renderCrosshair} only when vanilla
 * would itself draw the 2D crosshair (first person, not a hidden spectator crosshair, no F3 3D crosshair).
 */
public interface CrosshairProvider {
    /** Draws a custom crosshair. Returns true when it drew one, so the vanilla crosshair is skipped. */
    boolean renderCrosshair(DrawContext context, float tickDelta);
}
