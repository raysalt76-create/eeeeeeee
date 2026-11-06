package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * A pearl thrown to land on the ground a block or two in front of a moving player: the player a crystal
 * blast has just knocked away. It does not hit the player and does not aim at their hitbox
 * ({@link PearlGrappleSolver} does that); it lands <em>ahead</em> of where they will be.
 *
 * <p>The target's flight is the {@link PlayerMotionSimulation} forecast. For every pearl age the spot
 * {@code distance} blocks ahead of the forecast box, on the ground under it, is a candidate landing; the
 * look that puts the pearl there at that age is the closed-form drag-and-gravity root
 * ({@link PearlGrappleSolver#required}) at the throw speed. Each candidate is then flown through the real
 * world, and a throw counts when the pearl ends on a block top at the age it ends, between
 * {@value #BAND_NEAR} and {@value #BAND_FAR} blocks ahead of the forecast box of that age along its
 * heading and within {@value #BAND_SIDE} blocks to the side. The chance is the launch spread ({@value ThrowPhysics#FINE_COUNT} low-discrepancy
 * points) rolled over that test. The forecast is as good as the position updates the server has sent,
 * so the result is an estimate (UNVERIFIED in-game).</p>
 */
public final class PearlFollowSolver {

    /** A solved throw. */
    public record Shot(float yaw, float pitch, double chance, int hitAge, Vec3d landing) {
    }

    /** What the solver needs about the target: its forecast boxes and how stale they are. */
    public record Forecast(Box[] boxes, int lead) {
    }

    private static final int MIN_AGE = 6;
    public static final int MAX_AGE = 100;
    private static final int SWEEP_SAMPLES = 8;
    private static final int HEADING_TICKS = 8;
    public static final double BAND_NEAR = 0.7;
    public static final double BAND_FAR = 2.7;
    public static final double BAND_SIDE = 1.0;

    private PearlFollowSolver() {
    }

    /**
     * The throw whose pearl most likely ends ahead of the target, preferring the least turn from
     * {@code currentYaw}/{@code currentPitch} among throws of equal chance (within 0.05); null when none.
     */
    public static Shot solve(World world, PlayerEntity shooter, Vec3d spawn, Vec3d movement, boolean onGround,
                             Forecast forecast, double distance, float currentYaw, float currentPitch) {
        Box[] boxes = forecast.boxes();
        Vec3d heading = heading(boxes, forecast.lead(), shooter);
        Vec3d move = onGround ? new Vec3d(movement.x, 0.0, movement.z) : movement;
        Shot best = null;
        double bestTurn = Double.MAX_VALUE;
        for (int age = MIN_AGE; age <= MAX_AGE; ++age) {
            int index = age + forecast.lead();
            if (index >= boxes.length) break;
            Vec3d aim = landingPoint(world, shooter, boxes[index], heading, distance);
            if (aim == null) continue;
            double previous = PearlGrappleSolver.gap(spawn, move, aim, age, 0.0);
            double previousS = 0.0;
            for (int i = 1; i <= SWEEP_SAMPLES; ++i) {
                double s = i / (double) SWEEP_SAMPLES;
                double value = PearlGrappleSolver.gap(spawn, move, aim, age, s);
                if (Math.signum(value) != Math.signum(previous)) {
                    double root = PearlGrappleSolver.bisect(spawn, move, aim, age, previousS, s);
                    Vec3d launch = PearlGrappleSolver.required(spawn, aim, age, root).subtract(move);
                    float[] look = ThrowPhysics.lookAlong(launch, currentYaw);
                    // The mean throw first: only a look whose mean flight is in the band earns the full roll.
                    PearlSimulation.Path mean = PearlSimulation.thrown(world, shooter, look[0], look[1], movement, Vec3d.ZERO)
                            .trace(MAX_AGE);
                    Vec3d meanEnd = landing(mean);
                    if (meanEnd == null || !inBand(forecast, heading, mean.lastAge(), meanEnd)) {
                        previousS = s;
                        previous = value;
                        continue;
                    }
                    Shot shot = evaluate(world, shooter, movement, forecast, heading, look[0], look[1],
                            ThrowPhysics.COARSE_POINTS, ThrowPhysics.COARSE_WEIGHTS);
                    if (shot != null && shot.chance() > 0.0) {
                        double turn = Math.abs(MathHelper.wrapDegrees(look[0] - currentYaw)) + Math.abs(look[1] - currentPitch);
                        if (best == null || shot.chance() > best.chance() + 0.05
                                || (shot.chance() >= best.chance() - 0.05 && turn < bestTurn)) {
                            best = shot;
                            bestTurn = turn;
                        }
                    }
                }
                previousS = s;
                previous = value;
            }
        }
        return best;
    }

    /** The chance and mean landing of throwing with this look, against the same forecast ({@value ThrowPhysics#FINE_COUNT} points). */
    public static Shot evaluate(World world, PlayerEntity shooter, Vec3d movement, Forecast forecast, Vec3d heading,
                                float yaw, float pitch) {
        return evaluate(world, shooter, movement, forecast, heading, yaw, pitch,
                ThrowPhysics.FINE_POINTS, ThrowPhysics.FINE_WEIGHTS);
    }

    private static Shot evaluate(World world, PlayerEntity shooter, Vec3d movement, Forecast forecast, Vec3d heading,
                                 float yaw, float pitch, double[][] points, double[] weights) {
        double total = 0.0;
        for (int i = 0; i < points.length; i++) {
            Vec3d spread = new Vec3d(points[i][0], points[i][1], points[i][2]).multiply(ThrowPhysics.SPREAD);
            PearlSimulation.Path path = PearlSimulation.thrown(world, shooter, yaw, pitch, movement, spread).trace(MAX_AGE);
            Vec3d end = landing(path);
            if (end != null && inBand(forecast, heading, path.lastAge(), end)) total += weights[i];
        }
        PearlSimulation.Path mean = PearlSimulation.thrown(world, shooter, yaw, pitch, movement, Vec3d.ZERO).trace(MAX_AGE);
        Vec3d meanLanding = landing(mean);
        return new Shot(yaw, pitch, total, meanLanding == null ? -1 : mean.lastAge(), meanLanding);
    }

    /** The unit horizontal direction the target is travelling in over the forecast, else from you to it. */
    public static Vec3d heading(Box[] boxes, int lead, PlayerEntity shooter) {
        int from = Math.min(Math.max(0, lead), boxes.length - 1);
        int to = Math.min(from + HEADING_TICKS, boxes.length - 1);
        Vec3d travel = boxes[to].getCenter().subtract(boxes[from].getCenter());
        Vec3d flat = new Vec3d(travel.x, 0.0, travel.z);
        if (flat.lengthSquared() < 0.01) {
            Vec3d away = boxes[from].getCenter().subtract(shooter.getEntityPos());
            flat = new Vec3d(away.x, 0.0, away.z);
        }
        return flat.lengthSquared() < 1.0e-9 ? new Vec3d(0.0, 0.0, 1.0) : flat.normalize();
    }

    /** The ground point {@code distance} ahead of the box, or null where there is no ground within reach below. */
    private static Vec3d landingPoint(World world, PlayerEntity shooter, Box box, Vec3d heading, double distance) {
        Vec3d centre = box.getCenter();
        double x = centre.x + heading.x * distance;
        double z = centre.z + heading.z * distance;
        Vec3d top = new Vec3d(x, box.minY + 2.5, z);
        Vec3d bottom = new Vec3d(x, box.minY - 8.0, z);
        BlockHitResult ground = world.raycast(new RaycastContext(top, bottom, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, shooter));
        if (ground.getType() == HitResult.Type.MISS || ground.getSide() != Direction.UP) return null;
        return new Vec3d(x, ground.getPos().y + 0.05, z);
    }

    private static Vec3d landing(PearlSimulation.Path path) {
        HitResult end = path.end();
        if (!(end instanceof BlockHitResult block) || block.getSide() != Direction.UP) return null;
        return block.getPos();
    }

    /** The landing is ahead of the forecast box of that age, inside the band and not far to the side. */
    private static boolean inBand(Forecast forecast, Vec3d heading, int age, Vec3d landing) {
        int index = Math.min(age + forecast.lead(), forecast.boxes().length - 1);
        Vec3d at = forecast.boxes()[index].getCenter();
        double dx = landing.x - at.x;
        double dz = landing.z - at.z;
        double ahead = dx * heading.x + dz * heading.z;
        double side = Math.abs(dx * heading.z - dz * heading.x);
        return ahead >= BAND_NEAR && ahead <= BAND_FAR && side <= BAND_SIDE;
    }
}
