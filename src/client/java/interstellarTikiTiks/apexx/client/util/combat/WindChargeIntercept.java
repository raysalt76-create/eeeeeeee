package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Aims a wind charge so an ender pearl runs into it mid-air; the pearl then teleports its owner to
 * the pearl's position at the start of that tick ({@code EnderPearlEntity#onCollision} uses
 * {@code getLastRenderPos}).
 *
 * <p>Server timing (1.21.11): a projectile spawns when the server handles its use packet, and every
 * later server tick runs the world's entities in spawn order ({@code ServerWorld}'s entity list keeps
 * insertion order and {@code tickEntity} raises the age before the tick), so the older projectile
 * moves first. Two orders catch:</p>
 * <ul>
 *   <li><b>Charge after the pearl</b>, {@code throwAge} ticks later: the pearl's sweep on its tick
 *   {@code throwAge + 1 + k} is tested against the charge after {@code k} moves. The charge flies a
 *   constant 1.5 blocks a tick while the pearl slows, so it catches pearls thrown up, level and
 *   shallowly down.</li>
 *   <li><b>Charge before the pearl</b>, {@code gap} ticks earlier ({@link #solveOlder}): the charge
 *   moves first each tick, so the pearl's sweep on its tick {@code a} is tested against the charge
 *   after {@code a + gap} moves. Thrown steeply down, the pearl speeds up (gravity adds more than drag
 *   takes until it nears its ~3 blocks a tick terminal speed) and runs into the charge from behind.</li>
 * </ul>
 * <p>Either way the pearl's sweep carries the pearl's own age tolerance. A wind charge is a
 * redirectable projectile, so the pearl may hit it; the charge cannot hit the pearl. A sweep that
 * starts inside the charge's box does not hit it ({@code Box#raycast} only reports entry faces), so a
 * charge chasing a pearl straight up catches it on the tick the pearl drops out of the box's underside.</p>
 *
 * <p>Aim: for each pearl tick, the look that puts the charge's hitbox centre on the pearl's swept
 * segment seeds a candidate. Candidates are scored by the probability that the catch survives the
 * charge's own triangular launch spread and a one-tick server timing slip, limited to the looks a
 * turn from the current look can reach, and the best one is refined on a fine direction grid.
 * Near straight up or down the yaw barely changes the throw, so the current yaw is kept there
 * rather than spinning to an angle read from the pearl's tiny sideways drift. When the pearl is the
 * later throw, {@link #evaluatePearl} scores a pearl look against the charge already in flight
 * instead, over the pearl's own launch spread.</p>
 *
 * <p>Ranking: {@link Rank#CATCH} (our own pearl, whose throw tick the server sees exactly) keeps the
 * highest {@link Shot#score()}. {@link Rank#INTERCEPT} (a pearl someone else threw, seen through the
 * connection's latency) ranks by {@link Shot#interceptScore()} and, among looks that catch
 * reliably, takes the earliest catch: the nearest point of the pearl's flight.</p>
 */
public final class WindChargeIntercept {
    /** Where the charge is thrown from and the shooter movement the server adds to it. */
    public record Shooter(Vec3d spawn, Vec3d movement, boolean onGround) {}

    /** A wind charge in flight (or about to be): spawn, constant velocity and the moves it lives. */
    public record Charge(Vec3d spawn, Vec3d velocity, int lifetime) {
        /** Where the charge is after {@code moves} moves. */
        public Vec3d positionAt(int moves) {
            return spawn.add(velocity.multiply((double)moves));
        }
    }

    /**
     * A throw with this packet look catches the pearl on {@code catchAge} (the pearl's age) at
     * {@code catchPos}. The look and {@code velocity} belong to the projectile being aimed: the
     * charge, or the pearl for {@link #evaluatePearl}. {@code throwAge}: ticks between the two throws.
     * {@code confidence}: share of the aimed projectile's launch spread that still catches.
     * {@code slip}: share of one-tick server timing slips (handled a tick early or late) that still catch.
     */
    public record Shot(float yaw, float pitch, Vec3d velocity, int throwAge, int catchAge, Vec3d catchPos,
                       Vec3d windPos, float confidence, float slip) {
        /** Expected catch rate: the spread confidence, discounted when a timing slip would miss. */
        public double score() {
            return confidence * (1.0 - SLIP_WEIGHT + SLIP_WEIGHT * slip);
        }

        /**
         * Expected catch rate when the server's handling tick is only known to within one tick either
         * way: the nominal tick weighs one half, each slip one quarter.
         */
        public double interceptScore() {
            return confidence * (1.0 - INTERCEPT_SLIP_WEIGHT + INTERCEPT_SLIP_WEIGHT * slip);
        }

        public double score(Rank rank) {
            return rank == Rank.INTERCEPT ? interceptScore() : score();
        }
    }

    /** How candidate throws are ranked; see the class notes. */
    public enum Rank { CATCH, INTERCEPT }

    /**
     * The look a throw turns from, the largest straight yaw/pitch turn (degrees) it may take before
     * the throw, and the most the yaw alone may move. Looks beyond either are pulled back onto the
     * reachable end. The yaw limit exists for looks near vertical, where a huge yaw swing is a small
     * angle on the sphere and costs the turn limit almost nothing, yet swings the camera.
     */
    public record Reach(float yaw, float pitch, float maxTurn, float maxYaw) {
        public Reach(float yaw, float pitch, float maxTurn) {
            this(yaw, pitch, maxTurn, Float.POSITIVE_INFINITY);
        }

        public static Reach from(float yaw, float pitch) {
            return new Reach(yaw, pitch, Float.POSITIVE_INFINITY);
        }

        public Reach withMaxYaw(float limit) {
            return new Reach(yaw, pitch, maxTurn, limit);
        }

        public float turn(float toYaw, float toPitch) {
            float dy = MathHelper.wrapDegrees(toYaw - yaw);
            float dp = MathHelper.clamp(toPitch, -90.0f, 90.0f) - pitch;
            return (float)Math.sqrt(dy * dy + dp * dp);
        }

        /** The reachable look on the straight turn toward the target. */
        public float[] clamp(float toYaw, float toPitch) {
            float dy = MathHelper.wrapDegrees(toYaw - yaw);
            float dp = MathHelper.clamp(toPitch, -90.0f, 90.0f) - pitch;
            float distance = (float)Math.sqrt(dy * dy + dp * dp);
            float scale = distance > maxTurn ? maxTurn / distance : 1.0f;
            float turnedYaw = MathHelper.clamp(dy * scale, -maxYaw, maxYaw);
            return new float[]{yaw + turnedYaw, MathHelper.clamp(pitch + dp * scale, -90.0f, 90.0f)};
        }
    }

    private record Hit(int age, Vec3d point, Vec3d windPos) {}

    /**
     * Three-point Gauss rule of nextTriangular(0, SPREAD) ({@link ThrowPhysics#SPREAD_NODES}): it
     * matches the roll's variance and fourth moment, where the former {-0.5, 0, 0.5} grid understated
     * the variance by a sixth and never sampled the tails, so it read long catches as surer than they are.
     */
    private static final double[] SPREAD_POINTS = ThrowPhysics.SPREAD_NODES;
    private static final double[] SPREAD_WEIGHTS = ThrowPhysics.SPREAD_WEIGHTS;
    /**
     * Chance each way that the server handles the later use one tick early or late (client frame
     * pacing plus network jitter crossing a tick boundary), used by the catch-rate functions below.
     */
    public static final double SLIP_EACH_WAY = 0.1;
    /** Leading points of {@link ThrowPhysics#JOINT_POINTS} a coarse joint rate uses. */
    private static final int JOINT_COARSE = 24;
    /** Weight of the one-tick timing slip in {@link Shot#score()}. */
    private static final double SLIP_WEIGHT = 0.2;
    /** Weight of the one-tick timing slip in {@link Shot#interceptScore()}. */
    private static final double INTERCEPT_SLIP_WEIGHT = 0.5;
    /**
     * {@link Rank#INTERCEPT}: looks at or above this score (a sure spread that also survives one slip
     * side) all catch reliably; the earliest catch wins.
     */
    private static final double RELIABLE_INTERCEPT = 0.75;
    private static final int SEED_SAMPLES = 16;
    /** Offsets, in sweep fractions, from a radial crossing at which the charge's box is also tried. */
    private static final double[] SEED_ALONG = {0.0, 0.2, 0.4, -0.2};
    /**
     * Radial miss (blocks) within which a sweep that never crosses the charge's reach still seeds:
     * the box's half width plus the largest age tolerance, a little over.
     */
    private static final double SEED_SLACK = 0.5;
    private static final float COARSE_STEP = 0.2f;
    private static final float FINE_STEP = 0.05f;
    /** Scores this close count as equal; the smaller turn, then the earlier catch, wins. */
    private static final double SCORE_TIE = 0.02;
    private static final float TURN_TIE = 0.5f;
    /** A kept-yaw look further than this from the ideal direction is not worth evaluating. */
    private static final double KEEP_YAW_ANGLE = 6.0;
    /** Best free-flight candidates re-checked against the world before a solve gives up. */
    private static final int WORLD_CHECKS = 3;

    private WindChargeIntercept() {}

    /**
     * Best throw within {@code reach} for a charge whose use the server handles when the pearl has
     * flown {@code throwAge} ticks, or null when no look catches it within {@code maxCatchTicks}.
     * {@code refine} spends a fine look search on the best candidate.
     */
    public static Shot solve(World world, Entity owner, PearlSimulation.Path pearl, int throwAge,
                             Shooter shooter, int maxCatchTicks, Reach reach, boolean refine) {
        return solve(world, owner, pearl, throwAge, false, shooter, maxCatchTicks, reach, refine, Rank.CATCH);
    }

    /** {@link #solve(World, Entity, PearlSimulation.Path, int, Shooter, int, Reach, boolean)} ranked by {@code rank}. */
    public static Shot solve(World world, Entity owner, PearlSimulation.Path pearl, int throwAge,
                             Shooter shooter, int maxCatchTicks, Reach reach, boolean refine, Rank rank) {
        return solve(world, owner, pearl, throwAge, false, shooter, maxCatchTicks, reach, refine, rank);
    }

    /**
     * Best throw within {@code reach} for a charge thrown {@code gap} (>= 1) ticks BEFORE the pearl
     * whose flight from its own throw (age 0) is {@code pearl}, or null when no look lets the pearl
     * reach it within {@code maxCatchTicks} of the pearl's flight. The returned shot's
     * {@code throwAge} is the gap.
     */
    public static Shot solveOlder(World world, Entity owner, PearlSimulation.Path pearl, int gap,
                                  Shooter shooter, int maxCatchTicks, Reach reach, boolean refine) {
        if (gap < 1) return null;
        return solve(world, owner, pearl, gap, true, shooter, maxCatchTicks, reach, refine, Rank.CATCH);
    }

    private static Shot solve(World world, Entity owner, PearlSimulation.Path pearl, int throwAge, boolean older,
                              Shooter shooter, int maxCatchTicks, Reach reach, boolean refine, Rank rank) {
        if (pearl == null || shooter == null || reach == null) return null;
        Vec3d move = shooterMove(shooter);
        Vec3d offset = WindChargeSimulation.boxCenterOffset();
        double speed = (double)ThrowPhysics.WIND_CHARGE_POWER;
        // Seeds are ranked in free flight; only the best few are traced against the world, which is
        // where the cost is (a block and an entity sweep per charge tick).
        int unbounded = flightLimit(older, throwAge, maxCatchTicks) + 1;
        List<Shot> found = new ArrayList<>();
        int firstAge = older ? pearl.startAge() + 1 : throwAge + 2;
        int lastAge = Math.min(pearl.lastAge(), older ? pearl.startAge() + maxCatchTicks : throwAge + maxCatchTicks);
        for (int age = firstAge; age <= lastAge; ++age) {
            PearlSimulation.Segment segment = pearl.at(age);
            if (segment == null) continue;
            int moves = older ? age + throwAge : age - throwAge - 1;
            Vec3d origin = shooter.spawn().add(move.multiply(moves));
            double distance = speed * moves;
            double previousS = 0.0;
            double previous = gap(segment, 0.0, offset, origin, distance);
            double closestS = 0.0;
            double closest = Math.abs(previous);
            boolean crossed = false;
            for (int i = 1; i <= SEED_SAMPLES; ++i) {
                double s = i / (double)SEED_SAMPLES;
                double value = gap(segment, s, offset, origin, distance);
                if (Math.abs(value) < closest) {
                    closest = Math.abs(value);
                    closestS = s;
                }
                if (Math.signum(value) != Math.signum(previous)) {
                    crossed = true;
                    // The crossing itself, and points further along and back along this tick's
                    // sweep: when the crossing sits near the sweep's start, the sweep starts inside a
                    // box centred there and does not hit it, while a box a little further on is
                    // entered properly. That is the usual case when the two fly almost in step.
                    seed(found, pearl, segment, bisect(segment, previousS, s, offset, origin, distance), offset, origin,
                            throwAge, older, shooter, unbounded, maxCatchTicks, reach);
                }
                previousS = s;
                previous = value;
            }
            // No crossing, but the sweep passes within the box's reach of where the charge can be:
            // the pair flies almost in step and only the box's size (with the pearl's tolerance)
            // closes the last fraction of a block.
            if (!crossed && closest <= SEED_SLACK) {
                seed(found, pearl, segment, closestS, offset, origin, throwAge, older, shooter, unbounded,
                        maxCatchTicks, reach);
            }
        }
        for (int i = 0; i < WORLD_CHECKS && !found.isEmpty(); ++i) {
            Shot pick = null;
            for (Shot shot : found) pick = better(pick, shot, reach, rank);
            found.remove(pick);
            Shot exact = evaluate(world, owner, pearl, throwAge, older, shooter, pick.yaw(), pick.pitch(), maxCatchTicks);
            if (exact == null) continue;
            return refine ? refine(world, owner, pearl, throwAge, older, shooter, exact, maxCatchTicks, reach, rank) : exact;
        }
        return null;
    }

    /** Free-flight evaluation of the looks that put the charge's box on this sweep at {@code at}. */
    private static void seed(List<Shot> found, PearlSimulation.Path pearl, PearlSimulation.Segment segment, double at,
                             Vec3d offset, Vec3d origin, int throwAge, boolean older, Shooter shooter, int unbounded,
                             int maxCatchTicks, Reach reach) {
        for (double along : SEED_ALONG) {
            double s = at + along;
            if (s < 0.0 || s > 1.0) continue;
            Vec3d aim = lerp(segment, s).subtract(offset).subtract(origin);
            for (float[] look : seedLooks(aim, reach)) {
                Shot shot = evaluate(pearl, throwAge, older, shooter, look[0], look[1],
                        velocity(look[0], look[1], shooter, null), unbounded, maxCatchTicks);
                if (shot != null) found.add(shot);
            }
        }
    }

    /**
     * Best of these packet looks, each taken exactly as it will be sent (for example every look
     * the next GCD-quantized rotation step can reach), ranked as {@link #solve} ranks.
     */
    public static Shot best(World world, Entity owner, PearlSimulation.Path pearl, int throwAge,
                            Shooter shooter, int maxCatchTicks, List<float[]> looks, Reach reach) {
        return best(world, owner, pearl, throwAge, false, shooter, maxCatchTicks, looks, reach, Rank.CATCH);
    }

    /** {@link #best(World, Entity, PearlSimulation.Path, int, Shooter, int, List, Reach)} ranked by {@code rank}. */
    public static Shot best(World world, Entity owner, PearlSimulation.Path pearl, int throwAge,
                            Shooter shooter, int maxCatchTicks, List<float[]> looks, Reach reach, Rank rank) {
        return best(world, owner, pearl, throwAge, false, shooter, maxCatchTicks, looks, reach, rank);
    }

    /** {@link #best} for a charge thrown {@code gap} (>= 1) ticks before the pearl, as {@link #solveOlder}. */
    public static Shot bestOlder(World world, Entity owner, PearlSimulation.Path pearl, int gap,
                                 Shooter shooter, int maxCatchTicks, List<float[]> looks, Reach reach) {
        if (gap < 1) return null;
        return best(world, owner, pearl, gap, true, shooter, maxCatchTicks, looks, reach, Rank.CATCH);
    }

    private static Shot best(World world, Entity owner, PearlSimulation.Path pearl, int throwAge, boolean older,
                             Shooter shooter, int maxCatchTicks, List<float[]> looks, Reach reach, Rank rank) {
        if (pearl == null || shooter == null || looks == null || looks.isEmpty()) return null;
        float[] first = looks.get(0);
        int windMoves = windLifetime(world, owner, shooter.spawn(), velocity(first[0], first[1], shooter, null),
                flightLimit(older, throwAge, maxCatchTicks));
        List<Shot> found = new ArrayList<>();
        for (float[] look : looks) {
            Shot shot = evaluate(pearl, throwAge, older, shooter, look[0], look[1],
                    velocity(look[0], look[1], shooter, null), windMoves, maxCatchTicks);
            if (shot != null) found.add(shot);
        }
        // Each pick gets its own collision check: a nearby look may clip a different block.
        for (int i = 0; i < WORLD_CHECKS && !found.isEmpty(); ++i) {
            Shot pick = null;
            for (Shot shot : found) pick = better(pick, shot, reach, rank);
            found.remove(pick);
            Shot exact = evaluate(world, owner, pearl, throwAge, older, shooter, pick.yaw(), pick.pitch(), maxCatchTicks);
            if (exact != null) return exact;
        }
        return null;
    }

    /**
     * Exact outcome of throwing with this look (as the use packet will carry it): the
     * server-applied angles, the charge's own collisions, the catch point and its spread confidence.
     */
    public static Shot evaluate(World world, Entity owner, PearlSimulation.Path pearl, int throwAge,
                                Shooter shooter, float yaw, float pitch, int maxCatchTicks) {
        return evaluate(world, owner, pearl, throwAge, false, shooter, yaw, pitch, maxCatchTicks);
    }

    /**
     * Exact outcome of throwing the charge with this look {@code gap} (>= 1) ticks BEFORE a pearl
     * whose flight from its own throw (age 0) is {@code pearl}: the pearl, faster than the charge
     * when thrown steeply down, overtakes it. The returned shot's {@code throwAge} is the gap.
     */
    public static Shot evaluateOlder(World world, Entity owner, PearlSimulation.Path pearl, int gap,
                                     Shooter shooter, float yaw, float pitch, int maxCatchTicks) {
        if (pearl == null || shooter == null || gap < 1) return null;
        return evaluate(world, owner, pearl, gap, true, shooter, yaw, pitch, maxCatchTicks);
    }

    private static Shot evaluate(World world, Entity owner, PearlSimulation.Path pearl, int throwAge, boolean older,
                                 Shooter shooter, float yaw, float pitch, int maxCatchTicks) {
        Vec3d velocity = velocity(yaw, pitch, shooter, null);
        int windMoves = windLifetime(world, owner, shooter.spawn(), velocity,
                flightLimit(older, throwAge, maxCatchTicks));
        return evaluate(pearl, throwAge, older, shooter, yaw, pitch, velocity, windMoves, maxCatchTicks);
    }

    private static Shot evaluate(PearlSimulation.Path pearl, int throwAge, boolean older, Shooter shooter,
                                 float yaw, float pitch, Vec3d velocity, int windMoves, int maxCatchTicks) {
        int limit = flightLimit(older, throwAge, maxCatchTicks);
        Hit nominal = intercept(pearl, throwAge, shooter.spawn(), velocity, windMoves, limit, older);
        if (nominal == null) return null;
        double confidence = 0.0;
        for (int x = 0; x < SPREAD_POINTS.length; ++x) {
            for (int y = 0; y < SPREAD_POINTS.length; ++y) {
                for (int z = 0; z < SPREAD_POINTS.length; ++z) {
                    Vec3d spread = new Vec3d(SPREAD_POINTS[x] * ThrowPhysics.SPREAD,
                            SPREAD_POINTS[y] * ThrowPhysics.SPREAD, SPREAD_POINTS[z] * ThrowPhysics.SPREAD);
                    Vec3d rolled = velocity(yaw, pitch, shooter, spread);
                    if (intercept(pearl, throwAge, shooter.spawn(), rolled, windMoves, limit, older) != null) {
                        confidence += SPREAD_WEIGHTS[x] * SPREAD_WEIGHTS[y] * SPREAD_WEIGHTS[z];
                    }
                }
            }
        }
        // A slip shifts which server tick handles the later use; the gap then reads one less or more.
        // A gap of 0 is still a real slip (both uses handled between the same two server ticks).
        float slip = 0.0f;
        if (throwAge >= 1
                && intercept(pearl, throwAge - 1, shooter.spawn(), velocity, windMoves, limit, older) != null) slip += 0.5f;
        if (intercept(pearl, throwAge + 1, shooter.spawn(), velocity, windMoves, limit, older) != null) slip += 0.5f;
        return new Shot(yaw, pitch, velocity, throwAge, nominal.age(), nominal.point(),
                nominal.windPos(), (float)confidence, slip);
    }

    /**
     * The wind charge thrown with this packet look from this shooter state: its mean launch and the
     * moves it lives before bursting on a block or entity (capped at {@code maxMoves + 1}).
     */
    public static Charge charge(World world, Entity owner, Shooter shooter, float yaw, float pitch, int maxMoves) {
        return charge(world, owner, shooter.spawn(), velocity(yaw, pitch, shooter, null), maxMoves);
    }

    /** A wind charge with a known spawn and velocity (for example its spawn packet's). */
    public static Charge charge(World world, Entity owner, Vec3d spawn, Vec3d velocity, int maxMoves) {
        return new Charge(spawn, velocity, windLifetime(world, owner, spawn, velocity, maxMoves));
    }

    /**
     * Outcome of throwing the pearl with this look {@code gap} ticks after {@code charge} was thrown:
     * {@code pearl} is the pearl's flight from that throw and {@code launch} its mean launch velocity.
     * The pearl is the later throw here, so its own launch spread, which no packet reports before
     * the throw, is what {@code confidence} covers. Null when the pearl does not reach the charge
     * within {@code maxCatchTicks} of its flight.
     */
    public static Shot evaluatePearl(PearlSimulation.Path pearl, Vec3d launch, float yaw, float pitch,
                                     Charge charge, int gap, int maxCatchTicks) {
        if (pearl == null || launch == null || charge == null || gap < 0) return null;
        int limit = maxCatchTicks + gap;
        Hit nominal = intercept(pearl, gap, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true);
        if (nominal == null) return null;
        int ticks = pearl.lastAge() - pearl.startAge();
        double confidence = 0.0;
        for (int x = 0; x < SPREAD_POINTS.length; ++x) {
            for (int y = 0; y < SPREAD_POINTS.length; ++y) {
                for (int z = 0; z < SPREAD_POINTS.length; ++z) {
                    // The spread rolls the unit direction before the power scales it.
                    Vec3d spread = new Vec3d(SPREAD_POINTS[x], SPREAD_POINTS[y], SPREAD_POINTS[z])
                            .multiply(ThrowPhysics.SPREAD * (double)ThrowPhysics.PEARL_POWER);
                    // Free flight, cut where the nominal flight stops, so a block the nominal pearl
                    // hits still ends every rolled one there.
                    PearlSimulation.Path rolled = PearlSimulation.ballistic(pearl.start(), launch.add(spread),
                            pearl.startAge()).trace(ticks);
                    if (intercept(rolled, gap, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true) != null) {
                        confidence += SPREAD_WEIGHTS[x] * SPREAD_WEIGHTS[y] * SPREAD_WEIGHTS[z];
                    }
                }
            }
        }
        float slip = 0.0f;
        if (gap >= 1 && intercept(pearl, gap - 1, charge.spawn(), charge.velocity(), charge.lifetime(),
                limit, true) != null) slip += 0.5f;
        if (intercept(pearl, gap + 1, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true) != null) slip += 0.5f;
        return new Shot(yaw, pitch, launch, gap, nominal.age(), nominal.point(), nominal.windPos(),
                (float)confidence, slip);
    }

    // ── Catch rates (Pearl Catch) ────────────────────────────────────────────

    /**
     * Expected catch rate of a charge thrown with this look: the charge's own launch spread (the
     * {@link ThrowPhysics#FINE_POINTS} rule when {@code fine}, else the 27-point Gauss grid) at the
     * planned gap and at a one-tick slip either way, weighted {@link #SLIP_EACH_WAY} each. Unlike
     * {@link Shot#score()} every slip side is integrated over the spread as well, so it is the share
     * of real throws that catch, with the pearl as {@code pearl} has it.
     * {@code windMoves}: the move the charge bursts on ({@link #chargeMoves}).
     */
    public static double catchRate(PearlSimulation.Path pearl, int gap, boolean older, Shooter shooter,
                                   float yaw, float pitch, int windMoves, int maxCatchTicks, boolean fine) {
        if (pearl == null || shooter == null) return 0.0;
        double[][] points = fine ? ThrowPhysics.FINE_POINTS : ThrowPhysics.COARSE_POINTS;
        double[] weights = fine ? ThrowPhysics.FINE_WEIGHTS : ThrowPhysics.COARSE_WEIGHTS;
        int limit = flightLimit(older, gap, maxCatchTicks) + 1;
        double rate = 0.0;
        for (int i = 0; i < points.length; ++i) {
            Vec3d spread = new Vec3d(points[i][0], points[i][1], points[i][2]).multiply(ThrowPhysics.SPREAD);
            Vec3d velocity = velocity(yaw, pitch, shooter, spread);
            rate += weights[i] * slipWeighted(pearl, gap, older, shooter.spawn(), velocity, windMoves, limit);
        }
        return rate;
    }

    /**
     * Expected catch rate of a pearl thrown with this look {@code gap} ticks after {@code charge}:
     * the pearl's own launch spread, integrated as {@link #catchRate} does, and the one-tick slip.
     * {@code spawn}/{@code launch}: the pearl's spawn and mean launch velocity; {@code ticks}: its
     * flight length (a nominal flight cut short by a block ends every rolled one there too).
     */
    public static double pearlCatchRate(Vec3d spawn, Vec3d launch, int ticks, Charge charge, int gap,
                                        int maxCatchTicks, boolean fine) {
        if (spawn == null || launch == null || charge == null) return 0.0;
        double[][] points = fine ? ThrowPhysics.FINE_POINTS : ThrowPhysics.COARSE_POINTS;
        double[] weights = fine ? ThrowPhysics.FINE_WEIGHTS : ThrowPhysics.COARSE_WEIGHTS;
        double rate = 0.0;
        for (int i = 0; i < points.length; ++i) {
            Vec3d spread = new Vec3d(points[i][0], points[i][1], points[i][2])
                    .multiply(ThrowPhysics.SPREAD * (double)ThrowPhysics.PEARL_POWER);
            PearlSimulation.Path rolled = PearlSimulation.ballistic(spawn, launch.add(spread), 0).trace(ticks);
            rate += weights[i] * pearlSlipWeighted(rolled, charge, gap, maxCatchTicks);
        }
        return rate;
    }

    /**
     * Expected catch rate when neither launch is known yet: the pearl's spread (thrown from
     * {@code pearlSpawn} with mean {@code pearlLaunch}, followed {@code pearlTicks} ticks in open air)
     * and the charge's (this look from {@code shooter}) rolled together on {@link ThrowPhysics#JOINT_POINTS},
     * with the one-tick slip. {@code older}: the charge leaves first, {@code gap} ticks before the pearl;
     * otherwise {@code gap} ticks after it. The case of a second throw aimed before the first
     * projectile's spawn packet arrives.
     */
    public static double jointCatchRate(Vec3d pearlSpawn, Vec3d pearlLaunch, int pearlTicks, Shooter shooter,
                                        float yaw, float pitch, int windMoves, int gap, boolean older, int maxCatchTicks,
                                        boolean fine) {
        if (shooter == null) return 0.0;
        return jointCatchRate(pearlSpawn, pearlLaunch, pearlTicks, shooter.spawn(), velocity(yaw, pitch, shooter, null),
                windMoves, gap, older, maxCatchTicks, fine);
    }

    /**
     * {@link #jointCatchRate} for a charge given by its spawn and mean launch velocity (one already
     * predicted in flight). Not {@code fine}: the first {@link #JOINT_COARSE} points of the same sequence,
     * a quarter of the cost, for ranking many candidates.
     */
    public static double jointCatchRate(Vec3d pearlSpawn, Vec3d pearlLaunch, int pearlTicks, Vec3d chargeSpawn,
                                        Vec3d chargeVelocity, int windMoves, int gap, boolean older, int maxCatchTicks,
                                        boolean fine) {
        if (pearlSpawn == null || pearlLaunch == null || chargeSpawn == null || chargeVelocity == null) return 0.0;
        int limit = flightLimit(older, gap, maxCatchTicks) + 1;
        double pearlScale = ThrowPhysics.SPREAD * (double)ThrowPhysics.PEARL_POWER;
        double chargeScale = ThrowPhysics.SPREAD * (double)ThrowPhysics.WIND_CHARGE_POWER;
        int count = fine ? ThrowPhysics.JOINT_COUNT : JOINT_COARSE;
        double rate = 0.0;
        for (int i = 0; i < count; ++i) {
            double[] point = ThrowPhysics.JOINT_POINTS[i];
            Vec3d pearlSpread = new Vec3d(point[0], point[1], point[2]).multiply(pearlScale);
            PearlSimulation.Path rolled = PearlSimulation.ballistic(pearlSpawn, pearlLaunch.add(pearlSpread), 0)
                    .trace(Math.max(1, pearlTicks));
            Vec3d chargeSpread = new Vec3d(point[3], point[4], point[5]).multiply(chargeScale);
            rate += slipWeighted(rolled, gap, older, chargeSpawn, chargeVelocity.add(chargeSpread), windMoves, limit);
        }
        return rate / count;
    }

    /** Mean charge throw only (no spread): the slip-weighted share of {gap - 1, gap, gap + 1} that catches. */
    public static double nominalRate(PearlSimulation.Path pearl, int gap, boolean older, Shooter shooter,
                                     float yaw, float pitch, int windMoves, int maxCatchTicks) {
        if (pearl == null || shooter == null) return 0.0;
        return slipWeighted(pearl, gap, older, shooter.spawn(), velocity(yaw, pitch, shooter, null), windMoves,
                flightLimit(older, gap, maxCatchTicks) + 1);
    }

    /** Mean pearl throw only (no spread) against a charge in flight: the slip-weighted share that catches. */
    public static double nominalPearlRate(PearlSimulation.Path pearl, Charge charge, int gap, int maxCatchTicks) {
        if (pearl == null || charge == null) return 0.0;
        return pearlSlipWeighted(pearl, charge, gap, maxCatchTicks);
    }

    /** The move a charge thrown with this look bursts on (capped), for the rate functions. */
    public static int chargeMoves(World world, Entity owner, Shooter shooter, float yaw, float pitch,
                                  boolean older, int gap, int maxCatchTicks) {
        return windLifetime(world, owner, shooter.spawn(), velocity(yaw, pitch, shooter, null),
                flightLimit(older, gap + 1, maxCatchTicks) + 1);
    }

    /** The charge's launch velocity for this packet look from this shooter (mean throw). */
    public static Vec3d launch(Shooter shooter, float yaw, float pitch) {
        return velocity(yaw, pitch, shooter, null);
    }

    private static double slipWeighted(PearlSimulation.Path pearl, int gap, boolean older, Vec3d spawn,
                                       Vec3d velocity, int windMoves, int limit) {
        double hit = intercept(pearl, gap, spawn, velocity, windMoves, limit, older) != null ? 1.0 - 2.0 * SLIP_EACH_WAY : 0.0;
        if (gap >= 1 && intercept(pearl, gap - 1, spawn, velocity, windMoves, limit, older) != null) hit += SLIP_EACH_WAY;
        if (intercept(pearl, gap + 1, spawn, velocity, windMoves, limit, older) != null) hit += SLIP_EACH_WAY;
        return hit;
    }

    private static double pearlSlipWeighted(PearlSimulation.Path pearl, Charge charge, int gap, int maxCatchTicks) {
        int limit = maxCatchTicks + gap + 1;
        double hit = intercept(pearl, gap, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true) != null
                ? 1.0 - 2.0 * SLIP_EACH_WAY : 0.0;
        if (gap >= 1 && intercept(pearl, gap - 1, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true) != null) {
            hit += SLIP_EACH_WAY;
        }
        if (intercept(pearl, gap + 1, charge.spawn(), charge.velocity(), charge.lifetime(), limit, true) != null) {
            hit += SLIP_EACH_WAY;
        }
        return hit;
    }

    /**
     * Looks to try for an aim direction: the direct look (pulled into reach), and near the vertical
     * the look that keeps the current yaw and only turns the pitch.
     */
    private static List<float[]> seedLooks(Vec3d aim, Reach reach) {
        List<float[]> looks = new ArrayList<>(2);
        float[] direct = ThrowPhysics.lookAlong(aim, reach.yaw());
        looks.add(reach.clamp(direct[0], direct[1]));
        float directYaw = MathHelper.wrapDegrees(direct[0] - reach.yaw());
        if (Math.abs(directYaw) > reach.maxYaw()) {
            // The aim needs more yaw than the look may swing: the closest look on the yaw limit's edge.
            float[] edge = ThrowPhysics.closestLookWithYaw(aim, reach.yaw() + Math.copySign(reach.maxYaw(), directYaw));
            looks.add(reach.clamp(edge[0], edge[1]));
        }
        float[] kept = ThrowPhysics.closestLookWithYaw(aim, reach.yaw());
        if (reach.turn(kept[0], kept[1]) + TURN_TIE < reach.turn(direct[0], direct[1])
                && ThrowPhysics.lookAngle(kept[0], kept[1], direct[0], direct[1]) <= KEEP_YAW_ANGLE) {
            looks.add(reach.clamp(kept[0], kept[1]));
        }
        return looks;
    }

    /**
     * Coarse then fine grid of directions around a candidate (so it is as fine straight up as
     * level), kept within reach, reusing the charge's collision lifetime.
     */
    private static Shot refine(World world, Entity owner, PearlSimulation.Path pearl, int throwAge, boolean older,
                               Shooter shooter, Shot seed, int maxCatchTicks, Reach reach, Rank rank) {
        int windMoves = windLifetime(world, owner, shooter.spawn(), seed.velocity(),
                flightLimit(older, throwAge, maxCatchTicks));
        Shot best = seed;
        for (float step : new float[]{COARSE_STEP, FINE_STEP}) {
            Shot center = best;
            Vec3d direction = ThrowPhysics.rawDirection(ThrowPhysics.serverYaw(center.yaw()),
                    ThrowPhysics.serverPitch(center.pitch())).normalize();
            Vec3d side = direction.crossProduct(new Vec3d(0.0, 1.0, 0.0));
            if (side.lengthSquared() < 1.0E-12) {
                float yaw = center.yaw() * MathHelper.RADIANS_PER_DEGREE;
                side = new Vec3d(Math.cos(yaw), 0.0, Math.sin(yaw));
            }
            side = side.normalize();
            Vec3d up = side.crossProduct(direction).normalize();
            int radius = step == COARSE_STEP ? 3 : 2;
            for (int a = -radius; a <= radius; ++a) {
                for (int b = -radius; b <= radius; ++b) {
                    if (a == 0 && b == 0) continue;
                    Vec3d turned = direction
                            .add(side.multiply(Math.tan(Math.toRadians(a * step))))
                            .add(up.multiply(Math.tan(Math.toRadians(b * step))));
                    float[] look = ThrowPhysics.lookAlong(turned, center.yaw());
                    look = reach.clamp(look[0], look[1]);
                    best = better(best, evaluate(pearl, throwAge, older, shooter, look[0], look[1],
                            velocity(look[0], look[1], shooter, null), windMoves, maxCatchTicks), reach, rank);
                }
            }
        }
        if (best == seed) return seed;
        // The chosen look gets its own collision check: a nearby look may clip a different block.
        Shot exact = evaluate(world, owner, pearl, throwAge, older, shooter, best.yaw(), best.pitch(), maxCatchTicks);
        return exact != null ? better(seed, exact, reach, rank) : seed;
    }

    /** Moves the charge may make toward a catch: the pearl's catch window, plus the head start when older. */
    private static int flightLimit(boolean older, int throwAge, int maxCatchTicks) {
        return older ? maxCatchTicks + throwAge : maxCatchTicks;
    }

    /**
     * First pearl tick whose sweep enters the charge's box, or null. {@code limit} caps the charge's
     * moves; {@code windMoves} is the move it bursts on.
     */
    private static Hit intercept(PearlSimulation.Path pearl, int throwAge, Vec3d spawn, Vec3d velocity,
                                 int windMoves, int limit, boolean older) {
        int moveLimit = Math.min(windMoves, limit);
        if (older) {
            // The charge is the older entity and moves first each tick: by the pearl's sweep on age
            // a it has made a + throwAge moves, and it is gone once its bursting move has run.
            for (int age = pearl.startAge() + 1; ; ++age) {
                int moves = age + throwAge;
                if (moves >= moveLimit) return null;
                PearlSimulation.Segment segment = pearl.at(age);
                if (segment == null) return null;
                Vec3d windPos = spawn.add(velocity.multiply((double)moves));
                Optional<Vec3d> point = WindChargeSimulation.box(windPos).expand((double)segment.tolerance())
                        .raycast(segment.from(), segment.sweepEnd());
                if (point.isPresent()) {
                    if (segment.hit() instanceof EntityHitResult other
                            && segment.from().squaredDistanceTo(other.getPos()) < segment.from().squaredDistanceTo(point.get())) {
                        return null;
                    }
                    return new Hit(segment.age(), point.get(), windPos);
                }
                if (segment.ends()) return null;
            }
        }
        Vec3d wind = spawn;
        for (int moves = 0; moves < moveLimit; ++moves) {
            PearlSimulation.Segment segment = pearl.at(throwAge + 1 + moves);
            if (segment == null) return null;
            Optional<Vec3d> point = WindChargeSimulation.box(wind).expand((double)segment.tolerance())
                    .raycast(segment.from(), segment.sweepEnd());
            if (point.isPresent()) {
                // The pearl takes the closest entity on this sweep.
                if (segment.hit() instanceof EntityHitResult other
                        && segment.from().squaredDistanceTo(other.getPos()) < segment.from().squaredDistanceTo(point.get())) {
                    return null;
                }
                return new Hit(segment.age(), point.get(), wind);
            }
            if (segment.ends()) return null;
            wind = wind.add(velocity);
        }
        return null;
    }

    /** Moves the charge completes before it bursts on a block or entity (capped). */
    private static int windLifetime(World world, Entity owner, Vec3d spawn, Vec3d velocity, int maxCatchTicks) {
        List<WindChargeSimulation.Step> steps = new WindChargeSimulation(world, owner, spawn, velocity).trace(maxCatchTicks);
        for (WindChargeSimulation.Step step : steps) {
            // It still sits at its previous position while the pearl runs on the tick it bursts.
            if (step.ends()) return step.age();
        }
        return maxCatchTicks + 1;
    }

    private static Vec3d velocity(float yaw, float pitch, Shooter shooter, Vec3d spread) {
        return ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                ThrowPhysics.WIND_CHARGE_POWER, spread, shooter.movement(), shooter.onGround());
    }

    private static Vec3d shooterMove(Shooter shooter) {
        return new Vec3d(shooter.movement().x, shooter.onGround() ? 0.0 : shooter.movement().y, shooter.movement().z);
    }

    private static Vec3d lerp(PearlSimulation.Segment segment, double s) {
        return segment.from().add(segment.sweepEnd().subtract(segment.from()).multiply(s));
    }

    private static double gap(PearlSimulation.Segment segment, double s, Vec3d offset, Vec3d origin, double reach) {
        return lerp(segment, s).subtract(offset).distanceTo(origin) - reach;
    }

    private static double bisect(PearlSimulation.Segment segment, double low, double high, Vec3d offset,
                                 Vec3d origin, double reach) {
        double lowValue = gap(segment, low, offset, origin, reach);
        for (int i = 0; i < 40; ++i) {
            double mid = (low + high) * 0.5;
            double value = gap(segment, mid, offset, origin, reach);
            if (Math.signum(value) == Math.signum(lowValue)) {
                low = mid;
                lowValue = value;
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    /**
     * Higher expected catch rate wins; near-ties go to the smaller turn from the current look
     * (reached sooner and smoother), then to the earlier catch (less spread drift).
     */
    static Shot better(Shot current, Shot candidate, Reach reach) {
        return better(current, candidate, reach, Rank.CATCH);
    }

    /** {@link #better(Shot, Shot, Reach)}; {@link Rank#INTERCEPT} puts the earlier catch first between reliable looks and on near-ties. */
    static Shot better(Shot current, Shot candidate, Reach reach, Rank rank) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        double a = candidate.score(rank);
        double b = current.score(rank);
        boolean earlier = candidate.catchAge() < current.catchAge();
        boolean sameCatch = candidate.catchAge() == current.catchAge();
        if (rank == Rank.INTERCEPT && !sameCatch && Math.min(a, b) >= RELIABLE_INTERCEPT) {
            return earlier ? candidate : current;
        }
        if (a > b + SCORE_TIE) return candidate;
        if (a + SCORE_TIE < b) return current;
        if (rank == Rank.INTERCEPT && !sameCatch) return earlier ? candidate : current;
        float turnA = reach.turn(candidate.yaw(), candidate.pitch());
        float turnB = reach.turn(current.yaw(), current.pitch());
        if (Math.abs(turnA - turnB) > TURN_TIE) return turnA < turnB ? candidate : current;
        if (!sameCatch) return earlier ? candidate : current;
        return a > b ? candidate : current;
    }
}
