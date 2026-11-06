package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world-free drop timing: when the wing has to come off for the smash to be ready on contact. */
class ElytraDropPlannerTest {

    private static final EntityDimensions STANDING = EntityDimensions.changing(0.6f, 1.8f);
    private static final EntityDimensions GLIDING = EntityDimensions.changing(0.6f, 0.6f);
    private static final PlayerMotionSimulation SIM =
            new PlayerMotionSimulation(null, null, STANDING, GLIDING, GLIDING).withFloor(-500.0);
    private static final PlayerMotionSimulation.Behavior HOLD =
            new PlayerMotionSimulation.Behavior(0.0f, 30.0f, Vec3d.ZERO, false, 0.0, false, 0);
    private static final int CLEAR = 2;
    private static final int HORIZON = CLEAR + 14;

    private static ElytraDropPlanner.Self self(PlayerMotionSimulation.State s, double fall) {
        return new ElytraDropPlanner.Self(s.pos(), s.velocity(), s.mode() == PlayerMotionSimulation.Mode.GLIDING,
                s.onGround(), 0.0f, 30.0f, fall, 0.4, 1.62);
    }

    private static Box[] still(Box box) {
        Box[] boxes = new Box[HORIZON + 4];
        java.util.Arrays.fill(boxes, box);
        return boxes;
    }

    private static Box playerBox(double x, double y, double z) {
        return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void farTargetNeverDrops() {
        PlayerMotionSimulation.State s = new PlayerMotionSimulation.State(new Vec3d(0, 100, 0),
                new Vec3d(0, -0.6, 1.2), false, PlayerMotionSimulation.Mode.GLIDING);
        ElytraDropPlanner.Verdict v = ElytraDropPlanner.judge(SIM, self(s, 4.0), still(playerBox(0, 60, 80)), CLEAR, HORIZON);
        assertFalse(v.dropNow());
        assertEquals(ElytraDropPlanner.NEVER, v.hitNow());
    }

    @Test
    void glidingDiveDropsOnTheLastTickThatKeepsTheHit() {
        // A dive on a stationary target: fly the glide forward and find the first tick the planner says drop.
        Vec3d pos = new Vec3d(0, 100, 0);
        Vec3d vel = new Vec3d(0, -0.6, 1.2);
        // The target sits where the glide would be after 12 ticks.
        PlayerMotionSimulation.State probe = new PlayerMotionSimulation.State(pos, vel, false, PlayerMotionSimulation.Mode.GLIDING);
        for (int i = 0; i < 12; i++) probe = SIM.step(probe, HOLD, false);
        Box target = playerBox(probe.pos().x, probe.pos().y - 1.62, probe.pos().z);

        PlayerMotionSimulation.State s = new PlayerMotionSimulation.State(pos, vel, false, PlayerMotionSimulation.Mode.GLIDING);
        int dropTick = -1;
        double hitAtDrop = 0;
        for (int t = 0; t < 14 && dropTick < 0; t++) {
            ElytraDropPlanner.Verdict v = ElytraDropPlanner.judge(SIM, self(s, 4.0), still(target), CLEAR, HORIZON);
            if (v.dropNow()) {
                dropTick = t;
                hitAtDrop = v.hitNow();
            }
            s = SIM.step(s, HOLD, false);
        }
        assertTrue(dropTick > 0, "the wing is not shed immediately");
        assertTrue(hitAtDrop >= CLEAR, "the smash cannot be ready sooner than the flag round trip: " + hitAtDrop);
        assertTrue(hitAtDrop <= CLEAR + 4.0, "the wing came off far too early: hit in " + hitAtDrop + " ticks");
    }

    @Test
    void droppingLaterNeverBeatsDroppingNowOnceTheHitIsClose() {
        PlayerMotionSimulation.State s = new PlayerMotionSimulation.State(new Vec3d(0, 100, 0),
                new Vec3d(0, -1.0, 0.9), false, PlayerMotionSimulation.Mode.GLIDING);
        // Target just ahead and below: contact within a couple of ticks.
        Box target = playerBox(0, 94.5, 3.0);
        double now = ElytraDropPlanner.hitTick(SIM, self(s, 5.0), still(target), 0, CLEAR, HORIZON);
        double later = ElytraDropPlanner.hitTick(SIM, self(s, 5.0), still(target), 1, CLEAR, HORIZON);
        assertTrue(now < ElytraDropPlanner.NEVER);
        assertTrue(later >= now, "later " + later + " now " + now);
    }

    @Test
    void wearingTheWingWithoutGlidingDropsOnlyWhenContactIsOneTickAway() {
        // Falling upright with the elytra on (not gliding): the wing comes off in the tick before contact.
        PlayerMotionSimulation.State s = new PlayerMotionSimulation.State(new Vec3d(0, 100, 0),
                new Vec3d(0, -1.0, 0.0), false, PlayerMotionSimulation.Mode.AIR);
        Box target = playerBox(0, 80, 0);
        int dropTick = -1;
        double hit = 0;
        for (int t = 0; t < 30 && dropTick < 0; t++) {
            ElytraDropPlanner.Verdict v = ElytraDropPlanner.judge(SIM,
                    new ElytraDropPlanner.Self(s.pos(), s.velocity(), false, false, 0.0f, 0.0f, 3.0 + t, 0.4, 1.62),
                    still(target), 0, HORIZON);
            if (v.dropNow()) {
                dropTick = t;
                hit = v.hitNow();
            }
            s = SIM.step(s, HOLD, false);
        }
        assertTrue(dropTick > 0);
        assertTrue(hit <= 1.5, "dropped with the hit still " + hit + " ticks away");
    }

    @Test
    void fromNowShiftsAndRepeatsTheLastBox() {
        Box a = new Box(0, 0, 0, 1, 1, 1);
        Box b = new Box(1, 0, 0, 2, 1, 1);
        Box[] shifted = ElytraDropPlanner.fromNow(new Box[]{a, b}, 1, 4);
        assertEquals(b, shifted[0]);
        assertEquals(b, shifted[3]);
    }
}
