package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/**
 * The ray a server validates an attack against: from the eye position the server last saw, along the
 * rotation that goes on the wire (the silent rotation's final angles while one is active, the player's own
 * angles otherwise). Strict servers (Grim's Reach/Hitboxes, Intave's AttackRaytrace, Matrix's hitbox check)
 * all reject an attack whose target is not on that ray, so a module must not dispatch one on the strength of
 * a distance test or an angle tolerance.
 */
public final class WireRay {
    private WireRay() {
    }

    /**
     * True when the wire ray reaches {@code target}'s hitbox within {@code reach} blocks and no solid block
     * is closer. The hitbox is the vanilla one (targeting margin only), with no tolerance added.
     */
    public static boolean onEntity(MinecraftClient mc, Entity target, double reach) {
        if (mc == null || mc.player == null || mc.world == null || target == null || reach <= 0.0) return false;
        Vec3d eye = mc.player.getEyePos();
        // Silent rotation: the server receives the silent angles. Otherwise vanilla serializes the
        // player's own angles (which a visible rotation writes into), so that is the wire rotation.
        boolean silent = RotationManager.isSilentRotationActive();
        float yaw = silent ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = silent ? RotationManager.getFinalPitch() : mc.player.getPitch();
        Vec3d direction = mc.player.getRotationVector(pitch, yaw);
        Box box = target.getBoundingBox().expand(target.getTargetingMargin());
        double distance = hitDistance(eye, direction, box, reach);
        if (distance < 0.0) return false;
        if (distance <= 0.0) return true;
        Vec3d hitPoint = eye.add(direction.multiply(distance));
        var block = mc.world.raycast(new RaycastContext(eye, hitPoint, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, mc.player));
        return block == null || block.getType() == net.minecraft.util.hit.HitResult.Type.MISS
                || eye.distanceTo(block.getPos()) >= distance - 1.0e-4;
    }

    /**
     * Distance along the unit ray {@code eye + t*direction} to {@code box}, 0 when the eye is inside it,
     * or -1 when the ray misses the box within {@code reach}. Block occlusion is not considered here.
     */
    static double hitDistance(Vec3d eye, Vec3d direction, Box box, double reach) {
        if (eye == null || direction == null || box == null || reach <= 0.0) return -1.0;
        if (box.contains(eye)) return 0.0;
        Optional<Vec3d> hit = box.raycast(eye, eye.add(direction.multiply(reach)));
        if (hit.isEmpty()) return -1.0;
        double distance = eye.distanceTo(hit.get());
        return distance > reach ? -1.0 : distance;
    }
}
