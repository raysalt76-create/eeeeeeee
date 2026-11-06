package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A grounded target far below the shooter (standing on a tower or falling): the throw is scored on
 * the whole box with the landing floor, so a steep pearl is not asked to hit only a band at the top
 * of the head that launch spread and the lead blur carry it over.
 */
class PearlGrappleFarBelowTest {

    private static final double STEP = 0.0703125;

    @Test
    void aTargetFarBelowIsHitOnTheUpperBodyAndTheOwnerLandsAboveTheLegs() {
        for (double below : new double[]{6, 12, 20, 30, 45, 60}) {
            for (boolean falling : new boolean[]{false, true}) {
                double rate = run(below, falling);
                org.junit.jupiter.api.Assertions.assertTrue(rate >= 0.9,
                        "below " + below + " falling " + falling + ": " + rate);
            }
        }
    }

    private double run(double below, boolean falling) {
        double bandStart = PearlGrappleSolver.HEAD_BAND_START;
        Random random = new Random(99);
        int thrown = 0, hit = 0, above = 0, solved = 0, trials = 300;
        double chanceSum = 0;
        for (int n = 0; n < trials; ++n) {
            double horizontal = 2 + random.nextDouble() * 18;
            double bearing = random.nextDouble() * Math.PI * 2;
            double shooterFeet = 100;
            Vec3d movement = falling ? new Vec3d(0, -0.5 - random.nextDouble() * 1.0, 0) : Vec3d.ZERO;
            PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(
                    ThrowPhysics.pearlSpawn(new Vec3d(0.5, shooterFeet, 0.5), 1.62), movement, !falling);
            double tx = 0.5 + Math.sin(bearing) * horizontal;
            double tz = 0.5 + Math.cos(bearing) * horizontal;
            double ty = shooterFeet - below;
            Box full = new Box(tx - 0.3, ty, tz - 0.3, tx + 0.3, ty + 1.8, tz + 0.3);
            Box band = new Box(tx - 0.3, ty + 1.8 * bandStart, tz - 0.3, tx + 0.3, ty + 1.8, tz + 0.3);
            Box[] boxes = new Box[100];
            Arrays.fill(boxes, full);
            Box[] truth = new Box[100];
            Arrays.fill(truth, full);
            PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                    List.of(new PlayerMotionSimulation.Forecast("still", 1.0, boxes)),
                    PearlGrappleSolver.leads(1, 60), bandStart);
            float[] toward = ThrowPhysics.lookAlong(band.getCenter().subtract(thrower.spawn()), 0f);
            PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                    new PearlGrappleSolver.Lattice(toward[0], toward[1], STEP), 200, 80);
            if (shot == null) continue;
            ++solved;
            chanceSum += shot.chance();
            ++thrown;
            double unit = ThrowPhysics.SPREAD * ThrowPhysics.PEARL_POWER;
            PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, shot.yaw(), shot.pitch(), 80);
            double sx = tri(random) * unit, sy = tri(random) * unit, sz = tri(random) * unit;
            int age = PearlGrappleSolver.hitAge(flight, sx, sy, sz, truth, 1, bandStart);
            if (age > 0) ++hit;
        }
        System.out.printf("below %4.0f falling %-5b: solved %3d/%d meanChance %.2f realHit %.0f%%%n",
                below, falling, solved, trials, chanceSum / Math.max(1, solved), 100.0 * hit / Math.max(1, thrown));
        return (double) hit / Math.max(1, thrown);
    }

    private static double tri(Random r) {
        return r.nextDouble() - r.nextDouble();
    }
}
