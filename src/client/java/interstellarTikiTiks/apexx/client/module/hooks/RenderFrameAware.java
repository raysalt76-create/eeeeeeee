package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Runs combat/input logic directly from Minecraft's render loop.
 *
 * <p>This is deliberately separate from the HUD render callback: HUD rendering can be
 * suppressed or replaced by client-side render mods while the game itself continues to
 * render normally. Implementations must not draw from this callback.</p>
 */
public interface RenderFrameAware {
    void onRenderFrame();
}
