package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worldless end-to-end trial of the grapple: random shooters (standing, falling at different
 * speeds) and random targets (sprinting, walking, hopping, strafing, falling, gliding) that change
 * what they do mid-flight. Each scenario observes the target the way the client does (a server
 * update every second tick, a fitted velocity), solves a throw, then replays the real target, a
 * jittered network lead and a random launch spread against that throw and counts the hits.
 *
 * <p>Three runs on the same scenarios: the fit confined to one mode (the previous behaviour), the
 * fit that follows jumps and landings, and a ceiling where the solver is handed the true future.
 * The ceiling shows the solver itself loses almost nothing; what is left is how well the target's
 * next move can be known.</p>
 */
class PearlGrappleMonteCarloTest {

    private static final EntityDimensions STANDING = EntityDimensions.changing(0.6f, 1.8f);
    private static final EntityDimensions GLIDING = EntityDimensions.changing(0.6f, 0.6f);
    private static final double STEP = 0.0703125;
    private static final int HISTORY = 24;
    private static final int TIMELINE = HISTORY + 120;
    private static final double LEAD_SIGMA = 0.5;
    private static final int SCENARIOS = 600;
    private static final long SEED = 1234L;

    private record Outcome(int thrown, int hits, int scenarios) {}

    /** Per kind (ground, air, glide, each steady or changing; strafers): scenarios, thrown, hits. */
    private static int[][] byKind = new int[7][3];
    private static long forecastNanos;
    private static long solveNanos;

    @Test
    void followingJumpsAndLandingsInTheFitHitsMoreThanOneModeAtATime() {
        Outcome oneMode = run(0);
        int[][] oneModeBy = snapshot();
        Outcome followed = run(1);
        int[][] followedBy = snapshot();
        Outcome ceiling = run(2);
        int[][] ceilingBy = snapshot();
        long wideStart = System.nanoTime();
        Outcome wide = run(3);
        double wideMs = (System.nanoTime() - wideStart) / 1.0e6 / SCENARIOS;
        int[][] wideBy = snapshot();
        report("one-mode fit  ", oneModeBy);
        report("mode-aware fit", followedBy);
        report("wide hedge    ", wideBy);
        report("true future   ", ceilingBy);
        print("one-mode fit  ", oneMode);
        print("mode-aware fit", followed);
        print("wide hedge    ", wide);
        print("true future   ", ceiling);
        System.out.printf("wide hedge: %.1f ms per scenario (forecast %.1f ms, solve %.1f ms)%n", wideMs,
                forecastNanos / 1.0e6 / SCENARIOS, solveNanos / 1.0e6 / SCENARIOS);
        assertTrue(followed.hits >= oneMode.hits, "mode-aware " + followed.hits + " vs one-mode " + oneMode.hits);
        assertTrue(wide.hits > followed.hits, "wide hedge " + wide.hits + " vs mode-aware " + followed.hits);
    }

    private static void print(String name, Outcome outcome) {
        System.out.printf("%s: thrown %d, hit %d of %d scenarios (%.1f%%)%n", name, outcome.thrown, outcome.hits,
                outcome.scenarios, 100.0 * outcome.hits / outcome.scenarios);
    }

    private static int[][] snapshot() {
        int[][] copy = new int[7][];
        for (int i = 0; i < 7; ++i) copy[i] = byKind[i].clone();
        return copy;
    }

    private static void report(String name, int[][] by) {
        String[] kinds = {"ground steady", "ground change", "air steady", "air change", "glide steady", "glide change", "strafers"};
        for (int k = 0; k < 7; ++k) {
            System.out.printf("  %s %-13s: %d scenarios, thrown %d, hit %d (%.0f%% of thrown)%n", name, kinds[k], by[k][0],
                    by[k][1], by[k][2], 100.0 * by[k][2] / Math.max(1, by[k][1]));
        }
    }

    private static Outcome run(int variant) {
        int scenarios = SCENARIOS;
        long seed = SEED;
        Random random;
        int thrown = 0;
        int hits = 0;
        byKind = new int[7][3];
        for (int n = 0; n < scenarios; ++n) {
            random = new Random(new SplittableRandom(seed + n * 0x9E3779B97F4A7C15L).nextLong());
            int result = scenario(random, variant);
            if (result >= 0) ++thrown;
            if (result == 1) ++hits;
        }
        return new Outcome(thrown, hits, scenarios);
    }

    /** -1: no throw, 0: thrown and missed, 1: thrown and hit. */
    private static int scenario(Random random, int variant) {
        // The shooter: standing, or falling or rising at some speed.
        boolean falling = random.nextDouble() < 0.4;
        Vec3d movement = falling ? new Vec3d(random.nextGaussian() * 0.05, -0.1 - random.nextDouble() * 1.1,
                random.nextGaussian() * 0.05) : Vec3d.ZERO;
        double shooterFeet = falling ? 90 + random.nextDouble() * 40 : 64;
        PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(
                ThrowPhysics.pearlSpawn(new Vec3d(0.5, shooterFeet, 0.5), 1.62), movement, !falling);

        // The target, somewhere ahead.
        double bearing = Math.toRadians(random.nextDouble() * 120 - 60);
        double distance = 8 + random.nextDouble() * 26;
        double tx = 0.5 + Math.sin(bearing) * distance;
        double tz = 0.5 + Math.cos(bearing) * distance;
        double kind = random.nextDouble();
        PlayerMotionSimulation.Mode mode;
        double floor = shooterFeet - 3 - random.nextDouble() * 6;
        double ty;
        Vec3d velocity;
        float yaw = random.nextFloat() * 360f - 180f;
        boolean sprinting = random.nextDouble() < 0.7;
        double speed = sprinting ? PlayerMotionSimulation.SPRINT_SPEED : PlayerMotionSimulation.WALK_SPEED;
        Vec3d input = new Vec3d(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        float pitch = 0f;
        if (kind < 0.45) {
            mode = PlayerMotionSimulation.Mode.GROUND;
            ty = floor;
            velocity = input.multiply(speed * PlayerMotionSimulation.GROUND_FRICTION);
        } else if (kind < 0.75) {
            mode = PlayerMotionSimulation.Mode.AIR;
            ty = floor + 4 + random.nextDouble() * 30;
            velocity = new Vec3d(input.x * speed * 0.8, -random.nextDouble() * 1.3 + 0.2, input.z * speed * 0.8);
        } else {
            mode = PlayerMotionSimulation.Mode.GLIDING;
            ty = shooterFeet + random.nextDouble() * 30 - 12;
            floor = ty - 60;
            pitch = (float) (random.nextDouble() * 50 - 15);
            velocity = PlayerMotionSimulation.rotationVector(pitch, yaw).multiply(1.0 + random.nextDouble() * 0.8);
        }
        boolean glider = mode == PlayerMotionSimulation.Mode.GLIDING;
        int kindBase = mode == PlayerMotionSimulation.Mode.GROUND ? 0 : mode == PlayerMotionSimulation.Mode.AIR ? 2 : 4;
        boolean hop = !glider && mode == PlayerMotionSimulation.Mode.GROUND && sprinting && random.nextDouble() < 0.3;
        PlayerMotionSimulation.Behavior base = new PlayerMotionSimulation.Behavior(yaw, pitch,
                glider ? Vec3d.ZERO : input, sprinting, glider ? 0.0 : speed, hop, 0);

        // What they do next: nothing, one change somewhere from a few ticks ago to well into the flight,
        // or (on the ground and in the air) strafing: the heading swings 90 degrees back and forth.
        PlayerMotionSimulation.Behavior later = base;
        int change = Integer.MAX_VALUE;
        boolean strafer = !glider && random.nextDouble() < 0.3;
        int period = 4 + random.nextInt(7);
        int phase = random.nextInt(2 * period);
        PlayerMotionSimulation.Behavior left = turned(base, -45f);
        PlayerMotionSimulation.Behavior right = turned(base, 45f);
        if (strafer) {
            velocity = ((phase / period) % 2 == 0 ? left : right).input().multiply(
                    mode == PlayerMotionSimulation.Mode.GROUND ? speed * PlayerMotionSimulation.GROUND_FRICTION : speed * 0.8);
            if (mode == PlayerMotionSimulation.Mode.AIR) velocity = new Vec3d(velocity.x, base == null ? 0 : -random.nextDouble(), velocity.z);
        } else if (random.nextDouble() < 0.6) {
            change = random.nextInt(20) - 5;
            double pick = random.nextDouble();
            if (glider) {
                later = pick < 0.5 ? base.withPitch(pitch + (random.nextBoolean() ? 1 : -1) * (6 + random.nextFloat() * 14))
                        : turned(base, (random.nextBoolean() ? 1 : -1) * (8 + random.nextFloat() * 25));
            } else if (pick < 0.3) {
                later = base.released();
            } else if (pick < 0.75) {
                later = turned(base, (random.nextBoolean() ? 1 : -1) * (15 + random.nextFloat() * 85));
            } else {
                later = base.withHop(!hop);
            }
        }

        int kindIndex = strafer ? 6 : kindBase + (change == Integer.MAX_VALUE ? 0 : 1);

        // The true timeline: index t + HISTORY is the target at tick t (now is 0).
        PlayerMotionSimulation truthSim = new PlayerMotionSimulation(null, null, STANDING, GLIDING, GLIDING).withFloor(floor);
        PlayerMotionSimulation.State[] states = new PlayerMotionSimulation.State[TIMELINE];
        states[0] = new PlayerMotionSimulation.State(new Vec3d(tx, ty, tz), velocity,
                mode == PlayerMotionSimulation.Mode.GROUND, mode);
        for (int i = 1; i < TIMELINE; ++i) {
            int t = i - HISTORY;
            PlayerMotionSimulation.Behavior behavior = t > change ? later : base;
            if (strafer) behavior = (((t + HISTORY + phase) / period) % 2 == 0) ? left : right;
            states[i] = truthSim.step(states[i - 1], behavior, false);
        }

        // What the client has seen: an update every second tick, the newest one or two ticks old.
        int newestAge = random.nextInt(2);
        int newest = -newestAge;
        PlayerMotionSimulation.Mode newestMode = states[newest + HISTORY].mode();
        List<PlayerTrajectoryEngine.PositionUpdate> updates = new ArrayList<>();
        for (int t = newest % 2 == 0 ? -HISTORY : -HISTORY + 1; t <= newest; t += 2) {
            updates.add(new PlayerTrajectoryEngine.PositionUpdate(t, states[t + HISTORY].pos()));
        }
        // As predict does: the unbroken run of this mode (one update of slack), at least two updates.
        boolean groundAir = variant != 0 && newestMode == PlayerMotionSimulation.Mode.GROUND || newestMode == PlayerMotionSimulation.Mode.AIR;
        int since = newest;
        for (int t = newest; t >= -HISTORY; --t) {
            PlayerMotionSimulation.Mode m = states[t + HISTORY].mode();
            if (groundAir ? (m != PlayerMotionSimulation.Mode.GROUND && m != PlayerMotionSimulation.Mode.AIR) : m != newestMode) break;
            since = t;
        }
        // The mode flag arrives with each position update, so a tick sees the mode of the latest update at or before it.
        final int newestFinal = newest;
        java.util.function.LongFunction<PlayerMotionSimulation.Mode> modeAt = tick -> {
            long t = Math.min(tick, newestFinal);
            long seen = t % 2 == newestFinal % 2 ? t : t - 1;
            return states[(int) Math.max(-HISTORY, seen) + HISTORY].mode();
        };
        List<PlayerTrajectoryEngine.PositionUpdate> window = PlayerMotionSimulation.fitWindow(updates,
                Math.max(since - 1, -10));
        boolean moving = window.size() >= 2;
        double rtt = random.nextDouble() * 120;
        int horizon = 60 + 8 + 3;
        Vec3d lastPos = states[newest + HISTORY].pos();
        PlayerMotionSimulation hedge = new PlayerMotionSimulation(null, null, STANDING, GLIDING, GLIDING).withFloor(floor);
        long forecastStart = System.nanoTime();
        List<PlayerMotionSimulation.Forecast> forecasts = PlayerMotionSimulation.forecastFrom(hedge, window, lastPos,
                newestMode, yaw, pitch, sprinting, hop, 0, Long.MAX_VALUE, variant == 0 ? null : modeAt, moving, horizon,
                variant == 3 ? PlayerMotionSimulation.trail(updates, Math.max(since - 1, -HISTORY)) : null);
        if (variant == 3) forecastNanos += System.nanoTime() - forecastStart;
        double expected = newestAge + rtt / 50.0;
        int lead = (int) Math.round(expected + random.nextGaussian() * LEAD_SIGMA);
        Box[] truthBoxes = new Box[horizon + 1];
        for (int i = 0; i < truthBoxes.length; ++i) {
            int index = Math.min(TIMELINE - 1, newest + HISTORY + i);
            truthBoxes[i] = hedge.box(states[index]);
        }
        PearlGrappleSolver.Target target = variant == 2
                ? new PearlGrappleSolver.Target(List.of(new PlayerMotionSimulation.Forecast("truth", 1.0, truthBoxes)),
                        List.of(new PearlGrappleSolver.Lead(lead, 1.0)))
                : new PearlGrappleSolver.Target(forecasts, PearlGrappleSolver.leads(newestAge, rtt));
        ++byKind[kindIndex][0];
        float[] toward = ThrowPhysics.lookAlong(truthBoxes[0].getCenter().subtract(thrower.spawn()), 0f);
        long solveStart = System.nanoTime();
        PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                new PearlGrappleSolver.Lattice(toward[0], toward[1], STEP), 90, 60);
        if (variant == 3) solveNanos += System.nanoTime() - solveStart;
        // The module always throws when any plan exists (a lob otherwise), so every shot counts.
        if (shot == null) return -1;

        ++byKind[kindIndex][1];
        double unit = ThrowPhysics.SPREAD * ThrowPhysics.PEARL_POWER;
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, shot.yaw(), shot.pitch(), 60);
        int age = PearlGrappleSolver.hitAge(flight, triangular(random) * unit, triangular(random) * unit,
                triangular(random) * unit, truthBoxes, lead);
        if (age > 0) ++byKind[kindIndex][2];
        return age > 0 ? 1 : 0;
    }

    /** The behavior with its pressed direction, and the look that goes with it, swung {@code degrees}. */
    private static PlayerMotionSimulation.Behavior turned(PlayerMotionSimulation.Behavior b, float degrees) {
        double radians = Math.toRadians(degrees);
        Vec3d input = b.input();
        Vec3d swung = new Vec3d(input.x * Math.cos(radians) - input.z * Math.sin(radians), 0.0,
                input.x * Math.sin(radians) + input.z * Math.cos(radians));
        return new PlayerMotionSimulation.Behavior(b.yaw() + degrees, b.pitch(), swung, b.sprinting(), b.groundSpeed(),
                b.hop(), b.boostTicks());
    }

    private static double triangular(Random random) {
        return random.nextDouble() - random.nextDouble();
    }
}
