package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.MaceHitEvent;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BreachSwap — Automatically swaps to a Breach mace against opponents.
 * 
 * Operates with the same execution logic as AutoMace's Mace Swap Only mode:
 * intercepts sword attack, cancels the sword click, swaps to the breach mace slot, clicks in the same tick
 * with attack cooldown reset and keystroke HUD compatibility, and schedules humanized swap-back.
 */
public class BreachSwap extends Module implements AttackHandleAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting minArmorPoints = new SliderSetting("Armor Floor", 0, 0, 20, 0)
            .setDescription("Minimum armor points (0-20) the enemy must be wearing before a Breach swap is worth it. Breach only pays off against armor, so raise this to keep your normal weapon against lightly armored players. 0 swaps against anyone.");
    private final BooleanSetting requireEnchantedArmor = new BooleanSetting("Enchanted Gear Only", false)
            .setDescription("Only swap when at least one of the enemy's worn armor pieces is enchanted (usually Protection), where Breach's armor reduction matters most.");
    private final BooleanSetting weaponOnly = new BooleanSetting("Melee In Hand", true)
            .setDescription("Only trigger from a hit you start with a sword or axe in hand, so clicks with blocks, food or utility items are never turned into mace swaps.");
    private final ModeSetting hitFilter = new ModeSetting("Hit Filter", "Skip Blockers", "None", "Skip Blockers", "Crits Only", "Crits + Skip Blockers")
            .setDescription("Extra conditions on the click that triggers the swap. Skip Blockers: never swap into a raised shield (the mace hit would be wasted). Crits Only: swap only when the hit will be a critical (falling, not sprinting). The combined option applies both.");
    private final SliderSetting maxFallHeight = new SliderSetting("Fall Limit", 1.5, 0, 5, 1)
            .setDescription("Highest fall, in blocks, at which BreachSwap still acts. Above this the smash modules (AutoMace, Density swaps) own the hit. 0 restricts BreachSwap to grounded hits.");
    private final BooleanSetting trueClicks = new BooleanSetting("Native Clicks", true)
            .setDescription("Deliver the mace hit as a simulated OS mouse click instead of a direct attack call, so it also appears on CPS/keystroke overlays and follows the Legit execution path.");
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 3, 0, 15, 0)
            .withJitter(1)
            .setDescription("Extra wait between switching to the Breach mace and the hit, inside the click's own tick (on top of the shared 1-3 ms swap gap). Capped by the time left in the tick so the swap and the hit never split across ticks.")
            .setSection("Delays");
    /*
     * No 5 ms floor is needed here: the swap-back is only ever applied from onTick at a tick head (or
     * queued through TickActionOrder.deferSlot), so it always lands in a later movement interval than the
     * hit, which satisfies the contract's "5-15 ms or next movement interval" rule even at 0.
     */
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 30, 0, 250, 0)
            .withJitter(10)
            .setDescription("How long after the mace hit before your original weapon is reselected. Applied at a tick head, so 0 means the next tick; the swap-back never shares the attack's packet interval.")
            .setSection("Delays");

    private boolean reentrantAttack = false;
    private boolean swapBackPending = false;
    private int swapBackTargetSlot = -1;
    private int swappedMaceSlot = -1;
    private long swapBackDueNs = 0L;
    private double highestY = 0.0;

    public BreachSwap() {
        super("BreachSwap", "When you hit a well-armored enemy with your sword or axe, swaps to a Breach-enchanted mace in your hotbar for that one hit (Breach ignores part of their armor), then swaps back to your weapon. Uses a real synchronized slot change, so the server always sees the mace in hand. Grounded and low-fall hits only; higher drops are left to the smash modules.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(minArmorPoints);
        addSetting(requireEnchantedArmor);
        addSetting(weaponOnly);
        addSetting(hitFilter);
        addSetting(maxFallHeight);
        addSetting(trueClicks);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
        if (mc.player != null) {
            highestY = mc.player.getY();
        }
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        executeSwapBack();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (swapBackPending) {
            executeSwapBack();
        }
        resetState();
        SlotSync.releaseOwner(this);
    }

    public boolean isBusy() {
        return swapBackPending;
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (isEnabled() && swapBackPending) {
            event.setItemSwapLocked(true);
        }
    }

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        trackFall();

        // The swap back always comes at the head of the tick after the breach hit: never inside the
        // hit's own movement interval, and never at a frame-rate-dependent moment in between.
        if (swapBackPending && System.nanoTime() >= swapBackDueNs) {
            executeSwapBack();
        }
    }

    @Override
    public boolean handleAttack() {
        if (reentrantAttack) return false;
        if (!isEnabled() || mc.player == null || mc.world == null) {
            return false;
        }

        trackFall();

        // Past the configured fall height, yield to AutoMace / smash mechanics
        if (currentFallDistance() > maxFallHeight.getValue()) {
            if (swapBackPending) {
                executeSwapBack();
            }
            return false;
        }

        // If AutoMace owns or is armed for a smash attack, yield immediately
        AutoMace autoMace = ModuleAccess.getModule(AutoMace.class);
        if (autoMace != null && autoMace.isEnabled() && (autoMace.isSmashArmed() || autoMace.hasCommittedSmashTarget())) {
            return false;
        }

        // Own shield is raised (AutoShield) or a shield module is mid-sequence.
        if (interstellarTikiTiks.apexx.client.module.implementation.automations.AutoShield.isShielding()) {
            return false;
        }
        AutoShieldBreak shieldBreak = ModuleAccess.getModule(AutoShieldBreak.class);
        if (shieldBreak != null && shieldBreak.isEnabled() && shieldBreak.isBusy()) {
            return false;
        }
        MaceShieldDrain shieldDrain = ModuleAccess.getModule(MaceShieldDrain.class);
        if (shieldDrain != null && shieldDrain.isEnabled() && shieldDrain.isDraining()) {
            return false;
        }

        // Another module (shield break, stun slam, ...) owns the hotbar right now.
        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (!swapBackPending && (lock.isItemSwapLocked() || lock.isAutoAttackLocked())) {
            return false;
        }

        LivingEntity target = lookTarget();
        if (target == null || !target.isAlive() || target == mc.player) {
            return false;
        }

        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, target)) {
            return false;
        }
        if (target instanceof net.minecraft.entity.player.PlayerEntity playerTarget
                && interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().isFriend(playerTarget.getUuid())) {
            return false;
        }
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(target)) {
                return false;
            }
        }

        if ((hitFilter.is("Skip Blockers") || hitFilter.is("Crits + Skip Blockers")) && target.isBlocking()) {
            return false;
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(mc.player)) {
            return false;
        }

        if ((hitFilter.is("Crits Only") || hitFilter.is("Crits + Skip Blockers")) && !isCrit()) {
            return false;
        }

        if (minArmorPoints.getValue() > 0 && target.getArmor() < minArmorPoints.getValue()) {
            return false;
        }

        if (requireEnchantedArmor.isEnabled() && !hasEnchantedArmor(target)) {
            return false;
        }

        int breachSlot = findBestBreachMace(mc.player.getInventory());
        if (breachSlot < 0) {
            return false;
        }

        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (currentSlot == breachSlot) {
            return false;
        }

        int returnSlot = currentSlot;

        if (!TickActionOrder.canSwitchSlot()) {
            return false;
        }
        // A full attack charge, read before the swap: the slot change only resets it at the next
        // player update, after this tick's attack.
        if (mc.player.getAttackCooldownProgress(0.5f) < 1.0f) {
            return false;
        }

        // 1. Swap to Breach Mace slot (Keystrokes compatible via SlotSync -> SystemInputSimulator)
        SlotSync.selectNow(mc, breachSlot);
        if (mc.player.getInventory().getSelectedSlot() != breachSlot) {
            return false;
        }

        // Arm the return as soon as the mace selection is confirmed. doAttack() can report
        // false when another hook or the vanilla target gate rejects the action after the
        // hotbar has already changed; waiting for a successful return value leaves the player
        // stuck on the mace in that case.
        swappedMaceSlot = breachSlot;
        armSwapBack(returnSlot);
        waitSwapDelay(ModuleDelays.clampMs(swapDelay.sample()));

        // 2. Execute same-tick attack with mace. The click is emitted first and any key press it left
        // is drained after the vanilla attack, so one click is one attack.
        boolean hitSuccess = false;
        reentrantAttack = true;
        try {
            if (trueClicks.isEnabled()) {
                AttackValidator.emitAttackClick(mc);
            }
            if (mc instanceof MinecraftClientAccessor accessor) {
                interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(accessor);
                hitSuccess = AttackValidator.invokeVanillaAttack(mc);
            }
            suppressAttackInput();
        } finally {
            reentrantAttack = false;
        }

        if (!hitSuccess) {
            // Never strand the player on the mace after a rejected attack, even with Swap Back off. The
            // restore is queued for the next interval head (Rule 5), never sent in this call frame; it is
            // skipped there if the player has already moved off the mace.
            swapBackPending = false;
            swappedMaceSlot = -1;
            swapBackTargetSlot = -1;
            TickActionOrder.deferSlot(returnSlot, breachSlot);
            return true;
        }

        TickActionOrder.recordAttack();

        // Dispatch MaceHitEvent
        try {
            EventBus.post(new MaceHitEvent());
        } catch (Throwable ignored) {}

        // 3. Return true to cancel the original sword click
        return true;
    }

    private void armSwapBack(int returnSlot) {
        if (returnSlot < 0 || returnSlot >= 9) {
            return;
        }
        swapBackPending = true;
        swapBackTargetSlot = returnSlot;
        swapBackDueNs = System.nanoTime() + ModuleDelays.clampMs(swapBackDelay.sample()) * 1_000_000L;
    }

    /**
     * Swap Delay: a short in-thread wait between the confirmed mace selection and the hit, inside the
     * click's own tick (the hit is vanilla's synchronous doAttack, so it cannot be moved to a later
     * frame without leaving the tick). Capped so the remaining tick budget never drops below
     * {@link #MIN_TICK_BUDGET_MS}; the swap and the hit always share the tick.
     */
    private static void waitSwapDelay(long delayMs) {
        if (delayMs <= 0L) return;
        double budget = SubTickSequencer.remainingTickBudgetMs() - MIN_TICK_BUDGET_MS;
        long waitNs = Math.round(Math.min(delayMs, Math.max(0.0, budget)) * 1_000_000.0);
        if (waitNs <= 0L) return;
        long deadline = System.nanoTime() + waitNs;
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    private static final double MIN_TICK_BUDGET_MS = 8.0;

    private void suppressAttackInput() {
        if (mc.options != null && mc.options.attackKey != null) {
            while (mc.options.attackKey.wasPressed()) {}
            mc.options.attackKey.setPressed(false);
        }
    }

    private void executeSwapBack() {
        swapBackPending = false;
        if (mc.player == null) return;
        int targetSlot = swapBackTargetSlot;
        int maceSlot = swappedMaceSlot;
        swappedMaceSlot = -1;
        // Only restore while we still own the selection; if the player or another module
        // already moved off the mace slot, leave their choice alone.
        if (maceSlot >= 0 && mc.player.getInventory().getSelectedSlot() != maceSlot) {
            swapBackTargetSlot = -1;
            return;
        }
        if (targetSlot < 0 || targetSlot >= 9
                || !isHoldingWeaponStack(mc.player.getInventory().getStack(targetSlot))) {
            targetSlot = findWeaponSlot();
        }
        if (targetSlot >= 0 && targetSlot < 9) {
            SlotSync.restore(mc, targetSlot);
        }
        swapBackTargetSlot = -1;
    }

    private int findWeaponSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isHoldingWeaponStack(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCrit() {
        if (mc.player == null) return false;
        return !mc.player.isOnGround()
                && mc.player.fallDistance > 0.0f
                && !mc.player.isSprinting()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !mc.player.hasVehicle()
                && !mc.player.isGliding();
    }

    /** The living entity under the live ray: vanilla's crosshair, or the silent angles while a silent rotation is active. */
    private LivingEntity lookTarget() {
        if (mc.player == null) return null;
        HitResult hit = RotationManager.isSilentRotationActive()
                ? RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(),
                        mc.player.getEntityInteractionRange())
                : mc.crosshairTarget;
        return hit instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le ? le : null;
    }

    private boolean isHoldingWeapon(ClientPlayerEntity player) {
        if (player == null) return false;
        return isHoldingWeaponStack(player.getMainHandStack());
    }

    private boolean isHoldingWeaponStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.getItem() instanceof AxeItem;
    }

    private boolean hasEnchantedArmor(LivingEntity living) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack stack = living.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.hasEnchantments()) {
                return true;
            }
        }
        return false;
    }

    private int findBestBreachMace(PlayerInventory inv) {
        int bestSlot = -1;
        int highestBreachLevel = 0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof MaceItem)) {
                continue;
            }

            int breachLevel = getEnchantmentLevel(stack, Enchantments.BREACH);
            if (breachLevel > highestBreachLevel) {
                highestBreachLevel = breachLevel;
                bestSlot = i;
            } else if (breachLevel > 0 && bestSlot == -1) {
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private int getEnchantmentLevel(ItemStack stack, RegistryKey<Enchantment> enchantmentKey) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MaceItem)) return 0;
        if (mc.world != null) {
            try {
                var reg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                int lvl = EnchantmentHelper.getLevel(reg.getOrThrow(enchantmentKey), stack);
                if (lvl > 0) return lvl;
            } catch (Throwable ignored) {}
        }
        try {
            ItemEnchantmentsComponent enchants = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
            for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
                Optional<RegistryKey<Enchantment>> keyOpt = entry.getKey();
                if (keyOpt.isPresent() && keyOpt.get().equals(enchantmentKey)) {
                    return enchants.getLevel(entry);
                }
                String path = entry.getKey().map(k -> k.getValue().getPath()).orElse("");
                if ("breach".equalsIgnoreCase(path)) {
                    return enchants.getLevel(entry);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private void resetState() {
        this.reentrantAttack = false;
        this.swapBackPending = false;
        this.swapBackTargetSlot = -1;
        this.swappedMaceSlot = -1;
        this.swapBackDueNs = 0L;
    }

    private void trackFall() {
        if (mc.player == null) return;
        if (mc.player.isOnGround()) {
            highestY = mc.player.getY();
        } else {
            highestY = Math.max(highestY, mc.player.getY());
        }
    }

    private double currentFallDistance() {
        if (mc.player == null || mc.player.isOnGround()) return 0.0;
        double trackedFall = Math.max(0.0, highestY - mc.player.getY());
        double vanillaFall = Math.max(0.0, (double) mc.player.fallDistance);
        return Math.max(trackedFall, vanillaFall);
    }
}
