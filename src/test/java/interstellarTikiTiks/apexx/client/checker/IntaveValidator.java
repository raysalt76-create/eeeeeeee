package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier 5: Intave Verification Engine
 * Implements RotationSnapHeuristic, Silent Movement counter-strafing detection,
 * ToolSwitchHeuristic during block breaks, and NoSwingHeuristic.
 */
public class IntaveValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // Rotation snap tracking
    private float prevYaw = -9999f;
    private double yawMotionT2 = 0;
    private double yawMotionT1 = 0;
    private double yawMotionT0 = 0;

    // Recent combat interaction timestamps (within 150ms / 3 ticks)
    private long lastInteractionTimeNs = -1;

    // Movement strafe tracking for Silent Movement
    private int lastForward = 0;
    private int lastStrafe = 0;

    // Block break tracking for ToolSwitchHeuristic
    private int ticksSinceBreakStart = 999;
    private int ticksSinceBreakStop = 999;

    // Per-tick swing & attack tracking for NoSwingHeuristic
    private boolean swingThisTick = false;
    private boolean attackThisTick = false;

    @Override
    public int tier() {
        return 5;
    }

    @Override
    public String name() {
        return "Intave";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {
        swingThisTick = false;
        attackThisTick = false;
        ticksSinceBreakStart++;
        ticksSinceBreakStop++;
    }

    @Override
    public void onAction(WireFrame frame) {
        switch (frame.action()) {
            case SWING -> {
                swingThisTick = true;
                lastInteractionTimeNs = frame.nanoTime();
            }

            case ATTACK -> {
                attackThisTick = true;
                lastInteractionTimeNs = frame.nanoTime();
            }

            case BLOCK_PLACE -> {
                lastInteractionTimeNs = frame.nanoTime();
            }

            case BLOCK_DIG_START -> {
                ticksSinceBreakStart = 0;
            }

            case BLOCK_DIG_STOP -> {
                ticksSinceBreakStop = 0;
            }

            case SLOT_CHANGE -> {
                // ToolSwitchHeuristic: Slot changes sent right as block break starts or stops (<= 1 tick)
                if (ticksSinceBreakStart <= 1 || ticksSinceBreakStop <= 1) {
                    violations.add(new AcViolation(
                            5, "Intave", "ToolSwitchHeuristic",
                            String.format("Suspicious slot packet while breaking blocks (startTicks: %d, stopTicks: %d)",
                                    ticksSinceBreakStart, ticksSinceBreakStop),
                            frame.tick(), frame.nanoTime(), Math.min(ticksSinceBreakStart, ticksSinceBreakStop)
                    ));
                }
            }

            case MOVEMENT -> {
                // NoSwingHeuristic (intave NoSwingHeuristic#receiveMovementPacket): an attack in this movement
                // interval with no arm swing anywhere in it. The order inside the interval does not matter.
                if (attackThisTick && !swingThisTick) {
                    violations.add(new AcViolation(
                            5, "Intave", "NoSwingHeuristic",
                            "Missing swing packet on attack interaction in tick " + frame.tick(),
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
                attackThisTick = false;
                swingThisTick = false;
                float yaw = frame.yaw();
                if (prevYaw != -9999f) {
                    yawMotionT2 = yawMotionT1;
                    yawMotionT1 = yawMotionT0;
                    yawMotionT0 = Math.abs(yaw - prevYaw);

                    boolean recentInteraction = (lastInteractionTimeNs > 0) &&
                            (frame.nanoTime() - lastInteractionTimeNs <= 150_000_000L); // 150ms = 3 ticks

                    // RotationSnapHeuristic: previous < 9 && last > 40 && current < 9 within 150ms of interaction
                    if (yawMotionT2 < 9.0 && yawMotionT1 > 40.0 && yawMotionT0 < 9.0 && recentInteraction) {
                        violations.add(new AcViolation(
                                5, "Intave", "RotationSnapHeuristic",
                                String.format("Rotation snap detected: [%.2f° / %.2f° / %.2f°] within 150ms of combat interaction",
                                        yawMotionT2, yawMotionT1, yawMotionT0),
                                frame.tick(), frame.nanoTime(), yawMotionT1
                        ));
                    }

                    // Silent Movement Heuristic: Head turns > 25° while keys compensate to preserve world trajectory
                    if (yawMotionT0 > 25.0 && (frame.keyForward() != 0 || frame.keyStrafe() != 0)) {
                        if (lastForward != 0 || lastStrafe != 0) {
                            double dirOld = prevYaw + Math.toDegrees(Math.atan2(lastStrafe, lastForward));
                            double dirNew = yaw + Math.toDegrees(Math.atan2(frame.keyStrafe(), frame.keyForward()));
                            double dirDiff = Math.abs((dirNew - dirOld) % 360.0);
                            if (dirDiff > 180.0) dirDiff = 360.0 - dirDiff;

                            // If world-space direction remained nearly unchanged (< 10°) despite > 25° head turn
                            if (dirDiff < 10.0 && (frame.keyForward() != lastForward || frame.keyStrafe() != lastStrafe)) {
                                violations.add(new AcViolation(
                                        5, "Intave", "SilentMovementHeuristic",
                                        String.format("Counter-strafing detected: head yaw shifted %.2f° but movement direction differed by only %.2f°",
                                                yawMotionT0, dirDiff),
                                        frame.tick(), frame.nanoTime(), dirDiff
                                ));
                            }
                        }
                    }
                }

                prevYaw = yaw;
                lastForward = frame.keyForward();
                lastStrafe = frame.keyStrafe();
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
        prevYaw = -9999f;
        yawMotionT2 = 0;
        yawMotionT1 = 0;
        yawMotionT0 = 0;
        lastInteractionTimeNs = -1;
        lastForward = 0;
        lastStrafe = 0;
        ticksSinceBreakStart = 999;
        ticksSinceBreakStop = 999;
        swingThisTick = false;
        attackThisTick = false;
    }
}
