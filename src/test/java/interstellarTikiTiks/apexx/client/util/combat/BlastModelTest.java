package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blast damage formula and the crater ray walk, on synthetic terrain. */
class BlastModelTest {
    /** Stone below y = 0 (resistance 6), air above, an obsidian floor (1200) at y = -8. */
    private static final BlastModel.Terrain TERRAIN = new BlastModel.Terrain() {
        @Override
        public float resistance(BlockPos pos) {
            if (pos.getY() >= 0) return Float.NaN;
            return pos.getY() <= -8 ? 1200.0f : 6.0f;
        }

        @Override
        public boolean outOfBounds(BlockPos pos) {
            return false;
        }
    };

    @Test
    void anchorDamageMatchesTheVanillaFormulaAtKnownPoints() {
        // Point blank, fully exposed: impact 1 -> (1 + 1) / 2 * 7 * 10 + 1 = 71.
        assertEquals(71.0, BlastModel.rawDamage(0.0, 1.0, BlastModel.ANCHOR_POWER), 1e-9);
        // Half the radius, fully exposed: impact 0.5 -> (0.25 + 0.5) / 2 * 70 + 1 = 27.25.
        assertEquals(27.25, BlastModel.rawDamage(0.5, 1.0, BlastModel.ANCHOR_POWER), 1e-9);
        // At the radius edge nothing is left but the +1.
        assertEquals(1.0, BlastModel.rawDamage(1.0, 1.0, BlastModel.ANCHOR_POWER), 1e-9);
    }

    @Test
    void damageFallsWithDistanceAndRisesWithExposure() {
        double previous = Double.MAX_VALUE;
        for (int i = 0; i <= 20; i++) {
            double d = BlastModel.rawDamage(i / 20.0, 1.0, 5.0f);
            assertTrue(d <= previous);
            previous = d;
        }
        previous = 0.0;
        for (int i = 0; i <= 20; i++) {
            double d = BlastModel.rawDamage(0.3, i / 20.0, 5.0f);
            assertTrue(d >= previous);
            previous = d;
        }
    }

    /** Strength factor of the weakest and strongest ray the real blast can roll. */
    private static final float WEAKEST = 0.7f;
    private static final float STRONGEST = 1.3f;

    private static Set<BlockPos> carve(float factor) {
        Vec3d centre = new Vec3d(0.5, 1.5, 0.5);
        return BlastModel.carve(TERRAIN, centre, BlockPos.ofFloored(centre), 5.0f, () -> factor);
    }

    @Test
    void theSurelyRemovedSetIsInsideThePossibleOneAndBothAreBounded() {
        Set<BlockPos> sure = carve(WEAKEST);
        Set<BlockPos> possible = carve(STRONGEST);
        assertTrue(possible.containsAll(sure));
        assertTrue(possible.size() > sure.size());
        assertFalse(sure.isEmpty());
        for (BlockPos pos : possible) {
            assertTrue(pos.getY() < 0, "air is never listed");
            assertTrue(pos.getY() > -8, "the obsidian floor is never removed");
            // A ray of strength 5 * 1.3 loses 0.225 per 0.3-block step, so it reaches at most 8.67 blocks.
            double distance = Math.sqrt(pos.getSquaredDistance(0.5, 1.5, 0.5));
            assertTrue(distance <= 5.0 * STRONGEST / 0.225 * 0.3 + 1.0, "within the blast reach: " + pos + " at " + distance);
        }
    }

    @Test
    void craterGrowsWithPowerAndIsMirrorSymmetric() {
        Vec3d centre = new Vec3d(0.5, 1.5, 0.5);
        int last = 0;
        for (float power = 2.0f; power <= 8.0f; power += 1.0f) {
            Set<BlockPos> crater = BlastModel.carve(TERRAIN, centre, BlockPos.ofFloored(centre), power, () -> 1.0f);
            assertTrue(crater.size() >= last, "power " + power);
            last = crater.size();
        }
        Set<BlockPos> crater = carve(1.0f);
        assertTrue(carve(WEAKEST).size() <= crater.size() && crater.size() <= carve(STRONGEST).size());
        for (BlockPos pos : crater) {
            assertTrue(crater.contains(new BlockPos(-pos.getX(), pos.getY(), -pos.getZ())), "mirror of " + pos);
        }
    }

    @Test
    void monteCarloOfTheRealPerRayRollStaysBetweenTheBounds() {
        // The real blast rolls every ray at 0.7-1.3 of the power. Whatever the rolls: every block in
        // `sure` is removed, nothing outside `possible` is, and the blocks between come and go.
        Set<BlockPos> sure = carve(WEAKEST);
        Set<BlockPos> possible = carve(STRONGEST);
        Map<BlockPos, Integer> removed = new HashMap<>();
        int trials = 400;
        Random random = new Random(31);
        double sizeSum = 0.0;
        for (int t = 0; t < trials; t++) {
            Vec3d centre = new Vec3d(0.5, 1.5, 0.5);
            Set<BlockPos> crater = BlastModel.carve(TERRAIN, centre, BlockPos.ofFloored(centre), 5.0f,
                    () -> WEAKEST + random.nextFloat() * (STRONGEST - WEAKEST));
            assertTrue(crater.containsAll(sure), "trial " + t + " lost a surely removed block");
            assertTrue(possible.containsAll(crater), "trial " + t + " removed a block outside the possible set");
            sizeSum += crater.size();
            for (BlockPos pos : crater) removed.merge(pos, 1, Integer::sum);
        }
        double meanSize = sizeSum / trials;
        assertTrue(meanSize > sure.size() && meanSize < possible.size(), "mean crater " + meanSize);
        // The uncertain band is real: some blocks go in some rolls and not in others.
        int sometimes = 0;
        for (BlockPos pos : possible) {
            if (sure.contains(pos)) continue;
            double rate = removed.getOrDefault(pos, 0) / (double) trials;
            if (rate > 0.05 && rate < 0.95) sometimes++;
        }
        assertTrue(sometimes > 5, "blocks removed only sometimes: " + sometimes);
    }
}
