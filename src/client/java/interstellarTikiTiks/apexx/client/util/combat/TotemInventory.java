package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * Slot lookup and the swap clicks of the player's own screen handler, ported from Mochi's
 * InventoryUtil. Inventory index (0-8 hotbar, 9-35 main, 40 offhand) and handler slot id
 * (36-44 hotbar, 45 offhand) are different coordinate systems; {@link #handlerSlotFor(int)} converts.
 * Every click goes through {@link #clickSlot} so {@link InventoryClickGate} learns of it.
 */
public final class TotemInventory {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final int HOTBAR_SLOTS = 9;
    public static final int MAIN_SLOTS = 36;
    public static final int OFFHAND_HANDLER_SLOT = 45;
    public static final int OFFHAND_INVENTORY_INDEX = 40;
    public static final int OFFHAND_SWAP_BUTTON = 40;

    private TotemInventory() {
    }

    /** First inventory index in {@code match}, hotbar entries first, or -1. */
    public static int firstSlotMatching(Predicate<ItemStack> match) {
        if (mc.player == null || match == null) {
            return -1;
        }
        for (int index = 0; index < MAIN_SLOTS; index++) {
            if (match.test(mc.player.getInventory().getStack(index))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Handler slot id carrying the stack at {@code inventoryIndex}. Hotbar entries land at 36-44
     * because {@code PlayerScreenHandler} adds them after the main inventory; every other index
     * keeps its own number.
     */
    public static int handlerSlotFor(int inventoryIndex) {
        return inventoryIndex < HOTBAR_SLOTS ? inventoryIndex + MAIN_SLOTS : inventoryIndex;
    }

    /** Swaps the stack at {@code handlerSlot} with the offhand slot, without opening a screen. */
    public static void moveToOffhand(int handlerSlot) {
        clickSlot(handlerSlot, OFFHAND_SWAP_BUTTON);
    }

    /**
     * Swaps the stack at {@code handlerSlot} with a hotbar entry, the click a player makes by
     * pressing 1-9 while hovering a slot. Used to bring an inventory item into the held slot.
     */
    public static void swapWithHotbar(int handlerSlot, int hotbarIndex) {
        if (hotbarIndex < 0 || hotbarIndex >= HOTBAR_SLOTS) {
            return;
        }
        clickSlot(handlerSlot, hotbarIndex);
    }

    private static void clickSlot(int handlerSlot, int button) {
        clickSlot(handlerSlot, button, SlotActionType.SWAP);
    }

    private static void clickSlot(int handlerSlot, int button, SlotActionType action) {
        if (mc.player == null || mc.interactionManager == null || handlerSlot < 0) {
            return;
        }
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, handlerSlot, button,
                action, mc.player);
        // Every window click this client sends goes through here, so this is the one place that has
        // to tell the gate a click left the client on this tick.
        InventoryClickGate.noteClick();
    }

    /**
     * A window click bound to the handler the caller planned against ({@code syncId}): sent through the shared
     * {@code SystemInputSimulator.clickSlotBound} seam only while that handler is still open, and reported to
     * {@link InventoryClickGate}. Returns false when nothing was sent.
     */
    public static boolean clickBound(int syncId, int handlerSlot, int button, SlotActionType action) {
        if (mc.player == null || mc.interactionManager == null || handlerSlot < 0) {
            return false;
        }
        boolean sent = interstellarTikiTiks.apexx.client.util.SystemInputSimulator.clickSlotBound(
                syncId, handlerSlot, button, action);
        if (sent) {
            InventoryClickGate.noteClick();
        }
        return sent;
    }

    /** First inventory index in [{@code from}, {@code toExclusive}) whose stack {@code match} accepts, or -1. */
    public static int firstSlotMatching(Predicate<ItemStack> match, int from, int toExclusive) {
        if (mc.player == null || match == null) {
            return -1;
        }
        for (int index = Math.max(0, from); index < Math.min(MAIN_SLOTS, toExclusive); index++) {
            if (match.test(mc.player.getInventory().getStack(index))) {
                return index;
            }
        }
        return -1;
    }

    /** Totems the player carries: main inventory, hotbar and offhand. */
    public static int countItem(Item item) {
        if (mc.player == null) {
            return 0;
        }
        int total = 0;
        for (int index = 0; index < MAIN_SLOTS; index++) {
            ItemStack stack = mc.player.getInventory().getStack(index);
            if (!stack.isEmpty() && stack.isOf(item)) total += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (!offhand.isEmpty() && offhand.isOf(item)) total += offhand.getCount();
        return total;
    }

    /** True when any hotbar entry holds {@code item}. */
    public static boolean hotbarHasAny(Item item) {
        for (int index = 0; index < HOTBAR_SLOTS; index++) {
            if (hotbarSlotHas(index, item)) return true;
        }
        return false;
    }

    /** True when the given 0-based hotbar entry is empty. */
    public static boolean hotbarSlotEmpty(int hotbarIndex) {
        return mc.player != null && hotbarIndex >= 0 && hotbarIndex < HOTBAR_SLOTS
                && mc.player.getInventory().getStack(hotbarIndex).isEmpty();
    }

    /** Every slot of the player's own inventory in {@code screen} (offhand excluded) whose stack matches. */
    public static java.util.List<Slot> playerSlots(HandledScreen<?> screen, Predicate<ItemStack> match) {
        java.util.List<Slot> out = new java.util.ArrayList<>();
        if (screen == null || mc.player == null || match == null) {
            return out;
        }
        for (Slot slot : screen.getScreenHandler().slots) {
            if (isPlayerInventorySlot(slot) && slot.inventory == mc.player.getInventory()
                    && slot.hasStack() && match.test(slot.getStack())) {
                out.add(slot);
            }
        }
        return out;
    }

    public static ItemStack offhandStack() {
        return mc.player == null ? ItemStack.EMPTY : mc.player.getOffHandStack();
    }

    public static boolean offhandHas(Item item) {
        ItemStack offhand = offhandStack();
        return !offhand.isEmpty() && offhand.isOf(item);
    }

    /** True when the given 0-based hotbar entry holds {@code item}. */
    public static boolean hotbarSlotHas(int hotbarIndex, Item item) {
        if (mc.player == null || hotbarIndex < 0 || hotbarIndex >= HOTBAR_SLOTS) {
            return false;
        }
        ItemStack stack = mc.player.getInventory().getStack(hotbarIndex);
        return !stack.isEmpty() && stack.isOf(item);
    }

    /**
     * The player's own inventory slot in {@code screen} whose stack {@code match} accepts, skipping
     * the offhand stack. With {@code nearestFirst} the slot closest to the cursor wins, which is the
     * slot a cursor-simulating module has to walk to; otherwise the first hotbar entry wins, then
     * the earliest main-inventory entry — the order a player refills in.
     *
     * @param cursorX scaled cursor x, only read when {@code nearestFirst}
     * @param cursorY scaled cursor y, only read when {@code nearestFirst}
     */
    public static Slot playerSlot(HandledScreen<?> screen, Predicate<ItemStack> match,
                                  double cursorX, double cursorY, boolean nearestFirst) {
        if (screen == null || mc.player == null || match == null) {
            return null;
        }
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        Slot best = null;
        double nearest = Double.MAX_VALUE;
        int lowestIndex = Integer.MAX_VALUE;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (!isPlayerInventorySlot(slot) || !slot.hasStack() || !match.test(slot.getStack())) {
                continue;
            }
            if (nearestFirst) {
                double dx = (double) (accessor.getX() + slot.x) + 8.0 - cursorX;
                double dy = (double) (accessor.getY() + slot.y) + 8.0 - cursorY;
                double distance = dx * dx + dy * dy;
                if (distance >= nearest) {
                    continue;
                }
                nearest = distance;
                best = slot;
            } else if (slot.getIndex() < lowestIndex) {
                lowestIndex = slot.getIndex();
                best = slot;
            }
        }
        return best;
    }

    /** A slot of the player's own inventory that is not the offhand stack. */
    private static boolean isPlayerInventorySlot(Slot slot) {
        if (slot.id == OFFHAND_HANDLER_SLOT) {
            return false;
        }
        return slot.inventory != mc.player.getInventory() || slot.getIndex() != OFFHAND_INVENTORY_INDEX;
    }
}
