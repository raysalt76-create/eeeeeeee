package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Planning and per-tick decision engine for the stun slam (axe the shield, then mace smash).
 *
 * <p>The engine decides <i>when</i> the mace follow-up leaves, never <i>how</i>: the swap goes through
 * {@code SlotSync}, the click through {@code AttackValidator}, and both stay inside the tick's action
 * window ({@link TickActionOrder}). It does not touch the hotbar, rotations or packets.
 *
 * <h2>Timeline (client ticks, {@code 0} = the tick the axe click left in)</h2>
 * <pre>
 *   tick 0       select axe  ->  axe click                      (same tick, swap strictly first)
 *   tick 1..g    wait; each tick head re-asks {@link #decide}
 *   tick g       select mace ->  mace click                     (same tick, swap strictly first)
 *   tick g+1     restore the weapon slot                        (mace stays visibly held for one tick)
 * </pre>
 * The mace never shares the axe's movement-packet interval, so the earliest follow-up is tick 1.
 *
 * <h2>Why a forecast, not a fixed count</h2>
 * A fixed axe-to-mace gap is only right for one fall speed and one geometry. Past the planned tick the
 * diver has fallen through the target or the target has been moved out from under the line, which is
 * where the old fixed gaps missed. The engine therefore keeps three separate things:
 * <ul>
 *   <li><b>Profile ceiling</b> - the most ticks Adaptive mode may ever wait, from the vanilla downward
 *       speed (4 / 3 / 2 / 1 ticks) halved beyond {@value #HORIZONTAL_HALVING_DISTANCE} horizontal
 *       blocks. This is the user-facing "profile".</li>
 *   <li><b>Plan</b> - the largest gap up to that ceiling whose forecast hit (own fall simulated with
 *       world collisions, target by the shared motion forecast, reach tested against both the drawn box
 *       and the box the server will hold) still clears {@link #MIN_CONFIDENCE}. Waiting longer is only
 *       worth it while the hit survives, which is what "max ticks possible" means here.</li>
 *   <li><b>Per-tick decision</b> - the plan is not trusted blindly: every tick head the engine re-asks
 *       whether the next tick still holds, and fires now (when the live ray is valid) if the window is
 *       closing. A mace that cannot hit is never waited on past {@link #LATE_GRACE_TICKS}.</li>
 * </ul>
 *
 * <p>Everything here is a pure function of its inputs except {@link #observe}, which reads the live
 * client. Nothing in this class is evidence of wire behaviour: it is forecast logic, labelled
 * UNVERIFIED until captured in-game.
 */
public final class StunSlamEngine {

    // ---- Timeline ----
    /** The fastest legal axe-to-mace gap, in client ticks: the very next tick head. */
    public static final int MIN_GAP_TICKS = 1;
    /** Hardest cap on any follow-up gap and on how long a slam waits for its mace, in client ticks. */
    public static final int MAX_FOLLOW_UP_TICKS = 6;
    /** Ticks past the planned gap that a live-invalid follow-up keeps retrying before the slam unwinds. */
    public static final int LATE_GRACE_TICKS = 3;
    /** Extra forecast ticks beyond {@link #MAX_FOLLOW_UP_TICKS} so server-lead lookups never run dry. */
    private static final int HORIZON_SLACK = 4;

    // ---- Profile (user-facing buckets) ----
    // The last part of each band is where a late follow-up is most likely to miss, so each bucket edge
    // sits below the old edge: five percent for the faster bands, a larger margin for the slowest.
    private static final double FOUR_TICK_MAX_SPEED = 0.95 * 0.65;
    private static final double THREE_TICK_MAX_SPEED = 1.45 * 0.85;
    private static final double TWO_TICK_MAX_SPEED = 1.70 * 0.85;
    /** Beyond this many horizontal blocks the profile gap is halved (integer floor). */
    public static final double HORIZONTAL_HALVING_DISTANCE = 2.0;
    /** Fallback only: at or beyond this 3D distance the close-range reduction changes nothing. */
    private static final double CLOSE_START_DISTANCE = 2.7;
    /** Fallback only: at or inside this 3D distance the close-range reduction is at its maximum. */
    private static final double CLOSE_FULL_DISTANCE = 1.0;
    private static final int CLOSE_MAX_REDUCTION = 2;

    // ---- Hit model ----
    /** Vanilla melee reach used for the forecast, blocks. */
    public static final double REACH = 3.0;
    /** Inside the drawn reach by this much to count as a hit that will not flicker at the edge. */
    static final double CLIENT_REACH_MARGIN = 0.10;
    /** Same server margin the registration check uses. */
    static final double SERVER_REACH_MARGIN = InterceptPredictor.SERVER_MARGIN;
    /** Weighted share of forecast futures that must hold the hit for a gap to be chosen. */
    public static final double MIN_CONFIDENCE = 0.65;
    /** Fall distance the smash needs on the server (vanilla: strictly more than 1.5). */
    public static final double MIN_FALL_DISTANCE = 1.5;
    /** Turn the aim is assumed able to make per client tick, degrees, for the alignment factor. */
    static final double TURN_DEGREES_PER_TICK = 38.0;
    /** Half the hitbox width used for the angular tolerance of the alignment factor. */
    private static final double HITBOX_HALF_WIDTH = 0.30;

    // ---- Aim ----
    /** The pre-aim point stays this far inside every face of the current hitbox. */
    static final double AIM_INSET = 0.08;
    /** How far the follow-up aim point is pulled from the forecast side back toward the box centre. */
    static final double AIM_CORE_PULL = 0.35;

    private StunSlamEngine() {
    }

    // ───────────────────────────── Profile ─────────────────────────────

    /**
     * The profile ceiling for the vanilla downward speed {@code velocityY} (blocks/tick, negative while
     * falling): 4 / 3 / 2 ticks, or 1 for very fast falls, then halved with integer floor beyond
     * {@value #HORIZONTAL_HALVING_DISTANCE} horizontal blocks. Never below {@value #MIN_GAP_TICKS}:
     * one tick is the fastest follow-up vanilla can send (the mace's hotbar change cannot share the
     * axe's movement-packet interval).
     */
    public static int profileCeiling(double velocityY, double horizontalDistance) {
        double downwardSpeed = Math.max(0.0, -velocityY);
        int gap;
        if (downwardSpeed < FOUR_TICK_MAX_SPEED) {
            gap = 4;
        } else if (downwardSpeed < THREE_TICK_MAX_SPEED) {
            gap = 3;
        } else if (downwardSpeed < TWO_TICK_MAX_SPEED) {
            gap = 2;
        } else {
            gap = MIN_GAP_TICKS;
        }
        if (Double.isFinite(horizontalDistance) && horizontalDistance > HORIZONTAL_HALVING_DISTANCE) {
            gap /= 2;
        }
        return Math.max(MIN_GAP_TICKS, gap);
    }

    /**
     * Ticks a purely geometric rule would remove for a short 3D distance: none from 2.7 blocks outward,
     * rising linearly (rounded) to two ticks at one block and closer. Used only when no forecast is
     * available; the planner's own geometry replaces it otherwise.
     */
    public static int closeRangeReduction(double distance3d) {
        if (!Double.isFinite(distance3d) || distance3d >= CLOSE_START_DISTANCE) {
            return 0;
        }
        double t = (CLOSE_START_DISTANCE - distance3d) / (CLOSE_START_DISTANCE - CLOSE_FULL_DISTANCE);
        t = Math.min(1.0, Math.max(0.0, t));
        return (int) Math.round(t * CLOSE_MAX_REDUCTION);
    }

    /** The profile gap with the geometric close-range reduction, never below one tick (forecast-less fallback). */
    public static int fallbackGap(double velocityY, double horizontalDistance, double distance3d) {
        return Math.max(MIN_GAP_TICKS,
                profileCeiling(velocityY, horizontalDistance) - closeRangeReduction(distance3d));
    }

    // ───────────────────────────── Data ─────────────────────────────

    /**
     * One weighted guess at the target. {@code visible[k]} is the box as this client will hold it at the
     * head of tick {@code k} after the axe tick; {@code server[k]} is the box the server will hold when
     * an attack sent at that head arrives (the same forecast, shifted by the round trip).
     */
    public record Hypothesis(String name, double weight, Box[] visible, Box[] server) {
    }

    /**
     * Everything {@link #plan} and {@link #decide} read. Index {@code k} is the head of the k-th client
     * tick counted from now (index 0), so {@code eyes[1]} is where the eye is when the next tick runs.
     *
     * @param grounded  {@code grounded[k]} - the fall has ended by that head (a landing resets the fall
     *                  distance, so there is no smash left to deliver)
     * @param yaw       the view yaw now, degrees
     * @param pitch     the view pitch now, degrees
     */
    public record Outlook(Vec3d[] eyes, boolean[] grounded, List<Hypothesis> targets, float yaw, float pitch) {
        public int horizon() {
            return eyes.length - 1;
        }

        /** The box of the heaviest hypothesis at head {@code k}, or null when there is none. */
        public Box likeliestVisible(int k) {
            Hypothesis best = null;
            for (Hypothesis h : targets) {
                if (best == null || h.weight() > best.weight()) {
                    best = h;
                }
            }
            return best == null ? null : at(best.visible(), k);
        }
    }

    /**
     * Fixed per-slam inputs.
     *
     * @param exact        Normal mode: fire exactly {@code exactGap} ticks after the axe
     * @param exactGap     the configured gap for Normal mode (>= 1)
     * @param ceiling      Adaptive: the profile ceiling in ticks (>= 1)
     * @param floor        Adaptive: the lowest gap the user allows while it still holds (>= 1)
     * @param reach        melee reach, blocks (3.0, or 4.5 under spear reach)
     * @param registration whether the server-side box must also be inside reach
     */
    public record Params(boolean exact, int exactGap, int ceiling, int floor, double reach, boolean registration) {
        public static Params normal(int gap, double reach, boolean registration) {
            return new Params(true, Math.max(1, gap), Math.max(1, gap), 1, reach, registration);
        }

        public static Params adaptive(int ceiling, int floor, double reach, boolean registration) {
            return new Params(false, 1, Math.max(MIN_GAP_TICKS, ceiling), Math.max(MIN_GAP_TICKS, floor),
                    reach, registration);
        }
    }

    /** Why the plan picked its gap. */
    public enum Source {
        /** Normal mode: the configured gap, as set. */
        EXACT,
        /** Adaptive: the largest gap up to the ceiling that still holds. */
        ADAPTIVE_MAX,
        /** Adaptive: nothing at or above the floor holds; the best gap seen under the ceiling. */
        BEST_EFFORT
    }

    /** The chosen follow-up gap and the confidence of every candidate tick ({@code byTick[k]}, k >= 1). */
    public record Plan(int gap, double confidence, double[] byTick, Source source) {
    }

    public enum Verdict { WAIT, FIRE, ABANDON }

    public record Decision(Verdict verdict, String why) {
        static Decision wait(String why) {
            return new Decision(Verdict.WAIT, why);
        }

        static Decision fire(String why) {
            return new Decision(Verdict.FIRE, why);
        }

        static Decision abandon(String why) {
            return new Decision(Verdict.ABANDON, why);
        }
    }

    // ───────────────────────────── Planner ─────────────────────────────

    /**
     * Chooses the axe-to-mace gap. Normal mode keeps the configured gap exactly. Adaptive mode starts at
     * the floor and keeps extending the gap while every tick up to it clears {@link #MIN_CONFIDENCE},
     * stopping at the ceiling: the last tick of that unbroken run is the gap. A tick that holds only after
     * a dip is never chosen, because {@link #decide} fires as soon as the next tick stops holding, so a
     * plan across the dip could not be waited out. When the floor itself does not hold, it takes the
     * best-scoring gap under the ceiling (earliest on a near tie), because a hit that is likely sooner
     * beats one that is unlikely later.
     */
    public static Plan plan(Outlook outlook, Params params) {
        int max = Math.max(1, Math.min(MAX_FOLLOW_UP_TICKS, outlook.horizon()));
        double[] byTick = new double[max + 1];
        for (int k = 1; k <= max; k++) {
            byTick[k] = confidence(outlook, params, k);
        }
        if (params.exact()) {
            int gap = MathHelper.clamp(params.exactGap(), 1, max);
            return new Plan(gap, byTick[gap], byTick, Source.EXACT);
        }
        int ceiling = MathHelper.clamp(params.ceiling(), 1, max);
        int floor = MathHelper.clamp(params.floor(), 1, ceiling);
        if (byTick[floor] >= MIN_CONFIDENCE) {
            int g = floor;
            while (g < ceiling && byTick[g + 1] >= MIN_CONFIDENCE) {
                g++;
            }
            return new Plan(g, byTick[g], byTick, Source.ADAPTIVE_MAX);
        }
        int best = 1;
        for (int k = 2; k <= ceiling; k++) {
            if (byTick[k] > byTick[best] + 0.02) {
                best = k;
            }
        }
        return new Plan(best, byTick[best], byTick, Source.BEST_EFFORT);
    }

    /** Weighted share of forecast futures in which a click at the head of tick {@code k} hits. */
    public static double confidence(Outlook outlook, Params params, int k) {
        int idx = Math.min(Math.max(0, k), outlook.eyes().length - 1);
        if (k > 0 && outlook.grounded()[idx]) {
            return 0.0;
        }
        Vec3d eye = outlook.eyes()[idx];
        double reach = params.reach() - CLIENT_REACH_MARGIN;
        double serverReach = params.reach() - SERVER_REACH_MARGIN;
        double total = 0.0;
        double weights = 0.0;
        for (Hypothesis h : outlook.targets()) {
            weights += h.weight();
            Box visible = at(h.visible(), k);
            if (visible == null || distance(eye, visible) > reach) {
                continue;
            }
            if (params.registration()) {
                Box server = at(h.server(), k);
                if (server != null && distance(eye, server) > serverReach) {
                    continue;
                }
            }
            total += h.weight() * alignment(outlook, eye, visible, k);
        }
        return weights <= 0.0 ? 0.0 : MathHelper.clamp(total / weights, 0.0, 1.0);
    }

    /**
     * 1 when the view can be on the box by head {@code k}, falling to 0 as the turn needed outgrows what
     * {@code k} ticks of aim can do. Only matters after a fly-over, when the target is behind the view.
     */
    static double alignment(Outlook outlook, Vec3d eye, Box box, int k) {
        Vec3d centre = box.getCenter();
        Vec3d toCentre = centre.subtract(eye);
        double distance = toCentre.length();
        if (distance < 1.0e-4 || box.contains(eye)) {
            return 1.0;
        }
        Vec3d look = lookVector(outlook.yaw(), outlook.pitch());
        double dot = MathHelper.clamp(look.dotProduct(toCentre.multiply(1.0 / distance)), -1.0, 1.0);
        double errorDeg = Math.toDegrees(Math.acos(dot));
        double toleranceDeg = Math.toDegrees(Math.atan2(HITBOX_HALF_WIDTH, Math.max(0.3, distance)));
        double capacity = TURN_DEGREES_PER_TICK * Math.max(0, k) + toleranceDeg;
        if (errorDeg <= capacity) {
            return 1.0;
        }
        double span = capacity * 0.6 + 1.0;
        return MathHelper.clamp(1.0 - (errorDeg - capacity) / span, 0.0, 1.0);
    }

    // ───────────────────────────── Per-tick decision ─────────────────────────────

    /**
     * Asked at every tick head after the axe tick.
     *
     * <p>Adaptive mode has exactly two reasons to click: the planned gap is reached, or the forecast
     * says the next tick no longer holds the hit (which also overrides the floor, since the floor is only
     * a preference while the hit holds). It has no fall-speed or distance shortcut: the fall speed is
     * already in the profile ceiling and in the simulated fall, and a shortcut that fires on the first
     * tick turns Adaptive into a fixed one-tick gap.
     *
     * @param params          the slam's fixed inputs
     * @param plan            the plan made when the axe left
     * @param live            a fresh outlook, index 0 = this tick head
     * @param ticksSinceAxe   ticks since the axe (>= 1 on the first tick after it), in the unit the plan
     *                        was made in: plan index {@code k} is the k-th tick head after the axe's
     *                        own movement interval
     * @param liveHitValid    the live ray from the current authoritative angles is on the target, in
     *                        reach, and the vanilla gates allow the click right now
     */
    public static Decision decide(Params params, Plan plan, Outlook live, int ticksSinceAxe, boolean liveHitValid) {
        if (ticksSinceAxe < 1) {
            return Decision.wait("axe interval");
        }
        int gap = Math.max(1, plan.gap());
        int abandonAfter = Math.min(MAX_FOLLOW_UP_TICKS, Math.max(gap + LATE_GRACE_TICKS, 4));

        if (params.exact()) {
            if (ticksSinceAxe < gap) {
                return Decision.wait("gap " + ticksSinceAxe + "/" + gap);
            }
            if (liveHitValid) {
                return Decision.fire("configured gap reached");
            }
            return ticksSinceAxe > abandonAfter ? Decision.abandon("no valid hit by tick " + ticksSinceAxe)
                    : Decision.wait("gap reached, ray not valid yet");
        }

        if (!liveHitValid) {
            return ticksSinceAxe > abandonAfter ? Decision.abandon("no valid hit by tick " + ticksSinceAxe)
                    : Decision.wait("ray not valid yet");
        }
        if (ticksSinceAxe >= gap) {
            return Decision.fire("planned gap reached");
        }
        double next = live.horizon() >= 1 ? confidence(live, params, 1) : 0.0;
        String nextPct = Math.round(next * 100.0) + "%";
        if (next < MIN_CONFIDENCE) {
            return Decision.fire("window closing (next tick " + nextPct + ")");
        }
        int floor = Math.max(1, Math.min(params.floor(), gap));
        if (ticksSinceAxe < floor) {
            return Decision.wait("below floor " + ticksSinceAxe + "/" + floor + " (next tick " + nextPct + ")");
        }
        return Decision.wait("window holds (next tick " + nextPct + ")");
    }

    // ───────────────────────────── Aim ─────────────────────────────

    /**
     * Where the follow-up aim should sit. The strike is a single committed click, so the point leans
     * to the box core (not the edge a legit-looking tracker prefers) and toward where the target will
     * be at the next head, clamped inside the CURRENT box so the ray is valid now as well as then.
     *
     * @param current  the target's box now
     * @param forecast the box at the next head (may be null)
     */
    public static Vec3d corePoint(Box current, Box forecast) {
        Vec3d centre = current.getCenter();
        if (forecast == null) {
            return centre;
        }
        Box inner = current.contract(AIM_INSET, AIM_INSET, AIM_INSET);
        Vec3d lean = forecast.getCenter();
        Vec3d clamped = new Vec3d(
                MathHelper.clamp(lean.x, inner.minX, inner.maxX),
                MathHelper.clamp(lean.y, inner.minY, inner.maxY),
                MathHelper.clamp(lean.z, inner.minZ, inner.maxZ));
        return clamped.lerp(centre, AIM_CORE_PULL);
    }

    /**
     * The aim point that keeps ONE view direction valid for both clicks of the slam. At high fall
     * speeds the eye is a long way lower by the mace tick, so a point chosen only for the axe has to be
     * re-aimed in the ~45 ms between the two heads, which a bounded rotation cannot do. This picks, from
     * a grid inside the current box, the point whose direction from the current eye hits the box as it
     * will be at the axe head AND at the mace head, with the largest margin; the direction is then held
     * by the ordinary rotation controller with no snap needed between the clicks.
     *
     * @param eyeNow   the eye now (the camera rotates about this)
     * @param axeEye   the eye at the head the axe leaves from
     * @param axeBox   the target box at that head
     * @param maceEye  the eye at the head the mace leaves from
     * @param maceBox  the target box at that head
     * @param current  the target box now (candidate points stay inside it)
     * @param reach    melee reach, blocks
     * @return the world point to aim at, or null when no direction is valid for both
     */
    public static Vec3d dualClickAimPoint(Vec3d eyeNow, Vec3d axeEye, Box axeBox, Vec3d maceEye, Box maceBox,
                                          Box current, double reach) {
        if (eyeNow == null || axeEye == null || axeBox == null || maceEye == null || maceBox == null
                || current == null) {
            return null;
        }
        Box inner = current.contract(AIM_INSET, AIM_INSET, AIM_INSET);
        Vec3d centre = inner.getCenter();
        Vec3d best = null;
        double bestMargin = -1.0;
        double bestCentreDistance = Double.MAX_VALUE;
        for (int ix = 0; ix < DUAL_GRID_XZ; ix++) {
            for (int iy = 0; iy < DUAL_GRID_Y; iy++) {
                for (int iz = 0; iz < DUAL_GRID_XZ; iz++) {
                    Vec3d p = new Vec3d(
                            lerpGrid(inner.minX, inner.maxX, ix, DUAL_GRID_XZ),
                            lerpGrid(inner.minY, inner.maxY, iy, DUAL_GRID_Y),
                            lerpGrid(inner.minZ, inner.maxZ, iz, DUAL_GRID_XZ));
                    Vec3d toPoint = p.subtract(eyeNow);
                    double length = toPoint.length();
                    if (length < 1.0e-3) {
                        continue;
                    }
                    Vec3d dir = toPoint.multiply(1.0 / length);
                    double margin = Math.min(rayMargin(axeEye, dir, axeBox, reach),
                            rayMargin(maceEye, dir, maceBox, reach));
                    double toCentre = p.squaredDistanceTo(centre);
                    if (margin > bestMargin + 1.0e-9
                            || (Math.abs(margin - bestMargin) <= 1.0e-9 && toCentre < bestCentreDistance)) {
                        bestMargin = margin;
                        bestCentreDistance = toCentre;
                        best = p;
                    }
                }
            }
        }
        return bestMargin >= 0.0 ? best : null;
    }

    /** Candidate points per horizontal axis and on the vertical axis of the dual-click grid. */
    static final int DUAL_GRID_XZ = 5;
    static final int DUAL_GRID_Y = 7;
    /** Margins tried, largest first: how far the box is shrunk and the ray still hits. */
    private static final double[] DUAL_MARGINS = {0.16, 0.12, 0.08, 0.04, 0.0};

    /**
     * The largest shrink of {@code box} (blocks per face) that the segment from {@code eye} along
     * {@code dir}, {@code reach} long, still hits; -1 when it misses the unshrunk box.
     */
    static double rayMargin(Vec3d eye, Vec3d dir, Box box, double reach) {
        Vec3d end = eye.add(dir.multiply(reach));
        for (double margin : DUAL_MARGINS) {
            double sx = Math.min(margin, box.getLengthX() * 0.45);
            double sy = Math.min(margin, box.getLengthY() * 0.45);
            double sz = Math.min(margin, box.getLengthZ() * 0.45);
            Box shrunk = box.contract(sx, sy, sz);
            if (shrunk.contains(eye) || shrunk.raycast(eye, end).isPresent()) {
                return margin;
            }
        }
        return -1.0;
    }

    private static double lerpGrid(double min, double max, int index, int count) {
        if (count <= 1) {
            return (min + max) * 0.5;
        }
        return min + (max - min) * index / (count - 1);
    }

    // ───────────────────────────── Live observation ─────────────────────────────

    /**
     * Builds the outlook from the live client: the local fall is simulated with the shared motion model
     * (world collisions, current velocity, the horizontal input the player is evidently holding), the
     * target comes from the shared weighted forecasts, shifted by the connection round trip for the
     * server-side box. Falls back to constant velocity when no forecast exists.
     */
    public static Outlook observe(MinecraftClient mc, PlayerEntity target, int serverLead) {
        ClientPlayerEntity me = mc.player;
        int horizon = MAX_FOLLOW_UP_TICKS + 1;
        Vec3d[] eyes = new Vec3d[horizon + 1];
        boolean[] grounded = new boolean[horizon + 1];
        Vec3d pos = new Vec3d(me.getX(), me.getY(), me.getZ());
        double eyeOffset = me.getEyeY() - me.getY();
        eyes[0] = pos.add(0.0, eyeOffset, 0.0);
        Vec3d velocity = me.getVelocity();
        Vec3d horizontal = new Vec3d(velocity.x, 0.0, velocity.z);
        Vec3d input = horizontal.lengthSquared() > 0.03 * 0.03 ? horizontal.normalize() : Vec3d.ZERO;
        PlayerMotionSimulation sim = PlayerMotionSimulation.of(mc.world, me);
        PlayerMotionSimulation.State state = new PlayerMotionSimulation.State(pos, velocity, me.isOnGround(),
                me.isOnGround() ? PlayerMotionSimulation.Mode.GROUND : PlayerMotionSimulation.Mode.AIR);
        PlayerMotionSimulation.Behavior behavior = new PlayerMotionSimulation.Behavior(me.getYaw(), me.getPitch(),
                input, me.isSprinting(),
                me.isSprinting() ? PlayerMotionSimulation.SPRINT_SPEED : PlayerMotionSimulation.WALK_SPEED,
                false, 0);
        for (int k = 1; k <= horizon; k++) {
            state = sim.step(state, behavior, false);
            eyes[k] = state.pos().add(0.0, eyeOffset, 0.0);
            grounded[k] = state.onGround();
        }

        int need = horizon + Math.max(0, serverLead) + HORIZON_SLACK;
        List<Hypothesis> hypotheses = new ArrayList<>(3);
        PlayerMotionSimulation.Prediction prediction = PlayerMotionSimulation.predict(mc.world, target, need);
        long now = PlayerTrajectoryEngine.getInstance().currentTick();
        int age = (int) MathHelper.clamp(now - prediction.observedTick(), 0L, 8L);
        for (PlayerMotionSimulation.Forecast f : prediction.forecasts()) {
            Box[] boxes = f.boxes();
            if (boxes == null || boxes.length == 0) {
                continue;
            }
            Box[] visible = new Box[horizon + 1];
            Box[] server = new Box[horizon + 1];
            for (int k = 0; k <= horizon; k++) {
                visible[k] = at(boxes, age + k);
                server[k] = at(boxes, age + k + Math.max(0, serverLead));
            }
            hypotheses.add(new Hypothesis(f.name(), f.weight(), visible, server));
        }
        if (hypotheses.isEmpty()) {
            Box[] visible = new Box[horizon + 1];
            Box[] server = new Box[horizon + 1];
            for (int k = 0; k <= horizon; k++) {
                visible[k] = CombatPrediction.predictBox(target, k);
                server[k] = CombatPrediction.predictBox(target, k + Math.max(0, serverLead));
            }
            hypotheses.add(new Hypothesis("hold", 1.0, visible, server));
        }
        return new Outlook(eyes, grounded, hypotheses, me.getYaw(), me.getPitch());
    }

    // ───────────────────────────── Helpers ─────────────────────────────

    private static Box at(Box[] boxes, int k) {
        if (boxes == null || boxes.length == 0) {
            return null;
        }
        return boxes[Math.min(Math.max(0, k), boxes.length - 1)];
    }

    /** Distance from {@code p} to the nearest point of {@code box}; 0 when inside. */
    static double distance(Vec3d p, Box box) {
        double dx = p.x - MathHelper.clamp(p.x, box.minX, box.maxX);
        double dy = p.y - MathHelper.clamp(p.y, box.minY, box.maxY);
        double dz = p.z - MathHelper.clamp(p.z, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Entity#getRotationVector(pitch, yaw). */
    static Vec3d lookVector(float yaw, float pitch) {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(-yaw);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3d(Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }
}
