package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;

/**
 * Celeste-style autonomous sword aura.
 *
 * <p>This is deliberately separate from {@link SwordHit}: SwordHit is a manual
 * attack-time sword swap, while this module owns target selection, rotation,
 * cooldown gating, and the attack action.</p>
 */
public final class SwordAura extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    private static final double ATTACK_COOLDOWN = 0.9D;
    private static final double REAL_ATTACK_RANGE = 3.0D;
    private static final float ROTATION_TOLERANCE = 7.5F;

    private final SliderSetting range = new SliderSetting("Range", 3.1, 2.0, 6.0, 1)
            .setDescription("Distance in blocks at which players are picked up as targets and tracked. The hit itself still only fires inside vanilla's 3-block reach, so a larger scan range just starts the turn earlier.");
    private final ModeSetting mode = new ModeSetting("Mode", "Locked", "Locked", "Flick")
            .setDescription("Locked: keeps your aim on the target the whole time. Flick: stays loose and only turns onto the target's hitbox edge when a hit is ready, which looks less locked-on.");
    private final SliderSetting fov = new SliderSetting("FOV", 60.0, 10.0, 180.0, 0)
            .setDescription("Only players within this many degrees of where you are looking are targeted. Low values make it act only on players you are already facing.");
    private final BooleanSetting onlyCriticals = new BooleanSetting("Only Criticals", false)
            .setDescription("Only swing while you are falling and the hit would be a vanilla critical (more damage, fewer hits).");

    // Kept (unregistered) so configs that saved it still load; the per-module slider always applies.
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 120, 0, 500, 0)
            .withJitter(30)
            .setDescription("Wait after a new target is picked before the first swing, rounded to whole ticks (at least one). Later swings on the same target only wait for your weapon's charge.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0)
            .withJitter(15)
            .setDescription("Wait after a swing that needed a sword swap before your previous item is reselected, rounded to whole ticks (at least one).")
            .setSection("Delays");

    private PlayerEntity target;
    private int pendingRestoreSlot = -1;
    /** The sword slot this module selected; the swap back only applies while it is still held. */
    private int ownedSlot = -1;
    private int restoreAtAge = Integer.MIN_VALUE;
    // Humanized reaction window: when a NEW target is acquired we do not swing on the same tick,
    // we keep tracking and only become eligible to attack once this tick deadline passes. The delay
    // comes from this module's jittered Reaction Delay slider.
    private java.util.UUID reactionTargetId;
    private int reactUntilAge = Integer.MIN_VALUE;

    public SwordAura() {
        super("SwordAura", "Blatant kill aura: picks the best player in range and field of view, turns your aim onto them, swaps to a sword from your hotbar and attacks every time your weapon is fully charged. Hits only fire within vanilla reach with a live aim on the hitbox, but automatic target selection and aiming is easy for anticheats and spectators to spot. Off by default.",
                Category.BLATANT, SubCategory.GENERAL);
        addSetting(range);
        addSetting(mode);
        addSetting(fov);
        addSetting(onlyCriticals);
        addSetting(reactionDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        clearState();
    }

    @Override
    public void onDisable() {
        clearRotation();
        restorePendingSlotNow();
        clearState();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null
                || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.player.hasVehicle() || mc.player.isUsingItem()
                || mc.interactionManager == null || mc.interactionManager.isBreakingBlock()
                || RotationManager.isExclusiveLocked()) {
            clearRotation();
            restorePendingSlot();
            target = null;
            return;
        }

        restorePendingSlot();

        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isAutoAttackLocked() || lock.isItemSwapLocked()) {
            clearRotation();
            target = null;
            return;
        }

        if (!attackReady() || mc.player.handSwinging) {
            clearRotation();
            target = null;
            return;
        }

        PlayerEntity selected = selectTarget();
        int swordSlot = findBestSwordSlot();
        if (selected == null || swordSlot < 0 || (onlyCriticals.isEnabled() && !inCriticalWindow())) {
            clearRotation();
            target = null;
            return;
        }
        // New target → arm a humanized reaction window before the first swing lands.
        if (!selected.getUuid().equals(reactionTargetId)) {
            reactionTargetId = selected.getUuid();
            reactUntilAge = mc.player.age + Math.max(1, ModuleDelays.msToTicks(reactionDelay.sample()));
        }
        target = selected;

        if (mode.is("Flick")) {
            RotationManager.setRotationSupplier(
                    this,
                    RotationManager.Priority.HIGH,
                    () -> aimPoint(target),
                    180.0,
                    RotationManager.RotationMode.INSTANT,
                    0.0,
                    true,
                    false
            );
        } else {
            RotationManager.setStableSmoothSupplier(
                    this,
                    RotationManager.Priority.HIGH,
                    () -> aimPoint(target),
                    18.0,
                    true,
                    false
            );
        }

        if (mode.is("Locked")
                && (!RotationManager.isRotationComplete(ROTATION_TOLERANCE)
                || !RotationManager.isSilentAimFacing(target, REAL_ATTACK_RANGE))) {
            return;
        }

        if (!validAttackTarget(target) || !hasLineOfSight(target)
                || !RotationManager.isSilentAimFacing(target, REAL_ATTACK_RANGE)) {
            return;
        }

        for (AttackGatingProvider gating : ModuleAccess.getEnabledHooks(AttackGatingProvider.class)) {
            if (gating.shouldGateAttackInput()) {
                return;
            }
        }

        // Humanized reaction: keep rotating toward the new target (above) but hold the first swing
        // until the Reaction Delay window elapses. Subsequent swings on the same target are
        // gated only by the vanilla attack cooldown.
        if (mc.player.age < reactUntilAge) {
            return;
        }

        // SlotSync.select changes the selected slot immediately. Preserve the slot
        // that was visible before the swap and restore it on the next client tick.
        int visibleSlot = mc.player.getInventory().getSelectedSlot();
        if (visibleSlot != swordSlot) {
            pendingRestoreSlot = visibleSlot;
            ownedSlot = swordSlot;
            restoreAtAge = mc.player.age + Math.max(1, ModuleDelays.msToTicks(swapBackDelay.sample()));
        }
        if (!SlotSync.select(mc, swordSlot)) {
            return;
        }

        if (!InteractSim.attack(mc, target, true)) {
            return;
        }
    }

    private PlayerEntity selectTarget() {
        if (mc.player == null || mc.world == null) {
            return null;
        }

        double maxRange = range.getValue();
        double maxRangeSquared = maxRange * maxRange;
        double maxFov = fov.getValue();
        return mc.world.getPlayers().stream()
                .filter(player -> validScanTarget(player, maxRangeSquared, maxFov))
                .min(Comparator.comparingDouble(player -> mc.player.squaredDistanceTo(player)))
                .orElse(null);
    }

    private boolean validScanTarget(PlayerEntity player, double maxRangeSquared, double maxFov) {
        if (player == null || mc.player == null || mc.world == null
                || player == mc.player || !player.isAlive() || player.isRemoved()
                || player.isSpectator() || player.isCreative()
                || !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player)
                || FriendManager.getInstance().isFriend(player.getUuid())) {
            return false;
        }

        if (player.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) > maxRangeSquared) {
            return false;
        }
        return withinFov(player, maxFov) && hasLineOfSight(player);
    }

    private boolean validAttackTarget(PlayerEntity player) {
        if (player == null || mc.player == null || !player.isAlive() || player.isRemoved()) {
            return false;
        }
        if (player.getBoundingBox().squaredMagnitude(mc.player.getEyePos())
                > REAL_ATTACK_RANGE * REAL_ATTACK_RANGE) {
            return false;
        }
        return withinFov(player, fov.getValue());
    }

    private boolean withinFov(PlayerEntity player, double degrees) {
        if (mc.player == null) {
            return false;
        }
        float[] wanted = RotationManager.calculateRotationsToPos(aimPoint(player), mc.player.getYaw());
        double yaw = Math.abs(MathHelper.wrapDegrees(wanted[0] - mc.player.getYaw()));
        double pitch = Math.abs(wanted[1] - mc.player.getPitch());
        return Math.hypot(yaw, pitch) <= degrees;
    }

    private boolean hasLineOfSight(PlayerEntity player) {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        Vec3d origin = mc.player.getEyePos();
        Vec3d destination = aimPoint(player);
        HitResult result = mc.world.raycast(new RaycastContext(
                origin,
                destination,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));
        return result.getType() == HitResult.Type.MISS
                || result.getPos().squaredDistanceTo(destination) < 0.04D;
    }

    private boolean attackReady() {
        return mc.player != null
                && mc.player.getAttackCooldownProgress(0.0F) + 1.0E-4F >= ATTACK_COOLDOWN;
    }

    private boolean inCriticalWindow() {
        return mc.player != null
                && mc.player.fallDistance > 0.0F
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.hasVehicle()
                && !mc.player.isSprinting()
                && !mc.player.isGliding()
                && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS);
    }

    private int findBestSwordSlot() {
        if (mc.player == null) {
            return -1;
        }
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int visible = mc.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !CombatItemUtil.isSword(stack)) {
                continue;
            }
            double score = swordScore(stack) + (slot == visible ? 0.25 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private double swordScore(ItemStack stack) {
        double score;
        if (stack.isOf(Items.NETHERITE_SWORD)) score = 6.0;
        else if (stack.isOf(Items.DIAMOND_SWORD)) score = 5.0;
        else if (stack.isOf(Items.IRON_SWORD)) score = 4.0;
        else if (stack.isOf(Items.STONE_SWORD)) score = 3.0;
        else if (stack.isOf(Items.WOODEN_SWORD)) score = 2.0;
        else if (stack.isOf(Items.GOLDEN_SWORD)) score = 1.5;
        else score = 1.0;

        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants != null) {
            for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
                if (entry.getIdAsString().contains("sharpness")) {
                    score += enchants.getLevel(entry) * 1.25;
                }
            }
        }
        return score;
    }

    private void restorePendingSlot() {
        if (mc.player == null || pendingRestoreSlot < 0 || mc.player.age < restoreAtAge) {
            return;
        }
        // Only while the sword slot this module selected is still the held one (a newer user choice wins).
        if (mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            SlotSync.select(mc, pendingRestoreSlot);
        }
        pendingRestoreSlot = -1;
        ownedSlot = -1;
        restoreAtAge = Integer.MIN_VALUE;
    }

    private Vec3d aimPoint(PlayerEntity player) {
        return player == null ? null : player.getBoundingBox().getCenter();
    }

    private void clearRotation() {
        RotationManager.clearTarget(this);
    }

    private void restorePendingSlotNow() {
        if (pendingRestoreSlot >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            SlotSync.restore(mc, pendingRestoreSlot);
        }
        pendingRestoreSlot = -1;
        ownedSlot = -1;
        restoreAtAge = Integer.MIN_VALUE;
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearRotation();
        restorePendingSlotNow();
        clearState();
    }

    private void clearState() {
        target = null;
        ownedSlot = -1;
        pendingRestoreSlot = -1;
        restoreAtAge = Integer.MIN_VALUE;
        reactionTargetId = null;
        reactUntilAge = Integer.MIN_VALUE;
    }
}
