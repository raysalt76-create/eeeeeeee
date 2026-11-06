package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Pure 1.21.11 mace / melee damage math (no Minecraft types, unit tested).
 *
 * <p>Read from the 1.21.11 bytecode: {@code MaceItem.shouldDealAdditionalDamage} is
 * {@code fallDistance > 1.5 && !isGliding}; {@code MaceItem.getBonusAttackDamage} is the piecewise
 * 4/block (first 3), 2/block (3..8), 1/block (beyond 8) curve plus Density's
 * {@code smash_damage_per_fallen_block} (0.5 per level) times the fall distance; and
 * {@code DamageUtil.getDamageLeft} clamps {@code armor - damage / (2 + toughness / 4)} to
 * {@code [armor / 5, 20]}, divides by 25 and applies the weapon's {@code armor_effectiveness}
 * (Breach: -0.15 per level, clamped to [0, 1]).</p>
 */
public final class MaceDamageMath {

    /** Fall distance a smash needs before the bonus applies at all. */
    public static final double SMASH_MIN_FALL = 1.5;
    public static final double DENSITY_PER_LEVEL_PER_BLOCK = 0.5;
    public static final double BREACH_PER_LEVEL = 0.15;
    public static final float CRIT_MULTIPLIER = 1.5f;

    private MaceDamageMath() {
    }

    /** True when a hit at this fall distance counts as a smash. */
    public static boolean isSmash(double fallDistance, boolean gliding) {
        return fallDistance > SMASH_MIN_FALL && !gliding;
    }

    /** Vanilla smash bonus without enchantments. 0 below the smash threshold. */
    public static double smashBonus(double fallDistance) {
        if (!(fallDistance > SMASH_MIN_FALL)) return 0.0;
        double f = fallDistance;
        if (f <= 3.0) return 4.0 * f;
        if (f <= 8.0) return 12.0 + 2.0 * (f - 3.0);
        return 22.0 + (f - 8.0);
    }

    /** Smash bonus including Density (0.5 extra damage per block fallen per level). */
    public static double smashBonus(double fallDistance, int densityLevel) {
        double base = smashBonus(fallDistance);
        if (base <= 0.0) return 0.0;
        return base + DENSITY_PER_LEVEL_PER_BLOCK * Math.max(0, densityLevel) * fallDistance;
    }

    /**
     * Raw damage of a melee hit before the target's armor: the attack attribute scaled by the
     * cooldown curve ({@code 0.2 + p^2 * 0.8}), x1.5 on a crit, then the weapon's bonus damage, which
     * vanilla adds after the crit multiplier.
     */
    public static double rawHit(double attackDamage, float cooldownProgress, boolean crit, double bonusDamage) {
        float p = clamp01(cooldownProgress);
        double f = attackDamage * (0.2 + p * p * 0.8);
        if (crit) f *= CRIT_MULTIPLIER;
        return f + Math.max(0.0, bonusDamage);
    }

    /** Raw smash hit: {@link #rawHit} with the mace bonus at this fall distance. */
    public static double rawSmash(double attackDamage, float cooldownProgress, boolean crit,
                                  double fallDistance, int densityLevel) {
        return rawHit(attackDamage, cooldownProgress, crit, smashBonus(fallDistance, densityLevel));
    }

    /**
     * Damage left after armor ({@code DamageUtil.getDamageLeft}), with Breach lowering armor
     * effectiveness by 0.15 per level. Does not include Protection or Resistance.
     */
    public static double afterArmor(double damage, double armor, double toughness, int breachLevel) {
        if (damage <= 0.0) return 0.0;
        double a = Math.max(0.0, armor);
        double divisor = 2.0 + Math.max(0.0, toughness) / 4.0;
        double effective = clamp(a - damage / divisor, a * 0.2, 20.0);
        double reduction = effective / 25.0;
        reduction = clamp(reduction - BREACH_PER_LEVEL * Math.max(0, breachLevel), 0.0, 1.0);
        return damage * (1.0 - reduction);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
