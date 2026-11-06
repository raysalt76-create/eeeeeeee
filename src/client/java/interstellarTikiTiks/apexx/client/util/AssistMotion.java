package interstellarTikiTiks.apexx.client.util;

/**
 * Camera controller behind {@link RotationManager.RotationMode#ASSIST} (AimAssist "Normal").
 *
 * <p>Pure math, no Minecraft types: {@link RotationManager} converts the target's aim zone into
 * angles, feeds the player's own mouse delta for the frame, and adds the returned delta to the
 * camera. The controller is built around four rules:
 * <ul>
 *   <li><b>Aim at a zone, not a point.</b> The target is an angular rectangle. Inside it the
 *       closure error is zero, so the assist only carries the crosshair along with the target
 *       and never drags it to a fixed centre the player didn't choose.</li>
 *   <li><b>Fill the gap.</b> The controller computes a desired camera velocity
 *       (closure plus target-motion feed-forward), then credits whatever part of it the player's
 *       own mouse is already supplying. Flicking onto the target yourself therefore adds no
 *       assist on top, which is what removes overshoot. Optional pullaway resistance counters
 *       outward hand motion near the zone through the same continuous velocity filter.</li>
 *   <li><b>Continuous everything.</b> The assist velocity runs through a two-pole (critically
 *       damped) filter and the engagement level ramps through a smoothstep, so position, velocity
 *       and the start/stop of help are all continuous.</li>
 *   <li><b>Wind, then a clean landing.</b> With {@code wind01 > 0} the approach bends like a hand
 *       sweep (WindMouse): a gust velocity (an Ornstein-Uhlenbeck process, so it wanders smoothly
 *       instead of jittering) plus an arc toward one side picked per approach, both mostly across
 *       the closure direction and scaled by the closure speed, so they curve the path rather than
 *       slow it. Both fade out over the last few degrees, so the crosshair still lands precisely
 *       and never wobbles on the target. With {@code wind01 = 0} the controller is deterministic.</li>
 *   <li><b>Detach on intent.</b> Motion relative to the target that moves the crosshair away from
 *       it accumulates as evidence (degrees of deliberate pull). Evidence softly yields the assist
 *       first and fully detaches it past a threshold; a single fast swipe away detaches at once.
 *       It re-engages when the player swings back toward the target, lands on it, or rests near
 *       where they let go.</li>
 * </ul>
 */
public final class AssistMotion {

    /** Operator settings, all 0..1 except {@code reengageMs}. */
    public record Config(double strength01, double smoothness01, double tracking01,
                         boolean detach, double detachSensitivity01, double reengageMs,
                         boolean hold, double wind01, double pullawayStrength01) {
        public static final Config DEFAULT = new Config(0.55, 0.6, 0.85, true, 0.55, 300.0, true);

        /** Existing callers retain the original pullaway behavior. */
        public Config(double strength01, double smoothness01, double tracking01,
                      boolean detach, double detachSensitivity01, double reengageMs, boolean hold, double wind01) {
            this(strength01, smoothness01, tracking01, detach, detachSensitivity01, reengageMs, hold, wind01, 0.0);
        }

        /** No wind: the deterministic controller. */
        public Config(double strength01, double smoothness01, double tracking01,
                      boolean detach, double detachSensitivity01, double reengageMs, boolean hold) {
            this(strength01, smoothness01, tracking01, detach, detachSensitivity01, reengageMs, hold, 0.0);
        }
    }

    /**
     * The target's aim zone in camera angle space, degrees. {@code pitchMin} is the top edge
     * (Minecraft pitch is negative upward).
     */
    public record Zone(double yaw, double yawHalf, double pitchMin, double pitchMax) {
        public double centerPitch() {
            return (pitchMin + pitchMax) * 0.5;
        }

        public double radius() {
            return Math.max(yawHalf, (pitchMax - pitchMin) * 0.5);
        }

        /** Yaw/pitch error from the camera to the nearest point of the zone; zero when inside. */
        public double[] nearestError(double camYaw, double camPitch) {
            double relYaw = wrapDegrees(camYaw - yaw);
            double nearYaw = clamp(relYaw, -yawHalf, yawHalf);
            double nearPitch = clamp(camPitch, pitchMin, pitchMax);
            return new double[]{nearYaw - relYaw, nearPitch - camPitch};
        }
    }

    /** Camera delta for this frame plus diagnostic state. */
    public record Output(double yaw, double pitch, double engagement, boolean detached) {}

    /** Target bearing faster than this (deg/s) is a teleport, respawn or reacquire, not motion. */
    private static final double MAX_TARGET_RATE = 900.0;
    private static final double TARGET_VEL_TAU = 0.025;
    private static final double USER_VEL_TAU = 0.035;
    private static final double EVIDENCE_DECAY_TAU = 0.18;
    private static final double DISENGAGE_TAU = 0.05;
    /** After a detach the remaining help eases out over about this long instead of being cut. */
    private static final double DETACH_RELEASE_TAU = 0.06;
    /** How fast the pull toward the target drains while the hand is moving away from it. */
    private static final double PULL_DRAIN_TAU = 0.03;
    /** Below this the player's hand is considered at rest for re-engagement, deg/s. */
    private static final double IDLE_SPEED = 15.0;
    /** Closure velocity drains this fast once the crosshair is inside the zone. */
    private static final double INSIDE_DRAIN_TAU = 0.015;

    /** Gust memory: how long a gust keeps its direction (OU mean reversion), seconds. */
    private static final double WIND_TAU = 0.14;
    /** Gust size relative to the closure speed at full Wind. */
    private static final double WIND_GUST = 0.55;
    /** Arc size (sideways share of the closure speed) at full Wind. */
    private static final double WIND_ARC = 0.32;
    /** Share of the gust kept along the closure axis (speed variation); the rest bends the path. */
    private static final double WIND_ALONG = 0.3;
    /** Wind is full beyond this error and fades to nothing at {@link #WIND_LAND_DEG}, degrees. */
    private static final double WIND_FULL_DEG = 9.0;
    private static final double WIND_LAND_DEG = 1.2;

    private boolean init;
    private double lastCenterYaw;
    private double lastCenterPitch;
    private double tv1Yaw;
    private double tv1Pitch;
    private double targetVelYaw;
    private double targetVelPitch;

    private double userVelYaw;
    private double userVelPitch;

    // Two-pole filter on the closure part of the assist velocity (deg/s). Feed-forward of the
    // target's motion is already smooth and bypasses it, so tracking does not lag twice.
    private double v1Yaw;
    private double v1Pitch;
    private double velYaw;
    private double velPitch;

    private double engageRaw;
    private double engagement;

    private double evidence;
    private boolean detached;
    private double detachDistance;
    private double idleSeconds;

    // Wind: gust state in a frame aligned with the closure direction (along, across), unit-free.
    private final java.util.SplittableRandom windRandom = new java.util.SplittableRandom();
    private double gustAlong;
    private double gustAcross;
    /** Side of the current approach's arc (+1 / -1), or 0 while on target (re-picked per approach). */
    private double arcSide;

    /** Full reset: the next update starts from rest with no engagement. */
    public void reset() {
        retarget();
        userVelYaw = 0.0;
        userVelPitch = 0.0;
        v1Yaw = 0.0;
        v1Pitch = 0.0;
        velYaw = 0.0;
        velPitch = 0.0;
        engagement = 0.0;
        gustAlong = 0.0;
        gustAcross = 0.0;
        arcSide = 0.0;
    }

    /**
     * New target on the same channel. Target estimates, engagement and detach state restart,
     * while the assist velocity is kept so it decays through the filter instead of stopping dead.
     */
    public void retarget() {
        init = false;
        tv1Yaw = 0.0;
        tv1Pitch = 0.0;
        targetVelYaw = 0.0;
        targetVelPitch = 0.0;
        engageRaw = 0.0;
        evidence = 0.0;
        detached = false;
        detachDistance = 0.0;
        idleSeconds = 0.0;
    }

    public double engagement() {
        return engagement;
    }

    public boolean isDetached() {
        return detached;
    }

    /**
     * Advances one render frame.
     *
     * @param camYaw    current camera yaw, already including this frame's mouse movement
     * @param camPitch  current camera pitch
     * @param zone      target aim zone
     * @param userYaw   the player's own yaw movement this frame, degrees
     * @param userPitch the player's own pitch movement this frame, degrees
     * @param dtSeconds frame time
     */
    public Output update(double camYaw, double camPitch, Zone zone,
                         double userYaw, double userPitch, double dtSeconds, Config config) {
        double dt = clamp(dtSeconds, 1.0e-4, 0.1);
        Config c = config == null ? Config.DEFAULT : config;
        double strength = clamp(c.strength01(), 0.0, 1.0);
        double smooth = clamp(c.smoothness01(), 0.0, 1.0);

        // ── Target bearing velocity (includes the player's own translation, which is wanted).
        double centerPitch = zone.centerPitch();
        if (!init) {
            lastCenterYaw = zone.yaw();
            lastCenterPitch = centerPitch;
            tv1Yaw = 0.0;
            tv1Pitch = 0.0;
            targetVelYaw = 0.0;
            targetVelPitch = 0.0;
            init = true;
        } else {
            double rawYaw = wrapDegrees(zone.yaw() - lastCenterYaw) / dt;
            double rawPitch = (centerPitch - lastCenterPitch) / dt;
            lastCenterYaw = zone.yaw();
            lastCenterPitch = centerPitch;
            if (Math.hypot(rawYaw, rawPitch) > MAX_TARGET_RATE) {
                tv1Yaw = 0.0;
                tv1Pitch = 0.0;
                targetVelYaw = 0.0;
                targetVelPitch = 0.0;
            } else {
                double a = alpha(dt, TARGET_VEL_TAU);
                tv1Yaw += (rawYaw - tv1Yaw) * a;
                tv1Pitch += (rawPitch - tv1Pitch) * a;
                targetVelYaw += (tv1Yaw - targetVelYaw) * a;
                targetVelPitch += (tv1Pitch - targetVelPitch) * a;
            }
        }

        // ── Player hand velocity.
        double au = alpha(dt, USER_VEL_TAU);
        userVelYaw += (userYaw / dt - userVelYaw) * au;
        userVelPitch += (userPitch / dt - userVelPitch) * au;

        // ── Geometry.
        double[] err = zone.nearestError(camYaw, camPitch);
        double errYaw = err[0];
        double errPitch = err[1];
        double errMag = Math.hypot(errYaw, errPitch);
        double cYaw = wrapDegrees(zone.yaw() - camYaw);
        double cPitch = centerPitch - camPitch;
        double centerDist = Math.hypot(cYaw, cPitch);
        double radius = zone.radius();

        // ── Intent: how fast the HAND moves the crosshair away from the target.
        // Two readings must both agree. The absolute one (hand velocity) ignores a target that
        // simply runs away from an idle crosshair; the target-relative one ignores a hand that
        // is following a strafing target. Taking the smaller rejects both false pulls.
        double relYaw = userVelYaw - targetVelYaw;
        double relPitch = userVelPitch - targetVelPitch;
        double away;
        double toward;
        if (centerDist > 0.35) {
            double awayHand = -(userVelYaw * cYaw + userVelPitch * cPitch) / centerDist;
            double awayRelative = -(relYaw * cYaw + relPitch * cPitch) / centerDist;
            away = Math.min(awayHand, awayRelative);
            toward = -awayHand;
        } else {
            // Dead centre has no "away" direction; any sizeable motion of the hand relative to
            // the target leaves it.
            away = Math.min(Math.hypot(relYaw, relPitch), Math.hypot(userVelYaw, userVelPitch)) * 0.6;
            toward = 0.0;
        }

        double s = clamp(c.detachSensitivity01(), 0.0, 1.0);
        double resistance = clamp(c.pullawayStrength01(), 0.0, 1.0);
        double deadSpeed = lerp(60.0, 22.0, s) * lerp(1.0, 4.0, resistance);
        double travelToDetach = lerp(10.0, 2.5, s) * lerp(1.0, 6.0, resistance);
        double flickSpeed = lerp(650.0, 200.0, s) * lerp(1.0, 4.0, resistance);

        double yieldScale = 1.0;
        if (!c.detach()) {
            detached = false;
            evidence = 0.0;
        } else if (!detached) {
            if (away > deadSpeed) {
                evidence += (away - deadSpeed * 0.5) * dt;
            } else {
                evidence *= Math.exp(-dt / EVIDENCE_DECAY_TAU);
            }
            if (evidence >= travelToDetach || away > flickSpeed) {
                detached = true;
                detachDistance = centerDist;
                idleSeconds = 0.0;
                evidence = 0.0;
                // No new pull from here on; what is left of the assist velocity eases out (see the
                // release below) instead of stopping dead, so letting go has no step.
                engageRaw = 0.0;
            } else {
                // Yield smoothly with the evidence: the help is all but gone by the detach point.
                yieldScale = 1.0 - smoothstep(Math.pow(evidence / travelToDetach, 1.0 + 3.0 * resistance));
                if (away > deadSpeed) {
                    // While the hand is pulling away, the pull already built up toward the target
                    // drains fast at zero resistance; stronger locks retain their correction.
                    double drain = Math.exp(-dt * (1.0 - resistance) / PULL_DRAIN_TAU);
                    v1Yaw *= drain;
                    v1Pitch *= drain;
                    velYaw *= drain;
                    velPitch *= drain;
                }
            }
        } else {
            double handSpeed = Math.hypot(userVelYaw, userVelPitch);
            idleSeconds = handSpeed < IDLE_SPEED ? idleSeconds + dt : 0.0;
            boolean returning = toward > deadSpeed && centerDist < Math.max(detachDistance, radius * 3.0 + 6.0);
            boolean onTarget = centerDist <= radius + 1.0;
            boolean settled = idleSeconds * 1000.0 >= Math.max(0.0, c.reengageMs())
                    && centerDist <= detachDistance + 4.0;
            if (returning || onTarget || settled) {
                detached = false;
                evidence = 0.0;
                // Re-enter through the ramp so help fades back in rather than resuming at full.
                engageRaw = Math.min(engageRaw, 0.25);
            }
        }

        // ── Engagement envelope: linear ramp shaped by a smoothstep, exponential release.
        double engageTarget = (c.hold() && !detached) ? yieldScale : 0.0;
        if (engageTarget > engageRaw) {
            double riseSeconds = lerp(0.15, 0.45, smooth);
            engageRaw = Math.min(engageTarget, engageRaw + dt / riseSeconds);
        } else {
            engageRaw = engageTarget + (engageRaw - engageTarget) * Math.exp(-dt / DISENGAGE_TAU);
        }
        if (detached) {
            // Smooth release: what is left of the closure velocity decays instead of being cut.
            double release = Math.exp(-dt / DETACH_RELEASE_TAU);
            v1Yaw *= release;
            v1Pitch *= release;
            velYaw *= release;
            velPitch *= release;
        }
        engagement = smoothstep(engageRaw);

        // ── Desired camera velocity: closure toward the zone + target feed-forward.
        // Closure is linear (error / tau) for small errors and saturates smoothly at maxSpeed.
        double maxSpeed = lerp(90.0, 900.0, Math.pow(strength, 0.9));
        double tau = lerp(0.24, 0.06, strength) * lerp(0.7, 1.4, smooth);
        double closeSpeed = errMag > 1.0e-6 ? maxSpeed * Math.tanh(errMag / (maxSpeed * tau)) : 0.0;
        double tracking = clamp(c.tracking01(), 0.0, 1.0);
        double closeYaw = errMag > 1.0e-6 ? errYaw / errMag * closeSpeed : 0.0;
        double closePitch = errMag > 1.0e-6 ? errPitch / errMag * closeSpeed : 0.0;
        double ffYaw = targetVelYaw * tracking;
        double ffPitch = targetVelPitch * tracking;

        // Resist only the hand's outward motion, never a target moving away from an idle hand.
        // Feeding this into the closure filter keeps both acquisition and release continuous.
        if (resistance > 0.0 && !detached && centerDist > 1.0e-6) {
            double ux = cYaw / centerDist;
            double uy = cPitch / centerDist;
            double handAway = -(userYaw * ux + userPitch * uy) / dt;
            double relativeAway = handAway + targetVelYaw * ux + targetVelPitch * uy;
            double near = smoothstep(1.0 - errMag / Math.max(8.0, radius * 2.0));
            double counter = Math.min(maxSpeed, Math.max(0.0, Math.min(handAway, relativeAway)))
                    * resistance * 0.98 * near;
            closeYaw += ux * counter;
            closePitch += uy * counter;
        }

        // Wind: bend the closure into a curved, human sweep, fading out before the landing.
        double wind = clamp(c.wind01(), 0.0, 1.0);
        if (wind > 0.0 && errMag > 1.0e-6) {
            double[] w = windVelocity(errYaw / errMag, errPitch / errMag, errMag, closeSpeed, wind, dt);
            closeYaw += w[0];
            closePitch += w[1];
        } else {
            settleWind(dt);
        }

        // Credit the part the hand is already supplying, so the assist only fills the gap.
        double wantYaw = closeYaw + ffYaw;
        double wantPitch = closePitch + ffPitch;
        double wantMag = Math.hypot(wantYaw, wantPitch);
        double share = 0.0;
        if (wantMag > 1.0e-6) {
            double handAlong = clamp((userVelYaw * wantYaw + userVelPitch * wantPitch) / wantMag, 0.0, wantMag);
            share = (wantMag - handAlong) / wantMag * engagement;
        }

        // ── Two-pole filter on closure: C1-continuous approach. Inside the zone the closure
        // command is zero and its velocity drains quickly so it cannot coast out the far side.
        // The filter must stay well below the closure time constant or its lag turns the
        // approach under-damped (overshoot at high Strength with high Smoothness).
        double closeTau = errMag > 1.0e-6 ? Math.min(lerp(0.015, 0.06, smooth), tau * 0.25) : INSIDE_DRAIN_TAU;
        double av = alpha(dt, closeTau);
        v1Yaw += (closeYaw * share - v1Yaw) * av;
        v1Pitch += (closePitch * share - v1Pitch) * av;
        velYaw += (v1Yaw - velYaw) * av;
        velPitch += (v1Pitch - velPitch) * av;

        double stepYaw = (velYaw + ffYaw * share) * dt;
        double stepPitch = (velPitch + ffPitch * share) * dt;

        // Never carry the crosshair past the zone edge along the closure axis in one frame.
        if (errMag > 1.0e-6) {
            double ux = errYaw / errMag;
            double uy = errPitch / errMag;
            double along = stepYaw * ux + stepPitch * uy;
            double carry = Math.max(0.0, (ffYaw * ux + ffPitch * uy) * share * dt);
            double limit = errMag + carry;
            if (along > limit) {
                double excess = along - limit;
                stepYaw -= ux * excess;
                stepPitch -= uy * excess;
                double excessVel = excess / dt;
                velYaw -= ux * excessVel;
                velPitch -= uy * excessVel;
                v1Yaw -= ux * excessVel;
                v1Pitch -= uy * excessVel;
            }
        }

        // Never leave the zone through its far edge on either axis. The closure clamp above only
        // covers the closure direction; when wind (or a diagonal approach) has turned the closure
        // mostly onto one axis, velocity left on the other axis must not coast past the target.
        double camRelYaw = wrapDegrees(camYaw - zone.yaw());
        double[] yawClamp = clampFarEdge(camRelYaw, stepYaw, -zone.yawHalf(), zone.yawHalf(), ffYaw * share * dt);
        if (yawClamp[1] != 0.0) {
            stepYaw = yawClamp[0];
            velYaw -= yawClamp[1] / dt;
            v1Yaw -= yawClamp[1] / dt;
        }
        double[] pitchClamp = clampFarEdge(camPitch, stepPitch, zone.pitchMin(), zone.pitchMax(), ffPitch * share * dt);
        if (pitchClamp[1] != 0.0) {
            stepPitch = pitchClamp[0];
            velPitch -= pitchClamp[1] / dt;
            v1Pitch -= pitchClamp[1] / dt;
        }

        return new Output(stepYaw, stepPitch, engagement, detached);
    }

    /**
     * {@code [step, removed]}: the step limited so a position at or before the far edge (in the
     * direction of travel) does not end beyond it, plus the target's own carry this frame.
     */
    private static double[] clampFarEdge(double pos, double step, double lo, double hi, double carry) {
        if (step > 0.0 && pos <= hi) {
            double limit = hi + Math.max(0.0, carry) - pos;
            if (step > limit) return new double[]{limit, step - limit};
        } else if (step < 0.0 && pos >= lo) {
            double limit = lo + Math.min(0.0, carry) - pos;
            if (step < limit) return new double[]{limit, step - limit};
        }
        return new double[]{step, 0.0};
    }

    /**
     * Wind velocity (deg/s) for one frame, given the unit closure direction. The gust is an
     * Ornstein-Uhlenbeck process (exact discretisation, so it is frame-rate independent): it wanders
     * smoothly with memory {@link #WIND_TAU}. The arc keeps one side for the whole approach, which is
     * what makes a sweep look like a curve instead of a wobble.
     */
    private double[] windVelocity(double ux, double uy, double errMag, double closeSpeed, double wind, double dt) {
        if (arcSide == 0.0) {
            arcSide = windRandom.nextBoolean() ? 1.0 : -1.0;
        }
        double decay = Math.exp(-dt / WIND_TAU);
        double kick = Math.sqrt(1.0 - decay * decay);
        gustAlong = gustAlong * decay + kick * windRandom.nextGaussian();
        gustAcross = gustAcross * decay + kick * windRandom.nextGaussian();

        double t = clamp((errMag - WIND_LAND_DEG) / (WIND_FULL_DEG - WIND_LAND_DEG), 0.0, 1.0);
        double envelope = smoothstep(t);
        double scale = wind * closeSpeed * envelope;
        double along = scale * WIND_GUST * WIND_ALONG * gustAlong;
        double across = scale * (WIND_GUST * gustAcross + WIND_ARC * arcSide);
        // Never let the along-axis gust reverse the closure: it only varies the speed.
        along = Math.max(along, -0.6 * closeSpeed);
        // (ux, uy) is the closure direction; (-uy, ux) is across it.
        return new double[]{ux * along - uy * across, uy * along + ux * across};
    }

    /** On target or wind off: the gust relaxes and the next approach picks a fresh arc side. */
    private void settleWind(double dt) {
        double decay = Math.exp(-dt / WIND_TAU);
        gustAlong *= decay;
        gustAcross *= decay;
        arcSide = 0.0;
    }

    // ── Math helpers ────────────────────────────────────────────────────────────

    static double alpha(double dt, double tau) {
        return 1.0 - Math.exp(-dt / Math.max(1.0e-4, tau));
    }

    static double smoothstep(double t) {
        double x = clamp(t, 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }
}
