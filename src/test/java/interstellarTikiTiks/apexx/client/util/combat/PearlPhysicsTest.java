
package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world-free parts of the exact pearl and wind charge physics and the catch solve. */
class PearlPhysicsTest {
    private static final Vec3d FEET = new Vec3d(0.5, 200.0, 0.5);
    private static final double EYE = 1.62;
    /** Mouse step at the default sensitivity. */
    private static final float LOOK_STEP = 0.15f;

    /** A standing thrower: both projectiles leave from the same place. */
    private static WindChargeIntercept.Shooter standing() {
        return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(FEET, EYE), Vec3d.ZERO, true);
    }

    private static Vec3d pearlLaunch(float yaw, float pitch) {
        return ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
    }

    private static PearlSimulation.Path pearlAlong(float yaw, float pitch) {
        return PearlSimulation.ballistic(ThrowPhysics.pearlSpawn(FEET, EYE), pearlLaunch(yaw, pitch), 0).trace(60);
    }

    @Test
    void aSteepDownwardLookIsCaughtByAChargeThrownFirst() {
        float look = 45f;
        PearlSimulation.Path pearl = pearlAlong(0f, look);
        // The pearl speeds up on the way down: a charge thrown after it never closes the gap...
        for (int throwAge = 1; throwAge <= 6; ++throwAge) {
            assertNull(WindChargeIntercept.solve(null, null, pearl, throwAge, standing(), 40,
                    WindChargeIntercept.Reach.from(0f, look), false), "pearl first at gap " + throwAge);
        }
        // ...but a charge thrown a tick before it, on a steeper line, is run into from behind.
        WindChargeIntercept.Shot charge = WindChargeIntercept.solveOlder(null, null, pearl, 1, standing(), 40,
                WindChargeIntercept.Reach.from(0f, look), true);
        assertNotNull(charge);
        assertTrue(charge.pitch() > look, "the charge leaves steeper than the pearl");
        assertEquals(1, charge.throwAge());

        // The second throw, scored from the pearl's side against that charge, sees the same catch.
        WindChargeIntercept.Charge flying = WindChargeIntercept.charge(null, null, standing(), charge.yaw(),
                charge.pitch(), 50);
        WindChargeIntercept.Shot pearlSide = WindChargeIntercept.evaluatePearl(pearl, pearlLaunch(0f, look), 0f, look,
                flying, 1, 40);
        assertNotNull(pearlSide);
        assertEquals(charge.catchAge(), pearlSide.catchAge());
        assertTrue(pearlSide.confidence() > 0.5f, "most of the pearl's own spread still catches");
    }

    @Test
    void theSameLookMissesJustInsideTheDownwardMacroZoneButASteeperChargeCatches() {
        float look = 76.5f;
        PearlSimulation.Path pearl = pearlAlong(0f, look);
        for (int gap = 1; gap <= 3; ++gap) {
            assertNull(WindChargeIntercept.evaluateOlder(null, null, pearl, gap, standing(), 0f, look, 40),
                    "same look, gap " + gap);
        }
        WindChargeIntercept.Shot best = null;
        for (int step = 0; look + step * LOOK_STEP <= 90.0f; ++step) {
            WindChargeIntercept.Shot shot = WindChargeIntercept.evaluateOlder(null, null, pearl, 1, standing(), 0f,
                    look + step * LOOK_STEP, 40);
            if (shot != null && (best == null || shot.score() > best.score())) best = shot;
        }
        assertNotNull(best, "a pitch-only turn of the charge catches");
        assertTrue(best.pitch() - look < 4.0f, "and it is a small one: no camera whip near the pole");
    }

    @Test
    void aChargeHandledInTheSameServerTickAsThePearlStillCountsAsASlip() {
        // Gap 1 slips to gap 0 (both uses between the same two server ticks) or gap 2; both catch here.
        float look = 60f;
        WindChargeIntercept.Shot charge = WindChargeIntercept.solveOlder(null, null, pearlAlong(0f, look), 1,
                standing(), 40, WindChargeIntercept.Reach.from(0f, look), true);
        assertNotNull(charge);
        assertEquals(1.0f, charge.slip(), 1e-6);
    }

    @Test
    void aPairFlyingAlmostInStepIsStillSeeded() {
        // At 25 degrees down the pearl never quite reaches the charge's reach; only the box and the
        // pearl's tolerance close the last fraction of a block. The solve must still find it.
        float look = 25f;
        PearlSimulation.Path pearl = pearlAlong(0f, look);
        boolean anyLookCatches = false;
        for (float pitch = look; pitch <= look + 30f && !anyLookCatches; pitch += 0.05f) {
            anyLookCatches = WindChargeIntercept.evaluateOlder(null, null, pearl, 1, standing(), 0f, pitch, 40) != null;
        }
        assertTrue(anyLookCatches);
        assertNotNull(WindChargeIntercept.solveOlder(null, null, pearl, 1, standing(), 40,
                WindChargeIntercept.Reach.from(0f, look), false));
    }

    @Test
    void theUpwardMacroCatchesWithTheSameLook() {
        for (float look = -90f; look <= -76.5f; look += 2.5f) {
            WindChargeIntercept.Shot shot = WindChargeIntercept.evaluate(null, null, pearlAlong(0f, look), 1,
                    standing(), 0f, look, 40);
            assertNotNull(shot, "pitch " + look);
        }
    }

    @Test
    void serverAnglesAreWrappedAndClampedAsTheServerAppliesThem() {
        assertEquals(10f, ThrowPhysics.serverYaw(370f), 1e-4);
        assertEquals(-170f, ThrowPhysics.serverYaw(190f), 1e-4);
        assertEquals(90f, ThrowPhysics.serverPitch(95f), 1e-4);
        assertEquals(-90f, ThrowPhysics.serverPitch(-120f), 1e-4);
    }

    @Test
    void aFlatThrowGoesAlongTheYawAtFullPower() {
        Vec3d south = ThrowPhysics.launchVelocity(0f, 0f, ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
        assertEquals(0, south.x, 1e-6);
        assertEquals(0, south.y, 1e-6);
        assertEquals(1.5, south.z, 1e-6);
        // The shooter's movement is added, its vertical part only while airborne.
        Vec3d moving = ThrowPhysics.launchVelocity(0f, 0f, ThrowPhysics.PEARL_POWER, null, new Vec3d(.2, .3, 0), true);
        assertEquals(.2, moving.x, 1e-6);
        assertEquals(0, moving.y, 1e-6);
        Vec3d airborne = ThrowPhysics.launchVelocity(0f, 0f, ThrowPhysics.PEARL_POWER, null, new Vec3d(.2, .3, 0), false);
        assertEquals(.3, airborne.y, 1e-6);
    }

    @Test
    void lookAlongInvertsTheLaunchDirection() {
        for (float yaw = -170f; yaw < 180f; yaw += 23f) {
            for (float pitch = -80f; pitch <= 80f; pitch += 16f) {
                float[] look = ThrowPhysics.lookAlong(ThrowPhysics.rawDirection(yaw, pitch), yaw);
                assertTrue(ThrowPhysics.lookAngle(yaw, pitch, look[0], look[1]) < 0.05, "yaw " + yaw + " pitch " + pitch);
            }
        }
    }

    @Test
    void aPearlTickIsGravityThenDragThenTheMove() {
        Vec3d start = new Vec3d(0, 100, 0);
        Vec3d velocity = new Vec3d(0.4, 0.5, -0.2);
        PearlSimulation.Path path = PearlSimulation.ballistic(start, velocity, 0).trace(3);
        Vec3d expected = velocity.add(0, -PearlSimulation.GRAVITY, 0).multiply((double) PearlSimulation.DRAG);
        assertEquals(expected, path.at(1).velocity());
        assertEquals(start.add(expected), path.positionAt(1));
        assertEquals(1, path.at(1).age());
        assertEquals(3, path.lastAge());
    }

    @Test
    void entityToleranceGrowsWithAgeUpToThreeTenths() {
        assertEquals(0f, ThrowPhysics.entityTolerance(1), 1e-6);
        assertEquals(0f, ThrowPhysics.entityTolerance(2), 1e-6);
        assertEquals(0.15f, ThrowPhysics.entityTolerance(5), 1e-6);
        assertEquals(0.3f, ThrowPhysics.entityTolerance(8), 1e-6);
        assertEquals(0.3f, ThrowPhysics.entityTolerance(40), 1e-6);
    }

    @Test
    void theSolvedChargeCatchesThePearlOnThePredictedTick() {
        Vec3d spawn = new Vec3d(0.5, 120, 0.5);
        Vec3d launch = ThrowPhysics.launchVelocity(0f, -10f, ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
        PearlSimulation.Path pearl = PearlSimulation.ballistic(spawn.add(0, -(double) 0.1f, 0), launch, 0).trace(80);
        WindChargeIntercept.Shooter shooter = new WindChargeIntercept.Shooter(spawn, Vec3d.ZERO, true);
        int throwAge = 3;
        WindChargeIntercept.Shot shot = WindChargeIntercept.solve(null, null, pearl, throwAge, shooter, 40,
                WindChargeIntercept.Reach.from(0f, -10f), true);
        assertNotNull(shot, "a catch exists for a pearl thrown ahead");
        assertTrue(shot.catchAge() > throwAge + 1, "the catch comes after the charge has moved");
        assertTrue(shot.confidence() > 0f && shot.confidence() <= 1f);
        // Re-evaluating the chosen look gives the same catch: the solve and the check agree.
        WindChargeIntercept.Shot again = WindChargeIntercept.evaluate(null, null, pearl, throwAge, shooter,
                shot.yaw(), shot.pitch(), 40);
        assertNotNull(again);
        assertEquals(shot.catchAge(), again.catchAge());
    }
    @Test
    void interceptScoreWeighsAOneTickSlipAsAQuarterEachSide() {
        WindChargeIntercept.Shot exactTickOnly = shot(1f, 0f);
        assertEquals(0.8, exactTickOnly.score(), 1e-6);
        assertEquals(0.5, exactTickOnly.interceptScore(), 1e-6);
        assertEquals(0.75, shot(1f, 0.5f).interceptScore(), 1e-6);
        assertEquals(1.0, shot(1f, 1f).interceptScore(), 1e-6);
        assertEquals(exactTickOnly.score(), exactTickOnly.score(WindChargeIntercept.Rank.CATCH), 1e-9);
        assertEquals(exactTickOnly.interceptScore(), exactTickOnly.score(WindChargeIntercept.Rank.INTERCEPT), 1e-9);
    }

    @Test
    void aPearlCrossingAheadIsInterceptedOnANearMiss() {
        // Someone 12 blocks east throws west, passing 3 blocks in front of the shooter; seen at age 2.
        Vec3d spawn = ThrowPhysics.windChargeSpawn(new Vec3d(0.5, 100, 0.5), 1.62);
        Vec3d launch = ThrowPhysics.launchVelocity(90f, -5f, ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
        PearlSimulation.Path pearl = PearlSimulation.ballistic(new Vec3d(12.5, 101.5, 3.5), launch, 2).trace(80);
        WindChargeIntercept.Shooter shooter = new WindChargeIntercept.Shooter(spawn, Vec3d.ZERO, true);
        int throwAge = 7;
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(0f, 0f);
        // The pearl never crosses the charge's exact reach within one tick, only passes through its box.
        assertNotNull(WindChargeIntercept.solve(null, null, pearl, throwAge, shooter, 40, reach, true));
        WindChargeIntercept.Shot shot = WindChargeIntercept.solve(null, null, pearl, throwAge, shooter, 40, reach,
                true, WindChargeIntercept.Rank.INTERCEPT);
        assertNotNull(shot, "the crossing pearl can be intercepted");
        assertTrue(shot.catchPos().z > 3.0 && shot.catchPos().z < 4.0, "caught on its line in front of the shooter");
        WindChargeIntercept.Shot again = WindChargeIntercept.evaluate(null, null, pearl, throwAge, shooter,
                shot.yaw(), shot.pitch(), 40);
        assertNotNull(again);
        assertEquals(shot.catchAge(), again.catchAge());
    }

    @Test
    void anEscapingPearlIsChasedWithATimingSlipToSpare() {
        // A pearl thrown away from the shooter, 3 blocks ahead of it; seen at age 2, handled one tick later.
        Vec3d spawn = ThrowPhysics.windChargeSpawn(new Vec3d(0.5, 100, 0.5), 1.62);
        Vec3d launch = ThrowPhysics.launchVelocity(0f, -15f, ThrowPhysics.PEARL_POWER, null, Vec3d.ZERO, true);
        PearlSimulation.Path pearl = PearlSimulation.ballistic(new Vec3d(0.5, 101.6, 3.5), launch, 2).trace(80);
        WindChargeIntercept.Shooter shooter = new WindChargeIntercept.Shooter(spawn, Vec3d.ZERO, true);
        WindChargeIntercept.Shot shot = WindChargeIntercept.solve(null, null, pearl, 3, shooter, 40,
                WindChargeIntercept.Reach.from(0f, 0f), true, WindChargeIntercept.Rank.INTERCEPT);
        assertNotNull(shot);
        assertTrue(shot.slip() >= 0.5f, "a chase survives the server handling the throw a tick off");
        // Most of the charge's own spread still catches (an independent 40,000-roll replay of this shot
        // measured 91%); the slip side then lifts the intercept score to three quarters of that.
        assertTrue(shot.confidence() >= 0.9f, "confidence " + shot.confidence());
        assertEquals(shot.confidence() * 0.75, shot.interceptScore(), 1e-6);
    }

    private static WindChargeIntercept.Shot shot(float confidence, float slip) {
        return new WindChargeIntercept.Shot(0f, 0f, Vec3d.ZERO, 3, 8, Vec3d.ZERO, Vec3d.ZERO, confidence, slip);
    }
}
