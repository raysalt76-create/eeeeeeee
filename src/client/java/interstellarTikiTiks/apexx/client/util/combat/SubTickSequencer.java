package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-thread sub-tick pacing. Missed deadlines are cancelled, never flushed into a later tick. */
public final class SubTickSequencer {
    public static final double TICK_DURATION_MS = 50.0;
    public static final double SAFETY_MARGIN_MS = 5.0;
    private static volatile boolean variationEnabled = true;
    private static volatile long tickStartNanos;
    private static volatile long tickEpoch;
    private static volatile long lastSlotSwitchTimeMs;
    private static volatile long lastSlotSwitchNanos;
    private static volatile long swapGapNanos;
    private static volatile long swapGapEpoch = Long.MIN_VALUE;
    private static volatile int lastSelectedSlot = -1;
    /** Bounds of the sampled hotbar-swap -> action gap (Server-Side Validation rule 2). */
    static final double SWAP_GAP_MIN_MS = 1.0;
    static final double SWAP_GAP_MAX_MS = 3.0;
    private static volatile long lastContainerClickTimeMs;
    private static volatile int lastContainerSyncId = -1;
    private static final Map<Object, Long> ownerEpochs = new IdentityHashMap<>();
    private static final ConcurrentLinkedQueue<QueuedAction> pendingActions = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<FluentSequence> activeSequences = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean msDispatchPending = new AtomicBoolean();

    private SubTickSequencer() {}

    public static boolean isVariationEnabled() {
        return variationEnabled && !interstellarTikiTiks.apexx.client.util.ExecutionMode.isDirect();
    }

    public static void setVariationEnabled(boolean enabled) { variationEnabled = enabled; }
    public static double getMinMicroDelayMs() { return TimingProfile.minDelayMs(); }
    public static long currentTick() { return tickEpoch; }
    public static int pendingActionCount() { return pendingActions.size(); }
    public static int activeSequenceCount() { return activeSequences.size(); }

    public static void onTickStart() {
        // Cancel preparation left by a slow frame. Recovery only touches the original player
        // and only restores a slot still selected by that sequence.
        tickEpoch++;
        pendingActions.clear();
        FluentSequence sequence;
        while ((sequence = activeSequences.poll()) != null) sequence.handleError(sequence.mc);
        tickStartNanos = System.nanoTime();
        msDispatchPending.set(false);
        // After the epoch advanced: expire last tick's post-action rotation hold and apply any
        // clear/stop a module issued in the same tick as its action (rule 4).
        interstellarTikiTiks.apexx.client.util.RotationManager.onTickStart();
    }

    public static double remainingTickBudgetMs() {
        long start = tickStartNanos;
        return start == 0L ? 0.0 : Math.max(0.0,
                TICK_DURATION_MS - SAFETY_MARGIN_MS - (System.nanoTime() - start) / 1_000_000.0);
    }

    /**
     * True while this tick's action window is open on the client thread (tick head until the
     * movement packet). Work queued from here with a delay would only run in a render frame after
     * the movement packet, one interval after the swap that preceded it, so in-window delays
     * collapse to zero: the swap and its action stay in the same tick, as vanilla sends a hotbar
     * key and a click handled in one tick.
     */
    private static boolean inActionWindow(MinecraftClient mc) {
        return mc != null && mc.isOnThread() && TickActionOrder.isWindowOpen();
    }

    public static double sampleMicroDelayMs() {
        if (inActionWindow(MinecraftClient.getInstance())) return 0.0;
        return sampleClickPacingMs();
    }

    /** Pacing between container clicks: no world action follows them, so the window does not apply. */
    private static double sampleClickPacingMs() {
        double budget = remainingTickBudgetMs();
        if (budget <= 2.0 || !isVariationEnabled()) return 0.0;
        return Math.min(TimingProfile.delayMsExact(), budget * 0.85);
    }

    public static double sampleDefaultMicroDelayMs() { return sampleMicroDelayMs(); }

    /**
     * Waits for an absolute mechanic deadline only while it fits before this tick's movement.
     * Unlike cosmetic swap pacing, this delay is never collapsed, even in Direct mode. A caller
     * receiving false must revalidate in a later action window, rather than enqueue a stale hit.
     */
    public static boolean awaitActionDeadline(MinecraftClient mc, long deadlineNanos) {
        return awaitActionDeadline(deadlineNanos,
                () -> inActionWindow(mc) && mc.player != null && mc.world != null && mc.currentScreen == null,
                SubTickSequencer::remainingTickBudgetMs);
    }

    static boolean awaitActionDeadline(long deadlineNanos, java.util.function.BooleanSupplier canAct,
                                       java.util.function.DoubleSupplier tickBudgetMs) {
        if (!canAct.getAsBoolean()) return false;
        long remaining = deadlineNanos - System.nanoTime();
        double budget = tickBudgetMs.getAsDouble();
        if (budget <= 0.0 || remaining > (long) (budget * 1_000_000.0)) return false;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0L) {
            if (Thread.currentThread().isInterrupted()) return false;
            if (remaining > 1_000_000L) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining - 500_000L);
            } else {
                Thread.onSpinWait();
            }
        }
        return canAct.getAsBoolean() && tickBudgetMs.getAsDouble() > 0.0;
    }

    /**
     * Holds the client thread so the hotbar packet that was just sent and the action that follows it
     * reach the wire a real, bounded interval apart, still inside this tick's action window and before
     * its movement packet. A swap and its action are one tick in vanilla, but two sequential Java calls
     * can leave in the same millisecond; this gives {@link TimingProfile} an actual effect on the wire.
     * Direct execution uses the 1-3 ms end of the profile; the wait never exceeds half of what is left
     * of the tick, so the movement packet is not pushed out of its tick.
     */
    public static void awaitWireSeparation(MinecraftClient mc) {
        if (mc == null || !mc.isOnThread() || !TickActionOrder.isWindowOpen()) return;
        double target = TimingProfile.delayMsExact();
        if (interstellarTikiTiks.apexx.client.util.ExecutionMode.isDirect()) {
            target = Math.min(Math.max(1.0, target), 3.0);
        }
        double wait = Math.min(target, remainingTickBudgetMs() * 0.5);
        if (wait < 0.5) return;
        long end = System.nanoTime() + (long) (wait * 1_000_000.0);
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
    public static void recordSlotSelection(int slot) {
        lastSlotSwitchTimeMs = System.currentTimeMillis();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        swapGapNanos = sampleSwapGapNanos(random.nextDouble(), random.nextDouble());
        swapGapEpoch = tickEpoch;
        lastSlotSwitchNanos = System.nanoTime();
        lastSelectedSlot = slot;
    }

    /**
     * A swap -> action gap in [{@link #SWAP_GAP_MIN_MS}, {@link #SWAP_GAP_MAX_MS}] ms, triangular around
     * the midpoint (the mean of two uniforms), so the spacing is never a fixed constant.
     */
    static long sampleSwapGapNanos(double u1, double u2) {
        double t = Math.min(1.0, Math.max(0.0, (u1 + u2) * 0.5));
        return (long) ((SWAP_GAP_MIN_MS + (SWAP_GAP_MAX_MS - SWAP_GAP_MIN_MS) * t) * 1_000_000.0);
    }

    /** Pure form of {@link #swapGapRemainingNanos()}: 0 when no swap is on record or the gap has elapsed. */
    static long gapRemainingNanos(long switchNanos, long gapNanos, long nowNanos) {
        if (switchNanos == 0L || gapNanos <= 0L) return 0L;
        long elapsed = nowNanos - switchNanos;
        if (elapsed < 0L) return gapNanos;
        return Math.max(0L, gapNanos - elapsed);
    }
    public static long getLastSlotSwitchTimeMs() { return lastSlotSwitchTimeMs; }
    public static int getLastSelectedSlot() { return lastSelectedSlot; }
    public static void recordContainerClick(int syncId) {
        lastContainerClickTimeMs = System.currentTimeMillis();
        lastContainerSyncId = syncId;
    }
    public static long getLastContainerClickTimeMs() { return lastContainerClickTimeMs; }
    public static int getLastContainerSyncId() { return lastContainerSyncId; }

    private static long ownerEpoch(Object owner) {
        synchronized (ownerEpochs) { return ownerEpochs.getOrDefault(owner, 0L); }
    }

    public static void queueAction(Runnable action, double delayMs) {
        queueAction(ModuleExecutionContext.currentOwner(), action, delayMs);
    }

    public static void queueAction(Object owner, Runnable action, double delayMs) {
        if (action == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        // Off the client thread the player can vanish between the null check and the read: take one reference.
        net.minecraft.client.network.ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;
        Ticket ticket = new Ticket(mc, owner, player.getInventory().getSelectedSlot());
        if (mc.isOnThread() && (delayMs <= 0.0 || !isVariationEnabled() || remainingTickBudgetMs() <= 2.0
                || inActionWindow(mc))) {
            if (ticket.valid(mc)) ModuleExecutionContext.run(owner, action);
            return;
        }
        double delay = isVariationEnabled() ? Math.max(0.0, Math.min(delayMs, remainingTickBudgetMs())) : 0.0;
        pendingActions.add(new QueuedAction(System.nanoTime() + (long) (delay * 1_000_000L), action, -1, ticket));
        // Even zero-delay handoffs remain tracked and cancellable.
        if (!mc.isOnThread()) mc.send(() -> pollAndExecute(mc));
    }

    public static void cancelOwner(Object owner) {
        if (owner == null) return;
        synchronized (ownerEpochs) { ownerEpochs.put(owner, ownerEpoch(owner) + 1L); }
        pendingActions.removeIf(action -> action.ticket.owner == owner);
        activeSequences.removeIf(sequence -> {
            if (sequence.owner != owner) return false;
            sequence.handleError(sequence.mc);
            return true;
        });
    }

    public static void discardAll() {
        tickEpoch++;
        pendingActions.clear();
        for (FluentSequence sequence : activeSequences) sequence.complete = true;
        activeSequences.clear();
        synchronized (ownerEpochs) { ownerEpochs.clear(); }
        tickStartNanos = 0L;
        lastSlotSwitchTimeMs = lastContainerClickTimeMs = 0L;
        lastSlotSwitchNanos = swapGapNanos = 0L;
        swapGapEpoch = Long.MIN_VALUE;
        lastSelectedSlot = lastContainerSyncId = -1;
        msDispatchPending.set(false);
    }

    public static void queueContainerClick(MinecraftClient mc, int syncId, int slotId, int button, SlotActionType type) {
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        Object owner = ModuleExecutionContext.currentOwner();
        Ticket ticket = new Ticket(mc, owner, -1);
        Runnable click = () -> {
            if (mc.player.currentScreenHandler.syncId != syncId) return;
            if (slotId < 0 || slotId >= mc.player.currentScreenHandler.slots.size()) return;
            recordContainerClick(syncId);
            mc.interactionManager.clickSlot(syncId, slotId, button, type, mc.player);
        };
        double delay = isVariationEnabled() && System.currentTimeMillis() - lastContainerClickTimeMs < TimingProfile.maxDelayMs()
                ? sampleClickPacingMs() : 0.0;
        if (mc.isOnThread() && delay <= 0.0) {
            if (ticket.valid(mc)) ModuleExecutionContext.run(owner, click);
        } else {
            pendingActions.add(new QueuedAction(System.nanoTime() + (long) (delay * 1_000_000L), click, syncId, ticket));
        }
    }

    public static void pollAndExecute(MinecraftClient mc) {
        if (mc == null || !mc.isOnThread()) return;
        try {
            long now = System.nanoTime();
            List<QueuedAction> remaining = new ArrayList<>();
            QueuedAction action;
            while ((action = pendingActions.poll()) != null) {
                if (!action.ticket.valid(mc)) {
                    continue;
                }
                if (action.dueNanos > now) { remaining.add(action); continue; }
                try {
                    if (action.syncId == -1 || mc.player.currentScreenHandler.syncId == action.syncId) {
                        ModuleExecutionContext.run(action.ticket.owner, action.action);
                    }
                } catch (Throwable failure) {
                    ClientInternalLog.error("SubTickSequencer", "Delayed action failed", failure);
                }
            }
            pendingActions.addAll(remaining);
            List<FluentSequence> remainingSequences = new ArrayList<>();
            FluentSequence sequence;
            while ((sequence = activeSequences.poll()) != null) {
                try {
                    sequence.poll(mc, System.nanoTime());
                    if (!sequence.complete) remainingSequences.add(sequence);
                } catch (Throwable failure) {
                    sequence.handleError(mc);
                    ClientInternalLog.error("SubTickSequencer", "Delayed sequence failed", failure);
                }
            }
            activeSequences.addAll(remainingSequences);
        } finally { msDispatchPending.set(false); }
    }

    public static void onMsTick(MinecraftClient mc) {
        if (mc != null && (!pendingActions.isEmpty() || !activeSequences.isEmpty())
                && msDispatchPending.compareAndSet(false, true)) {
            mc.send(() -> pollAndExecute(mc));
        }
    }

    /** Compatibility entrypoint: only ready, valid work may run; delays are never collapsed. */
    public static void hardFlush(MinecraftClient mc) { pollAndExecute(mc); }
    public static FluentSequence sequence(MinecraftClient mc) {
        return sequence(mc, ModuleExecutionContext.currentOwner());
    }
    public static FluentSequence sequence(MinecraftClient mc, Object owner) { return new FluentSequence(mc, owner); }

    private static final class Ticket {
        final Object owner;
        final Object player;
        final Object world;
        final long tick = tickEpoch;
        final long epoch;
        int expectedSlot;
        Ticket(MinecraftClient mc, Object owner, int slot) {
            this.owner = owner;
            player = mc.player;
            world = mc.world;
            epoch = ownerEpoch(owner);
            expectedSlot = slot;
        }
        boolean valid(MinecraftClient mc) {
            return !Apexx.isTornDown() && tick == tickEpoch && epoch == ownerEpoch(owner)
                    && mc.player != null && mc.player == player && mc.world != null && mc.world == world
                    && (!(owner instanceof Module module) || module.isEnabled())
                    && (expectedSlot < 0 || mc.player.getInventory().getSelectedSlot() == expectedSlot)
                    && remainingTickBudgetMs() > 0.0;
        }
    }
    private record QueuedAction(long dueNanos, Runnable action, int syncId, Ticket ticket) {}

    public static final class FluentSequence {
        private final MinecraftClient mc;
        private final Object owner;
        private final List<SequenceStep> steps = new ArrayList<>();
        private Ticket ticket;
        private int index;
        private long nextStepNanos;
        private boolean started;
        private boolean complete;
        private int originalSlot = -1;
        private int selectedSlot = -1;
        public FluentSequence(MinecraftClient mc) { this(mc, ModuleExecutionContext.currentOwner()); }
        public FluentSequence(MinecraftClient mc, Object owner) { this.mc = mc; this.owner = owner; }
        public FluentSequence selectSlot(int slot) {
            steps.add(new SequenceStep(0.0, () -> {
                if (!SlotSync.select(mc, slot)) throw new IllegalStateException("Slot selection failed");
                selectedSlot = slot;
                ticket.expectedSlot = slot;
            }));
            return this;
        }
        public FluentSequence thenDelay() { steps.add(new SequenceStep(sampleMicroDelayMs(), null)); return this; }
        public FluentSequence action(Runnable action) { steps.add(new SequenceStep(0.0, action)); return this; }
        public FluentSequence restoreSlot(int slot) {
            originalSlot = slot;
            steps.add(new SequenceStep(0.0, () -> restoreOwnedSlot(mc)));
            return this;
        }
        public void start() {
            if (started) throw new IllegalStateException("Sequence already started");
            started = true;
            if (mc == null || mc.player == null || mc.world == null || steps.isEmpty()) { complete = true; return; }
            ticket = new Ticket(mc, owner, mc.player.getInventory().getSelectedSlot());
            if (inActionWindow(mc)) {
                // Started inside the action window: every step runs now, before the movement packet.
                for (SequenceStep step : steps) step.delayMs = 0.0;
            }
            double requested = steps.stream().mapToDouble(step -> step.delayMs).sum();
            double budget = remainingTickBudgetMs() * 0.9;
            if (requested > budget && requested > 0.0) {
                for (SequenceStep step : steps) step.delayMs *= budget / requested;
            }
            nextStepNanos = System.nanoTime() + (long) (steps.getFirst().delayMs * 1_000_000L);
            activeSequences.add(this);
            if (mc.isOnThread()) poll(mc, System.nanoTime());
        }
        void poll(MinecraftClient client, long now) {
            if (!started || complete) return;
            if (!ticket.valid(client)) { handleError(client); return; }
            while (index < steps.size() && now >= nextStepNanos) {
                if (!ticket.valid(client)) { handleError(client); return; }
                SequenceStep step = steps.get(index++);
                if (step.action != null) ModuleExecutionContext.run(owner, step.action);
                // Cancellation inside the action invalidates this ticket even when it was
                // temporarily removed from activeSequences by the polling loop.
                if (!ticket.valid(client)) { handleError(client); return; }
                if (index < steps.size()) {
                    nextStepNanos = System.nanoTime() + (long) (steps.get(index).delayMs * 1_000_000L);
                    now = System.nanoTime();
                }
            }
            complete = index == steps.size();
        }
        private void restoreOwnedSlot(MinecraftClient client) {
            if (ticket != null && client.player == ticket.player && client.world == ticket.world
                    && client.player != null && originalSlot >= 0 && originalSlot <= 8
                    && selectedSlot >= 0 && client.player.getInventory().getSelectedSlot() == selectedSlot) {
                ModuleExecutionContext.run(owner, () -> SlotSync.restore(client, originalSlot));
                ticket.expectedSlot = originalSlot;
            }
            selectedSlot = -1;
        }
        void handleError(MinecraftClient client) {
            complete = true;
            if (client != null && client.isOnThread()) restoreOwnedSlot(client);
        }
        public boolean isComplete() { return complete; }
    }
    private static final class SequenceStep {
        double delayMs;
        final Runnable action;
        SequenceStep(double delayMs, Runnable action) { this.delayMs = delayMs; this.action = action; }
    }

    /**
     * Nanoseconds still to wait before an action may follow the most recent hotbar swap: the rest of the
     * 1-3 ms gap sampled when {@link SlotSync#selectNow} changed the slot. 0 when no swap happened in this
     * tick (a swap from an earlier tick never delays an action) or the gap has already elapsed.
     */
    public static long swapGapRemainingNanos() {
        if (swapGapEpoch != tickEpoch) return 0L;
        return gapRemainingNanos(lastSlotSwitchNanos, swapGapNanos, System.nanoTime());
    }
}
