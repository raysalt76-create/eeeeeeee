package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InventoryClickGate;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Inventory Sorter / Kit Layouts: "Save Layout" remembers which item sits in every inventory, hotbar and
 * offhand slot; the bind (or "Sort Now", or opening your inventory with Sort On Open) puts your items back
 * into that layout.
 *
 * <p>Sorting is done with ordinary clicks in your own inventory screen, one click per tick at most and spaced
 * by the jittered Click Delay: a hotbar or offhand target is one number-key swap, any other slot is a pick up,
 * a put down and (when the target held something) a put back. With no screen open the bind opens your
 * inventory first and closes it again when done. Layouts live in hidden settings, so they travel with your
 * config and are never written anywhere else.</p>
 */
public final class KitLayout extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** Handler slots covered by a layout: main inventory 9-35, hotbar 36-44, offhand 45. */
    private static final int FIRST = 9;
    private static final int LAST = 45;
    private static final int SIZE = LAST - FIRST + 1;
    private static final int OFFHAND = 45;
    private static final int MAX_CLICKS = 200;

    private final ModeSetting kit = new ModeSetting("Kit", "Kit 1", "Kit 1", "Kit 2", "Kit 3")
            .setDescription("Which saved layout Save Layout writes and Sort applies.");
    private final BooleanSetting saveLayout = new BooleanSetting("Save Layout", false)
            .setDescription("Click to remember where every item in your inventory, hotbar and offhand is right now, as the selected kit.");
    private final BooleanSetting sortNow = new BooleanSetting("Sort Now", false)
            .setDescription("Click to arrange your inventory into the selected kit (opens your inventory if no screen is open). The module's bind does the same.");
    private final BooleanSetting sortOnOpen = new BooleanSetting("Sort On Open", false)
            .setDescription("Sorts automatically every time you open your inventory.");
    private final BooleanSetting closeAfter = new BooleanSetting("Close When Done", true)
            .setDescription("When the sort opened your inventory itself, closes it again once everything is in place.");
    private final StringSetting[] data = {
            hidden(new StringSetting("Kit 1 Data", "")),
            hidden(new StringSetting("Kit 2 Data", "")),
            hidden(new StringSetting("Kit 3 Data", ""))
    };

    private final SliderSetting openDelay = new SliderSetting("Open Delay (ms)", 160, 0, 1000, 0).withJitter(40)
            .setSection("Delays")
            .setDescription("Wait after the inventory opens before the first click.");
    private final SliderSetting clickDelay = new SliderSetting("Click Delay (ms)", 110, 0, 1000, 0).withJitter(30)
            .setSection("Delays")
            .setDescription("Wait between two clicks. One click per tick is the most it ever does.");
    private final SliderSetting closeDelay = new SliderSetting("Close Delay (ms)", 180, 0, 1000, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Wait after the last click before closing the inventory it opened.");

    private boolean saveRequested;
    private boolean sortRequested;
    private boolean sorting;
    private boolean openedByUs;
    private boolean inventoryWasOpen;
    private long nextClickAt;
    private long closeAt = -1L;
    private int clicks;
    // Pick-up chain for a main-inventory target.
    private int chainStage;
    private int chainSource = -1;
    private int chainTarget = -1;
    private String[] layout;

    public KitLayout() {
        super("KitLayout", "Saves where every item in your inventory goes and puts it all back with one key: Save Layout remembers the current arrangement (3 kits), the bind or Sort Now sorts your inventory into it with normal, spaced clicks.", Category.PLAYER);
        addSetting(kit);
        addSetting(saveLayout);
        addSetting(sortNow);
        addSetting(sortOnOpen);
        addSetting(closeAfter);
        for (StringSetting s : data) addSetting(s);
        addSetting(openDelay);
        addSetting(clickDelay);
        addSetting(closeDelay);
        // Buttons: a click only records the request; the tick does the work on the client thread.
        saveLayout.onChange(() -> {
            if (saveLayout.isEnabled()) {
                saveRequested = true;
                saveLayout.setEnabled(false);
            }
        });
        sortNow.onChange(() -> {
            if (sortNow.isEnabled()) {
                sortRequested = true;
                sortNow.setEnabled(false);
            }
        });
    }

    private static StringSetting hidden(StringSetting setting) {
        setting.setVisibility(() -> false);
        return setting;
    }

    @Override
    public boolean isKeyTriggered() {
        return true;
    }

    @Override
    protected void onTrigger() {
        sortRequested = true;
    }

    @Override
    public void onDisable() {
        stopSort(false);
        saveRequested = false;
        sortRequested = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        stopSort(false);
        sortRequested = false;
        InventoryClickGate.disarm(this);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            stopSort(false);
            return;
        }
        long now = System.currentTimeMillis();
        boolean inventoryOpen = mc.currentScreen instanceof InventoryScreen;
        boolean justOpened = inventoryOpen && !inventoryWasOpen;
        inventoryWasOpen = inventoryOpen;

        if (saveRequested) {
            saveRequested = false;
            save();
        }
        if (sortRequested) {
            sortRequested = false;
            beginSort(now, inventoryOpen);
        } else if (justOpened && sortOnOpen.isEnabled() && !sorting) {
            beginSort(now, true);
        }
        if (!sorting) return;

        if (closeAt >= 0L) {
            if (now >= closeAt) finishAndClose();
            return;
        }
        if (!inventoryOpen) {
            if (!openedByUs || mc.currentScreen != null) {
                // The player closed the inventory or opened something else: stop here.
                stopSort(false);
                return;
            }
            mc.setScreen(new InventoryScreen(mc.player));
            nextClickAt = now + ModuleDelays.clampMs(openDelay.sample());
            return;
        }
        if (justOpened) nextClickAt = Math.max(nextClickAt, now + ModuleDelays.clampMs(openDelay.sample()));
        if (now < nextClickAt || InventoryClickGate.clickedThisTick()) return;

        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null || mc.player.currentScreenHandler != handler) {
            stopSort(false);
            return;
        }
        if (clicks >= MAX_CLICKS) {
            Notifier.warn("KitLayout", "Stopped after " + MAX_CLICKS + " clicks.");
            stopSort(true);
            return;
        }
        if (!step(handler)) {
            stopSort(true);
            return;
        }
        nextClickAt = now + ModuleDelays.clampMs(clickDelay.sample());
    }

    // ------------------------------------------------------------------ save

    private void save() {
        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null) return;
        StringBuilder out = new StringBuilder();
        int items = 0;
        for (int slot = FIRST; slot <= LAST; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (slot > FIRST) out.append(';');
            if (!stack.isEmpty()) {
                out.append(Registries.ITEM.getId(stack.getItem()));
                items++;
            }
        }
        data[kitIndex()].setValue(out.toString());
        Notifier.success("KitLayout", "Saved " + kit.getCurrentMode() + " (" + items + " slots).");
    }

    private String[] load() {
        String raw = data[kitIndex()].getValue();
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length != SIZE) return null;
        return parts;
    }

    private int kitIndex() {
        int index = kit.getModes().indexOf(kit.getCurrentMode());
        return index < 0 || index >= data.length ? 0 : index;
    }

    // ------------------------------------------------------------------ sort

    private void beginSort(long now, boolean inventoryOpen) {
        if (sorting) return;
        if (mc.player.isCreative()) {
            Notifier.warn("KitLayout", "Sorting works in your survival inventory only.");
            return;
        }
        layout = load();
        if (layout == null) {
            Notifier.warn("KitLayout", kit.getCurrentMode() + " is empty. Use Save Layout first.");
            return;
        }
        if (!inventoryOpen && mc.currentScreen != null) {
            Notifier.warn("KitLayout", "Close the open screen first.");
            return;
        }
        sorting = true;
        openedByUs = !inventoryOpen;
        clicks = 0;
        chainStage = 0;
        closeAt = -1L;
        nextClickAt = now + (inventoryOpen ? ModuleDelays.clampMs(openDelay.sample()) : 0L);
    }

    /** One click toward the layout. False when the inventory already matches (or nothing more can move). */
    private boolean step(PlayerScreenHandler handler) {
        int syncId = handler.syncId;
        if (chainStage > 0) return continueChain(handler, syncId);
        if (!handler.getCursorStack().isEmpty()) {
            Notifier.warn("KitLayout", "Put down the item on your cursor first.");
            return false;
        }
        // Offhand first, then the hotbar, then the main inventory.
        int[] order = new int[SIZE];
        int n = 0;
        order[n++] = OFFHAND;
        for (int slot = 36; slot <= 44; slot++) order[n++] = slot;
        for (int slot = FIRST; slot <= 35; slot++) order[n++] = slot;
        for (int target : order) {
            String want = layout[target - FIRST];
            if (want.isEmpty() || idOf(handler, target).equals(want)) continue;
            int source = findSource(handler, want, target);
            if (source < 0) continue;
            if (target == OFFHAND || target >= 36) {
                int button = target == OFFHAND ? 40 : target - 36;
                if (!click(syncId, source, button, SlotActionType.SWAP)) return false;
                return true;
            }
            chainSource = source;
            chainTarget = target;
            chainStage = 1;
            return continueChain(handler, syncId);
        }
        return false;
    }

    private boolean continueChain(PlayerScreenHandler handler, int syncId) {
        switch (chainStage) {
            case 1 -> {
                if (!click(syncId, chainSource, 0, SlotActionType.PICKUP)) return endChain(false);
                chainStage = 2;
                return true;
            }
            case 2 -> {
                if (handler.getCursorStack().isEmpty()) return endChain(true);
                if (!click(syncId, chainTarget, 0, SlotActionType.PICKUP)) return endChain(false);
                chainStage = 3;
                return true;
            }
            default -> {
                // Whatever the target held is on the cursor now: put it where the moved stack was.
                if (handler.getCursorStack().isEmpty()) return endChain(true);
                if (!handler.getSlot(chainSource).getStack().isEmpty()) {
                    int empty = emptySlot(handler);
                    if (empty < 0) return endChain(false);
                    chainSource = empty;
                }
                if (!click(syncId, chainSource, 0, SlotActionType.PICKUP)) return endChain(false);
                return endChain(true);
            }
        }
    }

    private boolean endChain(boolean ok) {
        chainStage = 0;
        chainSource = -1;
        chainTarget = -1;
        return ok;
    }

    private boolean click(int syncId, int slot, int button, SlotActionType type) {
        if (!SystemInputSimulator.clickSlotBound(syncId, slot, button, type)) return false;
        InventoryClickGate.noteClick();
        clicks++;
        return true;
    }

    /** A slot holding {@code want} that is not already where the layout wants it (the fullest such stack). */
    private int findSource(PlayerScreenHandler handler, String want, int target) {
        int best = -1;
        int bestCount = -1;
        for (int slot = FIRST; slot <= LAST; slot++) {
            if (slot == target) continue;
            if (!idOf(handler, slot).equals(want)) continue;
            if (layout[slot - FIRST].equals(want)) continue; // already in a place the layout wants it
            int count = handler.getSlot(slot).getStack().getCount();
            if (count > bestCount) {
                bestCount = count;
                best = slot;
            }
        }
        return best;
    }

    private int emptySlot(PlayerScreenHandler handler) {
        for (int slot = FIRST; slot <= 44; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) return slot;
        }
        return -1;
    }

    private static String idOf(PlayerScreenHandler handler, int slot) {
        ItemStack stack = handler.getSlot(slot).getStack();
        return stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** Ends the sort; when {@code done} and the module opened the inventory, closes it after Close Delay. */
    private void stopSort(boolean done) {
        endChain(true);
        if (done && sorting && openedByUs && closeAfter.isEnabled() && mc.currentScreen instanceof InventoryScreen) {
            closeAt = System.currentTimeMillis() + ModuleDelays.clampMs(closeDelay.sample());
            return;
        }
        sorting = false;
        openedByUs = false;
        closeAt = -1L;
        layout = null;
    }

    private void finishAndClose() {
        boolean close = openedByUs && mc.currentScreen instanceof InventoryScreen;
        sorting = false;
        openedByUs = false;
        closeAt = -1L;
        layout = null;
        if (close && mc.player != null) mc.player.closeHandledScreen();
    }
}
