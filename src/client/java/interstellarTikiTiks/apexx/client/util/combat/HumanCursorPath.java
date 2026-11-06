package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A time-parametrised, hand-like pointer path between two screen points (GUI coordinates), with no
 * Minecraft dependency so it can be unit tested.
 *
 * <p>Model:
 * <ul>
 *   <li><b>Duration</b> from Fitts's law, {@code MT = a + b * log2(D / W + 1)}, divided by a speed factor.</li>
 *   <li><b>Shape</b>: a cubic Bezier whose two control points are pushed sideways by a random bend, so the
 *       hand travels on an arc rather than a ruler line.</li>
 *   <li><b>Timing</b>: the minimum-jerk profile {@code 10t^3 - 15t^4 + 6t^5} (slow start, fast middle, soft
 *       landing).</li>
 *   <li><b>Overshoot</b>: with a chance, the primary movement lands a few pixels past the target, then a short
 *       corrective sub-movement returns to it; otherwise the primary lands near the target with a small error
 *       and a micro-correction centres it.</li>
 *   <li><b>Miss</b>: with a chance, the primary lands beside the target (about one target width off) and the
 *       correction has to travel back. No click is ever made on the miss point.</li>
 *   <li><b>Tremor</b>: a small sinusoidal jitter enveloped by {@code sin(pi * progress)}, so the start and end
 *       points of every sub-movement stay exact.</li>
 * </ul>
 */
public final class HumanCursorPath {
    /** Fitts intercept and slope (ms) for a mouse at speed factor 1. */
    public static final double FITTS_A_MS = 80.0;
    public static final double FITTS_B_MS = 110.0;
    /** Pause between the primary movement and its correction (visual check), ms at speed 1. */
    private static final double CORRECTION_GAP_MIN_MS = 25.0;
    private static final double CORRECTION_GAP_MAX_MS = 70.0;

    /**
     * Movement parameters.
     *
     * @param speed           duration divisor (1 = average hand; 2 = twice as fast)
     * @param curvature       0..1, how far the arc may bend sideways relative to the distance
     * @param overshootChance 0..1 chance that the primary movement goes past the target
     * @param missChance      0..1 chance that the primary movement lands beside the target
     * @param jitterPx        tremor amplitude in GUI pixels
     */
    public record Params(double speed, double curvature, double overshootChance, double missChance, double jitterPx) {
        public Params {
            speed = Double.isFinite(speed) && speed > 0.05 ? speed : 1.0;
            curvature = clamp01(curvature);
            overshootChance = clamp01(overshootChance);
            missChance = clamp01(missChance);
            jitterPx = Double.isFinite(jitterPx) ? Math.max(0.0, jitterPx) : 0.0;
        }
    }

    private record Segment(double sx, double sy, double c1x, double c1y, double c2x, double c2y,
                           double ex, double ey, double startMs, double durationMs,
                           double tremorAmp, double phaseX, double phaseY, double freq) {
    }

    private final List<Segment> segments;
    private final double totalMs;
    private final double endX;
    private final double endY;
    private final boolean overshoot;
    private final boolean miss;

    private HumanCursorPath(List<Segment> segments, double endX, double endY, boolean overshoot, boolean miss) {
        this.segments = segments;
        Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        this.totalMs = last == null ? 0.0 : last.startMs + last.durationMs;
        this.endX = endX;
        this.endY = endY;
        this.overshoot = overshoot;
        this.miss = miss;
    }

    /** Fitts's-law movement time (ms) for {@code distance} to a target {@code width} wide, at speed factor 1. */
    public static double fittsMs(double distance, double width) {
        double d = Math.max(0.0, distance);
        double w = Math.max(1.0, width);
        return FITTS_A_MS + FITTS_B_MS * (Math.log(d / w + 1.0) / Math.log(2.0));
    }

    /** Minimum-jerk position profile for normalised time {@code t} in [0, 1]. */
    public static double minimumJerk(double t) {
        double u = clamp01(t);
        return u * u * u * (10.0 - 15.0 * u + 6.0 * u * u);
    }

    /**
     * Plans a path from (sx, sy) into a square target of side {@code targetSize} centred on (tx, ty).
     * The final point always lies within a quarter of the target size from its centre.
     */
    public static HumanCursorPath plan(double sx, double sy, double tx, double ty, double targetSize,
                                       Params params, Random random) {
        Params p = params == null ? new Params(1.0, 0.4, 0.3, 0.0, 0.6) : params;
        Random r = random == null ? new Random() : random;
        double width = Math.max(2.0, targetSize);
        if (Math.hypot(tx - sx, ty - sy) <= width * 0.25) {
            // Already resting on the target: no movement at all.
            return new HumanCursorPath(new ArrayList<>(0), sx, sy, false, false);
        }
        // Where the hand finally rests: near the centre, never on the slot edge.
        double finalX = tx + gauss(r) * width * 0.08;
        double finalY = ty + gauss(r) * width * 0.08;
        finalX = tx + clampAbs(finalX - tx, width * 0.25);
        finalY = ty + clampAbs(finalY - ty, width * 0.25);

        List<Segment> segments = new ArrayList<>(2);
        double dx = finalX - sx;
        double dy = finalY - sy;
        double distance = Math.hypot(dx, dy);
        if (!(distance > 0.5)) {
            return new HumanCursorPath(segments, finalX, finalY, false, false);
        }
        double ux = dx / distance;
        double uy = dy / distance;

        boolean miss = r.nextDouble() < p.missChance() && distance > width;
        boolean overshoot = !miss && r.nextDouble() < p.overshootChance() && distance > width;
        double landX;
        double landY;
        if (miss) {
            double angle = r.nextDouble() * Math.PI * 2.0;
            double off = width * (0.85 + r.nextDouble() * 0.4);
            landX = finalX + Math.cos(angle) * off;
            landY = finalY + Math.sin(angle) * off;
        } else if (overshoot) {
            double past = Math.max(2.0, Math.min(14.0, distance * (0.04 + r.nextDouble() * 0.06)));
            landX = finalX + ux * past + -uy * gauss(r) * 1.5;
            landY = finalY + uy * past + ux * gauss(r) * 1.5;
        } else {
            // An undershooting landing error, mostly along the travel direction.
            double along = -Math.abs(gauss(r)) * Math.min(6.0, distance * 0.03);
            landX = finalX + ux * along + -uy * gauss(r) * 1.0;
            landY = finalY + uy * along + ux * gauss(r) * 1.0;
        }

        double primaryMs = fittsMs(distance, width) / p.speed() * (0.9 + r.nextDouble() * 0.2);
        segments.add(segment(sx, sy, landX, landY, 0.0, primaryMs, p, r, 1.0));

        double corrDistance = Math.hypot(finalX - landX, finalY - landY);
        if (corrDistance > 0.35) {
            double gap = (CORRECTION_GAP_MIN_MS + r.nextDouble() * (CORRECTION_GAP_MAX_MS - CORRECTION_GAP_MIN_MS))
                    / p.speed();
            double corrMs = Math.max(30.0, fittsMs(corrDistance, width) * 0.75 / p.speed());
            segments.add(segment(landX, landY, finalX, finalY, primaryMs + gap, corrMs, p, r, 0.35));
        }
        return new HumanCursorPath(segments, finalX, finalY, overshoot, miss);
    }

    private static Segment segment(double sx, double sy, double ex, double ey, double startMs, double durationMs,
                                   Params p, Random r, double bendScale) {
        double dx = ex - sx;
        double dy = ey - sy;
        double distance = Math.hypot(dx, dy);
        double nx = distance > 0 ? -dy / distance : 0.0;
        double ny = distance > 0 ? dx / distance : 0.0;
        double sign = r.nextBoolean() ? 1.0 : -1.0;
        double bend = distance * p.curvature() * (0.05 + r.nextDouble() * 0.2) * sign * bendScale;
        double bend2 = bend * (0.4 + r.nextDouble() * 0.6);
        double c1x = sx + dx * (0.25 + r.nextDouble() * 0.1) + nx * bend;
        double c1y = sy + dy * (0.25 + r.nextDouble() * 0.1) + ny * bend;
        double c2x = sx + dx * (0.65 + r.nextDouble() * 0.1) + nx * bend2;
        double c2y = sy + dy * (0.65 + r.nextDouble() * 0.1) + ny * bend2;
        double amp = Math.min(p.jitterPx(), distance * 0.05);
        return new Segment(sx, sy, c1x, c1y, c2x, c2y, ex, ey, startMs, Math.max(1.0, durationMs),
                amp, r.nextDouble() * Math.PI * 2.0, r.nextDouble() * Math.PI * 2.0, 2.0 + r.nextDouble() * 3.0);
    }

    /** Cursor position {@code elapsedMs} after the path started; holds the end point once done. */
    public double[] positionAt(double elapsedMs) {
        if (segments.isEmpty()) return new double[]{endX, endY};
        double t = Math.max(0.0, elapsedMs);
        Segment active = segments.get(0);
        for (Segment s : segments) {
            if (t >= s.startMs) active = s;
        }
        double local = (t - active.startMs) / active.durationMs;
        if (local >= 1.0) {
            // In the gap after a segment, or past the end: rest where that segment ended.
            return new double[]{active.ex, active.ey};
        }
        double progress = minimumJerk(local);
        double x = bezier(active.sx, active.c1x, active.c2x, active.ex, progress);
        double y = bezier(active.sy, active.c1y, active.c2y, active.ey, progress);
        double envelope = Math.sin(Math.PI * clamp01(local));
        x += active.tremorAmp * envelope * Math.sin(active.phaseX + local * active.freq * Math.PI * 2.0);
        y += active.tremorAmp * envelope * Math.sin(active.phaseY + local * active.freq * Math.PI * 2.3);
        return new double[]{x, y};
    }

    public boolean isDone(double elapsedMs) {
        return elapsedMs >= totalMs;
    }

    public double totalMs() {
        return totalMs;
    }

    public double endX() {
        return endX;
    }

    public double endY() {
        return endY;
    }

    public boolean overshoots() {
        return overshoot;
    }

    public boolean misses() {
        return miss;
    }

    public int segmentCount() {
        return segments.size();
    }

    private static double bezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1.0 - t;
        return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3;
    }

    private static double gauss(Random r) {
        return Math.max(-2.5, Math.min(2.5, r.nextGaussian()));
    }

    private static double clampAbs(double v, double limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0;
    }
}
