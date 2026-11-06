package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Exact 1.21.11 throw math shared by thrown items. Every constant and every float/double
 * conversion mirrors the vanilla bytecode, so a launch computed here matches the server's bit for
 * bit apart from the random spread, which the server rolls and the client cannot know:
 * <ul>
 *   <li>look: the server applies an item-use packet's yaw/pitch through {@code wrapDegrees} and
 *   {@code Entity#setAngles} before the item is used;</li>
 *   <li>direction: {@code ProjectileEntity#setVelocity(Entity, pitch, yaw, 0, power, divergence)}
 *   with {@link MathHelper}'s table sin/cos on float angles;</li>
 *   <li>velocity: normalized direction (+ spread) times power, plus the shooter's
 *   {@code getMovement()} with its vertical part dropped while the shooter is on ground.</li>
 * </ul>
 */
public final class ThrowPhysics {
    /** EnderPearlItem.POWER and WindChargeItem.POWER. */
    public static final float PEARL_POWER = 1.5f;
    public static final float WIND_CHARGE_POWER = 1.5f;
    /** Both items throw with divergence 1.0: each axis gets nextTriangular(0, SPREAD) before scaling. */
    public static final double SPREAD = 0.0172275;
    /**
     * Three-point Gauss quadrature of the launch spread's triangular roll, in units of {@link #SPREAD}
     * (density {@code 1 - |x|} on [-1, 1]): nodes 0 and +-sqrt(0.4), weights 7/12 and 5/24. It matches
     * the distribution's mean, variance (1/6) and fourth moment (1/15) exactly.
     */
    public static final double[] SPREAD_NODES = {-Math.sqrt(0.4), 0.0, Math.sqrt(0.4)};
    public static final double[] SPREAD_WEIGHTS = {5.0 / 24.0, 7.0 / 12.0, 5.0 / 24.0};
    /**
     * The 27 grid combinations of {@link #SPREAD_NODES}, as unit spread offsets (to be multiplied by
     * {@link #SPREAD}) with their weights. Cheap, but a coarse step function of a hit region: measured
     * against a Monte Carlo in the tests it is off by about 0.12 where the spread straddles the region.
     */
    public static final double[][] COARSE_POINTS;
    public static final double[] COARSE_WEIGHTS;
    /**
     * {@value #FINE_COUNT} low-discrepancy (Halton, bases 2, 3, 5) points mapped through the quantile
     * function of the triangular roll, equal weights: the rule throws are gated on.
     */
    public static final int FINE_COUNT = 128;
    public static final double[][] FINE_POINTS;
    public static final double[] FINE_WEIGHTS;
    /**
     * {@value #JOINT_COUNT} low-discrepancy points (Halton, bases 2 to 13) of two independent triangular
     * rolls at once, six axes: the first three a pearl's spread, the last three a wind charge's. For a
     * catch whose two launches are both still unknown. Equal weights.
     */
    public static final int JOINT_COUNT = 96;
    /** Leading Halton points skipped: the larger bases start in lockstep. */
    private static final int JOINT_SKIP = 20;
    public static final double[][] JOINT_POINTS;
    public static final double[] JOINT_WEIGHTS;

    static {
        COARSE_POINTS = new double[27][];
        COARSE_WEIGHTS = new double[27];
        int n = 0;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    COARSE_POINTS[n] = new double[]{SPREAD_NODES[x], SPREAD_NODES[y], SPREAD_NODES[z]};
                    COARSE_WEIGHTS[n++] = SPREAD_WEIGHTS[x] * SPREAD_WEIGHTS[y] * SPREAD_WEIGHTS[z];
                }
            }
        }
        FINE_POINTS = new double[FINE_COUNT][];
        FINE_WEIGHTS = new double[FINE_COUNT];
        for (int i = 0; i < FINE_COUNT; i++) {
            FINE_POINTS[i] = new double[]{
                    triangularQuantile(halton(i + 1, 2)), triangularQuantile(halton(i + 1, 3)),
                    triangularQuantile(halton(i + 1, 5))};
            FINE_WEIGHTS[i] = 1.0 / FINE_COUNT;
        }
        int[] bases = {2, 3, 5, 7, 11, 13};
        JOINT_POINTS = new double[JOINT_COUNT][];
        JOINT_WEIGHTS = new double[JOINT_COUNT];
        for (int i = 0; i < JOINT_COUNT; i++) {
            double[] point = new double[bases.length];
            // Skipping the first points of the sequence avoids the lockstep start of the larger bases.
            for (int d = 0; d < bases.length; d++) point[d] = triangularQuantile(halton(i + 1 + JOINT_SKIP, bases[d]));
            JOINT_POINTS[i] = point;
            JOINT_WEIGHTS[i] = 1.0 / JOINT_COUNT;
        }
    }

    private static double halton(int index, int base) {
        double result = 0.0;
        double fraction = 1.0 / base;
        for (int i = index; i > 0; i /= base) {
            result += (i % base) * fraction;
            fraction /= base;
        }
        return result;
    }

    /** Inverse CDF of the triangular density {@code 1 - |x|} on [-1, 1]. */
    static double triangularQuantile(double p) {
        return p < 0.5 ? -1.0 + Math.sqrt(2.0 * p) : 1.0 - Math.sqrt(2.0 * (1.0 - p));
    }

    private static final float RADIANS = 0.017453292f;
    /** Horizontal share below which a direction counts as straight up or down. */
    private static final double VERTICAL_EPSILON = 1.0E-6;

    private ThrowPhysics() {}

    /** Yaw the server uses for a thrown item: wrapDegrees, then setAngles' {@code % 360}. */
    public static float serverYaw(float packetYaw) {
        return MathHelper.wrapDegrees(packetYaw) % 360.0f;
    }

    /** Pitch the server uses for a thrown item: wrapDegrees, setAngles' clamp, then setPitch's clamp. */
    public static float serverPitch(float packetPitch) {
        float angle = MathHelper.clamp(MathHelper.wrapDegrees(packetPitch), -90.0f, 90.0f) % 360.0f;
        return Math.clamp(angle % 360.0f, -90.0f, 90.0f);
    }

    /** Launch direction before normalizing, exactly as setVelocity(shooter, pitch, yaw, 0, ...) builds it. */
    public static Vec3d rawDirection(float yaw, float pitch) {
        float x = -MathHelper.sin((double)(yaw * RADIANS)) * MathHelper.cos((double)(pitch * RADIANS));
        float y = -MathHelper.sin((double)((pitch + 0.0f) * RADIANS));
        float z = MathHelper.cos((double)(yaw * RADIANS)) * MathHelper.cos((double)(pitch * RADIANS));
        return new Vec3d(x, y, z);
    }

    /**
     * Velocity the server gives a projectile thrown with this (already server-applied) look.
     * {@code spread} is the per-axis triangular roll (null or zero for the mean throw).
     */
    public static Vec3d launchVelocity(float yaw, float pitch, float power, Vec3d spread,
                                       Vec3d shooterMovement, boolean shooterOnGround) {
        Vec3d velocity = rawDirection(yaw, pitch).normalize();
        if (spread != null) velocity = velocity.add(spread.x, spread.y, spread.z);
        velocity = velocity.multiply((double)power);
        return velocity.add(shooterMovement.x, shooterOnGround ? 0.0 : shooterMovement.y, shooterMovement.z);
    }

    /** ThrownItemEntity(owner): x, eyeY - 0.1f, z. */
    public static Vec3d pearlSpawn(Vec3d feet, double eyeHeight) {
        return new Vec3d(feet.x, feet.y + eyeHeight - (double)0.1f, feet.z);
    }

    /** WindChargeItem's creator: x, eyePos.y, z. */
    public static Vec3d windChargeSpawn(Vec3d feet, double eyeHeight) {
        return new Vec3d(feet.x, feet.y + eyeHeight, feet.z);
    }

    /**
     * What {@code ServerPlayerEntity#getMovement()} reports for the local player: the delta of its
     * last sent move. Valid at tick head, before this tick's entity movement.
     */
    public static Vec3d localShooterMovement(ClientPlayerEntity player) {
        return new Vec3d(player.getX() - player.lastX, player.getY() - player.lastY, player.getZ() - player.lastZ);
    }

    /** ProjectileUtil#getToleranceMargin for a projectile of this age (float math as vanilla). */
    public static float entityTolerance(int age) {
        return Math.max(0.0f, Math.min(0.3f, (float)(age - 2) / 20.0f));
    }

    /** Yaw/pitch whose launch direction points along {@code direction} (inverse of {@link #rawDirection}). */
    public static float[] lookAlong(Vec3d direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float)Math.toDegrees(Math.atan2(-direction.y, horizontal));
        return new float[]{yaw, pitch};
    }

    /**
     * {@link #lookAlong(Vec3d)} for a direction that may be (numerically) straight up or down: there
     * the yaw does not change the throw, so it keeps {@code yawHint} instead of an arbitrary angle
     * read from rounding noise.
     */
    public static float[] lookAlong(Vec3d direction, float yawHint) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal <= VERTICAL_EPSILON * direction.length()) {
            return new float[]{yawHint, direction.y >= 0.0 ? -90.0f : 90.0f};
        }
        float[] look = lookAlong(direction);
        look[0] = yawHint + MathHelper.wrapDegrees(look[0] - yawHint);
        return look;
    }

    /**
     * The look with this yaw whose direction is closest to {@code direction}: only the pitch turns.
     * When the direction leans behind the yaw, the closest such look is straight up or down.
     */
    public static float[] closestLookWithYaw(Vec3d direction, float yaw) {
        double forward = -MathHelper.sin((double)(yaw * RADIANS)) * direction.x
                + MathHelper.cos((double)(yaw * RADIANS)) * direction.z;
        float pitch = (float)Math.toDegrees(Math.atan2(-direction.y, Math.max(0.0, forward)));
        return new float[]{yaw, pitch};
    }

    /** Angle in degrees between the launch directions of two looks. */
    public static double lookAngle(float yawA, float pitchA, float yawB, float pitchB) {
        Vec3d a = rawDirection(yawA, pitchA).normalize();
        Vec3d b = rawDirection(yawB, pitchB).normalize();
        return Math.toDegrees(Math.acos(MathHelper.clamp(a.dotProduct(b), -1.0, 1.0)));
    }

    /** Entity#updateMovementInFluid(WATER) for a hitbox: any water surface at or above the contracted box floor. */
    public static boolean touchingWater(World world, Box hitbox) {
        if (world == null) return false;
        Box box = hitbox.contract(0.001);
        int minX = MathHelper.floor(box.minX);
        int maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY);
        int maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ);
        int maxZ = MathHelper.ceil(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = minX; x < maxX; ++x) {
            for (int y = minY; y < maxY; ++y) {
                for (int z = minZ; z < maxZ; ++z) {
                    pos.set(x, y, z);
                    FluidState fluid = world.getFluidState(pos);
                    if (fluid.isIn(FluidTags.WATER) && y + fluid.getHeight(world, pos) >= box.minY) return true;
                }
            }
        }
        return false;
    }
}
