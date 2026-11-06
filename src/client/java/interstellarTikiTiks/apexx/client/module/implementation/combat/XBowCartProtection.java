package interstellarTikiTiks.apexx.client.module.implementation.combat;


import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * XBowCartProtection — Automatically detects incoming TNT Minecarts, aims, and fires a loaded Crossbow to detonate or deflect them safely.
 * 
 * <p>Ported with 1:1 functional parity from Orchard reference client (q96.x.lllllIl).
 */
public class XBowCartProtection extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting protectionRange = new SliderSetting("Range", 4.5, 2.0, 8.0, 1)
            .setDescription("Distance in blocks within which an incoming TNT minecart is shot. Shooting it while it is still a few blocks away detonates it before it reaches you.");
    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Client", "Silent")
            .setDescription("How the crossbow is aimed at the cart. Client: your visible camera turns onto the cart. Silent: only the rotation the server sees turns, your screen stays put.");
    private final ModeSetting rotationPattern = new ModeSetting("Rotation Pattern", "Smooth", "Sine", "Linear", "Instant")
            .setDescription("Shape of the turn toward the cart. Smooth, Sine and Linear ease in like a hand-made flick; Instant snaps in one frame, which strict anticheats flag.");

    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0)
            .withJitter(15)
            .setDescription("Wait after the shot before your previous hotbar item is reselected. Rounded to whole ticks with at least one tick, so the swap back never shares the shot's tick.")
            .setSection("Delays");
    private final SliderSetting aimTimeout = new SliderSetting("Aim Timeout (ms)", 1500, 300, 4000, 0)
            .setDescription("How long to keep aiming at one cart before giving up on it (for example when it is behind a wall). Too short gives up before the turn finishes.");

    private final Set<Integer> processedCartIds = new HashSet<>();
    private int returnSlot = -1;
    /** The crossbow slot this module selected; the swap back only applies while it is still held. */
    private int ownedSlot = -1;
    private int ticksRemaining = 0;
    private boolean waitingSwapBack = false;
    private TntMinecartEntity aimCart = null;
    private long aimStartMs = 0L;

    public XBowCartProtection() {
        super("XBowCartProtection", "Defends against TNT minecart attacks: when a TNT minecart comes within range, it swaps to a loaded crossbow in your hotbar, aims at the cart and fires once the live aim is actually on it, detonating the cart before it reaches you. Your previous item is reselected afterwards. Needs a pre-loaded crossbow.", Category.COMBAT);
        setSubCategory(SubCategory.CART);
        addSetting(protectionRange);
        addSetting(rotationMode);
        addSetting(rotationPattern);
        addSetting(swapBackDelay);
        addSetting(aimTimeout);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        recoverSlot();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        recoverSlot();
        resetState();
    }

    /** Tick head, ahead of this tick's movement packet: the swap and the shot belong to the same interval. */
    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.interactionManager == null) {
            resetState();
            return;
        }

        if (waitingSwapBack) {
            if (--ticksRemaining <= 0) {
                recoverSlot();
                RotationManager.stop(this);
                waitingSwapBack = false;
                returnSlot = -1;
                ownedSlot = -1;
            }
            return;
        }

        // AutoXBowCart (premium-only) places and shoots its own cart; defending against it would take over
        // the rotation (HIGHEST priority) and the crossbow slot mid-sequence. Queried through a hook so this
        // free-tier module never holds a hard class reference to a premium-only module.
        interstellarTikiTiks.apexx.client.module.hooks.CartSequenceAware own =
                interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledHook(
                        interstellarTikiTiks.apexx.client.module.hooks.CartSequenceAware.class);
        if (own != null && own.isSequenceActive()) {
            if (aimCart != null) {
                aimCart = null;
                RotationManager.stop(this);
            }
            return;
        }

        TntMinecartEntity targetCart = aimCart;
        if (targetCart != null && (targetCart.isRemoved() || !targetCart.isAlive()
                || mc.player.squaredDistanceTo(targetCart) > protectionRange.getValue() * protectionRange.getValue())) {
            targetCart = null;
            aimCart = null;
            RotationManager.stop(this);
        }
        if (targetCart == null) {
            targetCart = findTargetCart();
        }
        if (targetCart == null) {
            return;
        }

        int xbowSlot = findLoadedCrossbowSlot(mc.player.getInventory());
        if (xbowSlot < 0) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (aimCart != targetCart) {
            aimCart = targetCart;
            aimStartMs = nowMs;
        } else if (nowMs - aimStartMs > Math.round(aimTimeout.getValue())) {
            // Never shoot blind: the wire rotation did not reach the cart in time.
            processedCartIds.add(targetCart.getId());
            aimCart = null;
            RotationManager.stop(this);
            return;
        }

        Vec3d targetPos = targetCart.getBoundingBox().getCenter().add(0.0, 0.12, 0.0);
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d arrowOrigin = new Vec3d(eyePos.x, eyePos.y - 0.15, eyePos.z);
        Vec3d diff = targetPos.subtract(arrowOrigin);

        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        double flightTicks = Math.max(0.05, distXZ / 3.15);
        double gravityDrop = 0.5 * 0.05 * flightTicks * flightTicks;
        Vec3d compensatedTarget = targetPos.add(0.0, gravityDrop, 0.0);

        float yaw = (float) (MathHelper.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(diff.y + gravityDrop, distXZ) * (180.0 / Math.PI)));

        boolean isSilent = "Silent".equalsIgnoreCase(rotationMode.getCurrentMode());
        RotationManager.RotationMode mode;
        try {
            mode = RotationManager.RotationMode.valueOf(rotationPattern.getCurrentMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = RotationManager.RotationMode.SMOOTH;
        }
        if (!isSilent) {
            RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST,
                    () -> RotationManager.pointAlongRotation(yaw, pitch),
                    100.0, mode, 0.0, 0L, false, false);
        } else {
            RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST, () -> compensatedTarget, 100.0, mode, 0.0, 0L, true, false);
        }

        // Fire only when the rotation that goes on the wire has a live ray into the cart's hitbox.
        if (!interstellarTikiTiks.apexx.client.util.combat.WireRay.onEntity(mc, targetCart, protectionRange.getValue())) {
            return;
        }

        // Crossbow slot first, in vanilla order: when the hotbar cannot change yet this packet
        // interval (or the selection fails) nothing is fired and the cart is retried next tick.
        int currentSlot = getSelectedSlot();
        if (currentSlot != xbowSlot && (!TickActionOrder.canSwitchSlot() || !selectSlotLegit(xbowSlot))) {
            return;
        }
        processedCartIds.add(targetCart.getId());
        aimCart = null;
        this.returnSlot = currentSlot;
        this.ownedSlot = xbowSlot;

        // The wire ray was just validated, so hand the seam the angles that are on the wire.
        boolean silentActive = RotationManager.isSilentRotationActive();
        float wireYaw = silentActive ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float wirePitch = silentActive ? RotationManager.getFinalPitch() : mc.player.getPitch();
        interstellarTikiTiks.apexx.client.util.combat.InteractSim.useItem(mc, Hand.MAIN_HAND, wireYaw, wirePitch, isSilent);

        this.waitingSwapBack = true;
        this.ticksRemaining = Math.max(1, ModuleDelays.msToTicks(swapBackDelay.sample()));
    }

    private TntMinecartEntity findTargetCart() {
        if (mc.world == null || mc.player == null) return null;

        double maxDistSq = protectionRange.getValue() * protectionRange.getValue();
        TntMinecartEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof TntMinecartEntity cart) || cart.isRemoved() || !cart.isAlive()) {
                continue;
            }

            if (processedCartIds.contains(cart.getId())) {
                continue;
            }

            double distSq = mc.player.squaredDistanceTo(cart);
            if (distSq <= maxDistSq && distSq < closestDistSq) {
                closest = cart;
                closestDistSq = distSq;
            }
        }

        return closest;
    }

    private int findLoadedCrossbowSlot(PlayerInventory inventory) {
        if (inventory == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.CROSSBOW) && isLoadedCrossbow(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isLoadedCrossbow(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof CrossbowItem)) {
            return false;
        }
        ChargedProjectilesComponent component = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        return component != null && !component.isEmpty();
    }

    private boolean selectSlotLegit(int slot) {
        if (mc.player == null || mc.options == null || mc.options.hotbarKeys == null
                || slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            return true;
        }
        return SlotSync.select(mc, slot);
    }

    private int getSelectedSlot() {
        if (mc.player == null) return 0;
        return ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
    }

    /** Back to the previous slot, only while the crossbow slot this module selected is still held. */
    private void recoverSlot() {
        if (returnSlot >= 0 && returnSlot < 9 && mc.player != null && ownedSlot != returnSlot
                && mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            SlotSync.restore(mc, returnSlot);
        }
    }

    private void resetState() {
        this.processedCartIds.clear();
        this.returnSlot = -1;
        this.ownedSlot = -1;
        this.ticksRemaining = 0;
        this.waitingSwapBack = false;
        this.aimCart = null;
        RotationManager.stop(this);
    }
}
