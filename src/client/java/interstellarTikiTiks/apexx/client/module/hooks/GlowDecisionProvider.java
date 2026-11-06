package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.entity.Entity;

public interface GlowDecisionProvider {

    /** Blanket glow on every other living player (legacy behaviour). */
    boolean shouldApplyGlow();

    /**
     * Per-entity glow: a non-zero RGB makes this client-side entity glow with that outline colour
     * ({@code Entity.isGlowing} / {@code getTeamColorValue}). 0 leaves the entity alone. Called very
     * often on the render thread, so it must be cheap.
     */
    default int glowColorFor(Entity entity) {
        return 0;
    }
}
