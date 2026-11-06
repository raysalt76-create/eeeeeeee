package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Danger thresholds, humanization helpers and the offhand guard used by AutoTotem. */
class TotemPredictionTest {

    @Test
    void classifyPrefersPredictedLethalOverLowHealth() {
        assertEquals(TotemDanger.Level.PREDICTED_LETHAL, TotemDanger.classify(6f, 0f, 8f, 7f, 0f));
        assertEquals(TotemDanger.Level.LOW_HEALTH, TotemDanger.classify(6f, 0f, 8f, 2f, 0f));
        assertEquals(TotemDanger.Level.SAFE, TotemDanger.classify(20f, 0f, 8f, 5f, 1f));
        // Margin tips a near-lethal hit over.
        assertEquals(TotemDanger.Level.PREDICTED_LETHAL, TotemDanger.classify(20f, 0f, 8f, 19f, 1f));
        // Absorption counts toward the low-health threshold.
        assertEquals(TotemDanger.Level.SAFE, TotemDanger.classify(6f, 4f, 8f, 0f, 0f));
    }

    @Test
    void elytraImpactMatchesVanillaFormula() {
        assertEquals(0f, TotemDanger.elytraImpactDamage(0.2), 1e-6);
        assertEquals(7f, TotemDanger.elytraImpactDamage(1.0), 1e-6);
        assertEquals(0f, TotemDanger.elytraImpactDamage(Double.NaN), 1e-6);
    }

    @Test
    void maceSmashBonusFollowsTheThreeBands() {
        assertEquals(6f, TotemDanger.maceSmashDamage(1.0, 0, 6), 1e-6);
        assertEquals(6f + 12f, TotemDanger.maceSmashDamage(3.0, 0, 6), 1e-6);
        assertEquals(6f + 22f, TotemDanger.maceSmashDamage(8.0, 0, 6), 1e-6);
        assertEquals(6f + 24f, TotemDanger.maceSmashDamage(10.0, 0, 6), 1e-6);
        // Density 5 adds 2.5 per block.
        assertEquals(6f + 24f + 25f, TotemDanger.maceSmashDamage(10.0, 5, 6), 1e-6);
    }

    @Test
    void fatigueGrowsPerPopAndIsCapped() {
        assertEquals(1.0, TotemHumanizer.fatigueFactor(0, 4, 25), 1e-9);
        assertEquals(1.12, TotemHumanizer.fatigueFactor(3, 4, 25), 1e-9);
        assertEquals(1.25, TotemHumanizer.fatigueFactor(20, 4, 25), 1e-9);
    }

    @Test
    void popHistoryForgetsOldPops() {
        TotemHumanizer.PopHistory history = new TotemHumanizer.PopHistory();
        history.record(1_000);
        history.record(5_000);
        history.record(9_000);
        assertEquals(3, history.countWithin(10_000, 30_000));
        assertEquals(1, history.countWithin(12_000, 4_000));
    }

    @Test
    void slotPickIsNearestWithoutRandomness() {
        assertEquals(1, TotemHumanizer.pickSlot(List.of(50.0, 10.0, 30.0), 0.0, new Random(1)));
        assertEquals(-1, TotemHumanizer.pickSlot(List.of(), 50.0, new Random(1)));
    }

    @Test
    void slotPickSometimesReachesForAnother() {
        Random random = new Random(42);
        boolean other = false;
        for (int i = 0; i < 200 && !other; i++) {
            other = TotemHumanizer.pickSlot(List.of(10.0, 20.0, 30.0), 100.0, random) != 0;
        }
        assertTrue(other);
    }

    @Test
    void backoffDoublesAndCaps() {
        assertEquals(100, TotemHumanizer.backoffMs(100, 0, 2000));
        assertEquals(400, TotemHumanizer.backoffMs(100, 2, 2000));
        assertEquals(2000, TotemHumanizer.backoffMs(100, 8, 2000));
    }

    @Test
    void offhandGuardBlocksOnlyForeignModulesTakingTheTotem() {
        // Foreign module swaps a sword over the offhand totem: denied.
        assertTrue(OffhandGuard.deniesWindowClick(true, true, 36, 40, true, false, true));
        // Swapping another totem in is fine.
        assertFalse(OffhandGuard.deniesWindowClick(true, true, 36, 40, true, true, true));
        // Picking up the offhand slot directly: denied.
        assertTrue(OffhandGuard.deniesWindowClick(true, true, 45, 0, false, false, true));
        // Guard off, or no totem in the offhand: allowed.
        assertFalse(OffhandGuard.deniesWindowClick(false, true, 36, 40, true, false, true));
        assertFalse(OffhandGuard.deniesWindowClick(true, false, 36, 40, true, false, true));
        // Unrelated click: allowed.
        assertFalse(OffhandGuard.deniesWindowClick(true, true, 20, 0, false, false, true));
        assertTrue(OffhandGuard.deniesSwapKey(true, true, false));
        assertFalse(OffhandGuard.deniesSwapKey(true, true, true));
        assertFalse(OffhandGuard.deniesSwapKey(false, true, false));
    }

    @Test
    void offhandGuardExemptsTheProtectorAndUnownedInput() {
        Object autoTotem = new Object();
        Object other = new Object();
        OffhandGuard.update(autoTotem, true);
        try {
            assertTrue(OffhandGuard.blocksActor(other));
            assertFalse(OffhandGuard.blocksActor(autoTotem));
            assertFalse(OffhandGuard.blocksActor(null));
        } finally {
            OffhandGuard.release(autoTotem);
        }
        assertFalse(OffhandGuard.blocksActor(other));
    }
}
