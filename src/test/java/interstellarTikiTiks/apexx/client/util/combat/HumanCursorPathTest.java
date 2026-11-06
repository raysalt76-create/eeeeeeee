package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanCursorPathTest {

    private static final HumanCursorPath.Params DEFAULT = new HumanCursorPath.Params(1.0, 0.4, 0.3, 0.0, 0.6);

    @Test
    void fittsTimeGrowsWithDistanceAndShrinksWithWidth() {
        assertTrue(HumanCursorPath.fittsMs(200, 16) > HumanCursorPath.fittsMs(50, 16));
        assertTrue(HumanCursorPath.fittsMs(100, 32) < HumanCursorPath.fittsMs(100, 16));
        assertEquals(HumanCursorPath.FITTS_A_MS, HumanCursorPath.fittsMs(0, 16), 1e-9);
    }

    @Test
    void minimumJerkIsMonotonicWithFixedEnds() {
        assertEquals(0.0, HumanCursorPath.minimumJerk(0.0), 1e-12);
        assertEquals(1.0, HumanCursorPath.minimumJerk(1.0), 1e-12);
        assertEquals(0.5, HumanCursorPath.minimumJerk(0.5), 1e-12);
        double previous = 0.0;
        for (int i = 1; i <= 100; i++) {
            double v = HumanCursorPath.minimumJerk(i / 100.0);
            assertTrue(v >= previous);
            previous = v;
        }
    }

    @Test
    void pathStartsAtStartAndEndsInsideTheSlot() {
        for (int seed = 0; seed < 200; seed++) {
            HumanCursorPath path = HumanCursorPath.plan(10, 20, 150, 90, 16, DEFAULT, new Random(seed));
            double[] start = path.positionAt(0);
            assertEquals(10, start[0], 1e-6);
            assertEquals(20, start[1], 1e-6);
            double[] end = path.positionAt(path.totalMs() + 1);
            assertTrue(Math.abs(end[0] - 150) <= 4.0 + 1e-9, "x " + end[0]);
            assertTrue(Math.abs(end[1] - 90) <= 4.0 + 1e-9, "y " + end[1]);
            assertTrue(path.isDone(path.totalMs()));
            assertFalse(path.isDone(path.totalMs() * 0.5));
        }
    }

    @Test
    void longerMovesTakeLonger() {
        double shortSum = 0;
        double longSum = 0;
        for (int seed = 0; seed < 50; seed++) {
            shortSum += HumanCursorPath.plan(0, 0, 30, 0, 16, DEFAULT, new Random(seed)).totalMs();
            longSum += HumanCursorPath.plan(0, 0, 300, 0, 16, DEFAULT, new Random(seed)).totalMs();
        }
        assertTrue(longSum > shortSum);
    }

    @Test
    void fasterSpeedShortensThePath() {
        HumanCursorPath slow = HumanCursorPath.plan(0, 0, 200, 50, 16,
                new HumanCursorPath.Params(1.0, 0.4, 0, 0, 0), new Random(7));
        HumanCursorPath fast = HumanCursorPath.plan(0, 0, 200, 50, 16,
                new HumanCursorPath.Params(2.0, 0.4, 0, 0, 0), new Random(7));
        assertTrue(fast.totalMs() < slow.totalMs());
    }

    @Test
    void overshootGoesPastTheTargetAndCorrects() {
        HumanCursorPath.Params always = new HumanCursorPath.Params(1.0, 0.0, 1.0, 0.0, 0.0);
        HumanCursorPath path = HumanCursorPath.plan(0, 0, 200, 0, 16, always, new Random(3));
        assertTrue(path.overshoots());
        assertEquals(2, path.segmentCount());
        double maxX = Double.NEGATIVE_INFINITY;
        for (double t = 0; t <= path.totalMs(); t += 1.0) {
            maxX = Math.max(maxX, path.positionAt(t)[0]);
        }
        assertTrue(maxX > path.endX() + 1.0, "never went past the target: " + maxX);
    }

    @Test
    void missLandsBesideAndStillFinishesOnTarget() {
        HumanCursorPath.Params miss = new HumanCursorPath.Params(1.0, 0.3, 0.0, 1.0, 0.0);
        HumanCursorPath path = HumanCursorPath.plan(0, 0, 200, 100, 16, miss, new Random(11));
        assertTrue(path.misses());
        assertEquals(2, path.segmentCount());
        double[] end = path.positionAt(path.totalMs() + 1);
        assertTrue(Math.hypot(end[0] - 200, end[1] - 100) <= 4.0 * Math.sqrt(2) + 1e-9);
    }

    @Test
    void zeroDistanceIsInstant() {
        HumanCursorPath path = HumanCursorPath.plan(50, 50, 50, 50, 16,
                new HumanCursorPath.Params(1.0, 0.4, 0.0, 0.0, 0.0), new Random(1));
        assertEquals(0.0, path.totalMs(), 1e-9);
        assertEquals(50.0, path.positionAt(10)[0], 1e-9);
    }
}
