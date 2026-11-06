package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

import java.util.ArrayDeque;

/**
 * Tracks which actions have already left the client since the last movement packet, so a module
 * can keep to the order vanilla produces inside one client tick: hotbar selection first, then
 * attacks and item use, then the movement packet.
 *
 * <p>Vanilla handles hotbar keys before clicks within a tick, so a slot change that follows an
 * attack or a use-release inside the same movement-packet interval is a sequence a vanilla client
 * cannot emit. Render-frame dispatch makes that easy to hit by accident: an attack sent in a frame
 * after tick N shares its packet interval with the slot change made at the head of tick N+1.
 * {@link #canSwitchSlot()} reports whether a switch is still in the allowed position, and
 * {@link #deferSlot(int)} queues a restore for the first moment it is (the next tick head).</p>
 *
 * <p><b>Action window.</b> Vanilla emits every hotbar/attack/use/dig/place packet between the head of
 * the client tick and the movement packet; nothing but the tick-end marker follows it, so such a
 * packet after the movement packet is a sequence vanilla never sends. Modules that run from render
 * frames, {@code onMs} or {@code SubTickSequencer} callbacks execute in the gap after that packet.
 * The window is open from
 * {@link #onTickHead()} until {@link #onMovementPacketSent()}; an action packet a module emits while
 * it is closed is held in order and released at the next tick head, before that tick's movement
 * packet ({@link #hold}). Packets are never dropped, rewritten or reordered, only released at the
 * position a vanilla client would have sent them.</p>
 *
 * <p>All state is touched on the client thread only. The interval boundary is
 * {@code ClientPlayerEntity#sendMovementPackets} returning, wired in {@code ClientPlayerEntityMixin};
 * the window opens from {@code MinecraftClientMixin} at tick head.</p>
 */
public final class TickActionOrder {
    private static boolean attacked;
    private static boolean used;
    /**
     * Queued restores, oldest first: a second {@link #deferSlot} (another module, or the same one
     * again) must not overwrite the first, or that restore would be lost and its module left holding
     * the wrong slot. Each entry carries the slot that must still be held for it to apply.
     */
    private static final ArrayDeque<int[]> deferredSlots = new ArrayDeque<>();
    private static final int MAX_DEFERRED = 4;

    /** Shortest randomized gap (ms) between an attack/use and the swap-back that follows it. */
    public static final int SWAP_BACK_MIN_MS = 5;
    /** Longest randomized gap (ms) between an attack/use and the swap-back that follows it. */
    public static final int SWAP_BACK_MAX_MS = 15;

    /** A randomized swap-back gap in {@link #SWAP_BACK_MIN_MS}..{@link #SWAP_BACK_MAX_MS} ms. */
    public static int sampleSwapBackMs() {
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(SWAP_BACK_MIN_MS, SWAP_BACK_MAX_MS + 1);
    }

    /** True from tick head until the movement packet has been sent. */
    private static boolean windowOpen = true;
    private static final ArrayDeque<Held> held = new ArrayDeque<>();
    private static boolean flushing;
    /** Counts movement packets; an action belongs to the interval it was dispatched in. */
    private static long interval;

    private TickActionOrder() {
    }

    /** An attack was dispatched this interval (player or module). */
    public static void recordAttack() {
        attacked = true;
    }

    /** An item use or use-release was dispatched this interval. */
    public static void recordUse() {
        used = true;
    }

    /** True while a hotbar change is still ahead of every other action in this interval (held ones included). */
    public static boolean canSwitchSlot() {
        return !attacked && !used;
    }

    /**
     * True while an entity attack may still leave in this interval. Vanilla ignores an attack click
     * while an item is in use, so a use-release (shield drop) followed by an attack in one movement
     * interval is a sequence it cannot send (Grim PacketOrderI "attack: releasing"); two attacks in one
     * interval are not either. Same state as {@link #canSwitchSlot()}: nothing has left yet.
     */
    public static boolean canAttack() {
        return !attacked && !used;
    }

    /** What a packet means for the vanilla order inside one movement interval. */
    enum Kind {
        /** Not an action (movement, keep-alive, ...): never held. */
        NONE,
        SLOT,
        SWING,
        /** Container click: only held to stay ordered behind something already held. */
        CLICK,
        /** Entity attack or interaction, spear stab. */
        ATTACK,
        /** Item/block use, use release. */
        USE,
        /** Other player actions (digging, drop, offhand swap). */
        DIG
    }

    static Kind kindOf(Packet<?> packet) {
        if (packet instanceof UpdateSelectedSlotC2SPacket) return Kind.SLOT;
        if (packet instanceof HandSwingC2SPacket) return Kind.SWING;
        if (packet instanceof ClickSlotC2SPacket) return Kind.CLICK;
        if (packet instanceof PlayerInteractEntityC2SPacket) return Kind.ATTACK;
        if (packet instanceof PlayerInteractItemC2SPacket || packet instanceof PlayerInteractBlockC2SPacket) return Kind.USE;
        if (packet instanceof PlayerActionC2SPacket action) {
            String name = action.getAction().name();
            if (name.equals("STAB")) return Kind.ATTACK;
            if (name.equals("RELEASE_USE_ITEM")) return Kind.USE;
            return Kind.DIG;
        }
        return Kind.NONE;
    }

    /**
     * Records what actually left through the send seam while the window is open, so a vanilla click,
     * use or release by the player counts as much as a module's own.
     */
    private static void recordLive(Kind kind) {
        if (kind == Kind.ATTACK) {
            attacked = true;
        } else if (kind == Kind.USE || kind == Kind.DIG) {
            used = true;
        }
    }

    /**
     * Marks a held or released action as part of the interval it will go out in. A hotbar change, an arm
     * swing and a container click do not close the interval for a later hotbar change.
     */
    private static void recordAction(Kind kind) {
        if (kind == Kind.ATTACK) {
            attacked = true;
        } else if (kind == Kind.USE || kind == Kind.DIG) {
            used = true;
        }
    }

    /**
     * The current movement-packet interval. A swap and the action that follows it share one
     * interval, held packets included, only while this value is unchanged.
     */
    public static long interval() {
        return interval;
    }

    /** Number of action packets currently held for the next tick head (diagnostics). */
    public static int heldCount() {
        return held.size();
    }

    /** True while action packets may leave in vanilla position (before this tick's movement packet). */
    public static boolean isWindowOpen() {
        return windowOpen;
    }

    /**
     * Send-seam gate. Returns true when the packet was taken and will be released at the next tick
     * head. Order is preserved: once anything is held, later action packets (and container clicks, which
     * must stay ordered against slot changes) queue behind it.
     */
    public static boolean hold(Packet<?> packet) {
        if (flushing) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || !mc.isOnThread()) {
            return false;
        }
        return hold(packet, kindOf(packet));
    }

    /** {@link #hold(Packet)} without the client checks: the window/queue rule itself. */
    static boolean hold(Object packet, Kind kind) {
        if (flushing || kind == Kind.NONE) {
            return false;
        }
        boolean queued = !held.isEmpty();
        if (kind == Kind.CLICK && !queued) {
            return false;
        }
        if (windowOpen && !queued) {
            recordLive(kind);
            return false;
        }
        // The held batch is released ahead of the next movement packet, so it counts as an action in
        // that interval: a later hotbar change must wait for the interval after it.
        recordAction(kind);
        held.add(new Held(packet, kind));
        return true;
    }

    private static void flushHeld(MinecraftClient mc) {
        if (held.isEmpty()) {
            return;
        }
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null || mc.player == null || mc.world == null) {
            held.clear();
            return;
        }
        flush(packet -> handler.sendPacket((Packet<?>) packet));
    }

    /**
     * Releases held packets in order at the tick head. Everything released here is part of THIS
     * interval: an attack or use sent now closes it for a hotbar change exactly like a live one, so the
     * queued restore ({@link #applyDeferredSlot}) and any module that checks {@link #canSwitchSlot()} /
     * {@link #canAttack()} later in this tick see it. Without that, an attack held behind earlier packets
     * (released here, after the previous movement packet cleared the flags) left the interval looking
     * empty, and the swap-back that followed went out right behind the attack: slot, attack, slot.
     */
    static void flush(java.util.function.Consumer<Object> send) {
        if (held.isEmpty()) {
            return;
        }
        flushing = true;
        try {
            boolean actedThisInterval = false;
            Held next;
            while ((next = held.peek()) != null) {
                boolean slot = next.kind() == Kind.SLOT;
                if (slot && actedThisInterval) {
                    // Slot after an attack/use in one movement interval is not a vanilla sequence:
                    // the rest waits, in order, for the next tick head.
                    break;
                }
                held.poll();
                send.accept(next.packet());
                recordAction(next.kind());
                if (next.kind() == Kind.ATTACK || next.kind() == Kind.USE || next.kind() == Kind.DIG) {
                    actedThisInterval = true;
                }
            }
        } finally {
            flushing = false;
        }
    }

    /** Tick head, before module work: reopens the window, then releases held packets and queued restores. */
    public static void onTickHead() {
        windowOpen = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return;
        }
        flushHeld(mc);
        applyDeferredSlot(mc);
    }

    /** Test seam for {@link #onTickHead()}: the window opens and held packets go to {@code send}. */
    static void onTickHead(java.util.function.Consumer<Object> send) {
        windowOpen = true;
        flush(send);
    }

    private record Held(Object packet, Kind kind) {
    }

    /**
     * Queues a slot restore for the start of the next interval. Earlier requests are kept, in order; an
     * identical pending request is not queued twice.
     */
    public static void deferSlot(int slot, int expectedHeld) {
        if (slot < 0 || slot > 8) {
            return;
        }
        for (int[] entry : deferredSlots) {
            if (entry[0] == slot && entry[1] == expectedHeld) {
                return;
            }
        }
        if (deferredSlots.size() >= MAX_DEFERRED) {
            deferredSlots.pollFirst();
        }
        deferredSlots.addLast(new int[] {slot, expectedHeld});
    }

    /** A slot restore is queued for the next interval head and has not been applied yet. */
    public static boolean hasDeferredSlot() {
        return !deferredSlots.isEmpty();
    }

    /** Drops every queued restore, e.g. because a new sequence now owns the hotbar. */
    public static void cancelDeferredSlot() {
        deferredSlots.clear();
    }

    /** Called when the movement packet has been sent: the window closes and a new interval starts. */
    public static void onMovementPacketSent() {
        interval++;
        attacked = false;
        used = false;
        windowOpen = false;
    }

    private static void applyDeferredSlot(MinecraftClient mc) {
        if (deferredSlots.isEmpty() || !canSwitchSlot() || !held.isEmpty()) {
            // A just-released action is still in this interval: the restore must follow the next one.
            return;
        }
        if (mc.player == null || mc.world == null) {
            deferredSlots.clear();
            return;
        }
        // One hotbar change per interval: entries already satisfied or made stale are dropped, the first
        // applicable one is applied and the rest wait for the next interval head.
        int[] entry;
        while ((entry = deferredSlots.pollFirst()) != null) {
            int current = mc.player.getInventory().getSelectedSlot();
            if (current == entry[0] || current != entry[1]) {
                continue;
            }
            SlotSync.selectNow(mc, entry[0]);
            return;
        }
    }

    /** World change / disconnect: nothing queued may leak into the next session. */
    public static void reset() {
        attacked = false;
        used = false;
        deferredSlots.clear();
        windowOpen = true;
        held.clear();
        flushing = false;
    }
}
