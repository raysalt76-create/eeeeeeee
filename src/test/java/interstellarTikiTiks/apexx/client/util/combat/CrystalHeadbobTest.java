package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class CrystalHeadbobTest {

    /** A crystal that the look hits between 10 and 30 degrees above a rest pitch of 20, on any yaw near rest. */
    private static boolean band(float yaw, float pitch) {
        double up = 20.0 - pitch;
        return up >= 10.0 && up <= 30.0 && Math.abs(yaw) < 40.0;
    }

    @Test
    public void handSignMirrorsTheDiagonal() {
        assertEquals(1, CrystalHeadbob.handSign(CrystalHeadbob.RIGHTY));
        assertEquals(-1, CrystalHeadbob.handSign(CrystalHeadbob.LEFTY));
        float[] right = CrystalHeadbob.along(0f, 20f, 1, CrystalHeadbob.TILT, 10.0);
        float[] left = CrystalHeadbob.along(0f, 20f, -1, CrystalHeadbob.TILT, 10.0);
        assertTrue(right[0] > 0f, "righty rises toward the right (yaw grows)");
        assertTrue(left[0] < 0f, "lefty rises toward the left");
        assertEquals(right[1], left[1], 1e-4);
        assertEquals(10.0, 20.0 - right[1], 1e-4, "rising lowers the pitch");
    }

    @Test
    public void windowFollowsTheHandsDiagonalFirst() {
        CrystalHeadbob.Window w = CrystalHeadbob.findWindow(0f, 20f, 1, CrystalHeadbobTest::band);
        assertNotNull(w);
        assertEquals(CrystalHeadbob.TILT, w.ratio(), 1e-9);
        assertEquals(10.0, w.enter(), 1.0);
        assertEquals(30.0, w.exit(), 1.0);
    }

    @Test
    public void windowFallsBackToStraightUpWhenTiltMisses() {
        // Only straight above rest hits: the tilted diagonals leave the target.
        CrystalHeadbob.Window w = CrystalHeadbob.findWindow(0f, 20f, 1,
                (yaw, pitch) -> Math.abs(yaw) < 0.5f && 20.0 - pitch >= 8.0 && 20.0 - pitch <= 20.0);
        assertNotNull(w);
        assertEquals(0.0, w.ratio(), 1e-9);
    }

    @Test
    public void noWindowWhenNothingIsReached() {
        assertNull(CrystalHeadbob.findWindow(0f, 20f, 1, (y, p) -> false));
    }

    @Test
    public void depthZeroStaysJustInsideTheFirstDegree() {
        CrystalHeadbob.Window w = new CrystalHeadbob.Window(0.4, 10.0, 30.0);
        float[] shallow = CrystalHeadbob.goalIn(w, 0f, 20f, 1, 0.0);
        float[] deep = CrystalHeadbob.goalIn(w, 0f, 20f, 1, 1.0);
        double shallowUp = 20.0 - shallow[1];
        double deepUp = 20.0 - deep[1];
        assertEquals(11.5, shallowUp, 1e-4, "just inside the entering edge");
        assertEquals(28.5, deepUp, 1e-4, "just inside the far edge, never past it");
        assertTrue(deepUp > shallowUp);
        assertEquals(0.4 * shallowUp, shallow[0], 1e-4);
    }

    @Test
    public void narrowWindowUsesItsMiddle() {
        CrystalHeadbob.Window w = new CrystalHeadbob.Window(0.4, 10.0, 12.0);
        assertEquals(11.0, 20.0 - CrystalHeadbob.goalIn(w, 0f, 20f, 1, 0.9)[1], 1e-4);
    }

    @Test
    public void depthScalesWithAmountAndVariationStaysBounded() {
        Random r = new Random(7);
        assertEquals(0.0, CrystalHeadbob.depthFor(0.0, false, r), 1e-9);
        assertTrue(CrystalHeadbob.depthFor(0.15, false, r) < CrystalHeadbob.depthFor(0.9, false, r));
        for (int i = 0; i < 500; i++) {
            double d = CrystalHeadbob.depthFor(0.5, true, r);
            assertTrue(d >= 0.0 && d <= 0.95, "bounded: " + d);
        }
        // Varies, and is not a flat range: not every draw is the same and not every draw is extreme.
        double a = CrystalHeadbob.depthFor(0.5, true, new Random(1));
        double b = CrystalHeadbob.depthFor(0.5, true, new Random(2));
        assertNotEquals(a, b);
    }

    @Test
    public void returnEndsExactlyAtRestAndArcsOnTheWay() {
        // At rest (or with no travel) the waypoint is rest itself.
        float[] home = CrystalHeadbob.returnWaypoint(5f, 20f, 5f, 20f, 1, 12.0);
        assertArrayEquals(new float[]{5f, 20f}, home, 1e-5f);
        home = CrystalHeadbob.returnWaypoint(5f, 20f, 7f, 18f, 1, 0.0);
        assertArrayEquals(new float[]{5f, 20f}, home, 1e-5f);
        // Near the end of the way down it is rest.
        float[] near = CrystalHeadbob.returnWaypoint(5f, 20f, 5.8f, 18f, 1, 12.0);
        assertArrayEquals(new float[]{5f, 20f}, near, 1e-5f);
        // From the peak it aims part of the way down and a little off the diagonal.
        float[] peak = CrystalHeadbob.along(5f, 20f, 1, 0.4, 12.0);
        float[] mid = CrystalHeadbob.returnWaypoint(5f, 20f, peak[0], peak[1], 1, 12.0);
        double diag = 5f + 0.4 * (20.0 - mid[1]);
        assertTrue(mid[1] < 20f && mid[1] > peak[1], "between peak and rest");
        assertNotEquals(diag, mid[0], 1e-3, "an arc, not a retrace");
        assertTrue(Math.abs(mid[0] - diag) <= 1.9, "arc stays small");
    }
}
