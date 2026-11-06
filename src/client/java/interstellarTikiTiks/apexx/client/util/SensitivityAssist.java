package interstellarTikiTiks.apexx.client.util;

/**
 * Input-gain model behind AimAssist's "Sensitivity" mode.
 *
 * <p>It never turns the camera on its own: it only scales the player's mouse delta for the frame,
 * so zero input always produces zero output. Pure math, no Minecraft types, so the shaping can be
 * tested directly.
 * <ul>
 *   <li><b>Deep slowdown near the target.</b> Sensitivity drops smoothly as the crosshair nears the
 *       hitbox and is lowest on it (up to {@link #MAX_SLOWDOWN} at full Slowdown), so the crosshair
 *       settles and stays on the target. The drop grows quadratically across the approach band and is
 *       eased in time, so the sensitivity never steps from one frame to the next.</li>
 *   <li><b>Target-relative.</b> The share of the input that only follows the target's own angular
 *       motion passes through at full gain: tracking a strafing target is never slowed.</li>
 *   <li><b>Approach stays quick.</b> Outside the hitbox, motion toward it is slowed three quarters as
 *       much as motion across or away from it, so the crosshair is caught rather than dragged in.</li>
 *   <li><b>Flicks pass.</b> Only genuinely fast swipes (well above normal aiming speed) fade the
 *       slowdown out, so a deliberate flick away is never held.</li>
 * </ul>
 */
public final class SensitivityAssist {

    /**
     * @param slowdown01 how deep the sensitivity drop on the target is
     * @param zoneDeg    angular distance outside the hitbox edge over which the drop fades in
     */
    public record Config(double slowdown01, double zoneDeg) {}

    /**
     * Frame geometry in camera angle space, degrees.
     *
     * @param errYaw         yaw from the crosshair to the nearest point of the target zone (0 inside)
     * @param errPitch       pitch from the crosshair to the nearest point of the target zone (0 inside)
     * @param radiusDeg      apparent half-size of the target zone
     * @param targetYawVel   target bearing velocity, deg/s
     * @param targetPitchVel target bearing velocity, deg/s
     */
    public record Geometry(double errYaw, double errPitch, double radiusDeg,
                           double targetYawVel, double targetPitchVel) {}

    /** Fraction of sensitivity removed on the target at full Slowdown. */
    static final double MAX_SLOWDOWN = 0.9;
    /** Relative input speed (deg/s) at which the slowdown starts to fade, and where it is gone. */
    private static final double FLICK_START = 650.0;
    private static final double FLICK_END = 1500.0;
    private static final double SPEED_TAU = 0.03;
    /** Time constant of the gain itself: sensitivity eases in and out instead of stepping. */
    private static final double GAIN_TAU = 0.05;
    /** Share of the slowdown applied to motion toward the target while still outside it. */
    private static final double TOWARD_SHARE = 0.75;

    private double filteredSpeed;
    private double gain = 1.0;

    public void reset() {
        filteredSpeed = 0.0;
        gain = 1.0;
    }

    /** Current (eased) sensitivity gain on relative motion, 1 = untouched. */
    public double gain() {
        return gain;
    }

    /**
     * Shapes one frame of mouse input.
     *
     * @param inYaw   the player's yaw input this frame, degrees
     * @param inPitch the player's pitch input this frame, degrees
     * @return {yaw, pitch} to apply instead, degrees
     */
    public double[] shape(double inYaw, double inPitch, Geometry g, Config c, double dtSeconds) {
        double dt = AssistMotion.clamp(dtSeconds, 1.0e-4, 0.1);
        double slowdown = AssistMotion.clamp(c.slowdown01(), 0.0, 1.0) * MAX_SLOWDOWN;
        double zone = Math.max(0.5, c.zoneDeg());
        double dist = Math.hypot(g.errYaw(), g.errPitch());
        double approach = AssistMotion.clamp(1.0 - dist / zone, 0.0, 1.0);
        double near = approach * approach;

        // Split off the part of the input that only follows the target's own motion.
        double wYaw = g.targetYawVel() * dt;
        double wPitch = g.targetPitchVel() * dt;
        double wSq = wYaw * wYaw + wPitch * wPitch;
        double follow = 0.0;
        if (near > 0.0 && wSq > 1.0e-12) {
            follow = AssistMotion.clamp((inYaw * wYaw + inPitch * wPitch) / wSq, 0.0, 1.0);
        }
        double baseYaw = wYaw * follow;
        double basePitch = wPitch * follow;
        double rYaw = inYaw - baseYaw;
        double rPitch = inPitch - basePitch;
        double rMag = Math.hypot(rYaw, rPitch);

        filteredSpeed += (rMag / dt - filteredSpeed) * AssistMotion.alpha(dt, SPEED_TAU);
        double flick = AssistMotion.smoothstep((filteredSpeed - FLICK_START) / (FLICK_END - FLICK_START));
        double targetGain = 1.0 - slowdown * near * (1.0 - flick);
        gain += (targetGain - gain) * AssistMotion.alpha(dt, GAIN_TAU);
        // A flick takes effect at once; only the return to slow aiming is eased.
        if (targetGain > gain && flick > 0.0) {
            gain += (targetGain - gain) * flick;
        }

        if (Math.hypot(inYaw, inPitch) < 1.0e-9) {
            return new double[]{inYaw, inPitch};
        }

        double outYaw;
        double outPitch;
        if (dist <= 1.0e-6) {
            outYaw = rYaw * gain;
            outPitch = rPitch * gain;
        } else {
            double ux = g.errYaw() / dist;
            double uy = g.errPitch() / dist;
            double par = rYaw * ux + rPitch * uy;
            double perpYaw = rYaw - ux * par;
            double perpPitch = rPitch - uy * par;
            double parGain = par > 0.0 ? 1.0 - (1.0 - gain) * TOWARD_SHARE : gain;
            outYaw = ux * par * parGain + perpYaw * gain;
            outPitch = uy * par * parGain + perpPitch * gain;
        }
        return new double[]{baseYaw + outYaw, basePitch + outPitch};
    }
}
