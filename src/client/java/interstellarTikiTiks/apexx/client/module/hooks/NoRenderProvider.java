package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.particle.Particle;
import net.minecraft.particle.ParticleEffect;

/** Per-element switches for hiding vanilla overlays, weather, particles and the totem animation. */
public interface NoRenderProvider {
    default boolean hideFireOverlay() { return false; }

    /** Carved pumpkin and other head-slot camera overlays (not the powder-snow frost). */
    default boolean hidePumpkinOverlay() { return false; }

    default boolean hideUnderwaterOverlay() { return false; }

    default boolean hideInWallOverlay() { return false; }

    default boolean hideWeather() { return false; }

    /** The large totem-of-undying item that floats across the screen when your totem pops. */
    default boolean hideTotemAnimation() { return false; }

    /** Client thread. True drops a particle spawned from an effect before it is created. */
    default boolean blockParticle(ParticleEffect effect) { return false; }

    /** Client thread. True drops an already-built particle (block dust and other direct adds). */
    default boolean blockParticle(Particle particle) { return false; }
}
