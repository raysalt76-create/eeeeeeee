package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartPlanner;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.CrossbowShotGeometry;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.WebHitModel;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * AutoXBowCart - One-shot explosive crossbow propulsion combo.
 *
 * <p>Automates the explosive crossbow propulsion sequence:
 * <ol>
 *   <li>Pre-places a rail at the targeted position (or starts from an existing rail).</li>
 *   <li>Places a TNT Minecart on the rail (with optional Double Cart support).</li>
 *   <li>Optionally places a blast-shield safety block (slab/solid block) to absorb damage.</li>
 *   <li>Lights fire (Flint and Steel, or Lava) in the cell between you and the cart.</li>
 *   <li>Shoots the charged crossbow at the cart through that fire; the burning arrow detonates it.</li>
 *   <li>Restores the original hotbar slot and automatically finishes/disables.</li>
 * </ol>
 *
 * <p>Aims through {@link RotationManager#stepAim}; dispatch stays inside the tick action window.
 *
 * <p>The rail cell is searched in every direction around you (diagonals included) for one that is in
 * reach, can take a rail, and has a line of fire. The fire goes in whichever cell the shot actually
 * crosses on its way to the live cart box. Short-range trajectory checks account for the arrow's
 * launch height, drag, gravity and sampled divergence. These checks are predictions, not confirmed
 * impacts. Cart, ignition and crossbow state are observed before advancing, with bounded retries.
 */
public final class AutoXBowCart extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating,
        interstellarTikiTiks.apexx.client.module.hooks.RailPlacedAware,
        interstellarTikiTiks.apexx.client.module.hooks.CartSequenceAware {
    // ---- Delays ----
    /** Retired: kept unregistered so old configs still load; the per-step delays are always used. */
    private final BooleanSetting useTimingProfile = new BooleanSetting("Use Timing Profile", false);
    // One wait per step of the combo (each replaces the old single "Step Delay (ms)", same default).
    // Capped at 400 ms so the whole combo still finishes before the target walks off.
    private final SliderSetting railDelay = new SliderSetting("Rail Delay (ms)", 50, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait after the rail is placed before the TNT minecart, on top of vanilla's own item-use delay.")
            .setSection("Delays");
    private final SliderSetting cartDelay = new SliderSetting("Cart Delay (ms)", 50, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait after each TNT minecart is placed before the next cart (Double Cart) or the fire.")
            .setSection("Delays");
    private final SliderSetting fireDelay = new SliderSetting("Fire Delay (ms)", 50, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait after the fire is lit before the safety block or the crossbow shot.")
            .setSection("Delays");
    private final SliderSetting blockDelay = new SliderSetting("Safety Block Delay (ms)", 50, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait after the safety block is placed before the crossbow shot.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait after the shot is confirmed before Swap Back reselects your original slot. The swap back is always on a later tick than the shot.")
            .setSection("Delays")
            .setDependsOn("Swap Back");


    /** Block reach ceiling: never beyond vanilla's 4.5, whatever the attribute says. */
    private static final double MAX_REACH = 4.5;
    private static final long TIMEOUT_MS = 3000L;
    private static final long SEQUENCE_TIMEOUT_MS = 8000L;
    /** Re-placements of the rail, cart or fire when the server did not keep them. */
    private static final int MAX_STAGE_RETRIES = 3;
    /** New target cells tried when the first becomes unreachable before the rail is down. */
    private static final int MAX_REPLANS = 2;
    /** How long a click may stay out of reach (movement, lag) before another cell is chosen. */
    private static final long UNREACHABLE_BUFFER_MS = 350L;
    /** A confirmation waits this long plus the round trip before the step is placed again. */
    private static final long CONFIRM_BASE_MS = 200L;
    /** Cells examined with the full viability test when choosing a rail cell. */
    private static final int MAX_VIABILITY_CHECKS = 24;
    /** Maximum distance at which the shot's ray must already meet the cart. */
    private static final double SHOT_RAY_RANGE = CrossbowShotGeometry.RANGE;

    // --- Settings ---
    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Legit", "Legit", "Silent")
            .setDescription("Legit turns your real camera to each rail, cart and fire spot. Silent turns only what the server sees and keeps your screen still.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 120.0, 10.0, 180.0, 0)
            .setDescription("Largest turn per tick, in degrees, toward each placement face. The rotation step limits still apply, and the plan accounts for them.");
    private final BooleanSetting autoAim = new BooleanSetting("Auto Aim", true)
            .setDescription("On: the module turns to each rail, cart and fire spot itself. Off: it never touches your aim and only acts while you are looking at a valid spot yourself (Rotation Mode is ignored and your real camera is used).");
    private final BooleanSetting smoothRotation = new BooleanSetting("Smooth Rotation", true)
            .setDescription("Eases the turn: fast while far from the spot, slowing smoothly as it closes in, instead of a constant-speed swing that stops dead. Aim Speed stays the top speed.");
    private final ModeSetting ignitionMode = new ModeSetting("Ignition Mode", "Flint", "Flint", "Lava", "Both")
            .setDescription("What lights the fire the crossbow arrow flies through: Flint (flint and steel), Lava (lava bucket), or Both (whichever you carry).");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Puts you back on the hotbar slot you were holding once the combo ends, unless you switched slots yourself.");
    private final BooleanSetting railTrigger = new BooleanSetting("Rail Trigger", false)
            .setDescription("Starts the combo itself the moment you place a rail with your own hand (holding a rail and right-clicking), so you do not need the bind for every run. The bind still works on its own for an existing rail or an empty spot. Never fires for a rail AutoCart, FastCart or this module placed, so it cannot double up with AutoCart.");
    private final BooleanSetting advanced = new BooleanSetting("Advanced", false)
            .setDescription("Shows the extra options below (safety block, double cart).");
    private final BooleanSetting placeSafetyBlock = new BooleanSetting("Place safety block", false)
            .setDescription("Places a block or slab between you and the cart first, so the explosion hits the target and not you.");
    private final ModeSetting blockMode = new ModeSetting("Block Mode", "Auto", "Auto", "Select")
            .setDescription("Auto picks the safety block from your hotbar by itself; Select always uses the hotbar slot set below.");
    private final SliderSetting blockSlot = new SliderSetting("Block Slot", 9.0, 1.0, 9.0, 1)
            .setDescription("Hotbar slot (1-9) holding your safety blocks when Block Mode is Select.");
    private final BooleanSetting doubleCart = new BooleanSetting("Double Cart", false)
            .setDescription("Stacks a second TNT minecart on the rail before lighting it, for a much bigger explosion. Needs two carts and takes one more step.");

    // --- State Machine ---
    public enum Stage {
        IDLE,
        RAIL,
        CART,
        CART_CONFIRM,
        IGNITE,
        IGNITE_CONFIRM,
        SAFETY_BLOCK,
        SHOOT,
        SHOOT_CONFIRM,
        RESTORE
    }

    private Stage stage = Stage.IDLE;
    private BlockPos targetRailPos = null;
    private BlockPos ignitionPos = null;
    private BlockPos safetyBlockPos = null;

    private int originalSlot = -1;
    private int expectedSlot = -1;
    private int railSlot = -1;
    private int tntCartSlot = -1;
    private int ignitionSlot = -1;
    private int safetyBlockSlot = -1;
    private int crossbowSlot = -1;

    private int cartsPlacedCount = 0;
    private int targetCartsCount = 1;
    private boolean isLavaIgnition = false;
    private long stageStartTimeMs = 0L;
    private long sequenceStartTimeMs = 0L;
    private long nextActionTimeMs = 0L;

    private int railRetries = 0;
    private int cartRetries = 0;
    private int igniteRetries = 0;
    private int replans = 0;
    private long unreachableSinceMs = 0L;
    private final Set<BlockPos> rejectedFireCells = new HashSet<>();

    private Vec3d aimPoint = null;
    private SubTickSequencer.FluentSequence pendingSequence = null;
    private boolean pendingResult = false;
    private ClientPlayerEntity sequencePlayer;
    private ClientWorld sequenceWorld;
    private long lastActionTick = -1L;
    private int shotRetries;
    private boolean finishing;
    /** Set by {@link #onRailPlaced} just before enabling; consumed once by {@link #onEnable}. */
    private BlockPos pendingTriggerPos;

    // --- Rotation ramp (ease-in/ease-out, not just a flat speed cap) ---
    private Vec3d rampAimPoint;
    private int rampTicks;

    public AutoXBowCart() {
        super("AutoXBowCart", "Runs the full crossbow cart combo: places a rail and TNT minecart at the target, lights fire next to it and fires your charged crossbow through the flames to detonate the cart, with every step aimed and paced like a real player.", Category.COMBAT);
        addSetting(railDelay);
        addSetting(cartDelay);
        addSetting(fireDelay);
        addSetting(blockDelay);
        addSetting(swapBackDelay);
        setSubCategory(SubCategory.CART);

        addSetting(rotationMode);
        addSetting(aimSpeed);
        addSetting(autoAim);
        addSetting(smoothRotation);
        rotationMode.setVisibility(autoAim::isEnabled);
        aimSpeed.setVisibility(autoAim::isEnabled);
        smoothRotation.setVisibility(autoAim::isEnabled);
        addSetting(ignitionMode);
        addSetting(switchBack);
        addSetting(railTrigger);
        addSetting(advanced);
        addSetting(placeSafetyBlock);
        addSetting(blockMode);
        addSetting(blockSlot);
        addSetting(doubleCart);
    }

    @Override
    public boolean isOneShot() {
        return true;
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        BlockPos triggered = pendingTriggerPos;
        resetState();
        boolean started = triggered != null ? beginSequenceAt(triggered) : tryStartSequence();
        if (!started) {
            setEnabled(false);
        }
    }

    /**
     * Rail Trigger: the player placed a rail with their own real click (never a module's own
     * placement; see {@link interstellarTikiTiks.apexx.client.module.hooks.RailPlacedAware}). Starts
     * the combo from that exact rail, the same as the bind does for an already-existing one.
     */
    @Override
    public void onRailPlaced(BlockPos pos) {
        if (!railTrigger.getValue() || pos == null || isEnabled() || stage != Stage.IDLE
                || mc == null || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || mc.player.isUsingItem() || !mc.player.isAlive()
                || otherModuleHoldsSwap()) {
            return;
        }
        // Never double up with AutoCart's own flame-bow cycle on the rail it is about to use.
        AutoCart autoCart = ModuleAccess.getEnabledModule(AutoCart.class);
        if (autoCart != null && autoCart.isBusy()) return;
        if (!isRailBlock(mc.world.getBlockState(pos)) || isUnderOrAtPlayer(pos)
                || nearestDistSq(new Box(pos), mc.player.getEyePos()) > reach() * reach()) {
            return;
        }
        this.pendingTriggerPos = pos.toImmutable();
        setEnabled(true);
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        abortAndRestore();
    }

    @Override
    protected void onRuntimeStateReleased() {
        abortAndRestore();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (stage != Stage.IDLE) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    @Override
    public void onTick() {
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.player != sequencePlayer || mc.world != sequenceWorld || !mc.player.isAlive()
                || mc.currentScreen != null || mc.player.isUsingItem()) {
            abortAndRestore();
            return;
        }
        if (stage == Stage.IDLE) return;
        if (expectedSlot >= 0 && mc.player.getInventory().getSelectedSlot() != expectedSlot) {
            fail("Hotbar selection changed during sequence");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sequenceStartTimeMs > SEQUENCE_TIMEOUT_MS + 4L * pingMs()) {
            fail("Sequence deadline exceeded");
            return;
        }
        if (pendingSequence != null) {
            if (!pendingSequence.isComplete()) return;
            consumePendingResult(now);
        }
        if (now - stageStartTimeMs > TIMEOUT_MS + 2L * pingMs()) {
            if (stage == Stage.SAFETY_BLOCK) {
                // The cover is optional: never abandon a lit cart because it could not be placed.
                clearUnreachable();
                setStage(Stage.SHOOT);
                return;
            }
            fail("Timed out in " + stage);
            return;
        }
        // Confirmation-only transitions can advance immediately. A new rotation/action still
        // starts on a later tick than the preceding action, and only in the action window.
        if (!TickActionOrder.isWindowOpen() || lastActionTick == SubTickSequencer.currentTick()) return;
        for (int transitions = 0; transitions < Stage.values().length; transitions++) {
            Stage before = stage;
            if (now < nextActionTimeMs && stage != Stage.CART_CONFIRM
                    && stage != Stage.IGNITE_CONFIRM && stage != Stage.SHOOT_CONFIRM) return;
            switch (stage) {
                case RAIL -> processRailStage();
                case CART -> processCartStage();
                case CART_CONFIRM -> processCartConfirmStage();
                case SAFETY_BLOCK -> processSafetyBlockStage();
                case IGNITE -> processIgniteStage();
                case IGNITE_CONFIRM -> processIgniteConfirmStage();
                case SHOOT -> processShootStage();
                case SHOOT_CONFIRM -> processShootConfirmStage();
                case RESTORE -> processRestoreStage();
                default -> { return; }
            }
            if (stage == Stage.IDLE || stage == before || pendingSequence != null
                    || lastActionTick == SubTickSequencer.currentTick()) return;
        }
    }

    private void consumePendingResult(long now) {
        boolean done = pendingResult;
        pendingSequence = null;
        pendingResult = false;
        if (done) completeStep(now);
    }

    @Override
    public boolean isSequenceActive() {
        return stage != Stage.IDLE;
    }

    public boolean tryStartSequence() {
        if (stage != Stage.IDLE || mc == null || mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.currentScreen != null || mc.player.isUsingItem()
                || !mc.player.isAlive() || otherModuleHoldsSwap()) return false;

        BlockPos targetPos = resolveTargetRailPosition(null);
        if (targetPos == null) {
            Notifier.warn(getName(), "No valid placement position in range");
            return false;
        }
        return beginSequenceAt(targetPos);
    }

    /**
     * Shared by the bind ({@link #tryStartSequence}, which first searches for a cell) and Rail
     * Trigger ({@link #onRailPlaced}, which already has one): the inventory checks and state reset
     * that start a run against a known target rail cell.
     */
    private boolean beginSequenceAt(BlockPos targetPos) {
        PlayerInventory inv = mc.player.getInventory();
        int cbSlot = findChargedCrossbowSlot(inv);
        if (cbSlot < 0) {
            Notifier.warn(getName(), "No crossbow loaded with arrows in hotbar");
            return false;
        }

        int cartSlot = findTntMinecartSlot(inv);
        if (cartSlot < 0) {
            Notifier.warn(getName(), "No TNT minecart in hotbar");
            return false;
        }

        int ignSlot = findIgnitionSlot(inv);
        if (ignSlot < 0) {
            Notifier.warn(getName(), "No ignition tool (flint & steel or lava) in hotbar");
            return false;
        }

        int rSlot = findRailSlot(inv, targetPos);
        boolean hasExistingRail = isRailBlock(mc.world.getBlockState(targetPos));
        if (!hasExistingRail && rSlot < 0) {
            Notifier.warn(getName(), "No rail in hotbar");
            return false;
        }

        int safetySlot = -1;
        if (placeSafetyBlock.getValue()) {
            safetySlot = findSafetyBlockSlot(inv);
            if (safetySlot < 0 && !advanced.getValue()) {
                Notifier.warn(getName(), "No safety block in hotbar");
                return false;
            }
        }

        this.sequencePlayer = mc.player;
        this.sequenceWorld = mc.world;
        this.sequenceStartTimeMs = System.currentTimeMillis();
        this.shotRetries = 0;
        this.lastActionTick = -1L;
        this.originalSlot = inv.getSelectedSlot();
        this.expectedSlot = -1;
        this.crossbowSlot = cbSlot;
        this.tntCartSlot = cartSlot;
        this.ignitionSlot = ignSlot;
        this.railSlot = rSlot;
        this.safetyBlockSlot = safetySlot;
        this.targetRailPos = targetPos;

        this.targetCartsCount = (doubleCart.getValue() && countTntMinecarts(inv) >= 2) ? 2 : 1;
        this.cartsPlacedCount = 0;
        this.nextActionTimeMs = 0L;
        this.railRetries = 0;
        this.cartRetries = 0;
        this.igniteRetries = 0;
        this.replans = 0;
        this.unreachableSinceMs = 0L;
        this.rejectedFireCells.clear();
        this.ignitionPos = null;
        this.safetyBlockPos = null;
        this.rampAimPoint = null;
        this.rampTicks = 0;

        if (hasExistingRail) {
            setStage(Stage.CART);
        } else {
            setStage(Stage.RAIL);
        }
        return true;
    }

    // --- State Handlers ---

    private void processRailStage() {
        if (mc.world == null || mc.player == null || this.targetRailPos == null) {
            abortAndRestore();
            return;
        }
        BlockPos rail = this.targetRailPos;

        if (isRailBlock(mc.world.getBlockState(rail))) {
            clearUnreachable();
            setStage(Stage.CART);
            return;
        }
        if (this.railSlot < 0) {
            abortAndRestore();
            return;
        }
        ItemStack railStack = mc.player.getInventory().getStack(railSlot);
        if (!(railStack.getItem() instanceof BlockItem block) || !(block.getBlock() instanceof AbstractRailBlock)) {
            railSlot = findRailSlot(mc.player.getInventory(), rail);
            if (railSlot < 0) { fail("Rail stack depleted or changed"); return; }
        }

        // The player may have moved onto the cell, or the cell changed, since it was chosen.
        BlockPos support = rail.down();
        AutoCartPlanner.Aim aim = null;
        if (!isUnderOrAtPlayer(rail) && isAirOrReplaceable(rail) && isValidSupport(support)) {
            aim = AutoCartPlanner.faceAim(mc.world, mc.player, mc.player.getEyePos(), support, Direction.UP, rail, lookYaw());
        }
        if (aim == null) {
            if (unreachableExpired() && !replanTarget()) {
                abortAndRestore();
            }
            return;
        }
        clearUnreachable();
        attemptPlacement(this.railSlot, aim.hit(), aim.point(),
                stack -> stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof AbstractRailBlock);
    }

    private void processCartStage() {
        if (mc.world == null || mc.player == null || this.targetRailPos == null) {
            abortAndRestore();
            return;
        }
        BlockPos rail = this.targetRailPos;

        if (!isRailBlock(mc.world.getBlockState(rail))) {
            // The server may not have kept the rail: place it again rather than a cart on nothing.
            if (this.railRetries++ < MAX_STAGE_RETRIES && this.railSlot >= 0) {
                setStage(Stage.RAIL);
            } else {
                abortAndRestore();
            }
            return;
        }
        if (!stationaryRail(rail)) { fail("Rail became sloped, powered or waterlogged"); return; }
        if (isUnderOrAtPlayer(rail) || this.tntCartSlot < 0) {
            abortAndRestore();
            return;
        }

        if (countCartsOnRail(rail) >= targetCartsCount) {
            setStage(Stage.CART_CONFIRM);
            return;
        }
        if (!mc.player.getInventory().getStack(tntCartSlot).isOf(Items.TNT_MINECART)) {
            tntCartSlot = findTntMinecartSlot(mc.player.getInventory());
            if (tntCartSlot < 0) { fail("TNT minecart stack depleted"); return; }
        }
        AutoCartPlanner.Aim aim = AutoCartPlanner.insideAim(mc.world, mc.player, mc.player.getEyePos(), rail, 0.06, lookYaw());
        if (aim == null) {
            if (unreachableExpired()) abortAndRestore();
            return;
        }
        clearUnreachable();
        attemptPlacement(this.tntCartSlot, aim.hit(), aim.point(), stack -> stack.isOf(Items.TNT_MINECART));
    }

    private void processCartConfirmStage() {
        if (mc.world == null || this.targetRailPos == null) {
            abortAndRestore();
            return;
        }

        int onRail = countCartsOnRail(this.targetRailPos);
        if (onRail >= this.targetCartsCount) {
            // Fire first: with the cover up, the fire cell's support face may no longer be clickable.
            setStage(Stage.IGNITE);
            return;
        }
        // A spawn that is merely late must not read as a refused cart, or a second one is placed.
        if (System.currentTimeMillis() - this.stageStartTimeMs > 2L * confirmWindowMs() + 300L) {
            // The spawn never showed up: the server refused a cart. Place the missing ones again.
            if (this.cartRetries++ < MAX_STAGE_RETRIES) {
                this.cartsPlacedCount = onRail;
                setStage(Stage.CART);
            } else {
                abortAndRestore();
            }
        }
    }

    private void processSafetyBlockStage() {
        if (mc.player == null || mc.world == null || this.targetRailPos == null || this.safetyBlockSlot < 0) {
            setStage(Stage.SHOOT);
            return;
        }

        BlockPos blastShieldPos = this.safetyBlockPos != null ? this.safetyBlockPos : resolveSafetyBlockPosition(this.targetRailPos);
        if (blastShieldPos == null || !isAirOrReplaceable(blastShieldPos)) {
            setStage(Stage.SHOOT);
            return;
        }

        this.safetyBlockPos = blastShieldPos;
        BlockPos support = blastShieldPos.down();
        AutoCartPlanner.Aim aim = AutoCartPlanner.faceAim(mc.world, mc.player, mc.player.getEyePos(), support, Direction.UP,
                blastShieldPos, lookYaw());
        if (aim == null) {
            // Optional cover: if it cannot be clicked within the buffer, go on without it.
            if (unreachableExpired()) {
                clearUnreachable();
                setStage(Stage.SHOOT);
            }
            return;
        }
        clearUnreachable();
        attemptPlacement(this.safetyBlockSlot, aim.hit(), aim.point(), stack -> stack.getItem() instanceof BlockItem);
    }

    private void processIgniteStage() {
        if (mc.player == null || mc.world == null || this.targetRailPos == null || this.ignitionSlot < 0) {
            abortAndRestore();
            return;
        }

        BlockPos firePos = this.ignitionPos;
        if (firePos == null || !canHoldFire(firePos)) {
            firePos = resolveIgnitionTargetBlock(this.targetRailPos);
        }
        if (firePos == null) {
            abortAndRestore();
            return;
        }
        this.ignitionPos = firePos;

        BlockPos support = firePos.down();
        AutoCartPlanner.Aim aim = AutoCartPlanner.faceAim(mc.world, mc.player, mc.player.getEyePos(), support, Direction.UP,
                firePos, lookYaw());
        if (aim == null) {
            if (unreachableExpired()) {
                clearUnreachable();
                this.rejectedFireCells.add(firePos);
                this.ignitionPos = null;
                if (this.rejectedFireCells.size() > MAX_STAGE_RETRIES) abortAndRestore();
            }
            return;
        }
        clearUnreachable();
        if (this.isLavaIgnition) {
            // Buckets act through item use along the live look, not through a block interaction.
            BlockHitResult igniteHit = aim.hit();
            attemptAction(this.ignitionSlot, aim.point(),
                    () -> BlockPlacementValidator.validateHit(mc, igniteHit, isSilent()) != null,
                    () -> mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET) && useHeldItem());
        } else {
            attemptPlacement(this.ignitionSlot, aim.hit(), aim.point(), stack -> stack.isOf(Items.FLINT_AND_STEEL));
        }
    }

    private void processIgniteConfirmStage() {
        if (mc.world == null || this.targetRailPos == null) {
            abortAndRestore();
            return;
        }

        if (fireStillBurning()) {
            setStage(placeSafetyBlock.getValue() && this.safetyBlockSlot >= 0 ? Stage.SAFETY_BLOCK : Stage.SHOOT);
            return;
        }
        if (System.currentTimeMillis() - this.stageStartTimeMs > confirmWindowMs()) {
            // No fire means the arrow would not light the cart: place it again, never shoot dry.
            if (this.igniteRetries++ < MAX_STAGE_RETRIES) {
                setStage(Stage.IGNITE);
            } else {
                abortAndRestore();
            }
        }
    }

    private void processShootStage() {
        if (mc.player == null || mc.world == null || targetRailPos == null || crossbowSlot < 0) {
            abortAndRestore();
            return;
        }
        if (!isArrowCrossbow(mc.player.getInventory().getStack(crossbowSlot))) {
            fail("Charged arrow crossbow changed or was consumed");
            return;
        }
        if (!fireStillBurning()) {
            if (igniteRetries++ < MAX_STAGE_RETRIES) setStage(Stage.IGNITE);
            else fail("Ignition disappeared before shot");
            return;
        }
        if (!InteractSim.useReady(mc)) return;
        List<TntMinecartEntity> carts = liveCarts();
        if (carts.isEmpty()) { fail("Cart disappeared before shot"); return; }
        Vec3d aim = shotAimPoint(carts);
        if (aim == null) {
            if (unreachableExpired()) fail("No unobstructed burning-arrow path to cart");
            return;
        }
        clearUnreachable();
        attemptAction(crossbowSlot, aim, this::liveShotEligible,
                () -> isArrowCrossbow(mc.player.getMainHandStack()) && useHeldItem());
    }

    private void processShootConfirmStage() {
        // A queued click is not a shot. Observe the server's charged-projectile update before
        // finishing; a missing cart alone does not establish that our arrow was fired.
        ItemStack stack = mc.player.getInventory().getStack(crossbowSlot);
        if (!(stack.getItem() instanceof CrossbowItem)) {
            fail("Crossbow removed while awaiting confirmation");
        } else if (!CrossbowItem.isCharged(stack)) {
            setStage(Stage.RESTORE);
        } else if (System.currentTimeMillis() - stageStartTimeMs > confirmWindowMs()) {
            if (shotRetries++ < MAX_STAGE_RETRIES) setStage(Stage.SHOOT);
            else fail("Crossbow use was not confirmed");
        }
    }

    private List<TntMinecartEntity> liveCarts() {
        if (mc.world == null || targetRailPos == null) return List.of();
        Box cell = new Box(targetRailPos);
        return mc.world.getEntitiesByClass(TntMinecartEntity.class, cell,
                cart -> cart.isAlive() && cart.getBoundingBox().intersects(cell));
    }

    private Vec3d arrowOrigin() {
        return mc.player.getEyePos().add(0.0, -(double) 0.1F, 0.0);
    }

    /** Aim inside the observed box; never at a guessed rail-cell box or its grazing edge. */
    private Vec3d shotAimPoint(List<TntMinecartEntity> carts) {
        return shotAimPoint(carts, null);
    }

    private Vec3d shotAimPoint(List<TntMinecartEntity> carts, Box extraObstacle) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d origin = arrowOrigin();
        Box fire = ignitionBox();
        if (fire == null) return null;
        for (TntMinecartEntity cart : carts) {
            Box box = cart.getBoundingBox();
            Vec3d center = box.getCenter();
            double[] samples = {0.5, 0.3, 0.7};
            for (double y : samples) {
                for (double x : samples) {
                    for (double z : samples) {
                        Vec3d target = new Vec3d(MathHelper.lerp(x, box.minX, box.maxX),
                                MathHelper.lerp(y, box.minY, box.maxY), MathHelper.lerp(z, box.minZ, box.maxZ));
                        double ticks = origin.distanceTo(target) / CrossbowShotGeometry.SPEED;
                        Vec3d direction = target.add(0.0, ticks > 1.0 ? 0.05 * (ticks - 1.0) : 0.0, 0.0).subtract(origin).normalize();
                        if (box.raycast(eye, eye.add(direction.multiply(SHOT_RAY_RANGE))).isEmpty()) continue;
                        if (shotClear(origin, direction, box, fire, false, extraObstacle)
                                && shotClear(origin, direction, box, fire, true, extraObstacle)) {
                            return eye.add(direction.multiply(eye.distanceTo(center)));
                        }
                    }
                }
            }
        }
        return null;
    }

    private Box ignitionBox() {
        if (ignitionPos == null || !fireStillBurning()) return null;
        Box cell = new Box(ignitionPos).expand(-0.05);
        // Use the fluid's live height, rather than treating every lava surface as a full cube.
        if (mc.world.getBlockState(ignitionPos).isOf(Blocks.LAVA)) {
            double top = ignitionPos.getY() + mc.world.getFluidState(ignitionPos).getHeight(mc.world, ignitionPos) - 0.02;
            if (top <= cell.minY) return null;
            cell = new Box(cell.minX, cell.minY, cell.minZ, cell.maxX, top, cell.maxZ);
        }
        return cell;
    }

    private boolean liveShotEligible() {
        Box fire = ignitionBox();
        if (fire == null || !InteractSim.useReady(mc)) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = isSilent() && RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentRotationVec() : mc.player.getRotationVec(1.0f);
        for (TntMinecartEntity cart : liveCarts()) {
            Box box = cart.getBoundingBox();
            if (box.raycast(eye, eye.add(look.multiply(SHOT_RAY_RANGE))).isPresent()
                    && shotClear(arrowOrigin(), look, box, fire, true)) return true;
        }
        return false;
    }

    private boolean shotClear(Vec3d origin, Vec3d direction, Box cart, Box fire, boolean probeSpread) {
        return shotClear(origin, direction, cart, fire, probeSpread, null);
    }

    /** {@code extra} is a block that does not exist yet (the planned cover) and must stay out of the arrow's way. */
    private boolean shotClear(Vec3d origin, Vec3d direction, Box cart, Box fire, boolean probeSpread, Box extra) {
        List<Vec3d> directions = probeSpread ? CrossbowShotGeometry.spreadDirections(direction) : List.of(direction);
        for (Vec3d probe : directions) {
            CrossbowShotGeometry.Shot shot = CrossbowShotGeometry.trace(origin, probe, cart, fire);
            if (shot == null) return false;
            for (CrossbowShotGeometry.Segment segment : shot.segments()) {
                if (extra != null && (extra.contains(segment.from())
                        || extra.expand(0.15).raycast(segment.from(), segment.to()).isPresent())) return false;
                var block = mc.world.raycast(new RaycastContext(segment.from(), segment.to(),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                if (block.getType() == HitResult.Type.BLOCK) return false;
                Box swept = new Box(segment.from(), segment.to()).expand(0.3);
                for (Entity entity : mc.world.getOtherEntities(mc.player, swept,
                        entity -> entity.isAlive() && entity.canBeHitByProjectile()
                                && !(entity instanceof TntMinecartEntity && entity.getBoundingBox().equals(cart)))) {
                    if (entity.getBoundingBox().raycast(segment.from(), segment.to()).isPresent()) return false;
                }
                int steps = Math.max(1, (int) Math.ceil(segment.from().distanceTo(segment.to()) * 8.0));
                for (int i = 0; i <= steps; i++) {
                    BlockPos cell = BlockPos.ofFloored(segment.from().lerp(segment.to(), (double) i / steps));
                    if (!mc.world.isChunkLoaded(cell) || mc.world.getFluidState(cell).isIn(FluidTags.WATER)
                            || mc.world.hasRain(cell)) return false;
                }
            }
        }
        return true;
    }

    private void processRestoreStage() {
        abortAndRestore();
    }

    private void completeStep(long now) {
        SliderSetting wait = switch (this.stage) {
            case RAIL -> railDelay;
            case CART -> cartDelay;
            case IGNITE -> fireDelay;
            case SAFETY_BLOCK -> blockDelay;
            // After the shot the next gated stage is RESTORE: this is the swap-back wait.
            case SHOOT -> swapBackDelay;
            default -> null;
        };
        this.nextActionTimeMs = wait == null ? now : now + ModuleDelays.clampMs(wait.sample());
        switch (this.stage) {
            case RAIL -> setStage(Stage.CART);
            case CART -> {
                this.cartsPlacedCount++;
                if (this.cartsPlacedCount >= this.targetCartsCount) {
                    setStage(Stage.CART_CONFIRM);
                }
            }
            case SAFETY_BLOCK -> setStage(Stage.SHOOT);
            case IGNITE -> setStage(Stage.IGNITE_CONFIRM);
            case SHOOT -> setStage(Stage.SHOOT_CONFIRM);
            default -> { }
        }
    }

    // --- Interaction & Math Utilities ---

    /** One owner-scoped placement-rotation step toward the current aim point; commits immediately. */
    private boolean step() {
        if (aimPoint == null || mc.player == null) return false;
        if (!autoAim.isEnabled()) return true;
        if (rampAimPoint == null || rampAimPoint.squaredDistanceTo(aimPoint) > 1.0e-4) {
            // A genuinely new target (new placement face/cell): the ramp restarts from a standstill.
            rampAimPoint = aimPoint;
            rampTicks = 0;
        } else {
            rampTicks++;
        }
        float[] goal = AutoCartPlanner.anglesTo(mc.player.getEyePos(), aimPoint, lookYaw());
        return RotationManager.stepAim(this, RotationManager.Priority.HIGH, goal[0], goal[1],
                stepBudget(goal[0], goal[1]), isSilent());
    }

    /**
     * How far one tick may turn: an ease-in/ease-out curve, not a flat speed cap. A real turn
     * accelerates into its top speed over the first few ticks (rather than snapping to it on tick
     * one) and decelerates as it nears the target, with a touch of random variance so consecutive
     * turns are not an identical curve. Aim Speed stays the hard ceiling throughout.
     */
    private float stepBudget(float yaw, float pitch) {
        double top = Math.max(1.0, aimSpeed.getValue());
        if (!smoothRotation.isEnabled()) return (float) top;
        float dy = net.minecraft.util.math.MathHelper.wrapDegrees(yaw - lookYaw());
        float dp = pitch - lookPitch();
        double distance = Math.sqrt(dy * dy + dp * dp);
        double easeOut = Math.max(2.5, distance * 0.4);
        double easeIn = top * Math.min(1.0, 0.35 + 0.22 * rampTicks);
        double budget = Math.min(top, Math.min(easeOut, easeIn));
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.08;
        return (float) Math.max(1.0, budget * jitter);
    }

    private float lookYaw() {
        return RotationManager.getFinalYaw();
    }

    private float lookPitch() {
        return RotationManager.getFinalPitch();
    }

    private void attemptPlacement(int slot, BlockHitResult intended, Vec3d aim, Predicate<ItemStack> expectedItem) {
        boolean silent = isSilent();
        attemptAction(slot, aim,
                () -> BlockPlacementValidator.validateHit(mc, intended, silent) != null,
                () -> {
                    if (!expectedItem.test(mc.player.getMainHandStack())) return false;
                    BlockHitResult hit = BlockPlacementValidator.validateHit(mc, intended, silent);
                    if (hit == null) return false;
                    return InteractSim.interactBlockOncePerTick(mc, hit, silent);
                });
    }

    /**
     * Keeps the owner-scoped aim on {@code aim}; once {@code eligible} holds for the live ray, selects
     * {@code slot} and runs {@code action} later in this same tick.
     */
    private void attemptAction(int slot, Vec3d aim, BooleanSupplier eligible, BooleanSupplier action) {
        if (slot < 0 || slot > 8) {
            abortAndRestore();
            return;
        }
        this.aimPoint = aim;
        if (!step()) {
            // Another module holds the rotation. Do not sit on the wrong angle until the stage times out:
            // optional cover is dropped, anything else ends the sequence with the reason.
            if (unreachableExpired()) {
                clearUnreachable();
                if (stage == Stage.SAFETY_BLOCK) setStage(Stage.SHOOT);
                else fail("Rotation held by another module in " + stage);
            }
            return;
        }
        if (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()
                || lastActionTick == SubTickSequencer.currentTick() || otherModuleHoldsSwap() || !eligible.getAsBoolean()) {
            return;
        }
        this.pendingResult = false;
        final long tick = SubTickSequencer.currentTick();
        final Stage actionStage = stage;
        final boolean changingSlot = mc.player.getInventory().getSelectedSlot() != slot;
        try {
            this.pendingSequence = SubTickSequencer.sequence(mc, this)
                    .selectSlot(slot)
                    .thenDelay()
                    .action(() -> {
                        if (mc.player != sequencePlayer || mc.world != sequenceWorld || stage != actionStage
                                || tick != SubTickSequencer.currentTick() || !TickActionOrder.isWindowOpen()
                                || mc.currentScreen != null || mc.player.isUsingItem()
                                || mc.player.getInventory().getSelectedSlot() != slot || otherModuleHoldsSwap()) return;
                        expectedSlot = slot;
                        if (!eligible.getAsBoolean()) return;
                        if (changingSlot) SubTickSequencer.awaitWireSeparation(mc);
                        if (tick != SubTickSequencer.currentTick() || !eligible.getAsBoolean()) return;
                        // Even an unsuccessful vanilla use can send a packet. Do not retry in this tick.
                        lastActionTick = tick;
                        pendingResult = action.getAsBoolean();
                        if (pendingResult) RotationManager.setPostAttackHold(this, 1, lookYaw(), lookPitch());
                    });
            this.pendingSequence.start();
            if (mc.player == sequencePlayer && mc.player.getInventory().getSelectedSlot() == slot) expectedSlot = slot;
            if (pendingSequence.isComplete()) consumePendingResult(System.currentTimeMillis());
        } catch (Throwable failure) {
            ClientInternalLog.error(getName(), "Dispatch failed in " + stage, failure);
            abortAndRestore();
        }
    }

    /** Uses the held item through the shared seam: normal input in Legit, the silent path in Silent. */
    private boolean useHeldItem() {
        boolean silent = isSilent() && RotationManager.isSilentRotationActive();
        float yaw = silent ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = silent ? RotationManager.getFinalPitch() : mc.player.getPitch();
        if (silent) return InteractSim.useItem(mc, Hand.MAIN_HAND, yaw, pitch, true);
        if (!InteractSim.useReady(mc) || mc.gameRenderer == null || mc.player.isRiding()
                || mc.interactionManager.isBreakingBlock()) return false;
        // Synchronous vanilla use through the shared execution-mode seam. Completion is
        // determined by the cart/fire/charged-projectile state, not the void click return.
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        ClickSimController.useNow(mc);
        return true;
    }

    private boolean fireStillBurning() {
        if (mc.world == null || this.ignitionPos == null) return false;
        BlockState state = mc.world.getBlockState(this.ignitionPos);
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.LAVA);
    }

    private long pingMs() {
        return Math.max(0L, Math.min(2000L, (long) (WebHitModel.rttTicks(mc) * 50.0)));
    }

    /** How long a placement may stay unconfirmed: a round trip plus slack for the entity spawn or block update. */
    private long confirmWindowMs() {
        return CONFIRM_BASE_MS + pingMs();
    }

    private boolean unreachableExpired() {
        long now = System.currentTimeMillis();
        if (this.unreachableSinceMs == 0L) this.unreachableSinceMs = now;
        return now - this.unreachableSinceMs > UNREACHABLE_BUFFER_MS + pingMs();
    }

    private void clearUnreachable() {
        this.unreachableSinceMs = 0L;
    }

    /** Picks another rail cell when the first became unreachable before any rail was placed. */
    private boolean replanTarget() {
        if (this.replans >= MAX_REPLANS || this.targetRailPos == null) return false;
        BlockPos next = resolveTargetRailPosition(this.targetRailPos);
        if (next == null || isRailBlock(mc.world.getBlockState(this.targetRailPos))) return false;
        this.replans++;
        this.targetRailPos = next;
        this.railSlot = findRailSlot(mc.player.getInventory(), next);
        this.ignitionPos = null;
        this.safetyBlockPos = null;
        this.rejectedFireCells.clear();
        clearUnreachable();
        return true;
    }

    private boolean isSilent() {
        return autoAim.isEnabled() && "Silent".equalsIgnoreCase(rotationMode.getCurrentMode());
    }

    /** Another module (shield break, mace chain, ...) owns the hotbar right now. */
    private boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private void setStage(Stage nextStage) {
        this.stage = nextStage;
        this.stageStartTimeMs = System.currentTimeMillis();
    }

    private void fail(String reason) {
        ClientInternalLog.warn(getName(), reason + " [stage=" + stage + ", tick=" + SubTickSequencer.currentTick() + "]");
        abortAndRestore();
    }

    private void abortAndRestore() {
        if (finishing) return;
        finishing = true;
        try {
            SubTickSequencer.cancelOwner(this);
            restoreOwnedSlot();
            resetState();
            if (isEnabled()) setEnabled(false);
        } finally {
            finishing = false;
        }
    }

    /** Restores the pre-sequence slot only while the selection is still the one this module made. */
    private void restoreOwnedSlot() {
        if (switchBack.getValue() && this.originalSlot >= 0 && this.originalSlot <= 8 && this.expectedSlot >= 0
                && mc != null && mc.player != null && mc.player == sequencePlayer && mc.world == sequenceWorld
                && mc.player.getInventory().getSelectedSlot() == this.expectedSlot) {
            SlotSync.restore(mc, this.originalSlot);
        }
    }

    private void resetState() {
        if (this.stage != Stage.IDLE) lastActiveMs = System.currentTimeMillis();
        RotationManager.stop(this);
        if (this.pendingSequence != null && !this.pendingSequence.isComplete()) {
            SubTickSequencer.cancelOwner(this);
        }
        this.pendingSequence = null;
        this.pendingResult = false;
        this.stage = Stage.IDLE;
        this.targetRailPos = null;
        this.ignitionPos = null;
        this.safetyBlockPos = null;
        this.aimPoint = null;
        this.originalSlot = -1;
        this.expectedSlot = -1;
        this.railSlot = -1;
        this.tntCartSlot = -1;
        this.ignitionSlot = -1;
        this.safetyBlockSlot = -1;
        this.crossbowSlot = -1;
        this.cartsPlacedCount = 0;
        this.targetCartsCount = 1;
        this.sequencePlayer = null;
        this.sequenceWorld = null;
        this.lastActionTick = -1L;
        this.shotRetries = 0;
        this.stageStartTimeMs = 0L;
        this.sequenceStartTimeMs = 0L;
        this.nextActionTimeMs = 0L;
        SlotSync.releaseOwner(this);
        this.railRetries = 0;
        this.cartRetries = 0;
        this.igniteRetries = 0;
        this.replans = 0;
        this.unreachableSinceMs = 0L;
        this.rejectedFireCells.clear();
        this.rampAimPoint = null;
        this.rampTicks = 0;
        this.pendingTriggerPos = null;
    }

    private static volatile long lastActiveMs = 0L;

    /** Milliseconds since a sequence last ended, or Long.MAX_VALUE if none has run. */
    public static long msSinceLastSequence() {
        return lastActiveMs == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - lastActiveMs;
    }

    /** True while a rail/cart/ignition sequence is running; CartRefill waits for it. */
    public boolean isBusy() {
        return this.stage != Stage.IDLE;
    }

    private double reach() {
        return mc.player == null ? MAX_REACH : Math.min(MAX_REACH, mc.player.getBlockInteractionRange());
    }

    /** Squared distance from {@code eye} to the nearest point of {@code box}. */
    private static double nearestDistSq(Box box, Vec3d eye) {
        double dx = Math.max(Math.max(box.minX - eye.x, 0.0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0.0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0.0), eye.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The rail cell for this run: the cell under the crosshair if it can take the whole sequence,
     * else the best cell around the player in any direction (diagonals included). Cells are tried in
     * order of how little turning they need and how close they sit to a safe distance; the full test
     * (rail clickable, in reach, line of fire, fire cell) is run on the most promising ones.
     *
     * @param exclude a cell that failed and must not be chosen again
     */
    private BlockPos resolveTargetRailPosition(BlockPos exclude) {
        if (mc.player == null || mc.world == null) return null;

        // 1. Crosshair candidate
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos base = bhr.getBlockPos();
            BlockPos above = base.offset(bhr.getSide());
            if (isRailBlock(mc.world.getBlockState(base)) && !base.equals(exclude) && viableRailCell(base, true)) {
                return base.toImmutable();
            }
            if (!above.equals(exclude) && viableRailCell(above, false)) {
                return above.toImmutable();
            }
        }

        // 2. Search around the player. Never the player's own column: a cart at or below the feet
        // detonates on the player; cells right beside it come last.
        Vec3d look = mc.player.getRotationVec(1.0F);
        double flatLen = Math.hypot(look.x, look.z);
        Direction facing = mc.player.getHorizontalFacing();
        double lx = flatLen > 0.1 ? look.x / flatLen : facing.getOffsetX();
        double lz = flatLen > 0.1 ? look.z / flatLen : facing.getOffsetZ();
        BlockPos feet = mc.player.getBlockPos();
        int radius = (int) Math.ceil(reach());
        List<BlockPos> cells = new ArrayList<>();
        java.util.Map<BlockPos, Double> costs = new java.util.HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = Math.hypot(dx, dz);
                if (flat < 0.9 || flat > reach()) continue;
                double angle = Math.toDegrees(Math.acos(MathHelper.clamp((dx * lx + dz * lz) / flat, -1.0, 1.0)));
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos cell = feet.add(dx, dy, dz);
                    if (cell.equals(exclude)) continue;
                    double cost = angle + Math.abs(flat - 2.5) * 8.0 + Math.abs(dy) * 4.0 + (flat < 1.5 ? 40.0 : 0.0);
                    cells.add(cell);
                    costs.put(cell, cost);
                }
            }
        }
        cells.sort(Comparator.comparingDouble(costs::get));
        int checked = 0;
        for (BlockPos cell : cells) {
            if (checked >= MAX_VIABILITY_CHECKS) break;
            if (isUnderOrAtPlayer(cell) || (!isAirOrReplaceable(cell) && !isRailBlock(mc.world.getBlockState(cell)))
                    || !isValidSupport(cell.down())) continue;
            checked++;
            if (viableRailCell(cell, isRailBlock(mc.world.getBlockState(cell)))) return cell.toImmutable();
        }
        return null;
    }

    /**
     * A cell that can take the whole sequence: a rail can be placed there (or is), the player is not
     * in its column, its support face and the cart are within reach, the shot to the cart is not
     * blocked, and a fire cell exists on that shot.
     */
    private boolean viableRailCell(BlockPos cell, boolean hasRail) {
        if (mc.player == null || mc.world == null || isUnderOrAtPlayer(cell)) return false;
        Vec3d eye = mc.player.getEyePos();
        double reachSq = reach() * reach();
        if (hasRail && !stationaryRail(cell)) return false;
        if (!hasRail) {
            if (findRailSlot(mc.player.getInventory(), cell) < 0) return false;
            if (!AutoCartPlanner.railCellUsable(mc.world, cell)) return false;
            if (AutoCartPlanner.faceAim(mc.world, mc.player, eye, cell.down(), Direction.UP, cell, lookYaw()) == null) return false;
        }
        if (nearestDistSq(new Box(cell), eye) > reachSq) return false;
        Vec3d cartCenter = new Vec3d(cell.getX() + 0.5, cell.getY() + 0.38, cell.getZ() + 0.5);
        var shot = mc.world.raycast(new RaycastContext(eye, cartCenter, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        if (shot.getType() == HitResult.Type.BLOCK && !shot.getBlockPos().equals(cell)) return false;
        return resolveIgnitionTargetBlock(cell) != null;
    }

    /**
     * True when the rail cell shares the player's horizontal footprint (with a small margin for
     * movement) at or below head height, i.e. the rail or its cart would sit at or under the player.
     */
    private boolean isUnderOrAtPlayer(BlockPos cell) {
        if (mc.player == null) return false;
        Box bb = mc.player.getBoundingBox().expand(0.3, 0.0, 0.3);
        boolean overlapsColumn = bb.maxX > cell.getX() && bb.minX < cell.getX() + 1
                && bb.maxZ > cell.getZ() && bb.minZ < cell.getZ() + 1;
        return overlapsColumn && cell.getY() <= MathHelper.floor(mc.player.getEyeY());
    }

    /** Cells the segment {@code from} to {@code to} passes through, in order from {@code from}. */
    private static List<BlockPos> cellsOnSegment(Vec3d from, Vec3d to) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        double length = from.distanceTo(to);
        int samples = Math.max(2, (int) Math.ceil(length / 0.05));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            cells.add(BlockPos.ofFloored(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t,
                    from.z + (to.z - from.z) * t));
        }
        return new ArrayList<>(cells);
    }

    /**
     * The fire goes in a cell the shot really crosses on its way from your eyes to the cart, so the
     * arrow catches fire before it hits: that is a side cell for a straight shot and a diagonal cell
     * for a diagonal one. Cells nearest the cart come first, and only a cell whose supporting face
     * can be clicked from here is chosen. When the line clips no usable cell (for example when you
     * stand very close), the neighbor facing you is the last resort.
     */
    private BlockPos resolveIgnitionTargetBlock(BlockPos railPos) {
        if (mc.world == null || mc.player == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d cartCenter = new Vec3d(railPos.getX() + 0.5, railPos.getY() + 0.38, railPos.getZ() + 0.5);
        Vec3d lowEye = arrowOrigin();
        for (BlockPos cell : cellsOnSegment(cartCenter, eye)) {
            if (cell.equals(railPos) || this.rejectedFireCells.contains(cell) || !canHoldFire(cell)) continue;
            // Both the eye line and the arrow's spawn line (a tenth below the eye) must clip the cell.
            Box core = new Box(cell).expand(-0.08);
            if (core.raycast(eye, cartCenter).isEmpty() || core.raycast(lowEye, cartCenter).isEmpty()) continue;
            if (AutoCartPlanner.faceAim(mc.world, mc.player, eye, cell.down(), Direction.UP, cell, lookYaw()) == null) continue;
            return cell.toImmutable();
        }

        return null;
    }

    private boolean canHoldFire(BlockPos cell) {
        return isAirOrReplaceable(cell) && isValidSupport(cell.down())
                && !mc.player.getBoundingBox().intersects(new Box(cell))
                && (this.safetyBlockPos == null || !this.safetyBlockPos.equals(cell));
    }

    /**
     * The cell beside the rail on the side facing you (any of the eight, so a diagonal blast is
     * covered by a diagonal block) that can hold the cover block.
     */
    private BlockPos resolveSafetyBlockPosition(BlockPos railPos) {
        if (mc.player == null || mc.world == null) return null;
        // The fire is already placed by now; it is the cell the cover must leave alone.
        BlockPos fireCell = this.ignitionPos != null ? this.ignitionPos : resolveIgnitionTargetBlock(railPos);
        if (fireCell == null) return null;
        double px = mc.player.getX() - (railPos.getX() + 0.5);
        double pz = mc.player.getZ() - (railPos.getZ() + 0.5);
        double plen = Math.hypot(px, pz);
        if (plen < 1.0e-4) return null;
        BlockPos best = null;
        double bestAlign = 0.3;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = railPos.add(dx, 0, dz);
                if (candidate.equals(fireCell)) continue;
                if (!isAirOrReplaceable(candidate) || !isValidSupport(candidate.down())
                        || mc.player.getBoundingBox().intersects(new Box(candidate))) continue;
                double align = (dx * px + dz * pz) / (Math.hypot(dx, dz) * plen);
                ItemStack cover = mc.player.getInventory().getStack(safetyBlockSlot);
                double height = cover.getItem() instanceof BlockItem block && block.getBlock() instanceof SlabBlock ? 0.5 : 1.0;
                Box coverBox = new Box(candidate.getX(), candidate.getY(), candidate.getZ(),
                        candidate.getX() + 1.0, candidate.getY() + height, candidate.getZ() + 1.0);
                Vec3d center = new Vec3d(railPos.getX() + 0.5, railPos.getY() + 0.4, railPos.getZ() + 0.5);
                if (coverBox.raycast(arrowOrigin(), center).isPresent()) continue;
                // The cover must leave a real, validated shot: the same search the shot stage will run,
                // with the cover as an obstacle. Otherwise the cover is placed and no shot exists.
                List<TntMinecartEntity> carts = liveCarts();
                if (!carts.isEmpty() && shotAimPoint(carts, coverBox) == null) continue;
                if (AutoCartPlanner.faceAim(mc.world, mc.player, mc.player.getEyePos(), candidate.down(),
                        Direction.UP, candidate, lookYaw()) == null) continue;
                if (align > bestAlign) {
                    bestAlign = align;
                    best = candidate;
                }
            }
        }
        return best == null ? null : best.toImmutable();
    }

    private int countCartsOnRail(BlockPos railPos) {
        if (mc.world == null) return 0;
        Box checkArea = new Box(railPos);
        List<TntMinecartEntity> carts = mc.world.getEntitiesByClass(TntMinecartEntity.class, checkArea,
                cart -> cart.isAlive() && cart.getBoundingBox().intersects(checkArea));
        return carts.size();
    }

    private boolean isValidSupport(BlockPos pos) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && state.isSolidBlock(mc.world, pos);
    }

    private boolean isAirOrReplaceable(BlockPos pos) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return state.isAir() || state.isLiquid() || state.isReplaceable();
    }

    private boolean isRailBlock(BlockState state) {
        return state != null && state.getBlock() instanceof AbstractRailBlock;
    }

    private boolean stationaryRail(BlockPos cell) {
        BlockState state = mc.world.getBlockState(cell);
        if (!(state.getBlock() instanceof AbstractRailBlock rail) || !state.getFluidState().isEmpty()
                || state.get(rail.getShapeProperty()).isAscending()) return false;
        return !(rail instanceof PoweredRailBlock) || !state.get(PoweredRailBlock.POWERED);
    }

    // --- Inventory Lookups ---

    private int findChargedCrossbowSlot(PlayerInventory inv) {
        // Check held mainhand first
        ItemStack main = mc.player != null ? mc.player.getMainHandStack() : inv.getStack(inv.getSelectedSlot());
        if (isArrowCrossbow(main)) {
            return inv.getSelectedSlot();
        }
        // Check hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (isArrowCrossbow(s)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isArrowCrossbow(ItemStack stack) {
        if (!(stack.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(stack)) return false;
        ChargedProjectilesComponent ammo = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        return ammo != null && !ammo.isEmpty() && !ammo.contains(Items.FIREWORK_ROCKET);
    }

    private int findRailSlot(PlayerInventory inv, BlockPos cell) {
        return AutoCartPlanner.railSlot(mc.world, cell);
    }

    private int findTntMinecartSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.TNT_MINECART)) {
                return i;
            }
        }
        return -1;
    }

    private int countTntMinecarts(PlayerInventory inv) {
        int count = 0;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TNT_MINECART)) {
                count += inv.getStack(i).getCount();
            }
        }
        return count;
    }

    private int findIgnitionSlot(PlayerInventory inv) {
        String mode = ignitionMode.getCurrentMode();
        boolean allowFlint = "Flint".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);
        boolean allowLava = "Lava".equalsIgnoreCase(mode) || "Both".equalsIgnoreCase(mode);

        if (allowFlint) {
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.FLINT_AND_STEEL)) {
                    this.isLavaIgnition = false;
                    return i;
                }
            }
        }

        if (allowLava) {
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isOf(Items.LAVA_BUCKET)) {
                    this.isLavaIgnition = true;
                    return i;
                }
            }
        }
        return -1;
    }

    private int findSafetyBlockSlot(PlayerInventory inv) {
        if ("Select".equalsIgnoreCase(blockMode.getCurrentMode())) {
            int configured = (int) blockSlot.getValue() - 1;
            if (configured >= 0 && configured < 9) {
                ItemStack s = inv.getStack(configured);
                if (s.getItem() instanceof BlockItem bi && !(bi.getBlock() instanceof AbstractRailBlock)) {
                    return configured;
                }
            }
        }

        if ("Select".equalsIgnoreCase(blockMode.getCurrentMode())) return -1;

        // Auto mode: scan for slab first, then any solid block
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof SlabBlock) {
                return i;
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() instanceof BlockItem bi && !(bi.getBlock() instanceof AbstractRailBlock)) {
                return i;
            }
        }
        return -1;
    }
}
