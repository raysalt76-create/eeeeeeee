package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World-free checks of the maths the pearl landing and follow solvers stand on. */
class PearlSolverMathTest {

    @Test
    void spreadQuadratureMatchesTheTriangularRollMoments() {
        double[] x = ThrowPhysics.SPREAD_NODES;
        double[] w = ThrowPhysics.SPREAD_WEIGHTS;
        double m0 = 0, m1 = 0, m2 = 0, m4 = 0;
        for (int i = 0; i < 3; i++) {
            m0 += w[i];
            m1 += w[i] * x[i];
            m2 += w[i] * x[i] * x[i];
            m4 += w[i] * Math.pow(x[i], 4);
        }
        assertEquals(1.0, m0, 1e-12);
        assertEquals(0.0, m1, 1e-12);
        assertEquals(1.0 / 6.0, m2, 1e-12);
        assertEquals(1.0 / 15.0, m4, 1e-12);
    }

    @Test
    void theClosedFormLaunchReachesItsAimOnTheTickItWasSolvedFor() {
        // The root-finding both solvers share: required() velocity at the speed root puts the pearl at
        // the aim, a share s along its sweep, on pearl tick `age` (flown by the step-by-step simulation).
        Random random = new Random(21);
        int solved = 0;
        for (int trial = 0; trial < 2000; trial++) {
            Vec3d spawn = new Vec3d(0.0, 100.0, 0.0);
            Vec3d move = new Vec3d(random.nextGaussian() * 0.1, 0.0, random.nextGaussian() * 0.1);
            double distance = 3.0 + random.nextDouble() * 30.0;
            double bearing = random.nextDouble() * Math.PI * 2.0;
            Vec3d aim = new Vec3d(Math.cos(bearing) * distance, 100.0 + (random.nextDouble() - 0.6) * 8.0,
                    Math.sin(bearing) * distance);
            for (int age = 6; age <= 60; age++) {
                double previous = PearlGrappleSolver.gap(spawn, move, aim, age, 0.0);
                double previousS = 0.0;
                for (int i = 1; i <= 8; i++) {
                    double s = i / 8.0;
                    double value = PearlGrappleSolver.gap(spawn, move, aim, age, s);
                    if (Math.signum(value) != Math.signum(previous)) {
                        double root = PearlGrappleSolver.bisect(spawn, move, aim, age, previousS, s);
                        Vec3d launch = PearlGrappleSolver.required(spawn, aim, age, root);
                        assertEquals(ThrowPhysics.PEARL_POWER, launch.subtract(move).length(), 1e-6);
                        PearlSimulation simulation = PearlSimulation.ballistic(spawn, launch, 0);
                        PearlSimulation.Segment segment = null;
                        for (int tick = 1; tick <= age; tick++) segment = simulation.step();
                        Vec3d at = segment.from().add(segment.velocity().multiply(root));
                        assertTrue(at.distanceTo(aim) < 1e-6, "age " + age + " miss " + at.distanceTo(aim));
                        solved++;
                    }
                    previousS = s;
                    previous = value;
                }
            }
        }
        assertTrue(solved > 1000, "roots found: " + solved);
    }

    /** Where a ballistic pearl crosses the plane y = floorY (null if it never does within 200 ticks). */
    private static Vec3d crossing(Vec3d spawn, Vec3d velocity, double floorY) {
        PearlSimulation simulation = PearlSimulation.ballistic(spawn, velocity, 0);
        for (int tick = 1; tick <= 200; tick++) {
            PearlSimulation.Segment segment = simulation.step();
            Vec3d to = segment.from().add(segment.velocity());
            if (to.y <= floorY && segment.from().y > floorY) {
                double share = (segment.from().y - floorY) / (segment.from().y - to.y);
                return segment.from().add(segment.velocity().multiply(share));
            }
        }
        return null;
    }

    @Test
    void theThreePointChanceMatchesAMonteCarloOfTheRealLaunchSpread() {
        // A pearl aimed at a floor point 15 blocks out: the chance it lands within r of that point, by the
        // quadratures the solvers use against 400k draws of the real per-axis triangular roll
        // (the difference of two uniforms, as Random#nextTriangular). The two must agree.
        Vec3d spawn = new Vec3d(0.0, 101.5, 0.0);
        float yaw = 0.0f;
        double floorY = 100.0;
        float pitch = -10.0f;
        Vec3d direction = ThrowPhysics.rawDirection(yaw, pitch).normalize();
        Vec3d mean = crossing(spawn, direction.multiply(ThrowPhysics.PEARL_POWER), floorY);
        assertTrue(mean != null);
        Random random = new Random(41);
        int draws = 400_000;
        double[] radii = {0.05, 0.1, 0.2, 0.3, 0.4, 0.5};
        int[] hits = new int[radii.length];
        for (int i = 0; i < draws; i++) {
            Vec3d spread = new Vec3d(
                    (random.nextDouble() - random.nextDouble()) * ThrowPhysics.SPREAD,
                    (random.nextDouble() - random.nextDouble()) * ThrowPhysics.SPREAD,
                    (random.nextDouble() - random.nextDouble()) * ThrowPhysics.SPREAD);
            Vec3d at = crossing(spawn, direction.add(spread).multiply(ThrowPhysics.PEARL_POWER), floorY);
            double miss = at == null ? 1e9 : Math.hypot(at.x - mean.x, at.z - mean.z);
            for (int r = 0; r < radii.length; r++) if (miss <= radii[r]) hits[r]++;
        }
        double worstFine = 0.0;
        double worstCoarse = 0.0;
        for (int r = 0; r < radii.length; r++) {
            double monteCarlo = hits[r] / (double) draws;
            double fine = quadrature(spawn, direction, floorY, mean, radii[r], ThrowPhysics.FINE_POINTS, ThrowPhysics.FINE_WEIGHTS);
            double coarse = quadrature(spawn, direction, floorY, mean, radii[r], ThrowPhysics.COARSE_POINTS, ThrowPhysics.COARSE_WEIGHTS);
            System.out.printf("[spread] r=%.2f monteCarlo=%.4f fine=%.4f coarse=%.4f%n", radii[r], monteCarlo, fine, coarse);
            worstFine = Math.max(worstFine, Math.abs(fine - monteCarlo));
            worstCoarse = Math.max(worstCoarse, Math.abs(coarse - monteCarlo));
        }
        // Measured: the three-node rule is off by about 0.12 at the radius where the spread straddles the
        // region, which is why throws are gated on the 128-point low-discrepancy rule.
        assertTrue(worstFine < 0.05, "fine rule worst error " + worstFine);
        assertTrue(worstCoarse > worstFine, "three nodes " + worstCoarse + " vs fine rule " + worstFine);
    }

    private static double quadrature(Vec3d spawn, Vec3d direction, double floorY, Vec3d mean, double radius,
                                     double[][] points, double[] weights) {
        double total = 0.0;
        for (int i = 0; i < points.length; i++) {
            Vec3d spread = new Vec3d(points[i][0], points[i][1], points[i][2]).multiply(ThrowPhysics.SPREAD);
            Vec3d at = crossing(spawn, direction.add(spread).multiply(ThrowPhysics.PEARL_POWER), floorY);
            double miss = at == null ? 1e9 : Math.hypot(at.x - mean.x, at.z - mean.z);
            if (miss <= radius) total += weights[i];
        }
        return total;
    }

    @Test
    void theFineRuleHasTheTriangularMarginalsAndIsUncorrelated() {
        double[][] p = ThrowPhysics.FINE_POINTS;
        int n = p.length;
        assertEquals(ThrowPhysics.FINE_COUNT, n);
        double[] mean = new double[3];
        double[][] cov = new double[3][3];
        double fourth = 0.0;
        for (double[] point : p) {
            for (int a = 0; a < 3; a++) mean[a] += point[a] / n;
        }
        for (double[] point : p) {
            fourth += Math.pow(point[0], 4) / n;
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) cov[a][b] += (point[a] - mean[a]) * (point[b] - mean[b]) / n;
        }
        for (int a = 0; a < 3; a++) {
            assertEquals(0.0, mean[a], 0.03, "mean " + a);
            assertEquals(1.0 / 6.0, cov[a][a], 0.02, "variance " + a);
            for (int b = a + 1; b < 3; b++) assertEquals(0.0, cov[a][b], 0.02, "covariance " + a + b);
        }
        assertEquals(1.0 / 15.0, fourth, 0.015);
        double weights = 0.0;
        for (double w : ThrowPhysics.FINE_WEIGHTS) weights += w;
        assertEquals(1.0, weights, 1e-12);
        double coarse = 0.0;
        for (double w : ThrowPhysics.COARSE_WEIGHTS) coarse += w;
        assertEquals(1.0, coarse, 1e-12);
    }
}
