package interstellarTikiTiks.apexx.client.util.combat;

import java.util.random.RandomGenerator;

/**
 * The shape of a short vertical glide: a slow-in, slow-out S-curve whose steepness, and the pace it is
 * driven at, are drawn fresh for every glide so that no two look alike. Pure maths, no game state.
 *
 * <p>Position at share {@code t} of the glide is {@code t^a / (t^a + (1 - t)^a)} with the steepness
 * {@code a} drawn around 2.1; the pace is log-normal (positively skewed, as human movement times are),
 * truncated by redrawing so a single draw cannot stall or rush a glide.</p>
 */
public final class GlideProfile {

    public static final double SHAPE_MEAN = 2.1;
    public static final double SHAPE_SIGMA = 0.3;
    public static final double SHAPE_MIN = 1.5;
    public static final double SHAPE_MAX = 3.0;
    public static final double PACE_MIN = 0.6;
    public static final double PACE_MAX = 1.6;

    private GlideProfile() {
    }

    /** S-curve from 0 to 1 over {@code t} in 0..1 with steepness {@code shape}. */
    public static double ease(double t, double shape) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        double a = Math.pow(t, shape);
        return a / (a + Math.pow(1.0 - t, shape));
    }

    /**
     * A steepness for one glide: normal around {@link #SHAPE_MEAN}, redrawn when it falls outside the
     * limits (clamping would pile draws onto the limit values and leave sharp edges in the distribution).
     */
    public static double sampleShape(RandomGenerator random) {
        for (int attempt = 0; attempt < 16; attempt++) {
            double shape = SHAPE_MEAN + SHAPE_SIGMA * random.nextGaussian();
            if (shape >= SHAPE_MIN && shape <= SHAPE_MAX) return shape;
        }
        return SHAPE_MEAN;
    }

    /** A pace factor for one glide: {@code exp(sigma * Z)}, redrawn when outside the limits. */
    public static double samplePace(RandomGenerator random, double sigma) {
        for (int attempt = 0; attempt < 16; attempt++) {
            double pace = Math.exp(sigma * random.nextGaussian());
            if (pace >= PACE_MIN && pace <= PACE_MAX) return pace;
        }
        return 1.0;
    }

    /** The pitch after tick {@code tick} of a glide of {@code total} ticks from {@code from} to {@code to}. */
    public static float at(float from, float to, int tick, int total, double shape) {
        return from + (to - from) * (float) ease((double) Math.min(tick, total) / total, shape);
    }
}
