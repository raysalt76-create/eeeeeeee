package interstellarTikiTiks.apexx.client.util.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure math behind Jump Circles, Breadcrumbs, Swing/Projectile Trails, Block Outline and Item Glow. */
public class RenderVisualMathTest {

    @Test
    public void trailBufferKeepsNewestWhenFull() {
        TrailBuffer buffer = new TrailBuffer(3);
        for (int i = 0; i < 5; i++) buffer.add(i, i * 2, i * 3, i * 10L);
        assertEquals(3, buffer.size());
        assertEquals(2.0, buffer.x(0));
        assertEquals(4.0, buffer.x(2));
        assertEquals(40L, buffer.newestTime());
        assertEquals(0.0, buffer.distanceSqToNewest(4, 8, 12));
    }

    @Test
    public void trailBufferPrunesByTimeAndResizesKeepingNewest() {
        TrailBuffer buffer = new TrailBuffer(8);
        for (int i = 0; i < 6; i++) buffer.add(i, 0, 0, i * 100L);
        buffer.pruneOlderThan(250L);
        assertEquals(3, buffer.size());
        assertEquals(3.0, buffer.x(0));
        buffer.setCapacity(2);
        assertEquals(2, buffer.size());
        assertEquals(4.0, buffer.x(0));
        assertEquals(5.0, buffer.x(1));
        buffer.add(6, 0, 0, 600L);
        assertEquals(5.0, buffer.x(0));
        buffer.pruneOlderThan(10_000L);
        assertTrue(buffer.isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, buffer.distanceSqToNewest(0, 0, 0));
    }

    @Test
    public void fadeAndRingEasing() {
        assertEquals(1.0f, TrailMath.fade(0L, 1000L), 1e-6f);
        assertEquals(0.0f, TrailMath.fade(1000L, 1000L), 1e-6f);
        assertEquals(0.5f, TrailMath.fade(500L, 1000L), 1e-6f);
        assertEquals(1.0f, TrailMath.lifeFraction(5L, 0L), 1e-6f);
        assertEquals(0.0f, TrailMath.ringRadius(0.0f, 2.0f), 1e-6f);
        assertEquals(2.0f, TrailMath.ringRadius(1.0f, 2.0f), 1e-6f);
        // ease-out: more than half the radius by the time half the life has passed
        assertTrue(TrailMath.ringRadius(0.5f, 2.0f) > 1.0f);
        float p = TrailMath.pulse(0.25, 1.0);
        assertEquals(1.0f, p, 1e-5f);
    }

    @Test
    public void colourHelpers() {
        assertEquals(0x80FF0000, TrailMath.withAlpha(0xFFFF0000, 128f / 255f));
        assertEquals(0x40123456, TrailMath.scaleAlpha(0x80123456, 0.5f));
        assertEquals(0xFF7F7F7F, TrailMath.lerpArgb(0xFF000000, 0xFFFEFEFE, 0.5f));
        assertEquals(0xFFFF0000, TrailMath.hsv(0.0f, 1.0f, 1.0f));
        assertEquals(0xFF00FF00, TrailMath.hsv(1.0f / 3.0f, 1.0f, 1.0f));
        assertEquals(0xFFFF0000, TrailMath.hsv(1.0f, 1.0f, 1.0f)); // wraps
        assertEquals(0xFFFFFFFF, TrailMath.brighten(0xFF000000, 1.0f));
    }

    @Test
    public void outlineWidthIsScreenConstant() {
        float near = TrailMath.screenConstantHalfWidth(2.0f, 2.0);
        float far = TrailMath.screenConstantHalfWidth(2.0f, 8.0);
        assertEquals(4.0f, far / near, 1e-4f);
    }

    @Test
    public void firstPersonBladeSitsInFrontAndToTheArmSide() {
        Matrix4f scratch = new Matrix4f();
        Vector3f right = SwingTipModel.firstPerson(0.0f, true, 1.0f, scratch, new Vector3f());
        Vector3f left = SwingTipModel.firstPerson(0.0f, false, 1.0f, scratch, new Vector3f());
        Vector3f pommel = SwingTipModel.firstPerson(0.0f, true, 0.0f, scratch, new Vector3f());
        assertTrue(right.z < 0.0f, "tip in front of the camera: " + right);
        assertTrue(right.x > 0.0f, "right-hand tip on the right: " + right);
        // Vanilla mirrors the transform, not the flat item mesh, so only the side is mirrored exactly.
        assertTrue(left.x < 0.0f, "left-hand tip on the left: " + left);
        assertTrue(left.z < 0.0f);
        assertTrue(right.distance(pommel) > 0.3f);
        // The swing actually moves the tip.
        Vector3f mid = SwingTipModel.firstPerson(0.5f, true, 1.0f, scratch, new Vector3f());
        assertTrue(mid.distance(right) > 0.1f, "swing moves the tip: " + mid + " vs " + right);
    }

    @Test
    public void thirdPersonBladeIsAtHandHeightOnTheArmSide() {
        Matrix4f scratch = new Matrix4f();
        Vector3f right = SwingTipModel.thirdPerson(0.0f, true, 0.0f, false, 1.0f, scratch, new Vector3f());
        Vector3f left = SwingTipModel.thirdPerson(0.0f, false, 0.0f, false, 1.0f, scratch, new Vector3f());
        assertTrue(right.x > 0.0f, "right arm blade on the right: " + right);
        assertTrue(right.y > 0.4f && right.y < 1.9f, "blade near hand height: " + right);
        assertTrue(right.z > 0.0f, "blade held forward: " + right);
        assertTrue(left.x < 0.0f, "left arm blade on the left: " + left);
        Vector3f mid = SwingTipModel.thirdPerson(0.5f, true, 0.0f, false, 1.0f, scratch, new Vector3f());
        assertTrue(mid.distance(right) > 0.2f, "swing moves the blade: " + mid);
        Vector3f sneak = SwingTipModel.thirdPerson(0.0f, true, 0.0f, true, 1.0f, scratch, new Vector3f());
        assertTrue(sneak.y < right.y, "sneaking lowers the blade");
    }

    @Test
    public void fovCorrectionMatchesProjection() {
        assertEquals(1.0f, SwingTipModel.fovCorrection(70.0f, 70.0f), 1e-6f);
        float k = SwingTipModel.fovCorrection(70.0f, 90.0f);
        assertEquals(Math.tan(Math.toRadians(45)) / Math.tan(Math.toRadians(35)), k, 1e-5);
    }
}
