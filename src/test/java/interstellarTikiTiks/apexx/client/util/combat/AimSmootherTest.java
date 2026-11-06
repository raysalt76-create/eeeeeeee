package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimSmootherTest {

    private static final double FRAME = 1.0 / 144.0;

    /** A step in the wanted angle becomes a glide: no overshoot, no velocity step, and it converges. */
    @Test
    void stepBecomesContinuousGlideWithoutOvershoot() {
        AimSmoother smoother = new AimSmoother();
        float[] out = new float[2];
        smoother.step(0, 0, 0.0, FRAME, 0.05, false, out);
        double now = 0.0;
        double previous = 0.0;
        double previousVelocity = 0.0;
        double maxVelocityStep = 0.0;
        for (int i = 1; i <= 144; i++) {
            now += FRAME;
            smoother.step(30, 10, now, FRAME, 0.05, false, out);
            double velocity = (out[0] - previous) / FRAME;
            maxVelocityStep = Math.max(maxVelocityStep, Math.abs(velocity - previousVelocity));
            assertTrue(out[0] <= 30.0001, "yaw overshoot " + out[0]);
            assertTrue(out[1] <= 10.0001, "pitch overshoot " + out[1]);
            previous = out[0];
            previousVelocity = velocity;
        }
        assertEquals(30.0, out[0], 0.01);
        assertEquals(10.0, out[1], 0.01);
        // Velocity is continuous: one frame can only add the peak acceleration (omega^2 * jump) times a
        // frame, never the whole glide speed at once.
        double omega = 2.0 / 0.05;
        assertTrue(maxVelocityStep <= omega * omega * 30.0 * FRAME * 1.05, "velocity stepped by " + maxVelocityStep);
    }

    /** A 20 Hz staircase of a moving target is smoothed: frame-to-frame velocity changes shrink a lot. */
    @Test
    void staircaseIsSmoothedAndLeadsTheTarget() {
        AimSmoother smoother = new AimSmoother();
        float[] out = new float[2];
        double rate = 80.0; // deg/s of the real target
        double now = 0.0;
        double stepped = 0.0;
        smoother.step(0, 0, now, FRAME, 0.05, true, out);
        double rawJerk = 0.0;
        double smoothJerk = 0.0;
        double lastRawV = 0.0;
        double lastSmoothV = 0.0;
        double lastRaw = 0.0;
        double lastSmooth = 0.0;
        double lag = 0.0;
        for (int i = 1; i <= 288; i++) {
            now += FRAME;
            stepped = Math.floor(now / 0.05) * 0.05 * rate;
            smoother.step(stepped, 0, now, FRAME, 0.05, true, out);
            double rawV = (stepped - lastRaw) / FRAME;
            double smoothV = (out[0] - lastSmooth) / FRAME;
            if (i > 60) {
                rawJerk += Math.abs(rawV - lastRawV);
                smoothJerk += Math.abs(smoothV - lastSmoothV);
                lag = Math.max(lag, Math.abs(now * rate - out[0]));
            }
            lastRaw = stepped;
            lastSmooth = out[0];
            lastRawV = rawV;
            lastSmoothV = smoothV;
        }
        assertTrue(smoothJerk < rawJerk * 0.35, "smooth " + smoothJerk + " raw " + rawJerk);
        // With the rate fed forward the glide trails the true (continuous) path by well under a tick of travel.
        assertTrue(lag < rate * 0.06, "lag " + lag);
    }

    /** Yaw never goes the long way across the +/-180 seam. */
    @Test
    void wrapsAcrossTheSeam() {
        AimSmoother smoother = new AimSmoother();
        float[] out = new float[2];
        smoother.step(175, 0, 0.0, FRAME, 0.05, false, out);
        double now = 0.0;
        for (int i = 0; i < 40; i++) {
            now += FRAME;
            smoother.step(-175, 0, now, FRAME, 0.05, false, out);
            double distance = Math.abs(((out[0] - 175 + 540) % 360) - 180);
            assertTrue(distance <= 11.0, "went the long way: " + out[0]);
        }
    }
}
