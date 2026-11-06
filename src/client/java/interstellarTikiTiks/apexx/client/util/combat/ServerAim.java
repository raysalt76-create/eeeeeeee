package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Server-visible aim and live raycast consistency.
 */
public final class ServerAim {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private ServerAim() {
    }

    public static Vec3d getRotationVector(float pitch, float yaw) {
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = (float) Math.cos(g);
        float i = (float) Math.sin(g);
        float j = (float) Math.cos(f);
        float k = (float) Math.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    public static BlockHitResult rayFromRotation(float yaw, float pitch) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d dir = getRotationVector(pitch, yaw);
        double reach = mc.player.getBlockInteractionRange();
        return mc.world.raycast(new RaycastContext(eye, eye.add(dir.multiply(reach)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    public static HitResult getCrosshairTarget(float yaw, float pitch) {
        double reach = mc.player != null ? mc.player.getEntityInteractionRange() : 3.0;
        return getCrosshairTarget(yaw, pitch, reach);
    }

    public static HitResult getCrosshairTarget(float yaw, float pitch, double entityReach) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d dir = getRotationVector(pitch, yaw);
        double blockReach = mc.player.getBlockInteractionRange();
        Vec3d blockEnd = eye.add(dir.multiply(blockReach));
        BlockHitResult blockHit = mc.world.raycast(new RaycastContext(eye, blockEnd,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        Vec3d entityEnd = eye.add(dir.multiply(entityReach));
        Box box = new Box(eye, entityEnd).expand(1.0);
        var entityHit = ProjectileUtil.getEntityCollision(
                mc.world, mc.player, eye, entityEnd, box,
                entity -> !entity.isSpectator() && entity != mc.player && entity.canHit(), 0.3f);

        if (entityHit != null) {
            double blockDist = blockHit != null ? eye.squaredDistanceTo(blockHit.getPos()) : Double.MAX_VALUE;
            double entityDist = eye.squaredDistanceTo(entityHit.getPos());
            if (entityDist < blockDist) {
                return entityHit;
            }
        }
        return blockHit;
    }

    public static BlockHitResult rayFromLook() {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d dir = mc.player.getRotationVec(1.0F);
        double reach = mc.player.getBlockInteractionRange();
        return mc.world.raycast(new RaycastContext(eye, eye.add(dir.multiply(reach)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }
}
