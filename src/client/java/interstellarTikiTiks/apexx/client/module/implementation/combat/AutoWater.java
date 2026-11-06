package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * AutoWater — Offensive Water Bucket Placer.
 *
 * <p>Spams water placements directly at opponents' feet to trap, slow, and disrupt their
 * movement, sprint momentum, and knockback combos.
 */
public class AutoWater extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    public static AutoWater INSTANCE;

    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Legit", "Silent")
            .setDescription("Legit turns your real camera smoothly onto the target's feet. Silent turns only what the server sees and keeps your screen still.");
    private final ModeSetting rotationPattern = new ModeSetting("Rotation Pattern", "Smooth", "Sine", "Linear", "Instant")
            .setDescription("Shape of the turn onto the target. Smooth, Sine and Linear are human-like; Instant snaps in one frame and is easy to spot, so it only applies while Blatant Instant Rotation is on (otherwise Smooth is used).");
    private final BooleanSetting allowInstant = new BooleanSetting("Blatant Instant Rotation", false)
            .setBlatant(true)
            .setDescription("BLATANT: lets the Instant rotation pattern snap onto the target in one frame. Rotation checks flag instant snaps. Off: Instant falls back to Smooth.");
    private final SliderSetting range = new SliderSetting("Range", 3.0, 1.0, 3.0, 1)
            .setDescription("Farthest an enemy can be, in blocks, for water to be placed at their feet. Capped at vanilla's 3.0 reach.");
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true)
            .setDescription("Selects a water bucket from your hotbar for you. Off: only acts while you already hold one.");
    private final BooleanSetting onlyWhenMoving = new BooleanSetting("Only Moving Target", false)
            .setDescription("Only drops water on a target that is moving, where slowing them matters most.");

    // ---- Delays ----
    /** Retired: kept unregistered so old configs still load; Place Interval is always used. */
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting placeInterval = new SliderSetting("Place Interval (ms)", 220, 0, 1000, 0)
            .withJitter(30)
            .setDescription("Minimum wait between two water placements, in ms. The vanilla item-use cooldown (4 ticks, "
                    + "200 ms) is always respected on top of this, so values below 200 only remove the extra wait.")
            .setSection("Delays");
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 15, 0, 40, 0)
            .withJitter(4)
            .setDescription("Wait between selecting the water bucket and placing it, inside the same tick (capped at what is left of the tick).")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 100, 0, 1000, 0)
            .withJitter(25)
            .setDescription("Wait after the placement before your original hotbar slot is reselected (never in the same tick as the placement).")
            .setSection("Delays");

    private int originalSlot = -1;
    private long restoreAtMs = 0L;
    private long lastPlaceTime = 0L;
    private long placeIntervalMs = 0L;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private boolean pendingRestore;

    public AutoWater() {
        super("AutoWater", "Places water at a nearby enemy's feet to slow them, break their sprint and ruin their crits or escape, with a smooth aim onto the spot.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        INSTANCE = this;
        addSetting(rotationMode);
        addSetting(rotationPattern);
        addSetting(allowInstant);
        addSetting(range);
        addSetting(autoSwitch);
        addSetting(onlyWhenMoving);
        addSetting(placeInterval);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        RotationManager.clearTarget(this);
        restoreOriginalSlot();
        resetState();
    }

    private void resetState() {
        RotationManager.clearTarget(this);
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        pendingRestore = false;
        lastPlaceTime = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) {
            releaseRuntimeState();
            return;
        }
        if (mc.currentScreen != null || (pendingRestore && (mc.player != boundPlayer || mc.world != boundWorld))) {
            releaseRuntimeState();
            return;
        }

        if (pendingRestore) {
            // Earliest the tick after the placement; then the Swap Back Delay.
            if (System.currentTimeMillis() < restoreAtMs) return;
            pendingRestore = false;
            RotationManager.clearTarget(this);
            restoreOriginalSlot();
            return;
        }
        if (mc.currentScreen != null || !InteractSim.useReady(mc)) return;
        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < ModuleDelays.clampMs(placeIntervalMs)) {
            return;
        }

        PlayerEntity target = getTargetEnemy();
        if (target == null) {
            RotationManager.clearTarget(this);
            restoreOriginalSlot();
            return;
        }

        if (onlyWhenMoving.isEnabled()) {
            Vec3d targetVel = target.getVelocity();
            if (targetVel.horizontalLengthSquared() < 0.005) {
                RotationManager.clearTarget(this);
                return;
            }
        }

        int waterSlot = findHotbarSlot(Items.WATER_BUCKET);
        if (waterSlot == -1) {
            warnMissing("water-bucket", "No water bucket in the hotbar");
            RotationManager.clearTarget(this);
            return;
        }

        BlockPos footPos = target.getBlockPos();
        BlockState footState = mc.world.getBlockState(footPos);
        if (footState.isOf(Blocks.WATER)) {
            RotationManager.clearTarget(this);
            return; // Already flooded
        }

        BlockPos placeBasePos = footPos.down();
        BlockState baseState = mc.world.getBlockState(placeBasePos);
        if (baseState.isAir()) {
            placeBasePos = footPos;
        }

        BlockHitResult hit = new BlockHitResult(
                new Vec3d(placeBasePos.getX() + 0.5, placeBasePos.getY() + 1.0, placeBasePos.getZ() + 0.5),
                Direction.UP,
                placeBasePos,
                false
        );

        boolean silent = "Silent".equalsIgnoreCase(rotationMode.getCurrentMode());
        if (silent) {
            Vec3d targetVec = hit.getPos();
            RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGH, () -> targetVec, 100.0, resolveRotationMode(), 0.0, 0L, true, false);
        } else {
            aimAtBlock(hit.getPos());
        }

        if (interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator.validateHit(mc, hit, silent) == null) return;
        if (!autoSwitch.isEnabled() && !mc.player.getMainHandStack().isOf(Items.WATER_BUCKET)) return;
        rememberOriginalSlot();
        boundPlayer = mc.player;
        boundWorld = mc.world;
        expectedSlot = autoSwitch.isEnabled() ? waterSlot : mc.player.getInventory().getSelectedSlot();
        boolean swapped = autoSwitch.isEnabled() && mc.player.getInventory().getSelectedSlot() != waterSlot;
        if (swapped && (!TickActionOrder.canSwitchSlot()
                || !interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, waterSlot))) {
            restoreOriginalSlot();
            return;
        }
        if (swapped) {
            awaitSwapDelay();
        }
        SubTickSequencer.queueAction(this, () -> {
            if (!isValidTarget(target) || !mc.player.getMainHandStack().isOf(Items.WATER_BUCKET)) return;
            if (InteractSim.place(mc, hit, silent)) {
                lastPlaceTime = System.currentTimeMillis();
                placeIntervalMs = ModuleDelays.clampMs(placeInterval.sample());
            }
        }, SubTickSequencer.sampleMicroDelayMs());
        pendingRestore = true;
        restoreAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelay.sample());
    }

    /** Swap Delay: in-thread wait between the bucket select and the place, kept inside this tick. */
    private void awaitSwapDelay() {
        long ms = ModuleDelays.clampMs(swapDelay.sample());
        double budget = SubTickSequencer.remainingTickBudgetMs() * 0.5;
        double wait = Math.min(ms, budget);
        if (wait <= 0.0) return;
        SubTickSequencer.awaitActionDeadline(mc, System.nanoTime() + (long) (wait * 1_000_000.0));
    }

    private PlayerEntity getTargetEnemy() {
        if (TargetManager.getLockedTarget() instanceof PlayerEntity locked && isValidTarget(locked)) {
            return locked;
        }

        double maxDistSq = range.getValue() * range.getValue();
        PlayerEntity nearest = null;
        double nearestDistSq = maxDistSq;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isValidTarget(player)) continue;
            double distSq = mc.player.squaredDistanceTo(player);
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private boolean isValidTarget(PlayerEntity entity) {
        if (entity == null || entity == mc.player || !entity.isAlive() || entity.isSpectator() || entity.isCreative()
                || entity.getEntityWorld() != mc.world
                || !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, entity)
                || interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().isFriend(entity.getUuid())) return false;
        for (interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider filter
                : interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledHooks(
                        interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider.class)) {
            if (filter.isBot(entity)) return false;
        }
        return mc.player.squaredDistanceTo(entity) <= range.getValue() * range.getValue();
    }

    private void aimAtBlock(Vec3d pos) {
        if (mc.player == null || pos == null) return;
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGH, () -> pos,
                100.0, resolveRotationMode(), 0.0, 0L, false, false);
    }

    private RotationManager.RotationMode resolveRotationMode() {
        if (rotationPattern.is("Instant") && !allowInstant.isEnabled()) {
            return RotationManager.RotationMode.SMOOTH;
        }
        try {
            return RotationManager.RotationMode.valueOf(rotationPattern.getCurrentMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RotationManager.RotationMode.SMOOTH;
        }
    }

    private int findHotbarSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private void rememberOriginalSlot() {
        if (mc.player == null) return;
        if (originalSlot == -1) {
            originalSlot = ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
        }
    }

    private void restoreOriginalSlot() {
        // Owner check: only while the bucket slot this module selected is still held. SlotSync.restore
        // defers to the next interval when an action already left in this one.
        if (mc.player != null && mc.player == boundPlayer && mc.world == boundWorld && originalSlot != -1
                && originalSlot != expectedSlot
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, originalSlot);
        }
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        pendingRestore = false;
    }
}
