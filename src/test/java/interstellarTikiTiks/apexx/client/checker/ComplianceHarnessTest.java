package interstellarTikiTiks.apexx.client.checker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ComplianceHarnessTest {
    private ComplianceHarness harness;

    @BeforeEach
    void setUp() {
        harness = ComplianceHarness.strict();
    }

    @Test
    void testVanillaAttackSequenceIs100PercentClean() {
        int tick = 10;
        harness.startTick(tick);

        harness.recordKeyInput(1, 0); // forward
        harness.advanceTimeMs(1);

        harness.recordSlotChange(0); // sword
        harness.advanceTimeMs(2);

        float yaw = 45.0f;
        float pitch = 10.0f;
        harness.advanceTimeMs(1);

        harness.recordAttack(42, 2.7, yaw, pitch); // reach 2.7m < 3.0m
        harness.recordSwing(yaw, pitch);
        harness.advanceTimeMs(10);

        // Retain angle during movement packet (Rule 5 & AGC)
        harness.recordMovement(10.0, 64.0, 10.0, yaw, pitch, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    void testSameTickSwapAndAttackWithSubTickDelay() {
        int tick = 15;
        harness.startTick(tick);

        // Swap to axe (slot 1)
        harness.recordSlotChange(1);
        harness.advanceTimeMs(2); // 2ms sub-tick separation (Direct mode)

        float targetYaw = 60.0f;
        float targetPitch = 5.0f;
        harness.advanceTimeMs(1);

        harness.recordAttack(99, 2.8, targetYaw, targetPitch);
        harness.recordSwing(targetYaw, targetPitch);
        harness.advanceTimeMs(15);

        // Movement packet in same tick retains angle
        harness.recordMovement(0.0, 64.0, 0.0, targetYaw, targetPitch, true);
        harness.endTick(tick);

        // Tick 16: Deferred swap-back
        harness.advanceTimeMs(32); // Complete 50ms tick
        harness.startTick(tick + 1);
        harness.advanceTimeMs(5); // 5ms deferred restore
        harness.recordSlotChange(0); // restore original sword
        harness.recordMovement(0.0, 64.0, 0.0, targetYaw, targetPitch, true);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    void testGrimCatchesMissingSwing() {
        int tick = 20;
        harness.startTick(tick);
        harness.recordAttack(1, 2.5, 0f, 0f); // NO swing!
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.checkName().equals("PacketOrderB")));
    }

    @Test
    void testGrimCatchesPostMovementAttack() {
        int tick = 21;
        harness.startTick(tick);
        harness.recordMovement(0, 64, 0, 0f, 0f, true); // Movement closes interaction window!
        harness.recordAttack(1, 2.5, 0f, 0f); // Post-movement attack!
        harness.recordSwing(0f, 0f);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.checkName().equals("PacketOrderO")));
    }

    @Test
    void testGrimCatchesExcessiveReach() {
        int tick = 22;
        harness.startTick(tick);
        harness.recordAttack(1, 3.4, 0f, 0f); // 3.4m > 3.0m
        harness.recordSwing(0f, 0f);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.checkName().equals("Reach")));
    }

    @Test
    void testVulcanCatchesCpsCap() {
        // Send 27 clicks in 500ms (> 25 CPS)
        for (int i = 0; i < 27; i++) {
            harness.startTick(100 + i);
            harness.advanceTimeMs(15);
            harness.recordSwing(0f, 0f);
            harness.endTick(100 + i);
        }

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Vulcan 2.9.7") && v.checkName().contains("Autoclicker")));
    }

    @Test
    void testVulcanCatchesZeroDeltaSwap() {
        int tick = 30;
        harness.startTick(tick);
        harness.recordSlotChange(2);
        // 0ns delta between slot swap and attack
        harness.recordAttack(5, 2.0, 0f, 0f);
        harness.recordSwing(0f, 0f);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Vulcan 2.9.7") && v.checkName().equals("FastSwitch")));
    }

    @Test
    void testMatrixCatchesReach3_2m() {
        int tick = 40;
        harness.startTick(tick);
        harness.recordAttack(5, 3.25, 0f, 0f); // > 3.15m
        harness.recordSwing(0f, 0f);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Matrix 1.21.10") && v.checkName().equals("Reach")));
    }

    @Test
    void testMxacCatchesSnapAndFreeze() {
        // Feed 3 samples: 0.5° delta, then 45° snap, then 0.2° stillness
        harness.startTick(50);
        harness.recordMovement(0, 64, 0, 10.0f, 0f, true);
        harness.endTick(50);

        harness.startTick(51);
        harness.recordMovement(0, 64, 0, 10.5f, 0f, true); // delta = 0.5° (< 1.2°)
        harness.endTick(51);

        harness.startTick(52);
        harness.recordMovement(0, 64, 0, 55.5f, 0f, true); // delta = 45.0° (> 35°)
        harness.endTick(52);

        harness.startTick(53);
        harness.recordMovement(0, 64, 0, 55.7f, 0f, true); // delta = 0.2° (< 1.2°)
        harness.endTick(53);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("MXAc") && v.checkName().equals("AimFactorCheck")));
    }

    @Test
    void testIntaveCatchesRotationSnapInAttackWindow() {
        // Delta sequence: 5° (t-2) -> 45° (t-1) -> 5° (t0) within attack window
        harness.startTick(60);
        harness.recordMovement(0, 64, 0, 0.0f, 0f, true);
        harness.endTick(60);

        harness.startTick(61);
        harness.recordMovement(0, 64, 0, 5.0f, 0f, true); // motion = 5° (< 9°)
        harness.endTick(61);

        harness.startTick(62);
        harness.recordAttack(10, 2.5, 50.0f, 0f);
        harness.recordSwing(50.0f, 0f); // Interaction!
        harness.recordMovement(0, 64, 0, 50.0f, 0f, true); // motion = 45° (> 40°)
        harness.endTick(62);

        harness.startTick(63);
        harness.advanceTimeMs(50);
        harness.recordMovement(0, 64, 0, 55.0f, 0f, true); // motion = 5° (< 9°)
        harness.endTick(63);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Intave") && v.checkName().equals("RotationSnapHeuristic")));
    }

    @Test
    void testIntaveCatchesToolSwitchDuringBlockBreak() {
        int tick = 70;
        harness.startTick(tick);
        harness.recordBlockDigStart(10, 64, 10);
        // Switch tool at break start (<= 1 tick)
        harness.recordSlotChange(3);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Intave") && v.checkName().equals("ToolSwitchHeuristic")));
    }

    @Test
    void testAgcCatchesPostAttackSlotChange() {
        int tick = 80;
        harness.startTick(tick);
        harness.recordAttack(1, 2.5, 0f, 0f);
        harness.recordSwing(0f, 0f);
        // Illegal post-action slot change in same tick!
        harness.advanceTimeMs(2);
        harness.recordSlotChange(0);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("AntiGamingChair (AGC)") && v.checkName().contains("PostActionSlot")));
    }

    @Test
    void testAgcCatchesAngleRetentionFailure() {
        int tick = 90;
        harness.startTick(tick);
        harness.recordAttack(1, 2.5, 45.0f, 0f); // Attacked at yaw 45°
        harness.recordSwing(45.0f, 0f);
        harness.advanceTimeMs(5);
        // Failed to retain angle: pivoted to yaw 120° in movement packet within same tick!
        harness.recordMovement(0, 64, 0, 120.0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("AntiGamingChair (AGC)") && v.checkName().equals("AngleRetentionFailure")));
    }

    @Test
    void testGrimCatchesDuplicateSlotId() {
        int tick = 95;
        harness.startTick(tick);
        harness.recordSlotChange(3);
        harness.advanceTimeMs(5);
        harness.recordSlotChange(3); // duplicate slot!
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Grim 2.0") && v.checkName().equals("BadPacketsA")));
    }

    @Test
    void testGrimCatchesInvalidPitch() {
        int tick = 96;
        harness.startTick(tick);
        harness.recordMovement(0, 64, 0, 0f, 95.5f, true); // pitch > 90°!
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Grim 2.0") && v.checkName().equals("BadPacketsD")));
    }

    @Test
    void testGrimCatchesOutOfBoundsSlot() {
        int tick = 97;
        harness.startTick(tick);
        harness.recordSlotChange(9); // slot 9 is out of bounds [0..8]!
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Grim 2.0") && v.checkName().equals("BadPacketsY")));
    }

    @Test
    void testGrimCatchesInteractOrderViolation() {
        int tick = 98;
        harness.startTick(tick);
        // INTERACT sent without prior INTERACT_AT!
        harness.recordInteract(10, 2.0);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Grim 2.0") && v.checkName().equals("PacketOrderC")));
    }

    @Test
    void testVulcanCatchesTimerInflation() {
        harness.startTick(1);
        harness.endTick(1);

        // Next tick arrives only 20ms later (< 40ms) -> Timer inflation!
        harness.advanceTimeMs(20);
        harness.startTick(2);
        harness.endTick(2);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Vulcan 2.9.7") && v.checkName().equals("Timer")));
    }

    @Test
    void testIntaveCatchesSilentMovementCounterStrafing() {
        // Tick 1: Moving straight forward (yaw = 0°, forward = 1, strafe = 0 -> world angle = 0°)
        harness.startTick(110);
        harness.recordKeyInput(1, 0);
        harness.recordMovement(0, 64, 0, 0.0f, 0f, true);
        harness.endTick(110);

        // Tick 2: Snaps head to 90° right, but switches input to strafe left (forward = 0, strafe = -1)
        // World angle = 90° - 90° = 0° (world trajectory unchanged despite 90° head snap!)
        harness.startTick(111);
        harness.recordKeyInput(0, -1);
        harness.recordMovement(0, 64, 0, 90.0f, 0f, true);
        harness.endTick(111);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Intave") && v.checkName().equals("SilentMovementHeuristic")));
    }

    @Test
    void testAgcCatchesFastSwapBackWithoutDeferral() {
        int tick = 120;
        harness.startTick(tick);
        harness.recordSlotChange(1); // swap to axe
        harness.advanceTimeMs(2);
        harness.recordAttack(1, 2.5, 0f, 0f);
        harness.recordSwing(0f, 0f);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        // Next tick: swaps back immediately with only 1ms delta (< 5ms)
        harness.advanceTimeMs(1);
        harness.startTick(tick + 1);
        harness.recordSlotChange(0);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertFalse(report.isClean());
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("AntiGamingChair (AGC)") && v.checkName().equals("IllegalFastSwapBack")));
    }
}
