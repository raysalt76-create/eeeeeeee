package interstellarTikiTiks.apexx.client.util;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class RotationControllerTest {

    @Test
    public void testNearestYawBranch() {
        // Nearest yaw when turning across 180 / -180 boundary
        float reference = 179.0f;
        float target = -179.0f;
        float nearest = RotationMath.nearestYaw(target, reference);
        assertEquals(181.0f, nearest, 1e-4f);
        assertEquals(2.0f, nearest - reference, 1e-4f);

        // Nearest yaw when turning in negative territory
        reference = -179.0f;
        target = 179.0f;
        nearest = RotationMath.nearestYaw(target, reference);
        assertEquals(-181.0f, nearest, 1e-4f);
        assertEquals(-2.0f, nearest - reference, 1e-4f);
    }

    @Test
    public void testYawDelta() {
        assertEquals(2.0f, RotationMath.yawDelta(179.0f, -179.0f), 1e-4f);
        assertEquals(-2.0f, RotationMath.yawDelta(-179.0f, 179.0f), 1e-4f);
        assertEquals(45.0f, RotationMath.yawDelta(0.0f, 45.0f), 1e-4f);
        assertEquals(-45.0f, RotationMath.yawDelta(45.0f, 0.0f), 1e-4f);
    }

    @Test
    public void testStepBrakingAndSettle() {
        RotationController controller = new RotationController(new Random(42));
        RotationRequest req = RotationRequest.create()
                .speed(360.0)
                .response(0.65)
                .humanization(0.0)
                .prediction(0.0)
                .silent(true)
                .yawOnly(false);
        controller.apply(req);

        double curYaw = 0.0;
        double curPitch = 0.0;
        Vec3d target = new Vec3d(10.0, 0.0, 10.0); // 45 degrees yaw, 0 pitch from (0, 0, 0)
        float gcd = 0.05f;

        long now = 1000L;
        // Step multiple frames towards target
        for (int i = 0; i < 60; i++) {
            double[] move = controller.step(curYaw, curPitch, 0.0, 0.0, 0.0,
                    target, 0.0f, 0.0f, false, gcd, now + i * 16L, 1.0);
            curYaw += move[0];
            curPitch += move[1];
        }

        // Must converge to around -45 degrees (Minecraft yaw: atan2(z, x) - 90 deg -> atan2(10, 10) - 90 = -45)
        float[] expected = RotationMath.anglesTo(0, 0, 0, 10, 0, 10, 0.0f);
        assertEquals(expected[0], (float) curYaw, 0.5f);
        assertEquals(expected[1], (float) curPitch, 0.5f);
    }

    @Test
    public void testSmoothReleaseGlide() {
        RotationController controller = new RotationController(new Random(42));
        RotationRequest req = RotationRequest.create()
                .speed(360.0)
                .response(0.65)
                .humanization(0.0)
                .prediction(0.0)
                .silent(true)
                .yawOnly(false);
        controller.apply(req);

        // Player's camera is at (0, 0), authoritative wire rotation was at (50, 20)
        double curYaw = 50.0;
        double curPitch = 20.0;
        float visualYaw = 0.0f;
        float visualPitch = 0.0f;
        float gcd = 0.05f;

        long now = 1000L;
        // Glide back towards visual camera
        for (int i = 0; i < 60; i++) {
            double[] move = controller.step(curYaw, curPitch, 0.0, 0.0, 0.0,
                    null, visualYaw, visualPitch, true, gcd, now + i * 16L, 1.0);
            curYaw += move[0];
            curPitch += move[1];
        }

        // Should glide back to visual angles smoothly
        assertEquals(visualYaw, (float) curYaw, 0.5f);
        assertEquals(visualPitch, (float) curPitch, 0.5f);
    }

    @Test
    public void testYawOnlyPitchLocked() {
        RotationController controller = new RotationController(new Random(42));
        RotationRequest req = RotationRequest.create()
                .speed(360.0)
                .response(0.65)
                .humanization(0.0)
                .prediction(0.0)
                .silent(true)
                .yawOnly(true);
        controller.apply(req);

        double curYaw = 0.0;
        double curPitch = 15.0;
        Vec3d target = new Vec3d(10.0, 20.0, 10.0);
        float gcd = 0.05f;

        double[] move = controller.step(curYaw, curPitch, 0.0, 0.0, 0.0,
                target, 0.0f, 0.0f, false, gcd, 1000L, 1.0);

        // Pitch delta must be strictly 0.0 when yawOnly is enabled
        assertEquals(0.0, move[1], 1e-6);
        assertNotEquals(0.0, move[0]);
    }
}
