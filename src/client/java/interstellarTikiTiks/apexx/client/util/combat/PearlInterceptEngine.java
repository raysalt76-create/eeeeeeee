package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.MouseAimHelper;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pearl Intercept Engine: throws a wind charge into the flight of an ender pearl someone else threw,
 * so the pearl runs into the charge mid-air and ends there (its owner lands at that point) instead
 * of where it was aimed.
 *
 * <p>It is {@link PearlCatchEngine} turned around: same 1.21.11 physics ({@link PearlSimulation},
 * {@link WindChargeIntercept}, {@link ThrowPhysics}), same flick ({@link RotationManager#stepAim}) and
 * same same-tick select-then-use, but the pearl is already flying:</p>
 * <ul>
 *   <li><b>Target.</b> Pearls not owned by the local player or a friend, nearest first, within range;
 *   the first one a throw can still catch is locked by entity id.</li>
 *   <li><b>Tracking.</b> The client runs the server's {@code ThrownEntity} physics on the state the
 *   server sent, so every tick the flight is re-traced from the pearl's client state and its position
 *   is checked against the previous tick's trace. A server position sync (projectiles have no client
 *   interpolation) can set the copy a tick back or ahead along the same path; the match then
 *   re-anchors which server age the copy shows. The throw waits until the flight has been watched for
 *   the configured ticks and the last step landed on the trace (nothing moved the pearl off it).</li>
 *   <li><b>Timing.</b> The client sees the pearl one-way latency late and the use packet reaches the
 *   server one-way latency later; with the two tick clocks out of phase, a use sent now is handled
 *   when the pearl is on average {@code RTT / 50 ms + 0.5} ticks older than the client copy, give or
 *   take one tick. Shots are ranked with {@link WindChargeIntercept.Rank#INTERCEPT}, which weighs that
 *   slip and, among reliable looks, takes the earliest catch: the nearest point of the flight.</li>
 *   <li><b>Aim.</b> Every tick the plan is re-solved within what the turn can reach, and the look steps
 *   toward it at most the flick speed per tick, spread over the ticks left (PearlCatch's flick):
 *   the visible camera turns, or only the server-side rotation with silent aim. The throw goes once a
 *   look within one small step of the current one catches; the look is then held for the rest of the
 *   tick and the hotbar slot is restored on the next.</li>
 *   <li><b>Verify and retry.</b> A charge that was thrown is checked against the pearl: the client
 *   sees the pearl removed when its copy reaches the catch age, so a pearl still flying two ticks past
 *   the planned catch got past the charge. The pearl is then re-traced and, when the wind charge is off
 *   cooldown and another throw can still catch it, thrown at again (up to the configured throws).</li>
 *   <li><b>Latency.</b> The lead is re-read every tick from a short window of the server-reported
 *   latency, so a ping change while the pearl flies moves the plan with it.</li>
 *   <li><b>Target choice.</b> Every pearl in range is planned; the one with the best expected catch
 *   rate is locked, the nearest on ties.</li>
 * </ul>
 */
public final class PearlInterceptEngine {
    private enum Stage {
        IDLE, TRACK, SENT, DONE
    }

    /**
     * @param silent      aim with a server-side rotation and leave the visible camera alone
     * @param flickSpeed  most degrees the look turns a tick; the turn is spread over available ticks
     * @param range       farthest pearl considered, blocks
     * @param trackTicks  ticks the pearl's flight is watched before the wind charge may be thrown
     * @param maxWait     latest tick after the start that the wind charge may still be thrown
     * @param maxFlight   longest wind charge flight to the intercept, ticks
     * @param maxThrows   wind charges thrown at one pearl while earlier ones missed it; the caller passes 1
     *                    unless the keybind is held, and it is read every tick
     */
    public record Config(boolean silent, float flickSpeed, double range, int trackTicks, int maxWait,
                         int maxFlight, int maxThrows, double throwDelayMs, double swapBackDelayMs) {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final int PEARL_TRACE_TICKS = 160;
    /** Throw ticks searched past the earliest allowed one. */
    private static final int PLAN_HORIZON = 8;
    private static final int MAX_CANDIDATES = 6;
    /** A candidate must beat the nearest pearl's score by this much to be locked instead of it. */
    private static final double CANDIDATE_GAIN = 0.05;
    /** Pearl ages searched either side of the expected one when matching the trace. */
    private static final int MATCH_WINDOW = 2;
    /** Ticks past the planned catch age after which a pearl still flying got past the charge. */
    private static final int MISS_SLACK = 2;
    private static final int LATENCY_WINDOW = 6;
    private static final float SENT_LOOK_TOLERANCE = 1.4f;
    private static final double FREE_LOOK_STEP = 0.05;
    /**
     * Intercept score that needs no waiting for better: the whole launch spread catches, on the
     * nominal tick and one slip side. Pearls flying away (chased) usually reach it; pearls crossing
     * or closing in catch on one exact tick only (0.5), so a later look that survives a slip wins.
     */
    private static final double GOOD_SCORE = 0.75;
    private static final double WAIT_GAIN = 0.1;
    /** The pearl further than this (squared, blocks) from last tick's trace was moved off it. */
    private static final double TRACK_ERROR_SQ = 0.01;
    private static final double TELEPORT_MOTION_SQ = 16.0;
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double PLAYER_VERTICAL_DRAG = 0.98;
    private static final float TICK_MS = 50.0f;
    private static final int MAX_LEAD = 20;
    /** Shortest gap between two of our presses that a player could physically produce, ms. */
    private static final long MIN_PRESS_GAP_MS = 60L;

    private final Object owner;
    /** When this engine last pressed use (fresh-press gate for the vanilla use throttle). */
    private long lastPressAtMs;
    private Config config;
    /** Earliest moment the first throw may go (the module's Throw Delay, counted from the key press). */
    private long throwReadyAtMs;
    /** Earliest moment the slot may be restored after a throw (the module's Swap Back Delay). */
    private long restoreReadyAtMs;
    private Stage stage = Stage.IDLE;
    private ClientPlayerEntity player;
    private ClientWorld cycleWorld;
    private int windSlot = -1;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    private int pearlId = -1;
    /** Ticks between the client's copy of the pearl and the server handling a use sent now. */
    private int lead;
    private final int[] latencies = new int[LATENCY_WINDOW];
    private int latencyCount;
    private int elapsed;
    /** Tick of the last throw (0 before one); Max Wait counts from it. */
    private int waitBase;
    private int thrown;
    /** Server age of the pearl at which a thrown charge has missed if the pearl still flies. */
    private int missAge;
    /** The latest observation landed on the previous tick's trace. */
    private boolean mapped;
    /** Server age of the state the client copy shows; its own age until a sync re-anchors it. */
    private int stateAge;
    /** The copy's client age when last observed. */
    private int clientAge;
    private PearlSimulation.Path pearlPath;
    private WindChargeIntercept.Shot plan;
    private Vec3d motion = Vec3d.ZERO;
    private String failure;

    public PearlInterceptEngine(Object owner) {
        this.owner = owner;
    }

    public boolean isRunning() {
        return stage != Stage.IDLE;
    }

    public String failure() {
        return failure;
    }

    /** Picks the pearl and starts turning toward the first plan. Returns false when nothing can be intercepted. */
    public boolean start(Config config) {
        this.config = config;
        resetState();
        failure = null;
        throwReadyAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(config.throwDelayMs());
        if (!clientReady()) {
            failure = "Not ready (screen open or no world)";
            return false;
        }
        if (mc.player.isUsingItem()) {
            failure = "Busy using an item";
            return false;
        }
        int wind = findSlot(Items.WIND_CHARGE);
        if (wind < 0 || coolingDown(wind)) {
            failure = "Wind charge missing / cooling down";
            return false;
        }

        player = mc.player;
        cycleWorld = mc.world;
        windSlot = wind;
        originalSlot = ownedSlot = player.getInventory().getSelectedSlot();
        motion = ThrowPhysics.localShooterMovement(player);
        updateLead();

        List<EnderPearlEntity> candidates = foreignPearls();
        if (candidates.isEmpty()) {
            abort("No enemy pearl in range");
            return false;
        }
        float[] look = currentLook();
        EnderPearlEntity chosen = null;
        PearlSimulation.Path chosenPath = null;
        WindChargeIntercept.Shot chosenPlan = null;
        for (EnderPearlEntity pearl : candidates) {
            PearlSimulation.Path path = trace(pearl, pearl.age);
            WindChargeIntercept.Shot first = planAhead(path, pearl.age, look[0], look[1]);
            if (first == null) continue;
            // Candidates are nearest first, so the nearest one stays unless another clearly catches better.
            if (chosenPlan == null || first.interceptScore() > chosenPlan.interceptScore() + CANDIDATE_GAIN) {
                chosen = pearl;
                chosenPath = path;
                chosenPlan = first;
            }
        }
        if (chosen == null) {
            abort("No intercept for the pearls in range");
            return false;
        }
        pearlId = chosen.getId();
        stateAge = clientAge = chosen.age;
        pearlPath = chosenPath;
        plan = chosenPlan;
        stage = Stage.TRACK;
        stepLook();
        return stage != Stage.IDLE;
    }

    /** Advances the sequence by one tick. Returns true to continue, false when finished or stopped. */
    public boolean tick(Config config) {
        this.config = config;
        if (stage == Stage.IDLE) return false;
        if (mc.player == null || mc.world == null) {
            abort("No world");
            return false;
        }
        motion = ThrowPhysics.localShooterMovement(mc.player);
        elapsed++;

        try {
            switch (stage) {
                case TRACK -> track();
                case SENT -> sent();
                case DONE -> {
                    finish();
                    return false;
                }
                default -> {}
            }
            stepLook();
        } catch (RuntimeException error) {
            abort("Error: " + error.getClass().getSimpleName());
            return false;
        }
        return stage != Stage.IDLE;
    }

    public void stop() {
        abort("Stopped");
    }

    // ── Stages ──────────────────────────────────────────────────────────────

    private void track() {
        if (!cycleValid()) {
            abort("World, player or slot changed");
            return;
        }
        if (thrown > 0 && thrown >= config.maxThrows()) {
            // The keybind was let go (or the throws are used up): no further throws.
            stage = Stage.DONE;
            return;
        }
        EnderPearlEntity pearl = trackedPearl();
        if (pearl == null) {
            // After a throw the pearl vanishing is the charge taking it (or it landing at the same moment).
            if (thrown > 0) {
                stage = Stage.DONE;
            } else {
                abort("Pearl landed before the throw");
            }
            return;
        }
        if (elapsed - waitBase > config.maxWait()) {
            abort(thrown > 0 ? "Pearl got past the wind charge" : "No intercept in time");
            return;
        }
        updateLead();
        if (!player.getInventory().getStack(windSlot).isOf(Items.WIND_CHARGE)) {
            windSlot = findSlot(Items.WIND_CHARGE);
            if (windSlot < 0) {
                abort("No wind charge");
                return;
            }
        }
        int advanced = stateAge + Math.max(0, pearl.age - clientAge);
        int matched = matchTrace(pearl.getEntityPos(), advanced);
        mapped = matched != Integer.MIN_VALUE;
        stateAge = mapped ? matched : advanced;
        clientAge = pearl.age;
        pearlPath = trace(pearl, stateAge);
        // A thrown charge gets until its planned catch (plus slack) to take the pearl; the client
        // sees the pearl removed when its copy reaches that age.
        if (thrown > 0 && stateAge < missAge) return;
        float[] look = currentLook();
        WindChargeIntercept.Shot next = planAhead(pearlPath, stateAge, look[0], look[1]);
        if (elapsed >= config.trackTicks() && mapped && !teleported() && tryThrow(next)) return;
        if (next == null) {
            abort(thrown > 0 ? "Pearl got past the wind charge" : "No intercept ahead");
            return;
        }
        plan = next;
    }

    /** The tick after a throw: swap back, then watch whether the pearl is taken or throw again. */
    private void sent() {
        // Hold the swap back until the configured delay after the throw has passed.
        if (System.currentTimeMillis() < restoreReadyAtMs) return;
        restoreSlot();
        if (!cycleValid()) {
            abort("World, player or slot changed");
            return;
        }
        if (thrown >= config.maxThrows()) {
            stage = Stage.DONE;
            return;
        }
        waitBase = elapsed;
        missAge = plan.catchAge() + MISS_SLACK;
        stage = Stage.TRACK;
    }

    private boolean tryThrow(WindChargeIntercept.Shot later) {
        if (player.isUsingItem() || coolingDown(windSlot) || blockedByDrain()) return false;
        if (System.currentTimeMillis() < throwReadyAtMs) return false;
        int throwAge = stateAge + lead;
        WindChargeIntercept.Shooter shooter = currentShooter();
        WindChargeIntercept.Shot shot = reachableShot(throwAge, shooter);
        if (shot == null) return false;
        if (later != null && shot.interceptScore() < GOOD_SCORE
                && later.interceptScore() > shot.interceptScore() + WAIT_GAIN) return false;
        // The look turns at most the flick limit this tick; the throw only leaves once the look that
        // goes on the wire is the solved one, otherwise it waits for a later tick.
        if (!stepToward(shot.yaw(), shot.pitch(), flickLimit())) return false;
        float[] applied = currentLook();
        double tolerance = lookStep() + 1.0E-3;
        if (Math.abs(MathHelper.wrapDegrees(shot.yaw() - applied[0])) > tolerance
                || Math.abs(shot.pitch() - applied[1]) > tolerance) return false;
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluate(mc.world, player, pearlPath, throwAge, shooter,
                applied[0], applied[1], config.maxFlight());
        if (exact == null) return false;
        if (!selectSlot(windSlot)) return false;
        if (!useWindCharge()) return false;
        plan = exact;
        ++thrown;
        restoreReadyAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(config.swapBackDelayMs());
        stage = thrown >= config.maxThrows() ? Stage.DONE : Stage.SENT;
        return true;
    }

    private void finish() {
        restoreSlot();
        RotationManager.clearTarget(owner);
        resetState();
    }

    private void abort(String reason) {
        if (stage == Stage.IDLE && player == null) return;
        failure = reason;
        restoreSlot();
        RotationManager.clearTarget(owner);
        resetState();
    }

    private void resetState() {
        stage = Stage.IDLE;
        player = null;
        cycleWorld = null;
        windSlot = originalSlot = ownedSlot = -1;
        pearlId = -1;
        lead = 0;
        latencyCount = 0;
        elapsed = 0;
        waitBase = 0;
        thrown = 0;
        missAge = 0;
        mapped = false;
        stateAge = clientAge = 0;
        pearlPath = null;
        plan = null;
        motion = Vec3d.ZERO;
    }

    // ── Aim and look ────────────────────────────────────────────────────────

    private void stepLook() {
        if (stage == Stage.IDLE || player == null || plan == null) return;
        boolean held = switch (stage) {
            case TRACK -> thrown > 0 && stateAge < missAge
                    ? stepToward(plan.yaw(), plan.pitch(), SENT_LOOK_TOLERANCE)
                    : stepToward(plan.yaw(), plan.pitch(), smoothStep());
            case SENT, DONE -> stepToward(plan.yaw(), plan.pitch(), SENT_LOOK_TOLERANCE);
            default -> true;
        };
        if (!held) abort("Look held elsewhere");
    }

    private boolean stepToward(float yaw, float pitch, float maxStep) {
        return RotationManager.stepAim(owner, RotationManager.Priority.HIGHEST, yaw, pitch, maxStep, config.silent());
    }

    /** Flick speed capped to what one aim step can deliver under the active rotation profile. */
    private float flickLimit() {
        return Math.min(config.flickSpeed(), RotationManager.aimStepCeiling());
    }

    /** The turn left to the plan, spread over the ticks before its throw, at most the flick speed. */
    private float smoothStep() {
        float[] look = currentLook();
        float yaw = MathHelper.wrapDegrees(plan.yaw() - look[0]);
        float pitch = plan.pitch() - look[1];
        float remaining = (float) Math.sqrt(yaw * yaw + pitch * pitch);
        int ticksLeft = Math.max(1, plan.throwAge() - (stateAge + lead));
        return Math.min(flickLimit(), Math.max(0.1f, remaining / ticksLeft));
    }

    private float[] currentLook() {
        if (RotationManager.isSilentRotationActive()) {
            return new float[]{RotationManager.getFinalYaw(), RotationManager.getFinalPitch()};
        }
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    // ── Planning ────────────────────────────────────────────────────────────

    /**
     * Best throw on a later tick: from the first tick the tracking allows, the earliest tick with a
     * good shot, else the best one within the horizon. {@code age} is the pearl's age on the path.
     */
    private WindChargeIntercept.Shot planAhead(PearlSimulation.Path path, int age, float fromYaw, float fromPitch) {
        int first = Math.max(1, config.trackTicks() - elapsed);
        int last = Math.min(config.maxWait() - (elapsed - waitBase), first + PLAN_HORIZON);
        WindChargeIntercept.Shot best = null;
        for (int wait = first; wait <= last; ++wait) {
            WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(fromYaw, fromPitch,
                    wait * flickLimit() + SENT_LOOK_TOLERANCE);
            WindChargeIntercept.Shot shot = WindChargeIntercept.solve(mc.world, mc.player, path, age + lead + wait,
                    predictShooter(wait), config.maxFlight(), reach, false, WindChargeIntercept.Rank.INTERCEPT);
            if (shot == null) continue;
            if (best == null || shot.interceptScore() > best.interceptScore() + WAIT_GAIN) best = shot;
            if (best.interceptScore() >= GOOD_SCORE) break;
        }
        return best;
    }

    /** Best throw now among the looks one GCD-quantized step around the current look can send. */
    private WindChargeIntercept.Shot reachableShot(int throwAge, WindChargeIntercept.Shooter shooter) {
        float[] base = currentLook();
        float baseYaw = base[0];
        float basePitch = base[1];
        double step = lookStep();
        int span = (int) Math.floor(SENT_LOOK_TOLERANCE / step);
        int stride = Math.max(1, (span + 3) / 4);
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(baseYaw, basePitch);
        List<float[]> looks = new ArrayList<>();
        for (int i = -span; i <= span; i += stride) {
            for (int j = -span; j <= span; j += stride) {
                looks.add(latticeLook(baseYaw, basePitch, i, j, step));
            }
        }
        if (plan != null) {
            looks.add(latticeLook(baseYaw, basePitch,
                    latticeIndex(MathHelper.wrapDegrees(plan.yaw() - baseYaw), step, span),
                    latticeIndex(plan.pitch() - basePitch, step, span), step));
        }
        WindChargeIntercept.Shot best = WindChargeIntercept.best(mc.world, player, pearlPath, throwAge, shooter,
                config.maxFlight(), looks, reach, WindChargeIntercept.Rank.INTERCEPT);
        if (best == null || stride == 1) return best;
        int ci = latticeIndex(MathHelper.wrapDegrees(best.yaw() - baseYaw), step, span);
        int cj = latticeIndex(best.pitch() - basePitch, step, span);
        looks.clear();
        for (int i = Math.max(-span, ci - stride + 1); i <= Math.min(span, ci + stride - 1); ++i) {
            for (int j = Math.max(-span, cj - stride + 1); j <= Math.min(span, cj + stride - 1); ++j) {
                looks.add(latticeLook(baseYaw, basePitch, i, j, step));
            }
        }
        WindChargeIntercept.Shot fine = WindChargeIntercept.best(mc.world, player, pearlPath, throwAge, shooter,
                config.maxFlight(), looks, reach, WindChargeIntercept.Rank.INTERCEPT);
        return fine != null && fine.interceptScore() >= best.interceptScore() ? fine : best;
    }

    private static float[] latticeLook(float baseYaw, float basePitch, int i, int j, double step) {
        return new float[]{baseYaw + (float) (i * step), MathHelper.clamp(basePitch + (float) (j * step), -90.0f, 90.0f)};
    }

    private static int latticeIndex(float delta, double step, int span) {
        return MathHelper.clamp((int) Math.round(delta / step), -span, span);
    }

    private static double lookStep() {
        double gcd = MouseAimHelper.getGcdDouble();
        return gcd > 0.0 ? gcd : FREE_LOOK_STEP;
    }

    private WindChargeIntercept.Shooter currentShooter() {
        return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(player.getEntityPos(), eyeHeight()),
                motion, player.isOnGround());
    }

    private WindChargeIntercept.Shooter predictShooter(int ticks) {
        Vec3d feet = mc.player.getEntityPos();
        boolean onGround = mc.player.isOnGround();
        double y = feet.y;
        double dy = motion.y;
        if (!onGround) {
            double vy = mc.player.getVelocity().y;
            for (int i = 0; i < ticks; ++i) {
                y += vy;
                dy = vy;
                vy = (vy - PLAYER_GRAVITY) * PLAYER_VERTICAL_DRAG;
            }
        }
        Vec3d future = new Vec3d(feet.x + motion.x * ticks, y, feet.z + motion.z * ticks);
        return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(future, eyeHeight()),
                new Vec3d(motion.x, dy, motion.z), onGround);
    }

    private double eyeHeight() {
        return mc.player.getEyeY() - mc.player.getY();
    }

    /**
     * Ticks from the client copy's state to the server handling a use sent now: the server-reported
     * round trip, plus on average half a tick of phase between the two tick clocks.
     */
    private void updateLead() {
        int latency = 0;
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler != null && mc.player != null) {
            PlayerListEntry entry = handler.getPlayerListEntry(mc.player.getUuid());
            if (entry != null) latency = Math.max(0, entry.getLatency());
        }
        latencies[latencyCount % LATENCY_WINDOW] = latency;
        ++latencyCount;
        int samples = Math.min(latencyCount, LATENCY_WINDOW);
        long sum = 0;
        for (int i = 0; i < samples; ++i) sum += latencies[i];
        lead = Math.min(MAX_LEAD, Math.round((float) sum / samples / TICK_MS + 0.5f));
    }

    // ── The pearl ───────────────────────────────────────────────────────────

    /** Pearls in range thrown by someone other than the local player or a friend, nearest first. */
    private List<EnderPearlEntity> foreignPearls() {
        double maxDistanceSq = config.range() * config.range();
        List<EnderPearlEntity> pearls = new ArrayList<>();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EnderPearlEntity pearl && !pearl.isRemoved() && isForeign(pearl)
                    && player.squaredDistanceTo(pearl) <= maxDistanceSq) {
                pearls.add(pearl);
            }
        }
        pearls.sort(Comparator.comparingDouble(pearl -> player.squaredDistanceTo(pearl)));
        return pearls.size() > MAX_CANDIDATES ? pearls.subList(0, MAX_CANDIDATES) : pearls;
    }

    private boolean isForeign(EnderPearlEntity pearl) {
        Entity thrower = pearl.getOwner();
        if (thrower == null) return true;
        if (thrower == player || thrower.getUuid().equals(player.getUuid())) return false;
        if (!(thrower instanceof PlayerEntity)) return true;
        if (FriendManager.getInstance().isFriend(thrower.getUuid())) return false;
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(thrower)) return false;
        }
        return true;
    }

    private EnderPearlEntity trackedPearl() {
        Entity entity = mc.world.getEntityById(pearlId);
        return entity instanceof EnderPearlEntity pearl && !pearl.isRemoved() ? pearl : null;
    }

    /** The pearl's flight from its current client state, which is the server's state at {@code age}. */
    private PearlSimulation.Path trace(EnderPearlEntity pearl, int age) {
        Entity thrower = pearl.getOwner();
        int ticks = Math.min(PEARL_TRACE_TICKS, lead + config.maxWait() + config.maxFlight() + 2);
        return PearlSimulation.resume(mc.world, thrower, pearl, age, leftOwner(pearl, thrower)).trace(ticks);
    }

    /** ProjectileEntity#hasLeftOwner on the pearl's current state. */
    private static boolean leftOwner(EnderPearlEntity pearl, Entity thrower) {
        if (thrower == null) return true;
        Box box = PearlSimulation.box(pearl.getEntityPos()).stretch(pearl.getVelocity()).expand(1.0);
        return thrower.getRootVehicle().streamSelfAndPassengers().filter(EntityPredicates.CAN_HIT)
                .noneMatch(entity -> box.intersects(entity.getBoundingBox()));
    }

    /**
     * The age at which the previous trace puts the pearl where it is now: the expected one, or one
     * tick or two either side after a server sync set the copy back or ahead (closest fit wins).
     * MIN_VALUE when it left the trace.
     */
    private int matchTrace(Vec3d position, int expectedAge) {
        int best = Integer.MIN_VALUE;
        double bestError = TRACK_ERROR_SQ;
        for (int offset = 0; offset <= MATCH_WINDOW; ++offset) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (offset == 0 && sign < 0) continue;
                int age = expectedAge + sign * offset;
                Vec3d traced = pearlPath.positionAt(age);
                if (traced == null) continue;
                double error = traced.squaredDistanceTo(position);
                // The expected age wins ties; a different age needs a clearly closer fit.
                if (error < bestError - (best == Integer.MIN_VALUE ? 0.0 : 1.0E-6)) {
                    best = age;
                    bestError = error;
                }
            }
        }
        return best;
    }

    // ── Items and slots ─────────────────────────────────────────────────────

    private boolean selectSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        if (!TickActionOrder.canSwitchSlot()) return false;
        if (!SlotSync.select(mc, slot)) return false;
        ownedSlot = slot;
        return true;
    }

    private boolean useWindCharge() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!mc.player.getMainHandStack().isOf(Items.WIND_CHARGE)) return false;
        long now = System.currentTimeMillis();
        if (mc instanceof MinecraftClientAccessor accessor && accessor.getItemUseCooldown() > 0) {
            // A fresh press is not throttled by itemUseCooldown in vanilla (handleInputEvents' wasPressed
            // loop); only a held use key waits on it. Clear it only for a press a player could make
            // this soon after the previous one; otherwise wait for the vanilla throttle.
            if (now - lastPressAtMs < MIN_PRESS_GAP_MS) return false;
            accessor.setItemUseCooldown(0);
        }
        lastPressAtMs = now;
        ItemStack held = mc.player.getMainHandStack();
        int countBefore = held.getCount();
        // One use path: the shared input seam. A use it dropped is retried on a later tick.
        ClickSimController.useNow(mc, true);
        if (!useTookEffect(held, countBefore)) return false;
        TickActionOrder.recordUse();
        return true;
    }

    /** A real use shrinks the stack or starts the item's cooldown on the client at once. */
    private boolean useTookEffect(ItemStack held, int countBefore) {
        return held.isEmpty() || held.getCount() < countBefore
                || mc.player.getItemCooldownManager().isCoolingDown(held);
    }

    private void restoreSlot() {
        if (player == null || mc.player != player || originalSlot < 0
                || ownedSlot == originalSlot || mc.player.getInventory().getSelectedSlot() != ownedSlot) return;
        if (!TickActionOrder.canSwitchSlot()) {
            TickActionOrder.deferSlot(originalSlot, ownedSlot);
        } else {
            SlotSync.select(mc, originalSlot);
        }
        ownedSlot = originalSlot;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean clientReady() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && mc.player.isAlive() && !mc.player.isSpectator();
    }

    private boolean cycleValid() {
        return clientReady() && mc.world == cycleWorld && player == mc.player
                && slotOwned();
    }

    /** The selected slot is the one this sequence put there (after a throw, also the wind charge or the held slot it restores to). */
    private boolean slotOwned() {
        int selected = player.getInventory().getSelectedSlot();
        return selected == ownedSlot || (thrown > 0 && (selected == windSlot || selected == originalSlot));
    }

    private boolean teleported() {
        return motion.lengthSquared() > TELEPORT_MOTION_SQ;
    }

    private boolean coolingDown(int slot) {
        if (slot < 0 || mc.player == null) return true;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        return stack.isEmpty() || mc.player.getItemCooldownManager().isCoolingDown(stack);
    }

    private boolean blockedByDrain() {
        ShieldDrainEvent event = new ShieldDrainEvent();
        EventBus.post(event);
        return event.isActive();
    }

    private int findSlot(Item item) {
        if (mc.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) return slot;
        }
        return -1;
    }
}
