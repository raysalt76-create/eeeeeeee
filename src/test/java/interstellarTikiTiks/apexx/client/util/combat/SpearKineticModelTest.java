package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpearKineticModelTest {

    private static final Vec3d EYE = new Vec3d(0, 10, 0);
    private static final Vec3d DOWN = new Vec3d(0, -1, 0);

    private static Box boxAt(double distanceBelowEye) {
        double top = 10 - distanceBelowEye;
        return new Box(-0.3, top - 1.8, -0.3, 0.3, top, 0.3);
    }

    @Test
    void rayReachGrowsWithMovementAlongTheLook() {
        assertEquals(4.5, SpearKineticModel.rayLength(4.5, Vec3d.ZERO, DOWN), 1e-9);
        assertEquals(6.0, SpearKineticModel.rayLength(4.5, new Vec3d(0, -1.5, 0), DOWN), 1e-9);
        assertEquals(4.5, SpearKineticModel.rayLength(4.5, new Vec3d(0, 1.5, 0), DOWN), 1e-9,
                "movement against the look never shortens the ray");
    }

    @Test
    void serverRayTouchesWhereTheCrosshairCannotYet() {
        Box far = boxAt(5.6);
        assertFalse(SpearKineticModel.touches(EYE, DOWN, Vec3d.ZERO, 2.0, 4.5, 0.125, far));
        assertTrue(SpearKineticModel.touches(EYE, DOWN, new Vec3d(0, -1.5, 0), 2.0, 4.5, 0.125, far),
                "at 30 b/s the ray reaches 6.0 blocks");
    }

    @Test
    void minimumRangeIsRespected() {
        Box tooClose = new Box(-0.3, 8.5, -0.3, 0.3, 9.0, 0.3);
        assertFalse(SpearKineticModel.touches(EYE, DOWN, Vec3d.ZERO, 2.0, 4.5, 0.0, tooClose));
        assertNotNull(SpearKineticModel.contactPoint(EYE, DOWN, Vec3d.ZERO, 2.0, 4.5, 0.125, boxAt(3.0)));
    }

    @Test
    void speedsAndDamageFollowTheServerFormulas() {
        assertEquals(24.0, SpearKineticModel.speedAlong(DOWN, new Vec3d(0, -1.2, 0)), 1e-9);
        assertEquals(0.0, SpearKineticModel.relativeSpeed(3.0, 9.0), 1e-9);
        assertEquals(29.0, SpearKineticModel.spearDamage(23.9, 1.2), 1e-9, "netherite, 12 block fall");
        assertEquals(1.0, SpearKineticModel.spearDamage(0.0, 1.2), 1e-9);
    }

    @Test
    void smashBonusIsPiecewiseAndAddsDensityPerBlock() {
        assertEquals(12.0, SpearKineticModel.smashBonus(3.0, 0), 1e-9);
        assertEquals(22.0, SpearKineticModel.smashBonus(8.0, 0), 1e-9);
        assertEquals(30.0, SpearKineticModel.smashBonus(16.0, 0), 1e-9);
        assertEquals(30.0 + 0.5 * 5 * 16.0, SpearKineticModel.smashBonus(16.0, 5), 1e-9);
    }

    @Test
    void netGainIsTheExcessOverTheSpearHit() {
        double mace = SpearKineticModel.swappedMaceDamage(5.0, 1.0, 12.0, 0);
        assertEquals(31.0, mace, 1e-9);
        assertEquals(2.0, SpearKineticModel.netGainInsideInvulnerability(mace, 29.0), 1e-9);
        assertEquals(0.0, SpearKineticModel.netGainInsideInvulnerability(20.0, 29.0), 1e-9);
    }

    @Test
    void strikeEnvelopeGrowsWithClosingSpeed() {
        assertTrue(SpearKineticModel.inStrikeEnvelope(5.0, 4.5, 0.2, 0.0));
        assertFalse(SpearKineticModel.inStrikeEnvelope(8.0, 4.5, 0.2, 0.0));
        assertTrue(SpearKineticModel.inStrikeEnvelope(8.0, 4.5, 0.2, 1.5), "30 b/s reaches two ticks further");
        assertFalse(SpearKineticModel.inStrikeEnvelope(8.0, 4.5, 0.2, -1.5), "moving away never widens it");
    }

    @Test
    void ticksToFullChargeRoundsUp() {
        assertEquals(0, SpearKineticModel.ticksToFullCharge(1.0, 0.05));
        assertEquals(10, SpearKineticModel.ticksToFullCharge(0.5, 0.05));
        assertEquals(11, SpearKineticModel.ticksToFullCharge(0.46, 0.05));
        assertEquals(0, SpearKineticModel.ticksToFullCharge(0.2, 0.0));
    }
}
