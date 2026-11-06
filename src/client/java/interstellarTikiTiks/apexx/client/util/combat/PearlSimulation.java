package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Exact 1.21.11 ender pearl flight, one server tick per {@link #step()}:
 * <ol>
 *   <li>{@code ServerWorld#tickEntity} increments the age before the tick;</li>
 *   <li>{@code ThrownEntity#tick}: gravity 0.03, then drag 0.99f (0.8f while the previous tick
 *   left it touching water);</li>
 *   <li>{@code ProjectileUtil#getCollision}: a world-border-aware COLLIDER ray from the current
 *   position along that velocity, then the closest hittable entity between the position and the
 *   block-limited end, each entity box grown by the age tolerance;</li>
 *   <li>the move to the hit point or position + velocity, then the owner-left and water flags the
 *   next tick reads.</li>
 * </ol>
 * The only unknown is the server's launch spread; simulate the real pearl entity once its spawn
 * packet arrives to remove it. Other entities are taken where they are now.
 */
public final class PearlSimulation {
    public static final double GRAVITY = 0.03;
    public static final float DRAG = 0.99f;
    public static final float WATER_DRAG = 0.8f;
    public static final float WIDTH = 0.25f;
    public static final float HEIGHT = 0.25f;

    /**
     * One server tick: the swept segment from {@code from} to the block-limited {@code sweepEnd},
     * the velocity it moved with, the entity tolerance of that tick and what ended the flight.
     */
    public record Segment(int age, Vec3d from, Vec3d sweepEnd, Vec3d velocity, float tolerance,
                          HitResult hit, boolean leftOwner) {
        public boolean ends() { return hit != null; }
        /** Where the pearl is after this tick. */
        public Vec3d position() { return hit != null ? hit.getPos() : from.add(velocity); }
    }

    /** A traced flight: the state at {@code startAge}, then one segment per later age. */
    public record Path(int startAge, Vec3d start, List<Segment> segments) {
        public Segment at(int age) {
            int index = age - startAge - 1;
            return index >= 0 && index < segments.size() ? segments.get(index) : null;
        }

        public int lastAge() { return startAge + segments.size(); }

        public Vec3d positionAt(int age) {
            if (age == startAge) return start;
            Segment segment = at(age);
            return segment == null ? null : segment.position();
        }

        public boolean leftOwnerAt(int age) {
            Segment segment = at(age);
            return segment != null && segment.leftOwner();
        }

        public HitResult end() {
            return segments.isEmpty() ? null : segments.get(segments.size() - 1).hit();
        }
    }

    private final World world;
    /** When set, block collisions are cast against this view instead of the world (a predicted crater). */
    private BlockView terrain;
    private final Entity owner;
    private final Entity self;
    /** An entity the flight never collides with: a grapple target tested at its forecast box, or the catch's own wind charge. */
    private Entity ignored;
    private final int startAge;
    private final Vec3d start;
    private Vec3d position;
    private Vec3d velocity;
    private int age;
    private boolean touchingWater;
    private boolean leftOwner;
    private boolean done;

    private PearlSimulation(World world, Entity owner, Entity self, Entity ignored, Vec3d position, Vec3d velocity,
                            int age, boolean touchingWater, boolean leftOwner) {
        this.world = world;
        this.owner = owner;
        this.self = self;
        this.ignored = ignored;
        this.startAge = age;
        this.start = position;
        this.position = position;
        this.velocity = velocity;
        this.age = age;
        this.touchingWater = touchingWater;
        this.leftOwner = leftOwner;
    }

    /**
     * A pearl the shooter throws now with this look (the yaw/pitch the use packet will carry), from
     * the shooter's current tick-head state: that is what the server has when it handles the use.
     */
    public static PearlSimulation thrown(World world, PlayerEntity shooter, float yaw, float pitch,
                                         Vec3d shooterMovement, Vec3d spread) {
        return thrownPast(world, shooter, yaw, pitch, shooterMovement, spread, null);
    }

    /**
     * {@link #thrown} for a pearl aimed at a moving entity: the flight never collides with
     * {@code ignored} where it stands now, because the caller tests that entity's box where the
     * server will hold it when the pearl gets there. Every other entity and block still collides.
     */
    public static PearlSimulation thrownPast(World world, PlayerEntity shooter, float yaw, float pitch,
                                             Vec3d shooterMovement, Vec3d spread, Entity ignored) {
        Vec3d velocity = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, spread, shooterMovement, shooter.isOnGround());
        Vec3d spawn = ThrowPhysics.pearlSpawn(shooter.getEntityPos(), shooter.getEyeY() - shooter.getY());
        return new PearlSimulation(world, shooter, null, ignored, spawn, velocity, 0, false, false);
    }

    /**
     * A pearl launched from an explicit spawn with an explicit velocity (see {@link ThrowPhysics}),
     * for a throw planned from a predicted shooter state rather than the shooter's current one.
     */
    public static PearlSimulation launched(World world, Entity owner, Vec3d spawn, Vec3d velocity) {
        return new PearlSimulation(world, owner, null, null, spawn, velocity, 0, false, false);
    }

    /** Continues a real pearl from its current state; {@code age} is its server age for that state. */
    public static PearlSimulation resume(World world, Entity owner, Entity pearl, int age, boolean leftOwner) {
        boolean water = age > 0 && ThrowPhysics.touchingWater(world, box(pearl.getEntityPos()));
        return new PearlSimulation(world, owner, pearl, null, pearl.getEntityPos(), pearl.getVelocity(), age, water, leftOwner);
    }

    /**
     * Continues a pearl from an explicit state at server age {@code age}: its spawn packet's exact
     * position and velocity, before (or without) a client entity. {@code pearl} may be null.
     */
    public static PearlSimulation resume(World world, Entity owner, Entity pearl, Vec3d position, Vec3d velocity,
                                         int age, boolean leftOwner) {
        boolean water = age > 0 && ThrowPhysics.touchingWater(world, box(position));
        return new PearlSimulation(world, owner, pearl, null, position, velocity, age, water, leftOwner);
    }

    /** Pure ballistic state (no world): for tests and planning without collisions. */
    public static PearlSimulation ballistic(Vec3d position, Vec3d velocity, int age) {
        return new PearlSimulation(null, null, null, null, position, velocity, age, false, true);
    }

    /** EntityDimensions#getBoxAt for a 0.25 x 0.25 pearl. */
    public static Box box(Vec3d pos) {
        float half = WIDTH / 2.0f;
        return new Box(pos.x - (double)half, pos.y, pos.z - (double)half,
                pos.x + (double)half, pos.y + (double)HEIGHT, pos.z + (double)half);
    }

    /**
     * Leaves {@code entity} out of the entity sweep. The client copy of the catch's own wind charge sits
     * near, not exactly on, the charge the solve models, so stopping at it would reject real catches.
     */
    public PearlSimulation ignoring(Entity entity) {
        this.ignored = entity;
        return this;
    }

    /**
     * Casts the flight's block collisions against {@code view} (a {@link CraterView} over the world, say)
     * instead of the world itself. Entities and water still come from the world, and the world border is
     * not applied to the view.
     */
    public PearlSimulation withTerrain(BlockView view) {
        this.terrain = view;
        return this;
    }

    public Path trace(int maxTicks) {
        List<Segment> segments = new ArrayList<>(Math.max(0, maxTicks));
        for (int i = 0; i < maxTicks && !done; ++i) {
            Segment segment = step();
            if (segment == null) break;
            segments.add(segment);
        }
        return new Path(startAge, start, List.copyOf(segments));
    }

    /** Advances one server tick; null once the pearl has hit something. */
    public Segment step() {
        if (done) return null;
        ++age;
        velocity = velocity.add(0.0, -GRAVITY, 0.0);
        velocity = velocity.multiply((double)(touchingWater ? WATER_DRAG : DRAG));
        Vec3d from = position;
        Vec3d end = from.add(velocity);
        HitResult hit = null;
        if (world != null || terrain != null) {
            RaycastContext context = new RaycastContext(from, end,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                    self != null ? ShapeContext.of(self) : ShapeContext.absent());
            BlockHitResult block = terrain != null ? terrain.raycast(context)
                    : world.getCollisionsIncludingWorldBorder(context);
            if (block.getType() != HitResult.Type.MISS) {
                end = block.getPos();
                hit = block;
            }
        }
        float tolerance = ThrowPhysics.entityTolerance(age);
        EntityHitResult entity = world == null ? null : entityCollision(from, end, tolerance);
        if (entity != null) hit = entity;
        position = hit != null ? hit.getPos() : from.add(velocity);
        // ProjectileEntity#tick after the move: owner-left check, then baseTick's water state.
        if (!leftOwner) leftOwner = hasLeftOwner();
        touchingWater = ThrowPhysics.touchingWater(world, box(position));
        if (hit != null) done = true;
        return new Segment(age, from, end, velocity, tolerance, hit, leftOwner);
    }

    private EntityHitResult entityCollision(Vec3d from, Vec3d to, float tolerance) {
        Box search = box(from).stretch(velocity).expand(1.0);
        Entity best = null;
        Vec3d bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getOtherEntities(self, search, this::canHit)) {
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

    /** ProjectileEntity#canHit(Entity). */
    private boolean canHit(Entity entity) {
        if (entity == self || entity == ignored || !entity.canBeHitByProjectile()) return false;
        return owner == null || leftOwner || !owner.isConnectedThroughVehicle(entity);
    }

    /** ProjectileEntity#hasLeftOwner with the post-move box. */
    private boolean hasLeftOwner() {
        if (owner == null) return true;
        Box box = box(position).stretch(velocity).expand(1.0);
        return owner.getRootVehicle().streamSelfAndPassengers().filter(EntityPredicates.CAN_HIT)
                .noneMatch(entity -> box.intersects(entity.getBoundingBox()));
    }
}
