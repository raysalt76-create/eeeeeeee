package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counter-pearl: an enemy pearl is flown (worldless) to a point of its flight, and the chase throw
 * solved for that point must pass through it. The old aim (straight at the enemy pearl) is scored on the
 * same cases to show why it rarely followed.
 */
class PearlChaseSolverTest {

    @Test
    void counterPearlPassesThroughTheEnemyLanding() {
        Random random = new Random(5L);
        int cases = 200;
        int solved = 0;
        int straightClose = 0;
        double worst = 0.0;
        for (int n = 0; n < cases; ++n) {
            boolean falling = random.nextDouble() < 0.3;
            Vec3d movement = falling ? new Vec3d(0.0, -0.3 - random.nextDouble() * 0.5, 0.0) : Vec3d.ZERO;
            Vec3d spawn = ThrowPhysics.pearlSpawn(new Vec3d(0.5, 64, 0.5), 1.62);
            // The enemy throws from somewhere nearby; its pearl's position some ticks in is where it "lands".
            Vec3d enemy = new Vec3d(random.nextGaussian() * 6, 64 + random.nextGaussian(), random.nextGaussian() * 6);
            Vec3d enemyVelocity = ThrowPhysics.launchVelocity(random.nextFloat() * 360f - 180f,
                    (float) (random.nextDouble() * 60 - 45), ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
            PearlSimulation.Path enemyPath = PearlSimulation.ballistic(enemy, enemyVelocity, 1).trace(10 + random.nextInt(25));
            Vec3d landing = enemyPath.positionAt(enemyPath.lastAge());

            PearlChaseSolver.Shot shot = PearlChaseSolver.solve(null, null, spawn, movement, !falling, landing, 0f, 0f);
            assertNotNull(shot);
            // Brute force: the best pitch at the facing yaw, a quarter degree apart.
            float yaw = ThrowPhysics.lookAlong(landing.subtract(spawn))[0];
            double brute = Double.POSITIVE_INFINITY;
            for (float pitch = -89.0f; pitch <= 89.0f; pitch += 0.25f) {
                brute = Math.min(brute, PearlChaseSolver.evaluate(null, null, spawn, movement, !falling, yaw, pitch, landing).error());
            }
            if (brute < 1.5) assertTrue(shot.error() <= brute + 0.25, "case " + n + ": " + shot.error() + " vs brute force " + brute);
            if (brute < 0.5) {
                ++solved;
                worst = Math.max(worst, shot.error());
                assertTrue(shot.error() < 0.5, "case " + n + " misses by " + shot.error());
            }
            float[] straight = ThrowPhysics.lookAlong(landing.subtract(spawn));
            if (PearlChaseSolver.evaluate(null, null, spawn, movement, !falling, straight[0], straight[1], landing).error() < 1.5) {
                ++straightClose;
            }
        }
        System.out.printf("chase: %d reachable, worst miss %.3f blocks; aiming straight at it lands within 1.5 blocks %d/%d%n",
                solved, worst, straightClose, cases);
        assertTrue(solved > cases / 2);
    }
}
