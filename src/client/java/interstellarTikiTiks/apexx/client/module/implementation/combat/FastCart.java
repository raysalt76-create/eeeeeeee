package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseAware;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.AutoCartPlanner;
import interstellarTikiTiks.apexx.client.util.combat.WebHitModel;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * FastCart automates repeated minecart placement and use.
 *
 * <p>Hooks directly into block placement events when the player places any rail manually.
 * After its Step Delay it aims at the rail; once the live ray reaches the rail's top face it
 * switches to a TNT Minecart and places it in that same tick, then restores the original hotbar slot
 * and releases the aim on the next tick.
 *
 * <p>Buffered against latency: the click point is searched over the rail for one the ray reaches and
 * that is inside block reach (not only the middle), the retry window stretches with the round trip, and
 * after the swap back the cart is confirmed (its spawn takes a round trip). A cart the server refused is
 * placed again, a bounded number of times, instead of ending with a rail and no cart.
 */
public final class FastCart extends Module implements ItemUseAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    // ---- Delays ----
    // Kept (unregistered) so configs that saved it still load; the per-module sliders always apply.
    private final BooleanSetting useTimingProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting profileDelay = new SliderSetting("Step Delay (ms)", 30, 0, 300, 0)
            .withJitter(10)
            .setDescription("Wait after you place a rail before FastCart starts aiming at it and swapping to the TNT minecart. 0 starts on the next frame; higher looks more like a human reaction.")
            .setSection("Delays");
    private final SliderSetting retryDelay = new SliderSetting("Retry Delay (ms)", 50, 0, 200, 0)
            .withJitter(10)
            .setDescription("Wait between attempts while the aim has not yet reached the rail's top face, and after the server-side placement wait. Lower retries faster; too low just burns attempts before the rotation arrives.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0)
            .withJitter(10)
            .setDescription("Wait after the cart is placed before the aim is released and (with Swap Back) your previous slot is reselected. The swap back always lands on a later tick than the placement; 0 = the very next tick.")
            .setDependsOn("Swap Back")
            .setSection("Delays");
    private final SliderSetting confirmWait = new SliderSetting("Confirm Wait (ms)", 150, 0, 1000, 0)
            .withJitter(20)
            .setDescription("Extra wait, on top of your ping, for the placed cart to appear before FastCart decides the server refused it and places it again.")
            .setSection("Delays");


    /** Block reach ceiling: never beyond vanilla's 4.5, whatever the attribute says. */
    private static final double MAX_REACH = 4.5;
    private static final int TIMEOUT_TICKS = 20;
    /** Cart placements repeated when the server did not spawn the cart. */
    private static final int MAX_REPLACEMENTS = 2;
    private static final float AIM_TOLERANCE_DEG = 18.0f;

    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "SMOOTH", "SILENT", "SMOOTH")
            .setDescription("How FastCart turns to the rail. SMOOTH: your visible camera eases onto the rail's top face. SILENT: only the rotation sent to the server turns, your screen stays still.");
    private final SliderSetting strength = new SliderSetting("Strength", 50.0, 1.0, 100.0, 1)
            .setDescription("How quickly the aim converges on the rail (1 = slow and gentle, 100 = fastest). Higher places sooner; lower looks more natural.");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("After the minecart is placed, reselect the item you were holding before (usually your rails or weapon) on the next tick.");

    private int initialPlacementAge = Integer.MIN_VALUE;
    private long initialPlacementTimeNanos = Long.MIN_VALUE;
    private BlockHitResult lastRailHitResult = null;
    private int initialSlot = -1;

    private BlockPos targetRailPos = null;
    private int returnSlot = -1;
    private int tntCartSlot = -1;
    private boolean slotSwapped = false;
    private long nextActionTimeMs = 0L;
    private int waitAge = Integer.MIN_VALUE;
    private int restoreSlotAge = Integer.MIN_VALUE;
    private boolean isInternalPlacement = false;
    private boolean cartPlaced = false;
    private int retryCount = 0;
    /** Player age by which the cart's spawn must have arrived, once it was placed. */
    private int confirmAge = Integer.MIN_VALUE;
    private int replacements = 0;

    public FastCart() {
        super("FastCart", "Every time you place a rail by hand, FastCart aims at its top face, switches to a TNT minecart from your hotbar and places the cart on it in the same tick, then switches back. It searches the whole rail for a reachable click point, stretches its retry window with your ping, and re-places a cart the server refused (up to twice) so you never end up with a bare rail.", Category.COMBAT);
        addSetting(profileDelay);
        addSetting(retryDelay);
        addSetting(swapBackDelay);
        addSetting(confirmWait);
        setSubCategory(SubCategory.CART);
        addSetting(rotationMode);
        addSetting(strength);
        addSetting(switchBack);
    }

    @Override
    public void onEnable() {
        restoreOriginalSlotImmediately();
        resetTransientState();
        resetQueueState();
        this.isInternalPlacement = false;
    }

    @Override
    public void onDisable() {
        RotationManager.stop(this);
        restoreOriginalSlotImmediately();
        resetTransientState();
        resetQueueState();
    }

    @Override
    public boolean onItemUse(BlockHitResult hitResult) {
        if (!shouldInterceptInitialPlacement(hitResult) || isXBowCartRunning()) {
            return false;
        }

        restoreOriginalSlotImmediately();
        resetTransientState();
        resetQueueState();

        this.replacements = 0;
        this.initialPlacementAge = mc.player.age;
        this.initialPlacementTimeNanos = System.nanoTime();
        this.lastRailHitResult = hitResult;
        this.initialSlot = getPlayerSelectedSlot();
        return false;
    }

    @Override
    public void onPostItemUse(Hand hand, BlockHitResult hitResult, ActionResult result) {
        if (this.initialPlacementAge == Integer.MIN_VALUE) {
            return;
        }

        try {
            if (!isEnabled() || this.isInternalPlacement || result == null || !result.isAccepted()
                || mc.player == null || mc.world == null || mc.interactionManager == null
                || hand != Hand.MAIN_HAND || mc.player.age != this.initialPlacementAge) {
                return;
            }

            BlockHitResult effectiveHit = this.lastRailHitResult != null ? this.lastRailHitResult : hitResult;
            BlockPos resolvedRailPos = resolvePlacedRailPos(effectiveHit);

            if (resolvedRailPos != null) {
                armCartPlacement(resolvedRailPos, this.initialSlot);
            }
        } finally {
            resetTransientState();
        }
    }

    @Override
    public void onTick() {
        processPlacementTick();
    }

    private void processPlacementTick() {
        if (this.targetRailPos == null || mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }

        if (!mc.player.isAlive()) {
            RotationManager.stop(this);
            restoreOriginalSlotImmediately();
            resetQueueState();
            return;
        }

        if (this.confirmAge != Integer.MIN_VALUE) {
            confirmCart();
            return;
        }

        if (this.restoreSlotAge != Integer.MIN_VALUE) {
            if (mc.player.age < this.restoreSlotAge) {
                return;
            }
            RotationManager.stop(this);
            restoreOriginalSlotImmediately();
            if (this.cartPlaced && this.targetRailPos != null) {
                // Keep watching until the cart's spawn arrives (a round trip), then finish.
                this.confirmAge = mc.player.age + (int) Math.ceil(WebHitModel.rttTicks(mc))
                        + ModuleDelays.msToTicks(confirmWait.sample());
                this.restoreSlotAge = Integer.MIN_VALUE;
                return;
            }
            resetQueueState();
            return;
        }

        if (this.waitAge != Integer.MIN_VALUE) {
            if (mc.player.age <= this.waitAge) {
                return;
            }
            this.waitAge = Integer.MIN_VALUE;
            this.nextActionTimeMs = System.currentTimeMillis() + ModuleDelays.clampMs(retryDelay.sample());
            return;
        }

        if (System.currentTimeMillis() < this.nextActionTimeMs) {
            return;
        }

        BlockPos railPos = this.targetRailPos;
        if (!isRailBlock(mc.world.getBlockState(railPos))) {
            handleRetryOrAbort();
            return;
        }

        if (!isValidCartDistance(railPos)) {
            finishAndRestoreSlot();
            return;
        }

        if (this.tntCartSlot < 0) {
            this.tntCartSlot = findTntCartSlot(mc.player);
        }

        if (!this.slotSwapped && (this.tntCartSlot < 0 || !hasTntCartInSlot(this.tntCartSlot))) {
            finishAndRestoreSlot();
            return;
        }

        Vec3d aimVec = getCartPlacementAimVec(railPos);
        boolean rotationOnTarget = processAim(aimVec);

        if (rotationOnTarget) {
            executeCartPlacement(railPos);
        } else {
            this.retryCount++;
            if (this.retryCount > timeoutTicks()) {
                handleRetryOrAbort();
            }
        }
    }

    /** Retry window: the base plus two round trips, so a slow rail update or refusal still fits. */
    private int timeoutTicks() {
        return TIMEOUT_TICKS + (int) Math.ceil(2.0 * WebHitModel.rttTicks(mc));
    }

    /** After the swap back: done once a cart is on the rail, else place it again when its window passes. */
    private void confirmCart() {
        BlockPos rail = this.targetRailPos;
        if (rail == null || cartOnRail(rail)) {
            resetQueueState();
            return;
        }
        if (mc.player.age < this.confirmAge) {
            return;
        }
        int slot = findTntCartSlot(mc.player);
        if (this.replacements < MAX_REPLACEMENTS && isRailBlock(mc.world.getBlockState(rail)) && slot >= 0) {
            this.replacements++;
            int held = getPlayerSelectedSlot();
            armCartPlacement(rail, held);
            return;
        }
        resetQueueState();
    }

    private boolean cartOnRail(BlockPos rail) {
        return !mc.world.getEntitiesByClass(TntMinecartEntity.class, new Box(rail).expand(0.5), e -> e.isAlive()).isEmpty();
    }

    /**
     * Keeps the owner-scoped aim request alive and reports whether the look is close enough to try the
     * placement. Eligibility itself is decided in {@link #executeCartPlacement}: the live ray must reach
     * the rail's top face.
     */
    private boolean processAim(Vec3d aimVec) {
        if (mc.player == null || aimVec == null) return false;

        boolean silent = "SILENT".equalsIgnoreCase(rotationMode.getCurrentMode());
        double aimSpeed = Math.max(5.0, strength.getValue());
        RotationManager.aimAt(this, RotationManager.Priority.HIGHEST, () -> aimVec, aimSpeed, 0.0, silent);

        float currentYaw = silent && RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float currentPitch = silent && RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
        float[] targetRots = RotationManager.calculateRotationsToPos(aimVec, currentYaw);
        float deltaYaw = Math.abs(MathHelper.wrapDegrees(targetRots[0] - currentYaw));
        float deltaPitch = Math.abs(targetRots[1] - currentPitch);
        float angleDiff = (float) Math.hypot(deltaYaw, deltaPitch);

        return angleDiff <= AIM_TOLERANCE_DEG || (!silent && isLookingDirectlyAt(aimVec));
    }

    private boolean isLookingDirectlyAt(Vec3d targetVec) {
        if (mc.player == null || mc.crosshairTarget == null) return false;
        if (mc.crosshairTarget instanceof BlockHitResult blockHit) {
            return blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getPos().squaredDistanceTo(targetVec) <= 0.25;
        }
        return false;
    }

    /** The rail AutoXBowCart places belongs to its own sequence; FastCart must not arm on it. */
    private boolean isXBowCartRunning() {
        AutoXBowCart xbow = interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledModule(AutoXBowCart.class);
        return xbow != null && xbow.isBusy();
    }

    private boolean shouldInterceptInitialPlacement(BlockHitResult hitResult) {
        return isEnabled()
            && !this.isInternalPlacement
            && mc.player != null
            && mc.world != null
            && mc.interactionManager != null
            && hitResult != null
            && hitResult.getType() == HitResult.Type.BLOCK
            && hitResult.getSide() == Direction.UP
            && isRailStack(mc.player.getMainHandStack());
    }

    private boolean executeCartPlacement(BlockPos railPos) {
        this.waitAge = Integer.MIN_VALUE;

        if (!(this.targetRailPos != null && this.targetRailPos.equals(railPos) && !this.cartPlaced
            && isValidCartDistance(railPos) && this.tntCartSlot >= 0 && hasTntCartInSlot(this.tntCartSlot))) {
            handleRetryOrAbort();
            return false;
        }

        boolean silent = "SILENT".equalsIgnoreCase(rotationMode.getCurrentMode());
        BlockHitResult cartHit = new BlockHitResult(getCartPlacementAimVec(railPos), Direction.UP, railPos, false);
        // Eligible once the live ray reaches the rail's top face: the cart slot is selected first and
        // the cart placed later in this same tick.
        if (!interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)
                || interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator.validateHit(mc, cartHit, silent) == null) {
            handleRetryOrAbort();
            return false;
        }
        if (getPlayerSelectedSlot() != this.tntCartSlot) {
            if (!selectSlotLegit(this.tntCartSlot)) {
                handleRetryOrAbort(); // hotbar change not allowed yet this packet interval
                return false;
            }
            this.slotSwapped = true;
        }

        boolean success;
        try {
            this.isInternalPlacement = true;
            suppressUseKey();
            success = interstellarTikiTiks.apexx.client.util.combat.InteractSim.place(mc, cartHit, silent);
        } finally {
            this.isInternalPlacement = false;
        }

        if (success) {
            this.cartPlaced = true;
            interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
            // Rule 4: keep the angle the cart went out on for the rest of this tick.
            RotationManager.markActionDispatched(this);
            // The aim stays held for the rest of this tick; finishAndRestoreSlot releases it next tick.
            finishAndRestoreSlot();
            return true;
        }

        handleRetryOrAbort();
        return false;
    }

    /** True while a cart placement is armed or in flight; CartRefill waits for it. */
    public boolean isBusy() {
        return this.targetRailPos != null || this.initialPlacementAge != Integer.MIN_VALUE;
    }

    private BlockPos resolvePlacedRailPos(BlockHitResult hitResult) {
        if (mc.world == null || hitResult == null) return null;

        BlockPos basePos = hitResult.getBlockPos();
        BlockPos offsetPos = basePos.offset(hitResult.getSide());

        if (isRailBlock(mc.world.getBlockState(offsetPos))) {
            return offsetPos.toImmutable();
        }
        if (isRailBlock(mc.world.getBlockState(basePos))) {
            return basePos.toImmutable();
        }
        return offsetPos.toImmutable();
    }

    private void armCartPlacement(BlockPos railPos, int returnSlot) {
        this.confirmAge = Integer.MIN_VALUE;
        this.targetRailPos = railPos.toImmutable();
        this.returnSlot = returnSlot;
        this.tntCartSlot = -1;
        this.slotSwapped = false;
        this.retryCount = 0;
        this.waitAge = Integer.MIN_VALUE;
        this.restoreSlotAge = Integer.MIN_VALUE;
        this.cartPlaced = false;
        this.nextActionTimeMs = System.currentTimeMillis() + ModuleDelays.clampMs(profileDelay.sample());
    }

    private void handleRetryOrAbort() {
        this.retryCount++;
        if (this.retryCount > timeoutTicks()) {
            RotationManager.stop(this);
            finishAndRestoreSlot();
            return;
        }
        this.nextActionTimeMs = System.currentTimeMillis() + ModuleDelays.clampMs(retryDelay.sample());
    }

    /**
     * Ends the sequence after the Swap Back Delay, always on a later tick than the placement (the
     * restore runs from a later onTick): releases the aim and, with Swap Back on, restores the slot.
     */
    private void finishAndRestoreSlot() {
        if (mc.player == null) {
            RotationManager.stop(this);
            resetQueueState();
            return;
        }
        this.restoreSlotAge = mc.player.age + Math.max(1, ModuleDelays.msToTicks(swapBackDelay.sample()));
    }

    private int getPlayerSelectedSlot() {
        if (mc.player == null) return 0;
        return ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
    }

    private boolean selectSlotLegit(int slot) {
        if (mc.player == null || mc.options == null || mc.options.hotbarKeys == null
                || slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            return true;
        }
        return interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, slot);
    }

    private void restoreOriginalSlotImmediately() {
        int slot = this.returnSlot >= 0 ? this.returnSlot : this.initialSlot;
        // Only while the cart slot this module selected is still the held one.
        if (mc.player != null && switchBack.isEnabled() && slot >= 0 && slot < 9
                && this.slotSwapped && getPlayerSelectedSlot() == this.tntCartSlot) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, slot);
        }
    }

    private void suppressUseKey() {
        if (mc.options != null && mc.options.useKey != null && !interstellarTikiTiks.apexx.client.util.SystemInputSimulator.isBindingSimulatedPressed(mc.options.useKey)) {
            mc.options.useKey.setPressed(false);
        }
    }

    private void resetTransientState() {
        this.initialPlacementAge = Integer.MIN_VALUE;
        this.initialPlacementTimeNanos = Long.MIN_VALUE;
        this.lastRailHitResult = null;
        this.initialSlot = -1;
    }

    private void resetQueueState() {
        this.targetRailPos = null;
        this.returnSlot = -1;
        this.tntCartSlot = -1;
        this.slotSwapped = false;
        this.nextActionTimeMs = 0L;
        this.waitAge = Integer.MIN_VALUE;
        this.restoreSlotAge = Integer.MIN_VALUE;
        this.cartPlaced = false;
        this.retryCount = 0;
        this.confirmAge = Integer.MIN_VALUE;
    }

    /** The rail's box is inside block reach from the eye (the click point is chosen on it, not fixed). */
    private boolean isValidCartDistance(BlockPos pos) {
        if (mc.player == null || mc.world == null || pos == null) return false;
        if (!mc.world.getWorldBorder().contains(pos)) return false;
        Vec3d eye = mc.player.getEyePos();
        Box rail = new Box(pos);
        double dx = Math.max(Math.max(rail.minX - eye.x, 0.0), eye.x - rail.maxX);
        double dy = Math.max(Math.max(rail.minY - eye.y, 0.0), eye.y - rail.maxY);
        double dz = Math.max(Math.max(rail.minZ - eye.z, 0.0), eye.z - rail.maxZ);
        double reach = Math.min(MAX_REACH, mc.player.getBlockInteractionRange());
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * A point on the rail the ray from here reaches first, within reach; the rail's middle when none
     * does (the live-ray check before the placement still decides).
     */
    private Vec3d getCartPlacementAimVec(BlockPos pos) {
        if (mc.player != null && mc.world != null) {
            AutoCartPlanner.Aim aim = AutoCartPlanner.insideAim(mc.world, mc.player, mc.player.getEyePos(), pos, 0.06,
                    RotationManager.getFinalYaw());
            if (aim != null) return aim.point();
        }
        return Vec3d.ofBottomCenter(pos).add(0.0, 0.125, 0.0);
    }

    private boolean hasTntCartInSlot(int slot) {
        if (mc.player == null || slot < 0 || slot >= 9) return false;
        ItemStack stack = mc.player.getInventory().getStack(slot);
        return stack != null && !stack.isEmpty() && stack.isOf(Items.TNT_MINECART);
    }

    private int findTntCartSlot(PlayerEntity player) {
        if (player == null) return -1;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(Items.TNT_MINECART)) return i;
        }
        return -1;
    }

    private boolean isRailStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isOf(Items.RAIL) || stack.isOf(Items.POWERED_RAIL) || stack.isOf(Items.DETECTOR_RAIL) || stack.isOf(Items.ACTIVATOR_RAIL);
    }

    private boolean isRailBlock(BlockState state) {
        if (state == null) return false;
        return state.isOf(Blocks.RAIL) || state.isOf(Blocks.POWERED_RAIL) || state.isOf(Blocks.DETECTOR_RAIL) || state.isOf(Blocks.ACTIVATOR_RAIL);
    }
}
