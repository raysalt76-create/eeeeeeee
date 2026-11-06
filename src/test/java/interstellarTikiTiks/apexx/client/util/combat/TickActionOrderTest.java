package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickActionOrderTest {
    @AfterEach
    void cleanUp() {
        TickActionOrder.reset();
    }

    @Test
    void slotChangeIsAllowedBeforeActionsButNotAfterAnAttackOrUse() {
        assertTrue(TickActionOrder.canSwitchSlot());
        TickActionOrder.recordAttack();
        assertFalse(TickActionOrder.canSwitchSlot(), "a switch after an attack is not a vanilla order");
        TickActionOrder.onMovementPacketSent();
        assertTrue(TickActionOrder.canSwitchSlot(), "the next packet interval allows it again");
        TickActionOrder.recordUse();
        assertFalse(TickActionOrder.canSwitchSlot(), "a switch after a use-release is not a vanilla order");
    }
}
