package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class SwordHit extends Module implements AttackHandleAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // Kept (unregistered) so configs that saved it still load; the StDev companion now supplies variance.
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting swapBackTicks = new SliderSetting("Swap Back Delay (ms)", 100, 0, 500, 0)
            .withJitter(50)
            .setDescription("How long the sword stays selected after the hit before your previous item comes back. Rounded to game ticks (50 ms each); the swap back always waits at least until the next tick, even at 0. Higher feels steadier during fast trades.");
    private final BooleanSetting requireTarget = new BooleanSetting("Require Target", true)
            .setDescription("Only swap when your crosshair is actually on an entity within reach, so swinging at the air or at blocks never pulls out the sword.");
    private final BooleanSetting onlyPlayers = new BooleanSetting("Only Players", true)
            .setDescription("Only swap for hits on other players, not on mobs or other entities.");
    private final SliderSetting reach = new SliderSetting("Reach", 3.0, 1.0, 3.0, 1)
            .setDescription("Farthest target, in blocks, that counts for Require Target. Capped at vanilla's 3.0.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Never swap to the sword while you are in water or lava; the click lands with the item you hold.");
    private final BooleanSetting disableInAir = new BooleanSetting("Disable In Air", false)
            .setDescription("Never swap to the sword while you are off the ground, e.g. during crit jumps.");

    // Held Item Category Filters
    private final BooleanSetting axes = new BooleanSetting("Axes", true)
            .setDescription("Swaps to sword when attacking while holding an axe.");
    private final BooleanSetting gapples = new BooleanSetting("Gapples", true)
            .setDescription("Swaps to sword when attacking while holding a golden apple or enchanted golden apple.");
    private final BooleanSetting pearls = new BooleanSetting("Pearls", true)
            .setDescription("Swaps to sword when attacking while holding an ender pearl.");
    private final BooleanSetting crystals = new BooleanSetting("Crystals", true)
            .setDescription("Swaps to sword when attacking while holding an end crystal.");
    private final BooleanSetting totems = new BooleanSetting("Totems", true)
            .setDescription("Swaps to sword when attacking while holding a totem of undying.");
    private final BooleanSetting anchors = new BooleanSetting("Anchors", true)
            .setDescription("Swaps to sword when attacking while holding a respawn anchor.");
    private final BooleanSetting glowstone = new BooleanSetting("Glowstone", true)
            .setDescription("Swaps to sword when attacking while holding glowstone.");
    private final BooleanSetting potions = new BooleanSetting("Potions", true)
            .setDescription("Swaps to sword when attacking while holding a potion.");
    private final BooleanSetting windCharges = new BooleanSetting("Wind Charges", true)
            .setDescription("Swaps to sword when attacking while holding a wind charge or breeze rod.");
    private final BooleanSetting cobwebs = new BooleanSetting("Cobwebs", true)
            .setDescription("Swaps to sword when attacking while holding cobwebs.");
    private final BooleanSetting shields = new BooleanSetting("Shields", true)
            .setDescription("Swaps to sword when attacking while holding a shield.");
    private final BooleanSetting bows = new BooleanSetting("Bows & Crossbows", true)
            .setDescription("Swaps to sword when attacking while holding a bow or crossbow.");
    private final BooleanSetting nonWeapons = new BooleanSetting("General Non-Weapons", true)
            .setDescription("Swaps to sword when attacking while holding any non-weapon item (food, blocks, materials, utilities).");
    private final BooleanSetting maces = new BooleanSetting("Maces", false)
            .setDescription("Swaps to sword when attacking while holding a mace.");
    private final BooleanSetting tridents = new BooleanSetting("Tridents", false)
            .setDescription("Swaps to sword when attacking while holding a trident.");

    private boolean pendingSwapBack = false;
    private int originalSlot = -1;
    /** The sword slot this module selected; the swap-back only applies while it is still held. */
    private int ownedSlot = -1;
    private int ticksLeft = 0;

    public SwordHit() {
        super("SwordHit", "When you left-click an enemy while holding a non-sword item from the list below (axe, gapple, pearl, crystal, totem, blocks...), SwordHit swaps to the sword in your hotbar so the hit lands with sword damage, then swaps back to what you were holding after the Swap Back Delay. The swap is a real synchronized slot change made just before vanilla's own attack.", Category.COMBAT);
        swapBackTicks.setSection("Delays");
        addSetting(swapBackTicks);
        addSetting(requireTarget);
        addSetting(onlyPlayers);
        addSetting(reach);
        addSetting(disableInLiquids);
        addSetting(disableInAir);

        addSetting(axes);
        addSetting(gapples);
        addSetting(pearls);
        addSetting(crystals);
        addSetting(totems);
        addSetting(anchors);
        addSetting(glowstone);
        addSetting(potions);
        addSetting(windCharges);
        addSetting(cobwebs);
        addSetting(shields);
        addSetting(bows);
        addSetting(nonWeapons);
        addSetting(maces);
        addSetting(tridents);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        restoreOwnedSlot();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        restoreOwnedSlot();
        resetState();
    }

    /** Swap-back, only while the slot this module selected is still the held one; deferred past an attack. */
    private void restoreOwnedSlot() {
        if (pendingSwapBack && mc.player != null && originalSlot >= 0 && originalSlot < 9
                && mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        if (pendingSwapBack) {
            if (ticksLeft > 0) {
                ticksLeft--;
            }
            if (ticksLeft <= 0) {
                restoreOwnedSlot();
                pendingSwapBack = false;
                originalSlot = -1;
                ownedSlot = -1;
            }
        }
    }

    @Override
    public boolean handleAttack() {
        if (mc.player == null || mc.world == null) {
            return false;
        }

        if (!TickActionOrder.canSwitchSlot()) {
            return false;
        }

        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) {
            return false;
        }

        ShieldDrainEvent drain = new ShieldDrainEvent();
        EventBus.post(drain);
        if (drain.isActive()) {
            return false;
        }

        // Read at the click: no swap is made while a gate holds (a swap-back already pending still runs).
        if (disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(mc.player)) {
            return false;
        }
        if (disableInAir.isEnabled() && MeleeMechanics.isInAir(mc.player)) {
            return false;
        }

        if (requireTarget.isEnabled()) {
            LivingEntity target = getTarget();
            if (target == null) {
                return false;
            }
        }

        ItemStack held = mc.player.getMainHandStack();
        if (!shouldSwapForHeldItem(held)) {
            return false;
        }

        int swordSlot = findBestSwordSlot();
        if (swordSlot == -1) {
            return false;
        }

        int current = mc.player.getInventory().getSelectedSlot();
        if (current == swordSlot) {
            return false;
        }

        // The swap must precede the attack inside this packet interval; if an attack or use already
        // went out, leave the click alone rather than swap after it.
        if (!SlotSync.select(mc, swordSlot)) {
            return false;
        }
        // Vanilla's attack follows straight after this hook: keep the sampled swap-to-action gap.
        SlotSync.paceAfterSwap();
        if (!pendingSwapBack) {
            originalSlot = current;
        }
        pendingSwapBack = true;
        ownedSlot = swordSlot;
        // Swap Back Delay is the hold duration; its StDev companion adds the variance.
        ticksLeft = Math.max(1, interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.msToTicks(swapBackTicks.sample()));

        // Return false so Minecraft's doAttack proceeds to execute the attack with the selected sword
        return false;
    }

    private boolean shouldSwapForHeldItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return nonWeapons.isEnabled();
        }

        // Already holding a sword -> no swap needed
        if (CombatItemUtil.isSword(stack)) {
            return false;
        }

        if (totems.isEnabled() && stack.isOf(Items.TOTEM_OF_UNDYING)) return true;
        if (axes.isEnabled() && CombatItemUtil.isAxe(stack)) return true;
        if (gapples.isEnabled() && (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE))) return true;
        if (pearls.isEnabled() && stack.isOf(Items.ENDER_PEARL)) return true;
        if (crystals.isEnabled() && stack.isOf(Items.END_CRYSTAL)) return true;
        if (anchors.isEnabled() && stack.isOf(Items.RESPAWN_ANCHOR)) return true;
        if (glowstone.isEnabled() && stack.isOf(Items.GLOWSTONE)) return true;
        if (potions.isEnabled() && (stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION))) return true;
        if (windCharges.isEnabled() && (stack.isOf(Items.WIND_CHARGE) || stack.isOf(Items.BREEZE_ROD))) return true;
        if (cobwebs.isEnabled() && stack.isOf(Items.COBWEB)) return true;
        if (shields.isEnabled() && stack.isOf(Items.SHIELD)) return true;
        if (bows.isEnabled() && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW))) return true;
        if (maces.isEnabled() && CombatItemUtil.isMace(stack)) return true;
        if (tridents.isEnabled() && stack.getItem() instanceof TridentItem) return true;

        if (nonWeapons.isEnabled() && !CombatItemUtil.isWeapon(stack)) {
            return true;
        }

        return false;
    }

    private LivingEntity getTarget() {
        net.minecraft.util.hit.HitResult crosshair = RotationManager.isSilentRotationActive()
                ? RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(),
                        Math.min(reach.getValue(), 3.0))
                : mc.crosshairTarget;
        if (crosshair instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity le && le.isAlive() && le != mc.player
                && isAllowedTarget(le)) {
            if (!onlyPlayers.isEnabled() || le instanceof PlayerEntity) {
                return le;
            }
        }
        if (mc.player == null || mc.world == null) {
            return null;
        }

        double maxReach = Math.min(reach.getValue(), 3.0);
        Vec3d eye = mc.player.getEyePos();
        Vec3d rotVec = RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentRotationVec()
                : mc.player.getRotationVec(1.0f);
        Vec3d end = eye.add(rotVec.multiply(maxReach));
        LivingEntity best = null;
        double bestSq = maxReach * maxReach;

        for (Entity e : mc.world.getOtherEntities(mc.player, mc.player.getBoundingBox().expand(maxReach))) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive() || le.isSpectator()
                    || !isAllowedTarget(le)) {
                continue;
            }
            if (onlyPlayers.isEnabled() && !(le instanceof PlayerEntity)) {
                continue;
            }
            Box box = le.getBoundingBox();
            if (box.contains(eye)) {
                return le;
            }
            Optional<Vec3d> hit = box.raycast(eye, end);
            if (hit.isPresent()) {
                double d = eye.squaredDistanceTo(hit.get());
                if (d < bestSq) {
                    bestSq = d;
                    best = le;
                }
            }
        }
        return best;
    }

    /** Sticky-target lock, friends and AntiBot-flagged entities: a swap is never made for a hit on them. */
    private boolean isAllowedTarget(LivingEntity le) {
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, le)) {
            return false;
        }
        if (le instanceof PlayerEntity p && FriendManager.getInstance().isFriend(p.getUuid())) {
            return false;
        }
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(le)) {
                return false;
            }
        }
        return true;
    }

    private int findBestSwordSlot() {
        if (mc.player == null) {
            return -1;
        }
        int bestSlot = -1;
        double bestScore = -1.0;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !CombatItemUtil.isSword(stack)) {
                continue;
            }
            double score = getSwordScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private double getSwordScore(ItemStack stack) {
        double score = 1.0;
        if (stack.isOf(Items.NETHERITE_SWORD)) score = 6.0;
        else if (stack.isOf(Items.DIAMOND_SWORD)) score = 5.0;
        else if (stack.isOf(Items.IRON_SWORD)) score = 4.0;
        else if (stack.isOf(Items.STONE_SWORD)) score = 3.0;
        else if (stack.isOf(Items.WOODEN_SWORD)) score = 2.0;
        else if (stack.isOf(Items.GOLDEN_SWORD)) score = 1.5;

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

    private void resetState() {
        pendingSwapBack = false;
        originalSlot = -1;
        ownedSlot = -1;
        ticksLeft = 0;
    }
}
