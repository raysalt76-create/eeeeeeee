package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SafeAnchor's cycle order, tick count and blast decisions, and the shield ranking, on pure geometry. */
class SafeAnchorPlanningTest {

    @Test
    void noEnemyInRangeStillCycles() {
        // Nobody within Target Range: neither Out-Damage Only nor a Min Target Damage of 0 judges anything,
        // so the cycle charges whenever the blast is safe for you.
        assertNull(AnchorCycle.veto(3.0, 20.0, 4.0, true, false, null, null, 0.0, false));
        assertNull(AnchorCycle.veto(3.0, 20.0, 4.0, true, false, null, null, 0.0, true));
        assertEquals(AnchorCycle.Step.CHARGE, AnchorCycle.withoutShield(
                AnchorCycle.veto(3.0, 20.0, 4.0, true, false, null, null, 0.0, true), false));
        // Only a Min Target Damage above 0 asks for an enemy.
        assertNotNull(AnchorCycle.veto(3.0, 20.0, 4.0, true, false, null, null, 6.0, false));
    }

    @Test
    void noShieldCellButSafeChargesAndExplodes() {
        String veto = AnchorCycle.veto(5.0, 20.0, 4.0, false, false, null, null, 0.0, false);
        assertNull(veto);
        assertEquals(AnchorCycle.Step.CHARGE, AnchorCycle.withoutShield(veto, false));
        // An anchor someone charged already goes straight to the explosion.
        assertEquals(AnchorCycle.Step.EXPLODE, AnchorCycle.withoutShield(veto, true));
    }

    @Test
    void unsafeBlastRefusesWithTheReason() {
        String veto = AnchorCycle.veto(18.0, 20.0, 4.0, false, false, null, null, 0.0, false);
        assertNotNull(veto);
        assertTrue(veto.contains("Health Buffer"), veto);
        assertNull(AnchorCycle.withoutShield(veto, false));
        assertNull(AnchorCycle.withoutShield(veto, true));
        // Allow Totem Pop covers the pop only with a totem in the offhand or in the explode slot.
        assertNull(AnchorCycle.veto(18.0, 20.0, 4.0, true, true, null, null, 0.0, false));
        assertNotNull(AnchorCycle.veto(18.0, 20.0, 4.0, true, false, null, null, 0.0, false));
        // Exactly the buffer left is not enough; a hair more is.
        assertTrue(AnchorCycle.selfSafe(15.9, 20.0, 4.0, false, false));
        assertTrue(!AnchorCycle.selfSafe(16.0, 20.0, 4.0, false, false));
        // Out-Damage Only (opt-in) refuses an enemy the blast hurts less than you, and names them.
        String outDamage = AnchorCycle.veto(5.0, 20.0, 4.0, false, false, 2.0, "Enemy", 0.0, true);
        assertNotNull(outDamage);
        assertTrue(outDamage.contains("Enemy"), outDamage);
        assertNull(AnchorCycle.veto(5.0, 20.0, 4.0, false, false, 2.0, "Enemy", 0.0, false));
        assertNull(AnchorCycle.veto(5.0, 20.0, 4.0, false, false, 9.0, "Enemy", 0.0, true));
    }

    @Test
    void shieldGoesFirstAndTheChargeRightBeforeTheExplosion() {
        assertEquals(List.of(AnchorCycle.Step.SHIELD, AnchorCycle.Step.CHARGE, AnchorCycle.Step.EXPLODE),
                AnchorCycle.order(false, true, false));
        assertEquals(List.of(AnchorCycle.Step.PLACE, AnchorCycle.Step.SHIELD, AnchorCycle.Step.CHARGE,
                AnchorCycle.Step.EXPLODE), AnchorCycle.order(true, true, false));
        assertEquals(List.of(AnchorCycle.Step.SHIELD, AnchorCycle.Step.EXPLODE), AnchorCycle.order(false, true, true));
        assertEquals(List.of(AnchorCycle.Step.CHARGE, AnchorCycle.Step.EXPLODE), AnchorCycle.order(false, false, false));
    }

    @Test
    void sameTickTurnsMakeEveryStepOneTick() {
        List<AnchorCycle.Step> safe = AnchorCycle.order(false, true, false);
        assertEquals(3, AnchorCycle.ticks(safe, true));
        // Without same-tick turns only the shield-to-anchor turn costs a tick: charge and explode share a face.
        assertEquals(4, AnchorCycle.ticks(safe, false));
        // The old order (charge, shield, explode) turned twice.
        List<AnchorCycle.Step> old = List.of(AnchorCycle.Step.CHARGE, AnchorCycle.Step.SHIELD, AnchorCycle.Step.EXPLODE);
        assertEquals(5, AnchorCycle.ticks(old, false));
        assertEquals(2, AnchorCycle.ticks(AnchorCycle.order(false, false, false), false));
    }

    @Test
    void exposureLeftRanksAheadOfTurn() {
        // 10 % less exposure beats a much smaller turn.
        assertTrue(AnchorShieldPlanner.rankScore(0.2, 60.0) < AnchorShieldPlanner.rankScore(0.3, 0.0));
        // Equal exposure: the smaller turn wins.
        assertTrue(AnchorShieldPlanner.rankScore(0.2, 10.0) < AnchorShieldPlanner.rankScore(0.2, 40.0));
    }

    @Test
    void aCoverBetweenTheBlastAndTheBodyCutsOffItsSamples() {
        Vec3d blast = new Vec3d(0.5, 0.5, 0.5);
        Box body = new Box(2.7, 0.0, 0.2, 3.3, 1.8, 0.8);
        List<Vec3d> samples = AutoCartCover.exposureSamples(body);
        // A full block right between them, at x = 1..2: every sample's ray to the blast crosses it.
        double between = AnchorShieldPlanner.exposureWith(samples, samples.size(), blast, new Box(new BlockPos(1, 0, 0)));
        // Off to the side it shields nothing.
        double aside = AnchorShieldPlanner.exposureWith(samples, samples.size(), blast, new Box(new BlockPos(1, 0, 3)));
        // Above the anchor it shields only the upper rays, if any.
        double above = AnchorShieldPlanner.exposureWith(samples, samples.size(), blast, new Box(new BlockPos(1, 1, 0)));
        assertEquals(1.0, aside, 1e-9);
        assertTrue(between < 0.35, "between leaves " + between);
        assertTrue(between < above && above <= 1.0);
    }

    @Test
    void theLineWalkFindsTheCellsBetween() {
        Set<BlockPos> cells = new LinkedHashSet<>();
        AnchorShieldPlanner.walk(new Vec3d(0.5, 0.5, 0.5), new Vec3d(3.5, 0.5, 0.5), cells);
        assertTrue(cells.contains(new BlockPos(1, 0, 0)));
        assertTrue(cells.contains(new BlockPos(2, 0, 0)));
    }
}
