package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Free-flight coverage of the catch solve over the whole solver range (pitch -76.5..76.5): which
 * looks have a catch in either order, and that the pitch nudge (default 10 degrees) always reaches one
 * from the band where the exact look has none. World collisions are not modelled here.
 */
class PearlCatchCoverageTest {
    private static final Vec3d FEET = new Vec3d(0.5, 200.0, 0.5);
    private static final double EYE = 1.62;
    private static final float MACRO_PITCH = 76.5f;
    private static final float NUDGE = 10.0f;
    private static final double MIN_SCORE = 0.5;

    private static WindChargeIntercept.Shooter standing() {
        return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(FEET, EYE), Vec3d.ZERO, true);
    }

    /** Best expected catch rate of either order for a pearl thrown along {@code pitch}. */
    private static double best(float pitch) {
        Vec3d launch = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(0f), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
        PearlSimulation.Path pearl = PearlSimulation.ballistic(ThrowPhysics.pearlSpawn(FEET, EYE), launch, 0).trace(100);
        double best = 0.0;
        for (int gap = 1; gap <= 6; ++gap) {
            WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(0f, pitch);
            WindChargeIntercept.Shot after = WindChargeIntercept.solve(null, null, pearl, gap, standing(), 40, reach, false);
            WindChargeIntercept.Shot before = WindChargeIntercept.solveOlder(null, null, pearl, gap, standing(), 40, reach, false);
            if (after != null) best = Math.max(best, after.score());
            if (before != null) best = Math.max(best, before.score());
        }
        return best;
    }

    @Test
    void everySolverPitchHasACatchOrOneWithinTheNudge() {
        StringBuilder uncovered = new StringBuilder();
        int dead = 0;
        for (float pitch = -MACRO_PITCH + 0.5f; pitch < MACRO_PITCH; pitch += 0.5f) {
            if (best(pitch) >= MIN_SCORE) continue;
            ++dead;
            boolean reachable = false;
            for (float d = 1.0f; d <= NUDGE && !reachable; d += 1.0f) {
                reachable = best(pitch + d) >= MIN_SCORE || best(pitch - d) >= MIN_SCORE;
            }
            if (!reachable) uncovered.append(pitch).append(' ');
        }
        assertTrue(uncovered.length() == 0, "no catch within " + NUDGE + " degrees of: " + uncovered);
        assertTrue(dead > 0, "the dead band around 20 degrees down is expected to exist: the nudge covers it");
    }

    @Test
    void theDeadBandSitsNearTwentyDegreesDown() {
        assertTrue(best(20.0f) < MIN_SCORE, "pearl and charge never close at 20 degrees down");
        assertTrue(best(10.0f) >= MIN_SCORE, "pearl first catches a shallow downward throw");
        assertTrue(best(40.0f) >= MIN_SCORE, "charge first catches a steep downward throw");
        assertTrue(best(-45.0f) >= MIN_SCORE, "pearl first catches an upward throw");
    }
}
