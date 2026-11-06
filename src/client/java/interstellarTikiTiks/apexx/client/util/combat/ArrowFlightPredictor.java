package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.BiFunction;

/** Arrow flight, separate from the render preview and from interaction ray validation. */
public final class ArrowFlightPredictor {
    // PersistentProjectileEntity moves/collides first, then applies drag and gravity.
    public static final double AIR_DRAG = (double) 0.99F;
    public static final double GRAVITY = 0.05D;

    private ArrowFlightPredictor() {
    }

    public static Vec3d advanceVelocity(Vec3d velocity) {
        return velocity.multiply(AIR_DRAG).add(0.0, -GRAVITY, 0.0);
    }

    public static Result predict(World world, Entity owner, Vec3d origin, Vec3d initialVelocity) {
        return predict(world, owner, origin, initialVelocity, 0);
    }

    public static Result predict(World world, Entity owner, Vec3d origin, Vec3d initialVelocity, int initialAge) {
        return predict(world, owner, origin, initialVelocity, initialAge, entity -> false);
    }

    /** Only the intended cart may be ignored when predicting the later cover deadline. */
    public static Result predict(World world, Entity owner, Vec3d origin, Vec3d initialVelocity, int initialAge,
                                 Predicate<Entity> ignoredCollision) {
        return predict(world, owner, origin, initialVelocity, initialAge, ignoredCollision, 0);
    }

    /**
     * {@code history} leading moves are already flown: they replay free flight without block or
     * entity collisions, so blocks placed and entities moved since then cannot rewrite the past.
     */
    public static Result predict(World world, Entity owner, Vec3d origin, Vec3d initialVelocity, int initialAge,
                                 Predicate<Entity> ignoredCollision, int history) {
        ArrowEntity probe = new ArrowEntity(EntityType.ARROW, world);
        probe.setOwner(owner);
        Vec3d position = origin;
        Vec3d velocity = initialVelocity;
        boolean leftOwner = !owner.getBoundingBox().expand(1.0).contains(origin);
        List<Vec3d> path = new ArrayList<>();
        path.add(position);
        boolean raining = world.isRaining();
        for (int tick = 0; tick < 80; tick++) {
            Vec3d end = position.add(velocity);
            if (!world.isChunkLoaded(BlockPos.ofFloored(position))
                    || !world.isChunkLoaded(BlockPos.ofFloored(end))) return null;
            // Each tick starts with isTouchingWaterOrRain -> extinguish, so rain on the arrow box
            // puts the flame out before it can reach the cart.
            if (raining && (world.hasRain(BlockPos.ofFloored(position))
                    || world.hasRain(BlockPos.ofFloored(position.x, position.y + 0.5, position.z)))) return null;
            // Flame cannot be relied on after entering water. Sample the entire swept segment.
            int samples = Math.max(1, (int) Math.ceil(velocity.length() * 8.0));
            for (int sample = 0; sample <= samples; sample++) {
                BlockPos cell = BlockPos.ofFloored(position.lerp(end, (double) sample / samples));
                if (!world.isChunkLoaded(cell) || world.getFluidState(cell).isIn(FluidTags.WATER)) return null;
            }
            probe.setPosition(position);
            probe.setVelocity(velocity);
            probe.age = initialAge + tick;
            leftOwner |= !owner.getBoundingBox().intersects(probe.getBoundingBox().stretch(velocity).expand(1.0));
            if (tick < history) {
                position = end;
                path.add(position);
                velocity = advanceVelocity(velocity);
                continue;
            }
            boolean canHitOwner = leftOwner;
            BlockHitResult blockHit = world.getCollisionsIncludingWorldBorder(new RaycastContext(
                    position, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, probe));
            Vec3d collisionEnd = blockHit.getType() == HitResult.Type.BLOCK ? blockHit.getPos() : end;
            var entityHits = ProjectileUtil.collectPiercingCollisions(world, probe, position, collisionEnd,
                    probe.getBoundingBox().stretch(velocity).expand(1.0),
                    entity -> !ignoredCollision.test(entity) && entity.canBeHitByProjectile()
                            && (canHitOwner || !owner.isConnectedThroughVehicle(entity)), false);
            if (!entityHits.isEmpty()) return null;
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                path.add(blockHit.getPos());
                return new Result(blockHit, List.copyOf(path), initialAge);
            }
            position = end;
            path.add(position);
            velocity = advanceVelocity(velocity);
        }
        return null;
    }

    /** First flight tick that enters the future cart's hitbox; -1 means it would miss the cart. */
    public static int cartInterceptTick(List<Vec3d> path, BlockPos railCell) {
        return cartInterceptTick(path, railCell, EntityType.TNT_MINECART.getWidth(), EntityType.TNT_MINECART.getHeight(), 0);
    }

    static int cartInterceptTick(List<Vec3d> path, BlockPos railCell, double width, double height) {
        return cartInterceptTick(path, railCell, width, height, 0);
    }

    public static int cartInterceptTick(Result flight, BlockPos railCell) {
        return cartInterceptTick(flight.path(), railCell, EntityType.TNT_MINECART.getWidth(),
                EntityType.TNT_MINECART.getHeight(), flight.initialAge());
    }

    /** Vanilla's margin-only hits additionally require visibility to the real entity box. */
    public static int cartInterceptTick(World world, Entity owner, Result flight, BlockPos railCell) {
        return cartInterceptTick(flight.path(), railCell, EntityType.TNT_MINECART.getWidth(),
                EntityType.TNT_MINECART.getHeight(), flight.initialAge(), (contact, center) -> {
                    BlockHitResult hit = world.getCollisionsIncludingWorldBorder(new RaycastContext(
                            contact, center, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, owner));
                    return hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : center;
                });
    }

    static int cartInterceptTick(List<Vec3d> path, BlockPos railCell, double width, double height, int initialAge) {
        return cartInterceptTick(path, railCell, width, height, initialAge, (contact, center) -> center);
    }

    static int cartInterceptTick(List<Vec3d> path, BlockPos railCell, double width, double height, int initialAge,
                                 BiFunction<Vec3d, Vec3d, Vec3d> visibleEnd) {
        double halfWidth = width / 2.0;
        double baseY = railCell.getY() + 0.0625;
        Box cart = new Box(railCell.getX() + 0.5 - halfWidth, baseY,
                railCell.getZ() + 0.5 - halfWidth, railCell.getX() + 0.5 + halfWidth,
                baseY + height, railCell.getZ() + 0.5 + halfWidth);
        for (int index = 1; index < path.size(); index++) {
            Vec3d from = path.get(index - 1);
            Vec3d to = path.get(index);
            // ProjectileUtil.getToleranceMargin grows with projectile age in 1.21.11.
            float margin = Math.max(0.0F, Math.min(0.3F, (initialAge + index - 1 - 2) / 20.0F));
            if (cart.raycast(from, to).isPresent()) return index;
            if (margin <= 0) continue;
            var contact = cart.expand(margin).raycast(from, to);
            if (contact.isPresent() && cart.raycast(contact.get(),
                    visibleEnd.apply(contact.get(), cart.getCenter())).isPresent()) return index;
        }
        return -1;
    }

    public record Result(BlockHitResult landing, List<Vec3d> path, int initialAge) {
    }
}
