package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CrystalLogicTest {

    @Test
    void damageTickBlocksBreakInsideHurtWindow() {
        assertFalse(CrystalLogic.damageTickAllows(true, 7));
        assertTrue(CrystalLogic.damageTickAllows(true, 0));
        assertTrue(CrystalLogic.damageTickAllows(false, 7));
    }

    @Test
    void holdAndToggleActivation() {
        assertTrue(CrystalLogic.isHold("Hold"));
        assertTrue(CrystalLogic.isHold("hold"));
        assertFalse(CrystalLogic.isHold("Toggle"));
        assertFalse(CrystalLogic.isHold(null));
        assertTrue(CrystalLogic.active(true, true, false));
        assertFalse(CrystalLogic.active(true, false, true));
        assertTrue(CrystalLogic.active(false, false, true));
        assertFalse(CrystalLogic.active(false, true, false));
    }

    @Test
    void damageThresholds() {
        assertTrue(CrystalLogic.damageOk(10f, 3f, 20f, 4f, 8f));
        assertFalse(CrystalLogic.damageOk(3f, 1f, 20f, 4f, 8f));
        assertFalse(CrystalLogic.damageOk(10f, 9f, 20f, 4f, 8f));
        assertFalse(CrystalLogic.damageOk(10f, 7f, 7.2f, 4f, 8f)); // would (nearly) kill us
    }

    @Test
    void pauseOnKillTimerElapses() {
        CrystalLogic.PauseGate gate = new CrystalLogic.PauseGate();
        assertFalse(gate.isPaused(0));
        gate.onKill(1000, 500);
        assertTrue(gate.isPaused(1200));
        assertFalse(gate.isPaused(1500));
        assertFalse(gate.isPaused(1600));
    }

    @Test
    void pauseOnKillZeroDurationLastsUntilReset() {
        CrystalLogic.PauseGate gate = new CrystalLogic.PauseGate();
        gate.onKill(1000, 0);
        assertTrue(gate.isPaused(Long.MAX_VALUE - 1));
        gate.reset();
        assertFalse(gate.isPaused(2000));
    }

    @Test
    void finishThenOffDefersUntilCrystalBroken() {
        CrystalLogic.FinishGate gate = new CrystalLogic.FinishGate();
        assertFalse(gate.requestDisable(true, false, 0, 1500)); // nothing pending: disable now
        assertTrue(gate.requestDisable(true, true, 0, 1500));   // crystal pending: defer
        assertTrue(gate.isFinishing());
        assertFalse(gate.isDone(true, 500));
        assertTrue(gate.isDone(false, 500));                    // crystal broken
        assertTrue(gate.isDone(true, 1500));                    // timeout
        assertFalse(gate.requestDisable(true, true, 600, 1500)); // second request forces off
    }

    @Test
    void finishThenOffCancelledByReenable() {
        CrystalLogic.FinishGate gate = new CrystalLogic.FinishGate();
        gate.requestDisable(true, true, 0, 1500);
        gate.cancel();
        assertFalse(gate.isFinishing());
        assertFalse(gate.isDone(false, 10));
    }
}
