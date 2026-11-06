package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StrikePlannerTest {
    private static Box player(double x, double y, double z) {
        return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    /** Reach growing like the controller from rest: about 36 degrees by the next head, 100 by the one after. */
    private static StrikePlanner.Reach[] reach(int heads) {
        StrikePlanner.Reach[] out = new StrikePlanner.Reach[heads];
        for (int k = 0; k < heads; k++) {
            double d = 0.9 * StrikePlanner.restReach(k + 1.0);
            out[k] = new StrikePlanner.Reach(-d, d, -0.9 * d, 0.9 * d);
        }
        return out;
    }

    private static float[] lookAt(Vec3d eye, Vec3d p) {
        Vec3d to = p.subtract(eye);
        double h = Math.sqrt(to.x * to.x + to.z * to.z);
        return new float[]{(float) Math.toDegrees(Math.atan2(-to.x, to.z)), (float) -Math.toDegrees(Math.atan2(to.y, h))};
    }

    @Test
    void reachableTargetAheadIsStruckOnTheNextHead() {
        Vec3d eye = new Vec3d(0, 3.2, -2.0);
        StrikePlanner.Head head = new StrikePlanner.Head(eye, player(0, 0, 0), false);
        float[] view = lookAt(eye, new Vec3d(0, 1.1, 0));
        Optional<StrikePlanner.Plan> plan = StrikePlanner.plan(new StrikePlanner.Head[]{head}, reach(1), view,
                StrikePlanner.Need.SMASH, 1, 0, -1, 3.0, null);
        assertTrue(plan.isPresent());
        assertEquals(0, plan.get().head());
        assertEquals(StrikePlanner.Kind.SMASH, plan.get().kind());
    }

    @Test
    void passingOverAShieldPicksTheBackstabInsteadOfADoomedAxe() {
        // Diving past the target a block to its side. The shield faces -z, toward where we came from: the next
        // head is still in front of it, the two after are behind it.
        Box target = player(0, 0, 0);
        StrikePlanner.Head[] heads = {
                new StrikePlanner.Head(new Vec3d(1.0, 3.2, -1.0), target, true),
                new StrikePlanner.Head(new Vec3d(1.0, 2.4, 0.5), target, false),
                new StrikePlanner.Head(new Vec3d(1.0, 1.6, 2.0), target, false),
        };
        float[] view = lookAt(heads[0].eye(), new Vec3d(0, 1.1, 0));
        // An axe now would need the mace a 70-110 degree turn later from a standstill: not reachable. Behind the
        // shield on the next head a single smash is (about 70 degrees in two ticks).
        StrikePlanner.Plan plan = StrikePlanner.plan(heads, reach(3), view, StrikePlanner.Need.STUN, 1, 0, -1, 3.0, null)
                .orElseThrow();
        assertEquals(StrikePlanner.Kind.SMASH, plan.kind(), "behind the shield a single smash lands");
        assertEquals(1, plan.head(), "the backstab is on the first head past the shield");
    }

    @Test
    void preparedCandidatesPlanExactlyLikeFreshOnesAndAreNotWritten() {
        Box target = player(0, 0, 0);
        StrikePlanner.Head[] heads = {
                new StrikePlanner.Head(new Vec3d(1.0, 3.2, -1.0), target, true),
                new StrikePlanner.Head(new Vec3d(1.0, 2.4, 0.5), target, false),
                new StrikePlanner.Head(new Vec3d(1.0, 1.6, 2.0), target, false),
        };
        float[] view = lookAt(heads[0].eye(), new Vec3d(0, 1.1, 0));
        float[][][] prepared = StrikePlanner.prepareCandidates(heads, 3.0);
        for (StrikePlanner.Need need : StrikePlanner.Need.values()) {
            var fresh = StrikePlanner.plan(heads, reach(3), view, need, 1, false, 0, 2, 3.0, null);
            // Twice from the same prepared rows: a planner write into them would change the second answer.
            for (int pass = 0; pass < 2; pass++) {
                var cached = StrikePlanner.plan(heads, reach(3), view, need, 1, false, 0, 2, 3.0, null, prepared);
                assertEquals(fresh, cached, need + " pass " + pass);
            }
        }
    }

    @Test
    void straightOverTheTopWithNoTimeToTurnIsNotPlanned() {
        // Crossing directly over the head: the backstab is a full half-turn, which two ticks cannot make.
        Box target = player(0, 0, 0);
        StrikePlanner.Head[] heads = {
                new StrikePlanner.Head(new Vec3d(0, 3.4, -0.9), target, true),
                new StrikePlanner.Head(new Vec3d(0, 2.2, 0.6), target, false),
        };
        float[] view = lookAt(heads[0].eye(), new Vec3d(0, 1.1, 0));
        assertTrue(StrikePlanner.plan(heads, reach(2), view, StrikePlanner.Need.STUN, 1, 0, -1, 3.0, null).isEmpty());
    }

    @Test
    void nothingReachableMeansNoPlan() {
        Vec3d eye = new Vec3d(0, 3.2, -2.0);
        StrikePlanner.Head head = new StrikePlanner.Head(eye, player(0, 0, 0), false);
        float[] view = {180.0f, -60.0f};   // looking up and away
        assertTrue(StrikePlanner.plan(new StrikePlanner.Head[]{head}, reach(1), view, StrikePlanner.Need.SMASH,
                1, 0, -1, 3.0, null).isEmpty());
    }

    @Test
    void outOfReachHeadIsNeverPlanned() {
        Vec3d eye = new Vec3d(0, 6.0, -5.0);
        StrikePlanner.Head head = new StrikePlanner.Head(eye, player(0, 0, 0), false);
        float[] view = lookAt(eye, new Vec3d(0, 1.1, 0));
        assertTrue(StrikePlanner.plan(new StrikePlanner.Head[]{head}, reach(1), view, StrikePlanner.Need.SMASH,
                1, 0, -1, 3.0, null).isEmpty());
    }

    @Test
    void maceOnlyInsideItsWindow() {
        Box target = player(0, 0, 0);
        Vec3d eye = new Vec3d(0, 3.0, -1.5);
        StrikePlanner.Head[] heads = {new StrikePlanner.Head(eye, target, true), new StrikePlanner.Head(eye, target, true),
                new StrikePlanner.Head(eye, target, true)};
        float[] view = lookAt(eye, new Vec3d(0, 1.1, 0));
        StrikePlanner.Plan plan = StrikePlanner.plan(heads, reach(3), view, StrikePlanner.Need.MACE, 1, 2, 2, 3.0, null)
                .orElseThrow();
        assertEquals(2, plan.head());
        assertEquals(StrikePlanner.Kind.MACE, plan.kind());
    }

    @Test
    void stunPairNeedsAReachableMaceAfterTheAxe() {
        Box target = player(0, 0, 0);
        Vec3d eye = new Vec3d(0, 3.0, -1.5);
        StrikePlanner.Head[] heads = {new StrikePlanner.Head(eye, target, true), new StrikePlanner.Head(eye.add(0, -0.4, 0.2), target, true)};
        float[] view = lookAt(eye, new Vec3d(0, 1.1, 0));
        StrikePlanner.Plan plan = StrikePlanner.plan(heads, reach(2), view, StrikePlanner.Need.STUN, 1, 0, -1, 3.0, null)
                .orElseThrow();
        assertEquals(StrikePlanner.Kind.AXE, plan.kind());
        assertEquals(0, plan.head());
        // With a single head there is nowhere for the mace to go: no axe is planned.
        assertTrue(StrikePlanner.plan(new StrikePlanner.Head[]{heads[0]}, reach(1), view, StrikePlanner.Need.STUN,
                1, 0, -1, 3.0, null).isEmpty());
    }

    @Test
    void aStillValidPlanIsKept() {
        Vec3d eye = new Vec3d(0, 3.2, -2.0);
        StrikePlanner.Head head = new StrikePlanner.Head(eye, player(0, 0, 0), false);
        float[] view = lookAt(eye, new Vec3d(0, 1.1, 0));
        StrikePlanner.Plan best = StrikePlanner.plan(new StrikePlanner.Head[]{head}, reach(1), view,
                StrikePlanner.Need.SMASH, 1, 0, -1, 3.0, null).orElseThrow();
        int other = (best.point() + 7) % StrikePlanner.POINTS;
        StrikePlanner.Plan keep = new StrikePlanner.Plan(0, other, StrikePlanner.Kind.SMASH, 0, 0);
        StrikePlanner.Plan kept = StrikePlanner.plan(new StrikePlanner.Head[]{head}, reach(1), view,
                StrikePlanner.Need.SMASH, 1, 0, -1, 3.0, keep).orElseThrow();
        assertEquals(other, kept.point(), "the previous body point stays while it is still reachable");
    }

    @Test
    void fastFallPrefersOneDirectionThatHitsBothClicks() {
        Box target = player(0, 0, 0);
        StrikePlanner.Head[] heads = {
                new StrikePlanner.Head(new Vec3d(1.4, 3.4, 0), target, true),
                new StrikePlanner.Head(new Vec3d(1.4, 1.6, 0), target, true)
        };
        float[] view = lookAt(heads[0].eye(), target.getCenter());
        StrikePlanner.Plan axe = StrikePlanner.plan(heads, reach(2), view, StrikePlanner.Need.STUN,
                1, 0, -1, 3.0, null).orElseThrow();
        assertEquals(StrikePlanner.Kind.AXE, axe.kind());
        Vec3d direction = StunSlamEngine.lookVector(axe.yaw(), axe.pitch());
        for (StrikePlanner.Head head : heads) {
            assertTrue(target.raycast(head.eye(), head.eye().add(direction.multiply(3.0))).isPresent(),
                    "one direction must survive the 1.8-block eye drop, without a last-tick correction");
        }
    }

    @Test
    void maceKeepsAnAlreadyValidRayInsteadOfRecentering() {
        Box target = player(0, 0, 0);
        Vec3d eye = new Vec3d(1.4, 1.6, 0);
        // This is a valid follow-through near the foot of the box, away from every body-grid point.
        float[] view = lookAt(eye, new Vec3d(0, 0.12, 0));
        StrikePlanner.Reach[] tinyBudget = {new StrikePlanner.Reach(-0.001, 0.001, -0.001, 0.001)};
        StrikePlanner.Plan mace = StrikePlanner.plan(
                new StrikePlanner.Head[]{new StrikePlanner.Head(eye, target, true)}, tinyBudget, view,
                StrikePlanner.Need.MACE, 1, 0, 0, 3.0, null).orElseThrow();
        assertEquals(view[0], mace.yaw(), 0.0001);
        assertEquals(view[1], mace.pitch(), 0.0001);
    }

    @Test
    void aOneHeadFlybyStillHasAnAxeOnlyAimPlan() {
        Box target = player(0, 0, 0);
        Vec3d eye = new Vec3d(0, 3.2, -2);
        StrikePlanner.Head[] heads = {new StrikePlanner.Head(eye, target, true)};
        float[] view = lookAt(eye, target.getCenter());
        assertTrue(StrikePlanner.plan(heads, reach(1), view, StrikePlanner.Need.STUN,
                1, 0, -1, 3.0, null).isEmpty());
        StrikePlanner.Plan axe = StrikePlanner.plan(heads, reach(1), view, StrikePlanner.Need.AXE,
                1, 0, -1, 3.0, null).orElseThrow();
        assertEquals(0, axe.head());
        assertEquals(StrikePlanner.Kind.AXE, axe.kind());
    }

    @Test
    void normalPairCannotBorrowAnAdaptiveLaterMaceHead() {
        Box target = player(0, 0, 0);
        Vec3d eye = new Vec3d(0, 3, -1.5);
        StrikePlanner.Head[] heads = {
                new StrikePlanner.Head(eye, target, true),
                new StrikePlanner.Head(eye.add(20, 0, 0), target, true),
                new StrikePlanner.Head(eye, target, true)
        };
        float[] view = lookAt(eye, target.getCenter());
        assertTrue(StrikePlanner.plan(heads, reach(3), view, StrikePlanner.Need.STUN,
                1, true, 0, -1, 3.0, null).isEmpty(), "Normal 1 cannot aim for a pair that only works at gap 2");
        assertTrue(StrikePlanner.plan(heads, reach(3), view, StrikePlanner.Need.STUN,
                1, false, 0, -1, 3.0, null).isPresent());
    }
}
