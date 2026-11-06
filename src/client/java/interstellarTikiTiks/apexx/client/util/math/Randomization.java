package interstellarTikiTiks.apexx.client.util.math;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomization helpers scaled 1-100 and tuned to look human on the wire.
 * Autocorrelated streams, smooth value noise, and human distributions.
 */
public final class Randomization {

    public static final int PERCENT_MIN = 1;
    public static final int PERCENT_MAX = 100;

    private static final double TAU = Math.PI * 2.0;

    private Randomization() {
    }

    /** Clamps a 1-100 slider value into the valid range. */
    public static int clampPercent(int percent) {
        return Math.max(PERCENT_MIN, Math.min(PERCENT_MAX, percent));
    }

    /** Maps a 1-100 slider value to an internal {@code [0.01, 1.0]} amplitude. */
    public static double amplitude(int percent) {
        return clampPercent(percent) / 100.0;
    }

    /** Maps an internal {@code [0.0, 1.0]} amplitude back onto the 1-100 scale. */
    public static int amplitudePercent(double unit) {
        return clampPercent((int) Math.round(unit * 100.0));
    }

    /** Pushes an exact multiple of {@code grid} off the grid without changing its magnitude. */
    public static double offGrid(double value, double grid) {
        if (grid <= 0.0 || value == 0.0) {
            return value;
        }
        double remainder = Math.abs(value % grid);
        if (remainder < grid * 1.0E-3 || grid - remainder < grid * 1.0E-3) {
            return value + (value >= 0.0 ? 1.0 : -1.0) * grid * 0.37;
        }
        return value;
    }

    public static double smoothStep(double t) {
        double x = Math.max(0.0, Math.min(1.0, t));
        return x * x * (3.0 - 2.0 * x);
    }

    public static double uniform(double min, double max) {
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (min == max) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static float uniformFloat(float min, float max) {
        if (min > max) {
            float swap = min;
            min = max;
            max = swap;
        }
        if (min == max) {
            return min;
        }
        return ThreadLocalRandom.current().nextFloat(min, max);
    }

    public static int uniformInt(int minInclusive, int maxExclusive) {
        if (minInclusive >= maxExclusive) {
            return minInclusive;
        }
        return ThreadLocalRandom.current().nextInt(minInclusive, maxExclusive);
    }

    public static long uniformLong(long minInclusive, long maxExclusive) {
        if (minInclusive >= maxExclusive) {
            return minInclusive;
        }
        return ThreadLocalRandom.current().nextLong(minInclusive, maxExclusive);
    }

    public static boolean chance(double probability) {
        if (probability <= 0.0) {
            return false;
        }
        if (probability >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    public static double gaussian(double mean, double stdDev) {
        if (stdDev == 0.0) {
            return mean;
        }
        return mean + ThreadLocalRandom.current().nextGaussian() * stdDev;
    }

    public static double gaussianClamped(double mean, double stdDev, double min, double max) {
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        return Math.max(min, Math.min(max, Randomization.gaussian(mean, stdDev)));
    }

    public static double triangular(double min, double mode, double max) {
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (max == min) {
            return min;
        }
        double clampedMode = Math.max(min, Math.min(max, mode));
        double split = (clampedMode - min) / (max - min);
        double u = ThreadLocalRandom.current().nextDouble();
        if (u < split) {
            return min + Math.sqrt(u * (max - min) * (clampedMode - min));
        }
        return max - Math.sqrt((1.0 - u) * (max - min) * (max - clampedMode));
    }

    public static double logNormal(double median, double sigma) {
        if (median <= 0.0 || sigma <= 0.0) {
            return Math.max(median, 0.0);
        }
        return median * Math.exp(sigma * ThreadLocalRandom.current().nextGaussian());
    }

    /** Right-skewed human reaction delay clamped to {@code [minMs, maxMs]}. */
    public static long reactionMs(double minMs, double maxMs) {
        double low = Math.min(minMs, maxMs);
        double high = Math.max(minMs, maxMs);
        if (high <= low) {
            return Math.round(low);
        }
        double median = low + (high - low) * 0.35;
        double value = median * Math.exp(0.45 * ThreadLocalRandom.current().nextGaussian());
        return Math.round(Math.max(low, Math.min(high, value)));
    }

    /** Exponentially distributed interval (Poisson process) around {@code meanMs}. */
    public static long poissonInterval(long meanMs, long minMs, long maxMs) {
        long low = Math.min(minMs, maxMs);
        long high = Math.max(minMs, maxMs);
        if (meanMs <= 0L) {
            return Math.max(0L, low);
        }
        double u = ThreadLocalRandom.current().nextDouble();
        long value = Math.round(-Math.log(1.0 - u) * meanMs);
        return Math.max(low, Math.min(high, value));
    }

    public static double biased(double min, double max, double exponent) {
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        if (max == min) {
            return min;
        }
        double u = Math.pow(ThreadLocalRandom.current().nextDouble(), exponent);
        return min + (max - min) * u;
    }

    public static double jitter(double magnitude) {
        if (magnitude <= 0.0) {
            return 0.0;
        }
        double value = ThreadLocalRandom.current().nextGaussian() * (magnitude / 3.0);
        return Math.max(-magnitude, Math.min(magnitude, value));
    }

    public static double signed(double magnitude) {
        if (magnitude <= 0.0) {
            return 0.0;
        }
        return ThreadLocalRandom.current().nextBoolean() ? magnitude : -magnitude;
    }

    public static int weightedIndex(double... weights) {
        if (weights == null || weights.length == 0) {
            return -1;
        }
        double total = 0.0;
        for (double weight : weights) {
            if (weight > 0.0) {
                total += weight;
            }
        }
        if (total <= 0.0) {
            return Randomization.uniformInt(0, weights.length);
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0.0) {
                continue;
            }
            roll -= weights[i];
            if (roll <= 0.0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    @SafeVarargs
    public static <T> T pick(T... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        return values[Randomization.uniformInt(0, values.length)];
    }

    public static <T> T pick(List<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(Randomization.uniformInt(0, values.size()));
    }

    public static <T> void shuffle(List<T> values) {
        if (values == null) {
            return;
        }
        for (int i = values.size() - 1; i > 0; i--) {
            int j = Randomization.uniformInt(0, i + 1);
            T swap = values.get(i);
            values.set(i, values.get(j));
            values.set(j, swap);
        }
    }

    /**
     * Deterministic 1D value noise in {@code [-1, 1]}, continuous across neighbouring
     * samples. Use it to drive smoothly wandering offsets instead of re-rolling per tick.
     */
    public static final class SmoothNoise {
        private final int seed;

        public SmoothNoise() {
            this(ThreadLocalRandom.current().nextInt());
        }

        public SmoothNoise(int seed) {
            this.seed = seed;
        }

        public double sample(double x) {
            int anchor = (int) Math.floor(x);
            double t = Randomization.smoothStep(x - anchor);
            double a = this.unitHash(anchor);
            double b = this.unitHash(anchor + 1);
            return (a + (b - a) * t) * 2.0 - 1.0;
        }

        public double fbm(double x, int octaves) {
            return this.fbm(x, octaves, 2.0, 0.5);
        }

        public double fbm(double x, int octaves, double lacunarity, double gain) {
            double amplitude = 1.0;
            double frequency = 1.0;
            double total = 0.0;
            double normalizer = 0.0;
            int count = Math.max(1, octaves);
            for (int i = 0; i < count; i++) {
                total += this.sample(x * frequency) * amplitude;
                normalizer += amplitude;
                frequency *= lacunarity;
                amplitude *= gain;
            }
            return normalizer <= 0.0 ? 0.0 : total / normalizer;
        }

        private double unitHash(int x) {
            int h = x * 374761393 + this.seed * 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            h ^= h >>> 16;
            return (h & 0x7fffffff) / (double) 0x7fffffff;
        }
    }

    /**
     * Stateful generator with its own PRNG. Give each module its own instance so sequences
     * never correlate across modules.
     */
    public static final class Stream {
        private final Random random;
        private final SmoothNoise noiseSource;
        private double walk;

        public Stream() {
            this(ThreadLocalRandom.current().nextLong());
        }

        public Stream(long seed) {
            this.random = new Random(seed);
            this.noiseSource = new SmoothNoise((int) (seed ^ (seed >>> 32)));
        }

        public double uniform(double min, double max) {
            if (min > max) {
                double swap = min;
                min = max;
                max = swap;
            }
            if (min == max) {
                return min;
            }
            return min + this.random.nextDouble() * (max - min);
        }

        public float uniformFloat(float min, float max) {
            return (float) this.uniform(min, max);
        }

        public int uniformInt(int minInclusive, int maxExclusive) {
            if (minInclusive >= maxExclusive) {
                return minInclusive;
            }
            return minInclusive + this.random.nextInt(maxExclusive - minInclusive);
        }

        public long uniformLong(long minInclusive, long maxExclusive) {
            if (minInclusive >= maxExclusive) {
                return minInclusive;
            }
            return minInclusive + (long) (this.random.nextDouble() * (maxExclusive - minInclusive));
        }

        public boolean chance(double probability) {
            if (probability <= 0.0) {
                return false;
            }
            if (probability >= 1.0) {
                return true;
            }
            return this.random.nextDouble() < probability;
        }

        public double gaussian(double mean, double stdDev) {
            return stdDev == 0.0 ? mean : mean + this.random.nextGaussian() * stdDev;
        }

        public double gaussianClamped(double mean, double stdDev, double min, double max) {
            if (min > max) {
                double swap = min;
                min = max;
                max = swap;
            }
            return Math.max(min, Math.min(max, this.gaussian(mean, stdDev)));
        }

        public double triangular(double min, double mode, double max) {
            if (min > max) {
                double swap = min;
                min = max;
                max = swap;
            }
            if (max == min) {
                return min;
            }
            double clampedMode = Math.max(min, Math.min(max, mode));
            double split = (clampedMode - min) / (max - min);
            double u = this.random.nextDouble();
            if (u < split) {
                return min + Math.sqrt(u * (max - min) * (clampedMode - min));
            }
            return max - Math.sqrt((1.0 - u) * (max - min) * (max - clampedMode));
        }

        public double logNormal(double median, double sigma) {
            if (median <= 0.0 || sigma <= 0.0) {
                return Math.max(median, 0.0);
            }
            return median * Math.exp(sigma * this.random.nextGaussian());
        }

        public long reactionMs(double minMs, double maxMs) {
            double low = Math.min(minMs, maxMs);
            double high = Math.max(minMs, maxMs);
            if (high <= low) {
                return Math.round(low);
            }
            double median = low + (high - low) * 0.35;
            double value = median * Math.exp(0.45 * this.random.nextGaussian());
            return Math.round(Math.max(low, Math.min(high, value)));
        }

        public long poissonInterval(long meanMs, long minMs, long maxMs) {
            long low = Math.min(minMs, maxMs);
            long high = Math.max(minMs, maxMs);
            if (meanMs <= 0L) {
                return Math.max(0L, low);
            }
            double value = -Math.log(1.0 - this.random.nextDouble()) * meanMs;
            return Math.max(low, Math.min(high, Math.round(value)));
        }

        public double jitter(double magnitude) {
            if (magnitude <= 0.0) {
                return 0.0;
            }
            double value = this.random.nextGaussian() * (magnitude / 3.0);
            return Math.max(-magnitude, Math.min(magnitude, value));
        }

        public double signed(double magnitude) {
            if (magnitude <= 0.0) {
                return 0.0;
            }
            return this.random.nextBoolean() ? magnitude : -magnitude;
        }

        public int weightedIndex(double... weights) {
            if (weights == null || weights.length == 0) {
                return -1;
            }
            double total = 0.0;
            for (double weight : weights) {
                if (weight > 0.0) {
                    total += weight;
                }
            }
            if (total <= 0.0) {
                return this.uniformInt(0, weights.length);
            }
            double roll = this.random.nextDouble() * total;
            for (int i = 0; i < weights.length; i++) {
                if (weights[i] <= 0.0) {
                    continue;
                }
                roll -= weights[i];
                if (roll <= 0.0) {
                    return i;
                }
            }
            return weights.length - 1;
        }

        public double noise(double x) {
            return this.noiseSource.sample(x);
        }

        public double fbm(double x, int octaves) {
            return this.noiseSource.fbm(x, octaves);
        }

        /**
         * Bounded, autocorrelated walk. Successive calls drift by a gaussian step and reflect
         * off the bounds instead of re-rolling.
         */
        public double walk(double min, double max, double step) {
            if (max <= min) {
                this.walk = min;
                return min;
            }
            double candidate = this.walk + this.random.nextGaussian() * step;
            if (candidate > max) {
                candidate = max - (candidate - max);
            } else if (candidate < min) {
                candidate = min + (min - candidate);
            }
            this.walk = Math.max(min, Math.min(max, candidate));
            return this.walk;
        }

        public double offGrid(double value, double grid) {
            return Randomization.offGrid(value, grid);
        }
    }

    /**
     * Layered human tremor for aim/offset jitter: 1/f drift from fbm plus a slow tremor and
     * decaying Poisson impulses.
     */
    public static final class HumanizedJitter {
        private final Stream stream;
        private final SmoothNoise drift;
        private final double units;
        private final double driftFrequency;
        private final double tremorFrequency;
        private final double tremorPhase;
        private final long startTime;
        private double impulse;
        private long nextImpulseAt;

        public HumanizedJitter(int percent) {
            this(percent, 1.0);
        }

        public HumanizedJitter(int percent, double units) {
            this.stream = new Stream();
            this.drift = new SmoothNoise();
            this.units = Math.max(0.0, units) * Randomization.amplitude(percent);
            this.driftFrequency = 0.15 + this.stream.uniform(0.0, 0.25);
            this.tremorFrequency = 1.5 + this.stream.uniform(0.0, 3.0);
            this.tremorPhase = this.stream.uniform(0.0, TAU);
            this.startTime = System.currentTimeMillis();
            this.nextImpulseAt = this.startTime + this.stream.poissonInterval(180L, 60L, 500L);
        }

        public double sample(long timeMs) {
            if (this.units <= 0.0) {
                return 0.0;
            }
            double elapsedSeconds = (timeMs - this.startTime) / 1000.0;
            double driftValue = this.drift.fbm(elapsedSeconds * this.driftFrequency, 3);
            double tremor = Math.sin(timeMs * this.tremorFrequency * TAU / 1000.0 + this.tremorPhase) * 0.18;
            if (timeMs >= this.nextImpulseAt) {
                this.impulse = this.stream.jitter(0.55);
                this.nextImpulseAt = timeMs + this.stream.poissonInterval(180L, 60L, 500L);
            }
            this.impulse *= 0.88;
            double value = (driftValue * 0.7 + tremor + this.impulse) * this.units;
            return Randomization.offGrid(value, 0.01);
        }
    }
}
