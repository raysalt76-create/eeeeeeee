package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.MathHelper;

/**
 * Pearl Catch's second aim engine (the first is {@link WindChargeIntercept}'s seed-and-refine
 * solve). It does not look for one catching direction and polish it; it maps the whole catch region:
 * every look on a grid around a centre is thrown once, mean launch only, at the planned gap and at a
 * one-tick slip either way, and scored by the slip-weighted share that catches. That map is blurred
 * with the launch spread's own angular kernel (each axis of nextTriangular(0, 0.0172275) is a
 * Gaussian-like roll of about 0.4 degrees), and the look with the highest blurred value is the one
 * the spread is least likely to push out of the region: the region's robust centre rather than a
 * look on its edge, where a seed-and-refine search stops as soon as every spread sample still hits.
 *
 * <p>The grid is even in angle on the sphere: a yaw step is widened by 1/cos(pitch), so near vertical,
 * where the yaw barely moves a throw, it still spans the same arc. Pitch-only searches (the down macro)
 * run a single column.</p>
 */
public final class CatchRegionSolver {
    /** One axis of the launch spread, in degrees: nextTriangular(0, SPREAD) has deviation SPREAD / sqrt(6) radians. */
    public static final double SPREAD_SIGMA_DEG = Math.toDegrees(ThrowPhysics.SPREAD / Math.sqrt(6.0));
    /** Cells each side of the centre, per axis: the grid is (2 * CELLS + 1) squared looks. */
    private static final int CELLS = 10;
    /** Kernel half width, in standard deviations. */
    private static final double KERNEL_SIGMAS = 3.0;
    /** Never step finer than this; a finer grid only re-reads the same region. */
    private static final double MIN_STEP = 0.03;
    /** Yaw stretch near vertical is capped here (1/cos of about 84 degrees). */
    private static final double MAX_YAW_STRETCH = 10.0;

    /** Slip-weighted share of a mean throw with this look that catches, 0..1; 0 for a look that may not be used. */
    @FunctionalInterface
    public interface Oracle {
        double rate(float yaw, float pitch);
    }

    /**
     * The region's robust centre. {@code smoothed}: the blurred value there, the engine's own estimate of
     * the catch rate; {@code mass}: share of the grid that catches at all (how wide the region is).
     */
    public record Region(float yaw, float pitch, double smoothed, double mass) {}

    private CatchRegionSolver() {}

    /**
     * Maps the region around {@code centerYaw}/{@code centerPitch} over {@code windowDeg} degrees of arc
     * each side and returns its robust centre, or null when no look on the grid catches.
     * {@code pitchOnly}: keep the yaw and search the pitch alone.
     */
    public static Region solve(float centerYaw, float centerPitch, double windowDeg, boolean pitchOnly, Oracle oracle) {
        return solve(centerYaw, centerPitch, windowDeg, pitchOnly, 1.0, oracle);
    }

    /**
     * {@link #solve(float, float, double, boolean, Oracle)} with the blur widened {@code spreadScale}
     * times: sqrt(2) when both launches are still unknown, so two spreads add.
     */
    public static Region solve(float centerYaw, float centerPitch, double windowDeg, boolean pitchOnly, double spreadScale,
                               Oracle oracle) {
        if (oracle == null || !(windowDeg > 0.0)) return null;
        double step = Math.max(MIN_STEP, windowDeg / CELLS);
        int cells = (int) Math.ceil(windowDeg / step);
        double yawStep = step * yawStretch(centerPitch);
        return map(centerYaw, centerPitch, yawStep, step, pitchOnly ? 0 : cells, cells, spreadScale, oracle);
    }

    /**
     * The same on the mouse lattice: looks {@code base + i * step} on each axis, {@code span} steps each
     * side, every one of them a look the next rotation step can land on exactly.
     */
    public static Region solveLattice(float baseYaw, float basePitch, double step, int span, boolean pitchOnly,
                                      double spreadScale, Oracle oracle) {
        if (oracle == null || !(step > 0.0) || span < 0) return null;
        // Too fine a lattice is read at a coarser stride: the region does not need every step.
        int stride = Math.max(1, (int) Math.ceil(span / (double) CELLS));
        return map(baseYaw, basePitch, step * stride, step * stride, pitchOnly ? 0 : span / stride, span / stride,
                spreadScale, oracle);
    }

    private static double yawStretch(float pitch) {
        return Math.min(MAX_YAW_STRETCH, 1.0 / Math.max(1.0e-3, Math.cos(Math.toRadians(pitch))));
    }

    private static Region map(float centerYaw, float centerPitch, double yawStep, double pitchStep, int yawCells,
                              int pitchCells, double spreadScale, Oracle oracle) {
        int width = 2 * yawCells + 1;
        int height = 2 * pitchCells + 1;
        double[][] map = new double[width][height];
        double mass = 0.0;
        for (int i = 0; i < width; ++i) {
            float yaw = (float) (centerYaw + (i - yawCells) * yawStep);
            for (int j = 0; j < height; ++j) {
                float pitch = (float) (centerPitch + (j - pitchCells) * pitchStep);
                if (pitch < -90.0f || pitch > 90.0f) continue;
                double value = oracle.rate(yaw, pitch);
                map[i][j] = value;
                mass += value;
            }
        }
        if (mass <= 0.0) return null;
        // Each axis blurs by the spread's arc in its own cells: a yaw cell spans cos(pitch) of its degrees.
        double yawArc = Math.max(1.0e-3, yawStep / yawStretch(centerPitch));
        double sigma = SPREAD_SIGMA_DEG * spreadScale;
        double[][] blurred = blur(map, sigma / yawArc, sigma / pitchStep, yawCells == 0);
        double best = -1.0;
        for (double[] column : blurred) for (double value : column) best = Math.max(best, value);
        // The plateau's middle: of the cells within a hair of the best, the one nearest their mean.
        double sumI = 0.0, sumJ = 0.0;
        int count = 0;
        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                if (blurred[i][j] >= best - 1.0e-6) { sumI += i; sumJ += j; ++count; }
            }
        }
        double meanI = sumI / count, meanJ = sumJ / count;
        int bestI = 0, bestJ = 0;
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                if (blurred[i][j] < best - 1.0e-6) continue;
                double d = (i - meanI) * (i - meanI) + (j - meanJ) * (j - meanJ);
                if (d < nearest) { nearest = d; bestI = i; bestJ = j; }
            }
        }
        float yaw = (float) (centerYaw + (bestI - yawCells) * yawStep);
        float pitch = MathHelper.clamp((float) (centerPitch + (bestJ - pitchCells) * pitchStep), -90.0f, 90.0f);
        return new Region(yaw, pitch, best, mass / (width * (double) height));
    }

    /** Separable Gaussian blur, sigmas in cells per axis; cells off the grid count as no catch. */
    private static double[][] blur(double[][] map, double yawSigma, double pitchSigma, boolean pitchOnly) {
        int width = map.length;
        int height = map[0].length;
        double[] kernel = kernel(pitchSigma);
        int radius = kernel.length / 2;
        double[][] pass = new double[width][height];
        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                double sum = 0.0;
                for (int k = -radius; k <= radius; ++k) {
                    int jj = j + k;
                    if (jj >= 0 && jj < height) sum += kernel[k + radius] * map[i][jj];
                }
                pass[i][j] = sum;
            }
        }
        // A single column (pitch only) has no yaw neighbours to blur in; the fine rate that
        // validates every engine's pick still rolls the yaw.
        if (pitchOnly) return pass;
        kernel = kernel(yawSigma);
        radius = kernel.length / 2;
        double[][] out = new double[width][height];
        for (int i = 0; i < width; ++i) {
            for (int j = 0; j < height; ++j) {
                double sum = 0.0;
                for (int k = -radius; k <= radius; ++k) {
                    int ii = i + k;
                    if (ii >= 0 && ii < width) sum += kernel[k + radius] * pass[ii][j];
                }
                out[i][j] = sum;
            }
        }
        return out;
    }

    /** Normalised Gaussian taps out to {@link #KERNEL_SIGMAS}; a sigma under a tenth of a cell is a single tap. */
    private static double[] kernel(double sigma) {
        if (!(sigma > 0.1)) return new double[]{1.0};
        int radius = Math.max(1, (int) Math.ceil(KERNEL_SIGMAS * sigma));
        double[] kernel = new double[2 * radius + 1];
        double total = 0.0;
        for (int k = -radius; k <= radius; ++k) {
            kernel[k + radius] = Math.exp(-0.5 * (k / sigma) * (k / sigma));
            total += kernel[k + radius];
        }
        for (int k = 0; k < kernel.length; ++k) kernel[k] /= total;
        return kernel;
    }
}
