package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Pure numeric copy of the 1.21.11 server's charged-spear tick ({@code KineticWeaponComponent.usageTick}
 * and {@code ProjectileUtil.collectPiercingCollisions}), so a module can ask "would the server's next
 * evaluation of the state I last sent hit this box, and for how much". No world, no entity, no
 * packets: every input is a number the client already holds, which keeps it unit-testable.
 *
 * <p>Server behaviour mirrored (read from the bytecode, see docs/modules/SPEAR_MACE_DTAP_HARDENING.md):
 * <ul>
 *   <li>the ray runs from {@code eye + headLook * minRange} to
 *       {@code eye + headLook * (maxRange + max(0, movement . headLook))}, movement per tick;</li>
 *   <li>the hit box is the target box grown by the component's hitbox margin;</li>
 *   <li>speeds are per-tick movement times 20 projected on the look vector;</li>
 *   <li>damage is {@code baseAttack + floor(relativeSpeed * damageMultiplier)}.</li>
 * </ul>
 * COMPILED only; not RUNTIME VERIFIED.
 */
public final class SpearKineticModel {

    /** Server contact cooldown per target, in ticks, started on first contact whether or not damage is dealt. */
    public static final int CONTACT_COOLDOWN_TICKS = 10;

    /** The player's base attack damage attribute: the spear adds its speed term to this, not to the item bonus. */
    public static final double BASE_ATTACK_DAMAGE = 1.0;

    private SpearKineticModel() {
    }

    /**
     * True when a target at {@code eyeToBoxDistance} can be touched by the server ray within about two
     * ticks: used to keep the charge in use before the contact estimate itself exists, so a release in
     * the contact window does not end the charge the swap needs.
     */
    public static boolean inStrikeEnvelope(double eyeToBoxDistance, double maxRange, double margin, double speedPerTick) {
        return eyeToBoxDistance <= maxRange + margin + 2.0 * Math.max(0.0, speedPerTick) + 1.0;
    }

    /** Client ticks until the attack charge is full; 0 when it already is. */
    public static int ticksToFullCharge(double progress, double progressPerTick) {
        if (progress >= 1.0 || progressPerTick <= 0.0) {
            return 0;
        }
        return (int) Math.ceil((1.0 - progress) / progressPerTick);
    }

    /** Far end of the server's piercing ray along the look. */
    public static double rayLength(double maxRange, Vec3d movementPerTick, Vec3d look) {
        return maxRange + Math.max(0.0, movementPerTick.dotProduct(look));
    }

    /**
     * True when the server's piercing ray, cast from the sent eye along {@code headLook}, reaches {@code box}
     * grown by {@code margin}. Blocks are not considered here; the caller checks occlusion.
     */
    public static boolean touches(Vec3d eye, Vec3d headLook, Vec3d movementPerTick,
                                  double minRange, double maxRange, double margin, Box box) {
        Vec3d from = eye.add(headLook.multiply(minRange));
        Vec3d to = eye.add(headLook.multiply(rayLength(maxRange, movementPerTick, headLook)));
        Box grown = box.expand(margin);
        return grown.contains(from) || grown.raycast(from, to).isPresent();
    }

    /** Where the ray first enters the grown box, or null when it does not touch. */
    public static Vec3d contactPoint(Vec3d eye, Vec3d headLook, Vec3d movementPerTick,
                                     double minRange, double maxRange, double margin, Box box) {
        Vec3d from = eye.add(headLook.multiply(minRange));
        Vec3d to = eye.add(headLook.multiply(rayLength(maxRange, movementPerTick, headLook)));
        Box grown = box.expand(margin);
        if (grown.contains(from)) {
            return from;
        }
        return grown.raycast(from, to).orElse(null);
    }

    /** Speed along the look in blocks per second from a per-tick movement vector. */
    public static double speedAlong(Vec3d look, Vec3d movementPerTick) {
        return look.dotProduct(movementPerTick) * 20.0;
    }

    /** Relative speed the server feeds the damage condition: own speed minus the target's along the same look, floored at 0. */
    public static double relativeSpeed(double ownSpeed, double targetSpeed) {
        return Math.max(0.0, ownSpeed - targetSpeed);
    }

    /** Damage the server's pierce computes before armor. */
    public static double spearDamage(double relativeSpeed, double damageMultiplier) {
        return BASE_ATTACK_DAMAGE + Math.floor(relativeSpeed * damageMultiplier);
    }

    /** The mace's unscaled fall bonus ({@code MaceItem.getBonusAttackDamage}) plus Density per fallen block. */
    public static double smashBonus(double fallDistance, int densityLevel) {
        double base;
        if (fallDistance <= 3.0) {
            base = 4.0 * fallDistance;
        } else if (fallDistance <= 8.0) {
            base = 12.0 + 2.0 * (fallDistance - 3.0);
        } else {
            base = 22.0 + fallDistance - 8.0;
        }
        return base + 0.5 * densityLevel * fallDistance;
    }

    /** Mace hit as the server computes it when the swap beat the attribute refresh: spear attribute base, cooldown-scaled, plus the smash bonus. */
    public static double swappedMaceDamage(double spearAttributeDamage, double attackProgress,
                                           double fallDistance, int densityLevel) {
        double h = Math.max(0.0, Math.min(1.0, attackProgress));
        return spearAttributeDamage * (0.2 + 0.8 * h * h) + smashBonus(fallDistance, densityLevel);
    }

    /**
     * Damage the mace adds on top of the spear hit if the target's damage invulnerability applies
     * ({@code LivingEntity.damage}: while {@code timeUntilRegen > 10} only the excess over the last hit lands).
     */
    public static double netGainInsideInvulnerability(double maceDamage, double spearDamage) {
        return Math.max(0.0, maceDamage - spearDamage);
    }
}
