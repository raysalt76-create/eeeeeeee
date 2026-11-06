package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the send-seam gate ({@link TickActionOrder#hold(Object, TickActionOrder.Kind)}) the way the client does:
 * tick head (window opens, held packets flush), module work, the movement packet. Every packet that is not
 * taken by the gate is what the wire receives, in that order. The ShieldBreaker sequences below are the
 * calls {@code AutoShieldBreak} makes: select axe, attack, swing, then the swap-back through
 * {@code SlotSync.restore} (selected when {@link TickActionOrder#canSwitchSlot()} allows it, else queued
 * for the next tick head).
 */
class TickActionOrderPacketOrderTest {
    private final List<String> wire = new ArrayList<>();
    private boolean restoreQueued;
    private String queuedRestore;

    @AfterEach
    void cleanUp() {
        TickActionOrder.reset();
        ExecutionMode.set(ExecutionMode.LEGIT);
    }

    private void send(String packet, TickActionOrder.Kind kind) {
        if (!TickActionOrder.hold(packet, kind)) {
            wire.add(packet);
        }
    }

    private void tickHead() {
        TickActionOrder.onTickHead(packet -> wire.add((String) packet));
        // TickActionOrder.applyDeferredSlot: the queued restore goes out once the interval allows a switch.
        if (restoreQueued && TickActionOrder.canSwitchSlot() && TickActionOrder.heldCount() == 0) {
            restoreQueued = false;
            send(queuedRestore, TickActionOrder.Kind.SLOT);
        }
    }

    private void movement() {
        wire.add("MOVE");
        TickActionOrder.onMovementPacketSent();
    }

    /** SlotSync.restore: now when the interval allows a hotbar change, else at the next tick head. */
    private void restore(String slotPacket) {
        if (TickActionOrder.canSwitchSlot()) {
            send(slotPacket, TickActionOrder.Kind.SLOT);
        } else {
            restoreQueued = true;
            queuedRestore = slotPacket;
        }
    }

    /** AutoShieldBreak.dispatchInTick + axeHit, after the fix: slot, attack, then swing. */
    private void shieldBreakerHit() {
        assertTrue(TickActionOrder.canSwitchSlot());
        send("SLOT axe", TickActionOrder.Kind.SLOT);
        assertTrue(TickActionOrder.canAttack(), "the hit still belongs to the swap's interval");
        send("ATTACK", TickActionOrder.Kind.ATTACK);
        send("SWING", TickActionOrder.Kind.SWING);
    }

    /** No hotbar packet after an attack or use inside one movement interval (Grim PacketOrderE). */
    private static void assertVanillaIntervals(List<String> wire) {
        boolean acted = false;
        for (String packet : wire) {
            if (packet.equals("MOVE")) {
                acted = false;
            } else if (packet.startsWith("SLOT")) {
                assertFalse(acted, "slot change after an attack in one movement interval: " + wire);
            } else if (packet.startsWith("ATTACK") || packet.startsWith("USE")) {
                acted = true;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {ExecutionMode.LEGIT, ExecutionMode.DIRECT})
    void shieldBreakIsSlotAttackSwingWithTheSwapBackInTheNextInterval(String mode) {
        ExecutionMode.set(mode);
        tickHead();
        shieldBreakerHit();
        restore("SLOT sword"); // Swap-Back Delay 0: still never inside the hit's interval
        movement();
        tickHead();
        movement();

        assertEquals(List.of("SLOT axe", "ATTACK", "SWING", "MOVE", "SLOT sword", "MOVE"), wire);
        assertVanillaIntervals(wire);
    }

    @ParameterizedTest
    @ValueSource(strings = {ExecutionMode.LEGIT, ExecutionMode.DIRECT})
    void anAttackReleasedAtTheTickHeadClosesThatIntervalForTheSwapBack(String mode) {
        ExecutionMode.set(mode);
        // A render frame after the movement packet left another module's hit and its swap-back held.
        tickHead();
        movement();
        send("ATTACK other", TickActionOrder.Kind.ATTACK);
        send("SWING other", TickActionOrder.Kind.SWING);
        send("SLOT other-back", TickActionOrder.Kind.SLOT);

        // Tick N: the attack and swing are released, the slot after them waits for the next head.
        tickHead();
        assertEquals(1, TickActionOrder.heldCount(), "the slot after the attack stays held");
        assertFalse(TickActionOrder.canSwitchSlot(), "ShieldBreaker does not swap in an interval that already attacked");
        // The player's own hotbar key and click in this tick are not gated: they queue behind the held slot.
        send("SLOT axe", TickActionOrder.Kind.SLOT);
        send("ATTACK", TickActionOrder.Kind.ATTACK);
        send("SWING", TickActionOrder.Kind.SWING);
        movement();

        // Tick N+1: the held swap and hit go out first. The previous movement packet cleared the flags, so
        // only the release itself can tell the swap-back that an attack already left in this interval.
        tickHead();
        assertFalse(TickActionOrder.canSwitchSlot(), "a released attack closes the interval for a hotbar change");
        assertFalse(TickActionOrder.canAttack(), "and for a second attack");
        restore("SLOT sword");
        movement();
        tickHead();
        movement();

        assertEquals(List.of("MOVE", "ATTACK other", "SWING other", "MOVE",
                "SLOT other-back", "SLOT axe", "ATTACK", "SWING", "MOVE",
                "SLOT sword", "MOVE"), wire);
        assertVanillaIntervals(wire);
    }

    @Test
    void heldPacketsAreReleasedInOrderAndNeverReordered() {
        tickHead();
        movement();
        // Out of the window: a swap and its hit from a render frame are held together.
        send("SLOT axe", TickActionOrder.Kind.SLOT);
        send("ATTACK", TickActionOrder.Kind.ATTACK);
        send("SWING", TickActionOrder.Kind.SWING);
        assertEquals(List.of("MOVE"), wire, "nothing leaves after the movement packet");
        assertFalse(TickActionOrder.canSwitchSlot(), "a held attack already closes the coming interval");

        tickHead();
        assertEquals(List.of("MOVE", "SLOT axe", "ATTACK", "SWING"), wire);
        assertEquals(0, TickActionOrder.heldCount());
        assertFalse(TickActionOrder.canSwitchSlot());
    }
}
