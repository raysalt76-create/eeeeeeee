package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Full-frame post-processing that runs after the world (and hand) have been rendered, from
 * {@code GameRenderer.renderWorld}'s return.
 *
 * <p>The mixin lives in the loader and the modules in the payload, so the loader must reach them only through this
 * interface (found with {@code ModuleAccess.getEnabledHooks}). A direct static call to a payload class from a
 * per-frame mixin throws {@code NoClassDefFoundError} on the render thread whenever the payload is not loaded yet
 * (sign-in, free edition, failed download) and crashes the game.</p>
 */
public interface WorldRenderEndAware {
    void onWorldRenderEnd(float tickDelta);

    /** Lower runs first, so a later effect sees the earlier one's output. */
    default int worldRenderEndOrder() {
        return 0;
    }
}
