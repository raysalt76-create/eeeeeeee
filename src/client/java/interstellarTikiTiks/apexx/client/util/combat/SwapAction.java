package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import net.minecraft.client.MinecraftClient;

/**
 * One module's same-tick "select a slot, then act" step, plus ownership of the slot it selected.
 *
 * <p>The contract this implements is the Same-Tick Swap + Action rule: the hotbar selection goes
 * through {@link SlotSync} first, and the use or attack follows later in that same tick. In Direct
 * execution mode the action follows the selection in the same call; in Legit mode a
 * {@link SubTickSequencer} sequence puts the Timing Profile micro-delay between them (collapsed to
 * zero while the tick's action window is open, so both still leave before the movement packet). A
 * selection that is refused ({@link TickActionOrder#canSwitchSlot()}, another module's swap lock) does
 * nothing and is retried by the caller on a later tick; the action never runs against a slot that was
 * not selected.</p>
 *
 * <p>The module owns what it selected. {@link #restore()} puts the original slot back only while the
 * selection is still the one made here, and it does so through {@link TickActionOrder#deferSlot} so the
 * swap-back leaves at the head of the next movement interval, never in the call that acted.</p>
 */
public final class SwapAction {

    private final Module owner;
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private SubTickSequencer.FluentSequence pending;
    private long queuedTick = Long.MIN_VALUE;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    /** The slot this module expects to find selected next tick: its latest selection or restore. */
    private int expectedSlot = -1;
    /** The slot that was held before the latest selection (a refused or dropped swap leaves it there). */
    private int previousSlot = -1;

    public SwapAction(Module owner) {
        this.owner = owner;
    }

    /**
     * Runs {@code action} with {@code slot} held. {@code slot < 0} means whatever is held.
     *
     * @return true when the action ran or was queued for later in this tick; false when nothing was
     *         done (the selection was refused, or a queued step is still in flight)
     */
    public boolean act(int slot, Runnable action) {
        if (mc.player == null || mc.world == null || action == null || queuedThisTick()) return false;
        int held = mc.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot == held) {
            expectedSlot = held;
            previousSlot = held;
            action.run();
            return true;
        }
        if (slot > 8 || !TickActionOrder.canSwitchSlot() || swapLocked()) return false;
        int before = ownedSlot >= 0 && held == ownedSlot ? originalSlot : held;
        if (ExecutionMode.isDirect()) {
            if (!SlotSync.selectNow(mc, slot)) return false;
            originalSlot = before;
            ownedSlot = slot;
            expectedSlot = slot;
            previousSlot = held;
            TickActionOrder.cancelDeferredSlot();
            action.run();
            return true;
        }
        long tick = SubTickSequencer.currentTick();
        queuedTick = tick;
        try {
            pending = SubTickSequencer.sequence(mc, owner)
                    .selectSlot(slot)
                    .action(() -> {
                        originalSlot = before;
                        ownedSlot = slot;
                        TickActionOrder.cancelDeferredSlot();
                    })
                    .thenDelay()
                    .action(() -> {
                        queuedTick = Long.MIN_VALUE;
                        if (SubTickSequencer.currentTick() == tick && mc.player != null
                                && mc.player.getInventory().getSelectedSlot() == slot) {
                            action.run();
                        }
                    });
            expectedSlot = slot;
            previousSlot = held;
            pending.start();
        } catch (RuntimeException failure) {
            SubTickSequencer.cancelOwner(owner);
            pending = null;
            queuedTick = Long.MIN_VALUE;
            return false;
        }
        return true;
    }

    /** True while this module's swap step for the current tick is still waiting to act. */
    public boolean queuedThisTick() {
        if (queuedTick == Long.MIN_VALUE) return false;
        if (queuedTick != SubTickSequencer.currentTick() || (pending != null && pending.isComplete())) {
            // The sequencer dropped it, or the tick moved on: the step is over either way.
            queuedTick = Long.MIN_VALUE;
            pending = null;
            return false;
        }
        return true;
    }

    /** True while this module holds a slot it selected and has not yet put back. */
    public boolean holdsSlot() {
        return ownedSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() == ownedSlot;
    }

    public int originalSlot() {
        return originalSlot;
    }

    /**
     * Puts the original slot back at the head of the next movement interval, only while the selection
     * is still the one this module made. Safe to call repeatedly and when nothing is owned.
     */
    public void restore() {
        int back = originalSlot;
        int owned = ownedSlot;
        originalSlot = ownedSlot = -1;
        if (back < 0 || owned < 0 || back == owned || mc.player == null || mc.world == null
                || mc.player.getInventory().getSelectedSlot() != owned) {
            return;
        }
        expectedSlot = back;
        previousSlot = owned;
        TickActionOrder.deferSlot(back, owned);
    }

    /**
     * Like {@link #restore()}, but selects the original slot immediately when the interval's action
     * window allows it (no attack/use yet this interval); otherwise it falls back to the deferred
     * restore at the next tick head.
     */
    public void restoreNow() {
        int back = originalSlot;
        int owned = ownedSlot;
        if (back >= 0 && owned >= 0 && back != owned && mc.player != null && mc.world != null
                && mc.player.getInventory().getSelectedSlot() == owned && TickActionOrder.canSwitchSlot()
                && !swapLocked() && SlotSync.selectNow(mc, back)) {
            originalSlot = ownedSlot = -1;
            expectedSlot = back;
            previousSlot = owned;
            TickActionOrder.cancelDeferredSlot();
            return;
        }
        restore();
    }

    /**
     * True when the selected slot is none this module put there: the player pressed a hotbar key or
     * scrolled. Call at the head of the module's tick. The module is then to drop everything it holds
     * ({@link #reset}) and leave the slot alone; the answer clears once it has been given.
     */
    public boolean takeUserSlotChange() {
        if (expectedSlot < 0 || mc.player == null || queuedThisTick() || TickActionOrder.hasDeferredSlot()) {
            return false;
        }
        int held = mc.player.getInventory().getSelectedSlot();
        if (held == expectedSlot || held == previousSlot) return false;
        expectedSlot = -1;
        previousSlot = -1;
        return true;
    }

    /**
     * The player chose the held slot mid-sequence and the sequence goes on: the choice becomes the slot
     * the next {@link #act} comes back to. Nothing is owned until the next selection.
     */
    public void adoptUserSlot() {
        if (mc.player == null) return;
        int held = mc.player.getInventory().getSelectedSlot();
        originalSlot = ownedSlot = -1;
        expectedSlot = held;
        previousSlot = held;
    }

    /** Drops queued work and ownership without touching the hotbar (world change, disconnect). */
    public void reset(boolean dropDeferredRestore) {
        SubTickSequencer.cancelOwner(owner);
        pending = null;
        queuedTick = Long.MIN_VALUE;
        originalSlot = ownedSlot = -1;
        expectedSlot = -1;
        previousSlot = -1;
        if (dropDeferredRestore) TickActionOrder.cancelDeferredSlot();
    }

    private boolean swapLocked() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(owner);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }
}
