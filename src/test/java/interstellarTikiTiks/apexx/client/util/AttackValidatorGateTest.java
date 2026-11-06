package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttackValidatorGateTest {
    @Test
    void onlyBlatantOwnersSkipTheWireRayGate() {
        assertTrue(AttackValidator.requiresWireRay(false));
        assertFalse(AttackValidator.requiresWireRay(true));
    }
}
