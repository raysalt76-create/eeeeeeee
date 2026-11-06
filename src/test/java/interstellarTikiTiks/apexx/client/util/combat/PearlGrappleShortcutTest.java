package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grapple solver's speed-ups must not change a single answer: the spread envelope pruning and the
 * world corridor are exact shortcuts. Random throwers and moving targets are solved and scored with
 * and without them, and every shot (look, chance, hit tick) has to match bit for bit.
 */
class PearlGrappleShortcutTest {

    private static final int SCENARIOS = 60;

    @AfterEach
    void restore() {
        PearlGrappleSolver.pruneSpread = true;
        PearlGrappleSolver.useCorridor = true;
    }

    private record Scenario(PearlGrappleSolver.Thrower thrower, PearlGrappleSolver.Target target, float yaw, float pitch) {}

    private static Scenario scenario(Random random) {
        boolean falling = random.nextBoolean();
        Vec3d movement = falling ? new Vec3d(random.nextGaussian() * 0.05, -0.2 - random.nextDouble(), random.nextGaussian() * 0.05)
                : Vec3d.ZERO;
        PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(
                ThrowPhysics.pearlSpawn(new Vec3d(0.5, falling ? 100 : 64, 0.5), 1.62), movement, !falling);
        double bearing = Math.toRadians(random.nextDouble() * 120 - 60);
        double distance = 6 + random.nextDouble() * 26;
        Vec3d start = new Vec3d(0.5 + Math.sin(bearing) * distance, 60 + random.nextDouble() * 12,
                0.5 + Math.cos(bearing) * distance);
        List<PlayerMotionSimulation.Forecast> forecasts = new ArrayList<>();
        int kinds = 1 + random.nextInt(5);
        for (int k = 0; k < kinds; ++k) {
            double heading = random.nextDouble() * Math.PI * 2;
            double speed = random.nextDouble() * 0.3;
            double vy = random.nextBoolean() ? 0.0 : -random.nextDouble() * 0.6;
            Box[] boxes = new Box[80];
            for (int i = 0; i < boxes.length; ++i) {
                Vec3d at = start.add(Math.sin(heading) * speed * i, vy * i, Math.cos(heading) * speed * i);
                boxes[i] = new Box(at.x - 0.3, at.y, at.z - 0.3, at.x + 0.3, at.y + 1.8, at.z + 0.3);
            }
            forecasts.add(new PlayerMotionSimulation.Forecast("f" + k, 1.0 / kinds, boxes));
        }
        PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(forecasts,
                PearlGrappleSolver.leads(random.nextInt(2), random.nextDouble() * 150),
                random.nextBoolean() ? PearlGrappleSolver.HEAD_BAND_START : 0.0);
        float[] toward = ThrowPhysics.lookAlong(forecasts.get(0).boxes()[0].getCenter().subtract(thrower.spawn()), 0f);
        return new Scenario(thrower, target, toward[0], toward[1]);
    }

    @Test
    void envelopePruningLeavesEverySolveUnchanged() {
        Random random = new Random(77L);
        long pruned = 0L;
        long full = 0L;
        int solved = 0;
        for (int n = 0; n < SCENARIOS; ++n) {
            Scenario s = scenario(random);
            PearlGrappleSolver.Lattice lattice = new PearlGrappleSolver.Lattice(s.yaw(), s.pitch(), 0.0703125);
            PearlGrappleSolver.pruneSpread = true;
            long t0 = System.nanoTime();
            PearlGrappleSolver.Shot fast = PearlGrappleSolver.solve(null, null, null, s.thrower(), s.target(), lattice, 34, 56);
            long t1 = System.nanoTime();
            PearlGrappleSolver.pruneSpread = false;
            PearlGrappleSolver.Shot slow = PearlGrappleSolver.solve(null, null, null, s.thrower(), s.target(), lattice, 34, 56);
            long t2 = System.nanoTime();
            pruned += t1 - t0;
            full += t2 - t1;
            assertEquals(slow == null, fast == null, "scenario " + n);
            if (slow == null) continue;
            ++solved;
            assertEquals(slow.yaw(), fast.yaw(), 0.0f, "yaw, scenario " + n);
            assertEquals(slow.pitch(), fast.pitch(), 0.0f, "pitch, scenario " + n);
            assertEquals(slow.chance(), fast.chance(), 1.0e-12, "chance, scenario " + n);
            assertEquals(slow.hitAge(), fast.hitAge(), "hit tick, scenario " + n);
        }
        System.out.printf("grapple solve: %d/%d solved, pruned %.2f ms vs full %.2f ms per solve%n", solved, SCENARIOS,
                pruned / 1.0e6 / SCENARIOS, full / 1.0e6 / SCENARIOS);
        assertTrue(solved > 0, "no scenario solved");
    }

    /**
     * A stand-in world: a floor at {@code floorY} ends any flight whose sweep goes below it. The
     * corridor clears exactly the rolls that never reach it, as the real one clears rolls in empty air.
     */
    @Test
    void corridorAndPruningGiveTheSameTracedScore() {
        Random random = new Random(91L);
        int compared = 0;
        for (int n = 0; n < SCENARIOS * 2; ++n) {
            Scenario s = scenario(random);
            double floorY = 58 + random.nextDouble() * 8;
            PearlGrappleSolver.Corridor corridor = (flights, limits) -> {
                boolean[] clear = new boolean[flights.length];
                for (int i = 0; i < flights.length; ++i) {
                    if (limits[i] <= 0) continue;
                    boolean free = true;
                    for (int age = 1; age <= limits[i]; ++age) {
                        if (flights[i].ty[age] < floorY || flights[i].fy[age] < floorY) {
                            free = false;
                            break;
                        }
                    }
                    clear[i] = free;
                }
                return clear;
            };
            for (float dPitch : new float[]{0f, -4f, 3f}) {
                float pitch = s.pitch() + dPitch;
                PearlGrappleSolver.SpreadFlightTracer tracer = (spread, limit) -> floored(s, pitch, spread, limit, floorY);
                PearlGrappleSolver.pruneSpread = false;
                PearlGrappleSolver.Shot reference = PearlGrappleSolver.evaluateTraced(s.thrower(), s.target(), s.yaw(),
                        pitch, 56, tracer, null);
                PearlGrappleSolver.pruneSpread = true;
                PearlGrappleSolver.Shot fast = PearlGrappleSolver.evaluateTraced(s.thrower(), s.target(), s.yaw(),
                        pitch, 56, tracer, corridor);
                assertEquals(reference == null, fast == null, "scenario " + n);
                if (reference == null) continue;
                ++compared;
                assertEquals(reference.chance(), fast.chance(), 1.0e-12, "chance, scenario " + n);
                assertEquals(reference.hitAge(), fast.hitAge(), "hit tick, scenario " + n);
            }
        }
        assertTrue(compared > 0, "no traced score compared");
    }

    private static PearlGrappleSolver.Flight floored(Scenario s, float pitch, Vec3d spread, int limit, double floorY) {
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(s.thrower(), s.yaw(), pitch, spread, limit);
        for (int age = 1; age <= limit; ++age) {
            if (flight.ty[age] < floorY || flight.fy[age] < floorY) {
                flight.end = age;
                break;
            }
        }
        return flight;
    }
}
