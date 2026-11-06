package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossbowShotGeometryTest {
    private static final Box CART = new Box(2.01, 0.0625, -0.49, 2.99, 0.7625, 0.49);

    @Test
    void rejectsRayThroughRailCellThatMissesCart() {
        Vec3d origin = new Vec3d(0, 0.92, 0);
        Vec3d direction = new Vec3d(1, 0, 0);
        assertTrue(new Box(2, 0, -0.5, 3, 1, 0.5).raycast(origin, origin.add(6, 0, 0)).isPresent());
        assertNull(CrossbowShotGeometry.trace(origin, direction, CART, new Box(1, 0, -0.5, 2, 1, 0.5)));
    }

    @Test
    void fireBeyondCartCannotLightTheShot() {
        Vec3d origin = new Vec3d(0, 0.4, 0);
        assertNull(CrossbowShotGeometry.trace(origin, new Vec3d(1, 0, 0), CART,
                new Box(3.1, 0, -0.5, 4.1, 1, 0.5)));
    }

    @Test
    void fireAtImpactIsTooLateToIgniteBeforeContact() {
        assertNull(CrossbowShotGeometry.trace(new Vec3d(0, 0.4, 0), new Vec3d(1, 0, 0), CART,
                new Box(CART.minX, 0, -0.5, CART.maxX, 1, 0.5)));
    }

    @Test
    void stopsAtCartImpactInsteadOfTracingFireBehindIt() {
        Vec3d origin = new Vec3d(0, 0.4, 0);
        var shot = CrossbowShotGeometry.trace(origin, new Vec3d(1, 0, 0), CART,
                new Box(1, 0, -0.5, 2, 1, 0.5));
        assertNotNull(shot);
        assertEquals(2.01, shot.impact().x, 1.0e-8);
        assertEquals(shot.impact(), shot.segments().getLast().to());
    }

    @Test
    void arrowLaunchHeightMattersAtTheCartEdge() {
        Vec3d eye = new Vec3d(0, 0.15, 0);
        Box ignition = new Box(1, 0, -0.5, 2, 1, 0.5);
        assertNotNull(CrossbowShotGeometry.trace(eye, new Vec3d(1, 0, 0), CART, ignition));
        assertNull(CrossbowShotGeometry.trace(eye.add(0, -0.1, 0), new Vec3d(1, 0, 0), CART, ignition));
    }

    @Test
    void gravityIsAppliedBetweenFlightSegments() {
        Vec3d origin = new Vec3d(0, 0.775, 0);
        Box distant = CART.offset(2, 0, 0);
        assertTrue(distant.raycast(origin, origin.add(6, 0, 0)).isEmpty());
        var shot = CrossbowShotGeometry.trace(origin, new Vec3d(1, 0, 0), distant,
                new Box(3.3, 0, -0.5, 4, 1, 0.5));
        assertNotNull(shot, "Gravity brings the second segment into the cart");
        assertEquals(2, shot.segments().size());
        assertTrue(shot.impact().y < origin.y);
    }

    @Test
    void noShotBeyondTheBoundedRangeOrWithoutIgnition() {
        Vec3d origin = new Vec3d(0, 0.4, 0);
        assertNull(CrossbowShotGeometry.trace(origin, new Vec3d(1, 0, 0), CART.offset(6, 0, 0),
                new Box(1, 0, -0.5, 2, 1, 0.5)));
        assertNull(CrossbowShotGeometry.trace(origin, Vec3d.ZERO, CART, CART));
        assertNull(CrossbowShotGeometry.trace(origin, new Vec3d(1, 0, 0), CART, null));
    }

    @Test
    void centralAimSurvivesSpreadAcrossDirectionsHeightsAndRanges() {
        int checked = 0;
        for (int angle = 0; angle < 360; angle += 15) {
            for (double distance : new double[]{2.5, 3.5, 5.0}) {
                for (double height : new double[]{0.4, 1.52, 2.5}) {
                    double radians = Math.toRadians(angle);
                    Vec3d center = new Vec3d(Math.cos(radians) * distance, 0.4125, Math.sin(radians) * distance);
                    Vec3d origin = new Vec3d(0, height, 0);
                    Box cart = new Box(center.x - 0.49, 0.0625, center.z - 0.49,
                            center.x + 0.49, 0.7625, center.z + 0.49);
                    Vec3d fireCenter = origin.lerp(center, 0.55);
                    Box fire = new Box(fireCenter, fireCenter).expand(0.35);
                    for (Vec3d probe : CrossbowShotGeometry.spreadDirections(center.subtract(origin).normalize())) {
                        assertNotNull(CrossbowShotGeometry.trace(origin, probe, cart, fire),
                                "Central aim should survive an interior divergence sample");
                        checked++;
                    }
                }
            }
        }
        assertEquals(1512, checked);
    }
}
