package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Sizes the parameters of a {@code RotationRequest} so a turn lands on the target before the strike
 * window closes, wherever the target sits relative to the current view (including 180 degrees behind
 * after a fly-over). It does NOT step rotation: {@code RotationManager} still owns the stepping loop,
 * the ease-in/ease-out envelope and the GCD quantization. This only answers "how fast and how hard
 * must that request pull to arrive in time", from the turn error and the time left.
 *
 * <p>Timing model (fitted to {@code RotationController.stepAxis}, STATICALLY derived, not measured
 * in-game): a turn of {@code E} degrees at a speed ceiling {@code V} lands inside the hitbox in about
 * {@code E / V + SETTLE_SECONDS}. The solver inverts that for {@code V}. The ceiling never drops below
 * the caller's own base speed, so a small or unhurried turn keeps the module's normal feel.
 */
public final class TurnBudgetSolver {

    /** Seconds the controller needs on top of the cruise phase (ease-in plus settling onto the box). */
    private static final double SETTLE_SECONDS = 0.04;
    /** Smallest cruise time the solver will plan for; bounds the speed ceiling together with {@link #MAX_SPEED}. */
    private static final double MIN_CRUISE_SECONDS = 0.03;
    /** One client tick. The strike is armed a tick ahead of contact, so the aim must land that early. */
    private static final double TICK_SECONDS = 0.05;
    /** Smallest total budget; inside reach there is no time to spare. */
    private static final double MIN_BUDGET_SECONDS = 0.06;
    /** Budget reported when no contact is projected (separating or stationary): no deadline. */
    public static final double NO_DEADLINE = 1.0e3;
    /** Hard ceiling on the planned turn rate, degrees per second. */
    public static final double MAX_SPEED = 4500.0;
    /**
     * Ceiling on the response the solver may ask for (rate 24/s of the controller's 5..30). At full
     * response the settle after a large turn drops to ~0 within one tick, which reads as a hard
     * stop (a big per-tick yaw step followed by a near-zero one). 0.76 keeps a decaying tail while
     * still landing 180 degrees in ~100 ms. Chosen against a replica of the controller, not in-game.
     */
    private static final double RESPONSE_CEILING = 0.76;
    /** The pitch axis runs at this fraction of the yaw ceiling inside the controller. */
    private static final double PITCH_AXIS_SCALE = 0.9;

    private TurnBudgetSolver() {
    }

    /** Solver output: the speed ceiling (deg/s) and response (0..1) to put on the rotation request. */
    public record Plan(double speed, double response, double requiredSpeed) {
    }

    /**
     * Seconds until the eye can first strike the target hitbox, less one tick of arming lead.
     *
     * @param eye          eye position now
     * @param eyeVelTick   eye displacement per tick
     * @param targetBox    target hitbox now
     * @param targetVelTick target displacement per tick
     * @param reach        legal strike distance to the hitbox
     */
    public static double strikeBudgetSeconds(Vec3d eye, Vec3d eyeVelTick, Box targetBox,
                                             Vec3d targetVelTick, double reach) {
        Vec3d nearest = new Vec3d(
                MathHelper.clamp(eye.x, targetBox.minX, targetBox.maxX),
                MathHelper.clamp(eye.y, targetBox.minY, targetBox.maxY),
                MathHelper.clamp(eye.z, targetBox.minZ, targetBox.maxZ));
        Vec3d toTarget = nearest.subtract(eye);
        double distance = toTarget.length();
        if (distance <= reach) {
            // Already strikeable: the aim is needed now.
            return MIN_BUDGET_SECONDS;
        }
        double proximityBudget = MIN_BUDGET_SECONDS + Math.max(0.0, distance - reach) * 0.08;
        Vec3d relativeVelPerSecond = targetVelTick.subtract(eyeVelTick).multiply(1.0 / TICK_SECONDS);
        double closing = -toTarget.dotProduct(relativeVelPerSecond) / distance;
        if (closing <= 1.0e-3) {
            return Math.min(proximityBudget, Math.max(0.35, (distance - reach) * 0.15));
        }
        double closingBudget = Math.max(MIN_BUDGET_SECONDS, (distance - reach) / closing - TICK_SECONDS);
        return Math.min(closingBudget, proximityBudget);
    }

    /**
     * @param yawErrorDeg   absolute yaw error to the aim point, 0..180
     * @param pitchErrorDeg absolute pitch error to the aim point
     * @param budgetSeconds time before the strike window, from {@link #strikeBudgetSeconds}
     * @param baseSpeed     the module's normal speed ceiling, deg/s (the floor of the plan)
     * @param baseResponse  the module's normal response, 0..1 (the floor of the plan)
     */
    public static Plan solve(double yawErrorDeg, double pitchErrorDeg, double budgetSeconds,
                             double baseSpeed, double baseResponse) {
        double error = Math.max(Math.abs(yawErrorDeg), Math.abs(pitchErrorDeg) / PITCH_AXIS_SCALE);
        double cruise = Math.max(MIN_CRUISE_SECONDS, budgetSeconds - SETTLE_SECONDS);
        double required = error / cruise;
        double speed = MathHelper.clamp(required, baseSpeed, Math.max(baseSpeed, MAX_SPEED));
        // Response rises continuously with how far the requirement exceeds the base speed, so the
        // pull-in firms up smoothly instead of switching on at a threshold.
        double urgency = baseSpeed <= 0.0 ? 1.0 : MathHelper.clamp((required - baseSpeed) / baseSpeed, 0.0, 1.0);
        double response = MathHelper.lerp(urgency, baseResponse, 1.0);
        return new Plan(speed, Math.max(baseResponse, Math.min(RESPONSE_CEILING, response)), required);
    }
}
