package interstellarTikiTiks.apexx.client.module.hooks;

import interstellarTikiTiks.apexx.client.render.visuals.WorldSceneVisuals;

/** Payload-to-loader contract for effects evaluated against the captured world color/depth. */
public interface WorldSceneVisualProvider {
    void contributeWorldSceneVisuals(WorldSceneVisuals frame, long nowMillis);
}
