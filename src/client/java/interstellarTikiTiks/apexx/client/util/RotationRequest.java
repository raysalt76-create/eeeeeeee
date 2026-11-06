package interstellarTikiTiks.apexx.client.util;

/**
 * Expresses continuous parameters for a rotation request submitted to {@link RotationManager}.
 * Replaces mode enums with continuous parameters: speed, response, humanization, prediction,
 * reaction latency, silent mode, and yaw-only.
 */
public final class RotationRequest {

    private double speed = 360.0;
    private double response = 0.5;
    private double humanization = 0.0;
    private double prediction = 0.0;
    private long reactionMs = 0L;
    private boolean silent = false;
    private boolean yawOnly = false;
    private boolean placement = false;
    private boolean targetSmoothing = true;
    private boolean instant = false;
    private double aimSpread = 0.0;
    private double missChance = 0.0;
    private double maxTurnRateDegPerSec = 0.0;
    private long reactionStdevMs = 0L;

    private RotationRequest() {
    }

    public static RotationRequest create() {
        return new RotationRequest();
    }

    /** Ceiling on the rotation's angular speed, in degrees per second. */
    public RotationRequest speed(double degPerSecond) {
        this.speed = Math.max(0.0, degPerSecond);
        return this;
    }

    /**
     * How hard the rotation converges on the aim point, 0..1.
     */
    public RotationRequest response(double response) {
        this.response = clampUnit(response);
        return this;
    }

    /**
     * Amount of the organic humanization layer, 0..1: aim drift, tremor, overshoot.
     */
    public RotationRequest humanization(double humanization) {
        this.humanization = clampUnit(humanization);
        return this;
    }

    /**
     * Lead prediction on moving targets, 0..1.
     */
    public RotationRequest prediction(double prediction) {
        this.prediction = clampUnit(prediction);
        return this;
    }

    /**
     * Reaction latency before a freshly acquired rotation starts pulling, in milliseconds.
     */
    public RotationRequest reactionMs(long reactionMs) {
        this.reactionMs = Math.max(0L, reactionMs);
        return this;
    }

    /** Rotate silently: the camera stays mouse-controlled while wire rotation follows. */
    public RotationRequest silent(boolean silent) {
        this.silent = silent;
        return this;
    }

    /** Leave pitch untouched (yaw only). */
    public RotationRequest yawOnly(boolean yawOnly) {
        this.yawOnly = yawOnly;
        return this;
    }

    /** Deterministic block aiming with speed budget. */
    public RotationRequest placement(boolean placement) {
        this.placement = placement;
        return this;
    }

    /** Filter moving target positions before the angular servo. */
    public RotationRequest targetSmoothing(boolean enabled) {
        this.targetSmoothing = enabled;
        return this;
    }

    /**
     * Marks a snap-and-act (INSTANT) aim. RotationManager still floors the organic motion and
     * caps the turn rate on these, but withholds aim-point spread and reaction onset -- they
     * change WHERE the aim lands and WHEN it starts, which would cost placement accuracy and
     * burst-combo timing. Tracking aims (the default, false) receive the full treatment.
     */
    public RotationRequest instant(boolean instant) {
        this.instant = instant;
        return this;
    }

    public boolean instant() {
        return this.instant;
    }

    /** Reaction spread (ms, ex-Gaussian) for a freshly acquired tracking aim. */
    public RotationRequest reactionStdevMs(long stdevMs) {
        this.reactionStdevMs = Math.max(0L, stdevMs);
        return this;
    }

    public long reactionStdevMs() {
        return this.reactionStdevMs;
    }

    /** Aim-point wander as a 0..1 fraction of the target box; 0 = exact centre (tracking aims only). */
    public RotationRequest aimSpread(double spread01) {
        this.aimSpread = clampUnit(spread01);
        return this;
    }

    public double aimSpread() {
        return this.aimSpread;
    }

    /** Per-engagement deliberate-miss probability, 0..1 (tracking aims only). */
    public RotationRequest missChance(double miss01) {
        this.missChance = clampUnit(miss01);
        return this;
    }

    public double missChance() {
        return this.missChance;
    }

    /** Post-arbitration turn-rate ceiling, deg/s; 0 or less = uncapped. */
    public RotationRequest maxTurnRate(double degPerSecond) {
        this.maxTurnRateDegPerSec = Math.max(0.0, degPerSecond);
        return this;
    }

    public double maxTurnRateDegPerSec() {
        return this.maxTurnRateDegPerSec;
    }

    public boolean targetSmoothing() {
        return this.targetSmoothing;
    }

    public boolean placement() {
        return this.placement;
    }

    public double speed() {
        return this.speed;
    }

    public double response() {
        return this.response;
    }

    public double humanization() {
        return this.humanization;
    }

    public double prediction() {
        return this.prediction;
    }

    public long reactionMs() {
        return this.reactionMs;
    }

    public boolean silent() {
        return this.silent;
    }

    public boolean yawOnly() {
        return this.yawOnly;
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
