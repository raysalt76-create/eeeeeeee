package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Exact 1.21.11 movement of another player, one tick per step, to forecast where the server will
 * hold that player's hitbox when a projectile gets there. Per tick, as {@code LivingEntity#travel}
 * runs it:
 * <ul>
 *   <li><b>Gliding</b> ({@code calcGlidingVelocity}): look-driven lift and dive, then drag
 *   0.99/0.98/0.99. While an attached firework burns, its push toward 1.5x the look direction
 *   follows the move. The hitbox is the 0.6 x 0.6 x 0.6 gliding box, not the 1.8 standing one,
 *   and a glider slows down when it climbs.</li>
 *   <li><b>Ground and air</b> ({@code travelMidAir}): a jump sets 0.42 up (plus the 0.2 sprint
 *   push along the look), the held input is added, the player moves, then gravity 0.08,
 *   vertical drag 0.98 and horizontal friction 0.91 (x0.6 ordinary-block slipperiness when the
 *   tick started on ground) give the next tick's velocity.</li>
 *   <li><b>Fluids</b>: the fitted movement is held. This is an approximation.</li>
 * </ul>
 * Blocks collide through {@link Entity#adjustMovementForCollisions(Entity, Vec3d, Box, World, List)}
 * with a 0.6 step on ground; colliding axes lose their velocity, and landing ends a glide. A
 * player's inputs and look are unknown, so a {@link Behavior} states what a forecast assumes they
 * keep doing, {@link #fit} recovers the velocity from the server positions this client has seen,
 * and {@link #predict} builds the weighted set of forecasts a solver should hedge across.
 */
public final class PlayerMotionSimulation {

    public enum Mode { GROUND, AIR, GLIDING, FLUID }

    public static final double GRAVITY = 0.08;
    public static final float VERTICAL_DRAG = 0.98f;
    public static final float AIR_FRICTION = 0.91f;
    /** Ordinary-block slipperiness (0.6) times the 0.91 friction, as travelMidAir multiplies them in float. */
    public static final float GROUND_FRICTION = 0.6f * 0.91f;
    /** PlayerEntity#getOffGroundSpeed. */
    public static final float AIR_SPEED = 0.02f;
    public static final float SPRINT_AIR_SPEED = 0.025999999f;
    /** Steady ground speed on an ordinary block: input acceleration (0.1, x1.3 sprinting) over (1 - friction). */
    public static final double WALK_SPEED = 0.1 / (1.0 - GROUND_FRICTION);
    public static final double SPRINT_SPEED = 0.13 / (1.0 - GROUND_FRICTION);
    /** LivingEntity#jump: base jump velocity and the sprint push along the look. */
    public static final double JUMP_VELOCITY = 0.42f;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    private static final float STEP_HEIGHT = 0.6f;
    private static final float RADIANS = (float) (Math.PI / 180.0);
    private static final List<VoxelShape> NO_ENTITY_COLLISIONS = List.of();

    /** Updates older than this many ticks do not take part in a fit. */
    private static final int FIT_WINDOW = 10;
    private static final int FIT_UPDATES = 6;
    private static final int FIT_ITERATIONS = 8;
    /** A moving player is resent every second tick; this long without an update means it stands still. */
    private static final int STILL_TICKS = 4;
    /** Below this horizontal speed (blocks/tick) a player is not taken to be pressing a direction. */
    private static final double INPUT_SPEED = 0.03;
    /** How far a glider's look is assumed to swing either way over the forecast. */
    private static final float GLIDE_LOOK_SWING = 12.0f;

    /**
     * What a forecast assumes the player keeps doing: the look (gliding, firework push and the
     * sprint-jump push), the horizontal direction pressed (unit or zero), sprinting, the steady
     * speed that input holds on ground, whether they jump each time they land, and how many ticks
     * an attached firework still pushes.
     */
    public record Behavior(float yaw, float pitch, Vec3d input, boolean sprinting, double groundSpeed,
                           boolean hop, int boostTicks) {
        public Behavior withPitch(float newPitch) {
            return new Behavior(yaw, MathHelper.clamp(newPitch, -90.0f, 90.0f), input, sprinting, groundSpeed, hop, boostTicks);
        }

        public Behavior withHop(boolean newHop) {
            return new Behavior(yaw, pitch, input, sprinting, groundSpeed, newHop, boostTicks);
        }

        public Behavior released() {
            return new Behavior(yaw, pitch, Vec3d.ZERO, sprinting, 0.0, false, boostTicks);
        }

        /** The pressed direction and the look swung {@code degrees} (a turn of the whole heading). */
        public Behavior turned(float degrees) {
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            Vec3d swung = new Vec3d(input.x * cos - input.z * sin, 0.0, input.x * sin + input.z * cos);
            return new Behavior(yaw + degrees, pitch, swung, sprinting, groundSpeed, hop, boostTicks);
        }
    }

    /** Feet position, the velocity the next tick starts from, and how the player moves. */
    public record State(Vec3d pos, Vec3d velocity, boolean onGround, Mode mode) {}

    /** One weighted guess at the player's hitbox, by server tick after the observed state (index 0). */
    public record Forecast(String name, double weight, Box[] boxes) {}

    /**
     * The forecasts for a player and the client tick its newest server position was first seen on
     * (for a player standing still, the current tick: nothing newer can arrive).
     */
    public record Prediction(Mode mode, long observedTick, List<Forecast> forecasts) {}

    private final World world;
    private final Entity player;
    private final EntityDimensions upright;
    private final EntityDimensions glider;
    private final EntityDimensions swimmer;
    /** Height of a flat floor when there is no world (tests); NaN for none. */
    private double floor = Double.NaN;

    /**
     * @param world  collisions; null for none
     * @param player collision context (sneak-edge and world-border checks); may be null
     */
    public PlayerMotionSimulation(World world, Entity player, EntityDimensions upright, EntityDimensions glider,
                                  EntityDimensions swimmer) {
        this.world = world;
        this.player = player;
        this.upright = upright;
        this.glider = glider;
        this.swimmer = swimmer;
    }

    /** A worldless simulation whose only terrain is a flat floor at feet height {@code y}. */
    PlayerMotionSimulation withFloor(double y) {
        this.floor = y;
        return this;
    }

    public static PlayerMotionSimulation of(World world, PlayerEntity player) {
        EntityPose pose = player.isSneaking() ? EntityPose.CROUCHING : EntityPose.STANDING;
        return new PlayerMotionSimulation(world, player, player.getDimensions(pose),
                player.getDimensions(EntityPose.GLIDING), player.getDimensions(player.getPose()));
    }

    // ── Forecast ────────────────────────────────────────────────────────────

    /** Hitboxes for {@code ticks} ticks after {@code start}; index 0 is {@code start} itself. */
    public Box[] forecast(State start, Behavior behavior, int ticks) {
        Box[] boxes = new Box[Math.max(0, ticks) + 1];
        State state = start;
        boxes[0] = box(state);
        int boost = behavior.boostTicks();
        for (int i = 1; i < boxes.length; ++i) {
            state = step(state, behavior, boost-- > 0);
            boxes[i] = box(state);
        }
        return boxes;
    }

    /**
     * {@link #forecast(State, Behavior, int)} where the player switches from {@code behavior} to
     * {@code later} after tick {@code changeAt} (tick 1 is the first step from {@code start}).
     */
    public Box[] forecast(State start, Behavior behavior, Behavior later, int changeAt, int ticks) {
        Box[] boxes = new Box[Math.max(0, ticks) + 1];
        State state = start;
        boxes[0] = box(state);
        int boost = behavior.boostTicks();
        for (int i = 1; i < boxes.length; ++i) {
            state = step(state, i > changeAt ? later : behavior, boost-- > 0);
            boxes[i] = box(state);
        }
        return boxes;
    }

    /** {@link #forecast(State, Behavior, int)} where {@code behaviorAt} gives the behavior of each tick (1-based). */
    public Box[] forecast(State start, java.util.function.IntFunction<Behavior> behaviorAt, int boostTicks, int ticks) {
        Box[] boxes = new Box[Math.max(0, ticks) + 1];
        State state = start;
        boxes[0] = box(state);
        int boost = boostTicks;
        for (int i = 1; i < boxes.length; ++i) {
            state = step(state, behaviorAt.apply(i), boost-- > 0);
            boxes[i] = box(state);
        }
        return boxes;
    }

    public Box box(State state) {
        EntityDimensions dimensions = switch (state.mode()) {
            case GLIDING -> glider;
            case FLUID -> swimmer;
            default -> upright;
        };
        return dimensions.getBoxAt(state.pos());
    }

    /** One tick with collisions; {@code boost}: an attached firework pushes after this move. */
    public State step(State state, Behavior behavior, boolean boost) {
        Vec3d move = movement(state, behavior);
        Vec3d moved = collide(box(state), move, state.onGround() && state.mode() == Mode.GROUND);
        // Entity#move: horizontal axes compare approximately, the vertical exactly.
        boolean xHit = !MathHelper.approximatelyEquals(move.x, moved.x);
        boolean yHit = move.y != moved.y;
        boolean zHit = !MathHelper.approximatelyEquals(move.z, moved.z);
        boolean onGround = yHit && move.y < 0.0;
        Vec3d after = new Vec3d(xHit ? 0.0 : move.x, yHit ? 0.0 : move.y, zHit ? 0.0 : move.z);
        Mode mode;
        if (state.mode() == Mode.FLUID) {
            mode = Mode.FLUID;
        } else if (state.mode() == Mode.GLIDING && !onGround) {
            mode = Mode.GLIDING;
        } else {
            mode = onGround ? Mode.GROUND : Mode.AIR;
        }
        return new State(state.pos().add(moved), velocityAfter(after, state, behavior, boost), onGround, mode);
    }

    /** The movement of one tick from {@code state}, before collisions. */
    static Vec3d movement(State state, Behavior behavior) {
        Vec3d v = state.velocity();
        switch (state.mode()) {
            case GLIDING:
                return glide(v, behavior.yaw(), behavior.pitch());
            case FLUID:
                return v;
            default:
                double vx = v.x;
                double vy = v.y;
                double vz = v.z;
                if (state.onGround() && behavior.hop()) {
                    vy = Math.max(JUMP_VELOCITY, vy);
                    if (behavior.sprinting()) {
                        float yaw = behavior.yaw() * RADIANS;
                        vx += -MathHelper.sin(yaw) * SPRINT_JUMP_BOOST;
                        vz += MathHelper.cos(yaw) * SPRINT_JUMP_BOOST;
                    }
                }
                double accel = state.onGround()
                        ? behavior.groundSpeed() * (1.0 - GROUND_FRICTION)
                        : (behavior.sprinting() ? SPRINT_AIR_SPEED : AIR_SPEED);
                Vec3d input = behavior.input();
                return new Vec3d(vx + input.x * accel, vy, vz + input.z * accel);
        }
    }

    /** The velocity the next tick starts from, given this tick's movement with collided axes zeroed. */
    static Vec3d velocityAfter(Vec3d after, State state, Behavior behavior, boolean boost) {
        switch (state.mode()) {
            case GLIDING:
                return boost ? push(after, behavior.yaw(), behavior.pitch()) : after;
            case FLUID:
                return after;
            default:
                double friction = state.onGround() ? GROUND_FRICTION : AIR_FRICTION;
                return new Vec3d(after.x * friction, (after.y - GRAVITY) * VERTICAL_DRAG, after.z * friction);
        }
    }

    /** LivingEntity#calcGlidingVelocity with the default gravity, float math as vanilla. */
    static Vec3d glide(Vec3d velocity, float yaw, float pitch) {
        Vec3d look = rotationVector(pitch, yaw);
        float f = pitch * RADIANS;
        double d = Math.sqrt(look.x * look.x + look.z * look.z);
        double e = velocity.horizontalLength();
        double h = MathHelper.square(Math.cos(f));
        Vec3d v = velocity.add(0.0, GRAVITY * (-1.0 + h * 0.75), 0.0);
        if (v.y < 0.0 && d > 0.0) {
            double i = v.y * -0.1 * h;
            v = v.add(look.x * i / d, i, look.z * i / d);
        }
        if (f < 0.0f && d > 0.0) {
            double i = e * -MathHelper.sin(f) * 0.04;
            v = v.add(-look.x * i / d, i * 3.2, -look.z * i / d);
        }
        if (d > 0.0) {
            v = v.add((look.x / d * e - v.x) * 0.1, 0.0, (look.z / d * e - v.z) * 0.1);
        }
        return v.multiply(0.99f, 0.98f, 0.99f);
    }

    /** FireworkRocketEntity#tick's push on a gliding shooter. */
    static Vec3d push(Vec3d velocity, float yaw, float pitch) {
        Vec3d look = rotationVector(pitch, yaw);
        return velocity.add(look.x * 0.1 + (look.x * 1.5 - velocity.x) * 0.5,
                look.y * 0.1 + (look.y * 1.5 - velocity.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - velocity.z) * 0.5);
    }

    /** Entity#getRotationVector(pitch, yaw). */
    static Vec3d rotationVector(float pitch, float yaw) {
        float f = pitch * RADIANS;
        float g = -yaw * RADIANS;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    private Vec3d collide(Box box, Vec3d move, boolean canStep) {
        if (world == null) {
            if (Double.isNaN(floor)) return move;
            return new Vec3d(move.x, Math.max(move.y, floor - box.minY), move.z);
        }
        Vec3d moved = Entity.adjustMovementForCollisions(player, move, box, world, NO_ENTITY_COLLISIONS);
        boolean blockedSideways = !MathHelper.approximatelyEquals(move.x, moved.x)
                || !MathHelper.approximatelyEquals(move.z, moved.z);
        if (!canStep || !blockedSideways) return moved;
        // The step Entity#move takes on ground: rise, slide, settle back onto what was climbed.
        Vec3d up = Entity.adjustMovementForCollisions(player, new Vec3d(0.0, STEP_HEIGHT, 0.0), box, world, NO_ENTITY_COLLISIONS);
        Box raised = box.offset(up);
        Vec3d slide = Entity.adjustMovementForCollisions(player, new Vec3d(move.x, 0.0, move.z), raised, world, NO_ENTITY_COLLISIONS);
        Box slid = raised.offset(slide);
        Vec3d down = Entity.adjustMovementForCollisions(player, new Vec3d(0.0, Math.min(0.0, move.y) - up.y, 0.0),
                slid, world, NO_ENTITY_COLLISIONS);
        Vec3d stepped = up.add(slide).add(down);
        return stepped.horizontalLengthSquared() > moved.horizontalLengthSquared() + 1.0E-7 ? stepped : moved;
    }

    // ── Fit ─────────────────────────────────────────────────────────────────

    /**
     * The state at the newest of {@code updates} (oldest first, one mode throughout), with the
     * velocity that best explains them under {@code behavior}: Gauss-Newton over the velocity at
     * the oldest update, simulating without collisions. Fitting several updates averages out the
     * jitter in when each one arrived. On ground only the horizontal positions count (the height
     * is the terrain's). {@code boostFrom}: first tick an attached firework pushed on.
     */
    public static State fit(List<PlayerTrajectoryEngine.PositionUpdate> updates, Mode mode, Behavior behavior,
                            long boostFrom) {
        return fit(updates, mode, behavior, boostFrom, null);
    }

    /**
     * {@link #fit(List, Mode, Behavior, long)} over a track that changes mode: {@code modeAt} gives
     * the mode the player was in on each tick, so a jump (ground to air: the jump velocity and
     * sprint push apply) or a landing (air to ground: the floor takes the height) inside the window
     * is simulated as it happened instead of being fitted as steady movement. Null: {@code mode}
     * throughout. The result is in {@code mode}.
     */
    public static State fit(List<PlayerTrajectoryEngine.PositionUpdate> updates, Mode mode, Behavior behavior,
                            long boostFrom, LongFunction<Mode> modeAt) {
        PlayerTrajectoryEngine.PositionUpdate first = updates.get(0);
        PlayerTrajectoryEngine.PositionUpdate last = updates.get(updates.size() - 1);
        boolean onGround = mode == Mode.GROUND;
        LongFunction<Mode> modes = modeAt != null ? modeAt : tick -> mode;
        boolean startsOnGround = modes.apply(first.tick()) == Mode.GROUND;
        long span = last.tick() - first.tick();
        if (updates.size() < 2 || span <= 0) {
            return new State(last.pos(), Vec3d.ZERO, onGround, mode);
        }
        Behavior steady = behavior.withHop(false);
        Vec3d average = last.pos().subtract(first.pos()).multiply(1.0 / span);
        double[] v = {average.x, startsOnGround ? 0.0 : average.y, average.z};
        int residualCount = (updates.size() - 1) * 3;
        double[][] jacobian = new double[residualCount][3];
        for (int iteration = 0; iteration < FIT_ITERATIONS; ++iteration) {
            double[] r = residuals(updates, modes, steady, boostFrom, v);
            for (int p = 0; p < 3; ++p) {
                double h = 1.0E-6 * Math.max(1.0, Math.abs(v[p]));
                double[] shifted = v.clone();
                shifted[p] += h;
                double[] rs = residuals(updates, modes, steady, boostFrom, shifted);
                for (int i = 0; i < residualCount; ++i) jacobian[i][p] = (rs[i] - r[i]) / h;
            }
            double[][] a = new double[3][3];
            double[] g = new double[3];
            for (int i = 0; i < residualCount; ++i) {
                for (int p = 0; p < 3; ++p) {
                    g[p] -= jacobian[i][p] * r[i];
                    for (int q = 0; q < 3; ++q) a[p][q] += jacobian[i][p] * jacobian[i][q];
                }
            }
            for (int p = 0; p < 3; ++p) a[p][p] += 1.0E-9;
            if (startsOnGround) {
                // The height on ground is the floor's, not a free parameter.
                a[1][0] = a[1][2] = a[0][1] = a[2][1] = 0.0;
                a[1][1] = 1.0;
                g[1] = 0.0;
            }
            double[] dv = solve3(a, g);
            if (dv == null) break;
            double change = 0.0;
            for (int p = 0; p < 3; ++p) {
                v[p] += dv[p];
                change = Math.max(change, Math.abs(dv[p]));
            }
            if (change < 1.0E-9) break;
        }
        State state = new State(first.pos(), new Vec3d(v[0], v[1], v[2]), startsOnGround, modes.apply(first.tick()));
        for (long tick = first.tick() + 1; tick <= last.tick(); ++tick) {
            state = advance(state, steady, tick >= boostFrom, modes.apply(tick));
        }
        return new State(last.pos(), state.velocity(), onGround, mode);
    }

    /**
     * One tick without collisions into {@code next}; on ground the floor holds the height, and a
     * step from ground into air is a jump.
     */
    private static State advance(State state, Behavior behavior, boolean boost, Mode next) {
        boolean jump = state.mode() == Mode.GROUND && next == Mode.AIR;
        Behavior acting = jump ? behavior.withHop(true) : behavior;
        Vec3d move = movement(state, acting);
        if (state.mode() == Mode.GROUND && !jump) move = new Vec3d(move.x, 0.0, move.z);
        return new State(state.pos().add(move), velocityAfter(move, state, acting, boost), next == Mode.GROUND, next);
    }

    private static double[] residuals(List<PlayerTrajectoryEngine.PositionUpdate> updates, LongFunction<Mode> modes,
                                      Behavior behavior, long boostFrom, double[] v) {
        double[] r = new double[(updates.size() - 1) * 3];
        PlayerTrajectoryEngine.PositionUpdate first = updates.get(0);
        Mode startMode = modes.apply(first.tick());
        State state = new State(first.pos(), new Vec3d(v[0], v[1], v[2]), startMode == Mode.GROUND, startMode);
        long tick = first.tick();
        for (int j = 1; j < updates.size(); ++j) {
            PlayerTrajectoryEngine.PositionUpdate update = updates.get(j);
            while (tick < update.tick()) {
                ++tick;
                state = advance(state, behavior, tick >= boostFrom, modes.apply(tick));
            }
            Vec3d error = state.pos().subtract(update.pos());
            r[(j - 1) * 3] = error.x;
            r[(j - 1) * 3 + 1] = modes.apply(update.tick()) == Mode.GROUND ? 0.0 : error.y;
            r[(j - 1) * 3 + 2] = error.z;
        }
        return r;
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private static double[] solve3(double[][] a, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; ++i) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
        }
        for (int col = 0; col < 3; ++col) {
            int pivot = col;
            for (int row = col + 1; row < 3; ++row) {
                if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) pivot = row;
            }
            if (Math.abs(m[pivot][col]) < 1.0E-15) return null;
            double[] swap = m[col];
            m[col] = m[pivot];
            m[pivot] = swap;
            for (int row = col + 1; row < 3; ++row) {
                double factor = m[row][col] / m[col][col];
                for (int k = col; k < 4; ++k) m[row][k] -= factor * m[col][k];
            }
        }
        double[] x = new double[3];
        for (int row = 2; row >= 0; --row) {
            double sum = m[row][3];
            for (int k = row + 1; k < 3; ++k) sum -= m[row][k] * x[k];
            x[row] = sum / m[row][row];
        }
        return x;
    }

    // ── Prediction ──────────────────────────────────────────────────────────

    /**
     * Weighted forecasts of {@code player}'s hitbox for {@code horizon} ticks after its newest
     * server position, from the history {@link PlayerTrajectoryEngine} recorded:
     * <ul>
     *   <li>gliding: the look held (0.6), and swung {@value #GLIDE_LOOK_SWING} degrees up or down
     *   (0.2 each);</li>
     *   <li>ground and air: the input held, the input held while jumping on every landing, and the
     *   input released, weighted toward jumping when the player has been sprint-jumping;</li>
     *   <li>fluids, or a player standing still: the fitted movement held.</li>
     * </ul>
     */
    public static Prediction predict(World world, PlayerEntity player, int horizon) {
        return predict(world, player, horizon, false);
    }

    /**
     * {@link #predict(World, PlayerEntity, int)} with a wide hedge: besides holding, jumping and
     * releasing, the player may turn their heading (strafe, circle, cut back) or swing their glide
     * look early or late in the forecast, with the observed heading trend steering the odds. For
     * a solver that must connect with a player whose next input is unknown.
     */
    public static Prediction predictWide(World world, PlayerEntity player, int horizon) {
        return predict(world, player, horizon, true);
    }

    /** One prediction per (player, inputs) per tick: modules ask every frame, but the inputs only change per tick. */
    private record PredictionKey(Object world, int playerId, long tick, int updateCount, long newestTick,
                                 int horizon, boolean wide) {}

    private static final java.util.Map<PredictionKey, Prediction> PREDICTION_CACHE = new java.util.HashMap<>();
    private static long predictionCacheTick = Long.MIN_VALUE;

    private static Prediction predict(World world, PlayerEntity player, int horizon, boolean wide) {
        PlayerTrajectoryEngine engine = PlayerTrajectoryEngine.getInstance();
        List<PlayerTrajectoryEngine.PositionUpdate> updates = engine.updates(player);
        long tickNow = engine.currentTick();
        long newestTick = updates.isEmpty() ? Long.MIN_VALUE : updates.get(updates.size() - 1).tick();
        PredictionKey key = new PredictionKey(world, player.getId(), tickNow, updates.size(), newestTick, horizon, wide);
        synchronized (PREDICTION_CACHE) {
            if (tickNow != predictionCacheTick) {
                PREDICTION_CACHE.clear();
                predictionCacheTick = tickNow;
            }
            Prediction cached = PREDICTION_CACHE.get(key);
            if (cached != null) return cached;
        }
        Prediction computed = computePrediction(world, player, horizon, wide, engine, updates);
        synchronized (PREDICTION_CACHE) {
            if (tickNow == predictionCacheTick) PREDICTION_CACHE.put(key, computed);
        }
        return computed;
    }

    private static Prediction computePrediction(World world, PlayerEntity player, int horizon, boolean wide,
                                                PlayerTrajectoryEngine engine,
                                                List<PlayerTrajectoryEngine.PositionUpdate> updates) {
        List<PlayerTrajectoryEngine.PlayerSnapshot> history = engine.getHistory(player.getUuid());
        long now = engine.currentTick();
        Mode mode = modeOf(player);
        PlayerTrajectoryEngine.PositionUpdate newest = updates.isEmpty()
                ? new PlayerTrajectoryEngine.PositionUpdate(now, player.getTrackedPosition().getPos())
                : updates.get(updates.size() - 1);
        boolean moving = now - newest.tick() <= STILL_TICKS && updates.size() >= 2;

        float yaw = PlayerTrajectoryEngine.lookYaw(player);
        float pitch = PlayerTrajectoryEngine.lookPitch(player);
        boolean sprinting = player.isSprinting();
        FireworkRocketEntity rocket = mode == Mode.GLIDING ? attachedRocket(world, player) : null;
        int boostTicks = rocket != null ? boostTicksLeft(rocket) : 0;
        long boostFrom = rocket != null ? now - rocket.age : Long.MAX_VALUE;

        List<PlayerTrajectoryEngine.PositionUpdate> window = moving
                ? fitWindow(updates, Math.max(fitSince(history, mode, now) - 1, now - FIT_WINDOW))
                : List.of();
        if (!moving) {
            // Nothing newer can arrive for a still player, so its newest position is current.
            newest = new PlayerTrajectoryEngine.PositionUpdate(now, newest.pos());
        }
        boolean hopping = sprinting && hasJumped(history);
        List<Forecast> forecasts = forecastFrom(PlayerMotionSimulation.of(world, player), window, newest.pos(), mode,
                yaw, pitch, sprinting, hopping, boostTicks, boostFrom, tick -> modeOn(history, tick, mode),
                moving, horizon, wide ? trail(updates, Math.max(fitSince(history, mode, now) - 1, now - TRAIL_TICKS)) : null);
        return new Prediction(mode, newest.tick(), List.copyOf(forecasts));
    }

    /**
     * The forecasts from the server updates {@code window} (oldest first): the direction pressed and
     * the steady ground speed are read off the track, the velocity is fitted, and
     * {@link #forecasts} hedges over what the player does next. {@code newestPos} stands in when the
     * window is too short to fit.
     */
    static List<Forecast> forecastFrom(PlayerMotionSimulation simulation,
                                       List<PlayerTrajectoryEngine.PositionUpdate> window, Vec3d newestPos, Mode mode,
                                       float yaw, float pitch, boolean sprinting, boolean hopping, int boostTicks,
                                       long boostFrom, LongFunction<Mode> modeAt, boolean moving, int horizon) {
        return forecastFrom(simulation, window, newestPos, mode, yaw, pitch, sprinting, hopping, boostTicks, boostFrom,
                modeAt, moving, horizon, null);
    }

    /**
     * {@link #forecastFrom} with the {@link #predictWide} hedge when {@code trail} is not null: the
     * server updates of the unbroken run (oldest first), where a strafe rhythm is read from.
     */
    static List<Forecast> forecastFrom(PlayerMotionSimulation simulation,
                                       List<PlayerTrajectoryEngine.PositionUpdate> window, Vec3d newestPos, Mode mode,
                                       float yaw, float pitch, boolean sprinting, boolean hopping, int boostTicks,
                                       long boostFrom, LongFunction<Mode> modeAt, boolean moving, int horizon,
                                       List<PlayerTrajectoryEngine.PositionUpdate> trail) {
        if (trail != null && window.size() >= 3) {
            // A turn made inside the window is not averaged into the heading: only the run after it fits.
            window = sinceTurn(window);
        }
        Vec3d average = window.size() >= 2 ? averageVelocity(window) : Vec3d.ZERO;
        Vec3d input = inputDirection(average, sprinting, yaw);
        double groundSpeed = input.lengthSquared() == 0.0 ? 0.0
                : mode == Mode.GROUND ? (trail != null ? pathSpeed(window) : average.horizontalLength())
                : (sprinting ? SPRINT_SPEED : WALK_SPEED);
        Behavior hold = new Behavior(yaw, pitch, input, sprinting, groundSpeed, false, boostTicks);
        State start = window.size() >= 2
                ? fit(window, mode, hold, boostFrom, modeAt)
                : new State(newestPos, Vec3d.ZERO, mode == Mode.GROUND, mode);
        if (trail != null) {
            return wideForecasts(simulation, start, hold, mode, moving, hopping, horizon, headingTrend(window),
                    mode == Mode.GROUND || mode == Mode.AIR ? detectStrafe(trail, start) : null);
        }
        return forecasts(simulation, start, hold, mode, moving, hopping, horizon);
    }

    /**
     * The weighted hypotheses a solver hedges across, total weight 1, likeliest first:
     * <ul>
     *   <li>gliding: the look held (0.6), and swung {@value #GLIDE_LOOK_SWING} degrees up or down
     *   (0.2 each);</li>
     *   <li>ground and air: the input held, the input held while jumping on every landing, and the
     *   input released, weighted toward jumping when the player has been sprint-jumping;</li>
     *   <li>fluids, or a player standing still: the fitted movement held.</li>
     * </ul>
     */
    static List<Forecast> forecasts(PlayerMotionSimulation simulation, State start, Behavior hold, Mode mode,
                                    boolean moving, boolean hopping, int horizon) {
        List<Forecast> out = new ArrayList<>(3);
        if (!moving) {
            // The server sends any move, so a player it has not resent is where it was.
            Box[] still = new Box[Math.max(0, horizon) + 1];
            Arrays.fill(still, simulation.box(start));
            out.add(new Forecast("still", 1.0, still));
        } else if (mode == Mode.FLUID) {
            out.add(new Forecast("hold", 1.0, simulation.forecast(start, hold, horizon)));
        } else if (mode == Mode.GLIDING) {
            float pitch = hold.pitch();
            out.add(new Forecast("hold", 0.6, simulation.forecast(start, hold, horizon)));
            out.add(new Forecast("climb", 0.2, simulation.forecast(start, hold.withPitch(pitch - GLIDE_LOOK_SWING), horizon)));
            out.add(new Forecast("dive", 0.2, simulation.forecast(start, hold.withPitch(pitch + GLIDE_LOOK_SWING), horizon)));
        } else {
            double holdWeight = mode == Mode.GROUND ? (hopping ? 0.35 : 0.6) : (hopping ? 0.4 : 0.55);
            double hopWeight = mode == Mode.GROUND ? (hopping ? 0.45 : 0.15) : (hopping ? 0.4 : 0.2);
            out.add(new Forecast("hold", holdWeight, simulation.forecast(start, hold, horizon)));
            out.add(new Forecast("hop", hopWeight, simulation.forecast(start, hold.withHop(true), horizon)));
            out.add(new Forecast("release", 1.0 - holdWeight - hopWeight,
                    simulation.forecast(start, hold.released(), horizon)));
        }
        return out;
    }

    /** Swings of the heading the wide hedge covers (degrees), and the odds of each size. */
    private static final float[] TURN_SIZES = {40.0f, 90.0f};
    private static final double[] TURN_SIZE_WEIGHTS = {0.6, 0.4};
    /** Ticks after the observed state a hedged change lands: soon, and later in the flight. */
    private static final int[] TURN_TICKS = {2, 7};
    /** Heading swings below this (degrees) across a window read as a steady run. */
    private static final double STEADY_HEADING = 12.0;
    private static final float GLIDE_YAW_SWING = 20.0f;

    /**
     * The weighted hypotheses of {@link #predictWide}, total weight 1, likeliest first (the held
     * movement stays first). Ground and air: hold, jump on every landing, release, and the heading
     * swung either way by a small or a large turn, early or late. A trend of the heading in the
     * observed window gives the matching direction more odds. Gliding: the look held, pitched up or
     * down, and yawed either way. Fluids and still players are as in {@link #forecasts}.
     */
    static List<Forecast> wideForecasts(PlayerMotionSimulation simulation, State start, Behavior hold, Mode mode,
                                        boolean moving, boolean hopping, int horizon, double headingTrend,
                                        Strafe strafe) {
        if (!moving || mode == Mode.FLUID) {
            return forecasts(simulation, start, hold, mode, moving, hopping, horizon);
        }
        List<Forecast> out = new ArrayList<>(12);
        if (mode == Mode.GLIDING) {
            float pitch = hold.pitch();
            out.add(new Forecast("hold", 0.4, simulation.forecast(start, hold, horizon)));
            out.add(new Forecast("climb", 0.15, simulation.forecast(start, hold.withPitch(pitch - GLIDE_LOOK_SWING), horizon)));
            out.add(new Forecast("dive", 0.15, simulation.forecast(start, hold.withPitch(pitch + GLIDE_LOOK_SWING), horizon)));
            out.add(new Forecast("yaw-left", 0.15, simulation.forecast(start, hold,
                    hold.turned(-GLIDE_YAW_SWING), 3, horizon)));
            out.add(new Forecast("yaw-right", 0.15, simulation.forecast(start, hold,
                    hold.turned(GLIDE_YAW_SWING), 3, horizon)));
            return out;
        }
        if (hold.input().lengthSquared() == 0.0) {
            // Nothing pressed to swing: the plain hedge.
            return forecasts(simulation, start, hold, mode, true, hopping, horizon);
        }
        double holdWeight = mode == Mode.GROUND ? (hopping ? 0.34 : 0.50) : (hopping ? 0.34 : 0.48);
        double hopWeight = mode == Mode.GROUND ? (hopping ? 0.22 : 0.08) : (hopping ? 0.15 : 0.06);
        double releaseWeight = 0.07;
        if (Math.abs(headingTrend) < STEADY_HEADING) {
            // A steady run is likelier to go on: that mass comes from the turns.
            holdWeight += 0.08;
        }
        double strafeMass = 0.0;
        if (strafe != null) {
            // A rhythm seen in the track: that is what they go on doing, ahead of any one-off turn.
            strafeMass = strafe.confirmed() ? 0.55 : 0.4;
            holdWeight = Math.min(holdWeight, 0.5 * (1.0 - strafeMass));
            hopWeight = Math.min(hopWeight, 0.5 * (1.0 - strafeMass) - holdWeight + 0.05);
        }
        double turnMass = 1.0 - holdWeight - hopWeight - releaseWeight - strafeMass;
        if (strafe != null) {
            out.add(new Forecast("strafe-0", strafeMass * 0.5, strafeForecast(simulation, start, hold, strafe, 0, horizon)));
            out.add(new Forecast("strafe-early", strafeMass * 0.25, strafeForecast(simulation, start, hold, strafe, -2, horizon)));
            out.add(new Forecast("strafe-late", strafeMass * 0.25, strafeForecast(simulation, start, hold, strafe, 2, horizon)));
        }
        out.add(new Forecast("hold", holdWeight, simulation.forecast(start, hold, horizon)));
        out.add(new Forecast("hop", hopWeight, simulation.forecast(start, hold.withHop(true), horizon)));
        out.add(new Forecast("release", releaseWeight, simulation.forecast(start, hold.released(), horizon)));
        // Direction odds: even, or 70/30 toward the way the heading has been swinging.
        double toward = Math.abs(headingTrend) >= STEADY_HEADING ? 0.7 : 0.5;
        for (int side = 0; side < 2; ++side) {
            float sign = side == 0 ? 1.0f : -1.0f;
            boolean trended = headingTrend != 0.0 && Math.signum(headingTrend) == sign;
            double sideWeight = turnMass * (Math.abs(headingTrend) >= STEADY_HEADING
                    ? (trended ? toward : 1.0 - toward) : 0.5);
            for (int size = 0; size < TURN_SIZES.length; ++size) {
                for (int when = 0; when < TURN_TICKS.length; ++when) {
                    double weight = sideWeight * TURN_SIZE_WEIGHTS[size] / TURN_TICKS.length;
                    Behavior swung = hold.turned(sign * TURN_SIZES[size]);
                    out.add(new Forecast("turn", weight,
                            simulation.forecast(start, hold, swung, TURN_TICKS[when], horizon)));
                }
            }
        }
        return out;
    }

    /** How far back (client ticks) a strafe rhythm is read from. */
    private static final int TRAIL_TICKS = 24;
    /** Two headings this far apart (degrees) are two different directions pressed. */
    private static final double STRAFE_SEPARATION = 30.0;

    /**
     * A strafe read off the track: the two headings alternated between, the state now, the ticks
     * each dwell lasts and when (ticks after the newest update) the next switch is due.
     */
    record Strafe(Vec3d current, Vec3d other, double speed, int halfPeriod, int nextSwitch, boolean confirmed) {}

    /** The updates of {@code updates} from {@code fromTick} on, cut at the last stand-still gap. */
    static List<PlayerTrajectoryEngine.PositionUpdate> trail(List<PlayerTrajectoryEngine.PositionUpdate> updates,
                                                             long fromTick) {
        int begin = updates.size();
        for (int i = updates.size() - 1; i >= 0; --i) {
            PlayerTrajectoryEngine.PositionUpdate update = updates.get(i);
            if (i < updates.size() - 1 && updates.get(i + 1).tick() - update.tick() > STILL_TICKS) break;
            if (update.tick() < fromTick && updates.size() - i > 3) break;
            begin = i;
        }
        return new ArrayList<>(updates.subList(begin, updates.size()));
    }

    /**
     * The rhythm of a player alternating between two headings (an A/D strafe): each segment between
     * updates is labelled with the nearer of the newest heading and the one farthest from it, and
     * the run lengths between label changes give the dwell. One change seen gives the dwell of the
     * run before it (a lower bound, so unconfirmed); two or more are averaged (confirmed). Null when
     * the track shows no second heading or the player is too slow to have one.
     */
    static Strafe detectStrafe(List<PlayerTrajectoryEngine.PositionUpdate> trail, State start) {
        int n = trail.size() - 1;
        if (n < 3) return null;
        double[] heading = new double[n];
        double[] speed = new double[n];
        for (int k = 0; k < n; ++k) {
            heading[k] = segmentHeading(trail.get(k), trail.get(k + 1));
            if (Double.isNaN(heading[k])) {
                // A pause inside the run: only what came after it is a rhythm.
                trail = trail.subList(k + 1, trail.size());
                return detectStrafe(trail, start);
            }
            Vec3d d = trail.get(k + 1).pos().subtract(trail.get(k).pos());
            speed[k] = d.horizontalLength() / Math.max(1L, trail.get(k + 1).tick() - trail.get(k).tick());
        }
        double a = heading[n - 1];
        double farthest = 0.0;
        double b = a;
        for (int k = 0; k < n; ++k) {
            double apart = Math.abs(MathHelper.wrapDegrees((float) (heading[k] - a)));
            if (apart > farthest) {
                farthest = apart;
                b = heading[k];
            }
        }
        if (farthest < STRAFE_SEPARATION) return null;
        boolean[] isA = new boolean[n];
        for (int k = 0; k < n; ++k) {
            isA[k] = Math.abs(MathHelper.wrapDegrees((float) (heading[k] - a)))
                    <= Math.abs(MathHelper.wrapDegrees((float) (heading[k] - b)));
        }
        // Runs of one label: [first segment, last segment], in order.
        List<int[]> runs = new ArrayList<>();
        int from = 0;
        for (int k = 1; k <= n; ++k) {
            if (k == n || isA[k] != isA[from]) {
                runs.add(new int[]{from, k - 1});
                from = k;
            }
        }
        if (runs.size() < 2) return null;
        // A run's length in ticks: from the update it starts at to the update it ends at.
        double dwellSum = 0.0;
        int dwells = 0;
        for (int r = 0; r < runs.size() - 1; ++r) {
            int[] run = runs.get(r);
            boolean touchesStart = r == 0;
            long ticks = trail.get(run[1] + 1).tick() - trail.get(run[0]).tick();
            if (touchesStart && runs.size() > 2) continue; // truncated by the start of the trail
            dwellSum += ticks;
            ++dwells;
        }
        if (dwells == 0) {
            int[] first = runs.get(0);
            dwellSum = trail.get(first[1] + 1).tick() - trail.get(first[0]).tick();
            dwells = 1;
        }
        int half = (int) Math.max(1, Math.round(dwellSum / dwells));
        int[] current = runs.get(runs.size() - 1);
        long newestTick = trail.get(n).tick();
        long switchedAt = trail.get(current[0]).tick();
        int next = (int) Math.max(1L, switchedAt + half - newestTick);
        double steady = 0.0;
        for (int k = 0; k < n; ++k) steady += speed[k];
        steady /= n;
        Vec3d currentDir = unit(a);
        Vec3d otherDir = unit(b);
        return new Strafe(currentDir, otherDir, steady, half, next, runs.size() >= 3);
    }

    private static Vec3d unit(double headingDegrees) {
        double r = Math.toRadians(headingDegrees);
        return new Vec3d(-Math.sin(r), 0.0, Math.cos(r));
    }

    /**
     * The movement of a strafe continuing: the start velocity is the newest heading at the steady
     * speed, the input switches to the other heading when due ({@code shift} ticks off) and every
     * dwell after.
     */
    private static Box[] strafeForecast(PlayerMotionSimulation simulation, State start, Behavior hold, Strafe strafe,
                                        int shift, int horizon) {
        double carry = start.onGround() ? GROUND_FRICTION : 1.0;
        State from = new State(start.pos(), new Vec3d(strafe.current().x * strafe.speed() * carry, start.velocity().y,
                strafe.current().z * strafe.speed() * carry), start.onGround(), start.mode());
        int first = Math.max(1, strafe.nextSwitch() + shift);
        int half = Math.max(1, strafe.halfPeriod());
        Behavior current = new Behavior(hold.yaw(), hold.pitch(), strafe.current(), hold.sprinting(), strafe.speed(),
                false, hold.boostTicks());
        Behavior other = new Behavior(hold.yaw(), hold.pitch(), strafe.other(), hold.sprinting(), strafe.speed(),
                false, hold.boostTicks());
        return simulation.forecast(from, tick -> tick < first ? current : (((tick - first) / half) % 2 == 0 ? other : current),
                hold.boostTicks(), horizon);
    }

    /**
     * How far the heading has swung over the window, in degrees, positive one way and negative the
     * other: the direction of the newest segment against the oldest. 0 when the window is too short
     * or too slow to have a heading.
     */
    static double headingTrend(List<PlayerTrajectoryEngine.PositionUpdate> window) {
        if (window.size() < 3) return 0.0;
        double first = segmentHeading(window.get(0), window.get(1));
        double last = segmentHeading(window.get(window.size() - 2), window.get(window.size() - 1));
        if (Double.isNaN(first) || Double.isNaN(last)) return 0.0;
        return MathHelper.wrapDegrees((float) (last - first));
    }

    private static double segmentHeading(PlayerTrajectoryEngine.PositionUpdate from,
                                         PlayerTrajectoryEngine.PositionUpdate to) {
        Vec3d d = to.pos().subtract(from.pos());
        long ticks = Math.max(1L, to.tick() - from.tick());
        if (d.horizontalLength() / ticks < INPUT_SPEED) return Double.NaN;
        return Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    public static Mode modeOf(PlayerEntity player) {
        if (player.isTouchingWater() || player.isInLava()) return Mode.FLUID;
        if (player.isGliding()) return Mode.GLIDING;
        return player.isOnGround() ? Mode.GROUND : Mode.AIR;
    }

    /**
     * First tick a fit may start from: across jumps and landings (ground and air share one
     * simulation, {@link #fit(List, Mode, Behavior, long, LongFunction)}), otherwise the unbroken run of {@code mode}.
     */
    static long fitSince(List<PlayerTrajectoryEngine.PlayerSnapshot> history, Mode mode, long now) {
        if (mode != Mode.GROUND && mode != Mode.AIR) return modeSince(history, mode, now);
        long since = now;
        for (int i = history.size() - 1; i >= 0; --i) {
            Mode m = history.get(i).mode();
            if (m != Mode.GROUND && m != Mode.AIR) break;
            since = history.get(i).tick();
        }
        return since;
    }

    /** The mode the newest snapshot at or before {@code tick} recorded; {@code fallback} before the history. */
    static Mode modeOn(List<PlayerTrajectoryEngine.PlayerSnapshot> history, long tick, Mode fallback) {
        for (int i = history.size() - 1; i >= 0; --i) {
            if (history.get(i).tick() <= tick) return history.get(i).mode();
        }
        return fallback;
    }

    /** First tick of the unbroken run of {@code mode} that reaches the present. */
    private static long modeSince(List<PlayerTrajectoryEngine.PlayerSnapshot> history, Mode mode, long now) {
        long since = now;
        for (int i = history.size() - 1; i >= 0; --i) {
            if (history.get(i).mode() != mode) break;
            since = history.get(i).tick();
        }
        return since;
    }

    /**
     * The newest updates from {@code fromTick} on (at least two when there are two). A gap longer
     * than {@value #STILL_TICKS} ticks means the player stood still before it; the server sends
     * the first move within one tracking interval, so the still position is re-timed to two ticks
     * before the move instead of when it was first seen.
     */
    static List<PlayerTrajectoryEngine.PositionUpdate> fitWindow(
            List<PlayerTrajectoryEngine.PositionUpdate> updates, long fromTick) {
        List<PlayerTrajectoryEngine.PositionUpdate> window = new ArrayList<>();
        for (int i = updates.size() - 1; i >= 0 && window.size() < FIT_UPDATES; --i) {
            PlayerTrajectoryEngine.PositionUpdate update = updates.get(i);
            if (!window.isEmpty()) {
                PlayerTrajectoryEngine.PositionUpdate next = window.get(0);
                if (next.tick() - update.tick() > STILL_TICKS) {
                    if (window.size() < 2) {
                        window.add(0, new PlayerTrajectoryEngine.PositionUpdate(next.tick() - 2, update.pos()));
                    }
                    break;
                }
                if (update.tick() < fromTick && window.size() >= 2) break;
            }
            window.add(0, update);
        }
        return window;
    }

    /** Headings within this many degrees of the newest are one run. */
    private static final double RUN_HEADING = 20.0;

    /**
     * The newest updates whose segments all head within {@value #RUN_HEADING} degrees of the newest
     * segment; at least the newest segment (two updates) so a fit has something to work with.
     */
    static List<PlayerTrajectoryEngine.PositionUpdate> sinceTurn(List<PlayerTrajectoryEngine.PositionUpdate> window) {
        int n = window.size() - 1;
        double newest = segmentHeading(window.get(n - 1), window.get(n));
        if (Double.isNaN(newest)) return window;
        int begin = n - 1;
        for (int k = n - 2; k >= 0; --k) {
            double heading = segmentHeading(window.get(k), window.get(k + 1));
            if (Double.isNaN(heading)
                    || Math.abs(MathHelper.wrapDegrees((float) (heading - newest))) > RUN_HEADING) break;
            begin = k;
        }
        // Fewer than three updates fit worse than the whole window (arrival jitter swamps one segment).
        return n - begin + 1 >= 3 ? window.subList(begin, window.size()) : window;
    }

    /** Mean speed along the path (blocks per tick): unlike the vector average, turns do not cancel. */
    private static double pathSpeed(List<PlayerTrajectoryEngine.PositionUpdate> window) {
        double length = 0.0;
        for (int k = 1; k < window.size(); ++k) {
            length += window.get(k).pos().subtract(window.get(k - 1).pos()).horizontalLength();
        }
        long span = Math.max(1L, window.get(window.size() - 1).tick() - window.get(0).tick());
        return length / span;
    }

    private static Vec3d averageVelocity(List<PlayerTrajectoryEngine.PositionUpdate> window) {
        PlayerTrajectoryEngine.PositionUpdate first = window.get(0);
        PlayerTrajectoryEngine.PositionUpdate last = window.get(window.size() - 1);
        long span = Math.max(1L, last.tick() - first.tick());
        return last.pos().subtract(first.pos()).multiply(1.0 / span);
    }

    /**
     * The direction the player presses: the way it is going, or, when barely moving, the look
     * while sprinting (sprinting needs the forward key). Unit horizontal, or zero.
     */
    private static Vec3d inputDirection(Vec3d velocity, boolean sprinting, float yaw) {
        double speed = velocity.horizontalLength();
        if (speed > INPUT_SPEED) return new Vec3d(velocity.x / speed, 0.0, velocity.z / speed);
        if (!sprinting) return Vec3d.ZERO;
        float radians = yaw * RADIANS;
        return new Vec3d(-MathHelper.sin(radians), 0.0, MathHelper.cos(radians));
    }

    /** A sprint-jump in the recorded history: a ground tick followed by an airborne one. */
    private static boolean hasJumped(List<PlayerTrajectoryEngine.PlayerSnapshot> history) {
        for (int i = 1; i < history.size(); ++i) {
            PlayerTrajectoryEngine.PlayerSnapshot before = history.get(i - 1);
            PlayerTrajectoryEngine.PlayerSnapshot after = history.get(i);
            if (before.mode() == Mode.GROUND && after.mode() == Mode.AIR) return true;
        }
        return false;
    }

    /**
     * A firework riding with this glider, or null. An attached rocket sits at the shooter's
     * position plus a 0.5 hand offset every tick; the flag that marks it is private, so nearness
     * stands in for it.
     */
    private static FireworkRocketEntity attachedRocket(World world, PlayerEntity player) {
        if (world == null) return null;
        Vec3d at = player.getEntityPos();
        for (FireworkRocketEntity rocket : world.getEntitiesByClass(FireworkRocketEntity.class,
                player.getBoundingBox().expand(1.5), rocket -> !rocket.isRemoved())) {
            if (rocket.getEntityPos().squaredDistanceTo(at) <= 1.44) return rocket;
        }
        return null;
    }

    /** Expected ticks of push left: a rocket's lifetime averages 10 x (1 + flight duration) + 5. */
    private static int boostTicksLeft(FireworkRocketEntity rocket) {
        FireworksComponent fireworks = rocket.getStack().get(DataComponentTypes.FIREWORKS);
        int lifetime = 10 * (1 + (fireworks == null ? 0 : fireworks.flightDuration())) + 5;
        return Math.max(0, lifetime - rocket.age);
    }
}
