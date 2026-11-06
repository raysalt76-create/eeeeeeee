package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitivityAssistTest {
    private static final double DT = 1.0 / 144.0;
    private static final SensitivityAssist.Config CONFIG = new SensitivityAssist.Config(0.75, 5.0);

    /** Runs {@code frames} identical frames and returns the last output. */
    private static double[] settle(SensitivityAssist s, double inYaw, SensitivityAssist.Geometry g, int frames) {
        double[] out = null;
        for (int i = 0; i < frames; i++) {
            out = s.shape(inYaw, 0.0, g, CONFIG, DT);
        }
        return out;
    }

    @Test
    void zeroInputNeverMovesTheCamera() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(3.0, 1.0, 2.0, 80.0, 0.0);
        double[] out = s.shape(0.0, 0.0, g, CONFIG, DT);
        assertEquals(0.0, out[0], 0.0);
        assertEquals(0.0, out[1], 0.0);
    }

    @Test
    void farFromTargetInputIsUnchanged() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(40.0, 0.0, 2.0, 50.0, 0.0);
        double[] out = settle(s, 0.3, g, 30);
        assertEquals(0.3, out[0], 1.0e-9);
    }

    @Test
    void followingAMovingTargetIsNotSlowed() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(0.0, 0.0, 2.0, 90.0, 0.0);
        double[] out = settle(s, 90.0 * DT, g, 60);
        assertEquals(90.0 * DT, out[0], 1.0e-9);
    }

    @Test
    void onTargetSensitivityDropsDeeply() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        double in = 120.0 * DT;
        double[] out = settle(s, in, g, 60);
        // 0.75 Slowdown: about a third of the sensitivity is left on the target.
        assertEquals(1.0 - 0.75 * SensitivityAssist.MAX_SLOWDOWN, out[0] / in, 0.03);
    }

    @Test
    void sensitivityEasesInWithoutASingleFrameStep() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry off = new SensitivityAssist.Geometry(30.0, 0.0, 2.0, 0.0, 0.0);
        SensitivityAssist.Geometry on = new SensitivityAssist.Geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        settle(s, 0.2, off, 30);
        double last = s.gain();
        for (int i = 0; i < 60; i++) {
            s.shape(0.2, 0.0, on, CONFIG, DT);
            assertTrue(Math.abs(s.gain() - last) < 0.12, "gain stepped by " + (s.gain() - last));
            last = s.gain();
        }
        assertTrue(s.gain() < 0.4, "never settled low: " + s.gain());
    }

    @Test
    void flickAwayIsNotHeld() {
        SensitivityAssist s = new SensitivityAssist();
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(0.0, 0.0, 2.0, 0.0, 0.0);
        double in = 2000.0 * DT;
        double[] out = settle(s, in, g, 20);
        assertEquals(in, out[0], in * 0.02);
    }

    @Test
    void wideApproachSlowsQuadraticallyBeforeTheHitboxAcrossFrameRates() {
        SensitivityAssist.Config wide = new SensitivityAssist.Config(0.75, 16.0);
        for (int fps : new int[]{30, 144, 500}) {
            double dt = 1.0 / fps;
            double previousGain = 1.0;
            for (double distance : new double[]{16.0, 12.0, 8.0, 4.0, 0.0}) {
                SensitivityAssist s = new SensitivityAssist();
                SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(distance, 0.0, 2.0, 0.0, 0.0);
                double[] out = null;
                for (int i = 0; i < fps; i++) {
                    out = s.shape(-20.0 * dt, 0.0, g, wide, dt);
                }
                double gain = out[0] / (-20.0 * dt);
                double approach = 1.0 - distance / 16.0;
                assertEquals(1.0 - 0.75 * SensitivityAssist.MAX_SLOWDOWN * approach * approach,
                        gain, 1.0e-6);
                assertTrue(gain <= previousGain);
                previousGain = gain;
            }
        }
    }

    @Test
    void approachIsSlowedLessThanMovingAway() {
        SensitivityAssist.Geometry g = new SensitivityAssist.Geometry(1.5, 0.0, 2.0, 0.0, 0.0);
        double toward = settle(new SensitivityAssist(), 40.0 * DT, g, 30)[0];
        double away = settle(new SensitivityAssist(), -40.0 * DT, g, 30)[0];
        assertTrue(toward > 0.0 && away < 0.0);
        assertTrue(toward > -away, "toward " + toward + " away " + away);
    }
}
