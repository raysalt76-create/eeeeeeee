package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Pearl Catch planning, free of the client: every decision {@link PearlCatchEngine} takes about where
 * and when to throw, from an explicit {@link Context} (the shooter as the server will see it, the mouse
 * lattice, the limits). With a null world it plans in open air, which is how the offline sweeps and the
 * trainer run the very same code.
 *
 * <p><b>Ranking.</b> Every candidate catch is scored by its expected catch rate: the share of real throws
 * that still catch, with the server handling the later use a tick early or late. Two cases are mixed by
 * {@link PearlCatchModel#BLIND_SHARE}: the first projectile's spawn packet is in before the second throw
 * (only the second launch's spread is left, {@link WindChargeIntercept#catchRate} /
 * {@link WindChargeIntercept#pearlCatchRate}), or it is not (both spreads, {@link WindChargeIntercept#jointCatchRate}).
 * Each tick the shooter must be forecast costs a little ({@link PearlCatchModel#HORIZON_COST_GROUND}). The
 * best rate wins; rates within {@link PearlCatchModel#RATE_TIE} are level and the Catch Time decides (the
 * longest flight within it, else the shortest past it). Between the two orders the learned
 * {@link PearlCatchModel#chargeFirstBias} is added: past a downward pitch that depends on the fall speed
 * the charge must go first.</p>
 *
 * <p><b>Aim.</b> Each finalist and every live re-aim takes two votes, the physics solver's
 * ({@link WindChargeIntercept}) and the region model's ({@link CatchRegionSolver}), and lets
 * {@link PearlCatchEnsemble} weigh, blend and validate them on the fine spread rule of the case at hand.</p>
 *
 * <p><b>Shooter.</b> The shooter is forecast with player gravity and drag and, when the context names the
 * ground below ({@link Context#groundY}), lands on it: a jump's second throw leaves from the ground, not
 * from a fall continued through the floor.</p>
 */
public final class PearlCatchPlanner {
    /** 85% of vertical: at or beyond this pitch either way the fixed macros take over. */
    public static final float MACRO_PITCH = 76.5f;
    /** Largest look correction (degrees) a throw takes in the tick it leaves, so the camera never snaps. */
    public static final float SENT_LOOK_TOLERANCE = 1.4f;
    /** Pitch window (degrees) the down macro searches for its solved throw. */
    public static final float MACRO_PITCH_WINDOW = 6.0f;
    public static final int PEARL_TRACE_TICKS = 160;
    /** A plan below this rate counts as no catch: the look is nudged, or the macro takes over. */
    public static final double NUDGE_MIN_RATE = 0.5;

    /**
     * Ticks past the catch time a catch may still be taken, when nothing reaches it sooner: a pearl
     * cannot be caught sooner than the geometry allows (about 9 ticks straight up, 17 level), so a low
     * catch time takes the earliest catch there is instead of finding none and not aiming.
     */
    private static final int CATCH_OVERSHOOT = 30;
    private static final int CATCH_LIMIT_CAP = 150;
    /** Most ticks a charge-first plan may spend turning before the charge leaves. */
    private static final int MAX_LEAD_TICKS = 2;
    /** From this pitch magnitude on, a planned throw may swing the yaw only {@link #VERTICAL_YAW_BUDGET}. */
    private static final float YAW_BUDGET_PITCH = 55.0f;
    /**
     * Most degrees the yaw may move for a planned throw near vertical. Straight up the yaw barely
     * changes where a throw goes, so a solver would otherwise pick any yaw, up to 180 degrees round,
     * and the camera would spin for a few degrees of tilt.
     */
    private static final float VERTICAL_YAW_BUDGET = 20.0f;
    /** Candidates a search re-checks against the world before giving up on a tick. */
    private static final int WORLD_CHECKS = 3;
    /** Best candidates per order that go through the world check and both aim engines. */
    private static final int FINALISTS = 2;
    /** Pitch search for a look that has no catch: step, most degrees, and world-checked tries. */
    private static final float NUDGE_STEP = 1.0f;
    private static final float NUDGE_MAX = 15.0f;
    private static final int NUDGE_WORLD_TRIES = 3;
    /** Gaps the nudge's quick open-air scan tries past the floor: short flights are the sure ones. */
    private static final int NUDGE_SCAN_GAPS = 4;
    private static final double FREE_LOOK_STEP = 0.05;
    /** Region map half width, degrees of arc: a few times the catch box's angular size, bounded. */
    private static final double REGION_MIN = 0.6;
    private static final double REGION_MAX = 6.0;
    private static final double REGION_BOXES = 3.0;
    /** Half the wind charge box plus a pearl's largest age tolerance: the catch box's reach. */
    private static final double CATCH_RADIUS = 0.3125 / 2.0 + 0.3;
    /** Two unknown launches blur the region by both spreads. */
    private static final double BLIND_SPREAD_SCALE = Math.sqrt(2.0);
    /** Below this horizontal speed (blocks a tick) a grounded shooter counts as standing for the horizon cost. */
    private static final double STANDING_SPEED = 0.05;
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double PLAYER_VERTICAL_DRAG = (double) 0.98f;

    public enum Mode { SOLVER, MACRO_UP, MACRO_DOWN }

    /** The shooter as the server will have it when a use is handled. */
    public record ShooterState(Vec3d feet, Vec3d movement, boolean onGround) {}

    /**
     * Everything a plan depends on. {@code feet}, {@code movement} (the server's view of the last move),
     * {@code velocityY} and {@code onGround}: the shooter now; {@code groundY}: the top of the ground below
     * (NaN: none known); {@code sentYaw}/{@code sentPitch}: the look the next use packet carries;
     * {@code flickLimit}: degrees a tick the look may turn; {@code lookStep}: the mouse lattice step (0 when
     * unknown); {@code gapFloor}/{@code maxGap}: fewest and most ticks between the throws. A null
     * {@code world} plans in open air.
     */
    public record Context(World world, Entity owner, Vec3d feet, Vec3d movement, double velocityY, boolean onGround,
                          double groundY, double eyeHeight, float sentYaw, float sentPitch, float flickLimit,
                          double lookStep, int catchTime, int gapFloor, int maxGap) {
        public Context withSent(float yaw, float pitch) {
            return new Context(world, owner, feet, movement, velocityY, onGround, groundY, eyeHeight, yaw, pitch,
                    flickLimit, lookStep, catchTime, gapFloor, maxGap);
        }

        Context inOpenAir() {
            return new Context(null, owner, feet, movement, velocityY, onGround, groundY, eyeHeight, sentYaw, sentPitch,
                    flickLimit, lookStep, catchTime, gapFloor, maxGap);
        }
    }

    /** A candidate catch: order, ticks of turning before a charge-first throw, the shot and its expected rate. */
    public record Option(boolean chargeFirst, int lead, WindChargeIntercept.Shot shot, double rate) {
        /** Flight of the later throw to the catch, ticks. */
        public int flight() {
            return chargeFirst ? shot.catchAge() : shot.catchAge() - shot.throwAge();
        }
    }

    /**
     * A plan. Solver plans and the down macro carry both looks: the pearl's and the charge's, the first
     * throw being the charge when {@code chargeFirst}. {@code gap}: ticks between the throws (0: the
     * engine's floor); {@code pearlPath}: the pearl along its look with the world (pearl first);
     * {@code solved}: false for a macro or fallback that throws along the look without a solved catch.
     */
    public record Plan(Mode mode, boolean chargeFirst, int lead, int gap, float pearlYaw, float pearlPitch,
                       float chargeYaw, float chargePitch, double rate, WindChargeIntercept.Shot shot,
                       PearlSimulation.Path pearlPath, boolean solved, PearlCatchEnsemble.Source source) {
        public float firstYaw() { return chargeFirst ? chargeYaw : pearlYaw; }
        public float firstPitch() { return chargeFirst ? chargePitch : pearlPitch; }
        public float secondYaw() { return chargeFirst ? pearlYaw : chargeYaw; }
        public float secondPitch() { return chargeFirst ? pearlPitch : chargePitch; }
    }

    /** A live aim: the exact shot for the chosen look, its fine rate and which engine's look won. */
    public record Aim(WindChargeIntercept.Shot shot, double rate, PearlCatchEnsemble.Source source) {}

    /**
     * Both engines' votes for one throw, with the fine rate and the lattice snap the ensemble decides
     * with: what {@link PearlCatchEnsemble#decide} is handed, kept apart so the trainer can replay the
     * decision under other weights. {@code physicsShot}: the solver's exact shot.
     */
    record Ballot(PearlCatchEnsemble.Vote physics, PearlCatchEnsemble.Vote region, PearlCatchEnsemble.FineRate fine,
                  PearlCatchEnsemble.Snap snap, WindChargeIntercept.Shot physicsShot) {}

    /** How one candidate throw is rated: given the look of the aimed projectile, its expected catch rate. */
    @FunctionalInterface
    private interface Rater {
        double rate(float yaw, float pitch, boolean fine);
    }

    private final Context ctx;

    public PearlCatchPlanner(Context context) {
        this.ctx = context;
    }

    // ── Activation ───────────────────────────────────────────────────────────

    /** The plan for a press with this look: mode, order, gap and both looks. Never null. */
    public Plan plan(float yaw, float pitch) {
        if (pitch <= -MACRO_PITCH) {
            Plan up = planVerticalUp(yaw, pitch);
            if (up != null) return up;
            int gap = alongLookGap(yaw, pitch);
            return new Plan(Mode.MACRO_UP, false, 0, gap, yaw, pitch, yaw, pitch, 0.0, null, null, false, null);
        }
        if (pitch >= MACRO_PITCH) {
            // The macro holds the yaw exactly; the solver may tilt it within the vertical yaw budget,
            // which a falling shooter needs (the pearl and charge inherit the fall). The better one runs.
            Plan down = planDownMacro(yaw, pitch);
            Plan solved = planSolver(yaw, pitch);
            if (solved != null && (down == null || solved.rate() > down.rate() + PearlCatchModel.RATE_TIE)) return solved;
            if (down != null) return down;
            // No solved catch: still throw both along the look. Going down the pearl outruns the
            // charge, so the charge leaves first and the pearl runs into it from behind.
            return new Plan(Mode.MACRO_DOWN, true, 0, 0, yaw, pitch, yaw, pitch, 0.0, null, null, false, null);
        }
        Plan plan = planSolver(yaw, pitch);
        // Only a look that has no catch even in open air is moved: where terrain alone spoils the
        // catch (a wall in the way), the pearl stays where the player looks.
        if ((plan == null || plan.rate() < NUDGE_MIN_RATE) && freeFlightRate(yaw, pitch) < NUDGE_MIN_RATE) {
            Plan nudged = planNudged(yaw, pitch);
            if (nudged != null) return nudged;
        }
        if (plan != null) return plan;
        // No solved catch: the pearl still leaves along the look, the charge is aimed at it live; past
        // the order threshold the charge goes first instead, along the look.
        boolean chargeFirst = pitch >= PearlCatchModel.chargeFirstPitch(verticalSpeed());
        return new Plan(Mode.SOLVER, chargeFirst, 0, 0, yaw, pitch, yaw, pitch, 0.0, null, null, false, null);
    }

    /**
     * Straight up (within 85% of vertical): the same solver as any other look, with the yaw held near
     * the camera's ({@link #yawBudget}) so it aims by tilting the throws a few degrees instead of
     * swinging the camera round. Null when nothing catches well: the fixed up macro then runs.
     */
    private Plan planVerticalUp(float yaw, float pitch) {
        Plan plan = planSolver(yaw, pitch);
        if ((plan == null || plan.rate() < NUDGE_MIN_RATE) && freeFlightRate(yaw, pitch) < NUDGE_MIN_RATE) {
            Plan nudged = planNudged(yaw, pitch);
            if (nudged != null) plan = nudged;
        }
        return plan != null && plan.rate() >= NUDGE_MIN_RATE ? plan : null;
    }

    /**
     * Both orders for a look between the macro zones: every gap ranked in open air by its coarse rate,
     * the best {@link #FINALISTS} of each order checked against the world and aimed by both engines, then
     * the best of those by fine rate, the order bias and the Catch Time. Null when nothing catches.
     */
    public Plan planSolver(float yaw, float pitch) {
        return planSolver(yaw, pitch, null);
    }

    /** {@link #planSolver(float, float)} restricted to one order when {@code chargeFirstOnly} is set (the trainer's order sweep). */
    Plan planSolver(float yaw, float pitch, Boolean chargeFirstOnly) {
        ShooterState now = shooterIn(0);
        PearlSimulation.Path path = PearlSimulation.launched(ctx.world(), ctx.owner(), pearlSpawn(now),
                pearlLaunch(yaw, pitch, now)).trace(PEARL_TRACE_TICKS);
        double bias = PearlCatchModel.chargeFirstBias(pitch, verticalSpeed());
        Option best = null;
        PearlCatchEnsemble.Source bestSource = null;
        List<Option> pearlFirst = Boolean.TRUE.equals(chargeFirstOnly) ? List.of()
                : pearlFirstOptions(path, 0, ctx.sentYaw(), ctx.sentPitch(), maxGap(), false);
        List<Option> chargeFirst = Boolean.FALSE.equals(chargeFirstOnly) ? List.of() : chargeFirstOptions(yaw, pitch, maxGap());
        for (Option option : finalists(pearlFirst)) {
            Finished done = finishPearlFirst(option, path);
            if (done != null && prefers(done.option(), 0.0, best, biasOf(best, bias))) {
                best = done.option();
                bestSource = done.source();
            }
        }
        for (Option option : finalists(chargeFirst)) {
            Finished done = finishChargeFirst(option, yaw, pitch);
            if (done != null && prefers(done.option(), bias, best, biasOf(best, bias))) {
                best = done.option();
                bestSource = done.source();
            }
        }
        if (best == null) return null;
        WindChargeIntercept.Shot shot = best.shot();
        return new Plan(Mode.SOLVER, best.chargeFirst(), best.lead(), shot.throwAge(), yaw, pitch, shot.yaw(),
                shot.pitch(), best.rate(), shot, best.chargeFirst() ? null : path, true, bestSource);
    }

    private static double biasOf(Option option, double chargeFirstBias) {
        return option != null && option.chargeFirst() ? chargeFirstBias : 0.0;
    }

    /**
     * Blocks below the eye the best open-air catch along this look happens at: how much air a catch
     * needs. NaN when it has no catch even in open air.
     */
    public double airNeeded(float yaw, float pitch) {
        Plan open = new PearlCatchPlanner(ctx.inOpenAir()).planSolver(yaw, pitch);
        if (open == null || open.shot() == null || open.shot().catchPos() == null) return Double.NaN;
        return ctx.feet().y + ctx.eyeHeight() - open.shot().catchPos().y;
    }

    /** Best open-air rate of either order for a pearl look at {@code pitch}, over the short gaps. */
    public double freeFlightRate(float yaw, float pitch) {
        PearlCatchPlanner open = new PearlCatchPlanner(ctx.inOpenAir());
        int lastGap = Math.min(maxGap(), minGap() + NUDGE_SCAN_GAPS - 1);
        ShooterState now = open.shooterIn(0);
        PearlSimulation.Path path = PearlSimulation.ballistic(open.pearlSpawn(now), open.pearlLaunch(yaw, pitch, now), 0)
                .trace(PEARL_TRACE_TICKS);
        double best = 0.0;
        for (Option option : open.pearlFirstOptions(path, 0, yaw, pitch, lastGap, false)) best = Math.max(best, option.rate());
        for (Option option : open.chargeFirstOptions(yaw, pitch, lastGap)) best = Math.max(best, option.rate());
        return best;
    }

    /**
     * The look has no catch: moves the pearl's pitch the fewest degrees (up to {@link #NUDGE_MAX}) to a
     * look that does. Candidates are found in open air, nearest first, and only the first few are
     * planned against the world. Null when none is good enough.
     */
    private Plan planNudged(float yaw, float base) {
        List<Float> candidates = new ArrayList<>();
        for (float d = NUDGE_STEP; d <= NUDGE_MAX + 1.0e-3f && candidates.size() < NUDGE_WORLD_TRIES; d += NUDGE_STEP) {
            Float best = null;
            double bestRate = NUDGE_MIN_RATE;
            for (int sign = -1; sign <= 1; sign += 2) {
                float pitch = base + sign * d;
                // Off the macro zone, or (straight up) any tilt that stays on this side of vertical.
                if (Math.abs(pitch) > 90.0f) continue;
                if (Math.abs(pitch) >= MACRO_PITCH - 0.5f && !(base <= -MACRO_PITCH && pitch < 0.0f)) continue;
                double rate = freeFlightRate(yaw, pitch);
                if (rate >= bestRate) {
                    best = pitch;
                    bestRate = rate;
                }
            }
            if (best != null) candidates.add(best);
        }
        for (float pitch : candidates) {
            Plan plan = planSolver(yaw, pitch);
            if (plan != null && plan.rate() >= NUDGE_MIN_RATE) return plan;
        }
        return null;
    }

    /**
     * The gap for a macro that throws both items along the look (up, or down with no solved pitch):
     * the pearl along the look, the charge along the same look at every gap the floor and ceiling
     * allow, ranked as {@link #prefers} does. 0 when no gap catches (the floor then applies).
     */
    public int alongLookGap(float yaw, float pitch) {
        ShooterState now = shooterIn(0);
        PearlSimulation.Path path = PearlSimulation.launched(ctx.world(), ctx.owner(), pearlSpawn(now),
                pearlLaunch(yaw, pitch, now)).trace(PEARL_TRACE_TICKS);
        Option best = null;
        for (int gap = minGap(); gap <= maxGap(); ++gap) {
            WindChargeIntercept.Shooter shooter = chargeShooter(shooterIn(gap));
            WindChargeIntercept.Shot shot = WindChargeIntercept.evaluate(ctx.world(), ctx.owner(), path, gap,
                    shooter, yaw, pitch, catchLimit());
            if (shot == null || !confident(shot, false)) continue;
            Option option = new Option(false, 0, shot, pearlFirstRater(path, gap, shooter, false).rate(yaw, pitch, false));
            if (prefers(option, 0.0, best, 0.0)) best = option;
        }
        return best == null ? 0 : best.shot().throwAge();
    }

    /**
     * The down macro: the pearl along the look, the solved throw's pitch searched on the mouse lattice
     * with the yaw held. Charge first unless the pearl would leave faster than its terminal speed
     * (falling fast) and slow down, in which case the charge chases it as in an upward catch. The
     * solved pitch is the ensemble's (pitch-only region map). Null when nothing catches.
     */
    private Plan planDownMacro(float yaw, float pitch) {
        List<float[]> looks = pitchLattice(yaw, ctx.sentPitch(), pitch, MACRO_PITCH_WINDOW);
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(yaw, pitch);
        ShooterState now = shooterIn(0);
        double bias = PearlCatchModel.chargeFirstBias(pitch, verticalSpeed());
        PearlSimulation.Path nowPearl = pearlAlongLook(yaw, pitch, now, null, null);
        Option best = null;
        for (int gap = Math.max(1, minGap()); gap <= maxGap(); ++gap) {
            ShooterState later = shooterIn(gap);
            PearlSimulation.Path laterPearl = pearlAlongLook(yaw, pitch, later, null, null);
            WindChargeIntercept.Shot chargeFirst = WindChargeIntercept.bestOlder(null, ctx.owner(), laterPearl, gap,
                    chargeShooter(now), catchLimit(), looks, reach);
            if (chargeFirst != null && confident(chargeFirst, true)) {
                Rater rater = chargeFirstRater(yaw, pitch, 0, gap, laterPearl.lastAge(), null);
                Option option = new Option(true, 0, chargeFirst, rater.rate(chargeFirst.yaw(), chargeFirst.pitch(), false));
                if (prefers(option, bias, best, biasOf(best, bias))) best = option;
            }
            WindChargeIntercept.Shooter laterShooter = chargeShooter(later);
            WindChargeIntercept.Shot pearlFirst = WindChargeIntercept.best(null, ctx.owner(), nowPearl, gap,
                    laterShooter, catchLimit(), looks, reach);
            if (pearlFirst != null && confident(pearlFirst, false)) {
                Rater rater = pearlFirstRater(nowPearl, gap, laterShooter, false);
                Option option = new Option(false, 0, pearlFirst, rater.rate(pearlFirst.yaw(), pearlFirst.pitch(), false));
                if (prefers(option, 0.0, best, biasOf(best, bias))) best = option;
            }
        }
        if (best == null) return null;
        int gap = best.shot().throwAge();
        ShooterState later = shooterIn(gap);
        Finished done;
        if (best.chargeFirst()) {
            PearlSimulation.Path laterPearl = pearlAlongLook(yaw, pitch, later, ctx.world(), null);
            WindChargeIntercept.Shot exact = WindChargeIntercept.bestOlder(ctx.world(), ctx.owner(), laterPearl, gap,
                    chargeShooter(now), catchLimit(), looks, reach);
            if (exact == null || !confident(exact, true)) return null;
            done = aimChargeFirst(new Option(true, 0, exact, best.rate()), yaw, pitch, laterPearl, true, reach);
        } else {
            PearlSimulation.Path worldPearl = pearlAlongLook(yaw, pitch, now, ctx.world(), null);
            WindChargeIntercept.Shot exact = WindChargeIntercept.best(ctx.world(), ctx.owner(), worldPearl, gap,
                    chargeShooter(later), catchLimit(), looks, reach);
            if (exact == null || !confident(exact, false)) return null;
            done = aimPearlFirst(new Option(false, 0, exact, best.rate()), worldPearl, true, reach);
        }
        if (done == null) return null;
        WindChargeIntercept.Shot shot = done.option().shot();
        // The solved throw keeps the look's yaw exactly; only its pitch was searched.
        return new Plan(Mode.MACRO_DOWN, best.chargeFirst(), 0, gap, yaw, pitch, yaw, shot.pitch(),
                done.option().rate(), shot, null, true, done.source());
    }

    // ── Candidates ──────────────────────────────────────────────────────────

    /**
     * Pearl first: the charge throw at every tick still ahead (after {@code rel}), within the turn that
     * tick allows, solved in open air and rated by the coarse spread rules. {@code firstKnown}: the
     * pearl's real launch is in (its spawn packet), so only the charge's spread is left.
     */
    List<Option> pearlFirstOptions(PearlSimulation.Path path, int rel, float fromYaw, float fromPitch, int lastGap,
                                   boolean firstKnown) {
        List<Option> options = new ArrayList<>();
        for (int throwRel = Math.max(rel + 1, minGap()); throwRel <= lastGap; ++throwRel) {
            WindChargeIntercept.Shooter shooter = chargeShooter(shooterIn(throwRel - rel));
            WindChargeIntercept.Shot shot = WindChargeIntercept.solve(null, ctx.owner(), path, throwRel, shooter,
                    catchLimit(), chargeReach(throwRel - rel, fromYaw, fromPitch), false);
            if (shot == null || !confident(shot, false)) continue;
            double rate = pearlFirstRater(path, throwRel, shooter, firstKnown, null).rate(shot.yaw(), shot.pitch(), false);
            options.add(new Option(false, 0, shot, rate));
        }
        return options;
    }

    /**
     * Charge first: for every lead (ticks of turning before the charge leaves) and gap (ticks from the
     * charge to the pearl), the pearl along the look from the predicted shooter state and the charge
     * look that the pearl then runs into, rated in open air.
     */
    List<Option> chargeFirstOptions(float yaw, float pitch, int lastGap) {
        List<Option> options = new ArrayList<>();
        PearlSimulation.Path[] pearls = new PearlSimulation.Path[MAX_LEAD_TICKS + lastGap + 1];
        for (int lead = 0; lead <= MAX_LEAD_TICKS; ++lead) {
            WindChargeIntercept.Shooter thrower = chargeShooter(shooterIn(lead));
            WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(ctx.sentYaw(), ctx.sentPitch(),
                    (lead + 1) * ctx.flickLimit() + SENT_LOOK_TOLERANCE).withMaxYaw(yawBudget(ctx.sentPitch()));
            for (int gap = Math.max(1, minGap()); gap <= lastGap; ++gap) {
                int ticks = lead + gap;
                if (pearls[ticks] == null) pearls[ticks] = pearlAlongLook(yaw, pitch, shooterIn(ticks), null, null);
                WindChargeIntercept.Shot shot = WindChargeIntercept.solveOlder(null, ctx.owner(), pearls[ticks], gap,
                        thrower, catchLimit(), reach, false);
                if (shot == null || !confident(shot, true)) continue;
                double rate = chargeFirstRater(yaw, pitch, lead, gap, pearls[ticks].lastAge(), null)
                        .rate(shot.yaw(), shot.pitch(), false);
                options.add(new Option(true, lead, shot, rate));
            }
        }
        return options;
    }

    /** The best few options by rate, the Catch Time's pick among level ones first. */
    private List<Option> finalists(List<Option> options) {
        List<Option> sorted = new ArrayList<>(options);
        sorted.sort(Comparator.comparingDouble((Option option) -> option.rate() - horizonCost(option)).reversed());
        List<Option> picked = new ArrayList<>(FINALISTS);
        Option lead = null;
        for (Option option : sorted) if (prefers(option, 0.0, lead, 0.0)) lead = option;
        if (lead != null) picked.add(lead);
        for (Option option : sorted) {
            if (picked.size() >= FINALISTS) break;
            if (option != lead) picked.add(option);
        }
        return picked;
    }

    private record Finished(Option option, PearlCatchEnsemble.Source source) {}

    /** Pearl first: the world check of an open-air option, then both engines on the charge's look. */
    private Finished finishPearlFirst(Option option, PearlSimulation.Path path) {
        int throwRel = option.shot().throwAge();
        WindChargeIntercept.Shooter shooter = chargeShooter(shooterIn(throwRel));
        WindChargeIntercept.Reach reach = chargeReach(throwRel, ctx.sentYaw(), ctx.sentPitch());
        WindChargeIntercept.Shot exact = ctx.world() == null ? option.shot()
                : WindChargeIntercept.solve(ctx.world(), ctx.owner(), path, throwRel, shooter, catchLimit(), reach, false);
        if (exact == null || !confident(exact, false)) return null;
        return aimPearlFirst(new Option(false, 0, exact, option.rate()), path, false, reach);
    }

    /** Charge first: the world check of an open-air option, then both engines on the charge's look. */
    private Finished finishChargeFirst(Option option, float yaw, float pitch) {
        int lead = option.lead();
        int gap = option.shot().throwAge();
        WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(ctx.sentYaw(), ctx.sentPitch(),
                (lead + 1) * ctx.flickLimit() + SENT_LOOK_TOLERANCE).withMaxYaw(yawBudget(ctx.sentPitch()));
        PearlSimulation.Path pearl = pearlAlongLook(yaw, pitch, shooterIn(lead + gap), ctx.world(), null);
        WindChargeIntercept.Shot exact = ctx.world() == null ? option.shot()
                : WindChargeIntercept.solveOlder(ctx.world(), ctx.owner(), pearl, gap, chargeShooter(shooterIn(lead)),
                        catchLimit(), reach, false);
        if (exact == null || !confident(exact, true)) return null;
        return aimChargeFirst(new Option(true, lead, exact, option.rate()), yaw, pitch, pearl, false, reach);
    }

    /**
     * The charge's look for a pearl-first catch planned at activation, from both engines: the physics
     * solver's look (the option's) and the region model's robust centre, blended and validated on the
     * fine mixed rate. {@code pitchOnly}: the down macro, whose charge keeps the look's yaw.
     */
    private Finished aimPearlFirst(Option option, PearlSimulation.Path path, boolean pitchOnly, WindChargeIntercept.Reach reach) {
        WindChargeIntercept.Shot shot = option.shot();
        int throwRel = shot.throwAge();
        WindChargeIntercept.Shooter shooter = chargeShooter(shooterIn(throwRel));
        int windMoves = WindChargeIntercept.chargeMoves(ctx.world(), ctx.owner(), shooter, shot.yaw(), shot.pitch(),
                false, throwRel, catchLimit());
        Rater rater = pearlFirstRater(path, throwRel, shooter, false, ctx.world());
        CatchRegionSolver.Oracle oracle = (y, p) -> reachable(reach, y, p)
                ? WindChargeIntercept.nominalRate(path, throwRel, false, shooter, y, p, windMoves, catchLimit()) : 0.0;
        CatchRegionSolver.Region region = CatchRegionSolver.solve(shot.yaw(), shot.pitch(),
                regionWindow(shot.catchPos(), shooter.spawn()), pitchOnly, oracle);
        PearlCatchEnsemble.Decision decision = decide(shot, region, pitchOnly, rater,
                (y, p) -> snapPlanned(reach, pitchOnly ? shot.yaw() : y, p));
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluate(ctx.world(), ctx.owner(), path, throwRel, shooter,
                decision.yaw(), decision.pitch(), catchLimit());
        if (exact == null || !confident(exact, false)) {
            // The blend found a better spread rate on a look whose mean throw misses: keep the solver's.
            return new Finished(new Option(false, 0, shot, rater.rate(shot.yaw(), shot.pitch(), true)),
                    PearlCatchEnsemble.Source.PHYSICS);
        }
        return new Finished(new Option(false, 0, exact, decision.rate()), decision.source());
    }

    /**
     * The charge's look for a charge-first catch (the first throw), from both engines; the pearl that
     * follows along the look is rated over its own spread (and, mixed in, the charge's).
     * {@code pearl}: the pearl's flight from its throw, with the world.
     */
    private Finished aimChargeFirst(Option option, float yaw, float pitch, PearlSimulation.Path pearl, boolean pitchOnly,
                                    WindChargeIntercept.Reach reach) {
        WindChargeIntercept.Shot shot = option.shot();
        int lead = option.lead();
        int gap = shot.throwAge();
        ShooterState pearlState = shooterIn(lead + gap);
        WindChargeIntercept.Shooter thrower = chargeShooter(shooterIn(lead));
        int lifetime = WindChargeIntercept.charge(ctx.world(), ctx.owner(), thrower, shot.yaw(), shot.pitch(),
                chargeHorizon()).lifetime();
        int ticks = Math.max(1, pearl.lastAge());
        PearlSimulation.Path nominal = PearlSimulation.ballistic(pearlSpawn(pearlState), pearlLaunch(yaw, pitch, pearlState), 0)
                .trace(ticks);
        Rater rater = chargeFirstRater(yaw, pitch, lead, gap, ticks, lifetime);
        CatchRegionSolver.Oracle oracle = (y, p) -> reachable(reach, y, p)
                ? WindChargeIntercept.nominalPearlRate(nominal, freeCharge(thrower, y, p, lifetime), gap, catchLimit()) : 0.0;
        CatchRegionSolver.Region region = CatchRegionSolver.solve(shot.yaw(), shot.pitch(),
                regionWindow(shot.catchPos(), thrower.spawn()), pitchOnly, oracle);
        PearlCatchEnsemble.Decision decision = decide(shot, region, pitchOnly, rater,
                (y, p) -> snapPlanned(reach, pitchOnly ? shot.yaw() : y, p));
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluateOlder(ctx.world(), ctx.owner(), pearl, gap, thrower,
                decision.yaw(), decision.pitch(), catchLimit());
        if (exact == null || !confident(exact, true)) {
            return new Finished(new Option(true, lead, shot, rater.rate(shot.yaw(), shot.pitch(), true)),
                    PearlCatchEnsemble.Source.PHYSICS);
        }
        return new Finished(new Option(true, lead, exact, decision.rate()), decision.source());
    }

    /** The ensemble's decision between the physics shot and the region (either may be missing). */
    private static PearlCatchEnsemble.Decision decide(WindChargeIntercept.Shot shot, CatchRegionSolver.Region region,
                                                      boolean pitchOnly, Rater rater, PearlCatchEnsemble.Snap snap) {
        return PearlCatchEnsemble.decide(
                new PearlCatchEnsemble.Vote(shot.yaw(), shot.pitch(), shot.score()),
                region == null ? null
                        : new PearlCatchEnsemble.Vote(pitchOnly ? shot.yaw() : region.yaw(), region.pitch(), region.smoothed()),
                (y, p) -> rater.rate(y, p, true), snap);
    }

    // ── Rates ───────────────────────────────────────────────────────────────

    /**
     * Rater of the charge's look for a pearl-first catch at {@code gap} against {@code path}: the
     * charge's spread alone when the pearl's launch is known, else mixed with both spreads by
     * {@link PearlCatchModel#BLIND_SHARE}. {@code world}: for the charge's collisions (null: open air).
     */
    private Rater pearlFirstRater(PearlSimulation.Path path, int gap, WindChargeIntercept.Shooter shooter, boolean firstKnown,
                                  World world) {
        Vec3d launch = path.startAge() == 0 ? launchOf(path) : null;
        int pearlTicks = gap + catchLimit() + 2;
        return (yaw, pitch, fine) -> {
            int windMoves = WindChargeIntercept.chargeMoves(world, ctx.owner(), shooter, yaw, pitch, false, gap, catchLimit());
            double known = WindChargeIntercept.catchRate(path, gap, false, shooter, yaw, pitch, windMoves, catchLimit(), fine);
            if (firstKnown || launch == null) return known;
            double blind = WindChargeIntercept.jointCatchRate(path.start(), launch, pearlTicks, shooter, yaw, pitch,
                    windMoves, gap, false, catchLimit(), fine);
            return mix(known, blind);
        };
    }

    private Rater pearlFirstRater(PearlSimulation.Path path, int gap, WindChargeIntercept.Shooter shooter, boolean firstKnown) {
        return pearlFirstRater(path, gap, shooter, firstKnown, ctx.world());
    }

    /**
     * Rater of the charge's look for a charge-first catch: the pearl, thrown along {@code yaw}/{@code pitch}
     * {@code gap} ticks after a charge that leaves after {@code lead} ticks, over the pearl's own spread
     * (the charge's launch is in by then) mixed with both spreads. {@code lifetime}: the charge's
     * collision lifetime (null: computed per look in open air).
     */
    private Rater chargeFirstRater(float yaw, float pitch, int lead, int gap, int pearlTicks, Integer lifetime) {
        ShooterState pearlState = shooterIn(lead + gap);
        Vec3d spawn = pearlSpawn(pearlState);
        Vec3d launch = pearlLaunch(yaw, pitch, pearlState);
        WindChargeIntercept.Shooter thrower = chargeShooter(shooterIn(lead));
        return (chargeYaw, chargePitch, fine) -> {
            int moves = lifetime != null ? lifetime
                    : WindChargeIntercept.charge(null, ctx.owner(), thrower, chargeYaw, chargePitch, chargeHorizon()).lifetime();
            WindChargeIntercept.Charge charge = freeCharge(thrower, chargeYaw, chargePitch, moves);
            double known = WindChargeIntercept.pearlCatchRate(spawn, launch, Math.max(1, pearlTicks), charge, gap,
                    catchLimit(), fine);
            double blind = WindChargeIntercept.jointCatchRate(spawn, launch, Math.max(1, pearlTicks), charge.spawn(),
                    charge.velocity(), moves, gap, true, catchLimit(), fine);
            return mix(known, blind);
        };
    }

    private static double mix(double known, double blind) {
        return (1.0 - PearlCatchModel.BLIND_SHARE) * known + PearlCatchModel.BLIND_SHARE * blind;
    }

    /** The launch velocity of a pearl path that starts at its throw: one tick of gravity and drag undone. */
    static Vec3d launchOf(PearlSimulation.Path path) {
        PearlSimulation.Segment first = path.at(path.startAge() + 1);
        if (first == null) return null;
        Vec3d v = first.velocity().multiply(1.0 / (double) PearlSimulation.DRAG);
        return new Vec3d(v.x, v.y + PearlSimulation.GRAVITY, v.z);
    }

    private static WindChargeIntercept.Charge freeCharge(WindChargeIntercept.Shooter thrower, float yaw, float pitch, int lifetime) {
        return new WindChargeIntercept.Charge(thrower.spawn(), WindChargeIntercept.launch(thrower, yaw, pitch), lifetime);
    }

    /** Rate a plan gives up per tick the shooter has to be forecast past the first. */
    private double horizonCost(Option option) {
        int ticks = option.lead() + option.shot().throwAge() - 1;
        if (ticks <= 0) return 0.0;
        double horizontal = Math.sqrt(ctx.movement().x * ctx.movement().x + ctx.movement().z * ctx.movement().z);
        boolean settled = ctx.onGround() && horizontal < STANDING_SPEED;
        return ticks * (settled ? PearlCatchModel.HORIZON_COST_GROUND : PearlCatchModel.HORIZON_COST_AIR);
    }

    // ── Live (between the throws) ────────────────────────────────────────────

    /**
     * Pearl first, at {@code rel} ticks after the pearl: the best charge throw at any tick still ahead,
     * world-checked and rated on the fine rule, for the timing (is a better tick ahead?). Null when none.
     */
    public Option nextChargeTick(PearlSimulation.Path pearl, int rel, boolean firstKnown) {
        List<Option> options = pearlFirstOptions(pearl, rel, ctx.sentYaw(), ctx.sentPitch(), maxGap(), firstKnown);
        options.sort(Comparator.comparingDouble(Option::rate).reversed());
        Option best = null;
        int checks = 0;
        for (Option option : options) {
            if (checks++ >= WORLD_CHECKS) break;
            int throwRel = option.shot().throwAge();
            WindChargeIntercept.Shooter shooter = chargeShooter(shooterIn(throwRel - rel));
            WindChargeIntercept.Shot exact = ctx.world() == null ? option.shot()
                    : WindChargeIntercept.solve(ctx.world(), ctx.owner(), pearl, throwRel, shooter, catchLimit(),
                            chargeReach(throwRel - rel, ctx.sentYaw(), ctx.sentPitch()), false);
            if (exact == null || !confident(exact, false)) continue;
            // Rated on the fine rule, as the throw it is weighed against (aimCharge) is.
            double rate = pearlFirstRater(pearl, throwRel, shooter, firstKnown).rate(exact.yaw(), exact.pitch(), true);
            Option checked = new Option(false, 0, exact, rate);
            if (prefers(checked, 0.0, best, 0.0)) best = checked;
        }
        return best;
    }

    /**
     * Pearl first, throwing now ({@code rel} ticks after the pearl): the charge's look on the mouse
     * lattice within {@link #SENT_LOOK_TOLERANCE} of the sent look, from both engines. {@code planLook}
     * (may be null) is snapped in as an extra physics candidate. {@code firstKnown}: {@code pearl} is the
     * real pearl (its spawn packet is in); otherwise it is the forecast and its spread is rated in.
     * Null when no look catches.
     */
    public Aim aimCharge(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, float[] planLook,
                         boolean firstKnown) {
        Ballot ballot = chargeBallot(pearl, rel, shooter, planLook, firstKnown);
        return ballot == null ? null : settleCharge(pearl, rel, shooter, ballot);
    }

    /** The votes for {@link #aimCharge}; null when the solver finds no catch. */
    Ballot chargeBallot(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, float[] planLook,
                        boolean firstKnown) {
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(ctx.sentYaw(), ctx.sentPitch());
        WindChargeIntercept.Shot physics = latticeSearch(planLook,
                looks -> WindChargeIntercept.best(ctx.world(), ctx.owner(), pearl, rel, shooter, catchLimit(), looks, reach));
        if (physics == null || !confident(physics, false)) return null;
        int windMoves = WindChargeIntercept.chargeMoves(ctx.world(), ctx.owner(), shooter, physics.yaw(), physics.pitch(),
                false, rel, catchLimit());
        CatchRegionSolver.Region region = CatchRegionSolver.solveLattice(ctx.sentYaw(), ctx.sentPitch(), lookStep(),
                latticeSpan(), false, firstKnown ? 1.0 : BLIND_SPREAD_SCALE,
                (y, p) -> WindChargeIntercept.nominalRate(pearl, rel, false, shooter, y, p, windMoves, catchLimit()));
        return ballot(physics, region, false, liveChargeRater(pearl, rel, shooter, firstKnown), this::snapLive);
    }

    /**
     * {@link #aimCharge} for the down macro's second throw: the yaw held at {@code yaw}, the pitch within
     * {@code window} degrees of {@code centerPitch} on the lattice of the sent pitch.
     */
    public Aim aimChargePitch(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, float yaw,
                              float centerPitch, float window, boolean firstKnown) {
        List<float[]> looks = pitchLattice(yaw, ctx.sentPitch(), centerPitch, window);
        WindChargeIntercept.Shot physics = WindChargeIntercept.best(ctx.world(), ctx.owner(), pearl, rel, shooter,
                catchLimit(), looks, WindChargeIntercept.Reach.from(yaw, centerPitch));
        if (physics == null || !confident(physics, false)) return null;
        int windMoves = WindChargeIntercept.chargeMoves(ctx.world(), ctx.owner(), shooter, yaw, physics.pitch(),
                false, rel, catchLimit());
        CatchRegionSolver.Region region = CatchRegionSolver.solve(yaw, centerPitch, window, true,
                firstKnown ? 1.0 : BLIND_SPREAD_SCALE,
                (y, p) -> WindChargeIntercept.nominalRate(pearl, rel, false, shooter, yaw, p, windMoves, catchLimit()));
        Ballot ballot = ballot(physics, region, true, liveChargeRater(pearl, rel, shooter, firstKnown),
                (y, p) -> new float[]{yaw, snapPitch(p)});
        return settleCharge(pearl, rel, shooter, ballot);
    }

    /** The ensemble's pick for a pearl-first charge, checked exactly; the solver's shot if the pick's mean throw misses. */
    private Aim settleCharge(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, Ballot ballot) {
        PearlCatchEnsemble.Decision decision = PearlCatchEnsemble.decide(ballot.physics(), ballot.region(), ballot.fine(), ballot.snap());
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluate(ctx.world(), ctx.owner(), pearl, rel, shooter,
                decision.yaw(), decision.pitch(), catchLimit());
        if (exact == null || !confident(exact, false)) {
            WindChargeIntercept.Shot physics = ballot.physicsShot();
            return new Aim(physics, ballot.fine().rate(physics.yaw(), physics.pitch()), PearlCatchEnsemble.Source.PHYSICS);
        }
        return new Aim(exact, decision.rate(), decision.source());
    }

    private static Ballot ballot(WindChargeIntercept.Shot physics, CatchRegionSolver.Region region, boolean pitchOnly,
                                 Rater rater, PearlCatchEnsemble.Snap snap) {
        return new Ballot(new PearlCatchEnsemble.Vote(physics.yaw(), physics.pitch(), physics.score()),
                region == null ? null
                        : new PearlCatchEnsemble.Vote(pitchOnly ? physics.yaw() : region.yaw(), region.pitch(), region.smoothed()),
                (y, p) -> rater.rate(y, p, true), snap, physics);
    }

    /** Live pearl-first rater: the charge's spread on the real pearl, or both spreads on the forecast. */
    private Rater liveChargeRater(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, boolean firstKnown) {
        Vec3d launch = !firstKnown && pearl.startAge() == 0 ? launchOf(pearl) : null;
        int pearlTicks = rel + catchLimit() + 2;
        return (yaw, pitch, fine) -> {
            int windMoves = WindChargeIntercept.chargeMoves(ctx.world(), ctx.owner(), shooter, yaw, pitch, false, rel, catchLimit());
            if (launch == null) {
                return WindChargeIntercept.catchRate(pearl, rel, false, shooter, yaw, pitch, windMoves, catchLimit(), fine);
            }
            return WindChargeIntercept.jointCatchRate(pearl.start(), launch, pearlTicks, shooter, yaw, pitch, windMoves,
                    rel, false, catchLimit(), fine);
        };
    }

    /** Rate of a charge thrown now with this exact look against the pearl's flight (fine rule). */
    public double chargeRateNow(PearlSimulation.Path pearl, int rel, WindChargeIntercept.Shooter shooter, float yaw, float pitch,
                                boolean firstKnown) {
        return liveChargeRater(pearl, rel, shooter, firstKnown).rate(yaw, pitch, true);
    }

    /**
     * Charge first, at {@code rel} ticks after the charge: the best pearl throw along the look at any
     * tick still ahead, from the predicted shooter state, in open air and rated on the fine rule, for the
     * timing. Null when none.
     */
    public Option nextPearlTick(WindChargeIntercept.Charge charge, int rel, float yaw, float pitch, boolean firstKnown) {
        Option best = null;
        for (int gap = Math.max(rel + 1, minGap()); gap <= maxGap(); ++gap) {
            ShooterState state = shooterIn(gap - rel);
            Vec3d spawn = pearlSpawn(state);
            Vec3d launch = pearlLaunch(yaw, pitch, state);
            PearlSimulation.Path path = PearlSimulation.ballistic(spawn, launch, 0).trace(catchLimit() + 1);
            WindChargeIntercept.Shot shot = WindChargeIntercept.evaluatePearl(path, launch, yaw, pitch, charge, gap, catchLimit());
            if (shot == null || !confident(shot, true)) continue;
            double rate = livePearlRate(spawn, launch, catchLimit() + 1, charge, gap, firstKnown, true);
            Option option = new Option(true, 0, shot, rate);
            if (prefers(option, 0.0, best, 0.0)) best = option;
        }
        return best;
    }

    /**
     * Charge first, throwing the pearl now ({@code rel} ticks after the charge): the pearl's look on the
     * lattice near the sent look, from both engines. A non-null {@code pitchWindow} is the down macro:
     * the yaw held at {@code lookYaw} and the pitch within that many degrees of {@code lookPitch}.
     * {@code firstKnown}: {@code charge} is the real charge (its spawn packet is in). {@code ignored}: the
     * client copy of the catch's own charge. Null when no look catches.
     */
    public Aim aimPearl(WindChargeIntercept.Charge charge, int rel, float lookYaw, float lookPitch, Float pitchWindow,
                        boolean firstKnown, Entity ignored) {
        Ballot ballot = pearlBallot(charge, rel, lookYaw, lookPitch, pitchWindow, firstKnown, ignored);
        if (ballot == null) return null;
        PearlCatchEnsemble.Decision decision = PearlCatchEnsemble.decide(ballot.physics(), ballot.region(), ballot.fine(), ballot.snap());
        WindChargeIntercept.Shot exact = pearlShot(decision.yaw(), decision.pitch(), rel, charge, ignored);
        if (exact == null || !confident(exact, true)) {
            WindChargeIntercept.Shot physics = ballot.physicsShot();
            return new Aim(physics, ballot.fine().rate(physics.yaw(), physics.pitch()), PearlCatchEnsemble.Source.PHYSICS);
        }
        return new Aim(exact, decision.rate(), decision.source());
    }

    /** The votes for {@link #aimPearl}; null when the solver finds no catch. */
    Ballot pearlBallot(WindChargeIntercept.Charge charge, int rel, float lookYaw, float lookPitch, Float pitchWindow,
                       boolean firstKnown, Entity ignored) {
        ShooterState now = shooterIn(0);
        Vec3d spawn = pearlSpawn(now);
        boolean pitchOnly = pitchWindow != null;
        WindChargeIntercept.Reach reach = pitchOnly ? WindChargeIntercept.Reach.from(lookYaw, lookPitch)
                : WindChargeIntercept.Reach.from(ctx.sentYaw(), ctx.sentPitch());
        Function<List<float[]>, WindChargeIntercept.Shot> scorer = candidates -> {
            List<WindChargeIntercept.Shot> found = new ArrayList<>();
            for (float[] look : candidates) {
                Vec3d launch = pearlLaunch(look[0], look[1], now);
                PearlSimulation.Path path = PearlSimulation.ballistic(spawn, launch, 0).trace(catchLimit() + 1);
                WindChargeIntercept.Shot shot = WindChargeIntercept.evaluatePearl(path, launch, look[0], look[1], charge, rel, catchLimit());
                if (shot != null) found.add(shot);
            }
            for (int i = 0; i < WORLD_CHECKS && !found.isEmpty(); ++i) {
                WindChargeIntercept.Shot best = null;
                for (WindChargeIntercept.Shot shot : found) best = WindChargeIntercept.better(best, shot, reach);
                found.remove(best);
                WindChargeIntercept.Shot exact = pearlShot(best.yaw(), best.pitch(), rel, charge, ignored);
                if (exact != null) return exact;
            }
            return null;
        };
        WindChargeIntercept.Shot physics = pitchOnly
                ? scorer.apply(pitchLattice(lookYaw, ctx.sentPitch(), lookPitch, pitchWindow))
                : latticeSearch(new float[]{lookYaw, lookPitch}, scorer);
        if (physics == null || !confident(physics, true)) return null;
        int ticks = flightTicks(physics.yaw(), physics.pitch(), ignored);
        CatchRegionSolver.Oracle oracle = (y, p) -> {
            PearlSimulation.Path path = PearlSimulation.ballistic(spawn, pearlLaunch(pitchOnly ? lookYaw : y, p, now), 0).trace(ticks);
            return WindChargeIntercept.nominalPearlRate(path, charge, rel, catchLimit());
        };
        double scale = firstKnown ? 1.0 : BLIND_SPREAD_SCALE;
        CatchRegionSolver.Region region = pitchOnly
                ? CatchRegionSolver.solve(lookYaw, lookPitch, pitchWindow, true, scale, oracle)
                : CatchRegionSolver.solveLattice(ctx.sentYaw(), ctx.sentPitch(), lookStep(), latticeSpan(), false, scale, oracle);
        Rater rater = (y, p, fine) -> livePearlRate(spawn, pearlLaunch(y, p, now), ticks, charge, rel, firstKnown, fine);
        return ballot(physics, region, pitchOnly, rater,
                (y, p) -> pitchOnly ? new float[]{lookYaw, snapPitch(p)} : snapLive(y, p));
    }

    /** Live charge-first rate: the pearl's spread against the real charge, or both spreads against the forecast. */
    private double livePearlRate(Vec3d spawn, Vec3d launch, int ticks, WindChargeIntercept.Charge charge, int gap,
                                 boolean firstKnown, boolean fine) {
        if (firstKnown) return WindChargeIntercept.pearlCatchRate(spawn, launch, ticks, charge, gap, catchLimit(), fine);
        return WindChargeIntercept.jointCatchRate(spawn, launch, ticks, charge.spawn(), charge.velocity(), charge.lifetime(),
                gap, true, catchLimit(), fine);
    }

    /** Charge first: the exact outcome of the pearl thrown now with this look, with the world. */
    public WindChargeIntercept.Shot pearlShot(float yaw, float pitch, int rel, WindChargeIntercept.Charge charge, Entity ignored) {
        ShooterState now = shooterIn(0);
        Vec3d launch = pearlLaunch(yaw, pitch, now);
        PearlSimulation.Path path = PearlSimulation.launched(ctx.world(), ctx.owner(), pearlSpawn(now), launch)
                .ignoring(ignored).trace(catchLimit() + 1);
        return WindChargeIntercept.evaluatePearl(path, launch, yaw, pitch, charge, rel, catchLimit());
    }

    /** Ticks the pearl thrown now with this look flies before a block stops it (capped at the search). */
    private int flightTicks(float yaw, float pitch, Entity ignored) {
        ShooterState now = shooterIn(0);
        PearlSimulation.Path path = PearlSimulation.launched(ctx.world(), ctx.owner(), pearlSpawn(now),
                pearlLaunch(yaw, pitch, now)).ignoring(ignored).trace(catchLimit() + 1);
        return Math.max(1, path.lastAge());
    }

    // ── Ranking ─────────────────────────────────────────────────────────────

    /**
     * True when {@code candidate} (with {@code candidateBias} added to its rate) is the better catch than
     * {@code current} (with {@code currentBias}), each less its horizon cost: the higher rate wins; level
     * rates (within {@link PearlCatchModel#RATE_TIE}) go to the flight that suits the Catch Time (the
     * longest within it, else the shortest past it), then to the higher rate.
     */
    boolean prefers(Option candidate, double candidateBias, Option current, double currentBias) {
        if (current == null) return candidate != null;
        if (candidate == null) return false;
        double a = candidate.rate() + candidateBias - horizonCost(candidate);
        double b = current.rate() + currentBias - horizonCost(current);
        if (Math.abs(a - b) > PearlCatchModel.RATE_TIE) return a > b;
        int target = ctx.catchTime();
        int fa = candidate.flight();
        int fb = current.flight();
        boolean withinA = fa <= target;
        boolean withinB = fb <= target;
        if (withinA != withinB) return withinA;
        if (fa != fb) return withinA ? fa > fb : fa < fb;
        return a > b + 1.0e-9;
    }

    /**
     * {@code pearlLater}: the pearl is the later throw, so its age is the flight from the second throw.
     * Any catch within the search limit passes: the rate ranks catches rather than gating them.
     */
    boolean confident(WindChargeIntercept.Shot shot, boolean pearlLater) {
        int flight = pearlLater ? shot.catchAge() : shot.catchAge() - shot.throwAge();
        return flight >= 1 && flight <= catchLimit();
    }

    // ── Looks and lattice ───────────────────────────────────────────────────

    /** Yaw a planned throw may swing from {@code pitch}: unlimited off vertical, a few degrees near it. */
    public static float yawBudget(float pitch) {
        return Math.abs(pitch) >= YAW_BUDGET_PITCH ? VERTICAL_YAW_BUDGET : Float.POSITIVE_INFINITY;
    }

    private WindChargeIntercept.Reach chargeReach(int ticks, float fromYaw, float fromPitch) {
        return new WindChargeIntercept.Reach(fromYaw, fromPitch, ticks * ctx.flickLimit() + SENT_LOOK_TOLERANCE)
                .withMaxYaw(yawBudget(fromPitch));
    }

    private static boolean reachable(WindChargeIntercept.Reach reach, float yaw, float pitch) {
        if (reach.turn(yaw, pitch) > reach.maxTurn() + 1.0e-3f) return false;
        return Math.abs(MathHelper.wrapDegrees(yaw - reach.yaw())) <= reach.maxYaw() + 1.0e-3f;
    }

    /** Region window for a catch at {@code catchPos} thrown from {@code from}: a few catch boxes of arc. */
    private static double regionWindow(Vec3d catchPos, Vec3d from) {
        double distance = catchPos == null || from == null ? 10.0 : Math.max(1.0, catchPos.distanceTo(from));
        return MathHelper.clamp(Math.toDegrees(REGION_BOXES * CATCH_RADIUS / distance), REGION_MIN, REGION_MAX);
    }

    /** A planned look snapped onto the lattice of the sent look and pulled into reach. */
    private float[] snapPlanned(WindChargeIntercept.Reach reach, float yaw, float pitch) {
        float[] reached = reach.clamp(yaw, pitch);
        double step = lookStep();
        float snappedYaw = ctx.sentYaw() + (float) (Math.round(MathHelper.wrapDegrees(reached[0] - ctx.sentYaw()) / step) * step);
        float snappedPitch = ctx.sentPitch() + (float) (Math.round((reached[1] - ctx.sentPitch()) / step) * step);
        return new float[]{snappedYaw, MathHelper.clamp(snappedPitch, -90.0f, 90.0f)};
    }

    /** A live look snapped onto the lattice, within the sent-look tolerance. */
    private float[] snapLive(float yaw, float pitch) {
        double step = lookStep();
        int span = latticeSpan();
        int i = latticeIndex(MathHelper.wrapDegrees(yaw - ctx.sentYaw()), step, span);
        int j = latticeIndex(pitch - ctx.sentPitch(), step, span);
        return latticeLook(ctx.sentYaw(), ctx.sentPitch(), i, j, step);
    }

    /** A pitch snapped onto the lattice of the sent pitch. */
    private float snapPitch(float pitch) {
        double step = lookStep();
        return MathHelper.clamp(ctx.sentPitch() + (float) (Math.round((pitch - ctx.sentPitch()) / step) * step), -90.0f, 90.0f);
    }

    private int latticeSpan() {
        return (int) Math.floor(SENT_LOOK_TOLERANCE / lookStep());
    }

    /**
     * Best look on the mouse lattice within the sent-look tolerance of the sent look: a coarse pass (plus
     * {@code planLook} snapped onto the lattice), then every step around the coarse best.
     */
    WindChargeIntercept.Shot latticeSearch(float[] planLook, Function<List<float[]>, WindChargeIntercept.Shot> scorer) {
        float baseYaw = ctx.sentYaw();
        float basePitch = ctx.sentPitch();
        double step = lookStep();
        int span = latticeSpan();
        int stride = Math.max(1, (span + 3) / 4);
        List<float[]> looks = new ArrayList<>();
        for (int i = -span; i <= span; i += stride) {
            for (int j = -span; j <= span; j += stride) {
                looks.add(latticeLook(baseYaw, basePitch, i, j, step));
            }
        }
        if (planLook != null) {
            looks.add(latticeLook(baseYaw, basePitch,
                    latticeIndex(MathHelper.wrapDegrees(planLook[0] - baseYaw), step, span),
                    latticeIndex(planLook[1] - basePitch, step, span), step));
        }
        WindChargeIntercept.Shot best = scorer.apply(looks);
        if (best == null || stride == 1) return best;
        int ci = latticeIndex(MathHelper.wrapDegrees(best.yaw() - baseYaw), step, span);
        int cj = latticeIndex(best.pitch() - basePitch, step, span);
        looks.clear();
        for (int i = Math.max(-span, ci - stride + 1); i <= Math.min(span, ci + stride - 1); ++i) {
            for (int j = Math.max(-span, cj - stride + 1); j <= Math.min(span, cj + stride - 1); ++j) {
                looks.add(latticeLook(baseYaw, basePitch, i, j, step));
            }
        }
        WindChargeIntercept.Shot fine = scorer.apply(looks);
        return fine != null && fine.score() >= best.score() ? fine : best;
    }

    /**
     * Looks with this yaw and a pitch on the mouse lattice of the sent pitch, within {@code window}
     * degrees of {@code centerPitch}: what a pitch-only step from the sent look can land on.
     */
    public List<float[]> pitchLattice(float yaw, float sentPitch, float centerPitch, float window) {
        double step = lookStep();
        int low = (int) Math.ceil((Math.max(-90.0f, centerPitch - window) - sentPitch) / step);
        int high = (int) Math.floor((Math.min(90.0f, centerPitch + window) - sentPitch) / step);
        List<float[]> looks = new ArrayList<>(Math.max(1, high - low + 1));
        for (int j = low; j <= high; ++j) {
            looks.add(latticeLook(yaw, sentPitch, 0, j, step));
        }
        if (looks.isEmpty()) looks.add(new float[]{yaw, MathHelper.clamp(centerPitch, -90.0f, 90.0f)});
        return looks;
    }

    private static float[] latticeLook(float baseYaw, float basePitch, int i, int j, double step) {
        return new float[]{baseYaw + (float) (i * step), MathHelper.clamp(basePitch + (float) (j * step), -90.0f, 90.0f)};
    }

    private static int latticeIndex(float delta, double step, int span) {
        return MathHelper.clamp((int) Math.round(delta / step), -span, span);
    }

    private double lookStep() {
        return ctx.lookStep() > 0.0 ? ctx.lookStep() : FREE_LOOK_STEP;
    }

    // ── Shooter state ───────────────────────────────────────────────────────

    /**
     * The shooter when a use sent {@code ticks} client ticks from now is handled: the feet then, and
     * the movement {@code ServerPlayerEntity#getMovement} reports (the last sent move). The fall
     * follows player gravity and drag and stops on {@link Context#groundY}; horizontal motion is held.
     */
    public ShooterState shooterIn(int ticks) {
        Vec3d feet = ctx.feet();
        Vec3d motion = ctx.movement();
        if (ticks <= 0) return new ShooterState(feet, motion, ctx.onGround());
        double y = feet.y;
        double dy = motion.y;
        boolean onGround = ctx.onGround();
        if (!onGround) {
            double vy = ctx.velocityY();
            boolean ground = !Double.isNaN(ctx.groundY());
            for (int i = 0; i < ticks; ++i) {
                if (onGround) {
                    dy = 0.0;
                    continue;
                }
                double next = y + vy;
                if (ground && vy < 0.0 && next <= ctx.groundY()) {
                    dy = ctx.groundY() - y;
                    y = ctx.groundY();
                    onGround = true;
                    continue;
                }
                y = next;
                dy = vy;
                vy = (vy - PLAYER_GRAVITY) * PLAYER_VERTICAL_DRAG;
            }
        }
        return new ShooterState(new Vec3d(feet.x + motion.x * ticks, y, feet.z + motion.z * ticks),
                new Vec3d(motion.x, dy, motion.z), onGround);
    }

    public WindChargeIntercept.Shooter chargeShooter(ShooterState state) {
        return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(state.feet(), ctx.eyeHeight()),
                state.movement(), state.onGround());
    }

    public Vec3d pearlSpawn(ShooterState state) {
        return ThrowPhysics.pearlSpawn(state.feet(), ctx.eyeHeight());
    }

    public Vec3d pearlLaunch(float yaw, float pitch, ShooterState state) {
        return ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.PEARL_POWER, null, state.movement(), state.onGround());
    }

    /**
     * The pearl along this look from this shooter state, traced with {@code world} (null: open air)
     * far enough for a charge thrown before it or up to the longest gap after it.
     */
    public PearlSimulation.Path pearlAlongLook(float yaw, float pitch, ShooterState state, World world, Entity ignored) {
        return PearlSimulation.launched(world, ctx.owner(), pearlSpawn(state), pearlLaunch(yaw, pitch, state))
                .ignoring(ignored).trace(catchLimit() + maxGap() + 2);
    }

    /** Vertical movement the order table is read at: the last move's, in the air; 0 on the ground. */
    public double verticalSpeed() {
        return ctx.onGround() ? 0.0 : ctx.movement().y;
    }

    /** Moves the charge must be followed for: the longest gap plus the longest catch. */
    public int chargeHorizon() {
        return catchLimit() + maxGap() + 2;
    }

    /** Longest flight any plan searches: the Catch Time plus the overshoot taken when nothing is sooner. */
    public int catchLimit() {
        return Math.min(CATCH_LIMIT_CAP, ctx.catchTime() + CATCH_OVERSHOOT);
    }

    /** First tick (after the first throw) the second throw may leave on, never past the latest gap. */
    public int minGap() {
        return Math.min(Math.max(1, maxGap()), Math.max(1, ctx.gapFloor()));
    }

    public int maxGap() {
        return Math.max(1, ctx.maxGap());
    }
}
