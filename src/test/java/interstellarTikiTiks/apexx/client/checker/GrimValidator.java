package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier 1: Grim 2.0 Verification Engine
 * Implements authoritative packet-order finite state machines, reach boundaries,
 * and bad-packet invariant checks reversed directly from checkers/01-grim.
 */
public class GrimValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // Per-tick state tracking
    private boolean tickMovementSeen = false;
    private boolean tickSwingSeen = false;
    private boolean tickInteractAtSeen = false;
    private boolean tickAttackSeen = false;
    private boolean tickUseSeen = false;
    private boolean tickPlaceSeen = false;
    private boolean tickDigSeen = false;

    // PacketOrderB (grim PacketOrderB, 1.9+ clients): vanilla sends INTERACT(attack) -> ANIMATION. An attack
    // needs an arm swing since the previous attack, and its swing must follow it before any other
    // non-async packet (slot, movement, tick end, ...).
    private boolean swingSinceLastAttack = true;
    private boolean attackAwaitingSwing = false;

    // Cross-tick slot tracking
    private int currentSlot = 0;
    private boolean slotInitialized = false;

    @Override
    public int tier() {
        return 1;
    }

    @Override
    public String name() {
        return "Grim 2.0";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {
        tickMovementSeen = false;
        tickSwingSeen = false;
        tickInteractAtSeen = false;
        tickAttackSeen = false;
        tickUseSeen = false;
        tickPlaceSeen = false;
        tickDigSeen = false;
    }

    @Override
    public void onAction(WireFrame frame) {
        // BadPacketsD: Pitch clamp [-90.0, 90.0]
        if (frame.pitch() < -90.0f || frame.pitch() > 90.0f) {
            violations.add(new AcViolation(
                    1, "Grim 2.0", "BadPacketsD",
                    "Invalid pitch angle outside [-90.0, 90.0]: " + frame.pitch(),
                    frame.tick(), frame.nanoTime(), frame.pitch()
            ));
        }

        if (attackAwaitingSwing && frame.action() != WireAction.SWING && frame.action() != WireAction.ATTACK) {
            flagPostAttack(frame.tick(), frame.nanoTime());
        }

        switch (frame.action()) {
            case SLOT_CHANGE -> {
                // BadPacketsY: Slot index out of bounds [0..8]
                if (frame.slot() < 0 || frame.slot() > 8) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "BadPacketsY",
                            "Out of bounds hotbar slot ID: " + frame.slot(),
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                // BadPacketsA: Duplicate slot ID
                if (slotInitialized && frame.slot() == currentSlot) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "BadPacketsA",
                            "Duplicate hotbar slot packet for already-selected slot: " + frame.slot(),
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                // PacketOrderE: Slot change during conflicting action or after action in movement interval
                if (tickAttackSeen || tickUseSeen || tickPlaceSeen || tickDigSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderE",
                            "Slot change packet emitted after interaction action in the same movement interval",
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                // PacketOrderO: Slot change after movement packet
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "Slot change packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), frame.slot()
                    ));
                }

                currentSlot = frame.slot();
                slotInitialized = true;
            }

            case SWING -> {
                tickSwingSeen = true;
                swingSinceLastAttack = true;
                attackAwaitingSwing = false;
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "Arm swing packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case INTERACT_AT -> {
                tickInteractAtSeen = true;
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "INTERACT_AT packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case INTERACT -> {
                // PacketOrderC: INTERACT_AT must strictly precede INTERACT
                if (!tickInteractAtSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderC",
                            "INTERACT packet emitted without preceding INTERACT_AT packet",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "INTERACT packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case ATTACK -> {
                tickAttackSeen = true;

                // PacketOrderB pre-attack (grim PacketOrderB#onAttack): no arm swing since the previous attack.
                // The swing that belongs to this attack is checked when the next packet arrives.
                if (!swingSinceLastAttack) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderB",
                            "Attack packet emitted without an arm swing since the previous attack in tick " + frame.tick(),
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
                swingSinceLastAttack = false;
                attackAwaitingSwing = true;

                // PacketOrderO: Interaction after movement packet
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "Attack packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }

                // Reach check: 3.0 blocks max base reach
                if (frame.targetDistance() > 3.001) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "Reach",
                            String.format("Attack reach %.4fm exceeds maximum 3.0m reach threshold", frame.targetDistance()),
                            frame.tick(), frame.nanoTime(), frame.targetDistance()
                    ));
                }
            }

            case USE_ITEM -> {
                tickUseSeen = true;
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "UseItem packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case BLOCK_PLACE -> {
                tickPlaceSeen = true;
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "BlockPlace packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case BLOCK_DIG_START, BLOCK_DIG_STOP, BLOCK_DIG_ABORT -> {
                tickDigSeen = true;
                if (tickMovementSeen) {
                    violations.add(new AcViolation(
                            1, "Grim 2.0", "PacketOrderO",
                            "Digging packet emitted after movement packet before tick end",
                            frame.tick(), frame.nanoTime(), 0.0
                    ));
                }
            }

            case MOVEMENT -> {
                tickMovementSeen = true;
            }

            default -> {}
        }
    }

    @Override
    public void onTickEnd(int tick) {
        // CLIENT_TICK_END is a non-async packet as well: an attack still awaiting its swing is flagged here.
        if (attackAwaitingSwing) {
            flagPostAttack(tick, 0L);
        }
    }

    /** PacketOrderB post-attack: a 1.9+ client sends the arm swing right after the attack packet. */
    private void flagPostAttack(int tick, long nanoTime) {
        attackAwaitingSwing = false;
        violations.add(new AcViolation(
                1, "Grim 2.0", "PacketOrderB",
                "Attack packet not followed by its arm swing before the next packet (1.9+ order is attack -> swing)",
                tick, nanoTime, 0.0
        ));
    }

    @Override
    public List<AcViolation> getViolations() {
        return violations;
    }

    @Override
    public void reset() {
        violations.clear();
        swingSinceLastAttack = true;
        attackAwaitingSwing = false;
        slotInitialized = false;
        currentSlot = 0;
    }
}
