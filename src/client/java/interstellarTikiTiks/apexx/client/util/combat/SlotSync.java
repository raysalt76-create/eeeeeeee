package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.ClientPlayerInteractionManagerMixin;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import net.minecraft.client.MinecraftClient;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Hotbar selection that the server has actually seen before the next action runs.
 *
 * <h2>The race this exists to close</h2>
 * {@code PlayerInventory.setSelectedSlot} mutates client state only. The matching
 * {@code UpdateSelectedSlotC2SPacket} is not emitted there -- {@code ClientPlayerEntity.tick()}
 * sends it later, when it notices the selection changed. So the very common shape
 *
 * <pre>
 *     player.getInventory().setSelectedSlot(pearlSlot);
 *     ClickSimController.pulseUse(mc, ...);          // same tick
 * </pre>
 *
 * lets the server process the use against whatever was in hand <em>before</em> the
 * switch. In practice that means throwing the wrong item: the pearl macro uses the
 * sword, the windcharge macro uses the pearl. It is intermittent, because it depends on
 * where in the tick the packets land, which is exactly why it survives casual testing.
 *
 * <p>Eight call sites across four combat modules had this shape, plus the firework path
 * in combat modules. Rather than copy the three-line incantation into each of them, they all
 * route here.
 */
public final class SlotSync {

    private static final Object INTENT_LOCK = new Object();
    private static final Map<Object, SlotIntent> intents = new IdentityHashMap<>();

    private SlotSync() {
    }

    /**
     * Selects {@code slot} and pushes the change to the server immediately,
     * recording the slot selection timestamp in SubTickSequencer for sub-tick pacing.
     *
     * @return true if the selection is committed and it is safe to act on it now
     */
    public static boolean select(MinecraftClient mc, int slot) {
        return selectNow(mc, slot);
    }

    /**
     * Immediately applies the slot selection through the player's inventory and syncs it with the server,
     * and records the swap so the action that follows can be spaced from it ({@link #paceAfterSwap}).
     * Nothing here, or anywhere in the client, constructs a raw slot packet.
     */
    public static boolean selectNow(MinecraftClient mc, int slot) {
        if (mc == null || mc.player == null || slot < 0 || slot > 8) {
            return false;
        }
        boolean changed = mc.player.getInventory().getSelectedSlot() != slot;
        if (!applySelectionNow(mc, slot)) return false;
        recordSelectionIntent(slot);
        if (changed) SubTickSequencer.recordSlotSelection(slot);
        return true;
    }

    /** Longest in-thread wait for the swap gap, ns (the gap itself is sampled 1-3 ms). */
    private static final long MAX_PACE_NANOS = 3_500_000L;

    /**
     * Keeps the slot packet and the action that follows it at least 1-3 ms apart. Every action seam
     * (attack, use, place) calls this first; it returns immediately unless a hotbar swap happened a moment
     * ago, in which case it waits out the rest of the sampled gap. The wait is a short in-thread spin so
     * the calling module's control flow is unchanged, and it is skipped when the tick is nearly over.
     */
    public static void paceAfterSwap() {
        long remaining = SubTickSequencer.swapGapRemainingNanos();
        if (remaining <= 0L) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread()) return;
        long wait = paceWaitNanos(remaining, SubTickSequencer.remainingTickBudgetMs());
        if (wait <= 0L) return;
        long deadline = System.nanoTime() + wait;
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    /** Minimum tick budget (ms) left for the swap gap to be waited out in-thread. */
    static final double MIN_PACE_BUDGET_MS = 8.0;

    /**
     * How long {@link #paceAfterSwap} spins: the remaining swap gap, capped at {@link #MAX_PACE_NANOS};
     * 0 when nothing remains or the tick is nearly over (the movement packet must stay in its tick).
     */
    static long paceWaitNanos(long remainingGapNanos, double tickBudgetMs) {
        if (remainingGapNanos <= 0L || tickBudgetMs < MIN_PACE_BUDGET_MS) return 0L;
        return Math.min(remainingGapNanos, MAX_PACE_NANOS);
    }

    private static boolean applySelectionNow(MinecraftClient mc, int slot) {
        if (!mc.isOnThread() || !(mc.interactionManager
                instanceof ClientPlayerInteractionManagerMixin.ClientPlayerInteractionManagerAccessor acc)) return false;
        mc.player.getInventory().setSelectedSlot(slot);
        acc.invokeSyncSelectedSlot();
        return true;
    }

    /**
     * Swap-back: selects {@code slot} now when the packet interval allows it, otherwise queues it
     * through {@link TickActionOrder#deferSlot} for the start of the next interval, so a restore
     * is never dropped. The queued restore is skipped if the player has moved off the slot held now.
     *
     * @return true if the slot is selected now; false if it was queued or could not be applied
     */
    public static boolean restore(MinecraftClient mc, int slot) {
        if (mc == null || mc.player == null || slot < 0 || slot > 8) {
            return false;
        }
        int held = mc.player.getInventory().getSelectedSlot();
        if (held == slot) {
            return true;
        }
        if (!TickActionOrder.canSwitchSlot()) {
            TickActionOrder.deferSlot(slot, held);
            return false;
        }
        return select(mc, slot);
    }

    /** Records the short-lived slot expectation of the module currently executing. */
    public static void recordSelectionIntent(int slot) {
        if (slot < 0 || slot > 8) return;
        Object owner = ModuleExecutionContext.currentOwner();
        if (owner == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || !mc.isOnThread()) return;
        synchronized (INTENT_LOCK) {
            SlotIntent intent = intents.computeIfAbsent(owner, ignored -> new SlotIntent());
            intent.slot = slot;
            intent.player = mc.player;
            intent.world = mc.world;
            intent.tick = mc.player.age;
        }
    }

    /** Refuses a displaced same-tick action instead of stealing a newer slot selection. */
    public static boolean ensureSelectionForCurrentOwner(MinecraftClient mc) {
        if (mc == null || mc.player == null) return false;
        Object owner = ModuleExecutionContext.currentOwner();
        if (owner == null) return true;

        synchronized (INTENT_LOCK) {
            SlotIntent intent = intents.get(owner);
            if (intent == null) return true;
            if (intent.player != mc.player || intent.world != mc.world) {
                intents.remove(owner);
                return false;
            }
            if (intent.tick != mc.player.age) {
                intents.remove(owner);
                return true;
            }
            return mc.player.getInventory().getSelectedSlot() == intent.slot;
        }
    }

    public static void releaseOwner(Object owner) {
        if (owner == null) return;
        synchronized (INTENT_LOCK) {
            intents.remove(owner);
        }
    }

    public static void clearOwnership() {
        synchronized (INTENT_LOCK) {
            intents.clear();
        }
    }

    /**
     * Queues hotbar selection with a humanized micro-delay via SubTickSequencer.
     */
    public static void selectDelayed(MinecraftClient mc, int slot, double delayMs, Runnable onComplete) {
        SubTickSequencer.queueAction(() -> {
            if (select(mc, slot) && onComplete != null) {
                onComplete.run();
            }
        }, delayMs);
    }

    /** Current hotbar index, or -1 when there is no player. */
    public static int current(MinecraftClient mc) {
        if (mc == null || mc.player == null) {
            return -1;
        }
        return mc.player.getInventory().getSelectedSlot();
    }

    private static final class SlotIntent {
        int slot;
        Object player;
        Object world;
        int tick;
    }
}
