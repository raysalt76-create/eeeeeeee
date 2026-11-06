package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Decides when AutoMace's spear-reach pre-swap should happen, from a per-tick approach curve
 * ({@link InterceptPredictor#approachCurve}, index 0 = now, blocks). Pure arithmetic, no game state.
 *
 * <p>The curve is the forecast distance between the local eye and the target hitbox. The planner reads
 * three things from it: the closing speed, the fractional tick at which the spear reach is first
 * entered, and whether the target is slipping away. The lead the swap needs grows with closing speed:
 * the spear must be selected in an earlier packet interval than the smash that uses it, and a fast
 * closing target crosses the 3.0 to 4.5 band in under a tick, so a fixed lookahead is late.
 *
 * <p>The thresholds are heuristics and are UNVERIFIED in game.
 */
public final class SpearReachPlanner {

    /** Longest forecast the planner asks for, in ticks. */
    public static final int MAX_HORIZON = 10;
    /** Base lead, ticks: one interval between the spear and the smash, one for tick phase. */
    static final double BASE_LEAD_TICKS = 2.0;
    /** Extra lead per block/tick of closing speed beyond 0, ticks, capped by {@link #MAX_SPEED_LEAD_TICKS}. */
    static final double LEAD_PER_SPEED = 0.4;
    static final double MAX_SPEED_LEAD_TICKS = 3.0;
    /** Hold lookahead, ticks, before speed scaling. */
    static final int BASE_HOLD_TICKS = 6;

    private SpearReachPlanner() {}

    /**
     * @param inMeleeReach  the nearest forecast point is already inside the plain melee reach, so the
     *                      mace reaches without a spear
     * @param closingSpeed  blocks per tick the approach distance is shrinking, never negative
     * @param entryTick     fractional tick at which the spear reach is first entered, infinity if never
     * @param leadTicks     how far ahead of entry the swap is wanted at this closing speed
     * @param swapNow       the spear should be selected this tick
     */
    public record Plan(boolean inMeleeReach, double closingSpeed, double entryTick, double leadTicks,
                       boolean swapNow) {
        public static final Plan NONE = new Plan(false, 0.0, Double.POSITIVE_INFINITY, BASE_LEAD_TICKS, false);
    }

    /** Blocks per tick the distance shrinks over the first few ticks of the curve. */
    static double closingSpeed(double[] curve) {
        if (curve == null || curve.length < 2 || !Double.isFinite(curve[0])) {
            return 0.0;
        }
        int last = 0;
        for (int j = 1; j < Math.min(curve.length, 4); j++) {
            if (!Double.isFinite(curve[j])) {
                break;
            }
            last = j;
        }
        return last == 0 ? 0.0 : Math.max(0.0, (curve[0] - curve[last]) / last);
    }

    /** Closing speed of a curve, blocks per tick. */
    public static double closingSpeedOf(double[] curve) {
        return closingSpeed(curve);
    }

    /** Lead, in ticks, the swap needs at this closing speed. */
    static double leadTicks(double closingSpeed) {
        return BASE_LEAD_TICKS + Math.min(MAX_SPEED_LEAD_TICKS, Math.max(0.0, closingSpeed) * LEAD_PER_SPEED);
    }

    /** Fractional tick at which the curve first drops to {@code range}, infinity if it never does. */
    static double entryTick(double[] curve, double range) {
        if (curve == null) {
            return Double.POSITIVE_INFINITY;
        }
        for (int j = 0; j < curve.length; j++) {
            if (curve[j] <= range) {
                if (j == 0 || !Double.isFinite(curve[j - 1])) {
                    return j;
                }
                double span = curve[j - 1] - curve[j];
                return span <= 1.0e-9 ? j : (j - 1) + (curve[j - 1] - range) / span;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Plans the pre-swap for one target.
     *
     * @param curve      forecast approach per tick, from now
     * @param spearRange the spear attack reach
     * @param meleeRange the reach of the item held without the spear
     */
    public static Plan plan(double[] curve, double spearRange, double meleeRange) {
        if (curve == null || curve.length == 0 || !Double.isFinite(curve[0])) {
            return Plan.NONE;
        }
        double speed = closingSpeed(curve);
        double lead = leadTicks(speed);
        double entry = entryTick(curve, spearRange);
        // Already inside the held item's own reach: the mace gets there without a spear.
        boolean melee = curve[0] <= meleeRange;
        boolean swap = !melee && entry <= lead;
        return new Plan(melee, speed, entry, lead, swap);
    }

    /**
     * Instant plan: swap only once the target is already inside the spear reach (forecast point 0, the
     * current eye-to-hitbox distance) and outside the plain melee reach. No lead, no forecast.
     */
    public static Plan planInstant(double[] curve, double spearRange, double meleeRange) {
        if (curve == null || curve.length == 0 || !Double.isFinite(curve[0])) {
            return Plan.NONE;
        }
        boolean melee = curve[0] <= meleeRange;
        boolean swap = !melee && curve[0] <= spearRange;
        return new Plan(melee, closingSpeed(curve), swap ? 0.0 : entryTick(curve, spearRange), 0.0, swap);
    }

    /**
     * Forecast slack, blocks, on the tick the smash will use the spear reach: the forecast is a fit
     * through server-sent positions, so a target forecast a hair outside the reach is not written off.
     */
    public static final double SMASH_TICK_SLACK = 0.25;

    /**
     * Guarded instant plan, the one AutoMace's AutoSpear Reach uses. The spear is selected in this
     * tick's interval and the mace smash follows in the next, so the spear reach has to hold on the
     * NEXT forecast point, not only now. Three guards on top of {@link #planInstant}:
     * <ul>
     *   <li>the mace reaches on its own now or next tick ({@code min(now, next) <= meleeRange}): no swap;</li>
     *   <li>the target is leaving: forecast beyond {@code spearRange + SMASH_TICK_SLACK} next tick: no swap;</li>
     *   <li>horizontal only: the swap is gated by the XZ distance between the two players being strictly
     *       inside ({@code meleeRange}, {@code spearRange}) instead of the 3D one, because a dive closes
     *       the vertical gap by itself. The forecast must still put the box inside the spear reach on the
     *       smash tick, so a swap is never made for a hit that cannot reach.</li>
     * </ul>
     *
     * @param horizontal     current XZ distance between the two players, blocks (ignored unless
     *                       {@code horizontalOnly})
     * @param horizontalOnly gate on {@code horizontal} instead of the 3D distance now
     */
    public static Plan planGuarded(double[] curve, double spearRange, double meleeRange,
                                   double horizontal, boolean horizontalOnly) {
        if (curve == null || curve.length == 0 || !Double.isFinite(curve[0])) {
            return Plan.NONE;
        }
        double now = curve[0];
        double next = curve.length > 1 && Double.isFinite(curve[1]) ? curve[1] : now;
        boolean melee = Math.min(now, next) <= meleeRange;
        boolean reach;
        if (horizontalOnly) {
            reach = Double.isFinite(horizontal) && horizontal > meleeRange && horizontal < spearRange
                    && next <= spearRange;
        } else {
            reach = now <= spearRange && next <= spearRange + SMASH_TICK_SLACK;
        }
        boolean swap = reach && !melee;
        return new Plan(melee, closingSpeed(curve), swap ? 0.0 : entryTick(curve, spearRange), 0.0, swap);
    }

    /**
     * Horizontal-only hold check: a held spear is kept while the XZ distance is still inside the spear
     * reach plus {@code margin}, so a target that stepped back in the same tick releases at once.
     */
    public static boolean keepHoldingHorizontal(double horizontal, double spearRange, double margin) {
        return Double.isFinite(horizontal) && horizontal < spearRange + margin;
    }

    /**
     * True when the target is outside {@code meleeRange} now and the curve enters {@code spearRange}
     * within {@code ticks}. This is the opening a swap reaction is timed from: started here, the reaction
     * has elapsed by the tick {@link #planGuarded} allows the swap, instead of starting on that tick and
     * running out after the one-tick band has already been crossed.
     */
    public static boolean opensWithin(double[] curve, double spearRange, double meleeRange, double ticks) {
        if (curve == null || curve.length == 0 || !Double.isFinite(curve[0]) || curve[0] <= meleeRange) {
            return false;
        }
        return entryTick(curve, spearRange) <= ticks;
    }

    /** How many ticks of forecast a target at this closing speed needs. */
    public static int horizonFor(double closingSpeed) {
        return Math.min(MAX_HORIZON, (int) Math.ceil(leadTicks(closingSpeed)) + 2);
    }

    /**
     * How far out, in blocks, a target can be and still be worth forecasting: the spear reach plus what
     * it can close over the longest lead, with slack.
     */
    public static double candidateRadius(double spearRange, double closingSpeedGuess) {
        return spearRange + Math.max(4.0, closingSpeedGuess) * (BASE_LEAD_TICKS + MAX_SPEED_LEAD_TICKS) + 2.0;
    }

    /**
     * Whether a held spear is still worth holding: the curve gets within {@code spearRange + margin}
     * inside a hold window that widens with closing speed.
     */
    public static boolean keepHolding(double[] curve, double spearRange, double margin) {
        if (curve == null) {
            return false;
        }
        double speed = closingSpeed(curve);
        int window = Math.min(curve.length - 1, BASE_HOLD_TICKS + (int) Math.ceil(speed / 2.0));
        for (int j = 0; j <= window; j++) {
            if (curve[j] <= spearRange + margin) {
                return true;
            }
        }
        return false;
    }
}
