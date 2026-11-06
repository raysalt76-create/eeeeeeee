package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Pure arithmetic for "will the next hit kill me" decisions (AutoTotem pre-totem). No Minecraft types, so
 * it is unit-testable. The caller supplies damage estimates (crystal / anchor / TNT blasts from
 * {@code CrystalUtils}, fall damage from {@link #fallDamage}); this class only decides whether they are lethal.
 */
public final class TotemDanger {

    private TotemDanger() {
    }

    /** Health plus absorption: the pool an incoming hit has to get through. */
    public static float effectiveHealth(float health, float absorption) {
        return Math.max(0.0f, health) + Math.max(0.0f, absorption);
    }

    /**
     * True when {@code incoming} damage plus a safety {@code margin} (hit points) reaches the effective
     * health pool. A non-positive or NaN estimate is never lethal; a dead pool is not "about to die".
     */
    public static boolean isLethal(float health, float absorption, float incoming, float margin) {
        if (!(incoming > 0.0f)) return false;
        float pool = effectiveHealth(health, absorption);
        if (pool <= 0.0f) return false;
        return incoming + Math.max(0.0f, margin) >= pool;
    }

    /**
     * Vanilla fall damage: {@code ceil((distance - safeDistance) * multiplier)}, never negative.
     * {@code distance} is the fall distance at impact (current fall distance plus the drop still to go).
     */
    public static float fallDamage(double distance, double safeDistance, double multiplier) {
        if (!(distance > safeDistance) || !(multiplier > 0.0)) return 0.0f;
        return (float) Math.ceil((distance - safeDistance) * multiplier);
    }

    /**
     * Combines simultaneous blast estimates. Crystals chain-detonate, so blasts in the same window add up;
     * the result is deliberately an upper bound (extra totem equips are harmless, a missed one is not).
     */
    public static float sum(float... estimates) {
        float total = 0.0f;
        if (estimates == null) return total;
        for (float e : estimates) {
            if (e > 0.0f && Float.isFinite(e)) total += e;
        }
        return total;
    }

    /** Danger classes AutoTotem reacts to: nothing, health at or below the threshold, or a predicted lethal hit. */
    public enum Level { SAFE, LOW_HEALTH, PREDICTED_LETHAL }

    /**
     * Classifies the current state. A predicted lethal hit (with {@code margin}) wins over low health, so the
     * caller can tell a pre-totem situation from a merely hurt one.
     */
    public static Level classify(float health, float absorption, float lowHealthThreshold,
                                 float predictedIncoming, float margin) {
        if (isLethal(health, absorption, predictedIncoming, margin)) return Level.PREDICTED_LETHAL;
        float pool = effectiveHealth(health, absorption);
        if (pool > 0.0f && pool <= lowHealthThreshold) return Level.LOW_HEALTH;
        return Level.SAFE;
    }

    /**
     * Elytra wall impact: vanilla deals {@code (speedBefore - speedAfter) * 10 - 3} on a horizontal collision
     * while gliding; a full stop makes the after-speed 0. {@code horizontalSpeed} is blocks per tick.
     */
    public static float elytraImpactDamage(double horizontalSpeed) {
        if (!(horizontalSpeed > 0.0) || !Double.isFinite(horizontalSpeed)) return 0.0f;
        return (float) Math.max(0.0, horizontalSpeed * 10.0 - 3.0);
    }

    /**
     * Upper-bound mace smash damage before armor: base attack damage plus the fall bonus (4 per block for the
     * first 3, 2 per block up to 8, then 1 per block) and Density's 0.5 per level per block. No smash below
     * 1.5 blocks of fall.
     */
    public static float maceSmashDamage(double fallDistance, int densityLevel, double baseDamage) {
        double base = Math.max(0.0, baseDamage);
        if (!(fallDistance > 1.5) || !Double.isFinite(fallDistance)) return (float) base;
        double bonus;
        if (fallDistance <= 3.0) {
            bonus = 4.0 * fallDistance;
        } else if (fallDistance <= 8.0) {
            bonus = 12.0 + 2.0 * (fallDistance - 3.0);
        } else {
            bonus = 22.0 + (fallDistance - 8.0);
        }
        bonus += Math.max(0, densityLevel) * 0.5 * fallDistance;
        return (float) (base + bonus);
    }
}
