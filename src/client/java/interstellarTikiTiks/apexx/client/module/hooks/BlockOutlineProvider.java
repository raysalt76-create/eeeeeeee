package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.state.OutlineRenderState;

/**
 * Replaces the vanilla block selection outline. Called from {@code WorldRenderer.renderTargetBlockOutline}
 * (twice per frame: solid and translucent pass) with the outline vanilla is about to draw; {@code null}
 * when vanilla draws none (no block targeted, HUD hidden, adventure rules, ...).
 */
public interface BlockOutlineProvider {
    /** Records what vanilla would outline this frame, so the replacement follows vanilla's own gating. */
    void captureVanillaOutline(OutlineRenderState state);

    /** True to skip vanilla's outline draw. */
    boolean cancelVanillaOutline();
}
