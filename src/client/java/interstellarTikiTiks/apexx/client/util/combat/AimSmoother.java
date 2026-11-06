package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.MathHelper;

/**
 * Turns the stepped angles a tick-rate aim planner produces into a continuous command.
 *
 * <p>The planner's geometry is judged at tick heads, so between ticks it is a staircase: the wanted
 * angle holds for 50 ms, then jumps. Chased directly, each jump is a velocity pulse at 20 Hz, which is
 * the shake. This stage sits between the planner and the rotation controller and does three things:
 * <ol>
 *   <li><b>Critically damped follow</b> (Unity's SmoothDamp form, stable for any frame time): position
 *       and velocity are both continuous, so a jump in the wanted angle becomes one smooth glide with no
 *       velocity step and no overshoot.</li>
 *   <li><b>Rate feed-forward</b>: the per-tick change of the wanted angle is measured from the planner's
 *       own output (never from this stage's output, so there is no feedback loop) and added back as a
 *       lead, which cancels the steady-state lag a pure follower would have on a moving target.</li>
 *   <li><b>Strike-aware time constant</b>: the caller passes a smooth time that shrinks as a committed
 *       strike approaches, so the glide has converged on the planned angle by the head that clicks.</li>
 * </ol>
 *
 * <p>Pure math, no client state, one instance per aim owner. Yaw is unwrapped against its own state so
 * the follower never takes the long way round at the +/-180 seam.
 */
public final class AimSmoother {

    /** Wanted-angle changes above this (degrees) are re-plans, not target motion: no rate is learned. */
    private static final double JUMP_DEGREES = 25.0;
    /** Fastest rate the feed-forward may carry, degrees per second. */
    private static final double MAX_RATE = 360.0;
    /** A wanted angle that has not changed for this long has a rate of zero again, seconds. */
    private static final double RATE_STALE_SECONDS = 0.12;
    /** A change counts as a new planner sample above this many degrees. */
    private static final double SAMPLE_EPSILON = 1.0e-4;
    /** Share of the measured rate that is fed forward (under 1 keeps a stale rate from over-leading). */
    private static final double FEED_FORWARD = 0.85;

    private final Axis yaw = new Axis(true);
    private final Axis pitch = new Axis(false);
    private boolean seeded;

    /** Forgets all state; the next {@link #step} starts at the wanted angle. */
    public void reset() {
        seeded = false;
        yaw.clear();
        pitch.clear();
    }

    /**
     * @param wantYaw     the planner's wanted yaw, degrees
     * @param wantPitch   the planner's wanted pitch, degrees
     * @param nowSeconds  a monotonic clock, seconds
     * @param dtSeconds   time since the previous step
     * @param smoothTime  time constant, seconds (about the time to close 90% of a jump is 2x this)
     * @param feedForward whether to lead by the measured rate (tracking: yes; a committed strike: no)
     * @param out         receives {yaw, pitch}
     */
    public void step(double wantYaw, double wantPitch, double nowSeconds, double dtSeconds, double smoothTime,
                     boolean feedForward, float[] out) {
        double dt = MathHelper.clamp(dtSeconds, 1.0e-4, 0.1);
        double time = Math.max(1.0e-3, smoothTime);
        if (!seeded) {
            yaw.seed(wantYaw, nowSeconds);
            pitch.seed(wantPitch, nowSeconds);
            seeded = true;
        } else {
            yaw.sample(wantYaw, nowSeconds);
            pitch.sample(wantPitch, nowSeconds);
        }
        out[0] = (float) yaw.advance(wantYaw, nowSeconds, dt, time, feedForward);
        out[1] = (float) pitch.advance(wantPitch, nowSeconds, dt, time, feedForward);
    }

    /** Current smoothed angles without stepping; null before the first step. */
    public float[] current() {
        return seeded ? new float[]{(float) yaw.position, (float) pitch.position} : null;
    }

    private static final class Axis {
        private final boolean wrap;
        private double position;
        private double velocity;
        private double lastWant;
        private double lastSampleAt;
        private double rate;

        Axis(boolean wrap) {
            this.wrap = wrap;
        }

        void clear() {
            position = velocity = lastWant = lastSampleAt = rate = 0.0;
        }

        void seed(double want, double now) {
            position = want;
            velocity = 0.0;
            lastWant = want;
            lastSampleAt = now;
            rate = 0.0;
        }

        private double delta(double from, double to) {
            return wrap ? MathHelper.wrapDegrees(to - from) : to - from;
        }

        /** Learns the planner's rate from successive distinct wanted angles. */
        void sample(double want, double now) {
            double change = delta(lastWant, want);
            if (Math.abs(change) > SAMPLE_EPSILON) {
                double interval = now - lastSampleAt;
                if (Math.abs(change) > JUMP_DEGREES || interval <= 1.0e-3) {
                    rate = 0.0;
                } else {
                    double measured = MathHelper.clamp(change / Math.max(0.03, Math.min(interval, 0.12)),
                            -MAX_RATE, MAX_RATE);
                    rate = rate * 0.5 + measured * 0.5;
                }
                lastWant = want;
                lastSampleAt = now;
            } else if (now - lastSampleAt > RATE_STALE_SECONDS) {
                rate = 0.0;
            }
        }

        double advance(double want, double now, double dt, double smoothTime, boolean feedForward) {
            double goal = want;
            if (feedForward && now - lastSampleAt <= RATE_STALE_SECONDS) {
                goal = want + rate * smoothTime * FEED_FORWARD;
            }
            // Work relative to the current position so the yaw seam is never crossed the long way.
            double change = delta(goal, position);
            double omega = 2.0 / smoothTime;
            double x = omega * dt;
            double decay = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
            double temp = (velocity + omega * change) * dt;
            velocity = (velocity - omega * temp) * decay;
            double next = position + (-change + (change + temp) * decay);
            // change = position - goal, so next = goal + (change + temp) * decay.
            position = next;
            return position;
        }
    }
}
