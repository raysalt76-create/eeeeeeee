package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Backend interception engine for the falling mace smash.
 *
 * <p><b>Phase 1, always on, cheap:</b> every client tick each other player within
 * {@link #TRACK_RANGE} blocks gets a short history of the server-sent (tracked) position, from which a
 * smoothed velocity is fitted. A noise-free forecast of both bodies (the target under gravity, drag or
 * glide; the local player in free fall) finds the tick of closest approach.</p>
 *
 * <p><b>Phase 2, only when contact is near:</b> once the forecast puts the closest approach within
 * {@link #DEEP_WINDOW_TICKS} (500 ms) and within a few blocks of reach, an ensemble of
 * {@link #DEEP_SAMPLES} perturbed futures is run. Each draw jitters the fitted velocities by their
 * measured uncertainty and adds per-tick input noise (air steering, ground jumps) to both bodies. A
 * draw counts as a hit when, on some tick, the local eye is within reach of the target box as the
 * client sees it AND within reach of the box as the server will hold it when the attack arrives
 * (shifted by the connection latency), and the fall is deep enough for a smash. The fraction of
 * draws that hit is the confidence; the mean of their unit aim directions is the pre-aim direction.</p>
 *
 * <p>This class only forecasts. Rotation still goes through {@code RotationManager}, and whether an
 * attack is sent is still decided by the live ray and reach at the moment of the click. All calls run
 * on the client thread.</p>
 */
public final class InterceptPredictor {

    /** Players farther than this are not tracked at all. */
    public static final double TRACK_RANGE = 50.0;
    /** The ensemble only starts once the forecast contact is this close: 10 ticks = 500 ms. */
    public static final int DEEP_WINDOW_TICKS = 10;
    /** Vanilla entity attack reach. */
    public static final double REACH = 3.0;
    /** The server-side box must be this far inside reach, so latency rounding cannot flip a marginal hit. */
    public static final double SERVER_MARGIN = 0.05;
    /** Smash needs a fall strictly greater than this (vanilla {@code MaceItem}). */
    public static final double MACE_MIN_FALL = 1.5;

    static final int COARSE_HORIZON = 40;
    static final int DEEP_HORIZON_MAX = 24;
    static final int DEEP_SAMPLES = 160;
    static final int FIT_WINDOW = 8;
    static final int MAX_LAG_TICKS = 6;
    /** Contact more than this far beyond reach at the closest tick is not worth an ensemble. */
    static final double DEEP_TRIGGER_SLACK = 1.5;

    static final double GRAVITY = 0.08;
    static final double VERTICAL_DRAG = 0.98;
    /** Between "keys released" (0.91) and "keys held" (about 1.0): the noise term covers both. */
    static final double AIR_RETENTION = 0.94;
    static final double JUMP_VELOCITY = 0.42;
    static final double CHEST_FRACTION = 0.65;
    static final double VIS_OFFSET_DECAY_TICKS = 3.0;
    static final double NO_FLOOR = -1.0e9;

    private static final InterceptPredictor INSTANCE = new InterceptPredictor();

    public enum Mode { GROUND, AIR, GLIDE }

    /**
     * Everything the forecast needs, in plain numbers.
     *
     * @param myPos       local feet position now
     * @param myVel       local per-tick displacement for the NEXT tick (vanilla {@code getVelocity()})
     * @param eyeHeight   eye offset above the feet
     * @param fallNow     vanilla fall distance now (mirrors the server's)
     * @param groundY     height of the surface below the local player ({@link #NO_FLOOR} if none)
     * @param tgtPos      target feet position on the server timeline now (tracked, fitted)
     * @param tgtVel      target per-tick displacement
     * @param tgtVisOffset rendered position minus {@code tgtPos}; decays as the render catches up
     * @param halfWidth   target hitbox half width
     * @param height      target hitbox height
     * @param tgtMode     how the target moves
     * @param velSigmaH   one-sigma uncertainty of the target's horizontal velocity, blocks/tick
     * @param velSigmaV   one-sigma uncertainty of the target's vertical velocity, blocks/tick
     * @param lagTicks    round-trip latency in ticks: how far the server box is ahead of the tracked one
     * @param tgtFloorY   surface the target lands on ({@link #NO_FLOOR} if unknown)
     */
    public record Inputs(Vec3d myPos, Vec3d myVel, double eyeHeight, double fallNow, double groundY,
                         Vec3d tgtPos, Vec3d tgtVel, Vec3d tgtVisOffset, double halfWidth, double height,
                         Mode tgtMode, double velSigmaH, double velSigmaV, int lagTicks, double tgtFloorY) {}

    /**
     * One forecast.
     *
     * @param deep          true when the ensemble ran; the confidence below is only meaningful then
     * @param confidence    fraction of ensemble draws that produce a registrable hit, 0..1
     * @param interceptTick median tick (from now) of the first hit among hitting draws, -1 if none
     * @param aimDirection  unit direction from the future eye to the future target chest, averaged over
     *                      hitting draws; null when no draw hit
     * @param closestTick   tick of the closest approach on the noise-free forecast
     * @param closest       that closest approach, blocks (visual and latency-shifted box, worse of the two)
     */
    public record Result(UUID target, long computedTick, boolean deep, double confidence,
                         int interceptTick, Vec3d aimDirection, int closestTick, double closest) {}

    // ───────────────────────────── pure forecast core ─────────────────────────────

    /** Runs the coarse forecast and, when contact is near and {@code allowDeep}, the ensemble. */
    public static Result evaluate(UUID id, long tick, Inputs in, boolean allowDeep, long seed) {
        int lag = MathHelper.clamp(in.lagTicks(), 0, MAX_LAG_TICKS);
        int n = COARSE_HORIZON + 1;
        double[] mx = new double[n], my = new double[n], mz = new double[n];
        double[] tx = new double[n + lag], ty = new double[n + lag], tz = new double[n + lag];
        int valid = simulateMe(in, null, mx, my, mz, n);
        simulateTarget(in, null, tx, ty, tz, n + lag);

        double closest = Double.MAX_VALUE;
        int closestTick = -1;
        for (int j = 0; j < valid; j++) {
            double d = Math.max(visDistance(in, j, mx, my, mz, tx, ty, tz),
                    serverDistance(in, j, lag, mx, my, mz, tx, ty, tz) + SERVER_MARGIN);
            if (d < closest) {
                closest = d;
                closestTick = j;
            }
        }
        boolean near = closestTick >= 0 && closestTick <= DEEP_WINDOW_TICKS
                && closest <= REACH + DEEP_TRIGGER_SLACK;
        if (!allowDeep || !near) {
            return new Result(id, tick, false, 0.0, -1, null, closestTick, closest);
        }
        return runEnsemble(id, tick, in, lag, Math.min(DEEP_HORIZON_MAX, closestTick + 6), seed,
                closestTick, closest);
    }

    private static Result runEnsemble(UUID id, long tick, Inputs in, int lag, int horizon, long seed,
                                      int closestTick, double closest) {
        int n = horizon + 1;
        double[] mx = new double[n], my = new double[n], mz = new double[n];
        double[] tx = new double[n + lag], ty = new double[n + lag], tz = new double[n + lag];
        int[] firstHitHistogram = new int[n];
        SplittableRandom rng = new SplittableRandom(seed);
        double sumX = 0, sumY = 0, sumZ = 0;
        int hits = 0;

        for (int s = 0; s < DEEP_SAMPLES; s++) {
            int valid = simulateMe(in, rng, mx, my, mz, n);
            simulateTarget(in, rng, tx, ty, tz, n + lag);
            for (int j = 0; j < valid; j++) {
                if (!registers(in, j, lag, mx, my, mz, tx, ty, tz)) {
                    continue;
                }
                hits++;
                firstHitHistogram[j]++;
                double ex = mx[j], ey = my[j] + in.eyeHeight(), ez = mz[j];
                double decay = visDecay(j);
                double px = tx[j] + in.tgtVisOffset().x * decay;
                double py = ty[j] + in.tgtVisOffset().y * decay + in.height() * CHEST_FRACTION;
                double pz = tz[j] + in.tgtVisOffset().z * decay;
                double dx = px - ex, dy = py - ey, dz = pz - ez;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 1.0e-6) {
                    sumX += dx / len;
                    sumY += dy / len;
                    sumZ += dz / len;
                }
                break;
            }
        }
        double confidence = hits / (double) DEEP_SAMPLES;
        if (hits == 0) {
            return new Result(id, tick, true, 0.0, -1, null, closestTick, closest);
        }
        int median = 0;
        int seen = 0;
        for (int j = 0; j < n; j++) {
            seen += firstHitHistogram[j];
            if (seen * 2 >= hits) {
                median = j;
                break;
            }
        }
        Vec3d mean = new Vec3d(sumX, sumY, sumZ);
        Vec3d dir = mean.lengthSquared() > 1.0e-9 ? mean.normalize() : null;
        return new Result(id, tick, true, confidence, median, dir, closestTick, closest);
    }

    /** True when an attack on opportunity {@code j} would pass the client's reach AND register on the server. */
    private static boolean registers(Inputs in, int j, int lag, double[] mx, double[] my, double[] mz,
                                     double[] tx, double[] ty, double[] tz) {
        double fall = in.fallNow() + (my[0] - my[j]);
        if (fall <= MACE_MIN_FALL + 0.02) {
            return false;
        }
        return visDistance(in, j, mx, my, mz, tx, ty, tz) <= REACH
                && serverDistance(in, j, lag, mx, my, mz, tx, ty, tz) <= REACH - SERVER_MARGIN;
    }

    private static double visDecay(int j) {
        return Math.max(0.0, 1.0 - j / VIS_OFFSET_DECAY_TICKS);
    }

    /** Eye to the target box as the local client draws it on opportunity {@code j}. */
    private static double visDistance(Inputs in, int j, double[] mx, double[] my, double[] mz,
                                      double[] tx, double[] ty, double[] tz) {
        double decay = visDecay(j);
        return eyeToBox(mx[j], my[j] + in.eyeHeight(), mz[j],
                tx[j] + in.tgtVisOffset().x * decay, ty[j] + in.tgtVisOffset().y * decay,
                tz[j] + in.tgtVisOffset().z * decay, in.halfWidth(), in.height());
    }

    /** Eye to the box the server holds when the attack sent on opportunity {@code j} arrives. */
    private static double serverDistance(Inputs in, int j, int lag, double[] mx, double[] my, double[] mz,
                                         double[] tx, double[] ty, double[] tz) {
        int k = j + lag;
        return eyeToBox(mx[j], my[j] + in.eyeHeight(), mz[j], tx[k], ty[k], tz[k], in.halfWidth(), in.height());
    }

    /** Euclidean distance from an eye to an upright box standing at (cx, feetY, cz). */
    static double eyeToBox(double ex, double ey, double ez, double cx, double feetY, double cz,
                           double halfWidth, double height) {
        double dx = ex - MathHelper.clamp(ex, cx - halfWidth, cx + halfWidth);
        double dy = ey - MathHelper.clamp(ey, feetY, feetY + height);
        double dz = ez - MathHelper.clamp(ez, cz - halfWidth, cz + halfWidth);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Free fall of the local player. Index {@code j} is the position after {@code j} movements, i.e.
     * the position an attack at the head of tick {@code j + 1} is sent from. Returns how many indices
     * are still airborne; landing ends the fall and so ends smash eligibility.
     */
    static int simulateMe(Inputs in, SplittableRandom rng, double[] x, double[] y, double[] z, int n) {
        double px = in.myPos().x, py = in.myPos().y, pz = in.myPos().z;
        double vx = in.myVel().x, vy = in.myVel().y, vz = in.myVel().z;
        if (rng != null) {
            vx += rng.nextDouble(-0.02, 0.02);
            vz += rng.nextDouble(-0.02, 0.02);
        }
        for (int j = 0; j < n; j++) {
            x[j] = px;
            y[j] = py;
            z[j] = pz;
            if (py <= in.groundY() + 1.0e-6 && j > 0) {
                return j;
            }
            px += vx;
            py += vy;
            pz += vz;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            vx *= AIR_RETENTION;
            vz *= AIR_RETENTION;
            if (rng != null) {
                vx += gauss(rng) * 0.015;
                vz += gauss(rng) * 0.015;
            }
            if (py < in.groundY()) {
                py = in.groundY();
            }
        }
        return n;
    }

    /** Target path on the server timeline; index {@code k} is {@code k} ticks after the tracked position. */
    static void simulateTarget(Inputs in, SplittableRandom rng, double[] x, double[] y, double[] z, int n) {
        double px = in.tgtPos().x, py = in.tgtPos().y, pz = in.tgtPos().z;
        double vx = in.tgtVel().x, vy = in.tgtVel().y, vz = in.tgtVel().z;
        Mode mode = in.tgtMode();
        if (rng != null) {
            vx += gauss(rng) * in.velSigmaH();
            vz += gauss(rng) * in.velSigmaH();
            vy += gauss(rng) * in.velSigmaV();
        }
        for (int k = 0; k < n; k++) {
            x[k] = px;
            y[k] = py;
            z[k] = pz;
            switch (mode) {
                case GROUND -> {
                    if (rng != null) {
                        vx += gauss(rng) * 0.02;
                        vz += gauss(rng) * 0.02;
                        if (rng.nextDouble() < 0.05) {
                            mode = Mode.AIR;
                            vy = JUMP_VELOCITY;
                        }
                    }
                    px += vx;
                    pz += vz;
                    py += mode == Mode.AIR ? vy : 0.0;
                    if (mode == Mode.AIR) {
                        vy = (vy - GRAVITY) * VERTICAL_DRAG;
                    }
                }
                case AIR -> {
                    px += vx;
                    py += vy;
                    pz += vz;
                    vy = (vy - GRAVITY) * VERTICAL_DRAG;
                    vx *= AIR_RETENTION;
                    vz *= AIR_RETENTION;
                    if (rng != null) {
                        vx += gauss(rng) * 0.015;
                        vz += gauss(rng) * 0.015;
                    }
                    if (vy <= 0.0 && py <= in.tgtFloorY()) {
                        py = in.tgtFloorY();
                        mode = Mode.GROUND;
                        vy = 0.0;
                    }
                }
                case GLIDE -> {
                    px += vx;
                    py += vy;
                    pz += vz;
                    if (rng != null) {
                        vx += gauss(rng) * 0.03;
                        vy += gauss(rng) * 0.03;
                        vz += gauss(rng) * 0.03;
                    }
                }
            }
        }
    }

    private static double gauss(SplittableRandom rng) {
        return rng.nextGaussian();
    }

    // ───────────────────────────── per-tick tracking ─────────────────────────────

    private static final class Track {
        final long[] ticks = new long[FIT_WINDOW];
        final double[] xs = new double[FIT_WINDOW];
        final double[] ys = new double[FIT_WINDOW];
        final double[] zs = new double[FIT_WINDOW];
        int count;
        double lastGroundY = NO_FLOOR;
        /** Fitted target state on the server timeline now. */
        Vec3d base = Vec3d.ZERO;
        Vec3d velocity = Vec3d.ZERO;
        double sigmaH = 0.15;
        double sigmaV = 0.15;
        Result result;

        void add(long tick, Vec3d p) {
            if (count == FIT_WINDOW) {
                System.arraycopy(ticks, 1, ticks, 0, FIT_WINDOW - 1);
                System.arraycopy(xs, 1, xs, 0, FIT_WINDOW - 1);
                System.arraycopy(ys, 1, ys, 0, FIT_WINDOW - 1);
                System.arraycopy(zs, 1, zs, 0, FIT_WINDOW - 1);
                count--;
            }
            ticks[count] = tick;
            xs[count] = p.x;
            ys[count] = p.y;
            zs[count] = p.z;
            count++;
        }
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private long tick;
    private int lastPlayerAge = Integer.MIN_VALUE;
    private int lagTicks;

    private InterceptPredictor() {}

    public static InterceptPredictor getInstance() {
        return INSTANCE;
    }

    public void clear() {
        tracks.clear();
        lastPlayerAge = Integer.MIN_VALUE;
    }

    /** Round-trip latency in whole ticks, as of the last tick. */
    public int lagTicks() {
        return lagTicks;
    }

    /**
     * Advances tracking by one client tick (safe to call from several modules: it acts once per world
     * tick). {@code forecast} false keeps the cheap tracking only, e.g. while grounded.
     */
    public void onTick(MinecraftClient mc, boolean forecast) {
        if (mc == null || mc.world == null || mc.player == null) {
            clear();
            return;
        }
        if (mc.player.age == lastPlayerAge) {
            return;
        }
        lastPlayerAge = mc.player.age;
        tick++;
        lagTicks = readLagTicks(mc);

        PlayerEntity me = mc.player;
        boolean falling = forecast && !me.isOnGround() && !me.isGliding() && me.getVelocity().y < -0.01;
        double groundY = falling ? groundBelow(mc) : NO_FLOOR;
        List<UUID> alive = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == me || !p.isAlive() || p.isRemoved() || me.squaredDistanceTo(p) > TRACK_RANGE * TRACK_RANGE) {
                continue;
            }
            UUID id = p.getUuid();
            alive.add(id);
            Track track = tracks.computeIfAbsent(id, k -> new Track());
            if (p.isOnGround()) {
                track.lastGroundY = p.getY();
            }
            track.add(tick, p.getTrackedPosition().getPos());
            fit(track, p);
            if (falling) {
                track.result = evaluate(id, tick, inputsFor(me, p, track, groundY), true, id.getLeastSignificantBits());
            } else {
                track.result = null;
            }
        }
        tracks.keySet().retainAll(alive);
    }

    /** The newest forecast for a player, or null when none was computed this tick or last. */
    public Result result(UUID id) {
        Track track = tracks.get(id);
        if (track == null || track.result == null || tick - track.result.computedTick() > 1) {
            return null;
        }
        return track.result;
    }

    /** Highest-confidence ensemble result at or above {@code minConfidence} among accepted players. */
    public Result bestCandidate(double minConfidence, Predicate<UUID> accept) {
        Result best = null;
        for (Map.Entry<UUID, Track> entry : tracks.entrySet()) {
            Result r = result(entry.getKey());
            if (r == null || !r.deep() || r.aimDirection() == null || r.confidence() < minConfidence
                    || !accept.test(entry.getKey())) {
                continue;
            }
            if (best == null || r.confidence() > best.confidence()
                    || (r.confidence() == best.confidence() && r.interceptTick() < best.interceptTick())) {
                best = r;
            }
        }
        return best;
    }

    /** Smoothed per-tick velocity of a tracked player, or null if untracked. */
    public Vec3d velocity(UUID id) {
        Track track = tracks.get(id);
        return track == null || track.count < 3 ? null : track.velocity;
    }

    /**
     * The target's hitbox where the server will hold it {@code extraTicks} after now plus the
     * connection latency, i.e. when an attack sent now reaches the server. Null if untracked.
     */
    public Box serverBox(PlayerEntity target, double extraTicks) {
        Track track = target == null ? null : tracks.get(target.getUuid());
        if (track == null || track.count < 3) {
            return null;
        }
        double ahead = lagTicks + Math.max(0.0, extraTicks);
        Box now = target.getBoundingBox();
        double hw = now.getLengthX() / 2.0;
        double cx = track.base.x + track.velocity.x * ahead;
        double cy = track.base.y + track.velocity.y * ahead;
        double cz = track.base.z + track.velocity.z * ahead;
        return new Box(cx - hw, cy, cz - hw, cx + hw, cy + now.getLengthY(), cz + hw);
    }

    /**
     * Nearest approach, in blocks, between the local eye and the target hitbox over the next
     * {@code ticks} ticks (0 = now) on the noise-free forecast, taking the worse of the box as drawn
     * and the box as the server will hold it. Infinity if the target is untracked or the fall ends
     * first. Same tracking and movement model as the smash forecast, with no ensemble.
     */
    public double predictedApproach(PlayerEntity target, int ticks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Track track = target == null ? null : tracks.get(target.getUuid());
        if (mc == null || mc.player == null || mc.world == null || track == null || track.count < 3) {
            return Double.POSITIVE_INFINITY;
        }
        return approachWithin(inputsFor(mc.player, target, track, groundBelow(mc)), ticks);
    }

    /**
     * Per-tick forecast approach, blocks, index 0 = now, length {@code ticks + 1}; the same distance
     * {@link #predictedApproach} minimises. Entries past the end of the fall are infinity. Null when the
     * target is untracked. Lets a caller see WHEN a reach is entered and how fast, not only that it is.
     */
    public double[] approachCurve(PlayerEntity target, int ticks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Track track = target == null ? null : tracks.get(target.getUuid());
        if (mc == null || mc.player == null || mc.world == null || track == null || track.count < 3) {
            return null;
        }
        return curveWithin(inputsFor(mc.player, target, track, groundBelow(mc)), ticks);
    }

    /** Pure core of {@link #predictedApproach}. */
    static double approachWithin(Inputs in, int ticks) {
        double closest = Double.POSITIVE_INFINITY;
        for (double d : curveWithin(in, ticks)) {
            closest = Math.min(closest, d);
        }
        return closest;
    }

    /** Pure core of {@link #approachCurve}. */
    static double[] curveWithin(Inputs in, int ticks) {
        int lag = MathHelper.clamp(in.lagTicks(), 0, MAX_LAG_TICKS);
        int n = Math.max(0, ticks) + 1;
        double[] mx = new double[n], my = new double[n], mz = new double[n];
        double[] tx = new double[n + lag], ty = new double[n + lag], tz = new double[n + lag];
        int valid = simulateMe(in, null, mx, my, mz, n);
        simulateTarget(in, null, tx, ty, tz, n + lag);
        double[] curve = new double[n];
        java.util.Arrays.fill(curve, Double.POSITIVE_INFINITY);
        for (int j = 0; j < valid; j++) {
            curve[j] = Math.max(visDistance(in, j, mx, my, mz, tx, ty, tz),
                    serverDistance(in, j, lag, mx, my, mz, tx, ty, tz) + SERVER_MARGIN);
        }
        return curve;
    }

    private static int readLagTicks(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null || mc.player == null) {
            return 0;
        }
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        int latency = entry == null ? 0 : Math.max(0, entry.getLatency());
        return MathHelper.clamp(Math.round(latency / 50.0f), 0, MAX_LAG_TICKS);
    }

    private static double groundBelow(MinecraftClient mc) {
        Vec3d start = mc.player.getEntityPos().add(0.0, 0.05, 0.0);
        Vec3d end = start.add(0.0, -96.0, 0.0);
        var hit = mc.world.raycast(new RaycastContext(
                start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK ? hit.getPos().y : NO_FLOOR;
    }

    /**
     * Least-squares line through the tracked positions. The server sends a moving player every second
     * tick, so the stored position is on average half a tick old; the base is moved forward by that.
     * Airborne velocity is advanced from the window midpoint to now with the movement model.
     */
    private void fit(Track t, PlayerEntity p) {
        int n = t.count;
        if (n < 3) {
            Vec3d rendered = p.getEntityPos();
            t.base = p.getTrackedPosition().getPos();
            t.velocity = new Vec3d(rendered.x - p.lastX, rendered.y - p.lastY, rendered.z - p.lastZ);
            t.sigmaH = 0.15;
            t.sigmaV = 0.15;
            return;
        }
        double meanT = 0, meanX = 0, meanY = 0, meanZ = 0;
        for (int i = 0; i < n; i++) {
            meanT += t.ticks[i];
            meanX += t.xs[i];
            meanY += t.ys[i];
            meanZ += t.zs[i];
        }
        meanT /= n;
        meanX /= n;
        meanY /= n;
        meanZ /= n;
        double stt = 0, stx = 0, sty = 0, stz = 0;
        for (int i = 0; i < n; i++) {
            double dt = t.ticks[i] - meanT;
            stt += dt * dt;
            stx += dt * (t.xs[i] - meanX);
            sty += dt * (t.ys[i] - meanY);
            stz += dt * (t.zs[i] - meanZ);
        }
        double vx = stt > 0 ? stx / stt : 0, vy = stt > 0 ? sty / stt : 0, vz = stt > 0 ? stz / stt : 0;
        double resH = 0, resV = 0;
        for (int i = 0; i < n; i++) {
            double dt = t.ticks[i] - meanT;
            double ex = t.xs[i] - (meanX + vx * dt);
            double ey = t.ys[i] - (meanY + vy * dt);
            double ez = t.zs[i] - (meanZ + vz * dt);
            resH += ex * ex + ez * ez;
            resV += ey * ey;
        }
        t.sigmaH = MathHelper.clamp(0.03 + 0.25 * Math.sqrt(resH / (2.0 * n)), 0.03, 0.30);
        t.sigmaV = MathHelper.clamp(0.04 + 0.25 * Math.sqrt(resV / n), 0.04, 0.30);

        double nowOffset = tick - meanT + 0.5;
        t.base = new Vec3d(meanX + vx * nowOffset, meanY + vy * nowOffset, meanZ + vz * nowOffset);
        double midLag = tick - meanT;
        boolean airborne = !p.isOnGround() && !p.isGliding();
        for (int i = 0; airborne && i < (int) Math.round(midLag); i++) {
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            vx *= AIR_RETENTION;
            vz *= AIR_RETENTION;
        }
        t.velocity = new Vec3d(vx, vy, vz);
    }

    private Inputs inputsFor(PlayerEntity me, PlayerEntity p, Track t, double groundY) {
        Mode mode = p.isGliding() ? Mode.GLIDE : p.isOnGround() ? Mode.GROUND : Mode.AIR;
        Box box = p.getBoundingBox();
        Vec3d vel = t.velocity;
        double floor = NO_FLOOR;
        if (mode == Mode.AIR && t.lastGroundY > NO_FLOOR && t.base.y - t.lastGroundY < 40.0
                && t.base.y >= t.lastGroundY - 0.5) {
            floor = t.lastGroundY;
        }
        return new Inputs(me.getEntityPos(), me.getVelocity(), me.getStandingEyeHeight(), Math.max(0.0, me.fallDistance),
                groundY, t.base, vel, p.getEntityPos().subtract(t.base), box.getLengthX() / 2.0, box.getLengthY(),
                mode, t.sigmaH, t.sigmaV, lagTicks, floor);
    }
}
