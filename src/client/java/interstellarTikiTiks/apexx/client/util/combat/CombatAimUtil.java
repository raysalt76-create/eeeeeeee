package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class CombatAimUtil {
    private CombatAimUtil() {
    }

    public static double getHitboxInsideRatio(Vec3d eyePos, Box box, float yaw, float pitch) {
        Vec3d center = box.getCenter();
        double dx = center.x - eyePos.x;
        double dy = center.y - eyePos.y;
        double dz = center.z - eyePos.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontalDist, 1.0E-3)));

        double yawDiff = Math.abs(MathHelper.wrapDegrees(yaw - targetYaw));
        double pitchDiff = Math.abs(MathHelper.wrapDegrees(pitch - targetPitch));

        double safeDist = Math.max(horizontalDist, 1.0E-3);
        double halfWidth = Math.max(box.getLengthX(), box.getLengthZ()) * 0.5;
        double yawHalfSpan = Math.toDegrees(Math.atan2(Math.max(halfWidth, 1.0E-3), safeDist));
        double yawInside = 1.0 - Math.min(1.0, yawDiff / Math.max(yawHalfSpan, 1.0E-3));

        double distance3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double safeVertical = Math.max(distance3d, 1.0E-3);
        double halfHeight = box.getLengthY() * 0.5;
        double pitchHalfSpan = Math.toDegrees(Math.atan2(Math.max(halfHeight, 1.0E-3), safeVertical));
        double pitchInside = 1.0 - Math.min(1.0, pitchDiff / Math.max(pitchHalfSpan, 1.0E-3));

        return Math.min(yawInside, pitchInside);
    }
}
