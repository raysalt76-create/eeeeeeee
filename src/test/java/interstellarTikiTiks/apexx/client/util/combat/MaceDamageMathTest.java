package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaceDamageMathTest {
    private static final double EPS = 1.0e-9;

    @Test
    void noBonusAtOrBelowThreshold() {
        assertEquals(0.0, MaceDamageMath.smashBonus(0.0), EPS);
        assertEquals(0.0, MaceDamageMath.smashBonus(1.5), EPS);
        assertEquals(0.0, MaceDamageMath.smashBonus(1.5, 5), EPS);
        assertFalse(MaceDamageMath.isSmash(1.5, false));
        assertTrue(MaceDamageMath.isSmash(1.6, false));
        assertFalse(MaceDamageMath.isSmash(10.0, true));
    }

    @Test
    void piecewiseCurveMatchesVanilla() {
        assertEquals(8.0, MaceDamageMath.smashBonus(2.0), EPS);    // 4 per block for the first 3
        assertEquals(12.0, MaceDamageMath.smashBonus(3.0), EPS);
        assertEquals(16.0, MaceDamageMath.smashBonus(5.0), EPS);   // 12 + 2 * (5 - 3)
        assertEquals(22.0, MaceDamageMath.smashBonus(8.0), EPS);
        assertEquals(32.0, MaceDamageMath.smashBonus(18.0), EPS);  // 22 + (18 - 8)
    }

    @Test
    void densityAddsHalfPerLevelPerBlock() {
        // Density V at 10 blocks: 24 + 0.5 * 5 * 10 = 49
        assertEquals(49.0, MaceDamageMath.smashBonus(10.0, 5), EPS);
        assertEquals(MaceDamageMath.smashBonus(4.0), MaceDamageMath.smashBonus(4.0, 0), EPS);
    }

    @Test
    void rawHitAppliesCooldownCritThenBonus() {
        // 6 attack, full cooldown, crit: 9 + bonus (bonus is added after the crit multiplier)
        assertEquals(9.0 + 22.0, MaceDamageMath.rawSmash(6.0, 1.0f, true, 8.0, 0), 1.0e-6);
        // Empty cooldown: 6 * 0.2 = 1.2, no crit
        assertEquals(1.2, MaceDamageMath.rawHit(6.0, 0.0f, false, 0.0), 1.0e-6);
        // Cooldown above 1 is clamped
        assertEquals(6.0, MaceDamageMath.rawHit(6.0, 3.0f, false, 0.0), 1.0e-6);
    }

    @Test
    void armorFormulaAndBreach() {
        // Full diamond: 20 armor, 8 toughness, 20 damage -> 20 - 20/4 = 15 -> 0.6 reduction -> 8
        assertEquals(8.0, MaceDamageMath.afterArmor(20.0, 20.0, 8.0, 0), EPS);
        // Breach IV removes 0.6 effectiveness -> no reduction left
        assertEquals(20.0, MaceDamageMath.afterArmor(20.0, 20.0, 8.0, 4), EPS);
        // Minimum effective armor is armor / 5 even for huge hits
        assertEquals(100.0 * (1.0 - 4.0 / 25.0), MaceDamageMath.afterArmor(100.0, 20.0, 0.0, 0), EPS);
        // No armor -> unchanged
        assertEquals(7.5, MaceDamageMath.afterArmor(7.5, 0.0, 0.0, 0), EPS);
        assertEquals(0.0, MaceDamageMath.afterArmor(0.0, 20.0, 8.0, 0), EPS);
    }
}
