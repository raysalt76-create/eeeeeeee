package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure pacing math behind SlotSync.paceAfterSwap and the WireRay hitbox test. */
class SwapPacingTest {
    private static final long MS = 1_000_000L;

    @Test
    void sampledGapStaysWithinOneToThreeMilliseconds() {
        assertEquals(1 * MS, SubTickSequencer.sampleSwapGapNanos(0.0, 0.0));
        assertEquals(3 * MS, SubTickSequencer.sampleSwapGapNanos(1.0, 1.0));
        assertEquals(2 * MS, SubTickSequencer.sampleSwapGapNanos(0.0, 1.0));
        for (int i = 0; i < 1000; i++) {
            long gap = SubTickSequencer.sampleSwapGapNanos(Math.random(), Math.random());
            assertTrue(gap >= 1 * MS && gap <= 3 * MS, "gap " + gap);
        }
    }

    @Test
    void remainingGapIsZeroWithoutASwapAndCountsDownAfterOne() {
        assertEquals(0L, SubTickSequencer.gapRemainingNanos(0L, 2 * MS, 5 * MS), "no swap on record");
        long swap = 100 * MS;
        assertEquals(2 * MS, SubTickSequencer.gapRemainingNanos(swap, 2 * MS, swap));
        assertEquals(MS / 2, SubTickSequencer.gapRemainingNanos(swap, 2 * MS, swap + 3 * MS / 2));
        assertEquals(0L, SubTickSequencer.gapRemainingNanos(swap, 2 * MS, swap + 2 * MS), "elapsed");
        assertEquals(0L, SubTickSequencer.gapRemainingNanos(swap, 2 * MS, swap + 40 * MS));
        assertEquals(2 * MS, SubTickSequencer.gapRemainingNanos(swap, 2 * MS, swap - MS), "clock skew never exceeds the gap");
    }

    @Test
    void noSwapThisTickMeansNoWait() {
        // Nothing recorded since class init / reset: the seam must not spin.
        SubTickSequencer.discardAll();
        assertEquals(0L, SubTickSequencer.swapGapRemainingNanos());
    }

    @Test
    void paceWaitIsCappedAndSkippedNearTheEndOfTheTick() {
        assertEquals(0L, SlotSync.paceWaitNanos(0L, 40.0));
        assertEquals(2 * MS, SlotSync.paceWaitNanos(2 * MS, 40.0));
        assertEquals(3_500_000L, SlotSync.paceWaitNanos(20 * MS, 40.0), "never more than 3.5 ms in-thread");
        assertEquals(0L, SlotSync.paceWaitNanos(2 * MS, SlotSync.MIN_PACE_BUDGET_MS - 0.1),
                "movement packet keeps its tick");
    }

    @Test
    void wireRayHitDistanceRequiresTheRayToEnterTheBoxWithinReach() {
        Vec3d eye = new Vec3d(0.0, 1.62, 0.0);
        Box box = new Box(-0.3, 0.0, 2.7, 0.3, 1.8, 3.3); // player-sized, centred 3 blocks ahead (+Z)
        Vec3d ahead = new Vec3d(0.0, 0.0, 1.0);
        Vec3d aside = new Vec3d(1.0, 0.0, 0.0);
        assertEquals(2.7, WireRay.hitDistance(eye, ahead, box, 3.0), 1.0e-9);
        assertEquals(-1.0, WireRay.hitDistance(eye, ahead, box, 2.5), "out of reach along the ray");
        assertEquals(-1.0, WireRay.hitDistance(eye, aside, box, 6.0), "in distance but not on the ray");
        assertEquals(0.0, WireRay.hitDistance(new Vec3d(0.0, 1.0, 3.0), ahead, box, 3.0), "eye inside the box");
    }
}
