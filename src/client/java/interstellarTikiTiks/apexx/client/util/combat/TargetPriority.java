package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.math.MathHelper;

/**
 * Shared target-priority scoring so every combat module (AimAssist, AutoMace, …) ranks enemies the
 * same way. Higher score = more preferred. The weights are tiered so the priority order is, in
 * decreasing importance: LOW HP ≫ NO offhand totem ≫ offhand shield ≫ closer distance.
 *
 * <p>Selection only — this never moves the aim itself; callers still route the chosen target through
 * the smooth rotation engine. Callers are responsible for FOV / range / visibility gating; this just
 * orders the survivors.
 */
public final class TargetPriority {

    private TargetPriority() {
    }

    // Tier weights, chosen so each higher tier generally outranks the ones below it.
    private static final double HP_PER_POINT   = 100.0;  // health/absorption points (0..~40) → up to ~4000
    private static final double NO_TOTEM_BONUS = 2500.0; // no offhand totem = killable = high priority
    private static final double SHIELD_BONUS   = 800.0;  // offhand shield = medium priority
    private static final double DIST_PER_BLOCK = 6.0;    // closer = slightly preferred (lowest tier)

    /** Priority score for {@code target} from {@code self}'s point of view. Higher = pick this one. */
    public static double score(LivingEntity target, PlayerEntity self, double maxRange) {
        if (target == null || self == null) return Double.NEGATIVE_INFINITY;

        double s = 0.0;

        // Low HP — highest priority. Fewer effective HP → higher score.
        double hp = target.getHealth() + target.getAbsorptionAmount();
        s += (40.0 - MathHelper.clamp(hp, 0.0, 40.0)) * HP_PER_POINT;

        // Offhand contents.
        ItemStack off = target.getOffHandStack();
        boolean hasTotem  = off.isOf(Items.TOTEM_OF_UNDYING);
        boolean hasShield = off.getItem() instanceof ShieldItem || off.isOf(Items.SHIELD);
        if (!hasTotem)  s += NO_TOTEM_BONUS; // no totem to pop → prioritize the kill
        if (hasShield)  s += SHIELD_BONUS;   // shielding → worth committing to

        // Distance — lowest priority tiebreaker. Closer scores a little higher.
        double dist = self.getEyePos().distanceTo(target.getBoundingBox().getCenter());
        s += (MathHelper.clamp(maxRange, 0.0, 64.0) - MathHelper.clamp(dist, 0.0, maxRange)) * DIST_PER_BLOCK;

        return s;
    }
}
