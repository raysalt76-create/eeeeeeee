package interstellarTikiTiks.apexx.client.util;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Stateless angle and rotation math helpers shared by the rotation pipeline.
 */
public final class RotationMath {

    /** Minecraft clamps pitch to this range on both the wire and the camera. */
    public static final float PITCH_LIMIT = 90.0f;

    private RotationMath() {
    }

    /**
     * Yaw/pitch that points from eye at point.
     * The yaw is returned on the 360-degree branch nearest currentYaw.
     */
    public static float[] anglesTo(Vec3d eye, Vec3d point, float currentYaw) {
        return anglesTo(eye.x, eye.y, eye.z, point.x, point.y, point.z, currentYaw);
    }

    public static float[] anglesTo(double eyeX, double eyeY, double eyeZ,
                                   double x, double y, double z, float currentYaw) {
        double deltaX = x - eyeX;
        double deltaY = y - eyeY;
        double deltaZ = z - eyeZ;
        double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0f;
        float pitch = (float) (-Math.toDegrees(Math.atan2(deltaY, horizontal)));
        return new float[]{nearestYaw(yaw, currentYaw), clampPitch(pitch)};
    }

    /** Wraps yaw onto the 360-degree branch closest to reference. */
    public static float nearestYaw(float yaw, float reference) {
        return reference + MathHelper.wrapDegrees(yaw - reference);
    }

    /** Shortest signed yaw delta from from to to, in [-180, 180). */
    public static float yawDelta(float from, float to) {
        return MathHelper.wrapDegrees(to - from);
    }

    /**
     * Signed angular delta the step must cover on one axis.
     * Yaw takes the shortest wrapped path, pitch is bounded to [-90, 90].
     */
    public static double axisDelta(double current, double target, boolean yawAxis) {
        return yawAxis ? MathHelper.wrapDegrees((float) (target - current)) : target - current;
    }

    public static float clampPitch(float pitch) {
        return MathHelper.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT);
    }

    /**
     * Degrees of rotation produced by a single mouse-pixel step at the given sensitivity.
     */
    public static float mousePixelStep(double sensitivity) {
        float f = (float) sensitivity * 0.6f + 0.2f;
        return f * f * f * 1.2f;
    }

    /** Unit look vector for a yaw/pitch pair, matching the client's own rotation convention. */
    public static Vec3d direction(float yaw, float pitch) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vec3d(x, y, z).normalize();
    }
}
