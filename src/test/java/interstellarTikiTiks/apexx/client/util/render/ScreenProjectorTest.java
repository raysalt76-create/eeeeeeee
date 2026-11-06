package interstellarTikiTiks.apexx.client.util.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenProjectorTest {
    private static final float W = 800.0f;
    private static final float H = 600.0f;

    /** Identity camera rotation looks down -Z, like the default OpenGL eye space. */
    private static Matrix4f viewProj() {
        return ScreenProjector.buildViewProjection(new Matrix4f(), 70.0f, W / H, new Quaternionf());
    }

    @Test
    void pointStraightAheadIsScreenCentre() {
        float[] out = new float[3];
        assertTrue(ScreenProjector.project(viewProj(), 0.0f, 0.0f, -10.0f, W, H, out));
        assertEquals(W / 2.0f, out[0], 1.0e-3f);
        assertEquals(H / 2.0f, out[1], 1.0e-3f);
        assertEquals(10.0f, out[2], 1.0e-3f);
    }

    @Test
    void upAndRightMapToScreenAxes() {
        float[] out = new float[3];
        assertTrue(ScreenProjector.project(viewProj(), 1.0f, 1.0f, -10.0f, W, H, out));
        assertTrue(out[0] > W / 2.0f, "right of centre");
        assertTrue(out[1] < H / 2.0f, "screen y grows downward");
    }

    @Test
    void edgeOfVerticalFovHitsTopOfScreen() {
        float[] out = new float[3];
        float y = (float) Math.tan(Math.toRadians(35.0)) * 10.0f;
        assertTrue(ScreenProjector.project(viewProj(), 0.0f, y, -10.0f, W, H, out));
        assertEquals(0.0f, out[1], 1.0e-2f);
    }

    @Test
    void pointBehindCameraIsRejected() {
        float[] out = new float[3];
        assertFalse(ScreenProjector.project(viewProj(), 0.0f, 0.0f, 5.0f, W, H, out));
    }

    @Test
    void boxRectContainsProjectedCorners() {
        float[] rect = new float[4];
        assertTrue(ScreenProjector.projectBox(viewProj(), -0.3f, -1.0f, -6.0f, 0.3f, 0.8f, -5.4f, W, H, rect));
        assertTrue(rect[0] < W / 2.0f && rect[2] > W / 2.0f);
        assertTrue(rect[1] < H / 2.0f && rect[3] > H / 2.0f);
        float[] p = new float[3];
        ScreenProjector.project(viewProj(), 0.3f, 0.8f, -5.4f, W, H, p);
        assertTrue(p[0] <= rect[2] + 1.0e-3f && p[1] >= rect[1] - 1.0e-3f);
    }

    @Test
    void boxStraddlingCameraIsClippedNotRejected() {
        float[] rect = new float[4];
        assertTrue(ScreenProjector.projectBox(viewProj(), -0.5f, -0.5f, -3.0f, 0.5f, 0.5f, 3.0f, W, H, rect));
        assertTrue(rect[2] > rect[0] && rect[3] > rect[1]);
        assertFalse(ScreenProjector.projectBox(viewProj(), -0.5f, -0.5f, 1.0f, 0.5f, 0.5f, 3.0f, W, H, rect));
    }

    @Test
    void worldPerPixelScalesWithDistance() {
        double near = ScreenProjector.worldPerPixel(5.0, 70.0f, 600.0f);
        double far = ScreenProjector.worldPerPixel(10.0, 70.0f, 600.0f);
        assertEquals(2.0, far / near, 1.0e-9);
        assertEquals(2.0 * 10.0 * Math.tan(Math.toRadians(35.0)) / 600.0, far, 1.0e-9);
        assertEquals(0.0, ScreenProjector.worldPerPixel(5.0, 70.0f, 0.0f), 0.0);
    }

    @Test
    void overlapsScreenRespectsMargin() {
        assertTrue(ScreenProjector.overlapsScreen(10, 10, 20, 20, W, H, 0));
        assertFalse(ScreenProjector.overlapsScreen(-30, 10, -10, 20, W, H, 5));
        assertTrue(ScreenProjector.overlapsScreen(-30, 10, -3, 20, W, H, 5));
    }
}
