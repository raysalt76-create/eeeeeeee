package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;


/**
 * Container looting with bounded, screen-scoped click pacing.
 */
public class ChestSteal extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final BooleanSetting autoClose = new BooleanSetting("Auto Close", true)
            .setDescription("Closes the chest or shulker box by itself once every item it could move has been taken.");

    // ---- Delays ----
    /** Kept unregistered so old configs that saved it still load; the sliders below are always used. */
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting openDelay = new SliderSetting("Open Delay (ms)", 120, 0, 1000, 0)
            .withJitter(40)
            .setDescription("How long to wait after the container opens before the first item is taken, like a player looking at the contents first.");
    private final SliderSetting clickDelay = new SliderSetting("Click Delay (ms)", 55, 0, 500, 0)
            .withJitter(15)
            .setDescription("Wait between two shift-clicks. Lower loots faster; very low values look automated to servers that time inventory clicks.");
    private final SliderSetting closeDelay = new SliderSetting("Close Delay (ms)", 90, 0, 1000, 0)
            .withJitter(25)
            .setDescription("Wait between taking the last item and closing the container.")
            .setDependsOn("Auto Close");

    private long nextActionTime = 0L;
    private ScreenHandler activeHandler;
    private Object activePlayer;
    private final java.util.Set<Integer> blockedSlots = new java.util.HashSet<>();
    private final java.util.List<ItemStack> slotSnapshot = new java.util.ArrayList<>();
    private boolean awaitingClose = false;
    private long closeAtTime = 0L;

    public ChestSteal() {
        super("ChestSteal", "Empties chests, barrels and shulker boxes into your inventory while the container is open, one shift-click at a time with configurable, randomised pacing, then optionally closes it.", Category.PLAYER);
        openDelay.setSection("Delays");
        clickDelay.setSection("Delays");
        closeDelay.setSection("Delays");
        addSetting(autoClose);
        addSetting(openDelay);
        addSetting(clickDelay);
        addSetting(closeDelay);
    }

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.interactionManager == null) {
            resetSession();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) {
            resetSession();
            return;
        }

        int containerSlotCount = getContainerSlotCount(handler);
        if (containerSlotCount <= 0) {
            resetSession();
            return;
        }

        long now = System.currentTimeMillis();
        if (handler != activeHandler || mc.player != activePlayer) {
            resetSession();
            activeHandler = handler;
            activePlayer = mc.player;
            nextActionTime = now + ModuleDelays.clampMs(openDelay.sample());
        }
        if (slotsChanged(handler)) {
            blockedSlots.clear();
            captureSlots(handler);
            awaitingClose = false;
        }

        if (awaitingClose) {
            if (now >= closeAtTime) {
                mc.player.closeHandledScreen();
                resetSession();
            }
            return;
        }

        if (now < nextActionTime) return;

        // Search for the next lootable container slot
        int targetSlot = -1;
        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = handler.getSlot(i);
            if (slot.hasStack() && !slot.getStack().isEmpty() && !blockedSlots.contains(i)) {
                targetSlot = i;
                break;
            }
        }

        if (targetSlot != -1) {
            ItemStack before = handler.getSlot(targetSlot).getStack().copy();
            // Shared, syncId-bound click path. A paced (deferred) click has not run yet, so an
            // unchanged slot only means "unmovable" when the click went out synchronously.
            boolean ran = interstellarTikiTiks.apexx.client.util.SystemInputSimulator.clickSlotBound(handler.syncId, targetSlot, 0, SlotActionType.QUICK_MOVE);
            ItemStack after = handler.getSlot(targetSlot).getStack();
            if (ran && ItemStack.areEqual(before, after)) blockedSlots.add(targetSlot);
            captureSlots(handler);
            nextActionTime = now + ModuleDelays.clampMs(clickDelay.sample());
        } else {
            // Container is empty
            boolean empty = true;
            for (int i = 0; i < containerSlotCount; i++) if (handler.getSlot(i).hasStack()) { empty = false; break; }
            if (empty && autoClose.isEnabled() && !awaitingClose) {
                awaitingClose = true;
                closeAtTime = now + ModuleDelays.clampMs(closeDelay.sample());
            }
        }
    }

    private boolean slotsChanged(ScreenHandler handler) {
        if (slotSnapshot.size() != handler.slots.size()) return true;
        for (int i = 0; i < slotSnapshot.size(); i++) {
            if (!ItemStack.areEqual(slotSnapshot.get(i), handler.getSlot(i).getStack())) return true;
        }
        return false;
    }

    private void captureSlots(ScreenHandler handler) {
        slotSnapshot.clear();
        for (Slot slot : handler.slots) slotSnapshot.add(slot.getStack().copy());
    }

    private int getContainerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) {
            return generic.getRows() * 9;
        }
        if (handler instanceof ShulkerBoxScreenHandler) {
            return 27;
        }
        return 0;
    }

    @Override protected void onRuntimeStateReleased() { resetSession(); }

    private void resetSession() {
        activeHandler = null;
        activePlayer = null;
        blockedSlots.clear();
        slotSnapshot.clear();
        nextActionTime = 0L;
        awaitingClose = false;
        closeAtTime = 0L;
    }
}
