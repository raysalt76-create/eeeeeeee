package interstellarTikiTiks.apexx.client.module.implementation.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceDoubleTapSmashFallTest {

    @Test
    void glideDescentCountsOnlyUpToTheServerGlideCap() {
        // Dove 5 blocks during the chain, nothing yet since the glide ended: the server holds at most 1.0.
        assertEquals(MaceDoubleTap.GLIDE_FALL_CAP, MaceDoubleTap.serverFallLowerBound(100.0, 95.0, 95.0), 1.0e-9);
        // A shallow chain descent is all the bound may assume.
        assertEquals(0.4, MaceDoubleTap.serverFallLowerBound(100.0, 99.6, 99.6), 1.0e-9);
    }

    @Test
    void descentAfterTheGlideEndsAddsUncapped() {
        assertEquals(2.3, MaceDoubleTap.serverFallLowerBound(100.0, 95.0, 93.7), 1.0e-9);
        assertEquals(1.6, MaceDoubleTap.serverFallLowerBound(100.0, 99.6, 98.4), 1.0e-9);
    }

    @Test
    void ascentsNeverRaiseOrLowerTheBoundBelowZero() {
        assertEquals(0.0, MaceDoubleTap.serverFallLowerBound(100.0, 101.0, 102.0), 1.0e-9);
        assertEquals(MaceDoubleTap.GLIDE_FALL_CAP, MaceDoubleTap.serverFallLowerBound(100.0, 95.0, 96.0), 1.0e-9);
    }

    @Test
    void theSmashWaitsAtLeastOneBlockPastADeepGlide() {
        assertTrue(MaceDoubleTap.serverFallLowerBound(100.0, 90.0, 89.1) < MaceDoubleTap.SMASH_SERVER_FALL);
        assertTrue(MaceDoubleTap.serverFallLowerBound(100.0, 90.0, 89.0) >= MaceDoubleTap.SMASH_SERVER_FALL);
    }
}
