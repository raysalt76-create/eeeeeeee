package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StunSlamEngineTest {

    private static final int HORIZON = StunSlamEngine.MAX_FOLLOW_UP_TICKS + 1;

    /** Standing player-sized box, feet at (x, y, z). */
    private static Box playerBox(double x, double y, double z) {
        return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    /** A diver straight above a target, falling {@code speed} blocks per tick, looking straight down. */
    private static StunSlamEngine.Outlook dive(double eyeAboveTop, double speed, double[] targetDriftPerTick,
                                               int groundedFrom) {
        Vec3d[] eyes = new Vec3d[HORIZON + 1];
        boolean[] grounded = new boolean[HORIZON + 1];
        Box[] boxes = new Box[HORIZON + 1];
        double top = 1.8;
        double x = 0.0;
        for (int k = 0; k <= HORIZON; k++) {
            eyes[k] = new Vec3d(0.0, top + eyeAboveTop - speed * k, 0.0);
            grounded[k] = groundedFrom >= 0 && k >= groundedFrom;
            if (k > 0 && k - 1 < targetDriftPerTick.length) {
                x += targetDriftPerTick[k - 1];
            }
            boxes[k] = playerBox(x, 0.0, 0.0);
        }
        StunSlamEngine.Hypothesis hold = new StunSlamEngine.Hypothesis("hold", 1.0, boxes, boxes);
        return new StunSlamEngine.Outlook(eyes, grounded, List.of(hold), 0.0f, 90.0f);
    }

    @Test
    void profileCeilingFollowsVelocityBucketsAndHorizontalHalving() {
        assertEquals(4, StunSlamEngine.profileCeiling(-0.60, 0.0));
        assertEquals(3, StunSlamEngine.profileCeiling(-1.1395, 0.0));
        assertEquals(2, StunSlamEngine.profileCeiling(-1.40, 0.0));
        assertEquals(1, StunSlamEngine.profileCeiling(-1.7817, 0.0), "the fastest profile is one tick, never zero");
        assertEquals(2, StunSlamEngine.profileCeiling(-0.60, 2.01), "four ticks halve beyond two blocks");
        assertEquals(4, StunSlamEngine.profileCeiling(-0.60, 2.0), "exactly two blocks keeps the profile");
        assertEquals(1, StunSlamEngine.profileCeiling(-1.1395, 3.0));
    }

    @Test
    void adaptivePlanTakesTheMostTicksThatStillHit() {
        StunSlamEngine.Outlook outlook = dive(2.5, 0.5, new double[0], -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, false));
        assertEquals(4, plan.gap(), "a slow, steady fall keeps the whole profile ceiling");
        assertEquals(StunSlamEngine.Source.ADAPTIVE_MAX, plan.source());
        assertTrue(plan.confidence() >= StunSlamEngine.MIN_CONFIDENCE);
    }

    @Test
    void adaptivePlanStopsBeforeTheTargetLeavesReach() {
        // The target slides one block sideways per tick from the second tick on: reach is gone by tick 3.
        StunSlamEngine.Outlook outlook = dive(2.0, 0.5, new double[] {0.0, 1.5, 1.5, 1.5, 1.5, 1.5}, -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, false));
        assertTrue(plan.gap() < 4, "must not wait for a tick that cannot hit, got " + plan.gap());
        assertTrue(plan.confidence() >= StunSlamEngine.MIN_CONFIDENCE);
        assertTrue(plan.byTick()[plan.gap()] >= StunSlamEngine.MIN_CONFIDENCE);
    }

    @Test
    void landingEndsTheSmashSoThePlanFiresBeforeIt() {
        StunSlamEngine.Outlook outlook = dive(2.0, 0.4, new double[0], 3);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, false));
        assertEquals(2, plan.gap(), "tick 3 is on the ground: no fall left to smash with");
    }

    @Test
    void floorIsHonouredWhileItStillHoldsAndDroppedWhenItDoesNot() {
        StunSlamEngine.Outlook steady = dive(2.5, 0.3, new double[0], -1);
        assertEquals(4, StunSlamEngine.plan(steady, StunSlamEngine.Params.adaptive(4, 3, 3.0, false)).gap());

        StunSlamEngine.Outlook leaving = dive(2.0, 0.3, new double[] {0.0, 3.0, 3.0, 3.0, 3.0, 3.0}, -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(leaving, StunSlamEngine.Params.adaptive(4, 3, 3.0, false));
        assertTrue(plan.gap() < 3, "the floor yields when nothing at or above it can hit, got " + plan.gap());
    }

    @Test
    void aProfileOfZeroIsClampedToTheNextTickNeverTheSameTick() {
        StunSlamEngine.Outlook outlook = dive(2.5, 1.0, new double[0], -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(outlook, StunSlamEngine.Params.adaptive(0, 0, 3.0, false));
        assertEquals(1, plan.gap());
    }

    @Test
    void normalModeKeepsTheConfiguredGap() {
        StunSlamEngine.Outlook outlook = dive(2.0, 0.5, new double[] {0.0, 4.0, 4.0, 4.0, 4.0, 4.0}, -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(outlook, StunSlamEngine.Params.normal(3, 3.0, false));
        assertEquals(3, plan.gap());
        assertEquals(StunSlamEngine.Source.EXACT, plan.source());
    }

    @Test
    void serverBoxOutOfReachVetoesATickWhenRegistrationIsOn() {
        Vec3d[] eyes = new Vec3d[HORIZON + 1];
        Box[] visible = new Box[HORIZON + 1];
        Box[] server = new Box[HORIZON + 1];
        for (int k = 0; k <= HORIZON; k++) {
            eyes[k] = new Vec3d(0.0, 3.0, 0.0);
            visible[k] = playerBox(0.0, 0.0, 0.0);
            server[k] = playerBox(k >= 2 ? 6.0 : 0.0, 0.0, 0.0);
        }
        StunSlamEngine.Outlook outlook = new StunSlamEngine.Outlook(eyes, new boolean[HORIZON + 1],
                List.of(new StunSlamEngine.Hypothesis("hold", 1.0, visible, server)), 0.0f, 90.0f);
        assertEquals(1.0, StunSlamEngine.confidence(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, true), 1), 1e-9);
        assertEquals(0.0, StunSlamEngine.confidence(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, true), 2), 1e-9);
        assertEquals(1.0, StunSlamEngine.confidence(outlook, StunSlamEngine.Params.adaptive(4, 0, 3.0, false), 2), 1e-9);
    }

    @Test
    void behindTheViewLowersConfidenceUntilTheTurnFits() {
        StunSlamEngine.Outlook below = dive(2.5, 0.3, new double[0], -1);
        double lookingDown = StunSlamEngine.confidence(below, StunSlamEngine.Params.adaptive(4, 0, 3.0, false), 1);
        Vec3d[] eyes = below.eyes();
        StunSlamEngine.Outlook looksAway = new StunSlamEngine.Outlook(eyes, below.grounded(), below.targets(),
                0.0f, -90.0f);
        double lookingUp = StunSlamEngine.confidence(looksAway, StunSlamEngine.Params.adaptive(4, 0, 3.0, false), 1);
        assertEquals(1.0, lookingDown, 1e-9);
        assertTrue(lookingUp < lookingDown, "a 180 degree turn does not fit in one tick");
    }

    @Test
    void exactDecisionWaitsThenFiresThenAbandons() {
        StunSlamEngine.Params params = StunSlamEngine.Params.normal(2, 3.0, false);
        StunSlamEngine.Outlook live = dive(2.0, 0.5, new double[0], -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(live, params);

        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, live, 0, true).verdict());
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, live, 1, true).verdict());
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, live, 2, true).verdict());
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, live, 2, false).verdict(),
                "never clicks without a live valid ray");
        assertEquals(StunSlamEngine.Verdict.ABANDON,
                StunSlamEngine.decide(params, plan, live, 2 + StunSlamEngine.LATE_GRACE_TICKS + 1, false).verdict());
    }

    @Test
    void nextTickOnTheNextTickWhenGapIsOne() {
        StunSlamEngine.Params params = StunSlamEngine.Params.normal(1, 3.0, false);
        StunSlamEngine.Outlook live = dive(2.0, 0.5, new double[0], -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(live, params);
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, live, 1, true).verdict());
    }

    @Test
    void adaptiveFiresEarlyWhenTheWindowIsClosing() {
        StunSlamEngine.Params params = StunSlamEngine.Params.adaptive(4, 0, 3.0, false);
        StunSlamEngine.Outlook planning = dive(2.5, 0.3, new double[0], -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(planning, params);
        assertEquals(4, plan.gap());

        // Two ticks in, the target is now about to slide out of reach.
        StunSlamEngine.Outlook closing = dive(2.0, 0.3, new double[] {5.0, 5.0, 5.0, 5.0, 5.0, 5.0}, -1);
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, closing, 2, true).verdict());
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, closing, 2, false).verdict());

        // Holding window: keeps waiting for the planned tick.
        StunSlamEngine.Outlook holding = dive(2.0, 0.3, new double[0], -1);
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, holding, 2, true).verdict());
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, holding, 4, true).verdict());
    }

    @Test
    void fastDiveWhoseForecastStillHoldsWaitsForThePlan() {
        StunSlamEngine.Params params = StunSlamEngine.Params.adaptive(4, 1, 3.0, false);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(dive(2.5, 0.3, new double[0], -1), params);
        assertEquals(4, plan.gap());

        // 0.70 b/t is fast, but the next tick is still well inside reach: speed alone is no reason to fire.
        StunSlamEngine.Outlook fastDive = dive(2.0, 0.70, new double[0], -1);
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, fastDive, 1, true).verdict());
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, fastDive, 4, true).verdict());
    }

    @Test
    void fastDiveThatLandsNextTickFiresNow() {
        StunSlamEngine.Params params = StunSlamEngine.Params.adaptive(4, 1, 3.0, false);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(dive(2.5, 0.3, new double[0], -1), params);
        StunSlamEngine.Outlook landing = dive(2.0, 0.70, new double[0], 1);
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, landing, 1, true).verdict());
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, landing, 1, false).verdict(),
                "never fires without a valid live ray");
    }

    @Test
    void floorYieldsOnlyToAClosingWindow() {
        StunSlamEngine.Params params = StunSlamEngine.Params.adaptive(4, 3, 3.0, false);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(dive(2.5, 0.3, new double[0], -1), params);
        assertEquals(4, plan.gap());

        StunSlamEngine.Outlook holding = dive(2.0, 0.3, new double[0], -1);
        assertEquals(StunSlamEngine.Verdict.WAIT, StunSlamEngine.decide(params, plan, holding, 1, true).verdict(),
                "below the floor while the hit holds");
        StunSlamEngine.Outlook closing = dive(2.0, 0.3, new double[] {5.0, 5.0, 5.0, 5.0, 5.0, 5.0}, -1);
        assertEquals(StunSlamEngine.Verdict.FIRE, StunSlamEngine.decide(params, plan, closing, 1, true).verdict(),
                "a closing window beats the floor");
    }

    @Test
    void planNeverWaitsAcrossADip() {
        // In reach on tick 1, out on tick 2, back on ticks 3 and 4.
        StunSlamEngine.Outlook dip = dive(2.5, 0.3, new double[] {0.0, 5.0, -5.0, 0.0, 0.0, 0.0}, -1);
        StunSlamEngine.Plan plan = StunSlamEngine.plan(dip, StunSlamEngine.Params.adaptive(4, 1, 3.0, false));
        assertTrue(dip.horizon() >= 4);
        assertTrue(plan.byTick()[2] < StunSlamEngine.MIN_CONFIDENCE);
        assertTrue(plan.byTick()[4] >= StunSlamEngine.MIN_CONFIDENCE);
        assertEquals(1, plan.gap(), "decide fires before the dip, so the plan must not lie beyond it");
        assertEquals(StunSlamEngine.Source.ADAPTIVE_MAX, plan.source());

        StunSlamEngine.Plan fromFloor = StunSlamEngine.plan(dip, StunSlamEngine.Params.adaptive(4, 3, 3.0, false));
        assertEquals(4, fromFloor.gap(), "a run that starts at the floor extends to the ceiling");
    }

    @Test
    void everyGapSourceIsAtLeastOneTick() {
        assertEquals(1, StunSlamEngine.fallbackGap(-2.5, 3.0, 0.5));
        assertEquals(1, StunSlamEngine.profileCeiling(-3.0, 9.0));
        assertEquals(1, StunSlamEngine.Params.adaptive(0, 0, 3.0, false).ceiling());
        assertEquals(1, StunSlamEngine.Params.adaptive(0, 0, 3.0, false).floor());
        assertEquals(1, StunSlamEngine.Params.normal(0, 3.0, false).exactGap());
    }

    @Test
    void dualClickAimServesBothHeadsWhenTheEyeDropsAFastFall() {
        Box target = playerBox(0.0, 0.0, 0.0);
        Vec3d eyeNow = new Vec3d(1.4, 3.4, 0.0);
        Vec3d maceEye = new Vec3d(1.4, 1.6, 0.0); // 1.8 blocks lower one tick later
        Vec3d point = StunSlamEngine.dualClickAimPoint(eyeNow, eyeNow, target, maceEye, target, target, 3.0);
        assertTrue(point != null, "a single direction exists for both heads");
        Vec3d dir = point.subtract(eyeNow).normalize();
        assertTrue(target.raycast(eyeNow, eyeNow.add(dir.multiply(3.0))).isPresent(), "valid for the axe");
        assertTrue(target.raycast(maceEye, maceEye.add(dir.multiply(3.0))).isPresent(), "valid for the mace");
        assertTrue(target.contains(point), "the point stays inside the target");
    }

    @Test
    void dualClickAimIsNullWhenNoSingleDirectionServesBothHeads() {
        Box target = playerBox(0.0, 0.0, 0.0);
        Box gone = playerBox(40.0, 0.0, 0.0);
        Vec3d eye = new Vec3d(1.0, 2.5, 0.0);
        assertEquals(null, StunSlamEngine.dualClickAimPoint(eye, eye, target, eye, gone, target, 3.0));
    }

    @Test
    void corePointStaysInsideTheCurrentBoxAndLeansToTheForecast() {
        Box current = playerBox(0.0, 0.0, 0.0);
        Vec3d noForecast = StunSlamEngine.corePoint(current, null);
        assertEquals(0.0, noForecast.x, 1e-9);
        assertEquals(0.9, noForecast.y, 1e-9);

        Vec3d leaning = StunSlamEngine.corePoint(current, playerBox(0.5, 0.0, 0.0));
        assertTrue(leaning.x > 0.0, "leans toward the forecast side");
        assertTrue(current.contract(StunSlamEngine.AIM_INSET, StunSlamEngine.AIM_INSET, StunSlamEngine.AIM_INSET)
                .contains(leaning), "stays inside the current box so the ray is valid now");
        assertTrue(leaning.x < 0.30 - StunSlamEngine.AIM_INSET, "never reaches the edge");
    }
}
