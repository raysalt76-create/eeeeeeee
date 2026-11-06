package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The order of a safe anchor cycle, how many ticks it takes, and when the blast is allowed, as pure
 * functions.
 *
 * <p>One interaction per tick. The shield goes first and the charge right before the explosion, so the
 * anchor is charged (and could be set off by anyone) for a single tick, a failed shield leaves it
 * uncharged, and the charge and the explosion share one aim point (no turn between them).</p>
 */
public final class AnchorCycle {

    public enum Step { PLACE, SHIELD, CHARGE, EXPLODE }

    /** Out-Damage Only: the target must take at least this much more than you. */
    public static final double OUT_DAMAGE_MARGIN = 0.5;

    private AnchorCycle() {
    }

    /** The steps still to do, in order. */
    public static List<Step> order(boolean place, boolean shield, boolean charged) {
        List<Step> steps = new ArrayList<>(4);
        if (place) steps.add(Step.PLACE);
        if (shield) steps.add(Step.SHIELD);
        if (!charged) steps.add(Step.CHARGE);
        steps.add(Step.EXPLODE);
        return steps;
    }

    /** Whether a step clicks the anchor itself (charge, explode) rather than another face. */
    static boolean onAnchor(Step step) {
        return step == Step.CHARGE || step == Step.EXPLODE;
    }

    /**
     * Ticks from the first step to the last, counting the tick of each click. With same-tick turns a
     * turn finishes right before its click; without them, a click on a different face than the one
     * before waits one more tick for the look to turn (the look is held on the last click until the
     * next tick starts).
     */
    public static int ticks(List<Step> steps, boolean sameTickTurns) {
        int ticks = 0;
        Step previous = null;
        for (Step step : steps) {
            ticks += 1;
            boolean turn = previous != null && !(onAnchor(previous) && onAnchor(step));
            if (turn && !sameTickTurns) ticks += 1;
            previous = step;
        }
        return ticks;
    }

    /**
     * The blast leaves at least {@code healthBuffer} health, or (Allow Totem Pop) a totem covers the pop:
     * one in the offhand, or the item the anchor is set off with.
     */
    public static boolean selfSafe(double damage, double health, double healthBuffer, boolean allowTotemPop,
                                   boolean totemCovers) {
        if (damage <= 0.0 || damage < health - healthBuffer) return true;
        return allowTotemPop && totemCovers;
    }

    /**
     * Why the blast must not happen now, or null when nothing speaks against it: unsafe for you, or
     * not worth it against enemies. {@code targetDamage} is the most the blast deals to an enemy within
     * Target Range, null with no enemy in range; with none, only a Min Target Damage above 0 asks for
     * one (Out-Damage Only judges nothing without an enemy).
     */
    public static String veto(double selfDamage, double health, double healthBuffer, boolean allowTotemPop,
                              boolean totemCovers, Double targetDamage, String targetName, double minTargetDamage,
                              boolean outDamageOnly) {
        if (!selfSafe(selfDamage, health, healthBuffer, allowTotemPop, totemCovers)) {
            return String.format(Locale.ROOT, "the blast would take %.1f of your %.1f health (Health Buffer %.1f)",
                    selfDamage, health, healthBuffer);
        }
        if (targetDamage == null) {
            return minTargetDamage > 0.0 ? "no enemy within Target Range (Min Target Damage is on)" : null;
        }
        if (targetDamage < minTargetDamage) {
            return String.format(Locale.ROOT, "it would deal only %.1f to %s (Min Target Damage %.1f)", targetDamage,
                    targetName, minTargetDamage);
        }
        if (outDamageOnly && targetDamage < selfDamage + OUT_DAMAGE_MARGIN) {
            return String.format(Locale.ROOT, "it would hurt you (%.1f) as much as %s (%.1f)", selfDamage, targetName,
                    targetDamage);
        }
        return null;
    }

    /**
     * Where the cycle goes when no shield goes down: on to the charge (or straight to the explosion of
     * an anchor already charged) when {@code veto} is null, and nowhere (null: the anchor is left as it
     * is) when the blast is vetoed without a shield.
     */
    public static Step withoutShield(String veto, boolean charged) {
        if (veto != null) return null;
        return charged ? Step.EXPLODE : Step.CHARGE;
    }
}
