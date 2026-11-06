package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cobweb trap planning for AutoWeb: where a target will be by the time a web placed now exists on
 * the server, which cell will hold them, and a click a vanilla client could make to fill that cell.
 *
 * <h2>Motion source</h2>
 * Other players are drawn at an interpolated position that trails the last position the server sent
 * by up to three ticks ({@code PositionInterpolator}). Prediction reads the server-reported position
 * instead ({@link Entity#getTrackedPosition()}, written by {@code ClientPlayNetworkHandler.onEntity}
 * and {@code onEntityPositionSync}); the server sends it every second tick for players
 * ({@code EntityType.PLAYER} tracking interval 2). STATICALLY VERIFIED against 1.21.11 bytecode.
 *
 * <h2>Motion model</h2>
 * {@code LivingEntity.travelMidAir}: input acceleration is added, the move is collided against
 * blocks, then horizontal velocity is multiplied by {@code slipperiness * 0.91} on the ground or
 * {@code 0.91} in the air, and vertical velocity becomes {@code (vy - gravity) * 0.98}. A remote
 * player's held input is not visible, so it is solved from two consecutive server samples
 * ({@code a = (D - f^2 D') / (2 (1 + f))} for two-tick displacements D' then D) and kept as a fraction
 * of full sprint acceleration so it carries across ground and air. Knockback and other impulses are
 * recognised as an acceleration no input can produce.
 *
 * <h2>Robustness</h2>
 * Each plan simulates five input hypotheses (input as measured, input released, input toward the
 * local player, the measured input plus a jump, a sidestep across the line to the local player).
 * A cell scores by how centred the target is in it, how soon after the web exists they reach it, and
 * how long they stay. Its confidence is the weight of hypotheses that put the target in it, averaged
 * over timing: the web may reach the server later than planned (latency jitter, measured from the
 * spacing of the target's own position updates), so a hypothesis only counts fully when the target is
 * still in the cell at every one of the delayed arrivals, and a cell the target is about to leave
 * counts for less. A web is only worth placing where the hypotheses agree.
 *
 * <h2>Hold model ({@link #planHold})</h2>
 * A web catches the target where its hitbox first touches the cell, not where it would sit most
 * centred in it, and the move after the catch is scaled by 0.25 (velocity reset). A free-path score
 * therefore rewards cells the target only clips: it flies into them from the side, is held by its
 * leading edge and walks back out. {@code planHold} re-simulates each candidate with the web in
 * place and scores the share of the footprint inside the cell once caught, so only cells that hold
 * most of the body (a landing, a standstill) score well.
 *
 * <p>All methods run on the client thread. The model is an approximation of server state and is
 * UNVERIFIED in-game (latency jitter, server-side movement rules, plugins that alter knockback).
 */
public final class WebTrapPlanner {

    /** Which part of the target's body a web is meant to hold. */
    public enum Slice { FEET, HEAD }

    /** Simulation start state: server position, velocity before the next tick's input, held input. */
    public record Motion(Vec3d pos, Vec3d velocity, boolean onGround, Vec3d intent, boolean bhop) {
    }

    /**
     * One scored cell. {@code firstTick} is the first simulated tick at which a hypothesis enters it
     * ({@link #planHold}: the mean tick at which a web there catches them).
     * {@code held} is set by {@link #planHold} only (-1 otherwise): the mean share of the target's
     * footprint inside the cell once a web there has caught them.
     */
    public record Cell(BlockPos pos, double score, double confidence, int firstTick, double held) {
        public Cell(BlockPos pos, double score, double confidence, int firstTick) {
            this(pos, score, confidence, firstTick, -1.0);
        }
    }

    /** A click that a vanilla client could make to place into {@code cell}. */
    public record Click(BlockPos cell, BlockHitResult hit) {
    }

    /** Scored cells, best first, and the tick at which the measured path already runs into a web (-1: never). */
    public record Plan(List<Cell> cells, int webbedTick) {
        public Cell find(BlockPos pos) {
            if (pos == null) return null;
            for (Cell c : cells) {
                if (c.pos().equals(pos)) return c;
            }
            return null;
        }

        public double bestScore() {
            return cells.isEmpty() ? 0.0 : cells.get(0).score();
        }
    }

    /** {@code EntityType.PLAYER} tracking interval: the server sends player positions every second tick. */
    private static final int TRACK_INTERVAL = 2;
    private static final double AIR_FRICTION = 0.91;
    private static final double VERTICAL_DRAG = 0.98;
    /** Sprinting ground acceleration on slipperiness 0.6: {@code 0.1 * 1.3 * 0.21600002 / 0.6^3}. */
    private static final double SPRINT_SPEED = 0.13;
    /** {@code PlayerEntity.getOffGroundSpeed()} while sprinting. */
    private static final double SPRINT_AIR_ACCEL = 0.025999999;
    private static final double GROUND_ACCEL_FACTOR = 0.21600002;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    /** An interval needing more than this many times full acceleration was an impulse, not input. */
    private static final double IMPULSE_FACTOR = 2.5;
    private static final double MAX_INTENT = 1.15;

    public static final int HORIZON = 30;
    /** Ticks over which a later catch loses half its weight relative to an immediate one (~e-folding 12). */
    private static final double TIME_DECAY = 12.0;
    /** Minimum centred fraction for a hypothesis to count as "in the cell". */
    private static final double MIN_FRAC = 0.25;
    private static final double[] HYPOTHESIS_WEIGHT = {0.36, 0.16, 0.16, 0.16, 0.16};
    /**
     * {@link #planHold} hypotheses: input as measured, the same at half strength, input released,
     * toward the attacker, away from it, and to either side of that line. In a knockback flight
     * the only thing that can still change the landing is air control (about 0.026 per tick), so
     * these span what a victim can do; the measured input carries the most weight.
     */
    private static final double[] HOLD_WEIGHT = {0.30, 0.14, 0.14, 0.10, 0.10, 0.11, 0.11};
    /** {@code LivingEntity.tickMovement}: velocity components below this are zeroed before moving. */
    private static final double VELOCITY_EPSILON = 0.003;
    /** Late-arrival offsets (as fractions of the timing spread) and their weights in a cell's confidence. */
    private static final double[] ARRIVAL_OFFSET = {0.0, 0.5, 1.0};
    private static final double[] ARRIVAL_WEIGHT = {0.4, 0.3, 0.3};
    private static final double MIN_SPREAD = 0.6;
    private static final double MAX_SPREAD = 4.0;
    private static final double WATER_DRAG = 0.8;
    private static final double WATER_ACCEL = 0.02;
    /** Height of the head slice, measured down from the top of the hitbox. */
    private static final double HEAD_SLICE = 0.45;
    /** {@code CobwebBlock}: the move after a catch is scaled by this, and the velocity is reset. */
    private static final double WEB_SLOW_HORIZONTAL = 0.25;
    private static final double WEB_SLOW_VERTICAL = 0.05;
    /** Ticks after the catch over which the footprint share is judged (the move after the catch, then the pinned crawl). */
    private static final int SETTLE_TICKS = 4;
    /** Free-path cells refined by {@link #planHold}; each costs a handful of short simulations. */
    private static final int HOLD_CANDIDATES = 10;
    /** Footprint share inside the cell at which a catch starts to count, and at which it counts fully. */
    private static final double HOLD_RAMP_LOW = 0.35;
    private static final double HOLD_RAMP_HIGH = 0.7;

    private final Map<Integer, Track> tracks = new HashMap<>();
    private Object trackedWorld;
    private int clock;

    // ── Tracking ───────────────────────────────────────────────────────────────────

    private static final class Track {
        final UUID uuid;
        Vec3d pos;
        int sampleClock;
        int seenClock;
        /** Per-interval displacement (one server interval of {@link #TRACK_INTERVAL} ticks). */
        Vec3d disp = Vec3d.ZERO;
        boolean ground;
        /** Estimated horizontal move of the last tick of the latest interval. */
        Vec3d moveH = Vec3d.ZERO;
        double moveY;
        /** Held input as a fraction of full sprint acceleration (horizontal, world frame). */
        Vec3d intent = Vec3d.ZERO;
        int lastLaunchClock = Integer.MIN_VALUE / 2;
        int lastLandClock = Integer.MIN_VALUE / 2;
        int bhopStreak;
        int impulseClock = Integer.MIN_VALUE / 2;
        /** Smoothed deviation (ticks) of the spacing between this player's position updates from the nominal interval. */
        double jitter;

        Track(UUID uuid, Vec3d pos, int clock, boolean ground) {
            this.uuid = uuid;
            this.pos = pos;
            this.sampleClock = clock;
            this.seenClock = clock;
            this.ground = ground;
        }
    }

    /** Advances the clock and samples the server-reported position of every player within {@code range}. */
    public void tick(MinecraftClient mc, double range) {
        clock++;
        if (mc.world == null || mc.player == null) {
            clear();
            return;
        }
        if (trackedWorld != mc.world) {
            tracks.clear();
            trackedWorld = mc.world;
        }
        double rangeSq = range * range;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || mc.player.squaredDistanceTo(p) > rangeSq) {
                continue;
            }
            sample(p);
        }
        tracks.values().removeIf(t -> clock - t.seenClock > 40);
    }

    public void clear() {
        tracks.clear();
        trackedWorld = null;
    }

    /** Monotonic tick counter of this planner. */
    public int clock() {
        return clock;
    }

    /** Ticks since the latest server position of {@code p} arrived. */
    public int ticksSinceSample(PlayerEntity p) {
        Track t = track(p);
        return t == null ? 0 : Math.min(TRACK_INTERVAL * 2, clock - t.sampleClock);
    }

    /**
     * How irregularly this player's position updates arrive, in ticks (smoothed). Tick alignment alone
     * contributes about half a tick; more means the connection between the server and us is jittery.
     */
    public double jitterTicks(PlayerEntity p) {
        Track t = track(p);
        return t == null ? 0.5 : t.jitter;
    }

    /**
     * How many ticks later than planned a web placed now may reach the server, as a conservative
     * bound: grows with the lead (more round trip, more variance) and with the measured jitter.
     */
    private double timingSpread(PlayerEntity target, double from) {
        return MathHelper.clamp(MIN_SPREAD + 0.9 * jitterTicks(target) + 0.07 * from, MIN_SPREAD, MAX_SPREAD);
    }

    /** True when an impulse (knockback) or a launch off the ground showed up after {@code sinceClock}. */
    public boolean impulseSince(PlayerEntity p, int sinceClock) {
        Track t = track(p);
        return t != null && (t.impulseClock > sinceClock || t.lastLaunchClock > sinceClock);
    }

    private Track track(PlayerEntity p) {
        Track t = p == null ? null : tracks.get(p.getId());
        return t != null && t.uuid.equals(p.getUuid()) ? t : null;
    }

    private void sample(PlayerEntity p) {
        Vec3d pos = p.getTrackedPosition().getPos();
        boolean ground = p.isOnGround();
        Track t = track(p);
        if (t == null) {
            tracks.put(p.getId(), new Track(p.getUuid(), pos, clock, ground));
            return;
        }
        t.seenClock = clock;
        if (pos.equals(t.pos)) {
            if (clock - t.sampleClock > TRACK_INTERVAL + 2) {
                // The server sends nothing while a player stands still.
                t.disp = Vec3d.ZERO;
                t.moveH = Vec3d.ZERO;
                t.moveY = 0.0;
                t.intent = Vec3d.ZERO;
                t.ground = ground;
            }
            return;
        }

        int gap = clock - t.sampleClock;
        Vec3d raw = pos.subtract(t.pos);
        if (raw.lengthSquared() > 64.0) {
            // Teleport, respawn or a pearl: nothing before it predicts what comes next.
            tracks.put(p.getId(), new Track(p.getUuid(), pos, clock, ground));
            return;
        }
        boolean resumed = gap > 3 * TRACK_INTERVAL;
        // Two packets handled in one client tick show up as one sample covering both intervals.
        int intervals = resumed || gap <= TRACK_INTERVAL + 1 ? 1 : Math.max(1, Math.round(gap / (float) TRACK_INTERVAL));
        Vec3d d = raw.multiply(1.0 / intervals);
        Vec3d prev = resumed ? Vec3d.ZERO : t.disp;
        boolean prevGround = t.ground;

        if (!resumed) {
            double deviation = Math.min(4.0, Math.abs(gap - intervals * (double) TRACK_INTERVAL));
            t.jitter = 0.8 * t.jitter + 0.2 * deviation;
        }
        estimate(p, t, d, prev, prevGround, ground, pos);

        if (prevGround && !ground && d.y > 0.15) {
            t.bhopStreak = clock - t.lastLandClock <= 3 * TRACK_INTERVAL ? t.bhopStreak + 1 : 0;
            t.lastLaunchClock = clock;
        } else if (!prevGround && ground) {
            t.lastLandClock = clock;
        }
        t.pos = pos;
        t.sampleClock = clock;
        t.disp = d;
        t.ground = ground;
    }

    private void estimate(PlayerEntity p, Track t, Vec3d d, Vec3d prev, boolean prevGround, boolean ground, Vec3d pos) {
        World world = p.getEntityWorld();
        double slip = ground ? slipAt(world, pos) : 1.0;
        double f = ground ? slip * AIR_FRICTION : AIR_FRICTION;
        double full = fullAccel(p, ground, slip);
        Vec3d dh = new Vec3d(d.x, 0.0, d.z);
        Vec3d ph = new Vec3d(prev.x, 0.0, prev.z);

        if (ground == prevGround) {
            // Two ticks per interval: D = f^2 D' + 2 (1 + f) a.
            Vec3d a = dh.subtract(ph.multiply(f * f)).multiply(1.0 / (2.0 * (1.0 + f)));
            if (a.length() > IMPULSE_FACTOR * full) {
                // Knockback or another impulse at the start of the interval; the input is unchanged.
                t.impulseClock = clock;
                Vec3d held = t.intent.multiply(full);
                Vec3d d1 = dh.subtract(held).multiply(1.0 / (1.0 + f));
                t.moveH = d1.multiply(f).add(held);
            } else {
                Vec3d measured = a.multiply(1.0 / full);
                t.intent = clampLength(t.intent.multiply(0.35).add(measured.multiply(0.65)), MAX_INTENT);
                Vec3d d0 = ph.multiply(f).add(a).multiply(1.0 / (1.0 + f));
                t.moveH = d0.multiply(f * f).add(a.multiply(1.0 + f));
            }
        } else {
            // Took off or landed inside the interval: treat the change as happening at its start.
            if (dh.subtract(ph).length() > 0.25 * TRACK_INTERVAL) {
                t.impulseClock = clock;
            }
            Vec3d held = t.intent.multiply(full);
            Vec3d d1 = dh.subtract(held).multiply(1.0 / (1.0 + f));
            t.moveH = d1.multiply(f).add(held);
        }

        if (ground) {
            t.moveY = 0.0;
        } else {
            // Two airborne moves: dy2 = (dy1 - g) * 0.98 and D_y = dy1 + dy2.
            double g = p.getFinalGravity();
            double dy1 = (d.y + VERTICAL_DRAG * g) / (1.0 + VERTICAL_DRAG);
            t.moveY = (dy1 - g) * VERTICAL_DRAG;
        }
    }

    /** The measured motion of {@code p} at its latest server position. */
    public Motion observed(PlayerEntity p) {
        Track t = track(p);
        double g = p.getFinalGravity();
        if (t == null) {
            boolean ground = p.isOnGround();
            return new Motion(p.getTrackedPosition().getPos(),
                    new Vec3d(0.0, ground ? -g * VERTICAL_DRAG : 0.0, 0.0), ground, Vec3d.ZERO, false);
        }
        double slip = t.ground ? slipAt(p.getEntityWorld(), t.pos) : 1.0;
        double f = t.ground ? slip * AIR_FRICTION : AIR_FRICTION;
        double vy = t.ground ? -g * VERTICAL_DRAG : (t.moveY - g) * VERTICAL_DRAG;
        boolean bhop = t.bhopStreak > 0 && clock - t.lastLaunchClock < 30;
        return new Motion(t.pos, new Vec3d(t.moveH.x * f, vy, t.moveH.z * f), t.ground, t.intent, bhop);
    }

    /** {@code m} advanced {@code ticks} ticks with its own held input (the measured hypothesis). */
    public Motion advance(PlayerEntity p, Motion m, int ticks) {
        Sim sim = new Sim(p, m, m.intent(), m.bhop());
        for (int i = 0; i < ticks && sim.webbed < 0; i++) {
            sim.step();
        }
        Vec3d pos = new Vec3d((sim.box.minX + sim.box.maxX) * 0.5, sim.box.minY, (sim.box.minZ + sim.box.maxZ) * 0.5);
        return new Motion(pos, new Vec3d(sim.vx, sim.vy, sim.vz), sim.onGround, m.intent(), m.bhop());
    }

    // ── Planning ───────────────────────────────────────────────────────────────────

    /** Body state after a simulated tick of one hypothesis. */
    private record PathState(Box box, double vx, double vy, double vz, boolean onGround, int t) {
    }

    /** One planning run: every hypothesis' free path and its per-cell visit statistics. */
    private static final class Run {
        final double from;
        final double spread;
        final Vec3d[] intents;
        final boolean[] jumps;
        final double[] weights;
        final List<List<PathState>> paths = new ArrayList<>();
        final List<Map<BlockPos, double[]>> per = new ArrayList<>();
        int webbed = -1;

        Run(double from, double spread, Vec3d[] intents, boolean[] jumps, double[] weights) {
            this.from = from;
            this.spread = spread;
            this.intents = intents;
            this.jumps = jumps;
            this.weights = weights;
        }
    }

    /**
     * Scores every cell the target's {@code slice} passes through from {@code webTick} (ticks after
     * {@code start}, when a web placed now exists on the server) to the horizon.
     *
     * <p>This scores the free path only (how centred the target is in a cell, how long they stay). A
     * web catches the target on first contact, not at the most centred moment; {@link #planHold}
     * accounts for that.
     */
    public Plan plan(PlayerEntity target, Motion start, double webTick, Slice slice, Vec3d attacker) {
        Run run = simulate(target, start, webTick, slice, attacker, false);
        return new Plan(coarseCells(run), run.webbed);
    }

    /**
     * Like {@link #plan} but scores each candidate cell by how much of the target's body a web
     * placed there actually holds.
     *
     * <p>A cobweb acts on first contact: the target is caught when its hitbox first touches the cell
     * (a path sweep, as {@code Entity.applyEffectsFromBlocks}), its velocity is reset and the next
     * move is scaled by 0.25 horizontally and 0.05 vertically. A cell the target flies into from the
     * side therefore holds only its leading edge, and they walk back out. Each candidate is simulated
     * with the web in place: for every input hypothesis and late-arrival offset the catch tick is found
     * on the free path, the cobweb slowdown is applied for {@link #SETTLE_TICKS}, and the worst
     * share of the body's footprint inside the cell over that window is the hold. A cell entered
     * from above (a landing) holds the whole footprint; an edge-on entry scores low and is not chosen.
     *
     * <p>Cell fields: {@code held} is the weighted mean footprint share inside the cell given the
     * target is caught, {@code confidence} the weight of outcomes that hold most of the body (a ramp
     * from {@link #HOLD_RAMP_LOW} to {@link #HOLD_RAMP_HIGH}), {@code score} that weight discounted by
     * the catch time. Only the strongest {@link #HOLD_CANDIDATES} free-path cells that {@code candidate}
     * accepts are refined.
     *
     * @param candidate prunes cells that cannot be placed before the costly catch simulation; null accepts all
     */
    public Plan planHold(PlayerEntity target, Motion start, double webTick, Vec3d attacker,
                         java.util.function.Predicate<BlockPos> candidate) {
        Run run = simulate(target, start, webTick, Slice.FEET, attacker, true);
        List<Cell> refined = new ArrayList<>();
        int examined = 0;
        for (Cell c : coarseCells(run)) {
            if (examined >= HOLD_CANDIDATES) break;
            if (candidate != null && !candidate.test(c.pos())) continue;
            examined++;
            Cell hold = holdCell(target, run, c.pos());
            if (hold != null) refined.add(hold);
        }
        refined.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new Plan(refined, run.webbed);
    }

    /** {@code hold}: the {@link #planHold} hypothesis set, with every path kept for the catch simulation. */
    private Run simulate(PlayerEntity target, Motion start, double webTick, Slice slice, Vec3d attacker, boolean hold) {
        Vec3d toward = Vec3d.ZERO;
        Vec3d side = Vec3d.ZERO;
        if (attacker != null) {
            Vec3d d = new Vec3d(attacker.x - start.pos().x, 0.0, attacker.z - start.pos().z);
            if (d.lengthSquared() > 1.0e-4) {
                toward = d.normalize();
                // The dodge goes to whichever side the target is already drifting, else to the right.
                Vec3d perp = new Vec3d(-toward.z, 0.0, toward.x);
                double lean = start.intent().dotProduct(perp) + 0.5 * start.velocity().dotProduct(perp);
                side = lean < 0.0 ? perp.multiply(-1.0) : perp;
            }
        }
        Vec3d[] intents = {start.intent(), Vec3d.ZERO, toward, start.intent(), side};
        boolean[] jumps = {start.bhop(), false, false, !start.bhop(), false};
        double[] weights = HYPOTHESIS_WEIGHT;
        if (hold) {
            Vec3d perp = new Vec3d(-toward.z, 0.0, toward.x);
            intents = new Vec3d[]{start.intent(), start.intent().multiply(0.5), Vec3d.ZERO, toward,
                    toward.multiply(-1.0), perp, perp.multiply(-1.0)};
            jumps = new boolean[]{start.bhop(), start.bhop(), false, false, false, false, false};
            weights = HOLD_WEIGHT;
        }
        double from = Math.max(0.0, webTick);
        Run run = new Run(from, timingSpread(target, from), intents, jumps, weights);

        for (int h = 0; h < intents.length; h++) {
            Sim sim = new Sim(target, start, intents[h], jumps[h]);
            Map<BlockPos, double[]> per = new HashMap<>();
            List<PathState> path = hold ? new ArrayList<>(HORIZON + 1) : null;
            Box prevBox = sim.box;
            if (path != null) path.add(sim.state());
            accumulate(per, prevBox, 0.0, from, slice);
            for (int t = 1; t <= HORIZON && sim.webbed < 0; t++) {
                sim.step();
                if (path != null) path.add(sim.state());
                Box box = sim.box;
                accumulate(per, lerp(prevBox, box, 0.5), t - 0.5, from, slice);
                accumulate(per, box, t, from, slice);
                prevBox = box;
            }
            // A web on the path holds the target where it caught them (cobweb motion is ~0).
            for (int t = sim.t + 1; sim.webbed >= 0 && t <= HORIZON; t++) {
                accumulate(per, sim.box, t, from, slice);
            }
            if (h == 0) run.webbed = sim.webbed;
            run.per.add(per);
            run.paths.add(path);
        }
        return run;
    }

    /** Free-path cell scores, best first. */
    private static List<Cell> coarseCells(Run run) {
        Map<BlockPos, double[]> total = new HashMap<>();
        for (int h = 0; h < run.per.size(); h++) {
            double w = run.weights[h];
            for (Map.Entry<BlockPos, double[]> e : run.per.get(h).entrySet()) {
                double[] v = e.getValue();
                double[] acc = total.computeIfAbsent(e.getKey(), k -> new double[]{0.0, 0.0, Double.MAX_VALUE});
                // Share of the possible arrival times at which the target is still in the cell.
                double presence = 0.0;
                if (v[2] >= MIN_FRAC) {
                    for (int o = 0; o < ARRIVAL_OFFSET.length; o++) {
                        if (v[4] >= run.from + ARRIVAL_OFFSET[o] * run.spread - 1.0e-9) presence += ARRIVAL_WEIGHT[o];
                    }
                }
                acc[0] += w * (v[0] + 0.08 * Math.min(4.0, v[1])) * (0.4 + 0.6 * presence);
                acc[1] += w * presence;
                acc[2] = Math.min(acc[2], v[3]);
            }
        }
        List<Cell> cells = new ArrayList<>(total.size());
        for (Map.Entry<BlockPos, double[]> e : total.entrySet()) {
            double[] v = e.getValue();
            cells.add(new Cell(e.getKey(), v[0], v[1], (int) Math.ceil(v[2])));
        }
        cells.sort((a, b) -> Double.compare(b.score(), a.score()));
        return cells;
    }

    /** The hold of a web in {@code cell}; null when no hypothesis ever touches it. */
    private Cell holdCell(PlayerEntity target, Run run, BlockPos cell) {
        double score = 0.0;
        double confidence = 0.0;
        double heldSum = 0.0;
        double caught = 0.0;
        double tickSum = 0.0;
        for (int h = 0; h < run.paths.size(); h++) {
            List<PathState> path = run.paths.get(h);
            double[] memo = new double[HORIZON + 1];
            java.util.Arrays.fill(memo, -1.0);
            for (int o = 0; o < ARRIVAL_OFFSET.length; o++) {
                double arrival = run.from + ARRIVAL_OFFSET[o] * run.spread;
                int tc = firstContact(path, cell, Math.max(1, (int) Math.ceil(arrival - 1.0e-9)));
                if (tc < 0) continue;
                if (memo[tc] < 0.0) memo[tc] = settle(target, run, h, path, tc, cell);
                double held = memo[tc];
                double weight = run.weights[h] * ARRIVAL_WEIGHT[o];
                double credit = holdCredit(held);
                double soon = Math.exp(-Math.max(0.0, tc - run.from) / TIME_DECAY);
                // Descent apex bonus: if the target is entering this cell during descent near the top of the cell,
                // prioritize catching them on top of the web!
                PathState contactState = path.get(Math.min(tc, path.size() - 1));
                if (contactState.vy() <= 0.0 && contactState.box().minY >= cell.getY() + 0.4) {
                    credit = Math.min(1.0, credit * 1.35);
                    held = Math.min(1.0, held * 1.25);
                }
                score += weight * (0.7 * credit + 0.3 * held) * soon;
                confidence += weight * credit;
                heldSum += weight * held;
                caught += weight;
                tickSum += weight * tc;
            }
        }
        if (caught <= 0.0) return null;
        // firstTick of a hold cell is the weighted mean catch tick: when the web is needed by.
        return new Cell(cell, score, confidence, (int) Math.floor(tickSum / caught), heldSum / caught);
    }

    /** First tick at or after {@code from} whose movement sweep touches {@code cell}; -1 when none does. */
    private static int firstContact(List<PathState> path, BlockPos cell, int from) {
        int last = path.size() - 1;
        for (int t = from; t <= HORIZON; t++) {
            Box prev = path.get(Math.min(t - 1, last)).box();
            Box cur = path.get(Math.min(t, last)).box();
            if (touchesCell(prev.union(cur), cell)) return t;
        }
        return -1;
    }

    /**
     * Catches hypothesis {@code h} in {@code cell} at tick {@code tc} and runs the cobweb dynamics
     * for {@link #SETTLE_TICKS}; returns the smallest footprint share inside the cell over that
     * window (0 when they leave it).
     */
    private double settle(PlayerEntity target, Run run, int h, List<PathState> path, int tc, BlockPos cell) {
        PathState caught = path.get(Math.min(tc, path.size() - 1));
        Sim sim = new Sim(target, run.intents[h], run.jumps[h], caught, cell);
        double worst = 1.0;
        for (int k = 0; k < SETTLE_TICKS && worst > 0.0; k++) {
            sim.step();
            worst = Math.min(worst, footprintShare(sim.box, cell));
        }
        return worst;
    }

    /** Share of the body's footprint inside {@code cell}, discounted when only a sliver of its height is. */
    private static double footprintShare(Box body, BlockPos cell) {
        double area = (body.maxX - body.minX) * (body.maxZ - body.minZ);
        if (area <= 0.0) return 0.0;
        double ox = overlap(body.minX, body.maxX, cell.getX(), cell.getX() + 1.0);
        double oy = overlap(body.minY, body.maxY, cell.getY(), cell.getY() + 1.0);
        double oz = overlap(body.minZ, body.maxZ, cell.getZ(), cell.getZ() + 1.0);
        if (ox <= 0.0 || oy <= 0.0 || oz <= 0.0) return 0.0;
        return (ox * oz / area) * Math.min(1.0, oy / Math.min(0.3, body.maxY - body.minY));
    }

    /** 0 up to {@link #HOLD_RAMP_LOW} of the footprint, 1 from {@link #HOLD_RAMP_HIGH}, smooth between. */
    private static double holdCredit(double share) {
        double x = MathHelper.clamp((share - HOLD_RAMP_LOW) / (HOLD_RAMP_HIGH - HOLD_RAMP_LOW), 0.0, 1.0);
        return x * x * (3.0 - 2.0 * x);
    }

    private static boolean touchesCell(Box box, BlockPos cell) {
        return cell.getX() >= MathHelper.floor(box.minX) && cell.getX() <= MathHelper.floor(box.maxX - 1.0e-7)
                && cell.getY() >= MathHelper.floor(box.minY) && cell.getY() <= MathHelper.floor(box.maxY - 1.0e-7)
                && cell.getZ() >= MathHelper.floor(box.minZ) && cell.getZ() <= MathHelper.floor(box.maxZ - 1.0e-7);
    }

    /** Per hypothesis, per cell: [max weighted fraction, dwell, max fraction, first time, last time at or above {@link #MIN_FRAC}]. */
    private static void accumulate(Map<BlockPos, double[]> per, Box body, double time, double from, Slice slice) {
        if (time < from - 1.0e-9) return;
        double weight = Math.exp(-(time - from) / TIME_DECAY);
        Box sb = sliceBox(body, slice);
        int feetLayer = MathHelper.floor(body.minY + 1.0e-4);
        int x0 = MathHelper.floor(sb.minX), x1 = MathHelper.floor(sb.maxX - 1.0e-7);
        int y0 = MathHelper.floor(sb.minY), y1 = MathHelper.floor(sb.maxY - 1.0e-7);
        int z0 = MathHelper.floor(sb.minZ), z1 = MathHelper.floor(sb.maxZ - 1.0e-7);
        double area = (sb.maxX - sb.minX) * (sb.maxZ - sb.minZ);
        double minV = Math.min(0.3, sb.maxY - sb.minY);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    double ox = overlap(sb.minX, sb.maxX, x, x + 1.0);
                    double oy = overlap(sb.minY, sb.maxY, y, y + 1.0);
                    double oz = overlap(sb.minZ, sb.maxZ, z, z + 1.0);
                    if (ox <= 0.0 || oy <= 0.0 || oz <= 0.0) continue;
                    double frac = (ox * oz / area) * Math.min(1.0, oy / minV);
                    // Any body cell holds the target; the feet layer is preferred because it also
                    // has the ground under it to place against and blocks a jump out.
                    if (slice == Slice.FEET && y != feetLayer) frac *= 0.85;
                    double[] v = per.computeIfAbsent(new BlockPos(x, y, z),
                            k -> new double[]{0.0, 0.0, 0.0, Double.MAX_VALUE, -1.0});
                    v[0] = Math.max(v[0], weight * frac);
                    if (frac >= MIN_FRAC) {
                        v[1] += 0.5 * weight;
                        v[4] = Math.max(v[4], time);
                    }
                    v[2] = Math.max(v[2], frac);
                    v[3] = Math.min(v[3], time);
                }
            }
        }
    }

    private static Box sliceBox(Box body, Slice slice) {
        if (slice == Slice.HEAD) {
            return new Box(body.minX, Math.max(body.minY, body.maxY - HEAD_SLICE), body.minZ,
                    body.maxX, body.maxY, body.maxZ);
        }
        return body;
    }

    /** True when {@code box}'s head slice touches a cobweb. */
    public static boolean headInWeb(World world, Box box) {
        return touchesWeb(world, sliceBox(box, Slice.HEAD), null);
    }

    // ── Simulation ─────────────────────────────────────────────────────────────────

    /** One hypothesis of {@code LivingEntity.travelMidAir} for a remote player. */
    private final class Sim {
        final PlayerEntity p;
        final World world;
        final Vec3d intent;
        final boolean jump;
        final double gravity;
        final double step;
        final double speedRatio;
        final double jumpVelocity;
        Box box;
        double vx;
        double vy;
        double vz;
        boolean onGround;
        int t;
        int webbed = -1;
        /** Hold simulation: the cell a candidate web would fill, and whether the target is caught in it right now. */
        BlockPos extraWeb;
        boolean holdMode;
        boolean stuck;

        private Sim(PlayerEntity p, Vec3d intent, boolean jump) {
            this.p = p;
            this.world = p.getEntityWorld();
            this.intent = intent == null ? Vec3d.ZERO : intent;
            this.jump = jump;
            this.gravity = p.getFinalGravity();
            this.step = p.getStepHeight();
            this.speedRatio = speedRatio(p);
            this.jumpVelocity = MeleeMechanics.jumpVelocity(p);
        }

        Sim(PlayerEntity p, Motion m, Vec3d intent, boolean jump) {
            this(p, intent, jump);
            double hw = p.getWidth() / 2.0;
            this.box = new Box(m.pos().x - hw, m.pos().y, m.pos().z - hw,
                    m.pos().x + hw, m.pos().y + p.getHeight(), m.pos().z + hw);
            this.vx = m.velocity().x;
            this.vy = m.velocity().y;
            this.vz = m.velocity().z;
            this.onGround = m.onGround();
            if (touchesWeb(world, box, null)) webbed = 0;
        }

        /** Resumes a hypothesis path at {@code s}, caught by a web placed in {@code web}. */
        Sim(PlayerEntity p, Vec3d intent, boolean jump, PathState s, BlockPos web) {
            this(p, intent, jump);
            this.box = s.box();
            this.vx = s.vx();
            this.vy = s.vy();
            this.vz = s.vz();
            this.onGround = s.onGround();
            this.t = s.t();
            this.extraWeb = web;
            this.holdMode = true;
            this.stuck = true;
        }

        PathState state() {
            return new PathState(box, vx, vy, vz, onGround, t);
        }

        void step() {
            t++;
            if (Math.abs(vx) < VELOCITY_EPSILON) vx = 0.0;
            if (Math.abs(vy) < VELOCITY_EPSILON) vy = 0.0;
            if (Math.abs(vz) < VELOCITY_EPSILON) vz = 0.0;
            boolean groundBefore = onGround;
            double slip = groundBefore ? slipAt(world, box) : 1.0;
            if (groundBefore && jump) {
                vy = jumpVelocity;
                if (p.isSprinting()) {
                    float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
                    vx += -MathHelper.sin(yaw) * SPRINT_JUMP_BOOST;
                    vz += MathHelper.cos(yaw) * SPRINT_JUMP_BOOST;
                }
            }
            boolean water = inWater(world, box);
            double accel = water
                    ? WATER_ACCEL * speedRatio
                    : groundBefore
                    ? SPRINT_SPEED * speedRatio * (GROUND_ACCEL_FACTOR / (slip * slip * slip))
                    : SPRINT_AIR_ACCEL;
            vx += intent.x * accel;
            vz += intent.z * accel;

            Vec3d want = new Vec3d(vx, vy, vz);
            // Caught in a cobweb at the end of the previous move: this move is scaled and the
            // velocity reset ({@code Entity.move} with its movement multiplier).
            Vec3d attempt = stuck
                    ? new Vec3d(want.x * WEB_SLOW_HORIZONTAL, want.y * WEB_SLOW_VERTICAL, want.z * WEB_SLOW_HORIZONTAL)
                    : want;
            Vec3d moved = collide(world, p, box, attempt, groundBefore, step);
            Box next = box.offset(moved);
            boolean hitY = Math.abs(moved.y - attempt.y) > 1.0e-7;
            onGround = hitY && attempt.y < 0.0;
            if (hitY) vy = 0.0;
            if (Math.abs(moved.x - attempt.x) > 1.0e-7) vx = 0.0;
            if (Math.abs(moved.z - attempt.z) > 1.0e-7) vz = 0.0;
            if (stuck) {
                vx = 0.0;
                vy = 0.0;
                vz = 0.0;
            }

            // Soul sand and honey slow whoever stands in or on them ({@code Entity.getVelocityMultiplier}).
            double slow = velocityMultiplier(world, box);
            vx *= slow;
            vz *= slow;

            if (water) {
                vx *= WATER_DRAG;
                vz *= WATER_DRAG;
                vy = vy * WATER_DRAG - gravity / 16.0;
            } else {
                double f = groundBefore ? slip * AIR_FRICTION : AIR_FRICTION;
                vx *= f;
                vz *= f;
                vy = (vy - gravity) * VERTICAL_DRAG;
            }

            boolean touched = touchesWeb(world, box.union(next), extraWeb);
            if (holdMode) {
                stuck = touched;
            } else if (touched) {
                webbed = t;
            }
            box = next;
        }
    }

    private static Vec3d collide(World world, Entity e, Box box, Vec3d move, boolean groundBefore, double stepHeight) {
        Vec3d moved = collideBasic(world, e, box, move);
        boolean blockedH = Math.abs(moved.x - move.x) > 1.0e-7 || Math.abs(moved.z - move.z) > 1.0e-7;
        boolean grounded = groundBefore || (Math.abs(moved.y - move.y) > 1.0e-7 && move.y < 0.0);
        if (stepHeight > 0.0 && blockedH && grounded) {
            Vec3d up = collideBasic(world, e, box, new Vec3d(move.x, stepHeight, move.z));
            if (up.horizontalLengthSquared() > moved.horizontalLengthSquared() + 1.0e-7) {
                Box raised = box.offset(up);
                double down = collideBasic(world, e, raised, new Vec3d(0.0, -up.y + Math.min(0.0, move.y), 0.0)).y;
                return new Vec3d(up.x, up.y + down, up.z);
            }
        }
        return moved;
    }

    /** Vanilla axis order: Y first, then the larger horizontal component. */
    private static Vec3d collideBasic(World world, Entity e, Box box, Vec3d move) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : world.getBlockCollisions(e, box.stretch(move).expand(1.0e-7))) {
            shapes.add(shape);
        }
        double dy = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, box, shapes, move.y);
        Box moved = box.offset(0.0, dy, 0.0);
        double dx;
        double dz;
        if (Math.abs(move.x) >= Math.abs(move.z)) {
            dx = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moved, shapes, move.x);
            moved = moved.offset(dx, 0.0, 0.0);
            dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, move.z);
        } else {
            dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, move.z);
            moved = moved.offset(0.0, 0.0, dz);
            dx = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moved, shapes, move.x);
        }
        return new Vec3d(dx, dy, dz);
    }

    private static boolean touchesWeb(World world, Box box, BlockPos extra) {
        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.floor(box.maxX - 1.0e-7);
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.floor(box.maxY - 1.0e-7);
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.floor(box.maxZ - 1.0e-7);
        if (extra != null && extra.getX() >= x0 && extra.getX() <= x1 && extra.getY() >= y0 && extra.getY() <= y1
                && extra.getZ() >= z0 && extra.getZ() <= z1) {
            return true;
        }
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (world.getBlockState(m.set(x, y, z)).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    /** True when the body's lower half is in water: movement there follows fluid drag, not ground or air. */
    private static boolean inWater(World world, Box box) {
        BlockPos at = BlockPos.ofFloored((box.minX + box.maxX) * 0.5, box.minY + 0.4, (box.minZ + box.maxZ) * 0.5);
        return world.getFluidState(at).isIn(FluidTags.WATER);
    }

    /** {@code Entity.getVelocityMultiplier}: the block at the feet, else the one the feet stand on. */
    private static double velocityMultiplier(World world, Box box) {
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double here = world.getBlockState(BlockPos.ofFloored(cx, box.minY, cz)).getBlock().getVelocityMultiplier();
        if (here != 1.0) return here;
        return world.getBlockState(BlockPos.ofFloored(cx, box.minY - 0.500001, cz)).getBlock().getVelocityMultiplier();
    }

    /** Slipperiness of the block that sets ground friction ({@code getVelocityAffectingPos}: 0.5 below the feet). */
    private static double slipAt(World world, Vec3d feet) {
        return world.getBlockState(BlockPos.ofFloored(feet.x, feet.y - 0.500001, feet.z)).getBlock().getSlipperiness();
    }

    private static double slipAt(World world, Box box) {
        return slipAt(world, new Vec3d((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5));
    }

    private static double fullAccel(PlayerEntity p, boolean ground, double slip) {
        return ground ? SPRINT_SPEED * speedRatio(p) * (GROUND_ACCEL_FACTOR / (slip * slip * slip)) : SPRINT_AIR_ACCEL;
    }

    /** Movement speed relative to an unaffected player (Speed effects), never below 1. */
    private static double speedRatio(PlayerEntity p) {
        try {
            double v = p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
            double base = p.isSprinting() ? 0.13 : 0.1;
            return MathHelper.clamp(v / base, 1.0, 2.5);
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    // ── Fire policy (shared by AutoWeb and AutoShieldBreak's web) ─────────────────

    /** Fire-threshold relief per tick of round trip: a longer lead cannot be as certain. */
    private static final double PING_RELIEF_PER_TICK = 0.03;
    private static final double MAX_PING_RELIEF = 0.2;
    /** Relief per tick spent aiming at a cell without firing: waiting longer only loses the target. */
    private static final double URGENCY_PER_TICK = 0.04;
    private static final double MAX_URGENCY = 0.25;
    /** Never fire below this, whatever the relief. */
    public static final double MIN_FIRE_CONFIDENCE = 0.25;

    /**
     * Confidence a cell needs before a web is fired at it. A fixed threshold never fires on a
     * laggy link, where the input hypotheses diverge over the longer lead, or on a target that keeps
     * changing direction; the bar therefore drops with the round trip and with every tick the module
     * has already spent aiming, down to {@link #MIN_FIRE_CONFIDENCE}.
     */
    public static double fireConfidence(double base, double rttTicks, int aimedTicks) {
        double relief = Math.min(MAX_PING_RELIEF, PING_RELIEF_PER_TICK * Math.max(0.0, rttTicks))
                + Math.min(MAX_URGENCY, URGENCY_PER_TICK * Math.max(0, aimedTicks));
        return Math.max(Math.min(base, MIN_FIRE_CONFIDENCE), base - relief);
    }

    /**
     * Finds the cell and placement face that traps the target at the highest point of their descent.
     * During knockback/launch flight, the target rises to an apex (vy transitioning from positive to <= 0)
     * and begins falling. Placing a web in the cell where their descent begins ensures their feet enter
     * the top of the cobweb block, catching them immediately in mid-air at the highest point of descent
     * so they are suspended on top in the web.
     *
     * @param mc the minecraft client
     * @param target the target player entity
     * @param motion current motion/knockback model
     * @param webTick ticks from start when web arrives on server
     * @param reach block placement reach
     * @param margin entity clearance margin
     * @param placeable placement predicate
     * @return Click representing the best supporting face, or null if none reachable
     */
    public Click findDescentApexClick(MinecraftClient mc, PlayerEntity target, Motion motion,
                                      double webTick, double reach, double margin,
                                      java.util.function.Predicate<BlockPos> placeable) {
        if (mc == null || mc.player == null || mc.world == null || target == null || motion == null) {
            return null;
        }

        Sim sim = new Sim(target, motion, motion.intent(), false);
        List<PathState> path = new ArrayList<>(HORIZON + 1);
        path.add(sim.state());
        for (int t = 1; t <= HORIZON && sim.webbed < 0; t++) {
            sim.step();
            path.add(sim.state());
        }

        // 1. Locate apex of trajectory (highest Y)
        int apexIndex = 0;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            PathState s = path.get(i);
            if (s.box().minY > maxY) {
                maxY = s.box().minY;
                apexIndex = i;
            }
        }

        // 2. Scan from apex onward through descent (vy <= 0) for the highest placeable cell
        Click bestClick = null;
        double bestHeight = -Double.MAX_VALUE;
        double fromTick = Math.max(0.0, webTick);

        for (int i = apexIndex; i < path.size(); i++) {
            PathState s = path.get(i);
            if (s.t() < fromTick - 1.0) continue;

            Vec3d center = s.box().getCenter();
            double feetY = s.box().minY;
            BlockPos cell = BlockPos.ofFloored(center.x, feetY, center.z);

            if (placeable.test(cell)) {
                Click click = findClick(mc, cell, reach, margin);
                if (click != null && cell.getY() > bestHeight) {
                    bestHeight = cell.getY();
                    bestClick = click;
                }
            }

            // Also check support directly below if the elevated cell lacks direct support
            BlockPos below = cell.down();
            if (placeable.test(below)) {
                Click click = findClick(mc, below, reach, margin);
                if (click != null && below.getY() > bestHeight) {
                    bestHeight = below.getY();
                    bestClick = click;
                }
            }

            // If we found a valid placement at the trajectory apex, that is the ideal highest-descent trap
            if (bestClick != null && bestHeight >= (int) Math.floor(maxY)) {
                return bestClick;
            }
        }

        return bestClick;
    }

    /**
     * Direct fallback when no scored cell has a click: the cell the target's feet will be in when the
     * web exists (measured input, {@code webTick} ahead), then the cell of their latest server
     * position, then the cell they are drawn in. The first one {@code placeable} accepts with a
     * vanilla-clickable face wins. Used only after the scored plan found nothing to aim at, so a
     * target standing against a wall or in a corner still gets a web.
     */
    public Click directClick(MinecraftClient mc, PlayerEntity target, Motion motion, double webTick, double reach,
                             double margin, java.util.function.Predicate<BlockPos> placeable) {
        if (mc == null || mc.player == null || mc.world == null || target == null || motion == null) return null;
        List<BlockPos> candidates = new ArrayList<>(4);
        int ahead = MathHelper.clamp((int) Math.ceil(Math.max(0.0, webTick)), 0, HORIZON);
        addFeetCells(candidates, advance(target, motion, ahead).pos());
        addFeetCells(candidates, motion.pos());
        addFeetCells(candidates, target.getEntityPos());
        for (BlockPos cell : candidates) {
            if (!placeable.test(cell)) continue;
            Click click = findClick(mc, cell, reach, margin);
            if (click != null) return click;
        }
        return null;
    }

    private static void addFeetCells(List<BlockPos> out, Vec3d feet) {
        // Slightly above the feet so a target standing on a slab or carpet maps to its own cell.
        BlockPos cell = BlockPos.ofFloored(feet.x, feet.y + 0.1, feet.z);
        if (!out.contains(cell)) out.add(cell);
    }

    // ── Clicks ─────────────────────────────────────────────────────────────────────

    /**
     * The best click that places a web into {@code cell}: a face of a supporting block (or of a
     * replaceable plant in the cell), reached by the block ray within {@code reach}, with no
     * hittable entity in front of it. Vanilla's crosshair picks an entity over a farther block, so a
     * face behind the target's hitbox cannot be clicked. Among valid points, prefers small turns
     * and points away from face edges.
     *
     * @param entityMargin extra hitbox inflation, for entities that may move before the click lands
     */
    public static Click findClick(MinecraftClient mc, BlockPos cell, double reach, double entityMargin) {
        if (mc.player == null || mc.world == null || cell == null) return null;
        World world = mc.world;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = currentLook(mc);
        double reachSq = reach * reach;
        boolean sneaking = mc.player.isSneaking();
        Click best = null;
        double[] bestCost = {Double.MAX_VALUE};

        BlockState cellState = world.getBlockState(cell);
        if (!cellState.isAir() && cellState.isReplaceable() && cellState.getFluidState().isEmpty()) {
            // A plant in the cell is replaced in place, so any face of its outline works.
            VoxelShape shape = cellState.getOutlineShape(world, cell);
            if (!shape.isEmpty()) {
                Box b = shape.getBoundingBox().offset(cell);
                for (Direction side : Direction.values()) {
                    best = pickBetter(mc, best, bestCost, cell, cell, side, b, eye, look, reachSq, entityMargin);
                }
            }
        }
        for (Direction dir : Direction.values()) {
            BlockPos n = cell.offset(dir);
            BlockState ns = world.getBlockState(n);
            if (!WebPlacementHelper.isSupportingBlock(mc, n, ns) || (!sneaking && isInteractive(world, n, ns))) {
                continue;
            }
            VoxelShape shape = ns.getOutlineShape(world, n);
            if (shape.isEmpty()) continue;
            best = pickBetter(mc, best, bestCost, cell, n, dir.getOpposite(), shape.getBoundingBox().offset(n),
                    eye, look, reachSq, entityMargin);
        }
        return best;
    }

    /** Face sample grid. The outer rows matter: under a target standing mid-cell only the rim of
     *  the floor face is visible past their hitbox, and that rim is where a player would click. */
    private static final double[] FACE_POINTS = {0.06, 0.25, 0.5, 0.75, 0.94};

    private static Click pickBetter(MinecraftClient mc, Click best, double[] bestCost, BlockPos cell, BlockPos clicked,
                                    Direction side, Box b, Vec3d eye, Vec3d look, double reachSq, double margin) {
        Direction.Axis axis = side.getAxis();
        double plane = side.getDirection() == Direction.AxisDirection.POSITIVE ? b.getMax(axis) : b.getMin(axis);
        // The eye must be on the open side of the face to see it at all.
        double eyeCoord = eye.getComponentAlongAxis(axis);
        if (side.getDirection() == Direction.AxisDirection.POSITIVE ? eyeCoord <= plane : eyeCoord >= plane) {
            return best;
        }
        Direction.Axis ua = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis va = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        Vec3d normal = Vec3d.of(side.getVector());
        for (double fu : FACE_POINTS) {
            for (double fv : FACE_POINTS) {
                double u = MathHelper.lerp(fu, b.getMin(ua), b.getMax(ua));
                double v = MathHelper.lerp(fv, b.getMin(va), b.getMax(va));
                Vec3d point = compose(axis, plane, ua, u, va, v);
                if (eye.squaredDistanceTo(point) > reachSq) continue;
                double cost = angleDeg(look, point.subtract(eye)) + 6.0 * (Math.abs(fu - 0.5) + Math.abs(fv - 0.5));
                if (cost >= bestCost[0]) continue;
                BlockHitResult r = mc.world.raycast(new RaycastContext(eye, point.subtract(normal.multiply(0.02)),
                        RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
                if (r == null || r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(clicked)
                        || r.getSide() != side || !cell.equals(BlockPlacementValidator.placementCell(mc, r))
                        || eye.squaredDistanceTo(r.getPos()) > reachSq
                        || entityOccludes(mc, eye, r.getPos(), margin)) {
                    continue;
                }
                bestCost[0] = cost;
                best = new Click(cell, r);
            }
        }
        return best;
    }

    private static Vec3d compose(Direction.Axis axis, double plane, Direction.Axis ua, double u, Direction.Axis va, double v) {
        double x = axis == Direction.Axis.X ? plane : (ua == Direction.Axis.X ? u : v);
        double y = axis == Direction.Axis.Y ? plane : (ua == Direction.Axis.Y ? u : v);
        double z = axis == Direction.Axis.Z ? plane : (ua == Direction.Axis.Z ? u : v);
        return new Vec3d(x, y, z);
    }

    /** Whether a hittable entity is between {@code eye} and {@code end}, as vanilla's crosshair would see it. */
    public static boolean entityOccludes(MinecraftClient mc, Vec3d eye, Vec3d end, double margin) {
        if (mc.player == null || mc.world == null) return false;
        Box sweep = new Box(eye, end).expand(1.0 + margin);
        for (Entity e : mc.world.getOtherEntities(mc.player, sweep, x -> !x.isSpectator() && x.canHit())) {
            Box b = e.getBoundingBox().expand(e.getTargetingMargin() + margin);
            if (b.contains(eye) || b.raycast(eye, end).isPresent()) return true;
        }
        return false;
    }

    /** Look direction the server will see: the silent rotation while one is active, else the camera. */
    public static Vec3d currentLook(MinecraftClient mc) {
        if (RotationManager.isSilentRotationActive()) {
            return RotationManager.getSilentRotationVec();
        }
        return mc.player.getRotationVec(1.0F);
    }

    /** Blocks whose use action runs before a held block is placed against them (unless sneaking). */
    public static boolean isInteractive(World world, BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        return b instanceof BlockEntityProvider
                || state.createScreenHandlerFactory(world, pos) != null
                || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof NoteBlock
                || b instanceof AbstractRedstoneGateBlock || b instanceof CakeBlock || b instanceof CandleCakeBlock
                || b instanceof RespawnAnchorBlock || b instanceof ComposterBlock || b instanceof DragonEggBlock
                || b instanceof FlowerPotBlock;
    }

    // ── Math ───────────────────────────────────────────────────────────────────────

    private static double overlap(double a0, double a1, double b0, double b1) {
        return Math.min(a1, b1) - Math.max(a0, b0);
    }

    private static Box lerp(Box a, Box b, double t) {
        return new Box(MathHelper.lerp(t, a.minX, b.minX), MathHelper.lerp(t, a.minY, b.minY), MathHelper.lerp(t, a.minZ, b.minZ),
                MathHelper.lerp(t, a.maxX, b.maxX), MathHelper.lerp(t, a.maxY, b.maxY), MathHelper.lerp(t, a.maxZ, b.maxZ));
    }

    private static Vec3d clampLength(Vec3d v, double max) {
        double len = v.length();
        return len > max ? v.multiply(max / len) : v;
    }

    private static double angleDeg(Vec3d a, Vec3d b) {
        double la = a.length();
        double lb = b.length();
        if (la < 1.0e-9 || lb < 1.0e-9) return 180.0;
        return Math.toDegrees(Math.acos(MathHelper.clamp(a.dotProduct(b) / (la * lb), -1.0, 1.0)));
    }
}
