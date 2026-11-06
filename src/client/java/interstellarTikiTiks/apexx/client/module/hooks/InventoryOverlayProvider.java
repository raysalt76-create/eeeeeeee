package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;

public interface InventoryOverlayProvider {

    boolean shouldCancelVanillaHotbar();

    boolean shouldCancelVanillaInventory();

    boolean handleKeyPress(InventoryScreen screen, KeyInput keyInput);

    void renderInventory(DrawContext context, int mouseX, int mouseY, float tickDelta, InventoryScreen invScreen);
}
