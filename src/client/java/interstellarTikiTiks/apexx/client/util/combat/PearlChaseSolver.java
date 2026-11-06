package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A counter-pearl that follows an enemy pearl: where the enemy pearl will end ({@link PearlSimulation}
 * from its current state), and the throw whose own flight ends there too.
 *
 * <p>Looks come from the closed-form drag-and-gravity roots ({@link PearlGrappleSolver#required}) that
 * put a 1.5-speed pearl through the landing point on some tick (the flat and the lobbed arc); when the
 * point is out of reach, a pitch sweep at the yaw facing it supplies the throw that ends closest. Each
 * look is flown exactly (blocks and entities when a world is given), and scored by how close its
 * flight, up to where it ends, passes the landing point.</p>
 */
public final class PearlChaseSolver {

    /** A throw: the look, how far (blocks) its flight passes from the landing, its flight ticks and end. */
    public record Shot(float yaw, float pitch, double error, int flightTicks, Vec3d end) {}

    public static final int MAX_FLIGHT = 100;
    private static final int ROOT_SAMPLES = 8;
    private static final double SAME_LOOK = 0.05;
    /** Errors this close count as equal; the smaller turn then wins. */
    private static final double ERROR_TIE = 0.25;
    private static final float SWEEP_MIN = -87.5f;
    private static final float SWEEP_MAX = 87.5f;
    private static final float SWEEP_STEP = 2.5f;
    private static final float SWEEP_FINE = 0.25f;

    private PearlChaseSolver() {}

    /**
     * Where a pearl ends when it keeps flying from {@code position}/{@code velocity} (its current state,
     * server age {@code age}); null when it does not end within {@link #MAX_FLIGHT} ticks.
     */
    public static Vec3d landing(World world, Entity owner, Entity pearl, Vec3d position, Vec3d velocity, int age) {
        PearlSimulation.Path path = PearlSimulation.resume(world, owner, pearl, position, velocity, Math.max(1, age), true)
                .trace(MAX_FLIGHT);
        HitResult end = path.end();
        return end == null ? null : end.getPos();
    }

    /** The throw whose flight ends closest to {@code landing}; ties go to the smaller turn from the hint. Null for none. */
    public static Shot solve(World world, Entity shooter, Vec3d spawn, Vec3d movement, boolean onGround, Vec3d landing,
                             float yawHint, float pitchHint) {
        Vec3d move = onGround ? new Vec3d(movement.x, 0.0, movement.z) : movement;
        List<float[]> looks = new ArrayList<>();
        for (int age = 1; age <= MAX_FLIGHT; ++age) {
            double previousS = 0.0;
            double previous = PearlGrappleSolver.gap(spawn, move, landing, age, 0.0);
            for (int i = 1; i <= ROOT_SAMPLES; ++i) {
                double s = i / (double) ROOT_SAMPLES;
                double value = PearlGrappleSolver.gap(spawn, move, landing, age, s);
                if (Math.signum(value) != Math.signum(previous)) {
                    double root = PearlGrappleSolver.bisect(spawn, move, landing, age, previousS, s);
                    Vec3d launch = PearlGrappleSolver.required(spawn, landing, age, root).subtract(move);
                    if (launch.lengthSquared() > 1.0E-9) add(looks, ThrowPhysics.lookAlong(launch, yawHint));
                }
                previousS = s;
                previous = value;
            }
        }
        if (looks.isEmpty()) {
            // Out of reach of a closed-form throw: the pitch at the facing yaw that ends nearest.
            float yaw = ThrowPhysics.lookAlong(landing.subtract(spawn), yawHint)[0];
            float coarse = 0.0f;
            double coarseError = Double.POSITIVE_INFINITY;
            for (float pitch = SWEEP_MIN; pitch <= SWEEP_MAX + 1.0E-3f; pitch += SWEEP_STEP) {
                double error = evaluate(world, shooter, spawn, movement, onGround, yaw, pitch, landing).error();
                if (error < coarseError) {
                    coarseError = error;
                    coarse = pitch;
                }
            }
            for (float pitch = coarse - SWEEP_STEP; pitch <= coarse + SWEEP_STEP + 1.0E-3f; pitch += SWEEP_FINE) {
                add(looks, new float[]{yaw, MathHelper.clamp(pitch, -90.0f, 90.0f)});
            }
        }
        Shot best = null;
        double bestTurn = Double.MAX_VALUE;
        for (float[] look : looks) {
            Shot shot = evaluate(world, shooter, spawn, movement, onGround, look[0], look[1], landing);
            double turn = Math.abs(MathHelper.wrapDegrees(look[0] - yawHint)) + Math.abs(look[1] - pitchHint);
            if (best == null || shot.error() < best.error() - ERROR_TIE
                    || (shot.error() <= best.error() + ERROR_TIE && turn < bestTurn)) {
                best = shot;
                bestTurn = turn;
            }
        }
        return best;
    }

    /** The exact flight of this packet look (mean throw) and how close it passes the landing before it ends. */
    public static Shot evaluate(World world, Entity shooter, Vec3d spawn, Vec3d movement, boolean onGround,
                                float yaw, float pitch, Vec3d landing) {
        Vec3d velocity = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, null, movement, onGround);
        PearlSimulation.Path path = PearlSimulation.launched(world, shooter, spawn, velocity).trace(MAX_FLIGHT);
        double nearest = Double.POSITIVE_INFINITY;
        Vec3d end = spawn;
        for (PearlSimulation.Segment segment : path.segments()) {
            Vec3d to = segment.position();
            nearest = Math.min(nearest, pointToSegment(landing, segment.from(), to));
            end = to;
        }
        return new Shot(yaw, pitch, nearest, path.lastAge(), end);
    }

    static double pointToSegment(Vec3d p, Vec3d a, Vec3d b) {
        Vec3d ab = b.subtract(a);
        double length = ab.lengthSquared();
        double t = length < 1.0E-12 ? 0.0 : MathHelper.clamp(p.subtract(a).dotProduct(ab) / length, 0.0, 1.0);
        return p.distanceTo(a.add(ab.multiply(t)));
    }

    private static void add(List<float[]> looks, float[] look) {
        look[1] = MathHelper.clamp(look[1], -90.0f, 90.0f);
        for (float[] other : looks) {
            if (Math.abs(MathHelper.wrapDegrees(other[0] - look[0])) < SAME_LOOK && Math.abs(other[1] - look[1]) < SAME_LOOK) return;
        }
        looks.add(look);
    }
}
