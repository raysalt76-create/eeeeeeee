package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world-free parts of the grapple: target motion, the pearl intercept and the aim solve. */
class PearlGrappleSolverTest {

    private static final double STEP = 0.0703125; // a mid-range mouse-sensitivity step
    private static final EntityDimensions STANDING = EntityDimensions.changing(0.6f, 1.8f);
    private static final EntityDimensions GLIDING = EntityDimensions.changing(0.6f, 0.6f);
    private static final PlayerMotionSimulation WORLDLESS =
            new PlayerMotionSimulation(null, null, STANDING, GLIDING, GLIDING);

    private static PearlGrappleSolver.Thrower throwerAt(double x, double feetY, double z) {
        return new PearlGrappleSolver.Thrower(ThrowPhysics.pearlSpawn(new Vec3d(x, feetY, z), 1.62), Vec3d.ZERO, true);
    }

    @Test
    void boxEntryMatchesVanillaRaycastIncludingStartsInside() {
        Random random = new Random(7);
        for (int n = 0; n < 20000; ++n) {
            double x = random.nextDouble() * 2 - 1;
            double y = random.nextDouble() * 2 - 1;
            double z = random.nextDouble() * 2 - 1;
            Box box = new Box(x, y, z, x + 0.2 + random.nextDouble(), y + 0.2 + random.nextDouble(), z + 0.2 + random.nextDouble());
            Vec3d from = new Vec3d(random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3);
            Vec3d to = new Vec3d(random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3);
            Optional<Vec3d> vanilla = box.raycast(from, to);
            Vec3d d = to.subtract(from);
            double t = PearlGrappleSolver.entry(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    from.x, from.y, from.z, d.x, d.y, d.z);
            assertEquals(vanilla.isPresent(), t >= 0.0, "segment " + n);
            if (vanilla.isPresent()) {
                Vec3d point = from.add(d.multiply(t));
                assertTrue(point.distanceTo(vanilla.get()) < 1e-9, "segment " + n);
            }
        }
    }

    @Test
    void theBallisticFlightIsPearlSimulationTickForTick() {
        PearlGrappleSolver.Thrower thrower = new PearlGrappleSolver.Thrower(new Vec3d(3.25, 70.42, -8.5),
                new Vec3d(0.21, 0.08, -0.13), false);
        float yaw = 37.3f;
        float pitch = -12.6f;
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, yaw, pitch, 60);
        Vec3d launch = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, null, thrower.movement(), thrower.onGround());
        PearlSimulation.Path path = PearlSimulation.ballistic(thrower.spawn(), launch, 0).trace(60);
        for (int age = 1; age <= 60; ++age) {
            PearlSimulation.Segment segment = path.at(age);
            assertEquals(segment.from(), new Vec3d(flight.fx[age], flight.fy[age], flight.fz[age]), "from " + age);
            assertEquals(segment.sweepEnd(), new Vec3d(flight.tx[age], flight.ty[age], flight.tz[age]), "to " + age);
        }
    }

    @Test
    void aLaunchSpreadShiftsTheFlightBySpreadTimesTheDragSeries() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        Vec3d spread = new Vec3d(0.0172, -0.0172, 0.0172);
        Vec3d launch = ThrowPhysics.launchVelocity(10f, -5f, ThrowPhysics.PEARL_POWER, spread, Vec3d.ZERO, true);
        PearlSimulation.Path rolled = PearlSimulation.ballistic(thrower.spawn(), launch, 0).trace(10);
        PearlGrappleSolver.Flight nominal = PearlGrappleSolver.Flight.ballistic(thrower, 10f, -5f, 10);
        // A tiny box on the rolled flight's second sweep (no hit tolerance yet at age 2): the nominal
        // flight shifted by the spread passes through it, the unshifted one does not.
        PearlSimulation.Segment second = rolled.at(2);
        Vec3d middle = second.from().add(second.sweepEnd()).multiply(0.5);
        Box[] boxes = new Box[20];
        Arrays.fill(boxes, new Box(100, 100, 100, 101, 101, 101));
        boxes[2] = new Box(middle.add(-0.004, -0.004, -0.004), middle.add(0.004, 0.004, 0.004));
        Vec3d shift = spread.multiply(ThrowPhysics.PEARL_POWER);
        assertEquals(2, PearlGrappleSolver.hitAge(nominal, shift.x, shift.y, shift.z, boxes, 0));
        assertEquals(-1, PearlGrappleSolver.hitAge(nominal, 0, 0, 0, boxes, 0));
    }

    @Test
    void aSweepStartingInsideTheTargetDoesNotHitIt() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, 0f, 0f, 20);
        Box around = new Box(flight.fx[5] - 1, flight.fy[5] - 1, flight.fz[5] - 0.2,
                flight.fx[5] + 1, flight.fy[5] + 1, flight.fz[5] + 0.2);
        Box[] boxes = new Box[40];
        Arrays.fill(boxes, around);
        // Tick 4's sweep enters the box; with the box only on tick 5 (index 5), the pearl starts inside.
        Box[] onlyFive = new Box[40];
        Arrays.fill(onlyFive, new Box(100, 100, 100, 101, 101, 101));
        onlyFive[5] = around;
        assertEquals(4, PearlGrappleSolver.hitAge(flight, 0, 0, 0, boxes, 0));
        assertEquals(-1, PearlGrappleSolver.hitAge(flight, 0, 0, 0, onlyFive, 0));
    }

    @Test
    void leadWeightsSumToOneAroundTheExpectedLead() {
        for (double since = 0; since <= 3; since += 1) {
            for (double rtt = 0; rtt <= 300; rtt += 37) {
                List<PearlGrappleSolver.Lead> leads = PearlGrappleSolver.leads(since, rtt);
                double total = 0;
                double mean = 0;
                for (PearlGrappleSolver.Lead lead : leads) {
                    total += lead.weight();
                    mean += lead.offset() * lead.weight();
                }
                assertEquals(1.0, total, 1e-9);
                assertEquals(since + rtt / 50.0, mean, 1e-9);
            }
        }
    }

    @Test
    void aGlideStepIsVanillaCalcGlidingVelocity() {
        // Facing +x level at 1 block/tick: lift from the falling component, then the 0.99/0.98 drag.
        Vec3d next = PlayerMotionSimulation.glide(new Vec3d(1, 0, 0), -90f, 0f);
        assertEquals(0.991782, next.x, 1e-5);
        assertEquals(-0.01764, next.y, 1e-5);
        assertEquals(0.0, next.z, 1e-5);
        // Climbing trades speed for height; diving gains speed.
        Vec3d climb = PlayerMotionSimulation.glide(new Vec3d(1.5, 0, 0), -90f, -30f);
        Vec3d dive = PlayerMotionSimulation.glide(new Vec3d(1.5, 0, 0), -90f, 40f);
        assertTrue(climb.y > 0 && climb.horizontalLength() < 1.5);
        assertTrue(dive.length() > 1.48 && dive.y < 0);
    }

    @Test
    void theFitRecoversAGlidersVelocityFromEverySecondTick() {
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(-80f, 8f, Vec3d.ZERO,
                false, 0.0, false, 0);
        PlayerMotionSimulation.State truth = new PlayerMotionSimulation.State(new Vec3d(10, 120, -4),
                new Vec3d(1.35, -0.12, 0.2), false, PlayerMotionSimulation.Mode.GLIDING);
        List<PlayerTrajectoryEngine.PositionUpdate> updates = new ArrayList<>();
        updates.add(new PlayerTrajectoryEngine.PositionUpdate(100, truth.pos()));
        PlayerMotionSimulation.State state = truth;
        for (int tick = 101; tick <= 110; ++tick) {
            state = WORLDLESS.step(state, behavior, false);
            if (tick % 2 == 0) updates.add(new PlayerTrajectoryEngine.PositionUpdate(tick, state.pos()));
        }
        PlayerMotionSimulation.State fitted = PlayerMotionSimulation.fit(updates, PlayerMotionSimulation.Mode.GLIDING,
                behavior, Long.MAX_VALUE);
        assertEquals(state.pos(), fitted.pos());
        assertTrue(fitted.velocity().distanceTo(state.velocity()) < 1e-6, fitted.velocity() + " vs " + state.velocity());
    }

    @Test
    void theFitRecoversAnAirborneSprintersVelocity() {
        Vec3d input = new Vec3d(1, 0, 0);
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(-90f, 0f, input, true,
                PlayerMotionSimulation.SPRINT_SPEED, false, 0);
        PlayerMotionSimulation.State truth = new PlayerMotionSimulation.State(new Vec3d(0, 80, 0),
                new Vec3d(0.45, 0.3, 0.02), false, PlayerMotionSimulation.Mode.AIR);
        List<PlayerTrajectoryEngine.PositionUpdate> updates = new ArrayList<>();
        updates.add(new PlayerTrajectoryEngine.PositionUpdate(0, truth.pos()));
        PlayerMotionSimulation.State state = truth;
        for (int tick = 1; tick <= 6; ++tick) {
            state = WORLDLESS.step(state, behavior, false);
            if (tick % 2 == 0) updates.add(new PlayerTrajectoryEngine.PositionUpdate(tick, state.pos()));
        }
        PlayerMotionSimulation.State fitted = PlayerMotionSimulation.fit(updates, PlayerMotionSimulation.Mode.AIR,
                behavior, Long.MAX_VALUE);
        assertTrue(fitted.velocity().distanceTo(state.velocity()) < 1e-6);
    }

    @Test
    void aStillTargetIsHitAndTheLookLiesOnTheLattice() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        for (double distance : new double[]{6, 18, 32, 48}) {
            Box[] boxes = new Box[100];
            Arrays.fill(boxes, STANDING.getBoxAt(0.5, 64, 0.5 + distance));
            PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                    List.of(new PlayerMotionSimulation.Forecast("still", 1.0, boxes)), PearlGrappleSolver.leads(0, 0));
            PearlGrappleSolver.Lattice lattice = new PearlGrappleSolver.Lattice(0f, 0f, STEP);
            PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target, lattice, 40, 80);
            assertNotNull(shot, "distance " + distance);
            // At 48 blocks the full triangular spread reaches beyond the grown box. The old
            // 27-node score overstated this chance; the independent sweep checks calibration.
            assertTrue(shot.chance() > (distance < 40 ? 0.9 : 0.8),
                    "distance " + distance + " chance " + shot.chance());
            assertTrue(shot.hitAge() > 0);
            double pixels = (shot.pitch() - lattice.pitch()) / STEP;
            assertEquals(Math.round(pixels), pixels, 1e-3, "pitch on the lattice");
            // The owner lands where the pearl started its hit tick: just short of the target.
            assertTrue(shot.landing().distanceTo(boxes[0].getCenter()) < 2.5, "landing " + shot.landing());
        }
    }

    @Test
    void aTargetOutsideTheTurnAllowedIsNotThrownAt() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        Box[] boxes = new Box[100];
        // Due east while looking south: a 90 degree turn.
        Arrays.fill(boxes, STANDING.getBoxAt(20.5, 64, 0.5));
        PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("still", 1.0, boxes)), PearlGrappleSolver.leads(0, 0));
        PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                new PearlGrappleSolver.Lattice(0f, 0f, STEP), 28, 80);
        assertTrue(shot == null || shot.chance() < 0.05);
    }

    @Test
    void aRunnerIsLedAndHitWhereItWillBe() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        // Sprinting east across the view, 22 blocks south.
        Box[] boxes = new Box[100];
        for (int i = 0; i < boxes.length; ++i) {
            boxes[i] = STANDING.getBoxAt(-6 + i * PlayerMotionSimulation.SPRINT_SPEED, 64, 22.5);
        }
        PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("hold", 1.0, boxes)), PearlGrappleSolver.leads(1, 60));
        PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(null, null, null, thrower, target,
                new PearlGrappleSolver.Lattice(0f, 0f, STEP), 40, 80);
        assertNotNull(shot);
        assertTrue(shot.chance() > 0.8, "chance " + shot.chance());
        // Aimed ahead of where the runner is now (east is negative yaw).
        float[] atRunner = ThrowPhysics.lookAlong(boxes[0].getCenter().subtract(thrower.spawn()), 0f);
        assertTrue(shot.yaw() < atRunner[0], "led " + shot.yaw() + " vs " + atRunner[0]);
    }

    private static Box[] glider(Vec3d pos, float yaw, float pitch, double speed, int boostTicks) {
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(yaw, pitch, Vec3d.ZERO,
                false, 0.0, false, boostTicks);
        Vec3d velocity = PlayerMotionSimulation.rotationVector(pitch, yaw).multiply(speed);
        return WORLDLESS.forecast(new PlayerMotionSimulation.State(pos, velocity, false,
                PlayerMotionSimulation.Mode.GLIDING), behavior, 100);
    }

    private static PearlGrappleSolver.Shot solveToward(PearlGrappleSolver.Thrower thrower, Box[] boxes,
                                                       List<PearlGrappleSolver.Lead> leads) {
        PearlGrappleSolver.Target target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("hold", 1.0, boxes)), leads);
        float[] toward = ThrowPhysics.lookAlong(boxes[0].getCenter().subtract(thrower.spawn()), 0f);
        return PearlGrappleSolver.solve(null, null, null, thrower, target,
                new PearlGrappleSolver.Lattice(toward[0], toward[1], STEP), 90, 60);
    }

    @Test
    void aFireworkBoostedGliderIsHitInItsSmallGlidingBox() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        Box[] boxes = glider(new Vec3d(10, 70, 40), 170f, 5f, 1.5, 15);
        assertEquals(0.6, boxes[0].getLengthY(), 1e-6, "gliding box height");
        PearlGrappleSolver.Shot shot = solveToward(thrower, boxes, PearlGrappleSolver.leads(0, 50));
        assertNotNull(shot);
        assertTrue(shot.chance() > 0.5, "chance " + shot.chance());
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, shot.yaw(), shot.pitch(), 60);
        assertTrue(PearlGrappleSolver.hitAge(flight, 0, 0, 0, boxes, 1) > 0,
                "the mean throw meets the glider at its likeliest lead");
    }

    @Test
    void aHeadOnGliderCanSlipBetweenTwoPearlSweeps() {
        // Closing at ~2.8 blocks/tick on a 0.6 box: the server's one sweep per tick misses it on
        // every second lead, and a sweep that starts inside the box does not count. No look hits
        // on lead 1; leads 0 and 2 can, so the honest chance is their weight (0.15 + 0.15).
        PearlGrappleSolver.Thrower thrower = throwerAt(0.5, 64, 0.5);
        Box[] boxes = glider(new Vec3d(0.5, 76, 36), 180f, 15f, 1.3, 0);
        for (float yaw = -3f; yaw <= 3f; yaw += 0.25f) {
            for (float pitch = -50f; pitch <= -10f; pitch += 0.25f) {
                PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, yaw, pitch, 60);
                assertEquals(-1, PearlGrappleSolver.hitAge(flight, 0, 0, 0, boxes, 1), "look " + yaw + "/" + pitch);
            }
        }
        PearlGrappleSolver.Shot shot = solveToward(thrower, boxes, PearlGrappleSolver.leads(0, 50));
        assertNotNull(shot);
        assertEquals(0.30, shot.chance(), 1e-6);
    }

    @Test
    void theHitboxFollowsTheMovementMode() {
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(0f, 30f, Vec3d.ZERO,
                false, 0.0, false, 0);
        PlayerMotionSimulation.State state = new PlayerMotionSimulation.State(new Vec3d(0, 70, 0),
                new Vec3d(0, -0.5, 1.0), false, PlayerMotionSimulation.Mode.GLIDING);
        Box[] boxes = WORLDLESS.forecast(state, behavior, 5);
        for (Box box : boxes) assertEquals(0.6, box.getLengthY(), 1e-6);
        assertEquals(1.8, WORLDLESS.box(new PlayerMotionSimulation.State(Vec3d.ZERO, Vec3d.ZERO, true,
                PlayerMotionSimulation.Mode.GROUND)).getLengthY(), 1e-6);
    }

    @Test
    void lobLookFliesThroughAStillTargetBox() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0, 64, 0);
        Box box = new Box(-0.3, 64, 18.7, 0.3, 65.8, 19.3);
        Box[] boxes = new Box[100];
        Arrays.fill(boxes, box);
        float[] look = PearlGrappleSolver.lobLook(thrower, boxes, 0, 80, 0.0f);
        assertNotNull(look, "a still player 19 blocks away is inside the pearl's reach");
        PearlGrappleSolver.Flight flight = PearlGrappleSolver.Flight.ballistic(thrower, look[0], look[1], 80);
        double best = Double.MAX_VALUE;
        Vec3d centre = box.getCenter();
        for (int age = 1; age < 80; ++age) {
            double dx = flight.fx[age] - centre.x;
            double dy = flight.fy[age] - centre.y;
            double dz = flight.fz[age] - centre.z;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        assertTrue(best < 1.5, "the lob passes " + best + " blocks from the target centre");
    }

    @Test
    void lobLookIsNullBeyondReach() {
        PearlGrappleSolver.Thrower thrower = throwerAt(0, 64, 0);
        Box far = new Box(-0.3, 64, 499.7, 0.3, 65.8, 500.3);
        Box[] boxes = new Box[100];
        Arrays.fill(boxes, far);
        assertEquals(null, PearlGrappleSolver.lobLook(thrower, boxes, 0, 80, 0.0f));
    }
}
