package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier 6: AntiGamingChair (AGC / Minemen Club) Verification Engine
 * Implements strict wire-authoritative raycast verification, same-tick swap-action
 * sub-tick pacing, post-attack angle retention, and deferred swap-back rules.
 */
public class AgcValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // State within the current tick
    private boolean movementSeenThisTick = false;
    private boolean attackSeenThisTick = false;
    private boolean useSeenThisTick = false;
    private long lastSlotChangeNsThisTick = -1;
    private int currentHeldSlot = 0;

    // Angle retention tracking
    private int attackOccurredTick = -1;
    private float attackYaw = 0f;
    private float attackPitch = 0f;

    private long lastAttackNsAcrossTicks = -1;

    @Override
    public int tier() {
        return 6;
    }

    @Override
    public String name() {
        return "AntiGamingChair (AGC)";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {
        movementSeenThisTick = false;
        attackSeenThisTick = false;
        useSeenThisTick = false;
        lastSlotChangeNsThisTick = -1;
    }

    @Override
    public void onAction(WireFrame frame) {
        switch (frame.action()) {
            case SLOT_CHANGE -> {
                // Rule 1 & AGC: Slot change may NEVER occur after attack/use in the same movement interval
                if (attackSeenThisTick || useSeenThisTick) {
                    violations.add(new AcViolation(
                            6, "AntiGamingChair (AGC)", "BadPackets/Order (PostActionSlot)",
                            "Hotbar slot change emitted AFTER interaction in the same movement interval",
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                // AGC Rule: Slot change emitted after movement packet
                if (movementSeenThisTick) {
                    violations.add(new AcViolation(
                            6, "AntiGamingChair (AGC)", "BadPackets/Order (PostMovementSlot)",
                            "Slot change emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                // AGC Deferred Swap-Back Rule: Swap-back immediately after attack without 5-15ms deferral
                if (lastAttackNsAcrossTicks > 0) {
                    long deltaNs = frame.nanoTime() - lastAttackNsAcrossTicks;
                    if (deltaNs < 5_000_000L) { // < 5ms
                        violations.add(new AcViolation(
                                6, "AntiGamingChair (AGC)", "IllegalFastSwapBack",
                                String.format("Hotbar swap-back occurred with only %dns delta after attack (< 5ms). Must defer swap-back (5-15ms or deferSlot)", deltaNs),
                                frame.tick(), frame.nanoTime(), deltaNs / 1_000_000.0
                        ));
                    }
                }

                lastSlotChangeNsThisTick = frame.nanoTime();
                currentHeldSlot = frame.slot();
            }

            case ATTACK -> {
                attackSeenThisTick = true;
                lastAttackNsAcrossTicks = frame.nanoTime();

                // AGC Rule: No attacks after movement packet
                if (movementSeenThisTick) {
                    violations.add(new AcViolation(
                            6, "AntiGamingChair (AGC)", "TickCadence (PostMovementAttack)",
                            "Attack packet arrived after movement packet in tick " + frame.tick(),
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }

                // AGC Rule: Same-Tick Swap + Action must have sub-tick pacing (not 0ns)
                if (lastSlotChangeNsThisTick > 0) {
                    long deltaFromSwapNs = frame.nanoTime() - lastSlotChangeNsThisTick;
                    if (deltaFromSwapNs <= 0) {
                        violations.add(new AcViolation(
                                6, "AntiGamingChair (AGC)", "ZeroDeltaSwapAction",
                                "Attack executed at exact same timestamp (0ns delta) as slot swap. Must have sub-tick separation (1-3ms Direct / TimingProfile Legit)",
                                frame.tick(), frame.nanoTime(), 0.0
                        ));
                    }
                }

                // Record angles for Tick-Long Angle Retention verification
                attackOccurredTick = frame.tick();
                attackYaw = frame.yaw();
                attackPitch = frame.pitch();
            }

            case USE_ITEM, BLOCK_PLACE -> {
                useSeenThisTick = true;

                if (movementSeenThisTick) {
                    violations.add(new AcViolation(
                            6, "AntiGamingChair (AGC)", "TickCadence (PostMovementUse)",
                            "Use/Place packet arrived after movement packet in tick " + frame.tick(),
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case MOVEMENT -> {
                movementSeenThisTick = true;

                // AGC Tick-Long Angle Retention Rule:
                // If an attack occurred in this tick, the movement packet in this same tick MUST retain
                // the attack angle (within tiny epsilon), and not pivot to another target mid-tick.
                if (attackOccurredTick == frame.tick()) {
                    float yawDiff = Math.abs(frame.yaw() - attackYaw);
                    float pitchDiff = Math.abs(frame.pitch() - attackPitch);
                    if (yawDiff > 0.05f || pitchDiff > 0.05f) {
                        violations.add(new AcViolation(
                                6, "AntiGamingChair (AGC)", "AngleRetentionFailure",
                                String.format("Client failed to retain target angle for remainder of Tick %d. Attacked at (%.2f, %.2f) but moved at (%.2f, %.2f)",
                                        frame.tick(), attackYaw, attackPitch, frame.yaw(), frame.pitch()),
                                frame.tick(), frame.nanoTime(), Math.max(yawDiff, pitchDiff)
                        ));
                    }
                }
            }

            default -> {}
        }
    }

    @Override
    public void onTickEnd(int tick) {}

    @Override
    public List<AcViolation> getViolations() {
        return violations;
    }

    @Override
    public void reset() {
        violations.clear();
        movementSeenThisTick = false;
        attackSeenThisTick = false;
        useSeenThisTick = false;
        lastSlotChangeNsThisTick = -1;
        attackOccurredTick = -1;
        attackYaw = 0f;
        attackPitch = 0f;
        lastAttackNsAcrossTicks = -1;
    }
}
