package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * Forward model for the elytra wind-charge launch: vanilla 1.21.11 glide and free-fall velocity
 * updates, block collision, the wind-charge blast on the player, and the outcome planner built on
 * them. The glide constants are read from {@code LivingEntity#calcGlidingVelocity} (0.75 lift
 * share, -0.1 sink-to-lift, 0.04 and 3.2 pitch-up conversion, 0.1 heading pull, 0.99/0.98/0.99
 * drag); the blast follows the explosion knockback formula (power 1.2, player multiplier 1.22,
 * direction centre to eye, magnitude from the feet distance and the exposure).
 *
 * <p>STATICALLY VERIFIED against the game bytecode for the glide step only. The blast exposure,
 * the latency between a throw and its knockback, and the collapse-on-ground timing are
 * approximations: UNVERIFIED in-game. The model decides when to throw and which pull-up to fly; it
 * never replaces the live ray, the live slot or the live world.
 */
public final class ElytraGlidePhysics {
    public static final double GRAVITY = 0.08;
    /** Wind charge explosion power (blast radius is twice this: 2.4 blocks). */
    public static final double WIND_POWER = 1.2;
    /** Knockback multiplier of a player-thrown wind charge. */
    public static final double WIND_KNOCKBACK = 1.22;

    private static final double HALF_WIDTH = 0.3;
    private static final double GLIDE_HEIGHT = 0.6;
    private static final double STAND_HEIGHT = 1.8;
    private static final double GLIDE_EYE = 0.4;
    private static final double STAND_EYE = 1.62;
    private static final float RADIANS = 0.017453292f;
    /** Lift the blast centre off the block face so the exposure rays do not start inside it. */
    private static final double CENTRE_LIFT = 0.001;

    private ElytraGlidePhysics() {}

    /** Mutable player state for a rollout. */
    public static final class Body {
        public Vec3d pos;
        public Vec3d prevPos;
        public Vec3d vel;
        public boolean onGround;
        public boolean gliding;
        public float pitch;

        public Body(Vec3d pos, Vec3d prevPos, Vec3d vel, boolean onGround, boolean gliding, float pitch) {
            this.pos = pos;
            this.prevPos = prevPos;
            this.vel = vel;
            this.onGround = onGround;
            this.gliding = gliding;
            this.pitch = pitch;
        }

        public Body copy() {
            return new Body(pos, prevPos, vel, onGround, gliding, pitch);
        }

        public double eyeHeight() {
            return gliding ? GLIDE_EYE : STAND_EYE;
        }

        public Box box() {
            double height = gliding ? GLIDE_HEIGHT : STAND_HEIGHT;
            return new Box(pos.x - HALF_WIDTH, pos.y, pos.z - HALF_WIDTH,
                    pos.x + HALF_WIDTH, pos.y + height, pos.z + HALF_WIDTH);
        }
    }

    /** The live player as a rollout body; {@code pitch} is the look the coming tick will use. */
    public static Body of(ClientPlayerEntity player, float pitch) {
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d prev = new Vec3d(player.lastX, player.lastY, player.lastZ);
        return new Body(pos, prev, player.getVelocity(), player.isOnGround(), player.isGliding(), pitch);
    }

    // ── One tick ────────────────────────────────────────────────────────────

    /** {@code LivingEntity#calcGlidingVelocity}: the glide update, drag included. */
    public static Vec3d glideVelocity(Vec3d velocity, float yaw, float pitch) {
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        float pitchRad = pitch * RADIANS;
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double speedHorizontal = velocity.horizontalLength();
        double lift = MathHelper.square(Math.cos(pitchRad));
        Vec3d v = velocity.add(0.0, GRAVITY * (-1.0 + lift * 0.75), 0.0);
        if (v.y < 0.0 && lookHorizontal > 0.0) {
            double sink = v.y * -0.1 * lift;
            v = v.add(look.x * sink / lookHorizontal, sink, look.z * sink / lookHorizontal);
        }
        if (pitchRad < 0.0f && lookHorizontal > 0.0) {
            double climb = speedHorizontal * (double) (-MathHelper.sin((double) pitchRad)) * 0.04;
            v = v.add(-look.x * climb / lookHorizontal, climb * 3.2, -look.z * climb / lookHorizontal);
        }
        if (lookHorizontal > 0.0) {
            v = v.add((look.x / lookHorizontal * speedHorizontal - v.x) * 0.1, 0.0,
                    (look.z / lookHorizontal * speedHorizontal - v.z) * 0.1);
        }
        return v.multiply(0.99f, 0.98f, 0.99f);
    }

    /**
     * Advances the body one client tick. The look turns toward {@code targetPitch} by at most
     * {@code maxPitchStep} first (the committed step the tick moves with). A body that is airborne
     * at the tick start and wants to glide opens the elytra this tick, as the jump press does;
     * touching ground closes it, as the collapse does.
     */
    public static void step(World world, Body b, float yaw, float targetPitch, float maxPitchStep, boolean wantGlide) {
        b.prevPos = b.pos;
        b.pitch += MathHelper.clamp(targetPitch - b.pitch, -maxPitchStep, maxPitchStep);
        if (wantGlide && !b.gliding && !b.onGround) b.gliding = true;
        float friction = 0.91f;
        if (!b.gliding) {
            float slip = 1.0f;
            if (b.onGround) {
                Box box = b.box();
                slip = world.getBlockState(BlockPos.ofFloored(b.pos.x, box.minY - 0.500001, b.pos.z))
                        .getBlock().getSlipperiness();
            }
            friction = slip * 0.91f;
        }
        Vec3d motion = b.gliding ? glideVelocity(b.vel, yaw, b.pitch) : b.vel;
        Box box = b.box();
        Vec3d moved = collide(world, box, motion);
        boolean landed = motion.y < 0.0 && moved.y != motion.y;
        double vx = moved.x != motion.x ? 0.0 : motion.x;
        double vy = moved.y != motion.y ? 0.0 : motion.y;
        double vz = moved.z != motion.z ? 0.0 : motion.z;
        b.pos = b.pos.add(moved);
        b.onGround = landed;
        if (b.gliding) {
            b.vel = new Vec3d(vx, vy, vz);
            if (b.onGround) b.gliding = false;
        } else {
            b.vel = new Vec3d(vx * friction, (vy - GRAVITY) * 0.98, vz * friction);
        }
    }

    /** Entity#move's per-axis resolution: vertical first, then the larger horizontal axis first. */
    public static Vec3d collide(World world, Box box, Vec3d motion) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : world.getBlockCollisions(null, box.stretch(motion))) shapes.add(shape);
        double dy = motion.y;
        if (dy != 0.0) dy = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, box, shapes, dy);
        Box moved = box.offset(0.0, dy, 0.0);
        double dx = motion.x;
        double dz = motion.z;
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, dz);
            moved = moved.offset(0.0, 0.0, dz);
        }
        if (dx != 0.0) {
            dx = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moved, shapes, dx);
            moved = moved.offset(dx, 0.0, 0.0);
        }
        if (!zFirst && dz != 0.0) {
            dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, dz);
        }
        return new Vec3d(dx, dy, dz);
    }

    /** Free distance below the box before a block, or {@code maxDrop} when nothing is that close. */
    public static double gapBelow(World world, Box box, double maxDrop) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : world.getBlockCollisions(null, box.stretch(0.0, -maxDrop, 0.0))) shapes.add(shape);
        double offset = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, box, shapes, -maxDrop);
        return Math.min(maxDrop, -offset);
    }

    /** Ticks until the body touches ground when it keeps this look, or -1 beyond {@code maxTicks}. */
    public static int ticksToGround(World world, Body start, float yaw, int maxTicks) {
        Body b = start.copy();
        for (int tick = 1; tick <= maxTicks; ++tick) {
            step(world, b, yaw, b.pitch, 0.0f, false);
            if (b.onGround) return tick;
        }
        return -1;
    }
}
