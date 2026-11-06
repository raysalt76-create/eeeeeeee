package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;

/**
 * Chams: extra flat-colour model passes submitted by {@code LivingEntityRendererMixin} after the
 * entity's own model, using the renderer's live pose. Called on the render thread once per living
 * entity per frame, so implementations must be cheap and allocation-free.
 */
public interface EntityChamsProvider {
    /** Whether this entity gets chams this frame. */
    boolean chamsApplies(LivingEntityRenderState state);

    /** ARGB drawn with no depth test (shows through walls); alpha 0 skips the pass. */
    int chamsOccludedArgb(LivingEntityRenderState state);

    /** ARGB drawn depth-tested over the visible parts; alpha 0 skips the pass. */
    int chamsVisibleArgb(LivingEntityRenderState state);

    /** Additive ("glowing") blending instead of plain translucency. */
    boolean chamsAdditive();


    /** Expanded low-alpha shells around the model for a glow halo; 0 disables. */
    int chamsGlowLayers();

    /** Outer scale of the glow shells, e.g. 1.08. */
    float chamsGlowScale();
}
