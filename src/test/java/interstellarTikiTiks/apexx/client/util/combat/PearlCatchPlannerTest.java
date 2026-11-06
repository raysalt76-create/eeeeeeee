package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Shooter;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The planner, the two aim engines and the ensemble, in open air. */
class PearlCatchPlannerTest {
    private static final Shooter STANDING = new Shooter("standing", Vec3d.ZERO, 0.0, true);
    private static final Shooter FALLING = new Shooter("fall-2.5", new Vec3d(0, -2.5, 0), -2.5, false);

    private static PearlCatchPlanner planner(Shooter s, float yaw, float pitch) {
        return new PearlCatchPlanner(PearlCatchAccuracySweepTest.context(s, 0, yaw, pitch, 0.15));
    }

    @Test
    void everyLookGetsAPlanWithBothThrowsAimed() {
        // A press always throws: every pitch, up to straight up and down, yields a plan with both looks.
        for (Shooter s : PearlCatchBaselineSweepTest.shooters()) {
            for (float pitch = -89.0f; pitch <= 89.0f; pitch += 11.0f) {
                PearlCatchPlanner.Plan plan = planner(s, 30.0f, pitch).plan(30.0f, pitch);
                assertNotNull(plan, s.name() + " " + pitch);
                assertTrue(Float.isFinite(plan.firstYaw()) && Float.isFinite(plan.firstPitch()), s.name() + " " + pitch);
                assertTrue(Float.isFinite(plan.secondYaw()) && Float.isFinite(plan.secondPitch()), s.name() + " " + pitch);
                assertTrue(plan.rate() >= 0.0 && plan.rate() <= 1.0 + 1e-9);
                assertTrue(Math.abs(plan.pearlPitch() - pitch) <= 15.0f + 1e-3f, "the nudge stays within 15 degrees");
            }
        }
    }

    @Test
    void steepDownStandingThrowsTheChargeFirst() {
        PearlCatchPlanner.Plan plan = planner(STANDING, 0f, 50f).plan(0f, 50f);
        assertTrue(plan.solved());
        assertTrue(plan.chargeFirst(), "past the learned threshold the pearl runs into a charge thrown ahead of it");
        assertTrue(plan.rate() > 0.8, "rate " + plan.rate());
    }

    @Test
    void levelStandingThrowsThePearlFirst() {
        PearlCatchPlanner.Plan plan = planner(STANDING, 0f, -10f).plan(0f, -10f);
        assertTrue(plan.solved());
        assertFalse(plan.chargeFirst());
    }

    @Test
    void fallingFastChasesThePearlEvenSteeplyDown() {
        // At 2.5 blocks a tick the pearl leaves above terminal speed and slows: the charge chases it.
        PearlCatchPlanner.Plan plan = planner(FALLING, 0f, 50f).plan(0f, 50f);
        assertTrue(plan.solved());
        assertFalse(plan.chargeFirst());
        assertEquals(90.0, PearlCatchModel.chargeFirstPitch(-2.5), 1e-9);
    }

    @Test
    void theOrderTableInterpolatesAndBiasesBothWays() {
        assertEquals(26.0, PearlCatchModel.chargeFirstPitch(0.0), 1e-9);
        assertEquals(6.0, PearlCatchModel.chargeFirstPitch(0.42), 1e-9);
        double between = PearlCatchModel.chargeFirstPitch(-0.8);
        assertTrue(between > 24.0 && between < 36.0, "interpolated " + between);
        assertTrue(PearlCatchModel.chargeFirstBias(60f, 0.0) > 0.0);
        assertTrue(PearlCatchModel.chargeFirstBias(5f, 0.0) < 0.0);
        assertEquals(PearlCatchModel.ORDER_BIAS, PearlCatchModel.chargeFirstBias(80f, 0.0), 1e-9);
    }

    @Test
    void theForecastLandsOnTheGround() {
        Shooter jump = new Shooter("jump", new Vec3d(0, 0.42, 0), 0.3332, false);
        PearlCatchPlanner.Context context = PearlCatchAccuracySweepTest.context(jump, 0, 0f, 0f, 0.15);
        double ground = context.feet().y;
        PearlCatchPlanner withGround = new PearlCatchPlanner(new PearlCatchPlanner.Context(null, null, context.feet(),
                context.movement(), context.velocityY(), false, ground, context.eyeHeight(), 0f, 0f, 30f, 0.15, 5, 1, 24));
        PearlCatchPlanner.ShooterState landed = withGround.shooterIn(30);
        assertTrue(landed.onGround(), "a jump lands within 30 ticks");
        assertEquals(ground, landed.feet().y, 1e-9);
        assertEquals(0.0, landed.movement().y, 1e-9);
        PearlCatchPlanner.ShooterState open = new PearlCatchPlanner(context).shooterIn(30);
        assertTrue(open.feet().y < ground - 10.0, "without a ground the fall goes on");
    }

    @Test
    void theFineRateMatchesAnIndependentReplay() {
        // A pearl-first catch: the fine rate (charge spread and slip) against the replay's own rolls.
        PearlCatchPlanner.Plan plan = planner(STANDING, 0f, -30f).plan(0f, -30f);
        assertTrue(plan.solved() && !plan.chargeFirst());
        int gap = plan.gap();
        PearlCatchPlanner later = new PearlCatchPlanner(PearlCatchAccuracySweepTest.context(STANDING, gap, 0f, -30f, 0.15));
        WindChargeIntercept.Shooter shooter = later.chargeShooter(later.shooterIn(0));
        PearlSimulation.Path pearl = PearlSimulation.ballistic(STANDING.pearlSpawn(0), STANDING.pearlLaunch(0f, -30f, 0), 0).trace(160);
        double fine = later.chargeRateNow(pearl, gap, shooter, plan.chargeYaw(), plan.chargePitch(), true);
        SplittableRandom random = new SplittableRandom(3);
        int hits = 0;
        int n = 20000;
        for (int i = 0; i < n; ++i) {
            int g = PearlCatchTruth.slipped(gap, random);
            Vec3d charge = STANDING.chargeLaunch(plan.chargeYaw(), plan.chargePitch(), gap).add(PearlCatchTruth.roll(random).multiply(1.5));
            if (PearlCatchTruth.catches(STANDING.pearlSpawn(0), STANDING.pearlLaunch(0f, -30f, 0), shooter.spawn(), charge, g, false)) ++hits;
        }
        assertEquals(hits / (double) n, fine, 0.03, "fine rate vs replay");
    }

    @Test
    void theRegionModelFindsTheMiddleOfARegion() {
        // A disk of catching looks centred off the grid centre: the blurred peak is its middle.
        CatchRegionSolver.Region region = CatchRegionSolver.solve(10f, 5f, 3.0, false, (yaw, pitch) -> {
            double dy = yaw - 11.0, dp = pitch - 4.5;
            return dy * dy + dp * dp <= 1.0 ? 1.0 : 0.0;
        });
        assertNotNull(region);
        assertEquals(11.0, region.yaw(), 0.25);
        assertEquals(4.5, region.pitch(), 0.25);
        assertTrue(region.smoothed() > 0.5 && region.smoothed() <= 1.0);
        assertNull(CatchRegionSolver.solve(0f, 0f, 2.0, false, (yaw, pitch) -> 0.0), "no catch, no vote");
        CatchRegionSolver.Region column = CatchRegionSolver.solve(0f, 70f, 2.0, true, (yaw, pitch) -> pitch > 70.5 ? 1.0 : 0.0);
        assertNotNull(column);
        assertEquals(0f, column.yaw());
        assertTrue(column.pitch() > 71.0, "pitch-only: away from the edge at 70.5, " + column.pitch());
    }

    @Test
    void theEnsembleTakesTheBestRatedLookAndPrefersAgreement() {
        PearlCatchEnsemble.Snap snap = (yaw, pitch) -> new float[]{Math.round(yaw * 10f) / 10f, Math.round(pitch * 10f) / 10f};
        // The rate peaks between the two votes: the blend wins.
        PearlCatchEnsemble.Decision blended = PearlCatchEnsemble.decide(new PearlCatchEnsemble.Vote(0f, 0f, 0.9),
                new PearlCatchEnsemble.Vote(2f, 0f, 0.9), (yaw, pitch) -> 1.0 - Math.abs(yaw - 1.0) * 0.2, snap);
        assertEquals(PearlCatchEnsemble.Source.BLEND, blended.source());
        assertEquals(1.0f, blended.yaw(), 1e-6);
        // The rate favours the region's look: it wins.
        PearlCatchEnsemble.Decision region = PearlCatchEnsemble.decide(new PearlCatchEnsemble.Vote(0f, 0f, 0.9),
                new PearlCatchEnsemble.Vote(2f, 0f, 0.9), (yaw, pitch) -> yaw >= 1.9f ? 0.95 : 0.5, snap);
        assertEquals(PearlCatchEnsemble.Source.REGION, region.source());
        // Only one vote: it is taken, snapped.
        PearlCatchEnsemble.Decision single = PearlCatchEnsemble.decide(new PearlCatchEnsemble.Vote(0.04f, 0f, 0.7), null,
                (yaw, pitch) -> 0.7, snap);
        assertEquals(PearlCatchEnsemble.Source.PHYSICS, single.source());
        assertEquals(0.0f, single.yaw(), 1e-6);
        assertEquals(0.5, PearlCatchEnsemble.regionWeight(0.8, 0.8), 1e-9, "equal confidence, equal weight");
    }
}
