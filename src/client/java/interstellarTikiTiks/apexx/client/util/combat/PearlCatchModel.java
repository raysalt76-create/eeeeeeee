package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Trained constants of the Pearl Catch planner. Every value here was fitted offline by
 * {@code PearlCatchTrainer} (src/test): scenarios over every pitch and several shooter states, each plan
 * replayed against an independent copy of the server's physics with the real launch spread rolled per
 * projectile and the later use handled a tick early or late {@link WindChargeIntercept#SLIP_EACH_WAY} of
 * the time each way. The fit maximises that replayed catch rate; the trainer prints the values to copy here.
 */
public final class PearlCatchModel {
    // ── Ensemble (PearlCatchEnsemble) ────────────────────────────────────────

    /** How strongly an engine's own confidence moves its weight in the blend. */
    public static final double GAIN = 1.0;
    /** Weight bias of the physics solver's vote (the reference, 0). */
    public static final double BIAS_PHYSICS = 0.0;
    /** Weight bias of the region model's vote, relative to the physics solver. */
    public static final double BIAS_REGION = 0.0;
    /** Fine rates this close to the best count as level; levels go to the blend, then the region. */
    public static final double AGREEMENT_TIE = 0.0;

    // ── Order (which projectile leaves first) ────────────────────────────────

    /** Vertical shooter movement (blocks a tick, the server's view of the last move) the table is sampled at. */
    private static final double[] ORDER_SPEEDS = {-3.0, -2.5, -2.0, -1.5, -1.0, -0.6, -0.3, 0.0, 0.42};
    /**
     * Downward pitch from which the wind charge must go first, per {@link #ORDER_SPEEDS}: below it the
     * pearl leaves first and the charge chases it; at or past it the pearl, which speeds up on the way
     * down, runs into a charge thrown ahead of it. 90 means never: falling 1.5 blocks a tick or faster the
     * pearl inherits so much of the fall that it leaves above its terminal speed, slows, and is best
     * chased at every pitch. Rising from a jump the switch comes early (about 6 degrees down); on the
     * ground at about 26, with no catch in either order between roughly 16 and 24.
     */
    private static final double[] CHARGE_FIRST_PITCH = {90.0, 90.0, 90.0, 90.0, 36.0, 24.0, 16.0, 26.0, 6.0};
    /** Pitch over which the order preference fades from one side of the threshold to the other. */
    private static final double ORDER_BLEND = 4.0;
    /** Rate added to the order the threshold calls for, when both orders have a catch. */
    public static final double ORDER_BIAS = 0.05;

    // ── Ranking priors (not fitted: the open-air replay cannot measure them) ──

    /**
     * Share of second throws assumed to leave before the first projectile's spawn packet arrives (a high
     * ping, or a gap too short for the packet), so both launches are still unknown when it is aimed. A
     * plan's rate mixes the two cases by it.
     */
    public static final double BLIND_SHARE = 0.3;
    /**
     * Rate a plan gives up per tick the shooter has to be predicted ahead (lead plus gap past the first):
     * the player keeps pressing keys, lands, is hit. On the ground it is small; in the air, where a long
     * gap forecasts a whole fall, it is larger.
     */
    public static final double HORIZON_COST_GROUND = 0.002;
    public static final double HORIZON_COST_AIR = 0.006;

    // ── Catch Time ──────────────────────────────────────────────────────────

    /** Catches whose rates are this close are level; the Catch Time then picks between them. */
    public static final double RATE_TIE = 0.03;

    private PearlCatchModel() {}

    /** The pitch from which the charge goes first, for this vertical movement (on ground: 0). */
    public static double chargeFirstPitch(double verticalSpeed) {
        if (verticalSpeed <= ORDER_SPEEDS[0]) return CHARGE_FIRST_PITCH[0];
        for (int i = 1; i < ORDER_SPEEDS.length; ++i) {
            if (verticalSpeed <= ORDER_SPEEDS[i]) {
                double t = (verticalSpeed - ORDER_SPEEDS[i - 1]) / (ORDER_SPEEDS[i] - ORDER_SPEEDS[i - 1]);
                return CHARGE_FIRST_PITCH[i - 1] + t * (CHARGE_FIRST_PITCH[i] - CHARGE_FIRST_PITCH[i - 1]);
            }
        }
        return CHARGE_FIRST_PITCH[CHARGE_FIRST_PITCH.length - 1];
    }

    /**
     * Rate bonus for charge first over pearl first at this pitch: {@link #ORDER_BIAS} past the
     * threshold, minus it before, faded linearly across {@link #ORDER_BLEND} degrees.
     */
    public static double chargeFirstBias(float pitch, double verticalSpeed) {
        double over = (pitch - chargeFirstPitch(verticalSpeed)) / ORDER_BLEND;
        return ORDER_BIAS * Math.max(-1.0, Math.min(1.0, over));
    }
}
