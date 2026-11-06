package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TotemDangerTest {

    @Test
    void lethalAgainstHealthPlusAbsorption() {
        assertTrue(TotemDanger.isLethal(10f, 0f, 10f, 0f));
        assertFalse(TotemDanger.isLethal(10f, 4f, 10f, 0f)); // absorption saves us
        assertTrue(TotemDanger.isLethal(10f, 0f, 8f, 2f));   // margin
        assertFalse(TotemDanger.isLethal(10f, 0f, 7.9f, 2f));
    }

    @Test
    void noEstimateOrDeadIsNeverLethal() {
        assertFalse(TotemDanger.isLethal(10f, 0f, 0f, 5f));
        assertFalse(TotemDanger.isLethal(10f, 0f, Float.NaN, 5f));
        assertFalse(TotemDanger.isLethal(0f, 0f, 20f, 0f));
    }

    @Test
    void fallDamageMatchesVanilla() {
        assertEquals(0f, TotemDanger.fallDamage(3.0, 3.0, 1.0));
        assertEquals(1f, TotemDanger.fallDamage(3.5, 3.0, 1.0));
        assertEquals(7f, TotemDanger.fallDamage(10.0, 3.0, 1.0));
        assertEquals(0f, TotemDanger.fallDamage(10.0, 3.0, 0.0));
    }

    @Test
    void blastSumIgnoresInvalid() {
        assertEquals(12f, TotemDanger.sum(5f, 7f, -3f, Float.NaN, Float.POSITIVE_INFINITY));
        assertEquals(0f, TotemDanger.sum());
    }
}
