package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Observes attacks only after vanilla has accepted an entity hit and handed it to the
 * interaction manager. Implementations must never dispatch another attack from this callback.
 */
public interface AttackExecutionAware {
    default void onVanillaEntityAttack(Entity target, Vec3d hitPosition) {
        onVanillaEntityAttack(target);
    }

    /** Compatibility callback for existing attack observers that do not need the contact point. */
    default void onVanillaEntityAttack(Entity target) { }
}
