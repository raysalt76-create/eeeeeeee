package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.MouseAimHelper;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pearl Catch Engine: throws an ender pearl where the player looks and a wind charge into the pearl's
 * path, so the pearl runs into the charge mid-air and teleports the player there. It runs the sequence
 * (slots, look, timing, the first projectile's spawn packet); every decision about where and when to
 * throw comes from {@link PearlCatchPlanner}.
 *
 * <p>The pearl always flies along the look; the planner aims the wind charge. Which of the two leaves
 * first is physics: a charge flies a constant 1.5 blocks a tick, while a pearl slows going up or level
 * and speeds up going steeply down (until near its ~3 blocks a tick terminal speed).</p>
 * <ul>
 *   <li><b>Pearl first</b> (up, level, shallow down; and any pitch while falling fast, when the pearl
 *   leaves above terminal speed and slows): the charge follows and catches the slowing pearl.</li>
 *   <li><b>Charge first</b> (past a downward pitch learned per fall speed, {@link PearlCatchModel#chargeFirstPitch}:
 *   about 26 degrees standing, 6 rising from a jump, never falling 1.5 blocks a tick or faster): the
 *   charge leaves on a slightly steeper line and the pearl, thrown along the look a tick or more later,
 *   runs into it from behind.</li>
 * </ul>
 * <p>Both orders are planned at activation, every candidate rated by its expected catch rate (launch
 * spread and server timing slip, with the first projectile's spawn packet in or not), and the best runs;
 * the Catch Time picks between catches that rate alike. Each final look is chosen by two aim engines (the
 * physics solver and the region model) and the trained {@link PearlCatchEnsemble}. Two projectiles that
 * leave at the same speed a tick apart barely close on a shallow downward line, so a standing player's
 * looks around 20 degrees down have no catch in either order and are moved by the planner's nudge.</p>
 *
 * <p>Within 85% of vertical (|pitch| >= 76.5) the macros take over unless the solver, with the yaw held
 * near the camera's, does better. Up: pearl, gap, wind charge along the look. Down: the solved throw's
 * pitch turned (yaw held) and the other along the look; with no solved catch both along the look, charge
 * first, so the faster pearl runs into the charge from behind as well as the geometry allows.</p>
 *
 * <p><b>Both throws always go.</b> Any failure after the sequence starts (a look that never lines up,
 * a timeout, an error while planning) drops to {@link Stage#FALLBACK}, which throws whatever is still
 * missing along the look, one item a tick, rather than ending the sequence. A press while the pearl or
 * charge still cools down stays armed ({@link Stage#WAIT_READY}) and starts when they are ready; one
 * held back by Shield Drain starts anyway after {@link #DRAIN_WAIT} ticks. Every throw is a vanilla
 * {@code interactItem} for the main hand right after the slot select, never a simulated click, so
 * nothing under the crosshair or in the input rules can swallow it. The first throw that cannot line
 * up its look leaves along the current look after {@link #FIRST_TIMEOUT} ticks; the second leaves at
 * the best solved tick, or as soon as no better tick is ahead, the first projectile is gone, or the
 * latest gap is reached, aimed at the first projectile's flight as well as it can be.</p>
 *
 * <p>Physics are exact 1.21.11 ({@link PearlSimulation}, {@link WindChargeSimulation},
 * {@link ThrowPhysics}). The first projectile's spawn packet (sent inside a bundle) replaces its
 * prediction with the server's launch, spread included, and the second throw is aimed against that.
 * The shooter is forecast with player gravity and lands on the ground below. Each look is committed with
 * {@link RotationManager#stepAim} and evaluated exactly as the use packet will carry it, and a throw only
 * counts once the item's use cooldown shows it went through.</p>
 */
public final class PearlCatchEngine {
    private enum Stage {
        IDLE, WAIT_READY, AIM_FIRST, FLICK, MACRO_SECOND, FALLBACK, FINISH
    }

    private enum Mode {
        SOLVER, MACRO_UP, MACRO_DOWN
    }

    /** Which projectile leaves first. The pearl always flies along the look. */
    private enum Order {
        PEARL_FIRST, CHARGE_FIRST
    }

    /**
     * @param silent         aim with a server-side rotation and leave the visible camera alone
     * @param flickSpeed     most degrees the look turns a tick; the turn is spread over available ticks
     * @param minGap         fewest ticks between the first and the second throw
     * @param maxGap         latest tick after the first throw that the second may still leave
     * @param spawnWait      extra ticks to wait for the first projectile's spawn packet before throwing
     * @param maxCatch       the catch time: the flight from the second throw to the catch, ticks, the
     *                       sequence aims for. It picks the gap between the throws on every path (solved
     *                       catch, up macro, down macro): the longest flight within it that still catches
     * @param minConfidence  share of the launch spread that must still catch, 0..1 (0 = any valid catch)
     * @param swapBack       restore the original hotbar slot when the sequence ends
     */
    public record Config(boolean silent, float flickSpeed, int minGap, int maxGap, int spawnWait,
                         int maxCatch, float minConfidence, boolean swapBack,
                         double throwGapMs, double swapBackDelayMs) {
        public int maxGapTicks() {
            return Math.max(minGap, maxGap);
        }
    }

    /** A spawn packet's state for a projectile of the watched owner, read on the network thread. */
    private record Spawn(int id, EntityType<?> type, Vec3d position, Vec3d velocity) {}

    /** A charge-first plan: turn for {@code lead} ticks, throw the charge, then the pearl. */
    private record ChargeFirst(int lead, WindChargeIntercept.Shot shot) {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final int PEARL_TRACE_TICKS = PearlCatchPlanner.PEARL_TRACE_TICKS;
    /** Ticks the first throw may wait to line up (look, rotation channel, slot order). */
    private static final int FIRST_TIMEOUT = 6;
    /** Ticks a press waits for a cooling-down item (pearl 20, charge 10) before giving up. */
    private static final int READY_TIMEOUT = 45;
    /** Ticks a press waits for Shield Drain to let go of the hotbar before it starts anyway. */
    private static final int DRAIN_WAIT = 10;
    /** After {@link #FIRST_TIMEOUT} the first throw stops waiting for the look and leaves along the current one. */
    private static final int FIRST_FORCE_TICKS = 40;
    /** Ticks the plain fallback keeps retrying a refused throw before the sequence gives up. */
    private static final int FALLBACK_TIMEOUT = 40;
    /** Ticks past the longest gap during which the second throw keeps retrying before the sequence gives up. */
    private static final int SECOND_GRACE = 8;
    private static final float ALIGN_TOLERANCE = 0.35f;
    private static final double FREE_LOOK_STEP = 0.05;
    private static final int SPAWN_SEARCH = 4;
    private static final double SPAWN_MATCH_SQ = 1.0;
    private static final int SPAWN_QUEUE_LIMIT = 16;
    /** Ticks the main thread gets to apply a spawn packet before an unseen projectile counts as gone. */
    private static final int SPAWN_APPLY_TICKS = 4;
    private static final double TELEPORT_MOTION_SQ = 16.0;
    /** How far below the feet the ground is looked for when the shooter is in the air. */
    private static final double GROUND_SEARCH = 64.0;

    private final Object owner;
    private final ConcurrentLinkedQueue<Spawn> spawns = new ConcurrentLinkedQueue<>();
    private volatile int watchedOwner = Integer.MIN_VALUE;
    private Set<Integer> knownProjectiles = Set.of();

    private Config config;
    private Stage stage = Stage.IDLE;
    private Mode mode = Mode.SOLVER;
    private Order order = Order.PEARL_FIRST;
    private ClientPlayerEntity player;
    private ClientWorld cycleWorld;
    private int pearlSlot = -1;
    private int windSlot = -1;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    private long tickCount;
    /** The first throw gave up waiting for the exact look and leaves along the current one. */
    private boolean forcedFirst;
    private long stageTick = Long.MIN_VALUE;
    private long firstTick = Long.MIN_VALUE;
    /** The pearl's look: the camera look at activation. */
    private float lookYaw;
    private float lookPitch;
    /** Macro looks for the first and the second throw. */
    private float firstYaw;
    private float firstPitch;
    private float secondYaw;
    private float secondPitch;
    /** Fewest ticks between the two throws of this press: the vanilla minimum plus the Throw Gap, sampled once. */
    private int gapFloor = 1;
    /** The vanilla minimum alone: the floor the plan falls back to when the Throw Gap would cost the catch. */
    private int baseGapFloor = 1;
    /** FINISH: the look and rotation lock were handed back (the tick after the last throw). */
    private boolean lookReleased;
    /** Macro: ticks between the two throws, chosen from the Catch Time at activation (0 = the floor). */
    private int macroGap;
    private long finishAtMs;
    /** Which throws have gone, so a fallback finishes the sequence instead of repeating a throw. */
    private boolean pearlThrown;
    private boolean chargeThrown;
    private long lastThrowTick = Long.MIN_VALUE;
    private boolean fallbackPearlFirst = true;
    /** Pearl first: the pearl in flight. */
    private PearlSimulation.Path pearlPath;
    /** Charge first: the charge in flight. */
    private WindChargeIntercept.Charge charge;
    /** Charge first: the planned ticks from the charge to the pearl. */
    private int gapTarget;
    private boolean refined;
    private int projectileId = -1;
    private long refinedTick = Long.MIN_VALUE;
    private boolean projectileSeen;
    private WindChargeIntercept.Shot plan;
    /** Plans made at activation, reused by the first throw when it leaves in that same tick. */
    private ChargeFirst plannedCharge;
    private PearlSimulation.Path plannedPearlPath;
    private WindChargeIntercept.Shot plannedPearl;
    private long plannedTick = Long.MIN_VALUE;
    /** Why the first throw is still waiting, for the message when it times out. */
    private String waitReason;
    private Vec3d motion = Vec3d.ZERO;
    private String outcome = "Idle";
    private String failure;
    /** Why the last press had no solved catch, when that is worth telling the player (kept past the reset). */
    private String notice;
    /** The ground below the shooter, looked up once per tick for the planner's landing forecast. */
    private double groundY = Double.NaN;
    private long groundTick = Long.MIN_VALUE;

    public PearlCatchEngine(Object owner) {
        this.owner = owner;
    }

    public boolean isRunning() {
        return stage != Stage.IDLE;
    }

    /** Both throws are out and only the swap-back delay is left. */
    public boolean isFinishing() {
        return stage == Stage.FINISH;
    }

    public String failure() {
        return failure;
    }

    /** Set when the press threw without a solved catch for a reason the player can act on (too little air below). */
    public String notice() {
        return notice;
    }

    public String diagnosticState() {
        return stage + " mode=" + mode + " order=" + order + " refined=" + refined
                + " plan=" + (plan == null ? "none" : plan.yaw() + "/" + plan.pitch() + " gap " + plan.throwAge()
                + " catch@" + plan.catchAge() + " score " + plan.score())
                + " tick=" + tickCount + " first=" + firstTick + " outcome=" + outcome;
    }

    /**
     * Arms a catch sequence. Returns true when armed (it starts at once when nothing holds it back, or
     * waits up to {@link #READY_TIMEOUT} ticks for a cooling-down item), false when it can never run:
     * no pearl or wind charge in the hotbar, a screen open, no world.
     */
    public boolean start(Config config) {
        this.config = config;
        resetState();
        failure = null;
        notice = null;
        if (mc.player != null) motion = ThrowPhysics.localShooterMovement(mc.player);
        if (!clientReady()) {
            failure = "Not ready (screen open or no world)";
            return false;
        }
        if (findSlot(Items.ENDER_PEARL) < 0 || findSlot(Items.WIND_CHARGE) < 0) {
            failure = "Pearl or wind charge missing";
            return false;
        }
        player = mc.player;
        cycleWorld = mc.world;
        outcome = "Waiting";
        stage = Stage.WAIT_READY;
        stageTick = tickCount;
        waitReady();
        return stage != Stage.IDLE;
    }

    /**
     * A press while the last catch's items still cool down (or Shield Drain runs) is kept armed and
     * served the moment it allows, instead of being refused: nothing is owned while waiting.
     */
    private void waitReady() {
        if (!clientReady() || mc.world != cycleWorld || player != mc.player) {
            abort("World or player changed");
            return;
        }
        if (findSlot(Items.ENDER_PEARL) < 0 || findSlot(Items.WIND_CHARGE) < 0) {
            abort("Pearl or wind charge missing");
            return;
        }
        long waited = tickCount - stageTick;
        if (coolingDown(findSlot(Items.ENDER_PEARL)) || coolingDown(findSlot(Items.WIND_CHARGE))) {
            waitReason = "Ender Pearl or Wind Charge cooling down";
            if (waited > READY_TIMEOUT) abort("Not ready: " + waitReason);
            return;
        }
        // Shield Drain swaps the hotbar while it runs, so a press waits for it, but only briefly: the
        // press must throw, and every throw selects its own slot right before the use.
        if (waited <= DRAIN_WAIT && blockedByDrain()) {
            waitReason = "Shield Drain is running";
            return;
        }
        begin();
    }

    /** Plans from the live look and starts the first throw. */
    private void begin() {
        // This sequence owns the hotbar now: a restore queued by an earlier one must not fire into it.
        TickActionOrder.cancelDeferredSlot();
        pearlSlot = findSlot(Items.ENDER_PEARL);
        windSlot = findSlot(Items.WIND_CHARGE);
        originalSlot = ownedSlot = player.getInventory().getSelectedSlot();
        lookYaw = cameraYaw();
        lookPitch = cameraPitch();
        // One delay for the upward macro, the down macro and the solved horizontal catch: the second
        // throw never leaves sooner than this many ticks after the first. The module's own Throw Gap
        // slider sets it the same way on every path (a sub-tick remainder is honoured by probabilistic rounding).
        baseGapFloor = Math.max(1, config.minGap());
        gapFloor = baseGapFloor + ModuleDelays.msToTicks(config.throwGapMs());
        outcome = "Running";
        stage = Stage.AIM_FIRST;
        stageTick = tickCount;
        waitReason = null;
        // Take the rotation channel before the first throw so another aim cannot contest its look.
        RotationManager.acquireExclusiveLock(owner);

        try {
            plan();
            aimFirst();
        } catch (RuntimeException error) {
            // Planning is a refinement; the throws are the point. Throw both along the look instead.
            degrade("Plan error: " + error.getClass().getSimpleName());
        }
    }

    /** Chooses the mode, order and plan for the live look ({@link PearlCatchPlanner#plan}). */
    private void plan() {
        long planStart = System.nanoTime();
        PearlCatchPlanner planner = planner();
        PearlCatchPlanner.Plan planned = planner.plan(lookYaw, lookPitch);
        PearlTiming.report("PearlCatch", "plan", planStart);
        if (gapFloor > baseGapFloor) {
            // The Throw Gap raises the earliest second throw. The plan already respects it, but it must
            // never be what costs the catch: when the vanilla floor catches clearly better, it is used.
            int raisedFloor = gapFloor;
            gapFloor = baseGapFloor;
            PearlCatchPlanner basePlanner = planner();
            PearlCatchPlanner.Plan base = basePlanner.plan(lookYaw, lookPitch);
            boolean keepRaised = !base.solved()
                    || (planned.solved() && planned.rate() + PearlCatchModel.RATE_TIE >= base.rate());
            if (keepRaised) {
                gapFloor = raisedFloor;
            } else {
                planned = base;
                planner = basePlanner;
            }
        }
        if (!planned.solved() && lookPitch > 0.0f) {
            // Looking down, a catch comes only once the pearl has overtaken the charge, a dozen ticks or
            // more below: from a floor or a low ledge the pearl lands first. Say so instead of a silent miss.
            double air = planner.airNeeded(lookYaw, lookPitch);
            if (Double.isFinite(air)) {
                notice = String.format("No catch before the ground: looking %.0f degrees down needs about %.0f blocks of air below you. Both items were thrown.",
                        lookPitch, Math.ceil(air));
            }
        }
        float original = lookPitch;
        // A look with no catch has its pitch moved to the nearest that has one: the pearl takes it.
        lookPitch = planned.pearlPitch();
        if (Math.abs(lookPitch - original) > 1.0e-3f) {
            outcome = "Running (pitch moved " + String.format("%.0f", Math.abs(lookPitch - original)) + " deg)";
        }
        mode = switch (planned.mode()) {
            case SOLVER -> Mode.SOLVER;
            case MACRO_UP -> Mode.MACRO_UP;
            case MACRO_DOWN -> Mode.MACRO_DOWN;
        };
        order = planned.chargeFirst() ? Order.CHARGE_FIRST : Order.PEARL_FIRST;
        if (mode == Mode.SOLVER && planned.solved()) {
            plan = planned.shot();
            plannedTick = tickCount;
            if (planned.chargeFirst()) {
                plannedCharge = new ChargeFirst(planned.lead(), planned.shot());
            } else {
                plannedPearlPath = planned.pearlPath();
                plannedPearl = planned.shot();
            }
            return;
        }
        plan = planned.solved() ? planned.shot() : null;
        macroGap = planned.gap();
        if (mode == Mode.SOLVER) {
            if (!planned.chargeFirst()) return;
            // No solved catch past the order threshold: both along the look, charge first, as the
            // down macro does; the pearl is re-aimed against the charge in flight.
            mode = Mode.MACRO_DOWN;
        }
        firstYaw = planned.firstYaw();
        firstPitch = planned.firstPitch();
        secondYaw = planned.secondYaw();
        secondPitch = planned.secondPitch();
    }

    /**
     * The planner for this tick: the shooter now, the look the next use packet carries, the mouse lattice
     * and the limits of the running press.
     */
    private PearlCatchPlanner planner() {
        float[] sent = packetLook();
        return new PearlCatchPlanner(new PearlCatchPlanner.Context(mc.world, player, mc.player.getEntityPos(), motion,
                mc.player.getVelocity().y, mc.player.isOnGround(), groundBelow(), eyeHeight(), sent[0], sent[1],
                flickLimit(), MouseAimHelper.getGcdDouble(), config.maxCatch(), minGapTicks(), config.maxGapTicks()));
    }

    /**
     * The top of the ground under the shooter's box (the highest of five rays straight down), NaN when the
     * shooter is on the ground already or none is within {@link #GROUND_SEARCH}. Once per tick.
     */
    private double groundBelow() {
        if (groundTick == tickCount) return groundY;
        groundTick = tickCount;
        groundY = Double.NaN;
        if (mc.player.isOnGround()) return groundY;
        Box box = mc.player.getBoundingBox();
        double inset = 1.0e-3;
        double[][] columns = {{(box.minX + box.maxX) * 0.5, (box.minZ + box.maxZ) * 0.5},
                {box.minX + inset, box.minZ + inset}, {box.minX + inset, box.maxZ - inset},
                {box.maxX - inset, box.minZ + inset}, {box.maxX - inset, box.maxZ - inset}};
        for (double[] column : columns) {
            Vec3d from = new Vec3d(column[0], box.minY, column[1]);
            BlockHitResult hit = mc.world.raycast(new RaycastContext(from, from.add(0.0, -GROUND_SEARCH, 0.0),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) continue;
            groundY = Double.isNaN(groundY) ? hit.getPos().y : Math.max(groundY, hit.getPos().y);
        }
        return groundY;
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
        tickCount++;

        try {
            switch (stage) {
                case WAIT_READY -> waitReady();
                case AIM_FIRST -> aimFirst();
                case FLICK -> {
                    if (order == Order.PEARL_FIRST) flickCharge();
                    else flickPearl();
                }
                case MACRO_SECOND -> macroSecond();
                case FALLBACK -> fallbackStep();
                case FINISH -> {
                    if (!lookReleased) {
                        // Both throws left last tick (their tick kept the look): hand the look and the
                        // lock back now, so only the hotbar restore waits out the swap-back delay.
                        lookReleased = true;
                        RotationManager.clearTarget(owner);
                        RotationManager.releaseExclusiveLock(owner);
                    }
                    if (System.currentTimeMillis() >= finishAtMs) {
                        finish();
                        return false;
                    }
                }
                default -> {}
            }
            holdLook();
        } catch (RuntimeException error) {
            if (stage == Stage.FALLBACK || stage == Stage.WAIT_READY || stage == Stage.FINISH) {
                abort("Error: " + error.getClass().getSimpleName());
                return false;
            }
            degrade("Error: " + error.getClass().getSimpleName());
        }
        return stage != Stage.IDLE;
    }

    /**
     * Network thread. Entity spawns arrive inside a {@link BundlePacket}, so bundles are opened; only
     * the immutable spawn values of this sequence's owner are handed to the client thread.
     */
    public void receive(Packet<?> packet) {
        int watched = watchedOwner;
        if (watched == Integer.MIN_VALUE) return;
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> inner : bundle.getPackets()) offer(inner, watched);
        } else {
            offer(packet, watched);
        }
    }

    public void stop() {
        abort("Stopped");
    }

    private void offer(Packet<?> packet, int watched) {
        if (!(packet instanceof EntitySpawnS2CPacket spawn) || spawn.getEntityData() != watched) return;
        EntityType<?> type = spawn.getEntityType();
        if (type != EntityType.ENDER_PEARL && type != EntityType.WIND_CHARGE) return;
        if (spawns.size() >= SPAWN_QUEUE_LIMIT) return;
        spawns.add(new Spawn(spawn.getEntityId(), type, new Vec3d(spawn.getX(), spawn.getY(), spawn.getZ()),
                spawn.getVelocity()));
    }

    // ── Stages ──────────────────────────────────────────────────────────────

    private void aimFirst() {
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        long waited = tickCount - stageTick;
        if (waited > FIRST_TIMEOUT + FIRST_FORCE_TICKS) {
            abort("First throw never went: " + (waitReason == null ? "look not lined up" : waitReason));
            return;
        }
        if (waited > FIRST_TIMEOUT && !forcedFirst) {
            // The exact look would not come together in time: the pearl leaves along the look it has,
            // and the charge is aimed at it live. A throw that goes beats a perfect one that never does.
            forcedFirst = true;
            plannedTick = Long.MIN_VALUE;
            if (mode == Mode.SOLVER) {
                order = Order.PEARL_FIRST;
                plan = null;
            }
        }
        if (mode != Mode.SOLVER) {
            macroFirst();
        } else if (order == Order.PEARL_FIRST) {
            firstPearl();
        } else {
            firstCharge();
        }
    }

    /** Pearl first: line the look up, re-plan from the exact look, throw the pearl. */
    private void firstPearl() {
        if (!forcedFirst && !lineUp(lookYaw, lookPitch)) return;
        float[] look = packetLook();
        boolean planned = !forcedFirst && plannedTick == tickCount && look[0] == lookYaw && look[1] == lookPitch;
        PearlSimulation.Path path = planned ? plannedPearlPath
                : PearlSimulation.thrown(mc.world, player, look[0], look[1], motion, null).trace(PEARL_TRACE_TICKS);
        WindChargeIntercept.Shot first = planned ? plannedPearl : shotOf(planner().nextChargeTick(path, 0, false));
        watchSpawns(EnderPearlEntity.class);
        if (!throwItem(pearlSlot, Items.ENDER_PEARL)) return;
        pearlPath = path;
        plan = first;
        enterFlick();
    }

    /**
     * Charge first: re-plan from the live state, turn toward the charge look the planner chose, and throw
     * it once the exact look the use packet carries still lets a pearl along the look run into it (else
     * the best look on the lattice within the sent-look tolerance that does).
     */
    private void firstCharge() {
        PearlCatchPlanner planner = planner();
        ChargeFirst target = plannedTick == tickCount ? plannedCharge : replanChargeFirst(planner);
        if (target == null) {
            fallBackToPearlFirst();
            return;
        }
        WindChargeIntercept.Shot aim = target.shot();
        if (target.lead() > 0) {
            stepToward(aim.yaw(), aim.pitch(), flickLimit());
            return;
        }
        if (!readyToThrow(windSlot)) return;
        int gap = aim.throwAge();
        WindChargeIntercept.Shooter thrower = currentShooter(planner);
        PearlSimulation.Path pearl = planner.pearlAlongLook(lookYaw, lookPitch, planner.shooterIn(gap), mc.world, null);
        if (!stepToward(aim.yaw(), aim.pitch(), flickLimit())) return;
        float[] applied = packetLook();
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluateOlder(mc.world, player, pearl, gap, thrower,
                applied[0], applied[1], planner.catchLimit());
        if (exact == null || !planner.confident(exact, true)) {
            // The look the step landed on misses: the best lattice look within the sent tolerance.
            PearlCatchPlanner live = planner();
            WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(applied[0], applied[1]);
            WindChargeIntercept.Shot shot = live.latticeSearch(new float[]{aim.yaw(), aim.pitch()},
                    looks -> WindChargeIntercept.bestOlder(mc.world, player, pearl, gap, thrower, live.catchLimit(), looks, reach));
            if (shot == null || !live.confident(shot, true)) {
                fallBackToPearlFirst();
                return;
            }
            if (!stepToward(shot.yaw(), shot.pitch(), 180.0f)) return;
            applied = packetLook();
            exact = WindChargeIntercept.evaluateOlder(mc.world, player, pearl, gap, thrower, applied[0], applied[1],
                    live.catchLimit());
            if (exact == null || !live.confident(exact, true)) {
                fallBackToPearlFirst();
                return;
            }
        }
        watchSpawns(WindChargeEntity.class);
        if (!throwItem(windSlot, Items.WIND_CHARGE)) return;
        charge = WindChargeIntercept.charge(mc.world, player, thrower, applied[0], applied[1], planner.chargeHorizon());
        gapTarget = gap;
        plan = exact;
        enterFlick();
    }

    /** Charge first, re-planned from this tick's state: lead, gap and the charge's look, or null. */
    private ChargeFirst replanChargeFirst(PearlCatchPlanner planner) {
        PearlCatchPlanner.Plan replanned = planner.planSolver(lookYaw, lookPitch, true);
        return replanned == null ? null : new ChargeFirst(replanned.lead(), replanned.shot());
    }

    /** No charge-first catch exists: nothing was thrown yet, so the pearl leaves first along the look instead. */
    private void fallBackToPearlFirst() {
        order = Order.PEARL_FIRST;
        plan = null;
        plannedTick = Long.MIN_VALUE;
        firstPearl();
    }

    /** Macro: the first throw along its look as soon as the look is there. */
    private void macroFirst() {
        if (!forcedFirst && !lineUp(firstYaw, firstPitch)) return;
        float[] look = packetLook();
        WindChargeIntercept.Shooter thrower = currentShooter(planner());
        PearlSimulation.Path path = mode == Mode.MACRO_DOWN && order == Order.PEARL_FIRST
                ? PearlSimulation.thrown(mc.world, player, look[0], look[1], motion, null).trace(PEARL_TRACE_TICKS)
                : null;
        if (order == Order.PEARL_FIRST) {
            watchSpawns(EnderPearlEntity.class);
            if (!throwItem(pearlSlot, Items.ENDER_PEARL)) return;
            pearlPath = path;
        } else {
            watchSpawns(WindChargeEntity.class);
            if (!throwItem(windSlot, Items.WIND_CHARGE)) return;
            charge = WindChargeIntercept.charge(mc.world, player, thrower, look[0], look[1], planner().chargeHorizon());
        }
        firstTick = tickCount;
        stage = Stage.MACRO_SECOND;
        stageTick = tickCount;
    }

    /** Macro: the second throw once the gap and the timing delay have passed. */
    private void macroSecond() {
        int rel = (int) (tickCount - firstTick);
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        if (rel > hardLimit()) {
            degrade("Macro timeout");
            return;
        }
        if (rel < macroGapTicks()) return;
        boolean pearlNext = order == Order.CHARGE_FIRST;
        int slot = pearlNext ? pearlSlot : windSlot;
        Item item = pearlNext ? Items.ENDER_PEARL : Items.WIND_CHARGE;
        if (!slotHolds(slot, item)) {
            slot = findSlot(item);
            if (slot < 0) {
                abort(pearlNext ? "No ender pearl" : "No wind charge");
                return;
            }
            if (pearlNext) pearlSlot = slot; else windSlot = slot;
        }
        if (mode == Mode.MACRO_DOWN) resolveMacroSecond(rel);
        // Past the latest gap the throw leaves along whatever look is held: it must go.
        if (!lineUp(secondYaw, secondPitch) && rel < config.maxGapTicks()) return;
        if (!throwItem(slot, item)) return;
        enterFinish();
    }

    /**
     * Down macro: re-aims the second throw's pitch (yaw held) against the first projectile as the
     * server launched it, when its spawn packet is in (else its forecast, both spreads rated), and the
     * shooter as it is now. Keeps the planned look when nothing better is found.
     */
    private void resolveMacroSecond(int rel) {
        refine();
        PearlCatchPlanner planner = planner();
        PearlCatchPlanner.Aim aim;
        if (order == Order.CHARGE_FIRST) {
            if (charge == null) return;
            aim = planner.aimPearl(charge, rel, lookYaw, lookPitch, PearlCatchPlanner.SENT_LOOK_TOLERANCE, refined,
                    chargeEntity());
        } else {
            if (pearlPath == null) return;
            aim = planner.aimChargePitch(pearlPath, rel, currentShooter(planner), lookYaw, secondPitch,
                    PearlCatchPlanner.MACRO_PITCH_WINDOW, refined);
        }
        if (aim == null) return;
        secondYaw = aim.shot().yaw();
        secondPitch = aim.shot().pitch();
        plan = aim.shot();
    }

    /**
     * Pearl first, between the throws: aim the charge and throw it at the best tick. The charge always
     * goes: with a solved catch at the best tick, otherwise as soon as no better tick is ahead, the
     * pearl is gone, or the latest gap is reached, aimed at the pearl's flight as well as it can be.
     */
    private void flickCharge() {
        int rel = (int) (tickCount - firstTick);
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        if (rel > hardLimit()) {
            degrade("Wind charge never went");
            return;
        }
        if (!slotHolds(windSlot, Items.WIND_CHARGE)) {
            windSlot = findSlot(Items.WIND_CHARGE);
            if (windSlot < 0) {
                abort("No wind charge");
                return;
            }
        }
        refine();
        boolean gone = pearlPath == null || projectileGone();
        PearlCatchPlanner planner = planner();
        PearlCatchPlanner.Option next = gone ? null : planner.nextChargeTick(pearlPath, rel, refined);
        // Waiting for the pearl's spawn packet only pays while it can still arrive inside the wait window:
        // at a ping that brings it later, waiting only delays the charge, so it is not waited for at all.
        int spawnTick = expectedSpawnTick();
        int waitEnd = spawnTick <= minGapTicks() + config.spawnWait() ? Math.max(minGapTicks(), spawnTick) : minGapTicks();
        boolean waitForPearl = !refined && next != null && rel < waitEnd;
        if (!gone && rel >= minGapTicks() && !waitForPearl && !teleported() && tryThrowCharge(planner, rel, next)) return;
        boolean noCatchAhead = next == null && (gone || refined || rel >= waitEnd);
        if (rel >= minGapTicks() && (gone || noCatchAhead || rel >= config.maxGapTicks())
                && throwChargeAtPearl(rel)) return;
        if (next != null) plan = next.shot();
    }

    /**
     * Best-effort charge: aimed straight at where the pearl will be when the charge can reach it, or
     * at the last of its flight, or along the look when nothing of the flight is known.
     */
    private boolean throwChargeAtPearl(int rel) {
        if (!readyToThrow(windSlot)) return false;
        float[] aim = chargeAim(rel);
        stepToward(aim[0], aim[1], 180.0f);
        if (!throwItem(windSlot, Items.WIND_CHARGE)) return false;
        enterFinish();
        return true;
    }

    private float[] chargeAim(int rel) {
        if (pearlPath != null) {
            PearlCatchPlanner planner = planner();
            Vec3d from = currentShooter(planner).spawn();
            Vec3d fallback = null;
            for (int k = 1; k <= planner.catchLimit(); ++k) {
                Vec3d target = pearlPath.positionAt(rel + k);
                if (target == null) break;
                if (fallback == null) fallback = target;
                Vec3d d = target.subtract(from);
                if (d.length() <= 1.5 * k) return lookToward(d, lookYaw);
            }
            if (fallback == null) fallback = pearlPath.positionAt(Math.min(rel + 1, pearlPath.lastAge()));
            if (fallback != null) return lookToward(fallback.subtract(from), lookYaw);
        }
        return new float[]{lookYaw, lookPitch};
    }

    private static float[] lookToward(Vec3d d, float yawHint) {
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        if (d.lengthSquared() < 1.0E-8) return new float[]{yawHint, 0.0f};
        float yaw = horizontal < 1.0E-6 ? yawHint : (float) (MathHelper.atan2(d.z, d.x) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(d.y, horizontal) * 180.0 / Math.PI);
        float budget = PearlCatchPlanner.yawBudget(pitch);
        float swing = MathHelper.wrapDegrees(yaw - yawHint);
        if (Math.abs(swing) > budget) {
            // Near vertical a far-off yaw is a few degrees of tilt: keep the yaw near the camera's and
            // let the pitch carry what it can, rather than spinning the view round.
            float[] kept = ThrowPhysics.closestLookWithYaw(d, yawHint + Math.copySign(budget, swing));
            return new float[]{kept[0], MathHelper.clamp(kept[1], -90.0f, 90.0f)};
        }
        return new float[]{yaw, MathHelper.clamp(pitch, -90.0f, 90.0f)};
    }

    /**
     * Pearl first: the charge thrown now, aimed by both engines and the ensemble against the pearl (its
     * spawn packet's flight when in), unless a later tick ({@code later}) is the better catch.
     */
    private boolean tryThrowCharge(PearlCatchPlanner planner, int rel, PearlCatchPlanner.Option later) {
        if (!readyToThrow(windSlot)) return false;
        WindChargeIntercept.Shooter shooter = currentShooter(planner);
        PearlCatchPlanner.Aim aim = planner.aimCharge(pearlPath, rel, shooter,
                plan == null ? null : new float[]{plan.yaw(), plan.pitch()}, refined);
        if (aim == null) return false;
        // A later tick that rates better (or suits the Catch Time better at a like rate) is worth the wait.
        if (later != null && rel < config.maxGapTicks()
                && planner.prefers(later, 0.0, new PearlCatchPlanner.Option(false, 0, aim.shot(), aim.rate()), 0.0)) return false;
        if (!stepToward(aim.shot().yaw(), aim.shot().pitch(), 180.0f)) return false;
        float[] applied = packetLook();
        WindChargeIntercept.Shot exact = WindChargeIntercept.evaluate(mc.world, player, pearlPath, rel, shooter,
                applied[0], applied[1], planner.catchLimit());
        if (exact == null || !planner.confident(exact, false)) return false;
        if (!throwItem(windSlot, Items.WIND_CHARGE)) return false;
        plan = exact;
        enterFinish();
        return true;
    }

    /**
     * Charge first, between the throws: turn back to the look and throw the pearl into the charge. The
     * pearl always goes: with a solved catch at the best tick, otherwise as soon as no better tick is
     * ahead, the charge is gone (it burst on a block), or the latest gap is reached, along the look.
     */
    private void flickPearl() {
        int rel = (int) (tickCount - firstTick);
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        if (rel > hardLimit()) {
            degrade("Pearl never went");
            return;
        }
        if (!slotHolds(pearlSlot, Items.ENDER_PEARL)) {
            pearlSlot = findSlot(Items.ENDER_PEARL);
            if (pearlSlot < 0) {
                abort("No ender pearl");
                return;
            }
        }
        refine();
        boolean gone = charge == null || projectileGone();
        PearlCatchPlanner planner = planner();
        PearlCatchPlanner.Option next = gone ? null : planner.nextPearlTick(charge, rel, lookYaw, lookPitch, refined);
        // No wait for the charge's spawn packet here: it only refines the pearl's look by a degree or so,
        // while every tick of waiting puts the charge 1.5 blocks further ahead of a pearl that gains on it
        // only slowly, which pushes the catch tens of blocks lower or past the ground.
        if (!gone && rel >= minGapTicks() && !teleported() && tryThrowPearl(planner, rel, next)) return;
        boolean noCatchAhead = next == null;
        if (rel >= minGapTicks() && (gone || noCatchAhead || rel >= config.maxGapTicks())
                && throwPearlAlongLook()) return;
        if (next != null) gapTarget = next.shot().throwAge();
    }

    /** Best-effort pearl: along the look the player had, whatever the charge did. */
    private boolean throwPearlAlongLook() {
        if (!readyToThrow(pearlSlot)) return false;
        stepToward(lookYaw, lookPitch, 180.0f);
        if (!throwItem(pearlSlot, Items.ENDER_PEARL)) return false;
        enterFinish();
        return true;
    }

    /**
     * Charge first: the pearl thrown now, aimed by both engines and the ensemble within the sent-look
     * tolerance of the look, unless a later tick ({@code later}) is the better catch.
     */
    private boolean tryThrowPearl(PearlCatchPlanner planner, int rel, PearlCatchPlanner.Option later) {
        if (!readyToThrow(pearlSlot)) return false;
        PearlCatchPlanner.Aim aim = planner.aimPearl(charge, rel, lookYaw, lookPitch, null, refined, chargeEntity());
        if (aim == null) return false;
        if (later != null && rel < config.maxGapTicks()
                && planner.prefers(later, 0.0, new PearlCatchPlanner.Option(true, 0, aim.shot(), aim.rate()), 0.0)) return false;
        if (!stepToward(aim.shot().yaw(), aim.shot().pitch(), 180.0f)) return false;
        float[] applied = packetLook();
        WindChargeIntercept.Shot exact = planner.pearlShot(applied[0], applied[1], rel, charge, chargeEntity());
        if (exact == null || !planner.confident(exact, true)) return false;
        if (!throwItem(pearlSlot, Items.ENDER_PEARL)) return false;
        plan = exact;
        enterFinish();
        return true;
    }

    /**
     * The planned sequence cannot go on (a look that never lined up, a timeout, an error): whatever
     * has not been thrown yet is thrown along the look, one item a tick, so a press always throws.
     */
    private void degrade(String reason) {
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        waitReason = reason;
        outcome = "Fallback (" + reason + ")";
        // Past the learned order threshold the pearl outruns the charge: the charge goes first.
        double fall = mc.player.isOnGround() ? 0.0 : motion.y;
        fallbackPearlFirst = lookPitch < PearlCatchModel.chargeFirstPitch(fall);
        stage = Stage.FALLBACK;
        stageTick = tickCount;
    }

    /** Throws the item still missing, along the look; the charge after a pearl is aimed at its flight. */
    private void fallbackStep() {
        if (!sequenceValid()) {
            abort("World or player changed");
            return;
        }
        if (pearlThrown && chargeThrown) {
            enterFinish();
            return;
        }
        if (tickCount - stageTick > FALLBACK_TIMEOUT) {
            abort("Throw refused: " + (waitReason == null ? "unknown" : waitReason));
            return;
        }
        // A slot change cannot follow a use inside one movement interval: the second item waits a tick.
        if ((pearlThrown || chargeThrown) && lastThrowTick >= tickCount) return;
        boolean pearlNext = pearlThrown ? false : chargeThrown ? true : fallbackPearlFirst;
        Item item = pearlNext ? Items.ENDER_PEARL : Items.WIND_CHARGE;
        int slot = pearlNext ? pearlSlot : windSlot;
        if (!slotHolds(slot, item)) {
            slot = findSlot(item);
            if (slot < 0) {
                abort(pearlNext ? "No ender pearl" : "No wind charge");
                return;
            }
            if (pearlNext) pearlSlot = slot; else windSlot = slot;
        }
        float[] aim = !pearlNext && pearlThrown && lastThrowTick != Long.MIN_VALUE
                ? chargeAim((int) (tickCount - lastThrowTick))
                : new float[]{lookYaw, lookPitch};
        stepToward(aim[0], aim[1], 180.0f);
        throwItem(slot, item);
        if (pearlThrown && chargeThrown) enterFinish();
    }

    private void enterFlick() {
        firstTick = tickCount;
        stage = Stage.FLICK;
        stageTick = tickCount;
    }

    /**
     * Ticks after the first throw by which its spawn packet should have been applied: the use reaches the
     * server half a round trip later, is handled at its next tick, and the spawn comes back the other half.
     */
    private int expectedSpawnTick() {
        double rtt = 0.0;
        if (mc.getNetworkHandler() != null && mc.player != null) {
            net.minecraft.client.network.PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) rtt = Math.max(0, entry.getLatency());
        }
        return (int) Math.ceil((rtt + 50.0) / 50.0);
    }

    private void enterFinish() {
        lookReleased = false;
        finishAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(config.swapBackDelayMs());
        stage = Stage.FINISH;
        stageTick = tickCount;
    }

    private void finish() {
        if (config.swapBack()) restoreSlot();
        RotationManager.clearTarget(owner);
        RotationManager.releaseExclusiveLock(owner);
        outcome = "Finished";
        resetState();
    }

    private void abort(String reason) {
        if (stage == Stage.IDLE && player == null) return;
        failure = reason;
        restoreSlot();
        RotationManager.clearTarget(owner);
        RotationManager.releaseExclusiveLock(owner);
        outcome = reason;
        resetState();
    }

    private void resetState() {
        stage = Stage.IDLE;
        mode = Mode.SOLVER;
        order = Order.PEARL_FIRST;
        forcedFirst = false;
        player = null;
        cycleWorld = null;
        pearlSlot = windSlot = originalSlot = ownedSlot = -1;
        stageTick = firstTick = refinedTick = Long.MIN_VALUE;
        lookYaw = lookPitch = firstYaw = firstPitch = secondYaw = secondPitch = 0.0f;
        finishAtMs = 0L;
        gapFloor = 1;
        baseGapFloor = 1;
        lookReleased = false;
        macroGap = 0;
        pearlThrown = chargeThrown = false;
        lastThrowTick = Long.MIN_VALUE;
        fallbackPearlFirst = true;
        pearlPath = null;
        charge = null;
        gapTarget = 0;
        refined = false;
        projectileId = -1;
        projectileSeen = false;
        plan = null;
        plannedCharge = null;
        plannedPearlPath = null;
        plannedPearl = null;
        plannedTick = Long.MIN_VALUE;
        waitReason = null;
        motion = Vec3d.ZERO;
        groundY = Double.NaN;
        groundTick = Long.MIN_VALUE;
        watchedOwner = Integer.MIN_VALUE;
        spawns.clear();
        knownProjectiles = Set.of();
    }

    // ── Aim and look ────────────────────────────────────────────────────────

    /** Between the throws the look turns, spread evenly over the ticks left, toward the next throw. */
    private void holdLook() {
        if (stage != Stage.FLICK || player == null) return;
        int rel = (int) (tickCount - firstTick);
        // Another aim holding the channel cannot end the sequence: the throws below still go out, aimed
        // with whatever look is held, so a failed step here is not an abort.
        if (order == Order.PEARL_FIRST) {
            if (plan != null) stepToward(plan.yaw(), plan.pitch(), smoothStep(plan.yaw(), plan.pitch(), plan.throwAge() - rel));
        } else {
            stepToward(lookYaw, lookPitch, smoothStep(lookYaw, lookPitch, gapTarget - rel));
        }
    }

    /**
     * Turns the sent look toward this look and reports whether it is there (within half a mouse
     * step). The first throw waits here instead of leaving along a half-turned look, which also
     * covers another aim still holding the rotation channel when the key is pressed.
     */
    private boolean lineUp(float yaw, float pitch) {
        float tolerance = (float) Math.max(ALIGN_TOLERANCE, lookStep() * 0.5 + 0.01);
        if (aligned(yaw, pitch, tolerance)) return true;
        if (!stepToward(yaw, pitch, flickLimit())) {
            waitReason = "another aim holds the rotation";
            return false;
        }
        waitReason = "turning to the throw";
        return aligned(yaw, pitch, tolerance);
    }

    private boolean aligned(float yaw, float pitch, float tolerance) {
        float[] look = packetLook();
        // Near vertical a yaw difference is a tiny angle on the sphere: weigh it by cos(pitch), so a
        // straight-down look is not "misaligned" by a yaw that no longer changes where it points.
        double yawError = Math.abs(MathHelper.wrapDegrees(look[0] - yaw)) * Math.cos(Math.toRadians(pitch));
        return yawError <= tolerance && Math.abs(look[1] - pitch) <= tolerance;
    }

    private boolean stepToward(float yaw, float pitch, float maxStep) {
        return RotationManager.stepAim(owner, RotationManager.Priority.HIGHEST, yaw, pitch, maxStep, config.silent());
    }

    /**
     * Flick speed capped to what one aim step can deliver under the active rotation profile, so a plan
     * never counts on a turn the rotation core will not make.
     */
    private float flickLimit() {
        float limit = Math.min(config.flickSpeed(), RotationManager.aimStepCeiling());
        return Math.max(1.0f, limit);
    }

    private float smoothStep(float yaw, float pitch, int ticksLeft) {
        float[] look = packetLook();
        float dy = MathHelper.wrapDegrees(yaw - look[0]);
        float dp = pitch - look[1];
        float remaining = (float) Math.sqrt(dy * dy + dp * dp);
        return Math.min(flickLimit(), Math.max(0.1f, remaining / Math.max(1, ticksLeft)));
    }

    private float cameraYaw() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getVisualYaw() : mc.player.getYaw();
    }

    private float cameraPitch() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getVisualPitch() : mc.player.getPitch();
    }

    /** The look the next use packet carries ({@code ClientPlayerInteractionManager#interactItem}). */
    private float[] packetLook() {
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    // ── Planning helpers ────────────────────────────────────────────────────

    private static WindChargeIntercept.Shot shotOf(PearlCatchPlanner.Option option) {
        return option == null ? null : option.shot();
    }

    private Entity chargeEntity() {
        if (projectileId < 0 || mc.world == null) return null;
        return mc.world.getEntityById(projectileId);
    }

    private WindChargeIntercept.Shooter currentShooter(PearlCatchPlanner planner) {
        return planner.chargeShooter(planner.shooterIn(0));
    }

    private double eyeHeight() {
        return mc.player.getEyeY() - mc.player.getY();
    }

    /** The mouse lattice step, or a fine default when the sensitivity is unknown. */
    private static double lookStep() {
        double gcd = MouseAimHelper.getGcdDouble();
        return gcd > 0.0 ? gcd : FREE_LOOK_STEP;
    }

    // ── Real-projectile refinement ───────────────────────────────────────────

    /** Clears the spawn queue and starts watching this owner's projectiles, before the first throw. */
    private void watchSpawns(Class<? extends Entity> type) {
        Set<Integer> existing = new HashSet<>();
        if (mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (type.isInstance(entity)) existing.add(entity.getId());
            }
        }
        knownProjectiles = Set.copyOf(existing);
        spawns.clear();
        watchedOwner = player.getId();
    }

    private void refine() {
        if (refined) return;
        if (order == Order.PEARL_FIRST) refinePearl();
        else refineCharge();
    }

    /** Pearl first: the real pearl's launch (spawn packet, or its entity) replaces the prediction. */
    private void refinePearl() {
        if (pearlPath == null) return;
        Spawn match = null;
        int age = -1;
        for (Iterator<Spawn> it = spawns.iterator(); it.hasNext(); ) {
            Spawn spawn = it.next();
            it.remove();
            if (spawn.type() != EntityType.ENDER_PEARL) continue;
            int matched = spawnAge(spawn.position());
            if (matched >= 0) {
                match = spawn;
                age = matched;
                break;
            }
        }
        if (match == null) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof EnderPearlEntity pearl && pearl.getOwner() == player && !pearl.isRemoved()
                        && !knownProjectiles.contains(pearl.getId())) {
                    int matched = spawnAge(pearl.getEntityPos());
                    if (matched >= 0) {
                        match = new Spawn(pearl.getId(), EntityType.ENDER_PEARL, pearl.getEntityPos(), pearl.getVelocity());
                        age = matched;
                        break;
                    }
                }
            }
        }
        if (match == null) return;
        pearlPath = PearlSimulation.resume(mc.world, player, mc.world.getEntityById(match.id()), match.position(),
                match.velocity(), age, pearlPath.leftOwnerAt(age)).trace(PEARL_TRACE_TICKS);
        markRefined(match.id());
    }

    /** Charge first: the real charge's launch (spawn packet, or its entity) replaces the prediction. */
    private void refineCharge() {
        if (charge == null) return;
        Vec3d spawnPos = null;
        Vec3d velocity = null;
        int id = -1;
        for (Iterator<Spawn> it = spawns.iterator(); it.hasNext(); ) {
            Spawn spawn = it.next();
            it.remove();
            if (spawn.type() == EntityType.WIND_CHARGE
                    && spawn.position().squaredDistanceTo(charge.spawn()) <= SPAWN_MATCH_SQ) {
                spawnPos = spawn.position();
                velocity = spawn.velocity();
                id = spawn.id();
                break;
            }
        }
        if (spawnPos == null) {
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof WindChargeEntity wind) || wind.getOwner() != player || wind.isRemoved()
                        || knownProjectiles.contains(wind.getId())) continue;
                // Its client copy has flown some ticks at constant velocity; walk it back to the spawn.
                Vec3d v = wind.getVelocity();
                for (int k = 0; k <= SPAWN_SEARCH; ++k) {
                    Vec3d back = wind.getEntityPos().subtract(v.multiply(k));
                    if (back.squaredDistanceTo(charge.spawn()) <= SPAWN_MATCH_SQ) {
                        spawnPos = back;
                        velocity = v;
                        id = wind.getId();
                        break;
                    }
                }
                if (spawnPos != null) break;
            }
        }
        if (spawnPos == null) return;
        charge = WindChargeIntercept.charge(mc.world, player, spawnPos, velocity, planner().chargeHorizon());
        markRefined(id);
    }

    private void markRefined(int id) {
        refined = true;
        projectileId = id;
        projectileSeen = false;
        refinedTick = tickCount;
        watchedOwner = Integer.MIN_VALUE;
        spawns.clear();
    }

    private int spawnAge(Vec3d spawn) {
        int best = pearlPath.startAge();
        double bestDistance = Double.MAX_VALUE;
        for (int age = pearlPath.startAge(); age <= pearlPath.startAge() + SPAWN_SEARCH; ++age) {
            Vec3d point = pearlPath.positionAt(age);
            if (point == null) break;
            double distance = point.squaredDistanceTo(spawn);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = age;
            }
        }
        return bestDistance <= SPAWN_MATCH_SQ ? best : -1;
    }

    /**
     * True once the first projectile is gone (it hit something). The spawn packet is read on the
     * network thread, so right after refinement its entity may simply not be applied yet; it only
     * counts as gone once seen and then removed, or when it never appears.
     */
    private boolean projectileGone() {
        if (projectileId < 0) return false;
        Entity entity = mc.world.getEntityById(projectileId);
        if (entity != null && !entity.isRemoved()) {
            projectileSeen = true;
            return false;
        }
        return projectileSeen || tickCount - refinedTick > SPAWN_APPLY_TICKS;
    }

    // ── Items and slots ─────────────────────────────────────────────────────

    /** Same-tick swap then use. A failed throw leaves the stage as it was, to be retried. */
    private boolean throwItem(int slot, Item item) {
        if (!selectSlot(slot)) {
            waitReason = "the hotbar change has to wait for the next tick";
            return false;
        }
        if (!useItem(item)) {
            waitReason = "the use was refused (item missing from the hand or cooling down)";
            return false;
        }
        if (item == Items.ENDER_PEARL) pearlThrown = true;
        else if (item == Items.WIND_CHARGE) chargeThrown = true;
        lastThrowTick = tickCount;
        return true;
    }

    /** The item can be thrown: its use cooldown is not running. */
    private boolean readyToThrow(int slot) {
        if (coolingDown(slot)) {
            waitReason = "item cooling down";
            return false;
        }
        return true;
    }

    /**
     * Hotbar selection that keeps vanilla's in-tick order: a slot change cannot follow a use or an
     * attack inside one movement-packet interval, so it waits for the next tick instead.
     */
    private boolean selectSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() != slot && !TickActionOrder.canSwitchSlot()) return false;
        if (!SlotSync.select(mc, slot)) return false;
        ownedSlot = slot;
        return true;
    }

    /**
     * One use of the held item, exactly what vanilla's click handling sends for a plain right click:
     * {@code interactItem} for the main hand, then the swing the result asks for. It is dispatched
     * straight to the interaction manager rather than as a simulated click, because a click is
     * resolved against whatever is under the crosshair (a door, a chest, a villager, a block being
     * mined) and by the input rules of the moment (item-use throttle, a use already running, another
     * module blocking uses), any of which swallows the throw. Returns true once the use was accepted;
     * the caller never repeats an accepted use, so a throw cannot double.
     */
    private boolean useItem(Item item) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        ItemStack held = mc.player.getMainHandStack();
        if (!held.isOf(item) || mc.player.getItemCooldownManager().isCoolingDown(held)) return false;
        // Keeps the use 1-3 ms behind the hotbar swap that selected it (no-op when no swap just happened).
        SlotSync.paceAfterSwap();
        if (mc.player == null || mc.interactionManager == null) return false;
        ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (!result.isAccepted()) return false;
        if (mc.player == null) return true;
        if (!(result instanceof ActionResult.Success success) || success.swingSource() == ActionResult.SwingSource.CLIENT) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        // doItemUse restarts vanilla's four-tick item-use throttle after every click it handles.
        if (mc instanceof MinecraftClientAccessor accessor) {
            accessor.setItemUseCooldown(4);
        }
        if (item == Items.ENDER_PEARL) {
            EventBus.post(new PearlThrowEvent());
        }
        return true;
    }

    private void restoreSlot() {
        if (player == null || mc.player != player || originalSlot < 0
                || ownedSlot == originalSlot || mc.player.getInventory().getSelectedSlot() != ownedSlot) return;
        if (TickActionOrder.canSwitchSlot()) {
            SlotSync.select(mc, originalSlot);
        } else {
            // A throw already left in this movement-packet interval; vanilla cannot switch after it.
            TickActionOrder.deferSlot(originalSlot, ownedSlot);
        }
        ownedSlot = originalSlot;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean clientReady() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && mc.player.isAlive() && !mc.player.isSpectator();
    }

    /**
     * The sequence can still run: same world and player, alive. A screen opening mid-sequence does not
     * stop it (the throws are direct interaction calls), so a started press still throws. The hotbar is
     * deliberately not part of it: a swap by anything else is undone by selecting the item's slot again
     * before each throw.
     */
    private boolean sequenceValid() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.player.isAlive()
                && !mc.player.isSpectator() && mc.world == cycleWorld && player == mc.player;
    }

    /** First tick (after the first throw) the second throw may leave on, never past the latest gap. */
    private int minGapTicks() {
        return Math.min(Math.max(1, config.maxGapTicks()), gapFloor);
    }

    /** Macro: ticks between the two throws, from the Catch Time (never under the floor). */
    private int macroGapTicks() {
        return Math.max(minGapTicks(), Math.min(macroGap, config.maxGapTicks()));
    }

    /** Last tick (after the first throw) the second throw may still leave on. */
    private int hardLimit() {
        return config.maxGapTicks() + SECOND_GRACE;
    }

    private boolean teleported() {
        return motion.lengthSquared() > TELEPORT_MOTION_SQ;
    }

    private boolean slotHolds(int slot, Item item) {
        return slot >= 0 && mc.player != null && mc.player.getInventory().getStack(slot).isOf(item);
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

    /**
     * Minimum distance between two swept segments (a pearl step and a wind-charge step) over one
     * synchronized tick window.
     */
    public static double computeClosestDistance(Vec3d pearlStart, Vec3d pearlEnd, Vec3d windStart, Vec3d windEnd) {
        Vec3d relativeStart = pearlStart.subtract(windStart);
        Vec3d pearlDelta = pearlEnd.subtract(pearlStart);
        Vec3d windDelta = windEnd.subtract(windStart);
        Vec3d relativeDelta = pearlDelta.subtract(windDelta);
        double lengthSquared = relativeDelta.lengthSquared();
        double time = lengthSquared > 1.0E-9
                ? MathHelper.clamp(-relativeStart.dotProduct(relativeDelta) / lengthSquared, 0.0, 1.0)
                : 0.5;
        return pearlStart.add(pearlDelta.multiply(time)).distanceTo(windStart.add(windDelta.multiply(time)));
    }
}
