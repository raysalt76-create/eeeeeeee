package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.HandledScreenAccessor;
import interstellarTikiTiks.apexx.client.mixin.HandledScreenInterface;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

/**
 * One inventory-to-hotbar (or offhand) move, done the way a player does it.
 *
 * <p><b>Visible path:</b> open the player inventory, walk the real cursor onto the item, and once
 * the cursor is hovering it press the bound hotbar key (or the swap-hands key for the offhand), which
 * is the vanilla hover-swap. The inventory is closed again if this task opened it.
 *
 * <p><b>Silent path:</b> no screen; the same swap click is sent straight through the player screen
 * handler. It waits for a tick with no movement input first, and when {@code guiFallback} is set it
 * falls back to the visible path if the swap is not applied.
 *
 * <p>Each wait uses the owner's own delay slider value ({@link #delayMs(double)}), never a client-wide delay. Only one task runs at a time: a non-urgent task is
 * cancelled when an urgent one starts. Drive {@link #tick()} from the module's tick and
 * {@link #frame()} from its render callback; call {@link #cancel()} from the owner's cleanup.
 */
public final class HoverSwap {
    public enum Result { PENDING, DONE, FAILED }

    private enum Phase { OPEN, AIM, VERIFY, CLOSE }

    private static final int OFFHAND_BUTTON = 40;
    private static final long TIMEOUT_MS = 3000L;
    private static final long VERIFY_BASE_MS = 150L;
    private static final long VERIFY_MIN_MS = 200L;
    private static final long VERIFY_MAX_MS = 1000L;
    /** Ticks a silent swap waits for a movement-free tick before it gives up (or falls back). */
    private static final int QUIET_TIMEOUT_TICKS = 10;
    private static final int SILENT_HARD_TIMEOUT_TICKS = 60;
    private static final double CURSOR_STEP = 60.0;

    private static HoverSwap active;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Object owner;
    private final Predicate<ItemStack> source;
    private final Predicate<ItemStack> wanted;
    private final int hotbarTarget;
    private final boolean useCraftingGrid;
    private final boolean includeHotbar;
    private final boolean urgent;
    private final boolean guiFallback;

    /** The owning module's delay slider (ms) for every wait of the task; never 0 by default. */
    private double delaySliderMs = 100.0;
    private boolean silent;
    private Phase phase;
    private long startedMs;
    private long dueMs;
    private long verifyDeadlineMs;
    private int quietWaitTicks;
    private int silentAttempts;
    private boolean pressed;
    private boolean fallbackClickSent;
    private boolean openedByUs;
    private boolean finished;
    private int sourceSlotId = -1;

    /**
     * @param source          matches the stack to move
     * @param wanted          matches the stack that proves the destination is filled
     * @param hotbarTarget    hotbar index 0-8, or -1 for the offhand
     * @param useCraftingGrid also take stacks from the 2x2 crafting grid
     * @param includeHotbar   also take stacks from other hotbar slots
     */
    public HoverSwap(Object owner, Predicate<ItemStack> source, Predicate<ItemStack> wanted, int hotbarTarget,
                     boolean useCraftingGrid, boolean includeHotbar, boolean silent, boolean urgent,
                     boolean guiFallback) {
        this.owner = owner;
        this.source = source;
        this.wanted = wanted;
        this.hotbarTarget = hotbarTarget;
        this.useCraftingGrid = useCraftingGrid;
        this.includeHotbar = includeHotbar;
        this.silent = silent;
        this.urgent = urgent;
        this.guiFallback = guiFallback;
    }

    /** Sets the owner's delay slider value (ms, 0 allowed) used for every wait of this task. */
    public HoverSwap delayMs(double sliderMs) {
        this.delaySliderMs = sliderMs;
        return this;
    }

    private long stepDelayMs() {
        return ModuleDelays.clampMs(delaySliderMs);
    }

    public static boolean anyActive() {
        return active != null && !active.finished;
    }

    /** Starts the task. Returns false when another task owns the inventory and this one may not preempt it. */
    public boolean begin() {
        if (mc.player == null || mc.world == null) return false;
        if (active != null && !active.finished) {
            if (!urgent || active.urgent) return false;
            active.cancel();
        }
        if (findSource() == null) return false;
        active = this;
        startedMs = System.currentTimeMillis();
        dueMs = startedMs + stepDelayMs();
        phase = silent ? Phase.AIM : Phase.OPEN;
        return true;
    }

    public Object owner() {
        return owner;
    }

    /** Per client tick, before movement. */
    public Result tick() {
        if (finished) return Result.FAILED;
        if (!valid()) return fail();
        long now = System.currentTimeMillis();
        if (now - startedMs > TIMEOUT_MS) return fail();
        if (satisfied() && phase != Phase.CLOSE) return onSatisfied(now);

        switch (phase) {
            case OPEN -> {
                if (mc.currentScreen instanceof InventoryScreen) {
                    phase = Phase.AIM;
                    dueMs = now + stepDelayMs();
                } else if (now >= dueMs) {
                    openedByUs = true;
                    mc.setScreen(new InventoryScreen(mc.player));
                    phase = Phase.AIM;
                    dueMs = now + stepDelayMs();
                }
            }
            case AIM -> {
                return silent ? tickSilent(now) : tickVisible(now);
            }
            case VERIFY -> {
                if (now < verifyDeadlineMs) break;
                if (silent) {
                    if (++silentAttempts < 2) {
                        phase = Phase.AIM;
                        quietWaitTicks = 0;
                        dueMs = now + stepDelayMs();
                        break;
                    }
                    return silentFailed(now);
                }
                if (!fallbackClickSent && sourceSlotId >= 0) {
                    // The key press was not applied: send the packet the key would have produced.
                    fallbackClickSent = true;
                    clickSource();
                    verifyDeadlineMs = now + verifyWindow();
                    break;
                }
                return fail();
            }
            case CLOSE -> {
                if (now < dueMs) break;
                if (openedByUs && mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
                return finish(Result.DONE);
            }
        }
        return Result.PENDING;
    }

    /** Per render frame: glides the real cursor toward the item while the visible path is aiming. */
    public void frame() {
        if (finished || silent || phase != Phase.AIM) return;
        if (!(mc.currentScreen instanceof InventoryScreen screen) || !valid()) return;
        Slot slot = findSource();
        if (slot == null) return;
        sourceSlotId = slot.id;
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        SystemInputSimulator.moveCursorToScaled(screen.width, screen.height,
                accessor.getX() + slot.x + 8.0, accessor.getY() + slot.y + 8.0,
                CURSOR_STEP, ExecutionMode.isDirect() ? "Instant" : "Heuristic");
    }

    /** Stops the task and closes the inventory if this task opened it. Idempotent. */
    public void cancel() {
        if (finished) return;
        if (openedByUs && mc.player != null && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
        finished = true;
        if (active == this) active = null;
    }

    // ------------------------------------------------------------------ paths

    private Result tickSilent(long now) {
        Slot slot = findSource();
        if (slot == null) return fail();
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return Result.PENDING;
        if (now < dueMs) return Result.PENDING;
        if (!quiet()) {
            quietWaitTicks++;
            if (quietWaitTicks > SILENT_HARD_TIMEOUT_TICKS) return fail();
            if (quietWaitTicks > QUIET_TIMEOUT_TICKS && guiFallback) return toVisible(now);
            return Result.PENDING;
        }
        sourceSlotId = slot.id;
        clickSource();
        phase = Phase.VERIFY;
        verifyDeadlineMs = now + verifyWindow();
        return Result.PENDING;
    }

    private Result tickVisible(long now) {
        if (!(mc.currentScreen instanceof InventoryScreen screen)) {
            // The player closed it or something replaced it: stop rather than fight for the screen.
            return fail();
        }
        if (!screen.getScreenHandler().getCursorStack().isEmpty()) return Result.PENDING;
        Slot slot = findSource();
        if (slot == null) return fail();
        sourceSlotId = slot.id;
        if (now < dueMs || ((HandledScreenInterface) screen).getFocusedSlot() != slot) return Result.PENDING;
        if (!pressed) {
            pressed = true;
            if (hotbarTarget >= 0) SystemInputSimulator.tapHotbarSlotPhysical(hotbarTarget);
            else SystemInputSimulator.tapSwapHands();
            phase = Phase.VERIFY;
            verifyDeadlineMs = now + verifyWindow();
        }
        return Result.PENDING;
    }

    private Result silentFailed(long now) {
        if (guiFallback) return toVisible(now);
        return fail();
    }

    private Result toVisible(long now) {
        silent = false;
        pressed = false;
        phase = Phase.OPEN;
        dueMs = now + stepDelayMs();
        return Result.PENDING;
    }

    private Result onSatisfied(long now) {
        if (openedByUs) {
            phase = Phase.CLOSE;
            dueMs = now + stepDelayMs();
            return Result.PENDING;
        }
        return finish(Result.DONE);
    }

    // ------------------------------------------------------------------ helpers

    private void clickSource() {
        int button = hotbarTarget >= 0 ? hotbarTarget : OFFHAND_BUTTON;
        SystemInputSimulator.clickSlotNow(mc.player.currentScreenHandler.syncId, sourceSlotId, button,
                SlotActionType.SWAP);
    }

    private boolean valid() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || !mc.player.isAlive()) return false;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return false;
        // Clicks are bound to the player handler; any other open container invalidates the task.
        return mc.player.currentScreenHandler == mc.player.playerScreenHandler;
    }

    private boolean satisfied() {
        ItemStack dest = hotbarTarget >= 0 ? mc.player.getInventory().getStack(hotbarTarget) : mc.player.getOffHandStack();
        return wanted.test(dest);
    }

    private boolean quiet() {
        var input = mc.player.input;
        if (input == null) return true;
        var pi = input.playerInput;
        boolean moving = pi.forward() || pi.backward() || pi.left() || pi.right() || pi.jump();
        return !moving && (urgent || !mc.player.isUsingItem());
    }

    /** The stack to move: nearest to the cursor while a screen is up, otherwise the first match. */
    private Slot findSource() {
        if (mc.player == null) return null;
        Slot best = null;
        double bestDist = Double.MAX_VALUE;
        double cx = 0.0, cy = 0.0;
        InventoryScreen screen = mc.currentScreen instanceof InventoryScreen s ? s : null;
        if (screen != null) {
            cx = SystemInputSimulator.getScaledCursorX(screen.width);
            cy = SystemInputSimulator.getScaledCursorY(screen.height);
        }
        for (Slot slot : mc.player.playerScreenHandler.slots) {
            if (!eligible(slot) || !source.test(slot.getStack())) continue;
            if (screen == null) return slot;
            HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
            double dx = accessor.getX() + slot.x + 8.0 - cx;
            double dy = accessor.getY() + slot.y + 8.0 - cy;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = slot;
            }
        }
        return best;
    }

    private boolean eligible(Slot slot) {
        int id = slot.id;
        boolean main = id >= 9 && id <= 35;
        boolean grid = useCraftingGrid && id >= 1 && id <= 4;
        boolean hotbar = includeHotbar && id >= 36 && id <= 44 && (id - 36) != hotbarTarget;
        return (main || grid || hotbar) && slot.hasStack();
    }

    private long verifyWindow() {
        int ping = 0;
        if (mc.getNetworkHandler() != null && mc.player != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) ping = entry.getLatency();
        }
        return Math.max(VERIFY_MIN_MS, Math.min(VERIFY_MAX_MS, VERIFY_BASE_MS + ping));
    }

    private Result fail() {
        return finish(Result.FAILED);
    }

    private Result finish(Result result) {
        cancel();
        return result;
    }
}
