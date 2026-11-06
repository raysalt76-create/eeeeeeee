package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent flight replay: uses vanilla Box.raycast, never the solver's hit/scoring helpers. */
class PearlGrappleAccuracySweepTest {
    private static final int CASES = Integer.getInteger("grapple.cases", 720);
    private static final int ROLLS = Integer.getInteger("grapple.rolls", 1024);
    private static final int HORIZON = 64;
    private static final String[] KINDS = {"level", "crouching", "below", "above", "runner", "glider"};

    private record Replay(boolean hit, boolean highLanding, boolean overHead) {}

    @Test
    void broadGeometryAndLaunchSpreadSweep() {
        long[][] stats = new long[KINDS.length][10];
        double predicted = 0;
        int solved = 0;
        long started = System.nanoTime();
        for (int n = 0; n < CASES; n++) {
            Random geometry = new Random(0xA9E77L + n * 104729L);
            int kind = n % KINDS.length;
            double distance = 3 + geometry.nextDouble() * 37;
            double bearing = geometry.nextDouble() * Math.PI * 2;
            double height = kind == 2 ? -6 - geometry.nextDouble() * 45
                    : kind == 3 ? 2 + geometry.nextDouble() * 16 : 0;
            boolean airborne = n % 4 == 0;
            Vec3d movement = new Vec3d(geometry.nextDouble() * 0.6 - 0.3,
                    airborne ? -0.2 - geometry.nextDouble() * 1.1 : 0,
                    geometry.nextDouble() * 0.6 - 0.3);
            PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(
                    new Vec3d(0.5, 65.62 - (double) 0.1f, 0.5), movement, !airborne);
            double targetHeight = kind == 1 ? 1.5 : kind == 5 ? 0.6 : 1.8;
            double tx = 0.5 + Math.sin(bearing) * distance;
            double tz = 0.5 + Math.cos(bearing) * distance;
            double heading = geometry.nextDouble() * Math.PI * 2;
            double speed = kind == 4 ? 0.28 : kind == 5 ? 0.8 + geometry.nextDouble() * 0.6 : 0;
            Box[] boxes = new Box[HORIZON + 12];
            for (int tick = 0; tick < boxes.length; tick++) {
                double x = tx + Math.sin(heading) * speed * tick;
                double z = tz + Math.cos(heading) * speed * tick;
                boxes[tick] = new Box(x - 0.3, 64 + height, z - 0.3,
                        x + 0.3, 64 + height + targetHeight, z + 0.3);
            }
            List<PearlGrappleSolver.Lead> leads = PearlGrappleSolver.leads(1, 60);
            // This exercises the same landing preference used by the module.
            PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                    List.of(new PlayerMotionSimulation.Forecast("known path", 1, boxes)), leads,
                    PearlGrappleSolver.HEAD_BAND_START);
            float[] look = ThrowPhysics.lookAlong(boxes[0].getCenter().subtract(thrower.spawn()));
            double step = new double[]{0.0096, 0.0703125, 0.15, 0.6144}[geometry.nextInt(4)];
            PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                    new PearlGrappleSolver.Lattice(look[0], look[1], step), 180, HORIZON);
            stats[kind][0]++;
            boolean intercept = shot != null;
            if (!intercept) {
                shot = PearlGrappleSolver.bestEffort(null, null, null, thrower, target,
                        new PearlGrappleSolver.Lattice(look[0], look[1], step), 180, HORIZON);
            } else {
                stats[kind][1]++;
                solved++;
                predicted += shot.chance();
            }
            org.junit.jupiter.api.Assertions.assertNotNull(shot, "Every scenario must get a throw");
            assertTrue(Float.isFinite(shot.yaw()) && Float.isFinite(shot.pitch()));
            stats[kind][6]++;
            Random rolls = new Random(0xBEEFL + n * 65537L);
            for (int r = 0; r < ROLLS; r++) {
                double u = rolls.nextDouble(), sum = 0;
                int offset = leads.get(leads.size() - 1).offset();
                for (PearlGrappleSolver.Lead lead : leads) {
                    sum += lead.weight();
                    if (u <= sum) { offset = lead.offset(); break; }
                }
                Vec3d spread = new Vec3d(tri(rolls), tri(rolls), tri(rolls)).multiply(0.0172275);
                Replay replay = replay(thrower, shot, spread, boxes, offset, null);
                if (intercept) {
                    stats[kind][2]++;
                    if (replay.hit()) stats[kind][3]++;
                    if (replay.highLanding()) stats[kind][4]++;
                    if (replay.overHead()) stats[kind][5]++;
                }
                stats[kind][7]++;
                if (replay.hit()) stats[kind][8]++;
                if (replay.overHead()) stats[kind][9]++;
            }
        }
        long total = 0, hits = 0, overs = 0, allThrows = 0, allHits = 0, allOvers = 0, attempts = 0;
        for (int k = 0; k < stats.length; k++) {
            long[] s = stats[k];
            System.out.printf("%-10s solved %d/%d replay %,d hit %.2f%% high %.2f%% over %.2f%%%n",
                    KINDS[k], s[1], s[0], s[2], percent(s[3], s[2]), percent(s[4], s[2]), percent(s[5], s[2]));
            total += s[2]; hits += s[3]; overs += s[5];
            System.out.printf("           always-throw %d/%d fallback %d replay %,d hit %.2f%% over %.2f%%%n",
                    s[6], s[0], s[6] - s[1], s[7], percent(s[8], s[7]), percent(s[9], s[7]));
            attempts += s[6]; allThrows += s[7]; allHits += s[8]; allOvers += s[9];
        }
        System.out.printf("TOTAL solved %d/%d replay %,d hit %.3f%% over %.3f%% predicted %.3f%% time %.1fs%n",
                solved, CASES, total, percent(hits, total), percent(overs, total),
                100 * predicted / Math.max(1, solved), (System.nanoTime() - started) / 1e9);
        System.out.printf("ALWAYS-THROW attempts %d/%d fallback %d replay %,d hit %.3f%% over %.3f%%%n",
                attempts, CASES, attempts - solved, allThrows, percent(allHits, allThrows), percent(allOvers, allThrows));
        org.junit.jupiter.api.Assertions.assertEquals(CASES, attempts, "No scenario may be cancelled");
        org.junit.jupiter.api.Assertions.assertEquals((long) CASES * ROLLS, allThrows, "No skipped spread trials");
        // Some above/glide paths are physically unreachable, especially for a falling shooter.
        assertTrue(solved > CASES * 0.75, "Lost too many reachable geometries");
        assertTrue(percent(hits, total) > 75, "Independent replay hit rate regressed");
        assertTrue(percent(stats[3][5], stats[3][2]) < 0.5, "Elevated-target overhead regression");
    }

    @Test
    void farRangeChanceMatchesIndependentMonteCarloRatherThanCoarseGridOptimism() {
        PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(
                ThrowPhysics.pearlSpawn(new Vec3d(0.5, 64, 0.5), 1.62), Vec3d.ZERO, true);
        Box[] boxes = new Box[100];
        java.util.Arrays.fill(boxes, new Box(0.2, 64, 48.2, 0.8, 65.8, 48.8));
        PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("still", 1, boxes)),
                List.of(new PearlGrappleSolver.Lead(0, 1)));
        PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                new PearlGrappleSolver.Lattice(0, 0, 0.0703125), 40, 64);
        org.junit.jupiter.api.Assertions.assertNotNull(shot);
        Random random = new Random(88271);
        int hits = 0, rolls = 16384;
        for (int i = 0; i < rolls; i++) {
            Vec3d spread = new Vec3d(tri(random), tri(random), tri(random)).multiply(0.0172275);
            if (replay(thrower, shot, spread, boxes, 0, null).hit()) hits++;
        }
        double actual = (double) hits / rolls;
        System.out.printf("48-block calibration: predicted %.4f independently replayed %.4f%n", shot.chance(), actual);
        assertTrue(Math.abs(shot.chance() - actual) < 0.035, "Confidence does not match independent throws");
        assertTrue(actual > 0.8, "Far-range accuracy regressed");
    }

    /** Gravity, float drag, triangular launch spread and vanilla raycast, independently replayed. */
    private static Replay replay(PearlGrappleSolver.Thrower thrower, PearlGrappleSolver.Shot shot, Vec3d spread,
                                 Box[] boxes, int offset, Box obstacle) {
        Vec3d direction = ThrowPhysics.rawDirection(ThrowPhysics.serverYaw(shot.yaw()),
                ThrowPhysics.serverPitch(shot.pitch())).normalize();
        Vec3d v = direction.add(spread).multiply(1.5).add(thrower.movement().x,
                thrower.onGround() ? 0 : thrower.movement().y, thrower.movement().z);
        Vec3d pos = thrower.spawn();
        double nearest = Double.POSITIVE_INFINITY;
        boolean over = false;
        for (int age = 1; age <= HORIZON; age++) {
            v = v.add(0, -0.03, 0).multiply((double) 0.99f);
            Vec3d end = pos.add(v);
            Box box = boxes[Math.min(boxes.length - 1, Math.max(0, age + offset))];
            double tolerance = Math.max(0f, Math.min(0.3f, (float) (age - 2) / 20f));
            var block = obstacle == null ? java.util.Optional.<Vec3d>empty() : obstacle.raycast(pos, end);
            var hit = box.expand(tolerance).raycast(pos, end);
            if (block.isPresent() && (hit.isEmpty()
                    || pos.squaredDistanceTo(block.get()) <= pos.squaredDistanceTo(hit.get()))) {
                return new Replay(false, false, false);
            }
            if (hit.isPresent()) return new Replay(true,
                    box.maxY - box.minY < 1.2 || pos.y >= box.minY + (box.maxY - box.minY) * 0.6, false);
            Vec3d c = box.getCenter();
            double horizontalV = v.x * v.x + v.z * v.z;
            double t = horizontalV < 1e-12 ? 0 : Math.clamp(
                    ((c.x - pos.x) * v.x + (c.z - pos.z) * v.z) / horizontalV, 0, 1);
            Vec3d closest = pos.add(v.multiply(t));
            double d = (closest.x - c.x) * (closest.x - c.x) + (closest.z - c.z) * (closest.z - c.z);
            if (d < nearest) {
                nearest = d;
                over = d <= Math.pow(0.3 + tolerance, 2) && closest.y > box.maxY + tolerance;
            }
            pos = end;
        }
        return new Replay(false, false, over);
    }

    private static double tri(Random r) { return r.nextDouble() - r.nextDouble(); }
    private static double percent(long n, long d) { return 100.0 * n / Math.max(1, d); }
}
