package interstellarTikiTiks.apexx.client.checker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CombatModulesComplianceTest
 * Rigorous multi-tier verification suite running the 6-tier ComplianceHarness
 * against the exact wire packet sequences and kinematic profiles produced by
 * Apexx combat modules:
 * - AutoShieldBreak (Break, Stun, Swap-Back)
 * - AutoCrystal & AutoHitCrystal (Obsidian placement, Crystal placement, Detonation)
 * - AutoMace (Smash fall, stun slam)
 * - TriggerBot (Reach clamp, GCD quantization, attack-then-swing as vanilla 1.9+ sends it)
 * - BreachSwap (Mace swap-hit, deferred restore)
 * - PearlCatch & PearlGrapple (Solver vector aim, swap-use, angle retention)
 * - AutoWeb (Trap placement, live raycast)
 * - WTap & SprintReset (Sprint toggle packet ordering)
 * - SafeAnchor (Place, glowstone charge, detonate)
 * - SpearMaceDtap (Spear lunge followed by mace strike)
 */
public class CombatModulesComplianceTest {
    private ComplianceHarness harness;

    @BeforeEach
    void setUp() {
        harness = ComplianceHarness.strict();
    }

    @Test
    @DisplayName("AutoShieldBreak: Standard Break Sequence Compliance")
    void testAutoShieldBreakStandardExecution() {
        int tick = 100;
        harness.startTick(tick);

        // Player walking forward
        harness.recordKeyInput(1, 0);
        harness.advanceTimeMs(1);

        // AutoShieldBreak spots raised shield, swaps to axe (slot 1)
        harness.recordSlotChange(1);
        harness.advanceTimeMs(2); // Direct mode 2ms sub-tick separation

        float targetYaw = 42.5f;
        float targetPitch = 12.0f;
        harness.advanceTimeMs(1);

        // Attack entity within 2.6m reach, then its arm swing (vanilla 1.9+ doAttack order)
        harness.recordAttack(10, 2.6, targetYaw, targetPitch);
        harness.recordSwing(targetYaw, targetPitch);
        harness.advanceTimeMs(15);

        // Movement packet in Tick N retains target angle (AGC rule)
        harness.recordMovement(5.0, 64.0, 5.0, targetYaw, targetPitch, true);
        harness.endTick(tick);

        // Tick N+1: Deferred restore to sword (slot 0)
        harness.advanceTimeMs(32);
        harness.startTick(tick + 1);
        harness.advanceTimeMs(6); // 6ms deferred swap-back (> 5ms)
        harness.recordSlotChange(0);
        harness.recordMovement(5.1, 64.0, 5.1, targetYaw, targetPitch, true);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("AutoShieldBreak: the old swing-before-attack (1.8) order is flagged by Grim PacketOrderB")
    void testAutoShieldBreakLegacySwingOrderIsFlagged() {
        int tick = 100;
        harness.startTick(tick);
        harness.recordSlotChange(1);
        harness.advanceTimeMs(2);
        // What AutoShieldBreak sent before the fix: ANIMATION -> INTERACT(attack) -> movement.
        harness.recordSwing(0f, 0f);
        harness.recordAttack(10, 2.6, 0f, 0f);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.getViolations().stream().anyMatch(v -> v.antiCheat().equals("Grim 2.0")
                && v.checkName().equals("PacketOrderB")), report.formatReport());
    }

    @Test
    @DisplayName("AutoShieldBreak: Auto Stun Follow-Up Clicks Compliance")
    void testAutoShieldBreakWithAutoStunCadence() {
        int tick = 110;
        harness.startTick(tick);

        // Axe swap
        harness.recordSlotChange(1);
        harness.advanceTimeMs(2);

        float yaw = 30.0f;
        float pitch = 5.0f;

        // Break hit: attack, then its swing (vanilla 1.9+ order). One attack per movement interval, so
        // each follow-up goes out in a later tick once its gap has passed.
        harness.recordAttack(10, 2.7, yaw, pitch);
        harness.recordSwing(yaw, pitch);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, yaw, pitch, true);
        harness.endTick(tick);

        // Tick N+1: first stun click (>= 19 ms after the break hit)
        harness.advanceTimeMs(33);
        harness.startTick(tick + 1);
        harness.advanceTimeMs(1);
        harness.recordAttack(10, 2.7, yaw, pitch);
        harness.recordSwing(yaw, pitch);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, yaw, pitch, true);
        harness.endTick(tick + 1);

        // Tick N+2: second stun click (>= 30 ms after the first)
        harness.advanceTimeMs(34);
        harness.startTick(tick + 2);
        harness.advanceTimeMs(1);
        harness.recordAttack(10, 2.7, yaw, pitch);
        harness.recordSwing(yaw, pitch);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, yaw, pitch, true);
        harness.endTick(tick + 2);

        // Tick N+3: deferred restore to sword, never in a hit's interval
        harness.advanceTimeMs(29);
        harness.startTick(tick + 3);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0);
        harness.recordMovement(0, 64, 0, yaw, pitch, true);
        harness.endTick(tick + 3);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("AutoCrystal & AutoHitCrystal: Placement and Detonation Compliance")
    void testAutoCrystalPlacementAndDetonationCadence() {
        int tick = 200;

        // Tick N: Select Obsidian (slot 2) and place on ground
        harness.startTick(tick);
        harness.recordSlotChange(2);
        harness.advanceTimeMs(2);
        harness.recordBlockPlace(10, 63, 10);
        harness.advanceTimeMs(15);
        float aimYaw = 15.0f;
        float aimPitch = 35.0f;
        harness.recordMovement(10, 64, 8, aimYaw, aimPitch, true);
        harness.endTick(tick);

        // Tick N+1: Select End Crystal (slot 3) and place on top of obsidian
        harness.advanceTimeMs(33); // 50ms tick
        harness.startTick(tick + 1);
        harness.recordSlotChange(3);
        harness.advanceTimeMs(2);
        harness.recordBlockPlace(10, 64, 10);
        harness.advanceTimeMs(15);
        harness.recordMovement(10, 64, 8, aimYaw, aimPitch, true);
        harness.endTick(tick + 1);

        // Tick N+2: Detonate Crystal (Attack Crystal Entity)
        harness.advanceTimeMs(33);
        harness.startTick(tick + 2);
        harness.advanceTimeMs(2);
        harness.advanceTimeMs(1);
        harness.recordAttack(555 /* Crystal Entity */, 2.8, aimYaw, aimPitch);
        harness.recordSwing(aimYaw, aimPitch);
        harness.advanceTimeMs(15);
        harness.recordMovement(10, 64, 8, aimYaw, aimPitch, true);
        harness.endTick(tick + 2);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("AutoMace: Smash Fall and Finish Strike Compliance")
    void testAutoMaceSmashExecution() {
        int tick = 300;
        harness.startTick(tick);

        // Player falling from height (y decreases)
        float maceYaw = 85.0f;
        float macePitch = 25.0f;

        // Select Mace (slot 4)
        harness.recordSlotChange(4);
        harness.advanceTimeMs(2);

        // Smash hit
        harness.advanceTimeMs(1);
        harness.recordAttack(77, 2.6, maceYaw, macePitch);
        harness.recordSwing(maceYaw, macePitch);
        harness.advanceTimeMs(15);

        // Retain aim angle through movement
        harness.recordMovement(0, 68.0, 0, maceYaw, macePitch, false);
        harness.endTick(tick);

        // Tick N+1: Deferred restore to sword
        harness.advanceTimeMs(32);
        harness.startTick(tick + 1);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0);
        harness.recordMovement(0, 64.0, 0, maceYaw, macePitch, true);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("TriggerBot: Full Attack Cycle and GCD Compliance")
    void testTriggerBotCooldownAndReachGating() {
        float baseYaw = 0.0f;
        float basePitch = 0.0f;

        // Vanilla mouse sensitivity GCD step at sens = 0.5:
        // f = 0.5 * 0.6 + 0.2 = 0.5
        // gcd = 0.5^3 * 8.0 * 0.15 = 0.125 * 8.0 * 0.15 = 0.15°
        float gcd = 0.15f;

        for (int i = 0; i < 15; i++) {
            int tick = 400 + i;
            harness.startTick(tick);
            harness.advanceTimeMs(2);

            // Rotate smoothly with integer GCD increments
            baseYaw += gcd * 2; // +0.30°
            basePitch += gcd * 1; // +0.15°

            // TriggerBot attacks when charge is full (every 12-15 ticks for sword)
            if (i == 1 || i == 13) {
                harness.advanceTimeMs(1);
                // Reach 2.85m (< 3.0m)
                harness.recordAttack(20, 2.85, baseYaw, basePitch);
                harness.recordSwing(baseYaw, basePitch);
                harness.advanceTimeMs(10);
                harness.advanceTimeMs(37); // complete 50ms tick
            } else {
                harness.advanceTimeMs(48); // complete 50ms tick
            }

            harness.recordMovement(i * 0.2, 64.0, 0, baseYaw, basePitch, true);
            harness.endTick(tick);
        }

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("BreachSwap: Swap on Armor Breach and Restore Compliance")
    void testBreachSwapExecution() {
        int tick = 500;
        harness.startTick(tick);

        // Sword attack
        harness.recordAttack(30, 2.7, 0f, 0f);
        harness.recordSwing(0f, 0f);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        // Target shield breaks -> Swap to Breach Mace (slot 5) in next attack tick
        harness.advanceTimeMs(35);
        harness.startTick(tick + 1);
        harness.recordSlotChange(5);
        harness.advanceTimeMs(2);

        harness.recordAttack(30, 2.5, 0f, 0f);
        harness.recordSwing(0f, 0f);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick + 1);

        // Deferred restore
        harness.advanceTimeMs(33);
        harness.startTick(tick + 2);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0); // restore sword
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick + 2);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("PearlCatch & PearlGrapple: Intercept Aim and Throw Compliance")
    void testPearlCatchTrajectoryThrow() {
        int tick = 600;
        harness.startTick(tick);

        // Solver computes required aim vector (yaw: 120.45°, pitch: -24.3°)
        float solverYaw = 120.45f;
        float solverPitch = -24.3f;

        // Select pearl slot (slot 6)
        harness.recordSlotChange(6);
        harness.advanceTimeMs(2);

        // Throw pearl (UseItem)
        harness.recordUseItem();
        harness.advanceTimeMs(15);

        // Movement packet in same tick retains aim direction
        harness.recordMovement(10, 70, 10, solverYaw, solverPitch, false);
        harness.endTick(tick);

        // Deferred restore to weapon next tick
        harness.advanceTimeMs(33);
        harness.startTick(tick + 1);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0);
        harness.recordMovement(10, 69.5, 10, solverYaw, solverPitch, false);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("AutoWeb: Placement and Trap Cadence Compliance")
    void testAutoWebPlacementCadence() {
        int tick = 700;
        harness.startTick(tick);

        // Select Cobweb (slot 7)
        harness.recordSlotChange(7);
        harness.advanceTimeMs(2);

        // Place cobweb at target feet
        harness.recordBlockPlace(12, 64, 12);
        harness.advanceTimeMs(15);

        harness.recordMovement(10, 64, 10, 45f, 30f, true);
        harness.endTick(tick);

        // Restore weapon
        harness.advanceTimeMs(33);
        harness.startTick(tick + 1);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0);
        harness.recordMovement(10, 64, 10, 45f, 30f, true);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("WTap & SprintReset: Key Input Packet Sequence Compliance")
    void testWTapSprintResetCadence() {
        int tick = 800;
        harness.startTick(tick);

        // Attack opponent while moving forward
        harness.recordKeyInput(1, 0); // forward
        harness.recordAttack(40, 2.7, 0f, 0f);
        harness.recordSwing(0f, 0f);
        harness.advanceTimeMs(10);

        // WTap releases forward key before movement packet
        harness.recordKeyInput(0, 0);
        harness.recordMovement(0, 64, 0, 0f, 0f, true);
        harness.endTick(tick);

        // Next tick resumes forward key (SprintReset)
        harness.advanceTimeMs(40);
        harness.startTick(tick + 1);
        harness.recordKeyInput(1, 0);
        harness.recordMovement(0.2, 64, 0, 0f, 0f, true);
        harness.endTick(tick + 1);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("SafeAnchor: Charge and Detonate Cycle Compliance")
    void testSafeAnchorChargeAndDetonateCadence() {
        int tick = 900;
        harness.startTick(tick);

        // 1. Place Respawn Anchor (slot 8)
        harness.recordSlotChange(8);
        harness.advanceTimeMs(2);
        harness.recordBlockPlace(5, 64, 5);
        harness.advanceTimeMs(15);
        harness.recordMovement(5, 64, 3, 0f, 45f, true);
        harness.endTick(tick);

        // 2. Charge with Glowstone on next tick
        harness.advanceTimeMs(33);
        harness.startTick(tick + 1);
        harness.recordSlotChange(7); // glowstone
        harness.advanceTimeMs(2);
        harness.recordUseItem(); // charge anchor
        harness.advanceTimeMs(15);
        harness.recordMovement(5, 64, 3, 0f, 45f, true);
        harness.endTick(tick + 1);

        // 3. Detonate anchor with non-glowstone item
        harness.advanceTimeMs(33);
        harness.startTick(tick + 2);
        harness.recordSlotChange(0); // totem or sword
        harness.advanceTimeMs(2);
        harness.recordUseItem(); // detonate!
        harness.advanceTimeMs(15);
        harness.recordMovement(5, 64, 3, 0f, 45f, true);
        harness.endTick(tick + 2);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }

    @Test
    @DisplayName("SpearMaceDtap: Spear Lunge to Mace Slam Transition Compliance")
    void testSpearMaceDtapCadence() {
        int tick = 1000;
        harness.startTick(tick);

        // Spear lunge (slot 2)
        harness.recordSlotChange(2);
        harness.advanceTimeMs(2);
        harness.recordAttack(60, 2.9, 10f, 5f);
        harness.recordSwing(10f, 5f);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, 10f, 5f, true);
        harness.endTick(tick);

        // Swap to Mace (slot 4) on tick N+1
        harness.advanceTimeMs(33);
        harness.startTick(tick + 1);
        harness.recordSlotChange(4);
        harness.advanceTimeMs(2);
        harness.recordAttack(60, 2.6, 10f, 5f);
        harness.recordSwing(10f, 5f);
        harness.advanceTimeMs(15);
        harness.recordMovement(0, 64, 0, 10f, 5f, true);
        harness.endTick(tick + 1);

        // Deferred restore to weapon
        harness.advanceTimeMs(33);
        harness.startTick(tick + 2);
        harness.advanceTimeMs(6);
        harness.recordSlotChange(0);
        harness.recordMovement(0, 64, 0, 10f, 5f, true);
        harness.endTick(tick + 2);

        ComplianceReport report = harness.evaluate();
        assertTrue(report.isClean(), report.formatReport());
    }
}
