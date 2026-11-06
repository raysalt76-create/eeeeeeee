package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.entity.Entity;

import java.awt.Color;

/** Customises the vanilla totem-pop effects (particles, sound, floating totem). Client/render thread only. */
public interface TotemPopVisualProvider {
    /** How many ticks the totem particle emitter runs for this pop; vanilla is 30, 0 spawns none. */
    default int totemParticleTicks(Entity entity, int vanillaTicks) { return vanillaTicks; }

    /** True skips the vanilla totem sound for this pop (the provider may play its own). */
    default boolean suppressTotemSound(Entity entity) { return false; }

    /** Colour forced onto every totem particle, or null for vanilla's yellow/green mix. */
    default Color totemParticleColor() { return null; }

    /** True hides your own floating totem animation. */
    default boolean hideFloatingTotem() { return false; }

    /** Scale applied to your own floating totem animation; 1 is vanilla. */
    default float floatingTotemScale() { return 1.0f; }
}
