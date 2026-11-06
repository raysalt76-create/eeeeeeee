package interstellarTikiTiks.apexx.client.manager;

import org.junit.jupiter.api.Test;

import static interstellarTikiTiks.apexx.client.manager.FaultGate.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class FaultGateTest {
    private static final long T0 = 1_000_000L;

    @Test
    void aFreshGateRuns() {
        assertTrue(new FaultGate().allowed(T0));
    }

    @Test
    void faultsOutsideAWorldNeverCountAndNeverDisable() {
        FaultGate gate = new FaultGate();
        for (int i = 0; i < 500; i++) {
            long now = T0 + i * 2_000L;
            assertTrue(gate.allowed(now), "retried every second or so");
            assertEquals(PAUSE, gate.onFault(now, false, "no player"));
        }
        assertTrue(gate.allowed(T0 + 2_000_000L));
    }

    @Test
    void theFirstTwoFaultsInAWorldOnlyPauseBriefly() {
        FaultGate gate = new FaultGate();
        assertEquals(PAUSE, gate.onFault(T0, true, "x"));
        assertFalse(gate.allowed(T0 + 100));
        assertTrue(gate.allowed(T0 + FaultGate.SHORT_PAUSE_MS));
        assertEquals(PAUSE, gate.onFault(T0 + 300, true, "x"));
    }

    @Test
    void theThirdFaultInTheWindowSuspendsWithGrowingBackoff() {
        FaultGate gate = new FaultGate();
        long now = T0;
        gate.onFault(now, true, "x");
        gate.onFault(now += 300, true, "x");
        assertEquals(SUSPEND, gate.onFault(now += 300, true, "x"));
        assertFalse(gate.allowed(now + 1_999L));
        assertTrue(gate.allowed(now + 2_000L));

        now += 2_000L;
        gate.onFault(now, true, "x");
        gate.onFault(now += 300, true, "x");
        assertEquals(SUSPEND, gate.onFault(now += 300, true, "x"));
        assertFalse(gate.allowed(now + 9_999L));
        assertTrue(gate.allowed(now + 10_000L), "second trip waits ten seconds");
    }

    @Test
    void faultsSpreadOutBeyondTheWindowNeverTrip() {
        FaultGate gate = new FaultGate();
        for (int i = 0; i < 100; i++) {
            long now = T0 + i * (FaultGate.WINDOW_MS + 1_000L);
            assertEquals(PAUSE, gate.onFault(now, true, "x"), "one fault per eleven seconds is not a failing module");
        }
    }

    @Test
    void aModuleThatKeepsTrippingIsFinallyDisabledButNotBefore() {
        FaultGate gate = new FaultGate();
        long now = T0;
        int tripsSeen = 0;
        FaultGate.Action last = PAUSE;
        for (int guard = 0; guard < 100 && last != DISABLE; guard++) {
            now = Math.max(now, T0) + 1_000L;
            // wait out any suspension, then fail three times quickly
            long waitFrom = now;
            while (!gate.allowed(waitFrom)) waitFrom += 500L;
            now = waitFrom;
            gate.onFault(now, true, "x");
            gate.onFault(now += 100, true, "x");
            last = gate.onFault(now += 100, true, "x");
            if (last == SUSPEND || last == DISABLE) tripsSeen++;
        }
        assertEquals(DISABLE, last);
        assertEquals(FaultGate.TRIPS_TO_DISABLE, tripsSeen);
    }

    @Test
    void twoQuietMinutesForgiveEarlierTrips() {
        FaultGate gate = new FaultGate();
        long now = T0;
        gate.onFault(now, true, "x");
        gate.onFault(now += 100, true, "x");
        assertEquals(SUSPEND, gate.onFault(now += 100, true, "x"));
        now += 3_000L;
        gate.onSuccess(now);
        gate.onSuccess(now + FaultGate.QUIET_RESET_MS + 1);
        long later = now + FaultGate.QUIET_RESET_MS + 2;
        gate.onFault(later, true, "x");
        gate.onFault(later += 100, true, "x");
        assertEquals(SUSPEND, gate.onFault(later += 100, true, "x"));
        assertFalse(gate.allowed(later + 1_999L));
        assertTrue(gate.allowed(later + 2_000L), "back to the first, shortest backoff");
    }

    @Test
    void resetClearsEverything() {
        FaultGate gate = new FaultGate();
        long now = T0;
        gate.onFault(now, true, "x");
        gate.onFault(now += 100, true, "x");
        gate.onFault(now += 100, true, "x");
        assertFalse(gate.allowed(now + 10));
        gate.reset();
        assertTrue(gate.allowed(now + 10));
        assertFalse(gate.faulting(now + 10));
    }
}
