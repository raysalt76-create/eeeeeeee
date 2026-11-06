package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;

import java.awt.Color;

/** Supplies model-space visual styling to the shared GPU visual engine. */
public interface PlayerModelVisualProvider {
    boolean shouldRenderPlayerModel(LivingEntityRenderState state);

    Color getPlayerVisualColor(LivingEntityRenderState state, long timeMillis);

    float getPlayerVisualOpacity();

    float getPlayerVisualContourScale();

    int getPlayerVisualContourLayers();

    boolean renderPlayerVisualThroughWalls();
}
