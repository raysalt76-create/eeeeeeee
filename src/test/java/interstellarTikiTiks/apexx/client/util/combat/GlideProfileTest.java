package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statistical checks of the Headbob glide shape and pace draws (Statistical Invariants 1 and 2). */
class GlideProfileTest {
    private static final int SAMPLES = 200_000;

    @Test
    void easeIsAMonotoneSymmetricSCurve() {
        for (double shape = 1.5; shape <= 3.0; shape += 0.25) {
            assertEquals(0.0, GlideProfile.ease(0.0, shape), 0.0);
            assertEquals(1.0, GlideProfile.ease(1.0, shape), 0.0);
            assertEquals(0.5, GlideProfile.ease(0.5, shape), 1e-12);
            double previous = 0.0;
            for (int i = 1; i <= 1000; i++) {
                double t = i / 1000.0;
                double value = GlideProfile.ease(t, shape);
                assertTrue(value >= previous, "monotone at " + t);
                assertEquals(1.0, value + GlideProfile.ease(1.0 - t, shape), 1e-9, "point symmetry at " + t);
                previous = value;
            }
        }
    }

    @Test
    void shapeDrawsAreCentredOnTheMeanAndStayInsideTheClamp() {
        Random random = new Random(11);
        double sum = 0.0;
        int onEdge = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double shape = GlideProfile.sampleShape(random);
            assertTrue(shape >= GlideProfile.SHAPE_MIN && shape <= GlideProfile.SHAPE_MAX);
            if (shape == GlideProfile.SHAPE_MIN || shape == GlideProfile.SHAPE_MAX) onEdge++;
            sum += shape;
        }
        // Normal(2.1, 0.3) cut to [1.5, 3.0] by redrawing: the mean is the truncated-normal mean,
        // 2.1 + 0.3 * (phi(-2) - phi(3)) / (Phi(3) - Phi(-2)) = 2.1152.
        assertEquals(2.1152, sum / SAMPLES, 0.005);
        // Truncation is by redrawing, so no point mass piles up on a limit.
        assertEquals(0, onEdge, "draws exactly on a limit");
    }

    @Test
    void paceDrawsArePositivelySkewedLikeALogNormal() {
        Random random = new Random(12);
        double sigma = 0.25;
        double[] values = new double[SAMPLES];
        double sum = 0.0;
        int onEdge = 0;
        for (int i = 0; i < SAMPLES; i++) {
            values[i] = GlideProfile.samplePace(random, sigma);
            assertTrue(values[i] >= GlideProfile.PACE_MIN && values[i] <= GlideProfile.PACE_MAX);
            if (values[i] == GlideProfile.PACE_MIN || values[i] == GlideProfile.PACE_MAX) onEdge++;
            sum += values[i];
        }
        assertEquals(0, onEdge, "draws exactly on a limit");
        double mean = sum / SAMPLES;
        double m2 = 0.0;
        double m3 = 0.0;
        for (double v : values) {
            double d = v - mean;
            m2 += d * d;
            m3 += d * d * d;
        }
        double variance = m2 / SAMPLES;
        double skew = (m3 / SAMPLES) / Math.pow(variance, 1.5);
        // exp(0.25 Z): mean 1.032, skew ~0.78 before the clamp trims the tails a little.
        assertEquals(1.03, mean, 0.015);
        assertTrue(skew > 0.3 && skew < 1.0, "skew " + skew);
        double stdDevMs = Math.sqrt(variance);
        assertTrue(stdDevMs > 0.2 && stdDevMs < 0.3, "relative spread " + stdDevMs);
    }

    @Test
    void glideSecondDifferencesAreNotConstantAcrossGlides() {
        // Statistical Invariant 2: no repeated second-derivative profile across consecutive glides.
        Random random = new Random(13);
        int total = 4;
        double[] secondFirst = new double[5000];
        for (int g = 0; g < secondFirst.length; g++) {
            double shape = GlideProfile.sampleShape(random);
            float p0 = 0f;
            float p1 = GlideProfile.at(0f, 60f, 1, total, shape);
            float p2 = GlideProfile.at(0f, 60f, 2, total, shape);
            secondFirst[g] = (p2 - p1) - (p1 - p0);
        }
        double mean = 0.0;
        for (double v : secondFirst) mean += v;
        mean /= secondFirst.length;
        double variance = 0.0;
        for (double v : secondFirst) variance += (v - mean) * (v - mean);
        double std = Math.sqrt(variance / secondFirst.length);
        assertTrue(std > 0.5, "second difference std " + std + " degrees");
        // And it is a real accelerate-then-decelerate envelope inside one glide.
        float a = GlideProfile.at(0f, 60f, 1, 6, 2.1);
        float b = GlideProfile.at(0f, 60f, 2, 6, 2.1);
        float c = GlideProfile.at(0f, 60f, 3, 6, 2.1);
        float d = GlideProfile.at(0f, 60f, 4, 6, 2.1);
        float e = GlideProfile.at(0f, 60f, 5, 6, 2.1);
        float f = GlideProfile.at(0f, 60f, 6, 6, 2.1);
        assertTrue((b - a) > a && (c - b) > (b - a), "speeds up");
        assertTrue((d - c) > (e - d) && (e - d) > (f - e), "slows down");
        assertEquals(60f, f, 1e-3);
    }
}
