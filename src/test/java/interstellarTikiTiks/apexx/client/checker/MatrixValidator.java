package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tier 3: Matrix 1.21.10 Verification Engine
 * Implements the 80-sample sliding window aimbot collector (C0072ef),
 * local pre-filter (average yaw delta >= 2.0°), reach limits (3.15m max),
 * and ML anomaly feature heuristics.
 */
public class MatrixValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // 80-sample rotation window (gl)
    private record RotationSample(float yaw, float pitch) {}
    private final Deque<RotationSample> rotationWindow = new ArrayDeque<>();
    private static final int WINDOW_CAPACITY = 80;

    private boolean attackSeenInWindow = false;

    @Override
    public int tier() {
        return 3;
    }

    @Override
    public String name() {
        return "Matrix 1.21.10";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {}

    @Override
    public void onAction(WireFrame frame) {
        if (frame.action() == WireAction.MOVEMENT) {
            rotationWindow.addLast(new RotationSample(frame.yaw(), frame.pitch()));
            if (rotationWindow.size() > WINDOW_CAPACITY) {
                rotationWindow.pollFirst();
            }

            if (rotationWindow.size() == WINDOW_CAPACITY && attackSeenInWindow) {
                evaluateMatrixWindow(frame.tick(), frame.nanoTime());
            }
        }

        if (frame.action() == WireAction.ATTACK) {
            attackSeenInWindow = true;

            // Matrix Reach check: 3.15m maximum reach
            if (frame.targetDistance() > 3.151) {
                double vl = (frame.targetDistance() - 3.15) * 10.0;
                violations.add(new AcViolation(
                        3, "Matrix 1.21.10", "Reach",
                        String.format("Attack reach %.4fm exceeds Matrix 3.15m threshold (VL: %.2f)", frame.targetDistance(), vl),
                        frame.tick(), frame.nanoTime(), frame.targetDistance()
                ));
            }
        }
    }

    private void evaluateMatrixWindow(int tick, long nanoTime) {
        List<RotationSample> samples = new ArrayList<>(rotationWindow);
        double totalYawDelta = 0;
        int identicalDeltaCount = 0;
        float prevDelta = -1;

        for (int i = 0; i < samples.size() - 1; i++) {
            float dYaw = shortestAngularDistance(samples.get(i).yaw, samples.get(i + 1).yaw);
            totalYawDelta += dYaw;

            if (prevDelta >= 0 && Math.abs(dYaw - prevDelta) < 1e-5 && dYaw > 0.5f) {
                identicalDeltaCount++;
            }
            prevDelta = dYaw;
        }

        double avgYawDelta = totalYawDelta / (samples.size() - 1);

        // Pre-filter: only analyze active combat turning windows (avg delta >= 2.0°)
        if (avgYawDelta >= 2.0) {
            // Anomaly check: Perfectly uniform rotational delta across mechanical steps
            if (identicalDeltaCount > 15) {
                violations.add(new AcViolation(
                        3, "Matrix 1.21.10", "KillAura.Aimbot (bad_rotation)",
                        String.format("Detected %d identical mechanical rotation steps in 80-sample window", identicalDeltaCount),
                        tick, nanoTime, identicalDeltaCount
                ));
            }
        }
    }

    private static float shortestAngularDistance(float from, float to) {
        float diff = (to - from) % 360.0f;
        if (diff >= 180.0f) diff -= 360.0f;
        if (diff < -180.0f) diff += 360.0f;
        return Math.abs(diff);
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
        rotationWindow.clear();
        attackSeenInWindow = false;
    }
}
