package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.SpearReachProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * SpearReach - hits from the spear's 4.5-block reach while a sword is in hand. When a target is past the
 * sword's 3.0 reach but inside the spear's, the hotbar spear is selected, the hit lands with it, and the
 * sword slot is selected again after the Swap Back delay. Works on your own left click and through
 * TriggerBot ({@link SpearReachProvider}); targets inside 3.0 are still hit with the sword.
 */
public class SpearReach extends Module implements AttackHandleAware, SpearReachProvider {

    private static final double MELEE_REACH = 3.0;

    private final SliderSetting reach = new SliderSetting("Spear Reach", 4.5, 3.1, 4.5, 2)
            .setDescription("How far, in blocks, a hit can land by swapping to the spear. 4.5 is the spear's real range in 1.21.11.");
    private final BooleanSetting ownClick = new BooleanSetting("Own Click", true)
            .setDescription("Your own left click swaps to the spear when the target under your crosshair is beyond the sword's 3.0 reach and within Spear Reach. Off: only TriggerBot uses the spear reach.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 60, 0, 500, 0)
            .withJitter(15)
            .setSection("Delays")
            .setDescription("How long after the hit the sword slot is selected again.");

    private int spearSlot = -1;
    private int returnSlot = -1;
    private long returnAtMs = 0L;
    private boolean reentrant = false;

    public SpearReach() {
        super("SpearReach", "Hit from spear reach (4.5 blocks) while holding a sword: when a target is past the sword's reach, the spear is swapped in for the hit and the sword comes back right after. Works on your own click and with TriggerBot.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        addSetting(reach);
        addSetting(ownClick);
        addSetting(swapBackDelay);
    }

    // -- hooks -----------------------------------------------------------------------------

    @Override
    public boolean handleAttack() {
        if (!ownClick.isEnabled() || reentrant || !canWork() || pendingReturn()) {
            return false;
        }
        EntityHitResult hit = beyondMeleeTarget();
        return hit != null && attackFromSpearReach(hit);
    }

    @Override
    public double spearReach() {
        if (!isEnabled() || !canWork() || pendingReturn() || findSpearSlot() < 0) {
            return 0.0;
        }
        return reach.getValue();
    }

    @Override
    public boolean attackFromSpearReach(EntityHitResult hit) {
        if (!isEnabled() || reentrant || !canWork() || pendingReturn() || hit == null
                || !(hit.getEntity() instanceof LivingEntity target) || !isValidTarget(target)) {
            return false;
        }
        double distance = mc.player.getEyePos().distanceTo(hit.getPos());
        if (distance <= MELEE_REACH + 0.05 || distance > reach.getValue() + 1.0E-4 || mc.attackCooldown > 0) {
            return false;
        }
        int slot = findSpearSlot();
        if (slot < 0 || !TickActionOrder.canSwitchSlot()) {
            return false;
        }
        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) {
            return false;
        }
        int previous = mc.player.getInventory().getSelectedSlot();
        if (!SlotSync.select(mc, slot)) {
            return false;
        }
        spearSlot = slot;
        returnSlot = previous;
        returnAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelay.sample());

        HitResult previousCrosshair = mc.crosshairTarget;
        mc.crosshairTarget = hit;
        reentrant = true;
        boolean fired;
        try {
            fired = AttackValidator.invokeVanillaAttack(mc);
        } finally {
            reentrant = false;
            mc.crosshairTarget = previousCrosshair;
        }
        if (!fired) {
            returnAtMs = 0L; // nothing was hit: give the sword back at the next tick head
        }
        return fired;
    }

    // -- swap back -------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (spearSlot < 0 || mc.player == null) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != spearSlot) {
            clearReturn(); // the player or another module moved the hotbar: nothing of ours to restore
            return;
        }
        if (System.currentTimeMillis() >= returnAtMs) {
            SlotSync.restore(mc, returnSlot);
            clearReturn();
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (spearSlot >= 0 && mc != null && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == spearSlot) {
            SlotSync.restore(mc, returnSlot);
        }
        clearReturn();
        reentrant = false;
    }

    // -- helpers ---------------------------------------------------------------------------

    private boolean pendingReturn() {
        return spearSlot >= 0;
    }

    private void clearReturn() {
        spearSlot = -1;
        returnSlot = -1;
        returnAtMs = 0L;
    }

    private boolean canWork() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && !mc.player.isSpectator() && !mc.player.isUsingItem()
                && CombatItemUtil.isSword(mc.player.getMainHandStack());
    }

    private EntityHitResult beyondMeleeTarget() {
        boolean silent = RotationManager.isSilentRotationActive();
        float yaw = silent ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = silent ? RotationManager.getFinalPitch() : mc.player.getPitch();
        HitResult ray = RotationManager.getCrosshairTarget(yaw, pitch, reach.getValue());
        if (!(ray instanceof EntityHitResult hit)) {
            return null;
        }
        return mc.player.getEyePos().distanceTo(hit.getPos()) > MELEE_REACH + 0.05 ? hit : null;
    }

    private boolean isValidTarget(LivingEntity target) {
        return target != mc.player && target.isAlive() && !FriendManager.getInstance().isFriend(target.getUuid());
    }

    private int findSpearSlot() {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().contains("spear")
                    && !(stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1)) {
                return i;
            }
        }
        return -1;
    }
}
