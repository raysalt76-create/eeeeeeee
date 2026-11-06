package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SpearReachPlannerTest {

    private static double[] linear(double d0, double perTick, int n) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = Math.max(0.0, d0 - perTick * i);
        }
        return c;
    }

    @Test
    void fastClosingSwapsEarlierThanSlow() {
        // Entry of the 4.5 band is 3 ticks out in both; only the fast one is inside its (longer) lead.
        double[] slow = linear(4.5 + 1.0 * 3, 1.0, 11);
        double[] fast = linear(4.5 + 3.5 * 3, 3.5, 11);
        assertFalse(SpearReachPlanner.plan(slow, 4.5, 3.0).swapNow());
        assertTrue(SpearReachPlanner.plan(fast, 4.5, 3.0).swapNow());
    }

    @Test
    void insideMeleeReachNeverSwaps() {
        assertFalse(SpearReachPlanner.plan(linear(2.5, 1.0, 11), 4.5, 3.0).swapNow());
        assertTrue(SpearReachPlanner.plan(linear(2.5, 1.0, 11), 4.5, 3.0).inMeleeReach());
    }

    @Test
    void receivingTargetDoesNotSwapAndIsNotHeld() {
        double[] away = linear(6.0, -1.0, 11);
        assertFalse(SpearReachPlanner.plan(away, 4.5, 3.0).swapNow());
        assertFalse(SpearReachPlanner.keepHolding(linear(30.0, -1.0, 11), 4.5, 0.75));
    }

    @Test
    void entryTickInterpolates() {
        double e = SpearReachPlanner.entryTick(new double[] {8.0, 6.0, 4.0}, 5.0);
        assertEquals(1.5, e, 1.0e-9);
    }

    @Test
    void nullAndInfiniteCurvesAreNoPlan() {
        assertFalse(SpearReachPlanner.plan(null, 4.5, 3.0).swapNow());
        double[] inf = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        assertFalse(SpearReachPlanner.plan(inf, 4.5, 3.0).swapNow());
        assertFalse(SpearReachPlanner.keepHolding(inf, 4.5, 0.75));
    }

    // ── planGuarded (AutoMace AutoSpear Reach) ──

    @Test
    void guardedSwapsForTargetInsideSpearReachAndStaying() {
        double[] stay = {4.2, 4.1, 4.0, 3.9};
        SpearReachPlanner.Plan plan = SpearReachPlanner.planGuarded(stay, 4.5, 3.0, 4.0, false);
        assertTrue(plan.swapNow());
        assertFalse(plan.inMeleeReach());
    }

    @Test
    void guardedDoesNotSwapWhenTargetIsLeavingTheReach() {
        double[] leaving = {4.3, 4.9, 5.5, 6.1};
        assertFalse(SpearReachPlanner.planGuarded(leaving, 4.5, 3.0, 4.0, false).swapNow());
    }

    @Test
    void guardedDoesNotSwapWhenMaceReachesNextTick() {
        double[] closing = {3.4, 2.9, 2.4, 1.9};
        SpearReachPlanner.Plan plan = SpearReachPlanner.planGuarded(closing, 4.5, 3.0, 3.4, false);
        assertFalse(plan.swapNow());
        assertTrue(plan.inMeleeReach());
    }

    @Test
    void guardedWithoutHorizontalIgnoresHorizontalValue() {
        double[] stay = {4.2, 4.1};
        assertTrue(SpearReachPlanner.planGuarded(stay, 4.5, 3.0, 9.0, false).swapNow());
    }

    @Test
    void horizontalOnlyRequiresStrictlyBetweenMeleeAndSpearReach() {
        double[] reach = {4.4, 4.3, 4.2};
        assertTrue(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, 3.01, true).swapNow());
        assertTrue(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, 4.49, true).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, 3.0, true).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, 4.5, true).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, 2.0, true).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(reach, 4.5, 3.0, Double.NaN, true).swapNow());
    }

    @Test
    void horizontalOnlyStillNeedsTheBoxInsideSpearReachOnTheSmashTick() {
        // Horizontal 4.0 but the forecast keeps the box 6 blocks away: a spear cannot reach, no swap.
        double[] high = {7.0, 6.5, 6.2};
        assertFalse(SpearReachPlanner.planGuarded(high, 4.5, 3.0, 4.0, true).swapNow());
        // A dive that closes the vertical gap by next tick swaps even though the 3D distance now is 6.
        double[] dive = {6.0, 4.4, 4.2};
        assertTrue(SpearReachPlanner.planGuarded(dive, 4.5, 3.0, 4.0, true).swapNow());
        // The same dive without Horizontal Reach Only is gated on the 3D distance now (6.0 > 4.5).
        assertFalse(SpearReachPlanner.planGuarded(dive, 4.5, 3.0, 4.0, false).swapNow());
    }

    @Test
    void guardedNullAndInfiniteAreNoPlan() {
        assertFalse(SpearReachPlanner.planGuarded(null, 4.5, 3.0, 4.0, true).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(new double[0], 4.5, 3.0, 4.0, false).swapNow());
        assertFalse(SpearReachPlanner.planGuarded(
                new double[] {Double.POSITIVE_INFINITY}, 4.5, 3.0, 4.0, false).swapNow());
    }

    // ── opensWithin (the opening the swap reaction is timed from) ──

    @Test
    void openingIsSeenBeforeTheOneTickBand() {
        // Closing 0.6 blocks/tick: 6.0, 5.4, 4.8, 4.2, 3.6, 3.0 ... The guarded swap is allowed on one
        // tick only (index 3: 4.2 now, 3.6 next); spear reach is entered at tick 2.5 from index 0.
        double[] dive = linear(6.0, 0.6, 11);
        assertTrue(SpearReachPlanner.opensWithin(dive, 4.5, 3.0, 3.0));
        assertFalse(SpearReachPlanner.opensWithin(dive, 4.5, 3.0, 2.0));
        assertFalse(SpearReachPlanner.planGuarded(dive, 4.5, 3.0, 6.0, false).swapNow());
        double[] bandTick = java.util.Arrays.copyOfRange(dive, 3, dive.length);
        assertTrue(SpearReachPlanner.planGuarded(bandTick, 4.5, 3.0, 4.2, false).swapNow());
        // One tick later the mace reaches by itself on the smash tick: a reaction started on the band
        // tick lands here and finds no swap.
        double[] afterBand = java.util.Arrays.copyOfRange(dive, 4, dive.length);
        assertFalse(SpearReachPlanner.planGuarded(afterBand, 4.5, 3.0, 3.6, false).swapNow());
    }

    @Test
    void noOpeningInsideMeleeOrForNullCurves() {
        assertFalse(SpearReachPlanner.opensWithin(linear(2.5, 1.0, 11), 4.5, 3.0, 5.0));
        assertFalse(SpearReachPlanner.opensWithin(null, 4.5, 3.0, 5.0));
        assertFalse(SpearReachPlanner.opensWithin(new double[] {Double.NaN}, 4.5, 3.0, 5.0));
        assertFalse(SpearReachPlanner.opensWithin(linear(6.0, -1.0, 11), 4.5, 3.0, 5.0));
    }

    @Test
    void horizontalHoldFollowsSpearReachPlusMargin() {
        assertTrue(SpearReachPlanner.keepHoldingHorizontal(4.9, 4.5, 0.75));
        assertFalse(SpearReachPlanner.keepHoldingHorizontal(5.3, 4.5, 0.75));
        assertFalse(SpearReachPlanner.keepHoldingHorizontal(Double.NaN, 4.5, 0.75));
    }
}
