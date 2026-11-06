package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

/**
 * Draws on top of the player's inventory screen, after vanilla has drawn it (the HUD render pass runs
 * underneath screens). Found by enabled-module hook lookup from {@code InventoryScreenMixin}.
 */
public interface ScreenOverlayAware {
    void onInventoryOverlay(DrawContext context, InventoryScreen screen, float delta);
}
