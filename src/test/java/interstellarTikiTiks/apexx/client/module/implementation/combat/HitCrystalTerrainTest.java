package interstellarTikiTiks.apexx.client.module.implementation.combat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HitCrystalTerrainTest {
    private static Box target(double x, double footY, double z) {
        return new Box(x - 0.3, footY, z - 0.3, x + 0.3, footY + 1.8, z + 0.3);
    }

    @Test
    void flatGroundSelectsClosestClearNeighbour() {
        Box opponent = target(0.7, 64.4, 0.5);
        assertEquals(new BlockPos(1, 64, 0), HitCrystalTerrain.closest(opponent, p -> p.getY() == 64));
    }

    @Test
    void feetMustBeStrictlyAboveBottomAndExplosionNoHigherThanWaist() {
        BlockPos base = new BlockPos(1, 64, 0);
        assertFalse(HitCrystalTerrain.belowTarget(target(0.5, 64, 0.5), base));
        assertFalse(HitCrystalTerrain.belowTarget(target(0.5, 63.9, 0.5), base));
        assertFalse(HitCrystalTerrain.belowTarget(target(0.5, 64.05, 0.5), base));
        assertTrue(HitCrystalTerrain.belowTarget(target(0.5, 64.2, 0.5), base));
    }

    @Test
    void rejectsWholeFootprintIncludingStraddledColumnsAndAirborneTargets() {
        Box opponent = target(0.95, 65.3, 0.5);
        assertFalse(HitCrystalTerrain.belowTarget(opponent, new BlockPos(0, 64, 0)));
        assertFalse(HitCrystalTerrain.belowTarget(opponent, new BlockPos(1, 64, 0)));
        assertTrue(HitCrystalTerrain.belowTarget(opponent, new BlockPos(2, 64, 0)));
    }

    @Test
    void unevenTerrainUsesThreeDimensionalDistanceAndRejectsHeadLevel() {
        BlockPos lower = new BlockPos(1, 61, 0);
        BlockPos closer = new BlockPos(0, 64, 2);
        BlockPos headLevel = new BlockPos(1, 66, 0);
        Set<BlockPos> terrain = Set.of(lower, closer, headLevel);
        assertEquals(closer, HitCrystalTerrain.closest(target(0.5, 64.4, 0.5), terrain::contains));
    }

    @Test
    void blockedUnsupportedOrUnreachableCandidatesAreSkippedAndNoSolutionReturnsNull() {
        BlockPos onlyReachable = new BlockPos(2, 64, 1);
        assertEquals(onlyReachable, HitCrystalTerrain.closest(target(0.5, 64.4, 0.5), onlyReachable::equals));
        assertNull(HitCrystalTerrain.closest(target(0.5, 64.4, 0.5), p -> false));
    }

    @Test
    void scanRemapsWhenOpponentMovesAndIncludesOuterPatchAndLowerLayers() {
        Set<BlockPos> seen = new HashSet<>();
        HitCrystalTerrain.closest(target(10.5, 70.4, -4.5), p -> { seen.add(p); return false; });
        assertTrue(seen.contains(new BlockPos(13, 67, -2)));
        assertTrue(seen.contains(new BlockPos(7, 70, -8)));
        assertTrue(seen.stream().allMatch(p -> p.getX() >= 7 && p.getX() <= 13));
        assertTrue(seen.stream().noneMatch(p -> p.getX() == 10 && p.getZ() == -5));
        assertFalse(seen.contains(new BlockPos(1, 64, 0)));
    }

    @Test
    void canUseLowerGroundAfterVerticalKnockbackAndOuterRingWhenInnerNeighboursAreBlocked() {
        BlockPos lowerGround = new BlockPos(1, 64, 0);
        assertEquals(lowerGround, HitCrystalTerrain.closest(target(0.5, 70.4, 0.5), lowerGround::equals));
        BlockPos outerRing = new BlockPos(3, 64, 0);
        assertEquals(outerRing, HitCrystalTerrain.closest(target(0.5, 64.4, 0.5), outerRing::equals));
    }

    @Test
    void crossingABlockBoundaryReplacesTheClosestAnswer() {
        Set<BlockPos> clear = Set.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        assertEquals(new BlockPos(1, 64, 0), HitCrystalTerrain.closest(target(0.5, 64.4, 0.5), clear::contains));
        assertEquals(new BlockPos(2, 64, 0), HitCrystalTerrain.closest(target(1.5, 64.4, 0.5), clear::contains));
    }

    @Test
    void blastEnvelopeIncludesVerticalDistanceAndExcludesBoundary() {
        BlockPos base = new BlockPos(0, 64, 0);
        assertTrue(HitCrystalTerrain.inBlastRadius(new Vec3d(0.5, 65, 12.49), base));
        assertFalse(HitCrystalTerrain.inBlastRadius(new Vec3d(0.5, 65, 12.5), base));
        assertFalse(HitCrystalTerrain.inBlastRadius(new Vec3d(0.5, 77, 0.5), base));
        assertTrue(HitCrystalTerrain.inBlastRadius(new Vec3d(0.5, 76.9, 0.5), base));
    }
}
