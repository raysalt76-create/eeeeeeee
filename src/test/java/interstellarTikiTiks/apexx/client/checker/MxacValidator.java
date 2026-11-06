package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tier 4: MXAc Verification Engine
 * Implements AimFactorCheck (snap-and-freeze), AimPatternCheck (second-order derivatives),
 * and Mouse Sensitivity GCD quantization.
 */
public class MxacValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // AimFactorCheck: 3-sample window of yaw deltas
    private final Deque<Double> factorStack = new ArrayDeque<>();

    // AimPatternCheck: 100-sample window of second-order deltas
    private final List<Float> secondOrderDeltas = new ArrayList<>();
    private float lastYawDelta = -1f;
    private float prevYaw = -9999f;
    private float prevPitch = -9999f;

    @Override
    public int tier() {
        return 4;
    }

    @Override
    public String name() {
        return "MXAc";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {}

    @Override
    public void onAction(WireFrame frame) {
        if (frame.action() == WireAction.MOVEMENT) {
            float yaw = frame.yaw();
            float pitch = frame.pitch();

            if (prevYaw != -9999f) {
                float yawDelta = Math.abs(yaw - prevYaw);
                float pitchDelta = Math.abs(pitch - prevPitch);

                // Check 1: AimFactorCheck (3-sample evicting window)
                factorStack.addLast((double) yawDelta);
                if (factorStack.size() > 3) {
                    factorStack.pollFirst();
                }
                if (factorStack.size() == 3) {
                    List<Double> list = new ArrayList<>(factorStack);
                    double d0 = list.get(0);
                    double centre = list.get(1);
                    double d2 = list.get(2);

                    // Snap-and-freeze: turn > 35° immediately preceded & followed by stillness (< 1.2°)
                    if (centre > 35.0 && centre != 360.0 && d0 < 1.2 && d2 < 1.2) {
                        violations.add(new AcViolation(
                                4, "MXAc", "AimFactorCheck",
                                String.format("Snap-and-freeze detected: turn %.2f° flanked by stillness (%.2f°, %.2f°)", centre, d0, d2),
                                frame.tick(), frame.nanoTime(), centre
                        ));
                    }
                }

                // Check 2: AimPatternCheck (Second-order derivative d^2 yaw)
                if (lastYawDelta >= 0) {
                    float d2Yaw = Math.abs(yawDelta - lastYawDelta);
                    secondOrderDeltas.add(d2Yaw);
                    if (secondOrderDeltas.size() > 100) {
                        secondOrderDeltas.remove(0);
                    }

                    // Flag unnatural 0.0 second-derivative lock (constant mechanical acceleration)
                    if (secondOrderDeltas.size() >= 50 && d2Yaw < 1e-4 && yawDelta > 2.0f) {
                        int constantCount = 0;
                        for (float val : secondOrderDeltas) {
                            if (val < 1e-4) constantCount++;
                        }
                        if (constantCount > 10) {
                            violations.add(new AcViolation(
                                    4, "MXAc", "AimPatternCheck",
                                    String.format("Unnatural constant second-derivative acceleration across %d samples", constantCount),
                                    frame.tick(), frame.nanoTime(), constantCount
                            ));
                        }
                    }
                }
                lastYawDelta = yawDelta;

                // Check 3: Mouse Sensitivity GCD quantization
                // If pitch changed, verify it conforms to GCD steps for standard sensitivities [0.0..1.0]
                if (pitchDelta > 0.01f && pitchDelta < 30.0f) {
                    boolean conformsToAnyGcd = checkSensitivityGcd(pitchDelta);
                    if (!conformsToAnyGcd) {
                        violations.add(new AcViolation(
                                4, "MXAc", "SensitivityProcessor (GCD)",
                                String.format("Pitch delta %.6f° violates mouse sensitivity GCD quantization", pitchDelta),
                                frame.tick(), frame.nanoTime(), pitchDelta
                        ));
                    }
                }
            }

            prevYaw = yaw;
            prevPitch = pitch;
        }
    }

    private boolean checkSensitivityGcd(float delta) {
        // Test common sensitivity values (e.g. 0.0 to 1.0 in 0.05 steps)
        // vanilla gcd = (sens * 0.6 + 0.2)^3 * 8.0 * 0.15
        for (int i = 0; i <= 20; i++) {
            float sens = i * 0.05f;
            float f = sens * 0.6f + 0.2f;
            float gcd = f * f * f * 8.0f * 0.15f;

            float remainder = delta % gcd;
            if (remainder < 0.001f || Math.abs(remainder - gcd) < 0.001f) {
                return true;
            }
        }
        return false;
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
        factorStack.clear();
        secondOrderDeltas.clear();
        lastYawDelta = -1f;
        prevYaw = -9999f;
        prevPitch = -9999f;
    }
}
