package interstellarTikiTiks.apexx.client.util.simulation;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class TrajectoryLaunchUtils {

    private static final double PROJECTILE_SPAWN_Y_OFFSET = 0.1;

    private TrajectoryLaunchUtils() {
    }

    public static Vec3d getProjectileSpawnPos(PlayerEntity player, float partialTick) {
        double x = player.getLerpedPos(partialTick).x;
        double eyeHeight = player.getEyeHeight(player.getPose());
        double y = player.getLerpedPos(partialTick).y + eyeHeight - PROJECTILE_SPAWN_Y_OFFSET;
        double z = player.getLerpedPos(partialTick).z;
        return new Vec3d(x, y, z);
    }

    public static Vec3d getWindChargeSpawnPos(PlayerEntity player, float partialTick) {
        double x = player.getLerpedPos(partialTick).x;
        double eyeHeight = player.getEyeHeight(player.getPose());
        double y = player.getLerpedPos(partialTick).y + eyeHeight;
        double z = player.getLerpedPos(partialTick).z;
        return new Vec3d(x, y, z);
    }

    public static Vec3d getLaunchDirection(PlayerEntity player, float partialTick) {
        return Vec3d.fromPolar(player.getPitch(partialTick), player.getYaw(partialTick)).normalize();
    }

    public static Vec3d getLaunchDirection(PlayerEntity player, float pitchOffset, float partialTick) {
        return Vec3d.fromPolar(player.getPitch(partialTick) + pitchOffset, player.getYaw(partialTick)).normalize();
    }

    public static Vec3d getInheritedVelocity(PlayerEntity player) {
        Vec3d movement = player.getVelocity();
        return player.isOnGround() ? new Vec3d(movement.x, 0.0, movement.z) : movement;
    }

}
