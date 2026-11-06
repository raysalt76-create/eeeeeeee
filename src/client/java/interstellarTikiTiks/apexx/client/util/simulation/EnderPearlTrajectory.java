package interstellarTikiTiks.apexx.client.util.simulation;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts the client-visible path of a vanilla ender pearl, one point per tick
 * ({@code points[i]} is the position after {@code i} flight ticks).
 *
 * <p>The integration mirrors the thrown-entity tick order: gravity, drag, swept
 * block/entity collision, then movement. A launch prediction uses the deterministic
 * centerline of the vanilla launch; the server adds a small random launch spread when it
 * creates the real pearl, so no client-only preview can know that sample before the server
 * sends the entity. {@link #continueFrom} re-simulates from the real pearl's state once it
 * exists, which removes that spread from the remaining flight.</p>
 */
public final class EnderPearlTrajectory {
    public static final double GRAVITY = 0.03D;
    public static final double AIR_DRAG = 0.99D;
    public static final double WATER_DRAG = 0.8D;
    public static final float LAUNCH_SPEED = 1.5F;

    private EnderPearlTrajectory() {
    }

    /** Predicts a throw with an explicit launch orientation (the server-facing angles). */
    public static Result predict(ClientPlayerEntity player, World world, int maxTicks, float pitch, float yaw) {
        if (player == null || world == null || maxTicks <= 0) {
            return Result.empty();
        }

        EnderPearlEntity pearl = new EnderPearlEntity(world, player, Items.ENDER_PEARL.getDefaultStack());
        Vec3d position = new Vec3d(player.getX(), player.getEyeY() - 0.1D, player.getZ());
        pearl.setPosition(position);
        pearl.setVelocity(player, pitch, yaw, 0.0F, LAUNCH_SPEED, 0.0F);

        List<Vec3d> points = new ArrayList<>(maxTicks + 1);
        HitResult hit = simulate(player, world, pearl, position, pearl.getVelocity(), 0, maxTicks, points, true);
        return new Result(List.copyOf(points), hit);
    }

    /**
     * Simulates the flight of a pearl that is already in the air, starting from its observed
     * position and velocity. The returned list holds the positions AFTER each simulated tick
     * (it does not repeat {@code position}).
     */
    public static List<Vec3d> continueFrom(ClientPlayerEntity player, World world, Vec3d position,
                                           Vec3d velocity, int startAge, int maxTicks) {
        if (player == null || world == null || maxTicks <= 0) {
            return List.of();
        }
        EnderPearlEntity pearl = new EnderPearlEntity(world, player, Items.ENDER_PEARL.getDefaultStack());
        pearl.setPosition(position);
        pearl.setVelocity(velocity);

        List<Vec3d> points = new ArrayList<>(maxTicks);
        simulate(player, world, pearl, position, velocity, startAge, maxTicks, points, false);
        return List.copyOf(points);
    }

    private static HitResult simulate(ClientPlayerEntity player, World world, EnderPearlEntity pearl,
                                      Vec3d startPosition, Vec3d startVelocity, int startAge, int maxTicks,
                                      List<Vec3d> points, boolean includeStart) {
        Vec3d position = startPosition;
        Vec3d velocity = startVelocity;
        pearl.setVelocity(velocity);
        HitResult hit = null;

        for (int tick = 0; tick < maxTicks; tick++) {
            if (includeStart) {
                points.add(position);
            }

            // ProjectileUtil uses the projectile age for vanilla's early-flight collision
            // tolerance. The real entity is age 0 on its first tick.
            pearl.age = startAge + tick;
            pearl.setPosition(position);
            pearl.setVelocity(velocity);

            velocity = advanceVelocity(velocity, pearl.isTouchingWater());
            pearl.setVelocity(velocity);

            HitResult collision = ProjectileUtil.getCollision(pearl, entity -> canHit(player, entity));
            if (collision.getType() != HitResult.Type.MISS) {
                points.add(collision.getPos());
                hit = collision;
                break;
            }

            position = position.add(velocity);
            if (!includeStart) {
                points.add(position);
            }
        }

        if (includeStart && hit == null && (points.isEmpty() || !points.get(points.size() - 1).equals(position))) {
            points.add(position);
        }
        return hit;
    }

    /** Applies the vanilla thrown-entity order for one flight tick. */
    public static Vec3d advanceVelocity(Vec3d velocity, boolean touchingWater) {
        double drag = touchingWater ? WATER_DRAG : AIR_DRAG;
        return velocity.add(0.0D, -GRAVITY, 0.0D).multiply(drag);
    }

    private static boolean canHit(Entity owner, Entity entity) {
        return entity != owner
                && entity.canBeHitByProjectile()
                && !owner.isConnectedThroughVehicle(entity);
    }

    public record Result(List<Vec3d> points, HitResult hit) {
        private static Result empty() {
            return new Result(List.of(), null);
        }
    }
}
