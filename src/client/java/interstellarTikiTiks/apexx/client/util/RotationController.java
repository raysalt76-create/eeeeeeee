package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.util.math.Randomization;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * The rotation motion core — every rotation goes through this one model, parameterised by
 * {@link RotationRequest}. Continuous function of speed, response, humanization, and prediction.
 */
public final class RotationController {

    private static final double FRAME_SECONDS = 1.0 / 60.0;
    private static final double TAU = Math.PI * 2.0;

    /** Response rate at response = 0 and response = 1, rad/s. */
    private static final double MIN_RATE = 5.0;
    private static final double MAX_RATE = 30.0;
    /** Explicit-integration bound: the rate is capped so rate * dt <= 0.5 on any frame. */
    private static final double MAX_RATE_STEP = 0.5;
    /** Velocity kept when a frame would carry the rotation past the aim point. */
    private static final double ARRIVAL_DAMPING = 0.45;
    /** Error (deg) inside which leftover velocity is bled off instead of emitted. */
    private static final double SETTLE_DEGREES = 0.012;

    /** Time constant of the aim-point position filter, seconds. */
    private static final double TARGET_SMOOTH_TAU = 0.040;
    private static final double TARGET_VELOCITY_ALPHA = 0.28;
    private static final double TARGET_MIN_SPEED = 0.35;
    private static final int TARGET_MIN_SAMPLES = 3;

    private static final double TARGET_MAX_LEAD_SECONDS = 0.08;
    private static final double TARGET_MAX_LEAD_BLOCKS = 1.0;
    private static final double TARGET_VERTICAL_SCALE = 0.5;
    private static final double TARGET_SAMPLE_TIMEOUT = 0.5;
    private static final double TARGET_JUMP_RESET = 4.0;

    /** Aim drift / tremor / overshoot amplitudes at humanization = 1, degrees. */
    private static final double DRIFT_DEGREES = 0.30;
    private static final double TREMOR_DEGREES = 0.10;
    private static final double OVERSHOOT_DEGREES = 0.15;
    private static final double OVERSHOOT_MIN_ERROR = 6.0;
    private static final double OVERSHOOT_TAU = 0.22;
    /** Base +/-1 pixel nudge, so a long clean sweep never emits a locked constant step. */
    private static final double DITHER_BASE_YAW = 0.05;
    private static final double DITHER_BASE_PITCH = 0.03;

    private final Random rng;
    private final Randomization.SmoothNoise yawDrift = new Randomization.SmoothNoise();
    private final Randomization.SmoothNoise pitchDrift = new Randomization.SmoothNoise();

    // ------------------------------------------------------------------ request parameters
    private double speed = 360.0;
    private double rate = MIN_RATE;
    private double humanization = 0.0;
    private double prediction = 0.0;
    private boolean yawOnly = false;
    private boolean placement = false;
    private boolean targetSmoothing = true;
    /** Post-arbitration turn-rate ceiling in deg/s applied before quantization; 0 = uncapped. */
    private double turnRateCapDegPerSec = 0.0;

    // ------------------------------------------------------------------ motion state
    private double velocityYaw;
    private double velocityPitch;
    private double yawRemainder;
    private double pitchRemainder;
    private double speedScale = 1.0;

    // ------------------------------------------------------------------ human layer
    private double driftRateYaw;
    private double driftRatePitch;
    private double tremorRate;
    private double tremorPhase;
    private double overshootYaw;
    private double overshootPitch;
    private boolean overshootSeeded;
    private double humanizedYawOffset;
    private double humanizedPitchOffset;

    // ------------------------------------------------------------------ target tracking
    private boolean hasTargetSample;
    private double sampleX;
    private double sampleY;
    private double sampleZ;
    private double smoothedX;
    private double smoothedY;
    private double smoothedZ;
    private double targetVelocityX;
    private double targetVelocityY;
    private double targetVelocityZ;
    private long lastTargetSampleTime;
    private int targetSamples;

    /** Reused return buffers. */
    private final double[] mover = new double[2];
    private final double[] angles = new double[2];

    public RotationController(Random rng) {
        this.rng = rng;
        this.rollHumanParameters();
    }

    /** Copies a request in. Safe to call every tick with the same request. */
    public void apply(RotationRequest request) {
        this.speed = request.speed();
        this.rate = MIN_RATE + (MAX_RATE - MIN_RATE) * request.response();
        this.yawOnly = request.yawOnly();
        this.placement = request.placement();
        this.targetSmoothing = request.targetSmoothing();
        this.prediction = request.prediction();
        if (Math.abs(request.humanization() - this.humanization) > 1.0E-6) {
            this.humanization = request.humanization();
            this.rollHumanParameters();
        }
    }

    /**
     * Post-arbitration turn-rate ceiling in deg/s, applied to the committed per-frame step before
     * quantization. 0 (or negative) leaves motion uncapped. Set each step from the owning module's
     * turn-rate limit so no request -- an INSTANT snap included -- can emit an impossible turn.
     */
    public void setTurnRateCap(double degPerSec) {
        this.turnRateCapDegPerSec = Math.max(0.0, degPerSec);
    }

    public void reset(boolean forgetTarget) {
        this.velocityYaw = 0.0;
        this.velocityPitch = 0.0;
        this.yawRemainder = 0.0;
        this.pitchRemainder = 0.0;
        this.humanizedYawOffset = 0.0;
        this.humanizedPitchOffset = 0.0;
        this.overshootYaw = 0.0;
        this.overshootPitch = 0.0;
        this.overshootSeeded = false;
        this.speedScale = 1.0;
        this.rollHumanParameters();
        if (forgetTarget) {
            this.hasTargetSample = false;
            this.targetSamples = 0;
            this.targetVelocityX = 0.0;
            this.targetVelocityY = 0.0;
            this.targetVelocityZ = 0.0;
        }
    }

    public double[] step(double currentYaw, double currentPitch,
                         double eyeX, double eyeY, double eyeZ,
                         Vec3d targetPos,
                         float releaseYaw, float releasePitch, boolean releasing,
                         float gcd, long nowMs, double deltaTime) {
        if (this.placement && !releasing) return this.zero();
        double dt = Math.max(deltaTime, 0.05) * FRAME_SECONDS;
        double targetYaw;
        double targetPitch;
        if (targetPos != null) {
            this.resolveTarget(eyeX, eyeY, eyeZ, targetPos, currentYaw, currentPitch, nowMs);
            targetYaw = this.angles[0];
            targetPitch = this.angles[1];
        } else if (releasing) {
            targetYaw = RotationMath.nearestYaw(releaseYaw, (float) currentYaw);
            targetPitch = releasePitch;
        } else {
            return this.zero();
        }
        double yawError = RotationMath.axisDelta(currentYaw, targetYaw, true);
        double pitchError = RotationMath.axisDelta(currentPitch, targetPitch, false);
        this.updateSpeedScale(dt);
        this.updateHumanLayer(nowMs, dt, yawError, pitchError);
        targetYaw += this.humanizedYawOffset;
        targetPitch += this.humanizedPitchOffset;
        this.mover[0] = this.stepAxis(currentYaw, targetYaw, true, dt, gcd);
        this.mover[1] = this.yawOnly ? 0.0 : this.stepAxis(currentPitch, targetPitch, false, dt, gcd);
        return this.mover;
    }

    public double[] stepPlacement(double currentYaw, double currentPitch,
                                  Vec3d eye, Vec3d target, float sentYaw, float sentPitch, float gcd) {
        if (!this.placement || target == null) return this.zero();
        float[] goal = RotationMath.anglesTo(eye, target, sentYaw);
        this.mover[0] = placementAxis(currentYaw, sentYaw, goal[0], true, gcd);
        this.mover[1] = this.yawOnly ? 0 : placementAxis(currentPitch, sentPitch, goal[1], false, gcd);
        this.velocityYaw = this.velocityPitch = this.yawRemainder = this.pitchRemainder = 0;
        return this.mover;
    }

    private double placementAxis(double current, double sent, double goal, boolean yaw, float gcd) {
        double budget = this.speed * 0.05 * (yaw ? 1 : 0.9);
        double error = RotationMath.axisDelta(sent, goal, yaw);
        double delta = MathHelper.clamp(error, -budget, budget);
        if (gcd > 0) {
            long limit = (long) Math.floor(budget / gcd);
            long pixels = Math.max(-limit, Math.min(limit, Math.round(delta / gcd)));
            delta = pixels * (double) gcd;
        }
        return RotationMath.axisDelta(current, sent + delta, yaw);
    }

    public float[] arrivalAim(float yaw, float pitch, float desiredYaw, float desiredPitch,
                              double seconds, double frameSeconds) {
        double dt = MathHelper.clamp(frameSeconds, FRAME_SECONDS * .05, .05);
        int frames = Math.max(1, Math.min(120, (int) Math.round(seconds / dt)));
        return new float[]{(float) arrivalAxis(yaw, RotationMath.nearestYaw(desiredYaw, yaw),
                velocityYaw, yaw - 179.9, yaw + 179.9, true, frames, dt),
                (float) arrivalAxis(pitch, desiredPitch, velocityPitch, -90, 90, false, frames, dt)};
    }

    /**
     * Offsets (degrees) each axis reaches by {@code seconds} when commanded flat out from the live
     * velocity: {yawLow, yawHigh, pitchLow, pitchHigh}. Read-only; the same pursuit model as
     * {@link #arrivalAim}, so whatever this calls reachable, an arrival command can land on.
     */
    public double[] reachable(double seconds, double frameSeconds) {
        double dt = MathHelper.clamp(frameSeconds, FRAME_SECONDS * .05, .05);
        int frames = Math.max(1, Math.min(120, (int) Math.round(seconds / dt)));
        return new double[]{flatOut(velocityYaw, -1, true, frames, dt), flatOut(velocityYaw, 1, true, frames, dt),
                flatOut(velocityPitch, -1, false, frames, dt), flatOut(velocityPitch, 1, false, frames, dt)};
    }

    private double flatOut(double velocity, int sign, boolean yaw, int frames, double dt) {
        double axisRate = Math.min(rate, MAX_RATE_STEP / dt);
        double pursuit = 1 - Math.exp(-axisRate * dt);
        double ceiling = sign * this.speed * speedScale * (yaw ? 1 : .9);
        double position = 0.0;
        double speed = velocity;
        for (int frame = 0; frame < frames; frame++) {
            speed += (ceiling - speed) * pursuit;
            position += speed * dt;
        }
        return position;
    }

    private double arrivalAxis(double current, double desired, double velocity, double low,
                               double high, boolean yaw, int frames, double dt) {
        for (int iteration = 0; iteration < 32; iteration++) {
            double goal = (low + high) * .5;
            double position = current;
            double speed = velocity;
            double axisRate = Math.min(rate, MAX_RATE_STEP / dt);
            double pursuit = 1 - Math.exp(-axisRate * dt);
            double ceiling = this.speed * speedScale * (yaw ? 1 : .9);
            for (int frame = 0; frame < frames; frame++) {
                double error = goal - position;
                double target = Math.signum(error) * Math.min(ceiling, axisRate * Math.abs(error));
                speed += (target - speed) * pursuit;
                double move = speed * dt;
                if (Math.abs(move) > Math.abs(error)) { move = error; speed = move / dt * ARRIVAL_DAMPING; }
                position += move;
            }
            if (position < desired) low = goal; else high = goal;
        }
        return (low + high) * .5;
    }

    private double stepAxis(double current, double target, boolean yawAxis, double dt, float gcd) {
        double error = RotationMath.axisDelta(current, target, yawAxis);
        double absError = Math.abs(error);
        double velocity = yawAxis ? this.velocityYaw : this.velocityPitch;
        double axisSpeed = this.speed * this.speedScale * (yawAxis ? 1.0 : 0.9);
        double axisRate = Math.min(this.rate, MAX_RATE_STEP / dt);
        double targetVelocity = Math.signum(error) * Math.min(axisSpeed, axisRate * absError);
        double pursuit = 1.0 - Math.exp(-axisRate * dt);
        velocity += (targetVelocity - velocity) * pursuit;
        double move = velocity * dt;
        if (Math.abs(move) > absError) {
            move = error;
            velocity = move / dt * ARRIVAL_DAMPING;
        }
        if (absError <= Math.max(SETTLE_DEGREES, gcd * 0.5) && Math.abs(move) <= gcd) {
            velocity *= 0.35;
            move = 0.0;
        }
        if (this.turnRateCapDegPerSec > 0.0) {
            // Post-arbitration ceiling: cap the committed per-frame step so no request (an INSTANT
            // snap included) produces an impossible turn. Clamp BEFORE quantize so the emitted angle
            // stays on the mouse lattice. Per axis -- the controller steps axes independently -- so a
            // pure diagonal can reach ~1.41x the cap, still a hard, human-plausible bound.
            double cap = this.turnRateCapDegPerSec * dt;
            if (move > cap) move = cap;
            else if (move < -cap) move = -cap;
        }
        move = this.quantize(move, yawAxis, gcd);
        this.store(yawAxis, velocity);
        return move;
    }

    private void resolveTarget(double eyeX, double eyeY, double eyeZ, Vec3d targetPos,
                               double currentYaw, double currentPitch, long nowMs) {
        if (!targetSmoothing) {
            this.copyAngles(RotationMath.anglesTo(eyeX, eyeY, eyeZ, targetPos.x, targetPos.y, targetPos.z, (float) currentYaw));
            return;
        }
        this.trackTarget(targetPos, nowMs);
        double px = this.smoothedX;
        double py = this.smoothedY;
        double pz = this.smoothedZ;
        this.copyAngles(RotationMath.anglesTo(eyeX, eyeY, eyeZ, px, py, pz, (float) currentYaw));
        if (this.prediction <= 0.0 || this.targetSamples < TARGET_MIN_SAMPLES) {
            return;
        }
        double targetSpeed = Math.sqrt(this.targetVelocityX * this.targetVelocityX
                + this.targetVelocityY * this.targetVelocityY
                + this.targetVelocityZ * this.targetVelocityZ);
        if (targetSpeed < TARGET_MIN_SPEED) {
            return;
        }
        double lead = TARGET_MAX_LEAD_SECONDS * this.prediction;
        double leadX = this.targetVelocityX * lead;
        double leadY = this.targetVelocityY * lead * TARGET_VERTICAL_SCALE;
        double leadZ = this.targetVelocityZ * lead;
        double leadLength = Math.sqrt(leadX * leadX + leadY * leadY + leadZ * leadZ);
        if (leadLength > TARGET_MAX_LEAD_BLOCKS) {
            double shrink = TARGET_MAX_LEAD_BLOCKS / leadLength;
            leadX *= shrink;
            leadY *= shrink;
            leadZ *= shrink;
        }
        this.copyAngles(RotationMath.anglesTo(eyeX, eyeY, eyeZ,
                px + leadX, py + leadY, pz + leadZ, (float) currentYaw));
    }

    private void trackTarget(Vec3d targetPos, long nowMs) {
        double elapsed = (nowMs - this.lastTargetSampleTime) / 1000.0;
        double jumpX = targetPos.x - this.sampleX;
        double jumpY = targetPos.y - this.sampleY;
        double jumpZ = targetPos.z - this.sampleZ;
        double jump = Math.sqrt(jumpX * jumpX + jumpY * jumpY + jumpZ * jumpZ);
        if (!this.hasTargetSample || elapsed > TARGET_SAMPLE_TIMEOUT || jump > TARGET_JUMP_RESET) {
            this.smoothedX = targetPos.x;
            this.smoothedY = targetPos.y;
            this.smoothedZ = targetPos.z;
            this.sampleX = targetPos.x;
            this.sampleY = targetPos.y;
            this.sampleZ = targetPos.z;
            this.targetVelocityX = 0.0;
            this.targetVelocityY = 0.0;
            this.targetVelocityZ = 0.0;
            this.targetSamples = 1;
            this.lastTargetSampleTime = nowMs;
            this.hasTargetSample = true;
            return;
        }
        if (elapsed > 1.0E-4) {
            double alpha = 1.0 - Math.exp(-elapsed / TARGET_SMOOTH_TAU);
            this.smoothedX += (targetPos.x - this.smoothedX) * alpha;
            this.smoothedY += (targetPos.y - this.smoothedY) * alpha;
            this.smoothedZ += (targetPos.z - this.smoothedZ) * alpha;
            double instantX = (targetPos.x - this.sampleX) / elapsed;
            double instantY = (targetPos.y - this.sampleY) / elapsed;
            double instantZ = (targetPos.z - this.sampleZ) / elapsed;
            double blend = this.targetSamples <= 1 ? 1.0 : TARGET_VELOCITY_ALPHA;
            this.targetVelocityX += (instantX - this.targetVelocityX) * blend;
            this.targetVelocityY += (instantY - this.targetVelocityY) * blend;
            this.targetVelocityZ += (instantZ - this.targetVelocityZ) * blend;
            this.targetSamples++;
        }
        this.sampleX = targetPos.x;
        this.sampleY = targetPos.y;
        this.sampleZ = targetPos.z;
        this.lastTargetSampleTime = nowMs;
    }

    private void updateHumanLayer(long nowMs, double dt, double yawError, double pitchError) {
        if (this.humanization <= 0.0) {
            this.humanizedYawOffset = 0.0;
            this.humanizedPitchOffset = 0.0;
            this.overshootYaw = 0.0;
            this.overshootPitch = 0.0;
            return;
        }
        if (!this.overshootSeeded && Math.max(Math.abs(yawError), Math.abs(pitchError)) > OVERSHOOT_MIN_ERROR) {
            this.overshootSeeded = true;
            if (this.rng.nextDouble() < 0.25) {
                double magnitude = OVERSHOOT_DEGREES * this.humanization * (0.2 + this.rng.nextDouble());
                if (Math.abs(yawError) >= Math.abs(pitchError)) {
                    this.overshootYaw = Math.signum(yawError) * magnitude;
                } else {
                    this.overshootPitch = Math.signum(pitchError) * magnitude;
                }
            }
        }
        double t = nowMs * 0.001;
        double driftYaw = this.yawDrift.fbm(t * this.driftRateYaw, 3);
        double driftPitch = this.pitchDrift.fbm(t * this.driftRatePitch, 3);
        double tremor = Math.sin(t * TAU * this.tremorRate + this.tremorPhase);
        double scale = this.humanization;
        this.humanizedYawOffset = scale * (DRIFT_DEGREES * driftYaw + TREMOR_DEGREES * tremor) + this.overshootYaw;
        this.humanizedPitchOffset = scale * (DRIFT_DEGREES * 0.8 * driftPitch + TREMOR_DEGREES * 0.6 * tremor) + this.overshootPitch;
        double decay = Math.exp(-dt / OVERSHOOT_TAU);
        this.overshootYaw *= decay;
        this.overshootPitch *= decay;
    }

    private void updateSpeedScale(double dt) {
        double amplitude = 0.012 + 0.02 * this.humanization;
        this.speedScale = MathHelper.clamp(
                this.speedScale + this.rng.nextGaussian() * amplitude * Math.sqrt(dt), 0.9, 1.1);
    }

    private void rollHumanParameters() {
        this.driftRateYaw = 0.10 + this.rng.nextDouble() * 0.15;
        this.driftRatePitch = 0.10 + this.rng.nextDouble() * 0.15;
        this.tremorRate = 0.6 + this.rng.nextDouble();
        this.tremorPhase = this.rng.nextDouble() * TAU;
    }

    private double quantize(double delta, boolean yawAxis, float gcd) {
        if (gcd <= 0.0f) {
            return delta;
        }
        double residual = yawAxis ? (this.yawRemainder += delta) : (this.pitchRemainder += delta);
        if (Math.abs(residual) < 1.0E-5) {
            return 0.0;
        }
        long count = Math.round(residual / gcd);
        if (count != 0L) {
            double probability = (yawAxis ? DITHER_BASE_YAW : DITHER_BASE_PITCH)
                    + (yawAxis ? 0.45 : 0.30) * this.humanization;
            if (this.rng.nextDouble() < probability) {
                count += this.rng.nextBoolean() ? 1L : -1L;
            }
        }
        double emitted = count * (double) gcd;
        if (yawAxis) {
            this.yawRemainder = residual - emitted;
        } else {
            this.pitchRemainder = residual - emitted;
        }
        return emitted;
    }

    private void store(boolean yawAxis, double velocity) {
        if (yawAxis) {
            this.velocityYaw = velocity;
        } else {
            this.velocityPitch = velocity;
        }
    }

    private void copyAngles(float[] source) {
        this.angles[0] = source[0];
        this.angles[1] = source[1];
    }

    private double[] zero() {
        this.mover[0] = 0.0;
        this.mover[1] = 0.0;
        return this.mover;
    }
}
