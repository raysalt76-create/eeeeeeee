package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ModuleRandomDelay;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ElytraSwap extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // One-shot: each press performs a single swap (Tap) or repeats while held
    // (Repeat); a second press never cancels the in-flight swap.
    @Override
    public boolean isOneShot() {
        return true;
    }


    private int originalSlot = -1;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private int tickCounter = 0;
    private boolean wasWearingElytra = false;
    private boolean swapTriggered = false;

    // ---- Delays ----
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false)
            .setDescription("Legacy setting kept only so old configs load; no longer used.");
    private final SliderSetting useDelay = new SliderSetting("Use Delay (ms)", 2, 0, 40, 0)
            .withJitter(1)
            .setDescription("Wait between selecting the elytra/chestplate slot and right-clicking it to equip, inside the same tick. Capped at 40 ms so the equip never slips into the next tick.");
    private final SliderSetting waitTimeoutTicks = new SliderSetting("Swap Timeout (ticks)", 10, 1, 40, 0)
            .setDescription("How long to wait for the server to confirm the armor change before giving up and returning to your previous slot.");

    public ElytraSwap() {
        super("ElytraSwap", "One press swaps your chest slot between elytra and chestplate: it selects whichever one you are not wearing from the hotbar, right-clicks it to equip it, then returns to the item you were holding. Turns itself off when done, so bind it to a key.", Category.AUTOMATIONS);
        useProfile.setSection("Delays");
        useDelay.setSection("Delays");
        waitTimeoutTicks.setSection("Delays");
        addSetting(useDelay);
        addSetting(waitTimeoutTicks);
    }

    @Override
    public void onEnable() {
        originalSlot = expectedSlot = -1;
        if (mc.player == null || mc.world == null || mc.currentScreen != null
                || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)) {
            toggle();
            return;
        }

        ItemStack currentChest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        wasWearingElytra = currentChest.isOf(Items.ELYTRA);

        int targetSlot = findSwapSlot();

        if (targetSlot != -1) {
            originalSlot = mc.player.getInventory().getSelectedSlot();
            expectedSlot = targetSlot;
            boundPlayer = mc.player;
            boundWorld = mc.world;
            tickCounter = 0;
            swapTriggered = false;
            if (!interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, targetSlot)) {
                setEnabled(false);
                return;
            }
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.queueAction(this, () -> {
                ClickSimController.useNow(mc);
                swapTriggered = true;
            }, Math.min(40L, interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.clampMs(useDelay.sample())));
        } else {
            warnMissing("items", "No elytra or chestplate in hotbar");
            toggle();
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.player != boundPlayer || mc.world != boundWorld
                || originalSlot == -1 || !swapTriggered
                || mc.player.getInventory().getSelectedSlot() != expectedSlot) {
            setEnabled(false);
            return;
        }

        tickCounter++;

        ItemStack currentChest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        boolean isNowWearingElytra = currentChest.isOf(Items.ELYTRA);

        boolean swapComplete = (wasWearingElytra && !isNowWearingElytra && isChestplate(currentChest)) ||
                (!wasWearingElytra && isNowWearingElytra);

        if (swapComplete || tickCounter >= (int) waitTimeoutTicks.getValue()) {
            toggle();
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (originalSlot != -1 && mc.player != null && mc.player == boundPlayer && mc.world == boundWorld
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, originalSlot);
        }
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        tickCounter = 0;
        wasWearingElytra = false;
        swapTriggered = false;
        ModuleRandomDelay.clearActionState("player.use.elytraswap");
    }

    private int findSwapSlot() {
        ItemStack currentChest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        boolean wearingElytra = currentChest.isOf(Items.ELYTRA);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            if (wearingElytra) {

                if (isChestplate(stack)) return i;
            } else {

                if (stack.isOf(Items.ELYTRA)) return i;
            }
        }
        return -1;
    }

    private boolean isChestplate(ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (stack.isOf(Items.ELYTRA)) return false;

        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);

        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }


}
