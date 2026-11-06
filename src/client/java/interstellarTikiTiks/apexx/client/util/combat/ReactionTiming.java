package interstellarTikiTiks.apexx.client.util.combat;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared timing / randomization helpers for combat &amp; action modules.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Every timed action can have a base delay plus gaussian randomization ("reaction time").</li>
 *   <li>Delays are ALWAYS wall-clock milliseconds, so behaviour is identical regardless of the
 *       client's render FPS or the server's TPS. Modules that evaluate per render frame therefore
 *       react within a single frame, while the actual wait is exactly what the user configured.</li>
 *   <li>The {@link Deadline} helper lets a module lock in one random draw and reuse it across many
 *       frames, instead of re-rolling the delay every frame.</li>
 * </ul>
 */
public final class ReactionTiming {
    private ReactionTiming() {
    }

    /** Uniform value in [min,max] (argument order does not matter). */
    public static double uniform(double a, double b) {
        double min = Math.min(a, b);
        double max = Math.max(a, b);
        if (max - min < 1.0e-9) {
            return min;
        }
        return min + (max - min) * ThreadLocalRandom.current().nextDouble();
    }

    /** Uniform millisecond delay in [min,max], never negative. */
    public static long uniformMs(double minMs, double maxMs) {
        return Math.max(0L, Math.round(uniform(minMs, maxMs)));
    }

    /**
     * Gaussian reaction time: {@code mean + N(0,1) * stdev}, clamped to {@code [0, mean + 4*stdev]}
     * so an unlucky draw can never stall an action indefinitely.
     */
    public static long gaussianMs(double meanMs, double stdevMs) {
        double v = meanMs;
        if (stdevMs > 1.0e-9) {
            v += ThreadLocalRandom.current().nextGaussian() * stdevMs;
        }
        double hardMax = meanMs + Math.max(0.0, stdevMs) * 4.0;
        if (v < 0.0) {
            v = 0.0;
        }
        if (v > hardMax) {
            v = hardMax;
        }
        return Math.round(v);
    }

    /** Symmetric random offset in [-magnitude, +magnitude]. */
    public static double offset(double magnitude) {
        if (magnitude <= 1.0e-9) {
            return 0.0;
        }
        return (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * magnitude;
    }

    /**
     * A one-shot wall-clock deadline. Arm it once (the delay is sampled a single time), poll it
     * every frame with {@link #isReady()}, and {@link #reset()} it once the action has fired so the
     * next arm re-samples a fresh delay.
     */
    public static final class Deadline {
        private long readyAtMs = -1L;

        public boolean isArmed() {
            return readyAtMs >= 0L;
        }

        /** Arm with a gaussian delay; no-op if already armed. */
        public void armGaussianOnce(double meanMs, double stdevMs) {
            if (readyAtMs < 0L) {
                readyAtMs = System.currentTimeMillis() + gaussianMs(meanMs, stdevMs);
            }
        }

        /** Arm with a uniform min/max delay; no-op if already armed. */
        public void armUniformOnce(double minMs, double maxMs) {
            if (readyAtMs < 0L) {
                readyAtMs = System.currentTimeMillis() + uniformMs(minMs, maxMs);
            }
        }

        public boolean isReady() {
            return readyAtMs >= 0L && System.currentTimeMillis() >= readyAtMs;
        }

        public void reset() {
            readyAtMs = -1L;
        }
    }
}
