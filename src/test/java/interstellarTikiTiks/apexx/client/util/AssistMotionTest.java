package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistMotionTest {
    private static final double DT = 1.0 / 144.0;
    private static final AssistMotion.Config CONFIG = AssistMotion.Config.DEFAULT;

    private static AssistMotion.Zone zone(double yaw) {
        return new AssistMotion.Zone(yaw, 0.4, -0.8, 0.8);
    }

    @Test
    void nearestErrorIsZeroInsideZoneAndPointsAtEdgeOutside() {
        AssistMotion.Zone z = new AssistMotion.Zone(10.0, 2.0, -5.0, 5.0);
        double[] inside = z.nearestError(11.0, 1.0);
        assertEquals(0.0, inside[0], 1.0e-9);
        assertEquals(0.0, inside[1], 1.0e-9);

        double[] outside = z.nearestError(0.0, 9.0);
        assertEquals(8.0, outside[0], 1.0e-9);
        assertEquals(-4.0, outside[1], 1.0e-9);

        // Wraps across the +-180 seam.
        double[] wrapped = new AssistMotion.Zone(179.0, 1.0, -1.0, 1.0).nearestError(-178.0, 0.0);
        assertEquals(-2.0, wrapped[0], 1.0e-9);
    }

    @Test
    void convergesOntoStaticTargetWithoutOvershootAndWithContinuousVelocity() {
        for (double strength : new double[]{0.2, 0.55, 1.0}) {
            for (double smooth : new double[]{0.0, 0.6, 1.0}) {
                AssistMotion m = new AssistMotion();
                AssistMotion.Config c = new AssistMotion.Config(strength, smooth, 0.85, true, 0.55, 300.0, true);
                double cam = 0.0;
                double lastVel = 0.0;
                for (int i = 0; i < 144 * 3; i++) {
                    AssistMotion.Output o = m.update(cam, 0.0, zone(30.0), 0.0, 0.0, DT, c);
                    cam += o.yaw();
                    double vel = o.yaw() / DT;
                    assertTrue(cam <= 30.4 + 1.0e-6, "overshot the zone at s=" + strength + " sm=" + smooth);
                    // No single-frame velocity jump larger than a modest fraction of a turn.
                    assertTrue(Math.abs(vel - lastVel) < 60.0, "velocity jump " + (vel - lastVel));
                    lastVel = vel;
                }
                assertTrue(cam >= 29.6 - 0.05, "did not reach the zone: " + cam);
            }
        }
    }

    @Test
    void windCurvesTheApproachButStillLandsInTheZone() {
        AssistMotion.Config windy = new AssistMotion.Config(0.55, 0.6, 0.85, true, 0.55, 300.0, true, 1.0);
        double maxPitchOff = 0.0;
        for (int run = 0; run < 20; run++) {
            AssistMotion m = new AssistMotion();
            double yaw = 0.0;
            double pitch = 0.0;
            for (int i = 0; i < 144 * 3; i++) {
                AssistMotion.Output o = m.update(yaw, pitch, zone(30.0), 0.0, 0.0, DT, windy);
                yaw += o.yaw();
                pitch += o.pitch();
                maxPitchOff = Math.max(maxPitchOff, Math.abs(pitch));
                assertTrue(yaw <= 30.4 + 0.05, "overshot the zone with wind: " + yaw);
            }
            assertEquals(30.0, yaw, 0.45, "did not land with wind");
            assertTrue(Math.abs(pitch) <= 0.8 + 0.05, "settled outside the zone: " + pitch);
        }
        // A straight yaw-only approach would never leave pitch 0.
        assertTrue(maxPitchOff > 0.5, "wind did not bend the path: " + maxPitchOff);
    }

    @Test
    void noWindKeepsTheApproachStraight() {
        AssistMotion m = new AssistMotion();
        double yaw = 0.0;
        double pitch = 0.0;
        for (int i = 0; i < 144 * 2; i++) {
            AssistMotion.Output o = m.update(yaw, pitch, zone(30.0), 0.0, 0.0, DT, CONFIG);
            yaw += o.yaw();
            pitch += o.pitch();
        }
        assertEquals(0.0, pitch, 1.0e-9);
    }

    @Test
    void responseIsFrameRateIndependent() {
        double[] reach = new double[3];
        int[] fps = {30, 144, 500};
        for (int k = 0; k < fps.length; k++) {
            double dt = 1.0 / fps[k];
            AssistMotion m = new AssistMotion();
            double cam = 0.0;
            double t = 0.0;
            while (cam < 27.0 && t < 3.0) {
                cam += m.update(cam, 0.0, zone(30.0), 0.0, 0.0, dt, CONFIG).yaw();
                t += dt;
            }
            reach[k] = t;
        }
        assertEquals(reach[0], reach[1], 0.03);
        assertEquals(reach[1], reach[2], 0.03);
    }

    @Test
    void tracksStrafingTargetAndIgnoresItsEscapeAsIntent() {
        AssistMotion m = new AssistMotion();
        double cam = 0.0;
        double target = 0.0;
        double maxLag = 0.0;
        for (int i = 0; i < 144 * 3; i++) {
            target += 60.0 * DT * (i < 144 * 1.5 ? 1.0 : -1.0);
            AssistMotion.Output o = m.update(cam, 0.0, zone(target), 0.0, 0.0, DT, CONFIG);
            cam += o.yaw();
            assertFalse(o.detached(), "a moving target must not read as a pull-away");
            if (i > 72) maxLag = Math.max(maxLag, Math.abs(cam - target));
        }
        assertTrue(maxLag < 5.0, "tracking lag " + maxLag);
    }

    @Test
    void deliberatePullAwayDetachesAndStaysDetachedWithoutTug() {
        AssistMotion m = new AssistMotion();
        double cam = 0.0;
        for (int i = 0; i < 144; i++) {
            cam += m.update(cam, 0.0, zone(0.0), 0.0, 0.0, DT, CONFIG).yaw();
        }
        double hand = 0.0;
        boolean detached = false;
        for (int i = 0; i < 40; i++) {
            double u = -250.0 * DT;
            cam += u;
            hand += u;
            AssistMotion.Output o = m.update(cam, 0.0, zone(0.0), u, 0.0, DT, CONFIG);
            cam += o.yaw();
            detached |= o.detached();
        }
        assertTrue(detached);
        // While yielding, the assist resisted the hand by well under a degree in total.
        assertTrue(Math.abs(cam - hand) < 1.0, "fought the hand by " + (cam - hand));

        double restAt = cam;
        for (int i = 0; i < 144 * 2; i++) {
            AssistMotion.Output o = m.update(cam, 0.0, zone(0.0), 0.0, 0.0, DT, CONFIG);
            cam += o.yaw();
            assertTrue(o.detached());
        }
        assertEquals(restAt, cam, 0.05);
    }

    @Test
    void slowImpreciseMovementKeepsAssist() {
        AssistMotion m = new AssistMotion();
        double cam = -3.0;
        double pitch = 0.0;
        for (int i = 0; i < 144 * 2; i++) {
            double u = 40.0 * DT * Math.sin(i * 0.1);
            pitch += u;
            AssistMotion.Output o = m.update(cam, pitch, zone(0.0), 0.0, u, DT, CONFIG);
            cam += o.yaw();
            pitch += o.pitch();
            assertFalse(o.detached());
        }
        assertTrue(Math.abs(cam) < 0.6, "should have been carried onto the target: " + cam);
    }

    @Test
    void handFlickOntoTargetIsNotAmplifiedIntoOvershoot() {
        AssistMotion m = new AssistMotion();
        double cam = 0.0;
        for (int i = 0; i < 144; i++) {
            double u = i < 20 ? 1.4 : 0.0;
            cam += u;
            cam += m.update(cam, 0.0, zone(30.0), u, 0.0, DT, CONFIG).yaw();
            assertTrue(cam <= 30.4 + 1.0e-6, "overshoot " + cam);
        }
    }

    @Test
    void returningTowardTargetReengages() {
        AssistMotion m = new AssistMotion();
        double cam = 0.0;
        for (int i = 0; i < 144; i++) {
            cam += m.update(cam, 0.0, zone(0.0), 0.0, 0.0, DT, CONFIG).yaw();
        }
        for (int i = 0; i < 30; i++) {
            double u = -300.0 * DT;
            cam += u;
            cam += m.update(cam, 0.0, zone(0.0), u, 0.0, DT, CONFIG).yaw();
        }
        assertTrue(m.isDetached());
        boolean reengaged = false;
        for (int i = 0; i < 144 && !reengaged; i++) {
            double u = 200.0 * DT;
            cam += u;
            reengaged = !m.update(cam, 0.0, zone(0.0), u, 0.0, DT, CONFIG).detached();
        }
        assertTrue(reengaged);
    }

    @Test
    void strongerPullawayResistanceHoldsAgainstManualAimAcrossFrameRates() {
        for (int fps : new int[]{30, 144, 500}) {
            double dt = 1.0 / fps;
            double[] drift = new double[2];
            for (int level = 0; level < 2; level++) {
                AssistMotion m = new AssistMotion();
                AssistMotion.Config c = new AssistMotion.Config(0.93, 0.12, 1.0,
                        true, 0.3, 250.0, true, 0.0, level == 0 ? 0.0 : 0.8);
                double cam = 0.0;
                for (int i = 0; i < fps; i++) {
                    cam += m.update(cam, 0.0, zone(0.0), 0.0, 0.0, dt, c).yaw();
                }
                for (int i = 0; i < fps; i++) {
                    double hand = -100.0 * dt;
                    cam += hand;
                    cam += m.update(cam, 0.0, zone(0.0), hand, 0.0, dt, c).yaw();
                }
                drift[level] = Math.abs(cam);
                if (level == 1) assertFalse(m.isDetached(), "released at " + fps + " fps");
            }
            assertTrue(drift[1] < 5.0, "strong lock drift at " + fps + " fps: " + drift[1]);
            assertTrue(drift[1] < drift[0] * 0.3, "pullaway setting did not strengthen the lock");
        }
    }

    @Test
    void strongestPullawayStillReleasesOnADeliberateFlickAndResets() {
        AssistMotion m = new AssistMotion();
        AssistMotion.Config c = new AssistMotion.Config(0.93, 0.12, 1.0,
                true, 0.3, 250.0, true, 0.0, 1.0);
        double cam = 0.0;
        for (int i = 0; i < 144; i++) {
            cam += m.update(cam, 0.0, zone(0.0), 0.0, 0.0, DT, c).yaw();
        }
        boolean released = false;
        for (int i = 0; i < 36; i++) {
            double hand = -2400.0 * DT;
            cam += hand;
            released |= m.update(cam, 0.0, zone(0.0), hand, 0.0, DT, c).detached();
        }
        assertTrue(released, "maximum resistance trapped manual aim");
        m.reset();
        assertFalse(m.isDetached());
        assertEquals(0.0, m.update(0.0, 0.0, zone(0.0), 0.0, 0.0, DT, c).yaw(), 0.0);
    }

    @Test
    void heldOffAssistFadesToZero() {
        AssistMotion m = new AssistMotion();
        AssistMotion.Config off = new AssistMotion.Config(0.55, 0.6, 0.85, true, 0.55, 300.0, false);
        double cam = 0.0;
        for (int i = 0; i < 144; i++) {
            cam += m.update(cam, 0.0, zone(20.0), 0.0, 0.0, DT, off).yaw();
        }
        assertEquals(0.0, cam, 1.0e-9);
        assertEquals(0.0, m.engagement(), 1.0e-9);
    }
}
