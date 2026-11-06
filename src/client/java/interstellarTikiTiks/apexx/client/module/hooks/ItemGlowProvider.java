package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.entity.ItemEntity;

/**
 * Gives dropped items an entity outline (the glowing-effect outline). Called from
 * {@code ItemEntityRenderer.updateRenderState} on the render thread for every item entity being drawn.
 */
public interface ItemGlowProvider {
    /** Outline colour as 0xRRGGBB, or 0 to leave the item as vanilla has it. */
    int itemGlowColor(ItemEntity item);
}
