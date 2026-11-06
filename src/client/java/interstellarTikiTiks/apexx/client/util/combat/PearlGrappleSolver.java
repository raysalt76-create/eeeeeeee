package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aims an ender pearl so it runs into a moving player: the server reports that player as the
 * pearl's entity hit and teleports the owner to where the pearl started that tick
 * ({@code EnderPearlEntity#onCollision} uses the last-tick position).
 *
 * <p>Server timing (1.21.11): the pearl spawns when the server handles the use packet, from the
 * thrower's tick-head state, and flies {@link PearlSimulation}'s exact ticks. On pearl tick
 * {@code k} the server tests the pearl's sweep against the target's box, grown by the age
 * tolerance, where the target's own movement packets have put it by then. Against the target's
 * newest position this client has seen, that is {@code k + lead} target ticks later: the ticks
 * since that update plus the round trip, an integer the network phase makes uncertain by about a
 * tick, so {@link #leads} spreads it over neighbouring offsets. A sweep that starts inside the box
 * does not hit it ({@code Box#raycast}).</p>
 *
 * <p>Aim: for each pearl tick, the look that carries the pearl through the forecast box centre
 * seeds a candidate (closed-form drag and gravity, solved for the 1.5 throw speed). Candidates are
 * scored by the chance the throw connects: the weighted share of forecast movements
 * ({@link PlayerMotionSimulation#predict}), lead offsets and the server's triangular launch spread
 * whose flight enters the box. Every look evaluated lies on the mouse-sensitivity lattice of the
 * current look and within the turn allowed, so the chosen look is one the aim step can land on
 * exactly. The best candidates are refined on a coarse grid toward the centre of the region that
 * hits most (the most room for spread), then on a fine grid by the full chance, and the winner is
 * re-flown through the world: blocks, water and every other entity, with the target where the
 * forecast puts it.</p>
 */
public final class PearlGrappleSolver {

    /** The thrower at tick head: pearl spawn, the movement the server adds, and whether on ground. */
    public record Thrower(Vec3d spawn, Vec3d movement, boolean onGround) {}

    /** The target is sampled {@code age + offset} ticks after its newest seen position on pearl tick {@code age}. */
    public record Lead(int offset, double weight) {}

    /**
     * The forecasts, lead offsets, and preferred landing height. Connecting with the real box
     * takes priority; a high landing breaks close ties instead of excluding body hits and forcing
     * the throw toward the top edge. 0 disables the landing preference.
     */
    public record Target(List<PlayerMotionSimulation.Forecast> forecasts, List<Lead> leads, double landingFraction) {
        public Target(List<PlayerMotionSimulation.Forecast> forecasts, List<Lead> leads) {
            this(forecasts, leads, 0.0);
        }

        public int maxOffset() {
            int max = 0;
            for (Lead lead : leads) max = Math.max(max, lead.offset());
            return max;
        }
    }

    /** Looks the aim step can land on: the current look plus whole mouse-sensitivity steps. */
    public record Lattice(float yaw, float pitch, double step) {
        public float yawAt(int i) {
            return yaw + (float) (i * step);
        }

        public float pitchAt(int j) {
            return MathHelper.clamp(pitch + (float) (j * step), -90.0f, 90.0f);
        }
    }

    /**
     * A throw with this packet look: the chance it connects, the pearl tick of the most likely hit
     * (-1 if only unlikely cases hit) and where the owner then lands.
     */
    public record Shot(float yaw, float pitch, double chance, int hitAge, Vec3d landing) {}

    /** Boxes at least this tall are upright players; shorter ones (gliding, swimming) have no landing floor. */
    public static final double UPRIGHT_MIN_HEIGHT = 1.2;
    /** Preferred share of an upright box's height, measured at the start of the collision sweep. */
    public static final double HEAD_BAND_START = 0.6;
    /** At most one percentage point of hit probability may be traded for a higher landing. */
    private static final double LANDING_PREFERENCE = 0.01;

    private static final double DRAG = PearlSimulation.DRAG;
    private static final double GRAVITY = PearlSimulation.GRAVITY;
    private static final double POWER = ThrowPhysics.PEARL_POWER;
    public static final int MAX_AGE = 160;
    /** DRAG^k and the sum of DRAG^1..DRAG^k: the closed-form flight and the launch-spread shift. */
    private static final double[] POW = new double[MAX_AGE + 1];
    private static final double[] SUM = new double[MAX_AGE + 1];

    static {
        POW[0] = 1.0;
        for (int k = 1; k <= MAX_AGE; ++k) {
            POW[k] = POW[k - 1] * DRAG;
            SUM[k] = SUM[k - 1] + POW[k];
        }
    }

    private static final int SEED_SAMPLES = 8;
    /** How far along the line of sight, either way, the off-centre seeds aim (blocks). */
    private static final double SEED_DEPTH = 0.45;
    private static final int REFINED_SEEDS = 4;
    /**
     * Brute-force scan of every look the turn allows: about this many nodes cover the disc, and the
     * best {@value #SCAN_KEEP} join the closed-form seeds. Closed-form seeds each aim at one
     * forecast; the scan finds the looks between them that connect with several at once.
     */
    private static final int SCAN_NODES = 700;
    private static final int SCAN_KEEP = 4;
    private static final double SCAN_MIN_DEGREES = 0.6;
    /** The scan is skipped when a seed already connects with at least this share of the forecasts. */
    private static final double SCAN_SKIP_CHANCE = 0.9;
    /**
     * The ranking pass (seed order, scan, coarse grid) scores only the heaviest forecasts that make
     * up this share of the weight, capped at {@value #FAST_FORECASTS}, and the heaviest
     * {@value #FAST_LEADS} leads; the finalists are scored against everything.
     */
    private static final double FAST_MASS = 0.85;
    private static final int FAST_FORECASTS = 5;
    private static final int FAST_LEADS = 2;
    private static final double COARSE_DEGREES = 0.25;
    private static final int COARSE_RADIUS = 4;
    private static final double FINE_DEGREES = 0.08;
    private static final int FINE_RADIUS = 2;
    /** Chances this close count as equal; the earlier hit, then the smaller turn, wins. */
    private static final double CHANCE_TIE = 0.005;
    private static final double SAME_LOOK = 0.05;

    /** Largest |roll| per axis of each spread rule: every rolled flight stays this close to the mean one. */
    private static final double[] COARSE_REACH = maxAbs(ThrowPhysics.COARSE_POINTS);
    private static final double[] FINE_REACH = maxAbs(ThrowPhysics.FINE_POINTS);
    /** Margin of the envelope test: far above the rounding between a rolled flight and mean + shift. */
    private static final double ENVELOPE_SLACK = 1.0E-6;
    /** Margin of the corridor's world queries: above the hit tests' own 1e-7 edge tolerance. */
    private static final double CORRIDOR_SLACK = 1.0E-4;
    private static final int EVAL_CACHE_LIMIT = 64;

    /**
     * Exact shortcuts that skip work which provably cannot change a result: spread rolls are only
     * tested on the pearl ticks their envelope can reach the box, and rolls whose whole flight
     * crosses empty air are not re-flown through the world. Package-private switches so tests can
     * score the same throws with and without them and require identical answers.
     */
    static boolean pruneSpread = true;
    static boolean useCorridor = true;

    private static final ThreadLocal<EvalCache> EVAL_CACHE = ThreadLocal.withInitial(EvalCache::new);

    private static double[] maxAbs(double[][] points) {
        double[] max = new double[3];
        for (double[] point : points) {
            for (int axis = 0; axis < 3; ++axis) max[axis] = Math.max(max[axis], Math.abs(point[axis]));
        }
        return max;
    }

    private PearlGrappleSolver() {}

    /**
     * Lead offsets for a target whose newest position was seen {@code sinceSeen} ticks ago, with
     * {@code rttMs} round trip: the expected lead {@code x = sinceSeen + rtt / 50} split between
     * its floor and ceiling by proximity, then blurred one tick either way for jitter (0.15 each).
     */
    public static List<Lead> leads(double sinceSeen, double rttMs) {
        double x = Math.max(0.0, sinceSeen + Math.max(0.0, rttMs) / 50.0);
        int base = (int) Math.floor(x);
        double frac = x - base;
        double[] weights = {
                0.15 * (1.0 - frac),
                0.70 * (1.0 - frac) + 0.15 * frac,
                0.15 * (1.0 - frac) + 0.70 * frac,
                0.15 * frac
        };
        List<Lead> leads = new ArrayList<>(4);
        for (int i = 0; i < weights.length; ++i) {
            if (weights[i] > 1.0E-6) leads.add(new Lead(base - 1 + i, weights[i]));
        }
        return leads;
    }

    /**
     * Best throw on {@code lattice} within {@code maxTurn} degrees of its look, or null when none
     * can connect. {@code world}/{@code shooter} null: ballistic flight only (tests).
     */
    public static Shot solve(World world, PlayerEntity shooter, Entity target, Thrower thrower, Target forecast,
                             Lattice lattice, double maxTurn, int maxAge) {
        if (forecast.forecasts().isEmpty() || forecast.leads().isEmpty()) return null;
        int ages = Math.min(maxAge, MAX_AGE);
        WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(lattice.yaw(), lattice.pitch(), (float) maxTurn);
        Search search = new Search(thrower, forecast, lattice, reach, ages);

        List<int[]> seeds = new ArrayList<>();
        Lead[] byWeight = forecast.leads().toArray(new Lead[0]);
        Arrays.sort(byWeight, Comparator.comparingDouble(Lead::weight).reversed());
        for (int f = 0; f < forecast.forecasts().size(); ++f) {
            Box[] boxes = forecast.forecasts().get(f).boxes();
            // The likeliest forecast seeds at its two likeliest leads, the others at the likeliest.
            for (int l = 0; l < Math.min(f == 0 ? 2 : 1, byWeight.length); ++l) {
                seed(thrower, boxes, byWeight[l].offset(), ages, search, seeds);
            }
        }
        double seeded = 0.0;
        for (int[] s : seeds) seeded = Math.max(seeded, search.fast(s[0], s[1]));
        if (seeded < SCAN_SKIP_CHANCE) search.scan(seeds);
        if (seeds.isEmpty()) return null;

        // Fast score (no spread) for every seed; refine the best few.
        seeds.sort(Comparator.comparingDouble((int[] s) -> -search.fast(s[0], s[1]))
                .thenComparingDouble(s -> search.turn(s[0], s[1])));
        List<int[]> refined = new ArrayList<>();
        for (int[] s : seeds) {
            if (refined.size() >= REFINED_SEEDS) break;
            if (search.fast(s[0], s[1]) <= 0.0 && !refined.isEmpty()) break;
            int[] coarse = search.centre(s[0], s[1], strideFor(COARSE_DEGREES, lattice.step()), COARSE_RADIUS);
            int[] fine = search.best(coarse[0], coarse[1], strideFor(FINE_DEGREES, lattice.step()), FINE_RADIUS);
            if (refined.stream().noneMatch(r -> r[0] == fine[0] && r[1] == fine[1])) refined.add(fine);
        }

        // Full spread/forecast refinement is reserved for the strongest finalist. Repeating it
        // for every seed makes the same moving-target solve several times more expensive.
        int[] strongest = refined.stream().max(Comparator.comparingDouble(
                r -> search.precise(r[0], r[1]).quality())).orElseThrow();
        int[] polished = search.polish(strongest[0], strongest[1]);
        if (refined.stream().noneMatch(r -> r[0] == polished[0] && r[1] == polished[1])) refined.add(polished);

        // Rank ballistically first. World-trace only candidates that can improve on the best
        // verified shot; tracing every spread roll for inferior finalists wastes tick time.
        refined.sort(Comparator.comparingDouble((int[] r) -> {
            Shot s = search.shot(r[0], r[1]);
            return s == null ? 1.0 : -s.chance();
        }));
        Shot best = null;
        for (int[] r : refined) {
            Shot possible = search.shot(r[0], r[1]);
            if (possible == null) continue;
            if (best != null && possible.chance() + CHANCE_TIE < best.chance()) continue;
            Shot shot = world != null && shooter != null
                    // Each finalist flies through the world: a nearby look may clip a block another does not.
                    ? evaluate(world, shooter, target, thrower, forecast, lattice.yawAt(r[0]), lattice.pitchAt(r[1]), ages)
                    : possible;
            if (shot != null && (best == null || better(shot, best, reach))) best = shot;
        }
        return best;
    }

    /**
     * Always supplies a reachable throw when no intercept was found. Searches real ballistic
     * paths for a low weighted closest-approach cost to the forecast boxes, then checks the
     * finalists against terrain. A zero-chance shot is an honest best effort, not a predicted hit.
     */
    public static Shot bestEffort(World world, PlayerEntity shooter, Entity target, Thrower thrower, Target forecast,
                                  Lattice lattice, double maxTurn, int maxAge) {
        int ages = Math.max(1, Math.min(maxAge, MAX_AGE));
        if (forecast.forecasts().isEmpty() || forecast.leads().isEmpty()) {
            return new Shot(lattice.yaw(), lattice.pitch(), 0.0, -1, null);
        }
        var reach = new WindChargeIntercept.Reach(lattice.yaw(), lattice.pitch(), (float) maxTurn);
        Search search = new Search(thrower, forecast, lattice, reach, ages);
        List<int[]> nodes = new ArrayList<>();
        nodes.add(new int[]{0, 0});
        Lead lead = forecast.leads().stream().max(Comparator.comparingDouble(Lead::weight)).orElseThrow();
        for (PlayerMotionSimulation.Forecast movement : search.ranking.forecasts()) {
            seed(thrower, movement.boxes(), lead.offset(), ages, search, nodes);
            for (int age : new int[]{0, 4, 8, 16, 32, ages}) {
                int index = Math.min(movement.boxes().length - 1, Math.max(0, age + lead.offset()));
                if (index < 0) continue;
                float[] look = ThrowPhysics.lookAlong(movement.boxes()[index].getCenter().subtract(thrower.spawn()), lattice.yaw());
                nodes.add(search.snap(look[0], look[1]));
            }
        }
        double degrees = Math.max(SCAN_MIN_DEGREES, Math.sqrt(Math.PI * maxTurn * maxTurn / SCAN_NODES));
        int stride = strideFor(degrees, lattice.step());
        int radius = (int) Math.ceil(maxTurn / (stride * lattice.step()));
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                int i = a * stride, j = b * stride;
                if (search.reachable(i, j)) nodes.add(new int[]{i, j});
            }
        }
        nodes.sort(Comparator.comparingDouble((int[] n) -> search.miss(n[0], n[1]))
                .thenComparingDouble(n -> search.turn(n[0], n[1])));
        List<int[]> finalists = new ArrayList<>();
        for (int[] node : nodes) {
            if (finalists.size() >= REFINED_SEEDS) break;
            int[] refined = search.closest(node[0], node[1], strideFor(COARSE_DEGREES, lattice.step()));
            if (finalists.stream().noneMatch(n -> n[0] == refined[0] && n[1] == refined[1])) finalists.add(refined);
        }
        Shot best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int[] node : finalists) {
            float yaw = lattice.yawAt(node[0]), pitch = lattice.pitchAt(node[1]);
            Flight flight = world != null && shooter != null
                    ? Flight.traced(world, shooter, target, thrower, yaw, pitch, null, ages)
                    : Flight.ballistic(thrower, yaw, pitch, ages);
            Shot candidate = world != null && shooter != null
                    ? evaluate(world, shooter, target, thrower, forecast, yaw, pitch, ages)
                    : shot(flight, forecast, yaw, pitch);
            if (candidate == null) candidate = new Shot(yaw, pitch, 0.0, -1, null);
            double miss = missDistance(flight, forecast);
            if (best == null || candidate.chance() > best.chance() + 1.0E-9
                    || (Math.abs(candidate.chance() - best.chance()) < 1.0E-9 && miss < distance)) {
                best = candidate;
                distance = miss;
            }
        }
        return best != null ? best : new Shot(lattice.yaw(), lattice.pitch(), 0.0, -1, null);
    }

    /** Mean-flight closest approach, weighted over movement and network-phase hypotheses. */
    static double missDistance(Flight flight, Target forecast) {
        double total = 0.0, weight = 0.0;
        for (PlayerMotionSimulation.Forecast movement : forecast.forecasts()) {
            for (Lead lead : forecast.leads()) {
                double nearest = Double.POSITIVE_INFINITY;
                for (int age = 1; age <= flight.end; age++) {
                    int index = Math.max(0, age + lead.offset());
                    if (index >= movement.boxes().length) break;
                    Box box = movement.boxes()[index];
                    Vec3d c = box.getCenter();
                    double dx = flight.tx[age] - flight.fx[age], dy = flight.ty[age] - flight.fy[age],
                            dz = flight.tz[age] - flight.fz[age];
                    double length = dx * dx + dy * dy + dz * dz;
                    double t = length < 1.0E-12 ? 0.0 : Math.clamp(((c.x - flight.fx[age]) * dx
                            + (c.y - flight.fy[age]) * dy + (c.z - flight.fz[age]) * dz) / length, 0.0, 1.0);
                    double margin = ThrowPhysics.entityTolerance(age);
                    double x = flight.fx[age] + t * dx, y = flight.fy[age] + t * dy, z = flight.fz[age] + t * dz;
                    double ex = Math.max(0.0, Math.max(box.minX - margin - x, x - box.maxX - margin));
                    double ey = Math.max(0.0, Math.max(box.minY - margin - y, y - box.maxY - margin));
                    double ez = Math.max(0.0, Math.max(box.minZ - margin - z, z - box.maxZ - margin));
                    nearest = Math.min(nearest, ex * ex + ey * ey + ez * ez);
                }
                double w = movement.weight() * lead.weight();
                if (Double.isFinite(nearest) && w > 0.0) { total += w * nearest; weight += w; }
            }
        }
        return weight > 0.0 ? total / weight : Double.POSITIVE_INFINITY;
    }

    /**
     * The flattest look that puts the pearl through the middle of a forecast box: the earliest pearl
     * tick whose launch speed can be the 1.5 throw speed. A plain lob for when no throw is expected to
     * connect; the pearl still lands beside the target. {@code boxes[i]} is the target {@code i - offset}
     * ticks into the flight (the last box repeats). Null when the point is beyond the pearl's reach.
     */
    public static float[] lobLook(Thrower thrower, Box[] boxes, int offset, int maxAge, float yawHint) {
        if (boxes == null || boxes.length == 0) return null;
        Vec3d move = thrower.onGround()
                ? new Vec3d(thrower.movement().x, 0.0, thrower.movement().z) : thrower.movement();
        int ages = Math.min(maxAge, MAX_AGE);
        for (int age = 1; age <= ages; ++age) {
            Vec3d aim = boxes[Math.min(boxes.length - 1, Math.max(0, age + offset))].getCenter();
            double previousS = 0.0;
            double previous = gap(thrower.spawn(), move, aim, age, 0.0);
            for (int i = 1; i <= SEED_SAMPLES; ++i) {
                double s = i / (double) SEED_SAMPLES;
                double value = gap(thrower.spawn(), move, aim, age, s);
                if (Math.signum(value) != Math.signum(previous)) {
                    double root = bisect(thrower.spawn(), move, aim, age, previousS, s);
                    Vec3d launch = required(thrower.spawn(), aim, age, root).subtract(move);
                    if (launch.lengthSquared() > 1.0E-9) return ThrowPhysics.lookAlong(launch, yawHint);
                }
                previousS = s;
                previous = value;
            }
        }
        return null;
    }

    /**
     * Predicted outcome of this packet look: world flights from {@link PearlSimulation#launched},
     * independently traced for launch spread, ignoring {@code target} at its current position
     * and testing it at the forecast positions instead. Null when nothing hits.
     */
    public static Shot evaluate(World world, PlayerEntity shooter, Entity target, Thrower thrower, Target forecast,
                                float yaw, float pitch, int maxAge) {
        int ages = Math.min(maxAge, MAX_AGE);
        if (world == null || shooter == null) return shot(Flight.ballistic(thrower, yaw, pitch, ages), forecast, yaw, pitch);
        // One aim tick re-scores the same looks several times (both solves, the kept throw, the
        // applied look); the thrower instance is per tick, so it scopes the memo.
        EvalCache cache = EVAL_CACHE.get();
        EvalCache.Entry known = cache.find(world, shooter, target, thrower, forecast, yaw, pitch, ages);
        if (known != null) return known.shot();
        Shot shot = evaluateTraced(thrower, forecast, yaw, pitch, ages,
                (spread, limit) -> Flight.traced(world, shooter, target, thrower, yaw, pitch, spread, limit),
                worldCorridor(world, shooter, target));
        cache.put(world, shooter, target, forecast, yaw, pitch, ages, shot);
        return shot;
    }

    /** Traced evaluations of one aim tick, keyed by identity; dropped as soon as another thrower state is used. */
    private static final class EvalCache {
        private record Entry(World world, Entity shooter, Entity target, Target forecast, long time, int yawBits,
                             int pitchBits, int ages, Shot shot) {}

        private Thrower thrower;
        private final List<Entry> entries = new ArrayList<>();

        Entry find(World world, Entity shooter, Entity target, Thrower thrower, Target forecast, float yaw, float pitch,
                   int ages) {
            if (this.thrower != thrower) {
                this.thrower = thrower;
                entries.clear();
                return null;
            }
            long time = world.getTime();
            int yawBits = Float.floatToIntBits(yaw);
            int pitchBits = Float.floatToIntBits(pitch);
            for (Entry entry : entries) {
                if (entry.world() == world && entry.shooter() == shooter && entry.target() == target
                        && entry.forecast() == forecast && entry.time() == time && entry.yawBits() == yawBits
                        && entry.pitchBits() == pitchBits && entry.ages() == ages) {
                    return entry;
                }
            }
            return null;
        }

        void put(World world, Entity shooter, Entity target, Target forecast, float yaw, float pitch, int ages, Shot shot) {
            if (entries.size() >= EVAL_CACHE_LIMIT) entries.remove(0);
            entries.add(new Entry(world, shooter, target, forecast, world.getTime(), Float.floatToIntBits(yaw),
                    Float.floatToIntBits(pitch), ages, shot));
        }
    }

    /** Collision traces are supplied separately so terrain/spread scoring can be tested without a game world. */
    @FunctionalInterface
    interface SpreadFlightTracer {
        Flight trace(Vec3d spread, int ages);
    }

    /**
     * Which rolled ballistic flights provably meet nothing in the world up to their limit (no block,
     * hittable entity, water or border on any of those sweeps): their world trace equals the roll.
     */
    @FunctionalInterface
    interface Corridor {
        boolean[] clear(Flight[] flights, int[] limits);
    }

    static Shot evaluateTraced(Thrower thrower, Target forecast, float yaw, float pitch, int ages,
                               SpreadFlightTracer tracer) {
        return evaluateTraced(thrower, forecast, yaw, pitch, ages, tracer, null);
    }

    static Shot evaluateTraced(Thrower thrower, Target forecast, float yaw, float pitch, int ages,
                               SpreadFlightTracer tracer, Corridor corridor) {
        // Spread changes which blocks and entities a flight clips. Shifting a world-traced mean
        // path cannot score those collisions correctly, so each accepted spread roll is re-flown,
        // unless the corridor proves its flight crosses nothing (then the trace is the roll itself).
        Flight nominal = tracer.trace(null, ages);
        int hitAge = likeliestHit(nominal, forecast);
        List<PlayerMotionSimulation.Forecast> movements = forecast.forecasts();
        List<Lead> leads = forecast.leads();
        int combos = movements.size() * leads.size();
        int[][] candidates = null;
        int[] candidateCounts = null;
        if (pruneSpread) {
            // Every roll lies within the fine rule's reach of the mean ballistic flight, so each
            // (movement, lead) pair is only tested on the ticks that envelope can touch its box.
            Flight mean = Flight.ballistic(thrower, yaw, pitch, ages);
            double unit = ThrowPhysics.SPREAD * POWER;
            candidates = new int[combos][];
            candidateCounts = new int[combos];
            int c = 0;
            for (PlayerMotionSimulation.Forecast movement : movements) {
                for (Lead lead : leads) {
                    int[] buffer = new int[Math.max(1, mean.end)];
                    candidateCounts[c] = candidateAges(mean, FINE_REACH[0] * unit, FINE_REACH[1] * unit,
                            FINE_REACH[2] * unit, movement.boxes(), lead.offset(), buffer);
                    candidates[c++] = buffer;
                }
            }
        }
        int samples = ThrowPhysics.FINE_COUNT;
        Flight[] rolls = new Flight[samples];
        Vec3d[] spreads = new Vec3d[samples];
        int[] lastHits = new int[samples];
        for (int sample = 0; sample < samples; sample++) {
            double[] point = ThrowPhysics.FINE_POINTS[sample];
            Vec3d spread = new Vec3d(point[0], point[1], point[2]).multiply(ThrowPhysics.SPREAD);
            Flight possible = Flight.ballistic(thrower, yaw, pitch, spread, ages);
            int lastHit = 0;
            int c = 0;
            for (PlayerMotionSimulation.Forecast movement : movements) {
                for (Lead lead : leads) {
                    int age;
                    if (candidates == null) {
                        age = hitAge(possible, 0, 0, 0, movement.boxes(), lead.offset());
                    } else {
                        age = candidateCounts[c] == 0 ? -1 : hitAgeAt(possible, 0.0, 0.0, 0.0, movement.boxes(),
                                lead.offset(), candidates[c], candidateCounts[c]);
                    }
                    lastHit = Math.max(lastHit, age);
                    c++;
                }
            }
            rolls[sample] = possible;
            spreads[sample] = spread;
            lastHits[sample] = lastHit;
        }
        boolean[] clear = corridor != null && useCorridor ? corridor.clear(rolls, lastHits) : null;
        double chance = 0.0;
        for (int sample = 0; sample < samples; sample++) {
            if (lastHits[sample] == 0) continue;
            Flight traced = clear != null && clear[sample] ? rolls[sample] : tracer.trace(spreads[sample], lastHits[sample]);
            chance += accuracy(traced, forecast, false, true).chance() * ThrowPhysics.FINE_WEIGHTS[sample];
        }
        if (chance <= 0.0) return null;
        Vec3d landing = hitAge > 0 ? new Vec3d(nominal.fx[hitAge], nominal.fy[hitAge], nominal.fz[hitAge]) : null;
        return new Shot(yaw, pitch, chance, hitAge, landing);
    }

    /**
     * The world corridor: per pearl tick one block, entity, water and border query over the union of
     * every still-clear roll's sweep, then plain box tests per roll. A roll is clear only when none of
     * its sweeps up to its limit can touch a collision shape, a hittable entity (its owner while the
     * pearl has not left it excepted, exactly as the server skips it) or water, so its world trace is
     * its ballistic flight. Anything uncertain (vehicles, the border) marks the roll for a real trace.
     */
    static Corridor worldCorridor(World world, Entity owner, Entity ignored) {
        return (flights, limits) -> {
            int n = flights.length;
            boolean[] clean = new boolean[n];
            int horizon = 0;
            for (int i = 0; i < n; ++i) {
                if (limits[i] > 0) {
                    clean[i] = true;
                    horizon = Math.max(horizon, limits[i]);
                }
            }
            if (horizon == 0) return clean;
            if (owner != null && (owner.hasVehicle() || owner.hasPassengers())) return new boolean[n];
            Box ownerBox = owner == null ? null : owner.getBoundingBox();
            boolean ownerHittable = owner != null && EntityPredicates.CAN_HIT.test(owner);
            boolean[] leftOwner = new boolean[n];
            if (owner == null) Arrays.fill(leftOwner, true);
            double half = PearlSimulation.WIDTH / 2.0f;
            for (int age = 1; age <= horizon; ++age) {
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                double landMinY = Double.POSITIVE_INFINITY, landMaxY = Double.NEGATIVE_INFINITY;
                double landMinX = Double.POSITIVE_INFINITY, landMaxX = Double.NEGATIVE_INFINITY;
                double landMinZ = Double.POSITIVE_INFINITY, landMaxZ = Double.NEGATIVE_INFINITY;
                boolean any = false;
                for (int i = 0; i < n; ++i) {
                    if (!clean[i] || limits[i] < age) continue;
                    any = true;
                    Flight f = flights[i];
                    minX = Math.min(minX, Math.min(f.fx[age], f.tx[age]));
                    maxX = Math.max(maxX, Math.max(f.fx[age], f.tx[age]));
                    minY = Math.min(minY, Math.min(f.fy[age], f.ty[age]));
                    maxY = Math.max(maxY, Math.max(f.fy[age], f.ty[age]));
                    minZ = Math.min(minZ, Math.min(f.fz[age], f.tz[age]));
                    maxZ = Math.max(maxZ, Math.max(f.fz[age], f.tz[age]));
                    landMinX = Math.min(landMinX, f.tx[age] - half);
                    landMaxX = Math.max(landMaxX, f.tx[age] + half);
                    landMinY = Math.min(landMinY, f.ty[age]);
                    landMaxY = Math.max(landMaxY, f.ty[age] + PearlSimulation.HEIGHT);
                    landMinZ = Math.min(landMinZ, f.tz[age] - half);
                    landMaxZ = Math.max(landMaxZ, f.tz[age] + half);
                }
                if (!any) break;
                Box sweep = new Box(minX, minY, minZ, maxX, maxY, maxZ).expand(CORRIDOR_SLACK);
                // Water after the move sets the next tick's drag; the border ends a ray that leaves it.
                boolean blocked = !world.getWorldBorder().contains(sweep)
                        || ThrowPhysics.touchingWater(world, new Box(landMinX, landMinY, landMinZ, landMaxX, landMaxY, landMaxZ));
                List<Box> solids = null;
                List<Entity> entities = List.of();
                double tolerance = ThrowPhysics.entityTolerance(age);
                if (!blocked) {
                    for (VoxelShape shape : world.getBlockCollisions(null, sweep)) {
                        if (shape.isEmpty()) continue;
                        if (solids == null) solids = new ArrayList<>();
                        solids.addAll(shape.getBoundingBoxes());
                    }
                    entities = world.getOtherEntities(null, sweep.expand(tolerance),
                            e -> e != ignored && e.canBeHitByProjectile());
                }
                for (int i = 0; i < n; ++i) {
                    if (!clean[i] || limits[i] < age) continue;
                    if (blocked) {
                        clean[i] = false;
                        continue;
                    }
                    Flight f = flights[i];
                    Box segment = new Box(f.fx[age], f.fy[age], f.fz[age], f.tx[age], f.ty[age], f.tz[age])
                            .expand(CORRIDOR_SLACK);
                    boolean touched = false;
                    if (solids != null) {
                        for (Box solid : solids) {
                            if (segment.intersects(solid)) {
                                touched = true;
                                break;
                            }
                        }
                    }
                    if (!touched) {
                        for (Entity entity : entities) {
                            if (entity == owner && !leftOwner[i]) continue;
                            if (segment.intersects(entity.getBoundingBox().expand(tolerance))) {
                                touched = true;
                                break;
                            }
                        }
                    }
                    if (touched) {
                        clean[i] = false;
                        continue;
                    }
                    if (!leftOwner[i]) {
                        // ProjectileEntity#hasLeftOwner after the move, judged a hair early: a pearl
                        // counted as left too soon only makes the owner hittable, never the reverse.
                        Box moved = PearlSimulation.box(new Vec3d(f.tx[age], f.ty[age], f.tz[age]))
                                .stretch(f.tx[age] - f.fx[age], f.ty[age] - f.fy[age], f.tz[age] - f.fz[age])
                                .expand(1.0).contract(CORRIDOR_SLACK);
                        leftOwner[i] = !(ownerHittable && moved.intersects(ownerBox));
                    }
                }
            }
            return clean;
        };
    }

    private static Shot shot(Flight flight, Target forecast, float yaw, float pitch) {
        double chance = accuracy(flight, forecast, true, true).chance();
        return shot(flight, forecast, yaw, pitch, chance);
    }

    private static Shot shot(Flight flight, Target forecast, float yaw, float pitch, double chance) {
        if (chance <= 0.0) return null;
        int age = likeliestHit(flight, forecast);
        Vec3d landing = age > 0 ? new Vec3d(flight.fx[age], flight.fy[age], flight.fz[age]) : null;
        return new Shot(yaw, pitch, chance, age, landing);
    }

    /** Higher chance wins; near-ties go to the earlier hit (less to go wrong), then the smaller turn. */
    private static boolean better(Shot a, Shot b, WindChargeIntercept.Reach reach) {
        if (a.chance() > b.chance() + CHANCE_TIE) return true;
        if (a.chance() + CHANCE_TIE < b.chance()) return false;
        int ageA = a.hitAge() < 0 ? Integer.MAX_VALUE : a.hitAge();
        int ageB = b.hitAge() < 0 ? Integer.MAX_VALUE : b.hitAge();
        if (ageA != ageB) return ageA < ageB;
        return reach.turn(a.yaw(), a.pitch()) < reach.turn(b.yaw(), b.pitch());
    }

    // ── Seeds ───────────────────────────────────────────────────────────────

    /**
     * Looks that put the pearl through a point of the forecast box on some point of its sweep on
     * each tick: the launch velocity for that point is linear in the throw (drag and gravity in
     * closed form), so the roots of its speed minus 1.5 along the sweep are the throws that get
     * there. The box moves between ticks, so the speed can also jump across 1.5 at a tick
     * boundary; the direction there seeds too, and refinement corrects its speed mismatch.
     *
     * <p>Besides the centre, points {@value #SEED_DEPTH} blocks nearer and farther along the line
     * of sight seed: the server tests one sweep per tick, so a fast target closing on the pearl can
     * pass between two sweeps, and a sweep that starts inside the box does not count. Moving the
     * aim point along the sight line moves where on its sweep the pearl meets the box.</p>
     */
    private static void seed(Thrower thrower, Box[] boxes, int offset, int ages, Search search, List<int[]> out) {
        Vec3d move = thrower.onGround()
                ? new Vec3d(thrower.movement().x, 0.0, thrower.movement().z) : thrower.movement();
        // Body-centred solutions must compete with upper-body ones. Seeding only the preferred
        // landing band can miss an entire lower, more reliable region of the sensitivity lattice.
        double[] fractions = search.forecast.landingFraction() > 0.0
                ? new double[]{0.0, search.forecast.landingFraction()} : new double[]{0.0};
        for (double fraction : fractions) {
            for (double depth : new double[]{0.0, -SEED_DEPTH, SEED_DEPTH}) {
                double carried = Double.NaN;
                for (int age = 1; age <= ages; ++age) {
                    int index = Math.max(0, age + offset);
                    if (index >= boxes.length) break;
                    Vec3d centre = aimPoint(boxes[index], fraction);
                    Vec3d sight = centre.subtract(thrower.spawn());
                    Vec3d aim = sight.lengthSquared() > 1.0E-9 ? centre.add(sight.normalize().multiply(depth)) : centre;
                    double previousS = 0.0;
                    double previous = gap(thrower.spawn(), move, aim, age, 0.0);
                    if (!Double.isNaN(carried) && Math.signum(carried) != Math.signum(previous)) {
                        addSeed(required(thrower.spawn(), aim, age, 0.0).subtract(move), search, out);
                    }
                    for (int i = 1; i <= SEED_SAMPLES; ++i) {
                        double s = i / (double) SEED_SAMPLES;
                        double value = gap(thrower.spawn(), move, aim, age, s);
                        if (Math.signum(value) != Math.signum(previous)) {
                            double root = bisect(thrower.spawn(), move, aim, age, previousS, s);
                            addSeed(required(thrower.spawn(), aim, age, root).subtract(move), search, out);
                        }
                        previousS = s;
                        previous = value;
                    }
                    carried = previous;
                }
            }
        }
    }

    /**
     * The body centre and, as a separate candidate, the preferred upper landing band.
     */
    static Vec3d aimPoint(Box box, double landingFraction) {
        double height = box.maxY - box.minY;
        if (landingFraction <= 0.0 || height < UPRIGHT_MIN_HEIGHT) return box.getCenter();
        return new Vec3d((box.minX + box.maxX) * 0.5, box.minY + height * (1.0 + landingFraction) * 0.5,
                (box.minZ + box.maxZ) * 0.5);
    }

    private static void addSeed(Vec3d direction, Search search, List<int[]> out) {
        float[] look = ThrowPhysics.lookAlong(direction, search.lattice.yaw());
        int[] node = search.snap(look[0], look[1]);
        double step = search.lattice.step();
        for (int[] n : out) {
            if (Math.abs(n[0] - node[0]) * step < SAME_LOOK && Math.abs(n[1] - node[1]) * step < SAME_LOOK) return;
        }
        out.add(node);
    }

    /**
     * Launch velocity (before the thrower's movement) that puts the pearl at {@code aim} a share
     * {@code s} along its sweep on tick {@code age}: position = spawn + v * A - g * d / (1 - d) * (t - A),
     * with A = sum(d^1..d^(age-1)) + s * d^age and t = age - 1 + s.
     */
    static Vec3d required(Vec3d spawn, Vec3d aim, int age, double s) {
        double a = SUM[age - 1] + s * POW[age];
        double lift = GRAVITY * DRAG / (1.0 - DRAG) * (age - 1 + s - a);
        return new Vec3d((aim.x - spawn.x) / a, (aim.y - spawn.y + lift) / a, (aim.z - spawn.z) / a);
    }

    static double gap(Vec3d spawn, Vec3d move, Vec3d aim, int age, double s) {
        if (SUM[age - 1] + s * POW[age] < 1.0E-9) return Double.MAX_VALUE;
        return required(spawn, aim, age, s).subtract(move).length() - POWER;
    }

    static double bisect(Vec3d spawn, Vec3d move, Vec3d aim, int age, double low, double high) {
        double lowValue = gap(spawn, move, aim, age, low);
        for (int i = 0; i < 40; ++i) {
            double mid = (low + high) * 0.5;
            double value = gap(spawn, move, aim, age, mid);
            if (Math.signum(value) == Math.signum(lowValue)) {
                low = mid;
                lowValue = value;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    private static int strideFor(double degrees, double step) {
        return Math.max(1, (int) Math.round(degrees / step));
    }

    // ── Search on the lattice ───────────────────────────────────────────────

    private static final class Search {
        final Thrower thrower;
        final Target forecast;
        /** The heaviest of {@link #forecast}, for the ranking pass. */
        final Target ranking;
        final Lattice lattice;
        final WindChargeIntercept.Reach reach;
        final int ages;
        final Map<Long, Double> fastScores = new HashMap<>();
        final Map<Long, Double> fullScores = new HashMap<>();
        final Map<Long, Accuracy> preciseScores = new HashMap<>();
        final Map<Long, Shot> shots = new HashMap<>();
        final Map<Long, Double> missScores = new HashMap<>();

        Search(Thrower thrower, Target forecast, Lattice lattice, WindChargeIntercept.Reach reach, int ages) {
            this.thrower = thrower;
            this.forecast = forecast;
            this.ranking = heaviest(forecast);
            this.lattice = lattice;
            this.reach = reach;
            this.ages = ages;
        }

        /**
         * Scores a grid over the whole disc of reachable looks (mean throw, every forecast and
         * lead) and adds the best nodes to {@code out}; ties go to the smaller turn.
         */
        void scan(List<int[]> out) {
            double radius = reach.maxTurn();
            double step = lattice.step();
            double degrees = Math.max(SCAN_MIN_DEGREES, Math.sqrt(Math.PI * radius * radius / SCAN_NODES));
            int stride = Math.max(1, (int) Math.round(degrees / step));
            int n = (int) Math.ceil(radius / (stride * step));
            List<int[]> best = new ArrayList<>();
            for (int a = -n; a <= n; ++a) {
                for (int b = -n; b <= n; ++b) {
                    int i = a * stride;
                    int j = b * stride;
                    if (!reachable(i, j) || fast(i, j) <= 0.0) continue;
                    best.add(new int[]{i, j});
                }
            }
            best.sort(Comparator.comparingDouble((int[] node) -> -fast(node[0], node[1]))
                    .thenComparingDouble(node -> turn(node[0], node[1])));
            for (int k = 0; k < Math.min(SCAN_KEEP, best.size()); ++k) out.add(best.get(k));
        }

        /** The lattice node nearest a look, pulled back inside the turn allowed. */
        int[] snap(float yaw, float pitch) {
            float[] clamped = reach.clamp(yaw, pitch);
            double step = lattice.step();
            int i = (int) Math.round(MathHelper.wrapDegrees(clamped[0] - lattice.yaw()) / step);
            int j = (int) Math.round((clamped[1] - lattice.pitch()) / step);
            while ((i != 0 || j != 0) && !reachable(i, j)) {
                i -= Integer.signum(i);
                j -= Integer.signum(j);
            }
            return new int[]{i, j};
        }

        boolean reachable(int i, int j) {
            return turn(i, j) <= reach.maxTurn() + 1.0E-4;
        }

        double turn(int i, int j) {
            return reach.turn(lattice.yawAt(i), lattice.pitchAt(j));
        }

        double miss(int i, int j) {
            return missScores.computeIfAbsent(key(i, j), k -> missDistance(
                    Flight.ballistic(thrower, lattice.yawAt(i), lattice.pitchAt(j), ages), ranking));
        }

        int[] closest(int ci, int cj, int stride) {
            int[] best = {ci, cj};
            double distance = miss(ci, cj);
            for (int a = -COARSE_RADIUS; a <= COARSE_RADIUS; a++) {
                for (int b = -COARSE_RADIUS; b <= COARSE_RADIUS; b++) {
                    int i = ci + a * stride, j = cj + b * stride;
                    if (!reachable(i, j)) continue;
                    double candidate = miss(i, j);
                    if (candidate < distance) { best = new int[]{i, j}; distance = candidate; }
                }
            }
            return best;
        }

        double fast(int i, int j) {
            return fastScores.computeIfAbsent(key(i, j),
                    k -> accuracy(Flight.ballistic(thrower, lattice.yawAt(i), lattice.pitchAt(j), ages), ranking, false, false).quality());
        }

        double full(int i, int j) {
            return fullScores.computeIfAbsent(key(i, j),
                    k -> accuracy(Flight.ballistic(thrower, lattice.yawAt(i), lattice.pitchAt(j), ages), ranking, true, false).quality());
        }

        Shot shot(int i, int j) {
            return shots.computeIfAbsent(key(i, j), k -> PearlGrappleSolver.shot(
                    Flight.ballistic(thrower, lattice.yawAt(i), lattice.pitchAt(j), ages),
                    forecast, lattice.yawAt(i), lattice.pitchAt(j), precise(i, j).chance()));
        }

        Accuracy precise(int i, int j) {
            return preciseScores.computeIfAbsent(key(i, j), k -> accuracy(
                    Flight.ballistic(thrower, lattice.yawAt(i), lattice.pitchAt(j), ages), forecast, true, true));
        }

        /**
         * The middle of the best-scoring nodes on a grid around (ci, cj): where the most forecast
         * cases hit with the most room either side, which is what launch spread needs.
         */
        int[] centre(int ci, int cj, int stride, int radius) {
            double max = -1.0;
            List<int[]> nodes = new ArrayList<>();
            for (int a = -radius; a <= radius; ++a) {
                for (int b = -radius; b <= radius; ++b) {
                    int i = ci + a * stride;
                    int j = cj + b * stride;
                    if (!reachable(i, j)) continue;
                    double score = fast(i, j);
                    if (score > max + 1.0E-9) {
                        max = score;
                        nodes.clear();
                    }
                    if (score >= max - 1.0E-9) nodes.add(new int[]{i, j});
                }
            }
            if (nodes.isEmpty()) return new int[]{ci, cj};
            double mi = 0.0;
            double mj = 0.0;
            for (int[] node : nodes) {
                mi += node[0];
                mj += node[1];
            }
            mi /= nodes.size();
            mj /= nodes.size();
            int[] centre = {(int) Math.round(mi), (int) Math.round(mj)};
            if (reachable(centre[0], centre[1]) && fast(centre[0], centre[1]) >= max - 1.0E-9) return centre;
            int[] nearest = nodes.get(0);
            double bestDistance = Double.MAX_VALUE;
            for (int[] node : nodes) {
                double distance = (node[0] - mi) * (node[0] - mi) + (node[1] - mj) * (node[1] - mj);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = node;
                }
            }
            return nearest;
        }

        /** The node with the highest full chance on a grid around (ci, cj); ties keep the nearer one. */
        int[] best(int ci, int cj, int stride, int radius) {
            int[] best = {ci, cj};
            double bestScore = reachable(ci, cj) ? full(ci, cj) : -1.0;
            for (int a = -radius; a <= radius; ++a) {
                for (int b = -radius; b <= radius; ++b) {
                    int i = ci + a * stride;
                    int j = cj + b * stride;
                    if ((a == 0 && b == 0) || !reachable(i, j)) continue;
                    double score = full(i, j);
                    if (score > bestScore + 1.0E-9) {
                        bestScore = score;
                        best = new int[]{i, j};
                    }
                }
            }
            return best;
        }

        /** Fine spread scoring only for the finalist and its immediate lattice neighbours. */
        int[] polish(int ci, int cj) {
            int[] best = {ci, cj};
            double bestScore = -1.0;
            for (int[] delta : new int[][]{{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int i = ci + delta[0], j = cj + delta[1];
                if (!reachable(i, j)) continue;
                double score = precise(i, j).quality();
                if (score > bestScore + 1.0E-9) {
                    bestScore = score;
                    best = new int[]{i, j};
                }
            }
            return best;
        }

        private static long key(int i, int j) {
            return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
        }
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    /** The forecasts and leads that carry most of the weight, for ranking looks cheaply. */
    private static Target heaviest(Target full) {
        if (full.forecasts().size() <= FAST_FORECASTS && full.leads().size() <= FAST_LEADS) return full;
        List<PlayerMotionSimulation.Forecast> forecasts = new ArrayList<>(full.forecasts());
        forecasts.sort(Comparator.comparingDouble(PlayerMotionSimulation.Forecast::weight).reversed());
        double total = 0.0;
        for (PlayerMotionSimulation.Forecast f : forecasts) total += f.weight();
        List<PlayerMotionSimulation.Forecast> kept = new ArrayList<>();
        double mass = 0.0;
        for (PlayerMotionSimulation.Forecast f : forecasts) {
            if (kept.size() >= FAST_FORECASTS || (!kept.isEmpty() && mass >= FAST_MASS * total)) break;
            kept.add(f);
            mass += f.weight();
        }
        List<Lead> leads = new ArrayList<>(full.leads());
        leads.sort(Comparator.comparingDouble(Lead::weight).reversed());
        if (leads.size() > FAST_LEADS) leads = new ArrayList<>(leads.subList(0, FAST_LEADS));
        return new Target(kept, leads, full.landingFraction());
    }

    /**
     * Weighted share of (forecast, lead, launch spread) cases whose flight enters the box first;
     * without {@code spread} only the mean throw counts.
     */
    private record Accuracy(double chance, double highLanding) {
        double quality() { return chance + LANDING_PREFERENCE * highLanding; }
    }

    private static Accuracy accuracy(Flight flight, Target forecast, boolean spread, boolean fine) {
        double total = 0.0;
        double high = 0.0;
        double weights = 0.0;
        double unit = ThrowPhysics.SPREAD * POWER;
        double[][] points = fine ? ThrowPhysics.FINE_POINTS : ThrowPhysics.COARSE_POINTS;
        double[] sampleWeights = fine ? ThrowPhysics.FINE_WEIGHTS : ThrowPhysics.COARSE_WEIGHTS;
        double[] reach = fine ? FINE_REACH : COARSE_REACH;
        int samples = spread ? points.length : 1;
        // With spread, a pair is only tested on the ticks the envelope of every roll can reach its box.
        int[] ages = spread && pruneSpread ? new int[Math.max(1, flight.end)] : null;
        for (PlayerMotionSimulation.Forecast movement : forecast.forecasts()) {
            for (Lead lead : forecast.leads()) {
                double weight = movement.weight() * lead.weight();
                weights += weight;
                int count = 0;
                if (ages != null) {
                    count = candidateAges(flight, reach[0] * unit, reach[1] * unit, reach[2] * unit,
                            movement.boxes(), lead.offset(), ages);
                    if (count == 0) continue;
                }
                for (int sample = 0; sample < samples; sample++) {
                    double[] point = spread ? points[sample] : null;
                    double sx = spread ? point[0] * unit : 0.0;
                    double sy = spread ? point[1] * unit : 0.0;
                    double sz = spread ? point[2] * unit : 0.0;
                    int age = ages != null
                            ? hitAgeAt(flight, sx, sy, sz, movement.boxes(), lead.offset(), ages, count)
                            : hitAge(flight, sx, sy, sz, movement.boxes(), lead.offset());
                    if (age > 0) {
                        double sampleWeight = weight * (spread ? sampleWeights[sample] : 1.0);
                        total += sampleWeight;
                        Box box = movement.boxes()[Math.max(0, age + lead.offset())];
                        double height = box.maxY - box.minY;
                        if (forecast.landingFraction() <= 0.0 || height < UPRIGHT_MIN_HEIGHT
                                || flight.fy[age] + sy * SUM[age - 1] >= box.minY + height * forecast.landingFraction()) {
                            high += sampleWeight;
                        }
                    }
                }
            }
        }
        return weights > 0.0 ? new Accuracy(total / weights, high / weights) : new Accuracy(0.0, 0.0);
    }

    /** The hit of the likeliest forecast at its likeliest lead, else the first case that hits; -1 for none. */
    private static int likeliestHit(Flight flight, Target forecast) {
        if (forecast.forecasts().isEmpty() || forecast.leads().isEmpty()) return -1;
        PlayerMotionSimulation.Forecast top = forecast.forecasts().stream()
                .max(Comparator.comparingDouble(PlayerMotionSimulation.Forecast::weight)).orElseThrow();
        Lead likeliest = forecast.leads().stream().max(Comparator.comparingDouble(Lead::weight)).orElseThrow();
        int age = hitAge(flight, 0.0, 0.0, 0.0, top.boxes(), likeliest.offset());
        if (age > 0) return age;
        for (PlayerMotionSimulation.Forecast movement : forecast.forecasts()) {
            for (Lead lead : forecast.leads()) {
                age = hitAge(flight, 0.0, 0.0, 0.0, movement.boxes(), lead.offset());
                if (age > 0) return age;
            }
        }
        return -1;
    }

    /**
     * The pearl tick whose sweep, shifted by a launch spread of velocity {@code (sx, sy, sz)}, enters
     * the target's box (grown by that tick's tolerance) before anything else ends the flight; -1
     * for none. A spread shifts the position after k ticks by spread * sum(d^1..d^k).
     */
    static int hitAge(Flight flight, double sx, double sy, double sz, Box[] boxes, int offset) {
        return hitAge(flight, sx, sy, sz, boxes, offset, 0.0);
    }

    /**
     * {@link #hitAge(Flight, double, double, double, Box[], int)} where a hit whose sweep started
     * below {@code landingFraction} of an upright box's height ends the flight as a miss: the owner
     * would be teleported beside the target's legs, and the pearl is spent.
     */
    static int hitAge(Flight flight, double sx, double sy, double sz, Box[] boxes, int offset,
                      double landingFraction) {
        for (int age = 1; age <= flight.end; ++age) {
            int index = Math.max(0, age + offset);
            if (index >= boxes.length) return -1;
            int result = sweepHit(flight, sx, sy, sz, boxes[index], age, landingFraction);
            if (result != NO_ENTRY) return result;
        }
        return -1;
    }

    /**
     * {@link #hitAge(Flight, double, double, double, Box[], int)} tested only on {@code ages[0..count)}
     * (ascending): the ticks {@link #candidateAges} found the shifted flight can reach the box on.
     * Every other tick provably has no entry, so the first hit is the same.
     */
    static int hitAgeAt(Flight flight, double sx, double sy, double sz, Box[] boxes, int offset, int[] ages, int count) {
        for (int i = 0; i < count; ++i) {
            int age = ages[i];
            int result = sweepHit(flight, sx, sy, sz, boxes[Math.max(0, age + offset)], age, 0.0);
            if (result != NO_ENTRY) return result;
        }
        return -1;
    }

    /**
     * Pearl ticks, ascending, on which a flight shifted from {@code flight} by a launch spread of at most
     * {@code rx, ry, rz} per axis (velocity) can enter the tolerance-grown box: the sweep of every such
     * flight lies in the mean sweep's box grown by the spread times SUM. Stops where the boxes end.
     */
    static int candidateAges(Flight flight, double rx, double ry, double rz, Box[] boxes, int offset, int[] out) {
        int count = 0;
        for (int age = 1; age <= flight.end; ++age) {
            int index = Math.max(0, age + offset);
            if (index >= boxes.length) break;
            Box box = boxes[index];
            double margin = ThrowPhysics.entityTolerance(age) + ENVELOPE_SLACK;
            double before = SUM[age - 1];
            double after = SUM[age];
            if (outside(flight.fx[age], flight.tx[age], rx * before, rx * after, box.minX - margin, box.maxX + margin)
                    || outside(flight.fy[age], flight.ty[age], ry * before, ry * after, box.minY - margin, box.maxY + margin)
                    || outside(flight.fz[age], flight.tz[age], rz * before, rz * after, box.minZ - margin, box.maxZ + margin)) {
                continue;
            }
            out[count++] = age;
        }
        return count;
    }

    private static boolean outside(double from, double to, double fromReach, double toReach, double min, double max) {
        return Math.max(from + fromReach, to + toReach) < min || Math.min(from - fromReach, to - toReach) > max;
    }

    private static final int NO_ENTRY = -2;

    /** One tick of {@link #hitAge}: the age on a hit, -1 when the sweep ends the flight as a miss, else {@link #NO_ENTRY}. */
    private static int sweepHit(Flight flight, double sx, double sy, double sz, Box box, int age, double landingFraction) {
        double tolerance = ThrowPhysics.entityTolerance(age);
        double before = SUM[age - 1];
        double after = SUM[age];
        double ax = flight.fx[age] + sx * before;
        double ay = flight.fy[age] + sy * before;
        double az = flight.fz[age] + sz * before;
        double dx = flight.tx[age] + sx * after - ax;
        double dy = flight.ty[age] + sy * after - ay;
        double dz = flight.tz[age] + sz * after - az;
        double t = entry(box.minX - tolerance, box.minY - tolerance, box.minZ - tolerance,
                box.maxX + tolerance, box.maxY + tolerance, box.maxZ + tolerance, ax, ay, az, dx, dy, dz);
        if (t < 0.0) return NO_ENTRY;
        // Another entity on the last sweep takes the pearl if it is nearer.
        if (age == flight.end && flight.blockedSq <= t * t * (dx * dx + dy * dy + dz * dz)) return -1;
        double height = box.maxY - box.minY;
        if (landingFraction > 0.0 && height >= UPRIGHT_MIN_HEIGHT
                && ay < box.minY + height * landingFraction) {
            return -1;
        }
        return age;
    }

    /**
     * {@code Box#raycast}: the share (0, 1) of the sweep at which it enters the box through a face
     * it is moving toward, or -1. A sweep starting inside the box never enters it.
     */
    static double entry(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                        double x, double y, double z, double dx, double dy, double dz) {
        double best = 1.0;
        boolean hit = false;
        if (dx > 1.0E-7 || dx < -1.0E-7) {
            double t = ((dx > 0.0 ? minX : maxX) - x) / dx;
            if (0.0 < t && t < best && within(y + t * dy, minY, maxY) && within(z + t * dz, minZ, maxZ)) {
                best = t;
                hit = true;
            }
        }
        if (dy > 1.0E-7 || dy < -1.0E-7) {
            double t = ((dy > 0.0 ? minY : maxY) - y) / dy;
            if (0.0 < t && t < best && within(z + t * dz, minZ, maxZ) && within(x + t * dx, minX, maxX)) {
                best = t;
                hit = true;
            }
        }
        if (dz > 1.0E-7 || dz < -1.0E-7) {
            double t = ((dz > 0.0 ? minZ : maxZ) - z) / dz;
            if (0.0 < t && t < best && within(x + t * dx, minX, maxX) && within(y + t * dy, minY, maxY)) {
                best = t;
                hit = true;
            }
        }
        return hit ? best : -1.0;
    }

    private static boolean within(double value, double min, double max) {
        return min - 1.0E-7 < value && value < max + 1.0E-7;
    }

    // ── Flights ─────────────────────────────────────────────────────────────

    /** A pearl's sweeps by age 1..end, and how far along the last one another entity took the pearl. */
    static final class Flight {
        final double[] fx;
        final double[] fy;
        final double[] fz;
        final double[] tx;
        final double[] ty;
        final double[] tz;
        int end;
        double blockedSq = Double.POSITIVE_INFINITY;

        private Flight(int ages) {
            fx = new double[ages + 1];
            fy = new double[ages + 1];
            fz = new double[ages + 1];
            tx = new double[ages + 1];
            ty = new double[ages + 1];
            tz = new double[ages + 1];
        }

        /** {@link PearlSimulation#step()} without a world: gravity, drag, move, in the same double math. */
        static Flight ballistic(Thrower thrower, float yaw, float pitch, int ages) {
            return ballistic(thrower, yaw, pitch, null, ages);
        }

        static Flight ballistic(Thrower thrower, float yaw, float pitch, Vec3d spread, int ages) {
            Vec3d v = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                    ThrowPhysics.PEARL_POWER, spread, thrower.movement(), thrower.onGround());
            Flight flight = new Flight(ages);
            double x = thrower.spawn().x;
            double y = thrower.spawn().y;
            double z = thrower.spawn().z;
            double vx = v.x;
            double vy = v.y;
            double vz = v.z;
            for (int age = 1; age <= ages; ++age) {
                vx = (vx + 0.0) * DRAG;
                vy = (vy - GRAVITY) * DRAG;
                vz = (vz + 0.0) * DRAG;
                flight.fx[age] = x;
                flight.fy[age] = y;
                flight.fz[age] = z;
                x += vx;
                y += vy;
                z += vz;
                flight.tx[age] = x;
                flight.ty[age] = y;
                flight.tz[age] = z;
            }
            flight.end = ages;
            return flight;
        }

        /** The exact world flight; the sweep that ends it stops at the block or entity it hit. */
        static Flight traced(World world, PlayerEntity shooter, Entity target, Thrower thrower, float yaw, float pitch,
                             Vec3d spread, int ages) {
            Vec3d velocity = ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                    ThrowPhysics.PEARL_POWER, spread, thrower.movement(), thrower.onGround());
            PearlSimulation.Path path = PearlSimulation.launched(world, shooter, thrower.spawn(), velocity)
                    .ignoring(target).trace(ages);
            return fromPath(path, ages);
        }

        static Flight fromPath(PearlSimulation.Path path, int ages) {
            Flight flight = new Flight(ages);
            for (PearlSimulation.Segment segment : path.segments()) {
                int age = segment.age();
                if (age < 1 || age > ages) continue;
                flight.fx[age] = segment.from().x;
                flight.fy[age] = segment.from().y;
                flight.fz[age] = segment.from().z;
                flight.tx[age] = segment.sweepEnd().x;
                flight.ty[age] = segment.sweepEnd().y;
                flight.tz[age] = segment.sweepEnd().z;
            }
            flight.end = Math.min(ages, path.lastAge());
            PearlSimulation.Segment last = path.at(path.lastAge());
            if (last != null && last.hit() instanceof EntityHitResult other) {
                flight.blockedSq = last.from().squaredDistanceTo(other.getPos());
            }
            return flight;
        }
    }
}
