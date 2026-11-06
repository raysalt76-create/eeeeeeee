package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.mixin.HandledScreenAccessor;
import interstellarTikiTiks.apexx.client.mixin.HandledScreenInterface;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InventoryClickGate;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.item.PotionItem;
import net.minecraft.item.LingeringPotionItem;
import net.minecraft.potion.Potions;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.potion.Potion;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class PotRefill extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting potionType = new ModeSetting("Potion Type", "Healing", "Strength", "Speed")
            .setDescription("Type of potion to prioritize restocking: Healing, Strength, or Speed.");
    private final ModeSetting refillMode = new ModeSetting("Refill Mode", "Continuous", "One-Shot", "Hover")
            .setDescription("Continuous: stays on and restocks a target slot whenever it runs out. One-Shot: restocks once, then turns itself off. Hover: never opens the inventory itself; while you have it open, hovering your cursor over a potion moves it into the next empty Hover Slot after Hover Refill Delay.");
    private final BooleanSetting smartMode = new BooleanSetting("Smart Mode", true)
            .setDescription("Remembers every hotbar slot that has held the chosen potion and restocks it once it is used, on top of the Refill Slot toggles.");
    private final BooleanSetting autoOpen = new BooleanSetting("Auto Open", true)
            .setDescription("Automatically opens inventory when hotbar potions are depleted.");
    private final BooleanSetting autoClose = new BooleanSetting("Auto Close", true)
            .setDescription("Automatically closes inventory once potions have been restocked.");
    private final BooleanSetting splashOnly = new BooleanSetting("Splash Only", true)
            .setDescription("Only restocks splash and lingering potions; ignores drinkable bottles.");
    private final BooleanSetting notifyIdle = new BooleanSetting("Explain Idle", true)
            .setDescription("Tells you once why nothing is being restocked (no target slots, no matching potions).");




    // Sim Cursor: move the REAL cursor to each slot and pickup/place (a visible drag), exactly like
    // AutoTotemGuard, instead of an instant server-side SWAP. Off = the old instant SWAP.
    private final BooleanSetting simCursor = new BooleanSetting("Sim Cursor", true)
            .setDescription("Moves the real cursor to each slot and picks up/places items instead of an instant swap click.");
    private final SliderSetting cursorSpeed = new SliderSetting("Cursor Speed", 25, 1, 100, 0)
            .setDescription("Speed multiplier for simulated inventory cursor interpolation.");
    private final ModeSetting cursorMode = new ModeSetting("Cursor Mode", "Smooth", "Heuristic", "Instant")
            .setDescription("Cursor movement algorithm: Smooth (bezier curve), Heuristic (micro-drift), Instant.");

    private final BooleanSetting slot1 = new BooleanSetting("Refill Slot 1", false)
            .setDescription("Restocks potions into hotbar slot 1.");
    private final BooleanSetting slot2 = new BooleanSetting("Refill Slot 2", false)
            .setDescription("Restocks potions into hotbar slot 2.");
    private final BooleanSetting slot3 = new BooleanSetting("Refill Slot 3", false)
            .setDescription("Restocks potions into hotbar slot 3.");
    private final BooleanSetting slot4 = new BooleanSetting("Refill Slot 4", false)
            .setDescription("Restocks potions into hotbar slot 4.");
    private final BooleanSetting slot5 = new BooleanSetting("Refill Slot 5", false)
            .setDescription("Restocks potions into hotbar slot 5.");
    private final BooleanSetting slot6 = new BooleanSetting("Refill Slot 6", false)
            .setDescription("Restocks potions into hotbar slot 6.");
    private final BooleanSetting slot7 = new BooleanSetting("Refill Slot 7", false)
            .setDescription("Restocks potions into hotbar slot 7.");
    private final BooleanSetting slot8 = new BooleanSetting("Refill Slot 8", false)
            .setDescription("Restocks potions into hotbar slot 8.");
    private final BooleanSetting slot9 = new BooleanSetting("Refill Slot 9", false)
            .setDescription("Restocks potions into hotbar slot 9.");


    private int stage = 0;
    private long lastActionTime = 0;
    private final SliderSetting triggerDelay = new SliderSetting("Trigger Delay (ms)", 120, 0, 1000, 0)
            .withJitter(30).setSection("Delays")
            .setDescription("Wait after a target slot runs out before the refill starts.");
    private final SliderSetting openDelay = new SliderSetting("Open Delay (ms)", 60, 0, 500, 0)
            .withJitter(15).setSection("Delays")
            .setDescription("Wait after the inventory opens before the first move.");
    private final SliderSetting refillDelay = new SliderSetting("Refill Delay (ms)", 45, 0, 500, 0)
            .withJitter(12).setSection("Delays")
            .setDescription("Gap between slot moves.");
    private final SliderSetting clickDelay = new SliderSetting("Click Delay (ms)", 50, 0, 500, 0)
            .withJitter(15).setSection("Delays")
            .setDescription("Sim Cursor: gap between the pick-up and place clicks of one slot move.");
    private final SliderSetting closeDelay = new SliderSetting("Close Delay (ms)", 90, 0, 800, 0)
            .withJitter(25).setSection("Delays")
            .setDescription("Wait after the last move before the inventory is closed.");

    // ---- Hover mode (formerly the separate HoverPot module) ----
    private final BooleanSetting hoverMatchType = new BooleanSetting("Hover Match Type", false)
            .setVisibility(this::hoverMode)
            .setDescription("Hover: only move potions matching Potion Type and Splash Only. Off moves any potion you hover, like the old HoverPot.");
    private final BooleanSetting hoverAutoClose = new BooleanSetting("Hover Auto Close", false)
            .setVisibility(this::hoverMode)
            .setDescription("Hover: closes your inventory automatically once enough of the Hover Slots hold potions, so you are back in the fight without pressing Escape.");
    private final SliderSetting autoCloseFilledSlots = new SliderSetting("Auto Close Filled Slots", 6, 1, 9, 0)
            .setVisibility(() -> hoverMode() && hoverAutoClose.isEnabled())
            .setDescription("Hover: how many Hover Slots must hold potions before Hover Auto Close shuts the inventory.");
    private final BooleanSetting[] hoverSlots = new BooleanSetting[9];
    private final SliderSetting hoverRefillDelay = new SliderSetting("Hover Refill Delay (ms)", 30, 0, 500, 0)
            .withJitter(10).setSection("Delays")
            .setDescription("Hover: wait between your cursor landing on a potion and that potion being moved into the hotbar.");
    private final SliderSetting hoverCloseDelay = new SliderSetting("Hover Close Delay (ms)", 120, 0, 1000, 0)
            .withJitter(30).setSection("Delays")
            .setDescription("Hover: with Hover Auto Close on, wait between the hotbar reaching Auto Close Filled Slots and the inventory being closed.");

    private long hoverExecuteAt = -1L;
    /** When Hover Auto Close may shut the inventory; -1 while the fill condition is not met. */
    private long hoverCloseAt = -1L;
    private Slot hoverSourceSlot = null;
    private int hoverHotbarSlot = -1;
    private int hoverSyncId = -1;

    private final boolean[] rememberedSlots = new boolean[9];
    private String lastIdleReason = null;
    private long refillNeededSince = -1L;

    private long currentDelay = 0;
    private final List<Integer> targetHotbarSlots = new ArrayList<>();
    private final List<Integer> slotsToRefill = new ArrayList<>();

    // ── Cursor-drag sub-state (stage 2, Sim Cursor mode) ──
    // 0 = idle (pop next slot), 1 = move to src & pick up pot, 2 = move to dst & place, 3 = return the
    // displaced item to the now-empty src. Screen slot ids: main-inventory index i (9..35) == slot id i;
    // hotbar index h (0..8) == slot id 36+h.
    private int dragStep = 0;
    private int dragSrcId = -1;
    private int dragDstId = -1;

    public PotRefill() {
        super("PotRefill", "Keeps your potion hotbar slots filled: whenever a chosen slot (or a slot Smart Mode remembers holding potions) is empty, it opens your inventory, shift-clicks a matching potion in from storage and closes it again, with human-like delays between each step. Runs continuously or once per key press.", Category.PLAYER);
        setSubCategory(SubCategory.SMP);
        addSetting(refillMode);
        addSetting(potionType);
        addSetting(smartMode);
        addSetting(autoOpen);
        addSetting(autoClose);
        addSetting(splashOnly);
        addSetting(notifyIdle);

        addSetting(simCursor);
        addSetting(cursorSpeed);
        addSetting(cursorMode);

        addSetting(slot1);
        addSetting(slot2);
        addSetting(slot3);
        addSetting(slot4);
        addSetting(slot5);
        addSetting(slot6);
        addSetting(slot7);
        addSetting(slot8);
        addSetting(slot9);

        addSetting(hoverMatchType);
        addSetting(hoverAutoClose);
        addSetting(autoCloseFilledSlots);
        for (int i = 0; i < 9; i++) {
            final int n = i + 1;
            hoverSlots[i] = new BooleanSetting("Hover Slot " + n, i != 0)
                    .setVisibility(this::hoverMode)
                    .setDescription("Hover: lets hovered potions be moved into hotbar slot " + n + " (only while it is empty)."
                            + (i == 0 ? " Off by default so your weapon slot is left alone." : ""));
            addSetting(hoverSlots[i]);
        }

        addSetting(triggerDelay);
        addSetting(openDelay);
        addSetting(refillDelay);
        addSetting(clickDelay);
        addSetting(closeDelay);
        addSetting(hoverRefillDelay);
        addSetting(hoverCloseDelay);
    }

    private boolean oneShot() {
        return refillMode.is("One-Shot");
    }

    private boolean hoverMode() {
        return refillMode.is("Hover");
    }

    // ------------------------------------------------------------------ Hover mode

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!hoverMode()) {
            resetHover();
            return;
        }
        if (hoverExecuteAt != -1L) {
            if (System.currentTimeMillis() >= hoverExecuteAt) performHoverMove();
            return;
        }
        if (mc.player == null || mc.interactionManager == null || !(mc.currentScreen instanceof InventoryScreen screen)) {
            resetHover();
            return;
        }
        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasStack() || !isHoverPotion(hovered.getStack()) || isSlotInHotbar(hovered)) {
            maybeHoverAutoClose();
            return;
        }
        // A hotbar-key SWAP does nothing in vanilla while an item is on the cursor, so never send one then.
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        int target = findEmptyHoverSlot();
        if (target == -1) {
            maybeHoverAutoClose();
            return;
        }
        hoverSourceSlot = hovered;
        hoverHotbarSlot = target;
        hoverSyncId = mc.player.currentScreenHandler.syncId;
        hoverExecuteAt = System.currentTimeMillis() + ModuleDelays.clampMs(hoverRefillDelay.sample());
    }

    /** Re-validates the scheduled move against the live screen (it may have changed since) and sends one SWAP. */
    private void performHoverMove() {
        if (mc.player == null || mc.interactionManager == null || hoverSourceSlot == null || hoverHotbarSlot < 0
                || !(mc.currentScreen instanceof InventoryScreen)) {
            resetHoverPending();
            return;
        }
        int syncId = mc.player.currentScreenHandler.syncId;
        List<Slot> live = mc.player.currentScreenHandler.slots;
        if (hoverSyncId == -1 || syncId != hoverSyncId
                || hoverSourceSlot.id < 0 || hoverSourceSlot.id >= live.size()
                || live.get(hoverSourceSlot.id) != hoverSourceSlot
                || !hoverSourceSlot.hasStack() || !isHoverPotion(hoverSourceSlot.getStack())
                || !mc.player.getInventory().getStack(hoverHotbarSlot).isEmpty()
                || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            resetHoverPending();
            return;
        }
        // One window click per tick; keep the pending move and retry on a later frame.
        if (InventoryClickGate.clickedThisTick()) return;
        if (SystemInputSimulator.clickSlotBound(syncId, hoverSourceSlot.id, hoverHotbarSlot, SlotActionType.SWAP)) {
            InventoryClickGate.noteClick();
        }
        resetHoverPending();
        maybeHoverAutoClose();
    }

    private int findEmptyHoverSlot() {
        for (int i = 0; i < 9; i++) {
            if (!hoverSlots[i].isEnabled()) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            // Only empty slots are filled: a slot holding anything else (totem, gapple...) is never overwritten.
            if (stack == null || stack.isEmpty()) return i;
        }
        return -1;
    }

    private void maybeHoverAutoClose() {
        if (!hoverAutoClose.isEnabled() || mc.player == null || !(mc.currentScreen instanceof InventoryScreen)) {
            hoverCloseAt = -1L;
            return;
        }
        int configured = 0;
        int filled = 0;
        for (int i = 0; i < 9; i++) {
            if (!hoverSlots[i].isEnabled()) continue;
            configured++;
            if (isAnyPotion(mc.player.getInventory().getStack(i))) filled++;
        }
        if (configured == 0 || filled < (int) autoCloseFilledSlots.getValue()) {
            hoverCloseAt = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        if (hoverCloseAt < 0L) hoverCloseAt = now + ModuleDelays.clampMs(hoverCloseDelay.sample());
        if (now >= hoverCloseAt) {
            hoverCloseAt = -1L;
            mc.player.closeHandledScreen();
        }
    }

    private Slot getHoveredSlot(HandledScreen<?> screen) {
        try {
            Slot focused = ((HandledScreenInterface) screen).getFocusedSlot();
            if (focused != null) return focused;
        } catch (ClassCastException ignored) {
        }
        HandledScreenAccessor acc = (HandledScreenAccessor) screen;
        double mouseX = mc.mouse.getX() * (double) screen.width / (double) mc.getWindow().getWidth();
        double mouseY = mc.mouse.getY() * (double) screen.height / (double) mc.getWindow().getHeight();
        for (Slot slot : screen.getScreenHandler().slots) {
            int x = acc.getX() + slot.x;
            int y = acc.getY() + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) return slot;
        }
        return null;
    }

    private boolean isSlotInHotbar(Slot slot) {
        return mc.player != null && slot.inventory == mc.player.getInventory()
                && slot.getIndex() >= 0 && slot.getIndex() < 9;
    }

    private boolean isHoverPotion(ItemStack stack) {
        return hoverMatchType.isEnabled() ? isDesiredPotion(stack) : isAnyPotion(stack);
    }

    private static boolean isAnyPotion(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
    }

    private void resetHoverPending() {
        hoverExecuteAt = -1L;
        hoverSourceSlot = null;
        hoverHotbarSlot = -1;
        hoverSyncId = -1;
    }

    private void resetHover() {
        resetHoverPending();
        hoverCloseAt = -1L;
    }

    private long sampleMs(SliderSetting setting) {
        return Math.max(0L, Math.round(setting.sample()));
    }

    /** Explains, once per distinct reason, why nothing is being restocked. */
    private void explainIdle(String reason) {
        if (reason == null) {
            lastIdleReason = null;
            return;
        }
        if (!notifyIdle.isEnabled() || reason.equals(lastIdleReason)) return;
        lastIdleReason = reason;
        Notifier.info(getName(), reason);
    }

    /** Learns hotbar slots that currently hold the chosen potion (Smart Mode). */
    private void rememberPotionSlots() {
        if (!smartMode.isEnabled() || mc.player == null) return;
        for (int i = 0; i < 9; i++) {
            if (isDesiredPotion(mc.player.getInventory().getStack(i))) rememberedSlots[i] = true;
        }
    }

    @Override
    public void onEnable() {
        // Never switch off just because there is nothing to do yet: the module used to toggle itself
        // off here with default settings (no Refill Slot chosen), so it looked like it never turned on.
        stage = 0;
        lastActionTime = 0;
        currentDelay = 0;
        lastIdleReason = null;
        refillNeededSince = -1L;
        java.util.Arrays.fill(rememberedSlots, false);
        slotsToRefill.clear();
        resetDrag();
        resetHover();
    }

    @Override
    public void onDisable() {
        stage = 0;
        slotsToRefill.clear();
        resetDrag();
        resetHover();
    }

    @Override
    protected void onRuntimeStateReleased() {
        stage = 0;
        slotsToRefill.clear();
        resetDrag();
        resetHover();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) {
            stage = 0;
            slotsToRefill.clear();
            resetDrag();
            return;
        }
        // Hover mode is driven per frame from onRender and only reacts to the player's own open inventory.
        if (hoverMode()) {
            if (stage != 0) {
                stage = 0;
                slotsToRefill.clear();
                resetDrag();
            }
            refillNeededSince = -1L;
            return;
        }

        rememberPotionSlots();
        updateTargetSlots();

        long currentTime = System.currentTimeMillis();

        if (currentTime - lastActionTime < currentDelay) {
            return;
        }

        switch (stage) {
            case 0: {
                if (targetHotbarSlots.isEmpty()) {
                    explainIdle("No target slots yet: enable a Refill Slot, or keep Smart Mode on and hold a "
                            + potionType.getCurrentMode() + " potion in your hotbar once.");
                    if (oneShot()) toggle();
                    break;
                }
                populateSlotsToRefill();
                if (slotsToRefill.isEmpty()) {
                    refillNeededSince = -1L;
                    explainIdle(null);
                    if (oneShot()) toggle();
                    break;
                }
                if (!hasPotionsInMainInventory()) {
                    explainIdle("No " + potionType.getCurrentMode() + " potions left in your main inventory.");
                    if (oneShot()) toggle();
                    break;
                }
                explainIdle(null);
                // Refills only happen with the inventory open, so with Auto Open off wait for the
                // player to open it instead of starting a sequence stage 1 would abort.
                if (!autoOpen.isEnabled() && !(mc.currentScreen instanceof InventoryScreen)) {
                    break;
                }
                // Any other screen (chest, chat, ...) belongs to the player: wait for it to close.
                if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) {
                    break;
                }
                if (refillNeededSince < 0L) {
                    refillNeededSince = currentTime;
                    lastActionTime = currentTime;
                    currentDelay = sampleMs(triggerDelay);
                    break;
                }
                refillNeededSince = -1L;
                startRefillSequence();
                break;
            }
            case 1: {
                if (autoOpen.isEnabled() && !(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                // Only the player's own inventory: clicks are never sent with it closed, and the
                // slot ids below belong to the player handler, not to a chest or other container.
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    resetSequence();
                    break;
                }
                stage = 2;
                lastActionTime = System.currentTimeMillis();
                currentDelay = sampleMs(openDelay);
                break;
            }
            case 2: {
                if (!(mc.currentScreen instanceof InventoryScreen hs)) {
                    // Inventory closed mid-refill: a click now would be one with no inventory open.
                    resetSequence();
                    break;
                }
                // The player's own held item is never picked over, and a SWAP does nothing with a held item.
                if (dragStep == 0 && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                    break;
                }
                // Cursor-drag mode: move the real cursor slot-to-slot and pickup/place, like
                // AutoTotemGuard. Uses the instant SWAP when Sim Cursor is off.
                if (simCursor.isEnabled()) {
                    stepCursorRefill(hs);
                    break;
                }

                if (slotsToRefill.isEmpty()) {
                    stage = 3;
                    lastActionTime = System.currentTimeMillis();
                    currentDelay = sampleMs(closeDelay);
                    break;
                }

                int hotbarSlotIndex = slotsToRefill.remove(0);
                int inventoryPotSlot = findPotionInMainInventory();

                if (inventoryPotSlot != -1) {
                    SystemInputSimulator.clickSlotNow(mc.player.currentScreenHandler.syncId, inventoryPotSlot,
                            hotbarSlotIndex, SlotActionType.SWAP);
                    lastActionTime = System.currentTimeMillis();
                    currentDelay = sampleMs(refillDelay);
                } else {
                    slotsToRefill.clear();
                }
                break;
            }
            case 3: {
                if (autoClose.isEnabled() && mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                stage = 4;
                lastActionTime = System.currentTimeMillis();
                currentDelay = sampleMs(closeDelay);
                break;
            }
            case 4: {
                stage = 0;
                if (oneShot()) {
                    toggle();
                }
                break;
            }
        }
    }

    private void startRefillSequence() {
        stage = 1;
        dragStep = 0;
        dragSrcId = -1;
        dragDstId = -1;
        lastActionTime = System.currentTimeMillis();
        currentDelay = 0;
    }

    /** Aborts a refill that cannot run with the inventory open; a one-shot refill turns itself off. */
    private void resetSequence() {
        stage = 0;
        slotsToRefill.clear();
        resetDrag();
        lastActionTime = System.currentTimeMillis();
        currentDelay = 0;
        refillNeededSince = -1L;
        if (oneShot()) {
            toggle();
        }
    }

    /**
     * Multi-tick cursor drag for one hotbar slot: pick up a pot from the main inventory, carry it to the
     * hotbar slot and place it, then carry any displaced item back to the now-empty source slot — each
     * click preceded by a REAL cursor glide to the slot (SystemInputSimulator), same as AutoTotemGuard.
     * Runs one step per tick; while a drag is in flight the inter-slot refill delay is held at 0 so the
     * glide is smooth, and the delay is applied only between completed slot moves.
     */
    private void stepCursorRefill(HandledScreen<?> screen) {
        int syncId = mc.player.currentScreenHandler.syncId;

        if (dragStep == 0) {
            if (slotsToRefill.isEmpty()) {
                stage = 3;
                lastActionTime = System.currentTimeMillis();
                currentDelay = sampleMs(closeDelay);
                return;
            }
            int hotbarSlotIndex = slotsToRefill.remove(0);
            int inventoryPotSlot = findPotionInMainInventory();
            if (inventoryPotSlot == -1) {
                slotsToRefill.clear();
                return;
            }
            dragSrcId = inventoryPotSlot;      // main-inventory index (9..35) == screen slot id
            dragDstId = 36 + hotbarSlotIndex;  // hotbar index (0..8) == screen slot id 36..44
            dragStep = 1;
            currentDelay = 0;                  // keep the glide flowing tick-to-tick
        }

        if (dragStep == 1) {                   // move to source pot, pick it up
            Slot src = findSlotById(screen, dragSrcId);
            if (src == null) { resetDrag(); return; }
            if (!moveCursorToSlot(screen, src)) return;   // still gliding
            SystemInputSimulator.clickSlotNow(syncId, dragSrcId, 0, SlotActionType.PICKUP);
            dragStep = 2;
            spaceDragClick();
            return;
        }

        if (dragStep == 2) {                   // move to hotbar slot, place the pot
            Slot dst = findSlotById(screen, dragDstId);
            if (dst == null) { resetDrag(); return; }
            if (!moveCursorToSlot(screen, dst)) return;
            SystemInputSimulator.clickSlotNow(syncId, dragDstId, 0, SlotActionType.PICKUP);
            dragStep = 3;
            spaceDragClick();
            return;
        }

        if (dragStep == 3) {                   // return any displaced item to the emptied source
            if (screen.getScreenHandler().getCursorStack().isEmpty()) {
                finishOneDrag();               // nothing displaced — this slot is done
                return;
            }
            Slot src = findSlotById(screen, dragSrcId);
            if (src == null) { finishOneDrag(); return; }
            if (!moveCursorToSlot(screen, src)) return;
            SystemInputSimulator.clickSlotNow(syncId, dragSrcId, 0, SlotActionType.PICKUP);
            finishOneDrag();
        }
    }

    /** Spaces the next click of the same drag by the Click Delay (the cursor glide resumes after it). */
    private void spaceDragClick() {
        lastActionTime = System.currentTimeMillis();
        currentDelay = sampleMs(clickDelay);
    }

    /** One slot fully placed — idle the drag and space the next move by the refill delay. */
    private void finishOneDrag() {
        resetDrag();
        lastActionTime = System.currentTimeMillis();
        currentDelay = sampleMs(refillDelay);
    }

    private void resetDrag() {
        dragStep = 0;
        dragSrcId = -1;
        dragDstId = -1;
    }

    private Slot findSlotById(HandledScreen<?> screen, int slotId) {
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.id == slotId) return slot;
        }
        return null;
    }

    private boolean moveCursorToSlot(HandledScreen<?> screen, Slot slot) {
        if ("Instant".equalsIgnoreCase(cursorMode.getCurrentMode())) {
            return true; // no visible glide; click immediately
        }
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        return SystemInputSimulator.moveCursorToScaled(screen.width, screen.height,
                accessor.getX() + slot.x + 8.0,
                accessor.getY() + slot.y + 8.0,
                cursorSpeed.getValue(),
                cursorMode.getCurrentMode());
    }

    private void updateTargetSlots() {
        targetHotbarSlots.clear();
        addSlotIfValid(slot1, 0);
        addSlotIfValid(slot2, 1);
        addSlotIfValid(slot3, 2);
        addSlotIfValid(slot4, 3);
        addSlotIfValid(slot5, 4);
        addSlotIfValid(slot6, 5);
        addSlotIfValid(slot7, 6);
        addSlotIfValid(slot8, 7);
        addSlotIfValid(slot9, 8);
    }

    private void addSlotIfValid(BooleanSetting setting, int slotIndex) {
        if (setting.isEnabled() || (smartMode.isEnabled() && rememberedSlots[slotIndex])) {
            targetHotbarSlots.add(slotIndex);
        }
    }

    private void populateSlotsToRefill() {
        slotsToRefill.clear();
        for (int slot : targetHotbarSlots) {
            if (!isDesiredPotion(mc.player.getInventory().getStack(slot))) {
                slotsToRefill.add(slot);
            }
        }
    }

    private boolean hasPotionsInMainInventory() {
        return findPotionInMainInventory() != -1;
    }

    private int findPotionInMainInventory() {
        for (int i = 9; i < 36; i++) {
            if (isDesiredPotion(mc.player.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isDesiredPotion(ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (splashOnly.isEnabled()) {
            if (!(stack.getItem() instanceof SplashPotionItem)
                    && !(stack.getItem() instanceof LingeringPotionItem)) return false;
        } else {
            if (!(stack.getItem() instanceof PotionItem)
                    && !(stack.getItem() instanceof SplashPotionItem)
                    && !(stack.getItem() instanceof LingeringPotionItem)) return false;
        }

        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;

        List<RegistryEntry<Potion>> wanted = getWantedPotions();
        for (RegistryEntry<Potion> potion : wanted) {
            if (contents.matches(potion)) return true;
        }
        return false;
    }

    private List<RegistryEntry<Potion>> getWantedPotions() {
        List<RegistryEntry<Potion>> potions = new ArrayList<>();
        String mode = potionType.getCurrentMode();
        switch (mode) {
            case "Healing":
                potions.add(Potions.HEALING);
                potions.add(Potions.STRONG_HEALING);
                break;
            case "Strength":
                potions.add(Potions.STRENGTH);
                potions.add(Potions.LONG_STRENGTH);
                potions.add(Potions.STRONG_STRENGTH);
                break;
            case "Speed":
                potions.add(Potions.SWIFTNESS);
                potions.add(Potions.LONG_SWIFTNESS);
                potions.add(Potions.STRONG_SWIFTNESS);
                break;
        }
        return potions;
    }

}
