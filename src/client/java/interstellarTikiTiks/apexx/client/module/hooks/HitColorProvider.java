package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;

/** Replaces the red damage flash on hurt entities with a custom tint. Render thread only. */
public interface HitColorProvider {
    /**
     * ARGB tint to multiply into the hurt entity's model in place of the vanilla red overlay, or 0 to
     * leave this entity vanilla. Only called while {@code state.hurt} is true.
     */
    int hitColorArgb(LivingEntityRenderState state);
}
