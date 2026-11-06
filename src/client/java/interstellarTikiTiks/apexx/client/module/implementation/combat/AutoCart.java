package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.mixin.ClientPlayerEntityAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.ArrowFlightPredictor;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartCover;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartCycle;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartCycle.Stage;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartPlanner;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartTiming;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * AutoCart - on a Flame-bow release, places a rail and a TNT minecart (and optionally a cover block)
 * in the arrow's path, all in place before the arrow arrives.
 *
 * <p>The plan is counted in client ticks from the release. The shot is modelled as the server builds
 * it (eye - 0.1, pull x 3 along the last sent look, plus the shooter's last movement) with the bow's
 * random divergence sampled until the arrow's spawn packet reveals the exact launch. Each rail
 * candidate is scored by how many ticks of aim it needs through {@code RotationManager.stepAim}; the
 * module only commits to a cell whose rail and cart both fit before the arrow's intercept.</p>
 *
 * <p>One use per tick, never in the release tick. Each tick the owner-scoped rotation takes one step,
 * and once the live ray reaches the planned face the slot is selected first and the use follows later
 * in that same tick. The same rotation request stays alive for the rest of the tick.</p>
 */
public final class AutoCart extends Module implements OutgoingPacketInterceptor, IncomingPacketInterceptor, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** Rail candidates a cover is searched for, best first; the search is the costly part. */
    private static final int COVER_TRIES = 8;
    /** Movement ticks predicted ahead: the longest flight the arrow predictor follows, plus the boundary. */
    private static final int MOTION_HORIZON = 82;
    /** Hard stop for one cycle, in ticks, whatever the plan says. */
    private static final int MAX_CYCLE_TICKS = 60;
    private static final int MAX_TRACKED_ARROWS = 128;

    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Legit", "Legit", "Silent")
            .setDescription("Legit turns your real camera to each rail, cart and cover face. Silent turns only what the server sees and keeps your screen still.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 120.0, 10.0, 180.0, 0)
            .setDescription("Largest turn per tick, in degrees, toward each placement face. The rotation step limits still apply, and the plan accounts for them.");
    private final SliderSetting arrowMargin = new SliderSetting("Arrow Margin", 0.0, 0.0, 3.0, 0)
            .setDescription("Safety margin, in ticks, between the rail and cart being finished and your arrow arriving. 0 takes every shot that can just make it; higher skips tight shots but rarely fails.");
    private final BooleanSetting safetyBlock = new BooleanSetting("Safe Cart", false)
            .setDescription("After the cart, places a plank, log or slab between you and the blast whenever the explosion could reach you. Best effort: when there is no room or time for the cover, the rail and cart are still placed.");
    private final BooleanSetting requireCover = new BooleanSetting("Require Cover", false)
            .setDescription("Cancels the combo when Safe Cart cannot place its cover in time, instead of placing the rail and cart without it.")
            .setDependsOn("Safe Cart");
    private final BooleanSetting autoRelease = new BooleanSetting("Auto Release", false)
            .setDescription("Lets go of your Flame bow for you once it reaches Release Draw, but only when the rail and cart (and cover with Safe Cart) can be placed in time for that exact shot. Otherwise the bow stays drawn.");
    private final SliderSetting releaseDraw = new SliderSetting("Release Draw (%)", 15.0, 5.0, 40.0, 0)
            .setDescription("How far the bow is pulled back, in percent, before Auto Release lets go. Lower fires sooner with a slower arrow; vanilla fires nothing below 10%, so lower values wait for 10%.")
            .setDependsOn("Auto Release");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 60, 0, 500, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after a cart cycle ends before your previous hotbar item is reselected, in milliseconds. "
                    + "Placement timing itself is not adjustable: it is planned against the arrow's flight and any extra wait would make it miss.")
            .setDependsOn("Swap Back");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Puts you back on the hotbar slot you were holding once the combo ends, unless you switched slots yourself.");

    private final AutoCartCycle cycle = new AutoCartCycle();
    /** Arrow spawn packets, filled on the network thread and drained at tick start. */
    private final Queue<Spawn> spawns = new ConcurrentLinkedQueue<>();
    private final Set<Integer> previousArrows = new HashSet<>();
    private final Set<Integer> trackedArrows = new HashSet<>();

    /** A release seen by the packet hook, planned right after the send. */
    private record PendingShot(AutoCartPlanner.Launch launch, long tick) {
    }

    private record Spawn(int id, int owner, Vec3d position, Vec3d velocity) {
    }

    /** A rail with its schedule; {@code lastOffset} is the cart, or the cover when one is needed. */
    private record Choice(AutoCartPlanner.RailCandidate candidate, int railOffset, int cartOffset, int lastOffset,
                          int hits, AutoCartCover.Plan cover, int coverSteps, double distance) {
    }

    private static final Comparator<Choice> PREFERENCE = Comparator.comparingInt(Choice::hits).reversed()
            .thenComparing(Comparator.comparingDouble(Choice::distance).reversed())
            .thenComparing(Comparator.comparingInt((Choice choice) -> choice.candidate().nominal()).reversed());

    private long tick;
    private PendingShot pendingShot;
    private net.minecraft.client.network.ClientPlayerEntity boundPlayer;
    private net.minecraft.client.world.ClientWorld boundWorld;
    private AutoCartPlanner.Launch launch;
    private int arrowId = -1;
    private boolean arrowSeen;
    private BlockPos rail;
    private BlockPos support;
    /** The rail kind being placed this tick. */
    private Item railItem;
    private Vec3d aimPoint;
    private boolean waitForSpawn;
    private int plannedCoverSteps = -1;
    private AutoCartCover.Plan cover;
    private AutoCartCover.Context coverContext;
    private int originalSlot = -1;
    private int expectedSlot = -1;
    // A swap-back held for the Swap Back Delay after a cycle ends.
    private int deferredOriginal = -1;
    private int deferredExpected = -1;
    private long deferredRestoreAtMs = -1L;
    private boolean restorePending;
    private SubTickSequencer.FluentSequence pendingSequence;
    private long queuedTick = Long.MIN_VALUE;
    private long lastWarnMs;

    public AutoCart() {
        super("AutoCart", "While you shoot a Flame arrow, predicts where it will land near the target and places a rail and TNT minecart there just in time for the arrow to ignite it, with optional cover between you and the blast.", Category.COMBAT);
        setSubCategory(SubCategory.CART);

        addSetting(rotationMode);
        addSetting(aimSpeed);
        addSetting(arrowMargin);
        addSetting(safetyBlock);
        addSetting(requireCover);
        addSetting(autoRelease);
        addSetting(releaseDraw);
        addSetting(switchBack);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        trackedArrows.clear();
        reset(true);
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        abortAndRestore();
        flushDeferredRestore();
        trackedArrows.clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        abortAndRestore();
        flushDeferredRestore();
        trackedArrows.clear();
    }

    /** Turning off (or releasing) cannot wait for onTick, so a pending swap-back runs now instead. */
    private void flushDeferredRestore() {
        if (deferredRestoreAtMs < 0L) return;
        deferredRestoreAtMs = -1L;
        if (mc.player != null && mc.player.getInventory().getSelectedSlot() == deferredExpected) {
            SlotSync.restore(mc, deferredOriginal);
        }
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (cycle.active()) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    // ------------------------------------------------------------------ release

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        if (!isEnabled() || cycle.stage() != Stage.IDLE || pendingShot != null || mc.player == null || mc.world == null) {
            return false;
        }
        if (!(packet instanceof PlayerActionC2SPacket action)
                || action.getAction() != PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) {
            return false;
        }
        // The release is sent before the client clears its active item, so the pull is still readable.
        ItemStack bow = mc.player.getActiveItem();
        float pull = BowItem.getPullProgress(mc.player.getItemUseTime());
        if (!canRun() || !shotReady(bow, pull) || otherModuleHoldsSwap()) {
            warnMissingMaterials(bow, pull);
            return false;
        }
        // The server launches along the last look it received, not an unsent mouse delta.
        AutoCartPlanner.Launch shot = AutoCartPlanner.launch(mc.player, wireYaw(), wirePitch(), pull);
        pendingShot = new PendingShot(shot, tick);
        // Never swap or rotate from inside the send hook: that would put the work ahead of the release
        // packet. The queued task runs right after this send, before the next tick starts.
        mc.send(() -> ModuleCallbackGuard.run(this, "release plan", this::startFromRelease));
        return false;
    }

    private void startFromRelease() {
        PendingShot shot = pendingShot;
        pendingShot = null;
        if (shot == null || !isEnabled() || cycle.stage() != Stage.IDLE || !canRun()) return;
        List<Box> boxes = withReleaseBox(AutoCartPlanner.predictBoxes(mc.world, mc.player, MOTION_HORIZON, wireYaw()));
        List<ArrowFlightPredictor.Result> flights = AutoCartPlanner.flights(mc.world, mc.player, shot.launch(), entity -> false, 0);
        Choice choice = chooseRail(flights, boxes, 0, 1, margin(), safetyBlock.getValue(), -1);
        if (choice == null) return;
        bind(shot.launch(), shot.tick(), -1);
        previousArrows.clear();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PersistentProjectileEntity) previousArrows.add(entity.getId());
        }
        adoptRail(choice, boxes, 0);
        // The release tick itself may rotate but never use: the first step goes out now.
        step();
    }

    /**
     * Auto Release: lets go of a Flame bow drawn to the Release Draw percentage once the plan for exactly that shot (rail, cart
     * and, with Safe Cart, the cover) fits before the arrow arrives. The release itself is the use key
     * going up, which vanilla's input handling turns into its own stop-using call, so the packet hook
     * above plans it like a manual release. (The RELEASE_USE_ITEM packet is recorded for the interval by
     * TickActionOrder's send seam, so no explicit recordUse is needed here.)
     */
    private void autoReleaseBow() {
        if (!autoRelease.getValue() || !mc.player.isUsingItem() || !canRun() || otherModuleHoldsSwap()) return;
        ItemStack bow = mc.player.getActiveItem();
        float pull = BowItem.getPullProgress(mc.player.getItemUseTime());
        if (pull < Math.max(0.1F, (float) (releaseDraw.getValue() / 100.0)) || !shotReady(bow, pull)) return;
        AutoCartPlanner.Launch shot = AutoCartPlanner.launch(mc.player, wireYaw(), wirePitch(), pull);
        List<Box> boxes = withReleaseBox(AutoCartPlanner.predictBoxes(mc.world, mc.player, MOTION_HORIZON, wireYaw()));
        List<ArrowFlightPredictor.Result> flights = AutoCartPlanner.flights(mc.world, mc.player, shot, entity -> false, 0);
        if (chooseRail(flights, boxes, 0, 1, margin(), safetyBlock.getValue(), -1) == null) return;
        // Release through the input seam rather than calling stopUsingItem directly: vanilla's own input
        // handling sees the use key up and sends the release at its normal place in the tick. A draw this
        // module did not press (the player's own) is let go by lifting the binding the same way.
        SystemInputSimulator.releaseUse();
        if (mc.options != null && mc.options.useKey != null && mc.options.useKey.isPressed()
                && !SystemInputSimulator.isBindingSimulatedPressed(mc.options.useKey)) {
            mc.options.useKey.setPressed(false);
        }
    }

    /**
     * Arrow already in flight and not seen at release. Its client position trails the server by the
     * one-way latency, which the offset absorbs so the deadline is not optimistic.
     */
    private void discoverArrow() {
        if (!canRun() || otherModuleHoldsSwap() || !hasMaterials()) return;
        Box search = mc.player.getBoundingBox().expand(25.0);
        List<PersistentProjectileEntity> arrows = mc.world.getEntitiesByClass(PersistentProjectileEntity.class, search,
                arrow -> arrow.isAlive() && arrow.age <= 10 && arrow.getOwner() == mc.player && arrow.isOnFire()
                        && !trackedArrows.contains(arrow.getId()) && arrow.getVelocity().lengthSquared() > 0.25);
        for (PersistentProjectileEntity arrow : arrows) {
            if (trackedArrows.size() >= MAX_TRACKED_ARROWS) trackedArrows.clear();
            trackedArrows.add(arrow.getId());
            AutoCartPlanner.Launch shot = AutoCartPlanner.exact(arrow.getEntityPos(), arrow.getVelocity());
            int latencyTicks = (int) Math.ceil(latency() / 100.0);
            List<Box> boxes = AutoCartPlanner.predictBoxes(mc.world, mc.player, MOTION_HORIZON, wireYaw());
            long shotTick = tick - 1 - latencyTicks;
            int now = (int) (tick - shotTick);
            List<ArrowFlightPredictor.Result> flights = AutoCartPlanner.flights(mc.world, mc.player, shot, entity -> false, 0);
            Choice choice = chooseRail(flights, boxes, now, now, margin(), safetyBlock.getValue(), -1);
            if (choice == null) continue;
            bind(shot, shotTick, arrow.getId());
            adoptRail(choice, boxes, now);
            return;
        }
    }

    private void bind(AutoCartPlanner.Launch shot, long shotTick, int trackedArrow) {
        TickActionOrder.cancelDeferredSlot();
        boundPlayer = mc.player;
        boundWorld = mc.world;
        launch = shot;
        arrowId = trackedArrow;
        arrowSeen = false;
        spawns.clear();
        originalSlot = expectedSlot = mc.player.getInventory().getSelectedSlot();
        if (deferredRestoreAtMs >= 0L && mc.player.getInventory().getSelectedSlot() == deferredExpected) {
            // A new cycle started before the last one's swap-back fired: hand back the real original item at its end.
            originalSlot = deferredOriginal;
        }
        deferredRestoreAtMs = -1L;
        restorePending = false;
        cover = null;
        coverContext = null;
        waitForSpawn = false;
        cycle.begin(shotTick, safetyBlock.getValue());
    }

    private boolean shotReady(ItemStack bow, float pull) {
        return bow.getItem() instanceof BowItem && hasFlame(bow) && pull >= 0.1F
                && !mc.player.getProjectileType(bow).isEmpty() && hasMaterials();
    }

    /** A Flame shot that was skipped for missing items says which, instead of doing nothing silently. */
    private void warnMissingMaterials(ItemStack bow, float pull) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 3000L || !(bow.getItem() instanceof BowItem) || !hasFlame(bow) || pull < 0.1F) return;
        String missing = AutoCartPlanner.railSlot(mc.world, null) < 0 ? "a rail"
                : AutoCartPlanner.hotbarSlot(Items.TNT_MINECART) < 0 ? "a TNT minecart"
                : safetyBlock.getValue() && requireCover.getValue() && !coverSlotExists() ? "a cover block (planks, log or wooden slab) for Safe Cart" : null;
        if (missing == null) return;
        lastWarnMs = now;
        Notifier.warn(getName(), "Skipped: no " + missing + " in your hotbar");
    }

    private boolean hasMaterials() {
        return AutoCartPlanner.railSlot(mc.world, null) >= 0 && AutoCartPlanner.hotbarSlot(Items.TNT_MINECART) >= 0
                && (!safetyBlock.getValue() || !requireCover.getValue() || coverSlotExists());
    }

    private boolean coverSlotExists() {
        for (int slot = 0; slot < 9; slot++) {
            if (AutoCartCover.isCoverStack(mc.player.getInventory().getStack(slot))) return true;
        }
        return false;
    }

    private boolean hasFlame(ItemStack bow) {
        try {
            var registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            return EnchantmentHelper.getLevel(registry.getOrThrow(Enchantments.FLAME), bow) > 0;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Distinct available wood blocks; the planner compares their actual placed shapes. */
    private List<ItemStack> coverStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        Set<Item> seen = new HashSet<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (AutoCartCover.isCoverStack(stack) && seen.add(stack.getItem())) stacks.add(stack);
        }
        return stacks;
    }

    // ------------------------------------------------------------------ planning

    /**
     * Best rail for the current knowledge of the shot, or null when none can be finished in time.
     *
     * @param now               release offset of the tick being planned (its rotation step has not run)
     * @param earliestRail      first offset the rail may be used at
     * @param assumedCoverSteps below zero plans the cover; otherwise assumes that many rotation ticks to it
     */
    private Choice chooseRail(List<ArrowFlightPredictor.Result> flights, List<Box> boxes, int now, int earliestRail,
                              int margin, boolean safe, int assumedCoverSteps) {
        if (flights.isEmpty() || flights.get(0) == null) return null;
        var player = mc.player;
        int first = Math.max(Math.max(1, now), earliestRail);
        Box occupied = boxes.get(0).union(boxAt(boxes, now, first));
        // A use at offset N goes out at the start of that tick, from the position the previous tick left.
        List<AutoCartPlanner.RailCandidate> candidates = AutoCartPlanner.railCandidates(mc.world, player, flights,
                AutoCartPlanner.eyeOf(player, boxAt(boxes, now, first - 1)), lookYaw(), occupied);
        Vec3d center = boxes.get(0).getCenter();
        List<Choice> viable = new ArrayList<>();
        for (AutoCartPlanner.RailCandidate candidate : candidates) {
            if (AutoCartPlanner.railSlot(mc.world, candidate.rail()) < 0) continue;
            int steps = steps(lookYaw(), lookPitch(), candidate.yaw(), candidate.pitch());
            int railOffset = AutoCartTiming.actionOffset(now, now, first, steps);
            // The cart reuses the rail's look: a ray onto the support's top crosses the new rail first.
            int cartOffset = AutoCartTiming.nextOffset(railOffset, 0);
            if (!AutoCartTiming.inTime(cartOffset, candidate.nominal(), margin)) continue;
            viable.add(new Choice(candidate, railOffset, cartOffset, cartOffset, candidate.hits(cartOffset, margin),
                    null, 0, candidate.blast().squaredDistanceTo(center)));
        }
        viable.sort(PREFERENCE);
        if (!safe) return viable.isEmpty() ? null : viable.get(0);
        // Cover is best effort unless Require Cover is on: a rail with room for cover wins, but a rail
        // without room is still placed (rail and cart only) instead of cancelling the combo.
        List<Choice> covered = new ArrayList<>();
        int tries = 0;
        for (Choice choice : viable) {
            Choice withCover;
            if (assumedCoverSteps >= 0) {
                int coverOffset = AutoCartTiming.nextOffset(choice.cartOffset(), assumedCoverSteps);
                withCover = AutoCartTiming.inTime(coverOffset, choice.candidate().nominal(), margin)
                        ? new Choice(choice.candidate(), choice.railOffset(), choice.cartOffset(), coverOffset,
                        choice.candidate().hits(coverOffset, margin), null, assumedCoverSteps, choice.distance()) : null;
            } else {
                if (tries++ >= COVER_TRIES) break;
                withCover = withCover(choice, flights.get(0), boxes, now, margin);
            }
            if (withCover != null) covered.add(withCover);
        }
        if (!covered.isEmpty()) {
            covered.sort(PREFERENCE);
            return covered.get(0);
        }
        if (requireCover.getValue()) return null;
        return viable.isEmpty() ? null : viable.get(0);
    }

    /** Adds the cover use after the cart; a player the blast cannot see needs none. */
    private Choice withCover(Choice choice, ArrowFlightPredictor.Result flight, List<Box> boxes, int now, int margin) {
        AutoCartPlanner.RailCandidate candidate = choice.candidate();
        int intercept = candidate.nominal();
        // The blast happens during the arrow's move for segment `intercept`, after the client's
        // movement for offset intercept - 1 was applied on the server.
        Box exposed = boxAt(boxes, now, intercept - 1);
        if (AutoCartCover.exposure(mc.world, mc.player, candidate.blast(), exposed) <= 0) return choice;
        int coverOffset = choice.cartOffset() + 1;
        AutoCartCover.Plan plan = null;
        int steps = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            AutoCartCover.Context context = coverContext(candidate.rail(), flight, intercept, coverOffset, boxes, now, exposed);
            plan = AutoCartCover.find(mc.world, mc.player, context, lookYaw(), coverStacks());
            if (plan == null) return null;
            steps = steps(candidate.yaw(), candidate.pitch(), plan.yaw(), plan.pitch());
            int next = AutoCartTiming.nextOffset(choice.cartOffset(), steps);
            if (next == coverOffset) break;
            coverOffset = next;
        }
        if (!AutoCartTiming.inTime(coverOffset, intercept, margin)) return null;
        return new Choice(candidate, choice.railOffset(), choice.cartOffset(), coverOffset,
                candidate.hits(coverOffset, margin), plan, steps, choice.distance());
    }

    private AutoCartCover.Context coverContext(BlockPos railCell, ArrowFlightPredictor.Result flight, int intercept,
                                               int coverOffset, List<Box> boxes, int now, Box exposed) {
        // The server checks the placement against the box from before this tick's move.
        Box serverBox = boxAt(boxes, now, coverOffset - 1);
        Box placeBox = boxAt(boxes, now, coverOffset);
        return new AutoCartCover.Context(railCell, flight.path(), intercept, coverOffset,
                serverBox, placeBox, AutoCartPlanner.eyeOf(mc.player, serverBox), exposed);
    }

    private void adoptRail(Choice choice, List<Box> boxes, int now) {
        rail = choice.candidate().rail();
        support = choice.candidate().support();
        plannedCoverSteps = choice.cover() == null && choice.lastOffset() == choice.cartOffset() ? -1 : choice.coverSteps();
        AutoCartPlanner.Aim aim = AutoCartPlanner.faceAim(mc.world, mc.player,
                AutoCartPlanner.eyeOf(mc.player, boxAt(boxes, now, Math.max(0, now - 1))), support, Direction.UP, rail, lookYaw());
        aimPoint = aim != null ? aim.point() : choice.candidate().aimPoint();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        tick++;
        if (deferredRestoreAtMs >= 0L && System.currentTimeMillis() >= deferredRestoreAtMs) {
            if (mc.player != null && mc.player.getInventory().getSelectedSlot() == deferredExpected) {
                SlotSync.restore(mc, deferredOriginal);
            }
            deferredRestoreAtMs = -1L;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset(true);
            trackedArrows.clear();
            return;
        }
        if (cycle.stage() == Stage.IDLE) {
            spawns.clear();
            if (pendingShot == null) autoReleaseBow();
            if (pendingShot == null) discoverArrow();
            if (cycle.stage() == Stage.IDLE) return;
        } else {
            if (!sameWorld() || !mc.player.isAlive()) {
                reset(true);
                return;
            }
            resolveQueuedUse();
            if (!cycle.tick(tick)) {
                finish();
                return;
            }
            if (cycle.stage() == Stage.HOLD) return;
            if (tick - cycle.shotTick() > MAX_CYCLE_TICKS || !canRun()
                    || mc.player.getInventory().getSelectedSlot() != expectedSlot || mc.player.isUsingItem()) {
                finish();
                return;
            }
        }

        adoptSpawn();
        if (arrowFailure() != null) {
            finish();
            return;
        }
        int now = cycle.offset(tick);
        List<ArrowFlightPredictor.Result> flights = AutoCartPlanner.flights(mc.world, boundPlayer, launch, intendedCart(), now);
        if (flights.isEmpty() || flights.get(0) == null) {
            finish();
            return;
        }
        List<Box> boxes = AutoCartPlanner.predictBoxes(mc.world, mc.player, MOTION_HORIZON, wireYaw());
        String failure = switch (cycle.stage()) {
            case RAIL -> planRail(flights, boxes, now);
            case CART -> planCart(flights, boxes, now);
            case COVER -> planCover(flights, boxes, now);
            default -> null;
        };
        if (failure != null) {
            ClientInternalLog.warn("AutoCart", "cycle ended at " + cycle.stage() + ": " + failure);
            finish();
            return;
        }
        if (!step()) {
            finish();
            return;
        }
        tryUse();
    }

    /** A sequence the sequencer dropped or never finished gives its tick back to the stage. */
    private void resolveQueuedUse() {
        if (pendingSequence == null) return;
        if (!pendingSequence.isComplete()) SubTickSequencer.cancelOwner(this);
        pendingSequence = null;
        if (queuedTick != Long.MIN_VALUE) {
            cycle.withdrawn(queuedTick);
            queuedTick = Long.MIN_VALUE;
        }
    }

    private String planRail(List<ArrowFlightPredictor.Result> flights, List<Box> boxes, int now) {
        Choice choice = chooseRail(flights, boxes, now, 1, margin(), cycle.safe(), -1);
        if (choice == null) return "no rail in time";
        adoptRail(choice, boxes, now);
        // Until the spawn packet arrives the divergence is a guess. While the nominal arrow still
        // allows a later rail, wait for it instead of committing to a cell only some launches hit.
        waitForSpawn = !launch.exact() && choice.hits() < flights.size()
                && chooseRail(flights.subList(0, 1), boxes, now, now + 1, margin(), cycle.safe(),
                Math.max(0, choice.coverSteps())) != null;
        return null;
    }

    private String planCart(List<ArrowFlightPredictor.Result> flights, List<Box> boxes, int now) {
        BlockState state = mc.world.getBlockState(rail);
        if (!(state.getBlock() instanceof AbstractRailBlock railBlock) || state.get(railBlock.getShapeProperty()).isAscending()) {
            return "rail missing or sloped";
        }
        int intercept = ArrowFlightPredictor.cartInterceptTick(mc.world, boundPlayer, flights.get(0), rail);
        int previous = cycle.previousOffset();
        // Aim into the rail's own 2/16-tall outline; the cart item uses the rail block.
        AutoCartPlanner.Aim aim = AutoCartPlanner.insideAim(mc.world, mc.player,
                AutoCartPlanner.eyeOf(mc.player, boxAt(boxes, now, Math.max(0, now - 1))), rail, 0.0625, lookYaw());
        if (aim != null) aimPoint = aim.point();
        int steps = aim == null ? 1 : steps(lookYaw(), lookPitch(), aim.yaw(), aim.pitch());
        int cartOffset = AutoCartTiming.actionOffset(now, previous + 1, previous + 1, steps);
        if (!AutoCartTiming.inTime(cartOffset, intercept, margin())) return "cart would be late";
        // Safe mode never detonates a cart it cannot also cover.
        if (cycle.safe() && requireCover.getValue() && plannedCoverSteps >= 0
                && !AutoCartTiming.inTime(AutoCartTiming.nextOffset(cartOffset, plannedCoverSteps), intercept, margin())) {
            return "cover would be late";
        }
        return null;
    }

    private String planCover(List<ArrowFlightPredictor.Result> flights, List<Box> boxes, int now) {
        ArrowFlightPredictor.Result flight = flights.get(0);
        int intercept = ArrowFlightPredictor.cartInterceptTick(mc.world, boundPlayer, flight, rail);
        if (intercept < 1) return "arrow no longer meets the cart";
        Box exposed = boxAt(boxes, now, intercept - 1);
        if (AutoCartCover.exposure(mc.world, mc.player, AutoCartPlanner.blastPoint(rail), exposed) <= 0) {
            return "covered";
        }
        int previous = cycle.previousOffset();
        int coverOffset = Math.max(now, previous + 1);
        coverContext = coverContext(rail, flight, intercept, coverOffset, boxes, now, exposed);
        AutoCartCover.Plan plan = null;
        if (cover != null && AutoCartPlanner.hotbarSlot(cover.item()) >= 0
                && AutoCartCover.stillProtects(mc.world, mc.player, coverContext, cover)) {
            plan = AutoCartCover.reaim(mc.world, mc.player, cover, coverContext.eye(), lookYaw());
        }
        if (plan == null) plan = AutoCartCover.find(mc.world, mc.player, coverContext, lookYaw(), coverStacks());
        if (plan == null) return "no cover";
        cover = plan;
        coverOffset = AutoCartTiming.actionOffset(now, previous + 1, previous + 1,
                steps(lookYaw(), lookPitch(), plan.yaw(), plan.pitch()));
        if (!AutoCartTiming.inTime(coverOffset, intercept, margin())) return "cover would be late";
        aimPoint = plan.aimPoint();
        return null;
    }

    /** One owner-scoped placement-rotation step toward the current aim point; commits immediately. */
    private boolean step() {
        if (aimPoint == null || mc.player == null) return false;
        float[] goal = AutoCartPlanner.anglesTo(mc.player.getEyePos(), aimPoint, lookYaw());
        return RotationManager.stepAim(this, RotationManager.Priority.HIGH, goal[0], goal[1],
                stepBudget(), isSilent());
    }

    // ------------------------------------------------------------------ arrow

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        // Network thread: only hand the immutable packet values over.
        if (!(packet instanceof EntitySpawnS2CPacket spawn) || !isEnabled()) return false;
        EntityType<?> type = spawn.getEntityType();
        if ((type != EntityType.ARROW && type != EntityType.SPECTRAL_ARROW) || spawns.size() >= 32) return false;
        spawns.add(new Spawn(spawn.getEntityId(), spawn.getEntityData(),
                new Vec3d(spawn.getX(), spawn.getY(), spawn.getZ()), spawn.getVelocity()));
        return false;
    }

    /** The spawn packet holds the server's launch exactly: position before its first move and divergent velocity. */
    private void adoptSpawn() {
        if (launch == null) return;
        Spawn spawn;
        while ((spawn = spawns.poll()) != null) {
            if (launch.exact() || spawn.owner() != boundPlayer.getId() || previousArrows.contains(spawn.id())
                    || spawn.velocity().lengthSquared() < 0.01
                    || spawn.position().squaredDistanceTo(launch.origin()) > 2.25) continue;
            launch = AutoCartPlanner.exact(spawn.position(), spawn.velocity());
            arrowId = spawn.id();
            trackedArrows.add(spawn.id());
        }
    }

    private String arrowFailure() {
        if (arrowId < 0) return null;
        Entity entity = mc.world.getEntityById(arrowId);
        if (entity == null) return arrowSeen ? "arrow gone" : null;
        arrowSeen = true;
        if (!(entity instanceof PersistentProjectileEntity arrow)) return "arrow gone";
        if (arrow.isRemoved() || (arrow.age >= 2 && arrow.getVelocity().lengthSquared() < 1.0e-4)) return "arrow landed";
        return arrow.age >= 2 && !arrow.isOnFire() ? "arrow not burning" : null;
    }

    /** The intended cart is the arrow's target, not an obstacle in its predicted flight. */
    private Predicate<Entity> intendedCart() {
        BlockPos cell = rail;
        return entity -> cell != null && entity instanceof TntMinecartEntity && entity.getBlockPos().equals(cell);
    }

    // ------------------------------------------------------------------ use

    /**
     * Eligibility is the live ray reaching the planned face after this tick's step, not an angle
     * threshold. A miss retries next tick; the tick plan ends the cycle once the arrow no longer allows it.
     */
    private void tryUse() {
        if (!cycle.placing() || !cycle.canQueue(tick) || pendingSequence != null) return;
        Stage stage = cycle.stage();
        if (stage == Stage.RAIL && waitForSpawn) return;
        if (RotationManager.getCurrentRotation(this) == null) {
            finish();
            return;
        }
        if (stage == Stage.RAIL) {
            int railSlot = AutoCartPlanner.railSlot(mc.world, rail);
            railItem = railSlot < 0 ? null : mc.player.getInventory().getStack(railSlot).getItem();
            if (railItem == null) {
                finish();
                return;
            }
        }
        if (liveHit(stage) == null) return;
        Item item = stage == Stage.COVER ? cover.item() : stage == Stage.CART ? Items.TNT_MINECART : railItem;
        int slot = AutoCartPlanner.hotbarSlot(item);
        if (slot < 0) {
            finish();
            return;
        }
        if (mc.player.isUsingItem()) return;
        boolean swap = mc.player.getInventory().getSelectedSlot() != slot;
        if (swap && (!TickActionOrder.canSwitchSlot() || otherModuleHoldsSwap())) return;
        long queued = tick;
        expectedSlot = slot;
        cycle.queued(queued);
        queuedTick = queued;
        if (!swap) {
            useNow(stage, item, queued, true);
            return;
        }
        restorePending = true;
        if (ExecutionMode.isDirect()) {
            // Direct: the slot is selected and the use follows in this same call, before the tick's
            // movement packet, from the position the plan aimed from.
            if (!SlotSync.selectNow(mc, slot)) {
                cycle.withdrawn(queued);
                queuedTick = Long.MIN_VALUE;
                return;
            }
            useNow(stage, item, queued, true);
            return;
        }
        // The slot goes out first; the use follows later in this same tick.
        try {
            pendingSequence = SubTickSequencer.sequence(mc, this)
                    .selectSlot(slot)
                    .thenDelay()
                    .action(() -> useNow(stage, item, queued, false));
            pendingSequence.start();
        } catch (RuntimeException failure) {
            SubTickSequencer.cancelOwner(this);
            pendingSequence = null;
            cycle.withdrawn(queued);
            queuedTick = Long.MIN_VALUE;
        }
    }

    /** @param inline the use runs inside this tick's callback, ahead of the movement packet */
    private void useNow(Stage stage, Item item, long queued, boolean inline) {
        if (!isEnabled() || tick != queued || !sameWorld() || !canRun() || cycle.stage() != stage
                || RotationManager.getCurrentRotation(this) == null || mc.player.isUsingItem()
                || mc.player.getInventory().getSelectedSlot() != expectedSlot
                || !mc.player.getInventory().getStack(expectedSlot).isOf(item)) {
            return;
        }
        BlockHitResult hit = liveHit(stage);
        if (hit == null) return;
        boolean used = InteractSim.interactBlockOncePerTick(mc, hit, isSilent());
        boolean placed = switch (stage) {
            case RAIL -> item instanceof BlockItem placedRail && mc.world.getBlockState(rail).isOf(placedRail.getBlock());
            case COVER -> mc.world.getBlockState(cover.cell()) == cover.state();
            default -> true;
        };
        if (used && inline) TickActionOrder.recordUse();
        if (!used || !placed) {
            // A refused use gives its tick back; a stale queue mark would end the whole cycle.
            cycle.withdrawn(queued);
            queuedTick = Long.MIN_VALUE;
            return;
        }
        cycle.accepted(queued);
        queuedTick = Long.MIN_VALUE;
        // This tick's rotation request stays as it is; the next target starts next tick.
    }

    private BlockHitResult liveHit(Stage stage) {
        return switch (stage) {
            case RAIL -> railHit();
            case CART -> cartHit();
            case COVER -> coverHit();
            default -> null;
        };
    }

    private BlockHitResult railHit() {
        if (rail == null || support == null || !(railItem instanceof BlockItem railBlockItem)) return null;
        BlockHitResult hit = BlockPlacementValidator.hitForPlacement(mc, rail, isSilent());
        if (!placesRail(hit)) return null;
        ItemPlacementContext context = new ItemPlacementContext(mc.player, Hand.MAIN_HAND, railItem.getDefaultStack(), hit);
        if (!context.getBlockPos().equals(rail) || !context.canPlace() || !mc.world.getBlockState(rail).canReplace(context)
                || !AutoCartPlanner.railCellUsable(mc.world, rail)
                || !mc.world.canPlace(railBlockItem.getBlock().getDefaultState(), rail, ShapeContext.of(mc.player))) return null;
        return hit;
    }

    private boolean placesRail(BlockHitResult hit) {
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                && hit.getSide() == Direction.UP && rail.equals(BlockPlacementValidator.placementCell(mc, hit));
    }

    private BlockHitResult cartHit() {
        if (rail == null) return null;
        BlockHitResult hit = BlockPlacementValidator.hitForInteraction(mc, rail, isSilent());
        if (hit == null) return null;
        BlockState state = mc.world.getBlockState(rail);
        if (!(state.getBlock() instanceof AbstractRailBlock railBlock) || state.get(railBlock.getShapeProperty()).isAscending()
                || !mc.world.getOtherEntities(null, new Box(rail).expand(0.05),
                entity -> entity instanceof AbstractMinecartEntity).isEmpty()) return null;
        return hit;
    }

    private BlockHitResult coverHit() {
        if (cover == null || coverContext == null) return null;
        BlockHitResult hit = BlockPlacementValidator.hitForPlacement(mc, cover.cell(), isSilent());
        if (!AutoCartCover.matches(hit, cover) || !AutoCartCover.canPlace(mc.world, mc.player, cover, hit)) return null;
        // The server checks the box from before this tick's move, a queued check the one after.
        Box before = mc.player.getBoundingBox();
        for (Box shape : cover.collision()) {
            if (shape.intersects(before) || shape.intersects(coverContext.placeBox())) return null;
        }
        return hit;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Post-move box of the tick at release offset {@code offset}, planned from offset {@code now}
     * (index 0 is the position at the start of tick {@code now}).
     */
    private static Box boxAt(List<Box> boxes, int now, int offset) {
        return boxes.get(Math.max(0, Math.min(boxes.size() - 1, offset - now + 1)));
    }

    /** A release is planned after its tick already moved: the list gains a leading start-of-tick box. */
    private static List<Box> withReleaseBox(List<Box> boxes) {
        List<Box> shifted = new ArrayList<>(boxes.size() + 1);
        shifted.add(boxes.get(0));
        shifted.addAll(boxes);
        return shifted;
    }

    private int steps(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        return AutoCartTiming.stepsNeeded(fromYaw, fromPitch, toYaw, toPitch, stepBudget(),
                RotationManager.aimStepCeiling());
    }

    /**
     * Degrees one aim step may turn: the Aim Speed setting. The rotation core's own step ceiling is
     * applied by the planner, so the tick plan matches what the rotation core will actually deliver.
     */
    private float stepBudget() {
        double budget = aimSpeed.getValue();
        return (float) Math.max(1.0, budget);
    }

    private int margin() {
        return (int) arrowMargin.getValue();
    }

    private float lookYaw() {
        return RotationManager.getFinalYaw();
    }

    private float lookPitch() {
        return RotationManager.getFinalPitch();
    }

    /** The last yaw the server received. */
    private float wireYaw() {
        float yaw = mc.player instanceof ClientPlayerEntityAccessor accessor ? accessor.getLastYawClient() : Float.NaN;
        return Float.isFinite(yaw) ? yaw : mc.player.getYaw();
    }

    private float wirePitch() {
        float pitch = mc.player instanceof ClientPlayerEntityAccessor accessor ? accessor.getLastPitchClient() : Float.NaN;
        return Float.isFinite(pitch) ? pitch : mc.player.getPitch();
    }

    private int latency() {
        var connection = mc.getNetworkHandler();
        var entry = connection == null ? null : connection.getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : entry.getLatency();
    }

    private boolean isSilent() {
        return "Silent".equalsIgnoreCase(rotationMode.getCurrentMode());
    }

    /** True while a shot is pending or a rail/cart/cover cycle is running; CartRefill waits for it. */
    public boolean isBusy() {
        return cycle.stage() != Stage.IDLE || pendingShot != null;
    }

    private boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null
                && mc.currentScreen == null && !mc.isPaused() && mc.player.isAlive() && !mc.player.hasVehicle()
                && mc.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
    }

    private boolean sameWorld() {
        return mc.player == boundPlayer && mc.world == boundWorld && boundPlayer != null && boundWorld != null;
    }

    /** Another module (shield break, mace chain, ...) owns the hotbar right now. */
    private boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    /** Ends the cycle: releases the aim and restores the slot this module selected. */
    private void finish() {
        if (!sameWorld()) {
            reset(true);
            return;
        }
        abortAndRestore();
    }

    private void abortAndRestore() {
        restoreOwnedSlot();
        // The manager's own takeback eases a silent look back to the camera.
        RotationManager.clearTarget(this);
        reset(false);
    }

    /** Restores the pre-sequence slot only while the selection is still the one this module made. */
    private void restoreOwnedSlot() {
        if (switchBack.getValue() && restorePending && originalSlot >= 0 && originalSlot <= 8 && expectedSlot >= 0
                && mc.player != null && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            long delay = ModuleDelays.clampMs(swapBackDelay.sample());
            if (delay <= 0L) {
                SlotSync.restore(mc, originalSlot);
            } else {
                deferredOriginal = originalSlot;
                deferredExpected = expectedSlot;
                deferredRestoreAtMs = System.currentTimeMillis() + delay;
            }
        }
        restorePending = false;
    }

    private void reset(boolean stopRotation) {
        // Only a hard reset (world change, disconnect) drops a queued restore; a normal finish just
        // deferred it and it must survive until the next movement interval.
        if (stopRotation) {
            TickActionOrder.cancelDeferredSlot();
            deferredRestoreAtMs = -1L;
        }
        SubTickSequencer.cancelOwner(this);
        if (stopRotation) RotationManager.stop(this);
        cycle.reset();
        pendingShot = null;
        pendingSequence = null;
        queuedTick = Long.MIN_VALUE;
        boundPlayer = null;
        boundWorld = null;
        previousArrows.clear();
        spawns.clear();
        launch = null;
        arrowId = -1;
        arrowSeen = false;
        rail = support = null;
        railItem = null;
        aimPoint = null;
        waitForSpawn = false;
        plannedCoverSteps = -1;
        cover = null;
        coverContext = null;
        originalSlot = expectedSlot = -1;
        restorePending = false;
    }
}
