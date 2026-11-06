package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttackCooldownMathTest {

    @Test
    void fractionIsClamped() {
        assertEquals(0.0f, AttackCooldownMath.ringFraction(-0.5f));
        assertEquals(0.0f, AttackCooldownMath.ringFraction(Float.NaN));
        assertEquals(0.5f, AttackCooldownMath.ringFraction(0.5f));
        assertEquals(1.0f, AttackCooldownMath.ringFraction(1.7f));
    }

    @Test
    void stagesSwitchAtHalfSwingAndFull() {
        assertEquals(AttackCooldownMath.STAGE_CHARGING, AttackCooldownMath.stage(0.0f));
        assertEquals(AttackCooldownMath.STAGE_CHARGING, AttackCooldownMath.stage(0.847f));
        assertEquals(AttackCooldownMath.STAGE_HALF_SWING, AttackCooldownMath.stage(0.848f));
        assertEquals(AttackCooldownMath.STAGE_HALF_SWING, AttackCooldownMath.stage(0.99f));
        assertEquals(AttackCooldownMath.STAGE_FULL, AttackCooldownMath.stage(0.9995f));
        assertEquals(AttackCooldownMath.STAGE_FULL, AttackCooldownMath.stage(1.0f));
    }

    @Test
    void segmentsRoundToNearest() {
        assertEquals(0, AttackCooldownMath.filledSegments(0.0f, 48));
        assertEquals(24, AttackCooldownMath.filledSegments(0.5f, 48));
        assertEquals(48, AttackCooldownMath.filledSegments(2.0f, 48));
        assertEquals(0, AttackCooldownMath.filledSegments(0.5f, 0));
    }

    @Test
    void lerpArgbEndpointsAndMidpoint() {
        assertEquals(0xFF000000, AttackCooldownMath.lerpArgb(0xFF000000, 0xFFFFFFFF, 0.0f));
        assertEquals(0xFFFFFFFF, AttackCooldownMath.lerpArgb(0xFF000000, 0xFFFFFFFF, 1.0f));
        assertEquals(0xFF808080, AttackCooldownMath.lerpArgb(0xFF000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0x00000000, AttackCooldownMath.lerpArgb(0x00000000, 0xFFFFFFFF, -3.0f));
    }
}
