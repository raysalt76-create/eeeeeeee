package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.AbstractWindChargeEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Exact 1.21.11 wind charge flight, one server tick per {@link #step()}
 * ({@code AbstractWindChargeEntity} / {@code ExplosiveProjectileEntity#tick}): acceleration power
 * 0 and drag 1.0f in and out of water leave the velocity constant; then ProjectileUtil's COLLIDER
 * block ray and hittable-entity sweep (no other wind charge, no end crystal, nothing noclip), then
 * the move. Its hitbox hangs 0.15 below its position. It explodes at whatever it hits, and when it
 * rises 30 blocks above the world's top.
 */
public final class WindChargeSimulation {
    public static final float WIDTH = 0.3125f;
    public static final float HEIGHT = 0.3125f;

    /** One server tick of flight: segment, block-limited end, and what ended it ({@code burst}: above the world). */
    public record Step(int age, Vec3d from, Vec3d sweepEnd, HitResult hit, boolean burst) {
        public boolean ends() { return burst || hit != null; }
    }

    private final World world;
    private final Entity owner;
    private Vec3d position;
    private Vec3d velocity;
    private int age;
    private boolean leftOwner;
    private boolean done;

    public WindChargeSimulation(World world, Entity owner, Vec3d spawn, Vec3d velocity) {
        this.world = world;
        this.owner = owner;
        this.position = spawn;
        this.velocity = velocity;
    }

    /** AbstractWindChargeEntity#calculateDefaultBoundingBox. */
    public static Box box(Vec3d pos) {
        float half = WIDTH / 2.0f;
        double bottom = pos.y - (double)0.15f;
        return new Box(pos.x - (double)half, bottom, pos.z - (double)half,
                pos.x + (double)half, bottom + (double)HEIGHT, pos.z + (double)half);
    }

    /** Offset from a wind charge's position to its hitbox centre. */
    public static Vec3d boxCenterOffset() {
        return new Vec3d(0.0, -(double)0.15f + (double)HEIGHT / 2.0, 0.0);
    }

    public Vec3d position() { return position; }

    public Vec3d velocity() { return velocity; }

    public int age() { return age; }

    public boolean isDone() { return done; }

    /** Runs until it hits something or {@code maxTicks} pass; returns the steps taken. */
    public List<Step> trace(int maxTicks) {
        List<Step> steps = new ArrayList<>(Math.max(0, maxTicks));
        for (int i = 0; i < maxTicks && !done; ++i) {
            Step step = step();
            if (step == null) break;
            steps.add(step);
        }
        return steps;
    }

    /** Advances one server tick; null once the charge has burst. */
    public Step step() {
        if (done) return null;
        ++age;
        if (world != null && position.y >= world.getTopYInclusive() + 31) {
            // blockY > topY + 30: it bursts in place before moving.
            done = true;
            return new Step(age, position, position, null, true);
        }
        velocity = velocity.add(velocity.normalize().multiply(0.0)).multiply((double)1.0f);
        Vec3d from = position;
        Vec3d end = from.add(velocity);
        HitResult hit = null;
        if (world != null) {
            BlockHitResult block = world.getCollisionsIncludingWorldBorder(new RaycastContext(from, end,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
            if (block.getType() != HitResult.Type.MISS) {
                end = block.getPos();
                hit = block;
            }
            EntityHitResult entity = entityCollision(from, end, ThrowPhysics.entityTolerance(age));
            if (entity != null) hit = entity;
        }
        position = hit != null ? hit.getPos() : from.add(velocity);
        if (!leftOwner) leftOwner = hasLeftOwner();
        if (hit != null) done = true;
        return new Step(age, from, end, hit, false);
    }

    private EntityHitResult entityCollision(Vec3d from, Vec3d to, float tolerance) {
        Box search = box(from).stretch(velocity).expand(1.0);
        Entity best = null;
        Vec3d bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getOtherEntities(null, search, this::canHit)) {
            Optional<Vec3d> point = entity.getBoundingBox().expand((double)tolerance).raycast(from, to);
            if (point.isEmpty()) continue;
            double distance = from.squaredDistanceTo(point.get());
            if (distance < bestDistance) {
                best = entity;
                bestPos = point.get();
                bestDistance = distance;
            }
        }
        return best == null ? null : new EntityHitResult(best, bestPos);
    }

    /** AbstractWindChargeEntity / ExplosiveProjectileEntity / ProjectileEntity#canHit(Entity). */
    private boolean canHit(Entity entity) {
        if (entity instanceof AbstractWindChargeEntity || entity.getType() == EntityType.END_CRYSTAL) return false;
        if (!entity.canBeHitByProjectile() || entity.noClip) return false;
        return owner == null || leftOwner || !owner.isConnectedThroughVehicle(entity);
    }

    private boolean hasLeftOwner() {
        if (owner == null) return true;
        Box box = box(position).stretch(velocity).expand(1.0);
        return owner.getRootVehicle().streamSelfAndPassengers().filter(EntityPredicates.CAN_HIT)
                .noneMatch(entity -> box.intersects(entity.getBoundingBox()));
    }
}
