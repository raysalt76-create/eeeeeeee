package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Decides on which tick the elytra has to come off for a mace smash to be possible the moment the
 * target is in reach, and not a tick earlier.
 *
 * <p>The smash needs the wing OFF (the client's gliding flag only clears after the server round
 * trip) and a real fall of at least {@value #MIN_FALL} blocks. Shedding the wing at tick
 * {@code d} therefore makes a hit possible from tick {@code d + clear + fall}, where {@code clear}
 * is the latency until the gliding flag is gone and {@code fall} the ticks of free fall the 1.5
 * blocks take. The planner flies the local player forward with the exact elytra/air physics of
 * {@link PlayerMotionSimulation}, switching to free fall at {@code d + clear}, and reports the first
 * moment the swept eye segment is inside reach of the forecast target box with the smash ready.</p>
 *
 * <p>Rule: drop now when waiting one more tick would delay that moment. While the target is still
 * far the moment is set by the approach and a later drop only speeds it up (more glide), so the
 * module waits; once it is set by {@code d + clear + fall}, every tick of waiting is a tick of delay
 * and the wing comes off.</p>
 *
 * <p>Pure: no client state, all inputs are plain values (unit-testable).</p>
 */
public final class ElytraDropPlanner {

    /** Vanilla entity attack reach, plus a pad so the wing never comes off a hair late. */
    public static final double REACH = 3.0;
    public static final double REACH_PAD = 0.15;
    /** A smash needs a fall strictly greater than this (vanilla {@code MaceItem}). */
    public static final double MIN_FALL = 1.5;
    public static final double FALL_PAD = 0.05;
    /** Gliding slower than this downwards keeps the fall distance reset (it stays at most 1.0). */
    public static final double GLIDE_RESET_VY = -0.5;
    /** Waiting is only judged harmful when it delays the hit by more than this many ticks. */
    public static final double WAIT_TOLERANCE_TICKS = 0.25;
    public static final double NEVER = Double.POSITIVE_INFINITY;

    private static final int SUBSTEPS = 8;

    private ElytraDropPlanner() {
    }

    /**
     * The local player now.
     *
     * @param feet        feet position
     * @param velocity    the velocity the next tick starts from (vanilla {@code getVelocity()})
     * @param gliding     the gliding flag as the client holds it
     * @param fallDistance vanilla fall distance now
     * @param glideEye    eye offset above the feet while gliding
     * @param standEye    eye offset above the feet standing/falling upright
     */
    public record Self(Vec3d feet, Vec3d velocity, boolean gliding, boolean onGround, float yaw, float pitch,
                       double fallDistance, double glideEye, double standEye) {}

    /**
     * Tick (fractional, 0 = now) of the first smash-ready contact when the wing is shed {@code dropIn}
     * ticks from now, or {@link #NEVER}. {@code target[i]} is the target's box {@code i} ticks from
     * now; {@code clearTicks} the ticks from the use until the gliding flag is gone.
     */
    public static double hitTick(PlayerMotionSimulation sim, Self me, Box[] target, int dropIn, int clearTicks,
                                 int horizon) {
        PlayerMotionSimulation.State s = new PlayerMotionSimulation.State(me.feet(), me.velocity(), me.onGround(),
                me.gliding() ? PlayerMotionSimulation.Mode.GLIDING : PlayerMotionSimulation.Mode.AIR);
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(me.yaw(), me.pitch(),
                Vec3d.ZERO, false, 0.0, false, 0);
        double fall = Math.max(0.0, me.fallDistance());
        // Tick index from which the wing is off for the smash: the use tick, plus the flag round trip when gliding.
        int offAt = dropIn + (me.gliding() ? Math.max(0, clearTicks) : 0);
        int last = Math.min(horizon, target.length - 2);
        double reach = REACH + REACH_PAD;
        for (int i = 0; i <= last; i++) {
            boolean gliding = s.mode() == PlayerMotionSimulation.Mode.GLIDING;
            boolean ready = i >= offAt && !gliding && fall >= MIN_FALL + FALL_PAD && s.velocity().y < -0.01;
            PlayerMotionSimulation.State n = sim.step(s, behavior, false);
            if (n.onGround()) {
                return NEVER;
            }
            if (ready) {
                double e0 = s.pos().y + eyeOffset(s, me);
                double e1 = n.pos().y + eyeOffset(n, me);
                for (int k = 0; k <= SUBSTEPS; k++) {
                    double t = k / (double) SUBSTEPS;
                    double ex = s.pos().x + (n.pos().x - s.pos().x) * t;
                    double ey = e0 + (e1 - e0) * t;
                    double ez = s.pos().z + (n.pos().z - s.pos().z) * t;
                    if (distanceToLerpedBox(ex, ey, ez, target[i], target[i + 1], t) <= reach) {
                        return i + t;
                    }
                }
            }
            double dy = n.pos().y - s.pos().y;
            if (gliding && s.velocity().y > GLIDE_RESET_VY) {
                fall = Math.min(fall, 1.0);
            } else if (dy < 0.0) {
                fall -= dy;
            }
            if (n.mode() == PlayerMotionSimulation.Mode.GLIDING && i + 1 >= offAt) {
                n = new PlayerMotionSimulation.State(n.pos(), n.velocity(), false, PlayerMotionSimulation.Mode.AIR);
            }
            s = n;
        }
        return NEVER;
    }

    /** What one forecast says: the hit tick if the wing comes off now, and whether it must. */
    public record Verdict(double hitNow, double hitIfLater, boolean dropNow) {}

    /**
     * Drop now when the smash is possible at all and waiting one more tick would delay it (or lose
     * it). Never when the target is not reached within the horizon.
     */
    public static Verdict judge(PlayerMotionSimulation sim, Self me, Box[] target, int clearTicks, int horizon) {
        double now = hitTick(sim, me, target, 0, clearTicks, horizon);
        if (now == NEVER) {
            return new Verdict(NEVER, NEVER, false);
        }
        double later = hitTick(sim, me, target, 1, clearTicks, horizon);
        return new Verdict(now, later, later == NEVER || later > now + WAIT_TOLERANCE_TICKS);
    }

    /**
     * Boxes of a forecast re-indexed to start now: {@code forecast[sinceSeen + i]}, the last one
     * repeated when the forecast is shorter.
     */
    public static Box[] fromNow(Box[] forecast, int sinceSeen, int length) {
        Box[] out = new Box[Math.max(2, length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = forecast[Math.min(forecast.length - 1, Math.max(0, sinceSeen + i))];
        }
        return out;
    }

    private static double eyeOffset(PlayerMotionSimulation.State state, Self me) {
        return state.mode() == PlayerMotionSimulation.Mode.GLIDING ? me.glideEye() : me.standEye();
    }

    private static double distanceToLerpedBox(double px, double py, double pz, Box a, Box b, double t) {
        double minX = a.minX + (b.minX - a.minX) * t;
        double minY = a.minY + (b.minY - a.minY) * t;
        double minZ = a.minZ + (b.minZ - a.minZ) * t;
        double maxX = a.maxX + (b.maxX - a.maxX) * t;
        double maxY = a.maxY + (b.maxY - a.maxY) * t;
        double maxZ = a.maxZ + (b.maxZ - a.maxZ) * t;
        double dx = px - MathHelper.clamp(px, minX, maxX);
        double dy = py - MathHelper.clamp(py, minY, maxY);
        double dz = pz - MathHelper.clamp(pz, minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
