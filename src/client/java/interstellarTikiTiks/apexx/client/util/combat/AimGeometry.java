package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Shared aim geometry used by the combat modules so the FOV, multipoint and hurt-tick logic is
 * defined once instead of drifting per module (the exact failure mode that left AutoMace on a
 * different aim curve than AimAssist).
 *
 * <ul>
 *   <li>{@link #isInFov} — separate horizontal (yaw) and vertical (pitch) cones, matching echo's
 *       {@code CombatUtils.isInFOV(player, target, yawFov, pitchFov)}.</li>
 *   <li>{@link #closestBoxPoint} — the point on a hitbox nearest the current view line, for
 *       multipoint aim (less rotation to a valid hit → faster on-target, smaller flick).</li>
 *   <li>{@link #adjustedHurtTime} — vanilla {@code hurtTime} reduced by the target's ping in ticks,
 *       the "how recently was this entity hit" signal echo's HurtTickHandler exposes. Lower = fresher
 *       = better smash target (more likely out of i-frames).</li>
 * </ul>
 */
public final class AimGeometry {

    private AimGeometry() {
    }

    /**
     * Minecraft yaw (degrees) for a horizontal delta {@code (dx, dz)} from viewer to target:
     * {@code toDegrees(atan2(dz, dx)) - 90}. The one definition of the client's yaw convention;
     * callers pass whatever reference-point deltas they already compute.
     */
    public static double yawDegrees(double dx, double dz) {
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    /**
     * Minecraft pitch (degrees) for a vertical delta {@code dy} over horizontal distance
     * {@code horizontalDistance}: {@code -toDegrees(atan2(dy, horiz))}. Negative because MC pitch
     * is positive looking down.
     */
    public static double pitchDegrees(double dy, double horizontalDistance) {
        return -Math.toDegrees(Math.atan2(dy, horizontalDistance));
    }

    /** {@code [yaw, pitch]} in degrees from {@code from} to {@code to} using the shared convention. */
    public static float[] yawPitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) yawDegrees(dx, dz), (float) pitchDegrees(dy, horiz)};
    }

    /** True if {@code target} is within both the yaw and pitch cones of {@code self}'s current view. */
    public static boolean isInFov(PlayerEntity self, Entity target, double yawFov, double pitchFov) {
        if (self == null || target == null) {
            return false;
        }
        // A full cone is a no-op gate; skip the trig so the default (360/180) costs nothing.
        if (yawFov >= 360.0 && pitchFov >= 180.0) {
            return true;
        }
        Vec3d eye = self.getEyePos();
        Vec3d diff = target.getBoundingBox().getCenter().subtract(eye);
        double horiz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float targetYaw = (float) yawDegrees(diff.x, diff.z);
        float targetPitch = (float) pitchDegrees(diff.y, horiz);
        double yawDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - self.getYaw()));
        double pitchDiff = Math.abs(MathHelper.wrapDegrees(targetPitch - self.getPitch()));
        return yawDiff <= yawFov / 2.0 && pitchDiff <= pitchFov / 2.0;
    }

    /**
     * True if {@code target} is within a 3D spherical cone of angle {@code fovDegrees} around {@code self}'s crosshair.
     * Evaluated via 3D vector dot product: lookVec · (target - eye).normalize() >= cos(fov / 2).
     */
    public static boolean isInFovCone(PlayerEntity self, Entity target, double fovDegrees) {
        if (self == null || target == null) {
            return false;
        }
        if (fovDegrees >= 360.0) {
            return true;
        }
        Vec3d eye = self.getEyePos();
        Vec3d toTarget = target.getBoundingBox().getCenter().subtract(eye).normalize();
        double minDot = Math.cos(Math.toRadians(fovDegrees * 0.5));
        return self.getRotationVec(1.0f).dotProduct(toTarget) >= minDot;
    }

    /**
     * The point on {@code box} closest to the ray from {@code eye} along {@code look}. Approximated by
     * projecting along the current view to the box's distance and clamping into the box — cheap and
     * stable, and exactly what we want for multipoint (the nearest reachable hit point).
     */
    public static Vec3d closestBoxPoint(Box box, Vec3d eye, Vec3d look) {
        Vec3d center = box.getCenter();
        double dist = center.subtract(eye).length();
        Vec3d along = eye.add(look.multiply(dist));
        double x = MathHelper.clamp(along.x, box.minX, box.maxX);
        double y = MathHelper.clamp(along.y, box.minY, box.maxY);
        double z = MathHelper.clamp(along.z, box.minZ, box.maxZ);
        return new Vec3d(x, y, z);
    }

    /**
     * Vanilla {@code hurtTime} for {@code target}, reduced by that player's ping expressed in ticks
     * (50 ms = 1 tick). Zero means "not recently hurt" — the freshest, best smash target. Catches
     * hits from any source (ours or a teammate's) because {@code hurtTime} is server-driven.
     */
    public static int adjustedHurtTime(LivingEntity target) {
        if (target == null) {
            return 0;
        }
        int pingTicks = 0;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (target instanceof PlayerEntity && mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(target.getUuid());
            if (info != null) {
                pingTicks = info.getLatency() / 50;
            }
        }
        return Math.max(target.hurtTime - Math.max(pingTicks - 1, 0), 0);
    }
}
