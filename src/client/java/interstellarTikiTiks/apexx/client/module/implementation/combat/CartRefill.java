package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Puts a TNT minecart (or a rail, per Item) back into a hotbar slot after the one there has been used up.
 *
 * <p>The move is one vanilla hotbar-swap click (a number key over an inventory slot) on the player's
 * own screen handler (syncId 0), bound to that syncId. With "Open Inventory" on it is performed the way a
 * player does it: open the inventory, swap, close. Each step is separated by its own delay slider.
 *
 * <p>It never starts (or keeps) a refill while AutoCart, AutoXBowCart or FastCart is mid-cycle, and waits a short
 * grace period after they go idle, so the inventory never closes over a placement or steals the cycle's slot.
 *
 * <p>Handler slot ids: main inventory index 9..35 is slot id 9..35, hotbar index h is slot id 36 + h.
 */
public class CartRefill extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting item = new ModeSetting("Item", "TNT Minecart", "Rail")
            .setDescription("What to restock into the slot: TNT minecarts, or rails (any rail type).");
    private final ModeSetting mode = new ModeSetting("Mode", "Key", "Auto")
            .setDescription("Key: refill once each time Refill Key is pressed. Auto: refill by itself whenever the target slot has run out of the chosen item.");
    private final BooleanSetting refillKey = new BooleanSetting("Refill Key", false)
            .setBindable(true)
            .setDescription("Bind a key here. Each press refills the target slot once (Key mode).")
            .setDependsOn("Mode:Key");
    private final ModeSetting target = new ModeSetting("Target Slot", "Selected Slot", "Slot 1", "Slot 2", "Slot 3",
            "Slot 4", "Slot 5", "Slot 6", "Slot 7", "Slot 8", "Slot 9")
            .setDescription("Hotbar slot to refill. Selected Slot follows the slot you are holding, but only refills it once it is empty after holding the chosen item.");
    private final BooleanSetting replaceOther = new BooleanSetting("Replace Other Items", false)
            .setDescription("Also swap the item into a fixed target slot that holds some other item (that item goes to the source's old inventory slot). Off: only empty slots are refilled.");
    private final BooleanSetting openInventory = new BooleanSetting("Open Inventory", true)
            .setDescription("Open the inventory for the swap and close it afterwards, as a player would. Off: swap on the player inventory without opening the screen.");
    private final BooleanSetting silent = new BooleanSetting("Silent", false)
            .setBlatant(true)
            .setDescription("Swaps without opening the inventory, even when Open Inventory is on. A player cannot click inventory slots with no inventory open, so servers that check this can flag it.");
    private final BooleanSetting hotbarSources = new BooleanSetting("Use Hotbar Carts", true)
            .setDescription("Also take the item from other hotbar slots, not only from the main inventory.");
    private final BooleanSetting skipOnXBowCart = new BooleanSetting("Skip After XBowCart", false)
            .setDescription("Does not refill while AutoXBowCart is running or for a few seconds after it ends.");
    private final SliderSetting xbowSkipWindow = new SliderSetting("XBowCart Skip Window (s)", 5.0, 0.0, 30.0, 1)
            .setDescription("How long after an AutoXBowCart sequence ends refills stay paused (Skip After XBowCart).")
            .setDependsOn("Skip After XBowCart");

    private final SliderSetting detectDelay = new SliderSetting("Detect Delay (ms)", 60, 0, 1000, 0).withJitter(15)
            .setDescription("Wait after the slot runs out (or the key is pressed) before starting the refill.")
            .setSection("Delays");
    private final SliderSetting clickDelay = new SliderSetting("Click Delay (ms)", 45, 0, 1000, 0).withJitter(12)
            .setDescription("Gap between each step: opening the inventory, the swap click, and closing it.")
            .setSection("Delays");
    private final SliderSetting recheckCooldown = new SliderSetting("Recheck Cooldown (ms)", 250, 0, 2000, 0).withJitter(40)
            .setDescription("After a refill (or a failed attempt), Auto mode waits this long before checking the slot again.")
            .setSection("Delays");
    private final SliderSetting idleGrace = new SliderSetting("Idle Grace (ms)", 150, 0, 1000, 0).withJitter(25)
            .setDescription("How long AutoCart, AutoXBowCart and FastCart must have been idle before a refill starts, so the inventory never opens right over a placement.")
            .setSection("Delays");

    private enum Stage { IDLE, WAIT_DETECT, WAIT_OPEN, WAIT_SWAP, WAIT_CLOSE }

    private Stage stage = Stage.IDLE;
    private long dueMs;
    private long cooldownUntilMs;
    private long busyUntilMs;
    private int targetSlot = -1;
    private boolean openedScreen;
    /** Per hotbar slot: it held the chosen item the last time it was looked at. */
    private final boolean[] heldCart = new boolean[9];

    public CartRefill() {
        super("CartRefill", "Keeps TNT minecarts (or rails) stocked: when your hotbar slot runs out, it moves more from your inventory into that slot, either when you press the refill key or automatically. Waits until AutoCart, AutoXBowCart and FastCart have finished their cycle, then uses a single vanilla inventory swap with configurable delays.", Category.PLAYER);
        setSubCategory(SubCategory.CART);
        addSetting(item);
        addSetting(mode);
        addSetting(refillKey);
        addSetting(target);
        addSetting(replaceOther);
        addSetting(openInventory);
        addSetting(silent);
        addSetting(hotbarSources);
        addSetting(skipOnXBowCart);
        addSetting(xbowSkipWindow);
        addSetting(detectDelay);
        addSetting(clickDelay);
        addSetting(recheckCooldown);
        addSetting(idleGrace);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        closeIfOpened();
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        closeIfOpened();
        reset();
    }

    private void reset() {
        stage = Stage.IDLE;
        dueMs = 0L;
        cooldownUntilMs = 0L;
        busyUntilMs = 0L;
        targetSlot = -1;
        openedScreen = false;
        java.util.Arrays.fill(heldCart, false);
        refillKey.setEnabled(false);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (cartModulesBusy()) {
            // Never start (or keep) a refill under a running cart cycle: it would close the
            // inventory over the placement or steal the slot the cycle is about to use.
            busyUntilMs = now + ModuleDelays.clampMs(idleGrace.sample());
            if (stage != Stage.IDLE) abort(now);
            rememberCarts();
            return;
        }
        switch (stage) {
            case IDLE -> idle(now);
            case WAIT_DETECT -> {
                if (now >= dueMs) beginMove(now);
            }
            case WAIT_OPEN -> {
                if (now >= dueMs) {
                    if (!(mc.currentScreen instanceof InventoryScreen)) {
                        abort(now);
                        return;
                    }
                    stage = Stage.WAIT_SWAP;
                    dueMs = now;
                }
            }
            case WAIT_SWAP -> {
                if (now >= dueMs) swap(now);
            }
            case WAIT_CLOSE -> {
                if (now >= dueMs) {
                    closeIfOpened();
                    finish(now);
                }
            }
        }
        rememberCarts();
    }

    private void idle(long now) {
        boolean keyPressed = refillKey.isEnabled();
        if (keyPressed) refillKey.setEnabled(false);
        boolean auto = mode.is("Auto");
        if (!auto && !keyPressed) return;
        if (auto && now < cooldownUntilMs) return;
        if (now < busyUntilMs) return;
        if (skipOnXBowCart.isEnabled() && AutoXBowCart.msSinceLastSequence() < (long) (xbowSkipWindow.getValue() * 1000.0)) return;

        int slot = resolveTarget();
        if (slot < 0 || !needsRefill(slot) || findSource(slot) < 0) return;
        targetSlot = slot;
        stage = Stage.WAIT_DETECT;
        dueMs = now + ModuleDelays.clampMs(detectDelay.sample());
    }

    private int resolveTarget() {
        String t = target.getCurrentMode();
        if (t.startsWith("Slot ")) {
            try {
                return Integer.parseInt(t.substring(5).trim()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return mc.player.getInventory().getSelectedSlot();
    }

    private boolean needsRefill(int slot) {
        ItemStack stack = mc.player.getInventory().getStack(slot);
        if (isWanted(stack)) return false;
        if (target.is("Selected Slot")) {
            // Only the slot that just ran out of carts, so holding a sword never pulls a cart over it.
            return stack.isEmpty() && heldCart[slot];
        }
        return stack.isEmpty() || replaceOther.isEnabled();
    }

    /** Handler slot id of a TNT minecart to take, or -1. */
    private int findSource(int hotbarTarget) {
        for (int i = 9; i <= 35; i++) {
            if (isWanted(mc.player.getInventory().getStack(i))) return i;
        }
        if (hotbarSources.isEnabled()) {
            for (int h = 0; h < 9; h++) {
                if (h == hotbarTarget) continue;
                if (isWanted(mc.player.getInventory().getStack(h))) return 36 + h;
            }
        }
        return -1;
    }

    private void beginMove(long now) {
        if (targetSlot < 0 || !needsRefill(targetSlot) || findSource(targetSlot) < 0) {
            abort(now);
            return;
        }
        if (openInventory.isEnabled() && !silent.isEnabled()) {
            if (mc.currentScreen == null) {
                mc.setScreen(new InventoryScreen(mc.player));
                openedScreen = true;
            } else if (!(mc.currentScreen instanceof InventoryScreen)) {
                abort(now);
                return;
            }
            stage = Stage.WAIT_OPEN;
            dueMs = now + ModuleDelays.clampMs(clickDelay.sample());
            return;
        }
        // Without the screen the player's own handler must be the active one.
        if (mc.currentScreen != null || mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            abort(now);
            return;
        }
        stage = Stage.WAIT_SWAP;
        dueMs = now;
    }

    private void swap(long now) {
        int source = findSource(targetSlot);
        if (source < 0 || !needsRefill(targetSlot)
                || mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            closeIfOpened();
            abort(now);
            return;
        }
        int syncId = mc.player.playerScreenHandler.syncId;
        SystemInputSimulator.clickSlotBound(syncId, source, targetSlot, SlotActionType.SWAP);
        if (openedScreen) {
            stage = Stage.WAIT_CLOSE;
            dueMs = now + ModuleDelays.clampMs(clickDelay.sample());
        } else {
            finish(now);
        }
    }

    private void closeIfOpened() {
        if (openedScreen && mc.player != null && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
        openedScreen = false;
    }

    private void finish(long now) {
        stage = Stage.IDLE;
        targetSlot = -1;
        cooldownUntilMs = now + ModuleDelays.clampMs(recheckCooldown.sample());
    }

    private void abort(long now) {
        closeIfOpened();
        finish(now);
    }

    private boolean isWanted(ItemStack stack) {
        if (item.is("Rail")) {
            return stack.isOf(Items.RAIL) || stack.isOf(Items.POWERED_RAIL)
                    || stack.isOf(Items.DETECTOR_RAIL) || stack.isOf(Items.ACTIVATOR_RAIL);
        }
        return stack.isOf(Items.TNT_MINECART);
    }

    private boolean cartModulesBusy() {
        AutoCart autoCart = ModuleAccess.getEnabledModule(AutoCart.class);
        if (autoCart != null && autoCart.isBusy()) return true;
        AutoXBowCart xbow = ModuleAccess.getEnabledModule(AutoXBowCart.class);
        if (xbow != null && xbow.isBusy()) return true;
        FastCart fastCart = ModuleAccess.getEnabledModule(FastCart.class);
        return fastCart != null && fastCart.isBusy();
    }

    private void rememberCarts() {
        for (int h = 0; h < 9; h++) {
            ItemStack stack = mc.player.getInventory().getStack(h);
            if (isWanted(stack)) heldCart[h] = true;
            else if (!stack.isEmpty()) heldCart[h] = false;
        }
    }
}
