package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.math.Vec3d;

/** Client-side replacement of the vanilla explosion particles (the explosion sound and knockback are untouched). */
public interface ExplosionVisualProvider {
    /**
     * Client thread, once per applied explosion packet, before vanilla spawns its explosion particles.
     * Return true to skip vanilla's explosion burst and block-smoke particles for this explosion
     * (the provider draws its own effect instead).
     */
    boolean replaceExplosionVisuals(Vec3d center, float radius);
}
