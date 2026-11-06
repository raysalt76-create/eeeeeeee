package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeleeMechanicsTest {
    private static final double G = MeleeMechanics.DEFAULT_GRAVITY;

    @Test
    void critStateNowIsZeroTicks() {
        assertEquals(0, MeleeMechanics.ticksUntilFalling(true, 0.3, G, 20));
    }

    @Test
    void groundKnockbackLiftFallsOnTheSixthMove() {
        // Ground knockback sets vy to 0.4: moves 0.4, 0.314, 0.229, 0.146, 0.065, then -0.015.
        // The first downward move is index 5, so fallDistance > 0 from tick 6.
        assertEquals(6, MeleeMechanics.ticksUntilFalling(false, 0.4, G, 20));
    }

    @Test
    void plainJumpFallsOnTheSeventhMove() {
        // 0.42, 0.333, 0.248, 0.165, 0.083, 0.003, then -0.075.
        assertEquals(7, MeleeMechanics.ticksUntilFalling(false, 0.42, G, 20));
    }

    @Test
    void alreadyDescendingFallsAfterOneMove() {
        assertEquals(1, MeleeMechanics.ticksUntilFalling(false, -0.1, G, 20));
    }

    @Test
    void windowBeyondBudgetIsNone() {
        assertEquals(MeleeMechanics.NONE, MeleeMechanics.ticksUntilFalling(false, 0.42, G, 6));
    }

    @Test
    void verticalOffsetMatchesManualIntegration() {
        double expected = 0.42 + 0.3332 + (0.3332 - G) * MeleeMechanics.VERTICAL_DRAG;
        assertEquals(expected, MeleeMechanics.verticalOffset(0.42, G, 3), 1.0e-9);
        assertEquals(0.0, MeleeMechanics.verticalOffset(0.42, G, 0), 0.0);
    }

    // ── Crit sync ──────────────────────────────────────────────────────────────────

    /** Flat ground at the jump's take-off height. */
    private static int critSwing(double startVy, boolean critNow, int fallen, int delay, int min, double heightAboveGround) {
        return MeleeMechanics.ticksUntilCritSwing(startVy, G, critNow, fallen, delay, min, 18,
                y -> y < -heightAboveGround);
    }

    @Test
    void jumpCritLandsOnTheConfiguredFallingTick() {
        // First downward move is the 7th; with one falling tick of delay the swing is on the 8th.
        assertEquals(7, critSwing(0.42, false, 0, 0, 0, 0.0));
        assertEquals(8, critSwing(0.42, false, 0, 1, 0, 0.0));
        assertEquals(9, critSwing(0.42, false, 0, 2, 0, 0.0));
    }

    @Test
    void spamJumpSwingsOnTheSameTickWhereverTheWeaponBecameReady() {
        // Re-plan from every tick of one jump: the absolute swing tick must not move.
        double vy = 0.42;
        double height = 0.0;
        int fallStart = -1;
        for (int k = 0; k <= 8; k++) {
            boolean critNow = fallStart >= 0;
            int fallen = critNow ? k - fallStart : 0;
            int t = critSwing(vy, critNow, fallen, 1, 0, height);
            assertEquals(8, k + t, "re-planned at tick " + k);
            // Advance one move.
            if (vy < 0.0 && fallStart < 0) fallStart = k + 1;
            height += vy;
            vy = (vy - G) * MeleeMechanics.VERTICAL_DRAG;
        }
    }

    @Test
    void swordSpamJumpCritsEveryJumpOnTheSyncTickEvenAfterALateJump() {
        // Sword: strong (> 0.90 charge) 11 ticks after a hit, full at 12.5; a jump cycle is 12 ticks.
        // Planning against the strong charge keeps every crit on the second falling tick (take-off
        // + 8), including after a jump that started a tick late.
        int lastHit = -100;
        int jump = 0;
        for (int k = 0; k < 20; k++) {
            if (k == 7) jump++;               // one late jump
            int strongTicks = Math.max(0, lastHit + 11 - jump);
            int t = critSwing(0.42, false, 0, 1, strongTicks, 0.0);
            assertEquals(jump + 8, jump + t, "jump " + k + " drifted");
            lastHit = jump + t;
            jump += 12;
        }
    }

    @Test
    void waitingForFullSwordChargeDriftsUntilAJumpHasNoCrit() {
        // The old full-charge plan: 13 ticks between swings against a 12-tick cycle.
        int lastHit = -100;
        int jump = 0;
        boolean missed = false;
        for (int k = 0; k < 20 && !missed; k++) {
            int fullTicks = Math.max(0, lastHit + 13 - jump);
            int t = critSwing(0.42, false, 0, 1, fullTicks, 0.0);
            if (t == MeleeMechanics.NONE) {
                missed = true;
            } else {
                lastHit = jump + t;
            }
            jump += 12;
        }
        assertTrue(missed, "full-charge planning was expected to skip a jump");
    }

    @Test
    void chargeLaterThanTheFallDelayStillSwingsWhileFalling() {
        assertEquals(10, critSwing(0.42, false, 0, 1, 10, 0.0));
    }

    @Test
    void shortDropLandingBeforeTheDelaySwingsOnTheLastFallingTick() {
        // Moves -0.1 then -0.176: the second one lands, so the only falling tick is the first.
        assertEquals(1, critSwing(-0.1, false, 0, 2, 0, 0.2));
        // Already falling, landing next move: swing now rather than lose the crit.
        assertEquals(0, critSwing(-0.3, true, 0, 2, 0, 0.1));
    }

    @Test
    void weaponNotReadyBeforeLandingIsNone() {
        assertEquals(MeleeMechanics.NONE, critSwing(0.42, false, 0, 1, 14, 0.0));
    }

    @Test
    void airDriftFactorIsGeometricSum() {
        assertEquals(0.0, MeleeMechanics.airDriftFactor(0), 0.0);
        assertEquals(1.0, MeleeMechanics.airDriftFactor(1), 1.0e-12);
        assertEquals(1.0 + 0.91 + 0.91 * 0.91, MeleeMechanics.airDriftFactor(3), 1.0e-12);
    }
}
