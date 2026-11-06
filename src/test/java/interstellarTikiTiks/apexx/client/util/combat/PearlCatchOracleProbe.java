package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Shooter;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

/**
 * Ceiling probe for the accuracy notes: per order and gap (1-8), a coarse-to-fine look search run on the
 * replay itself ({@link PearlCatchTruth}, both launches rolled), reporting the best rate it reaches. Opt-in:
 * {@code -Ppearlcatch.probe=true} (and {@code -Ppearlcatch.shooter=<index>}) with the sim init script.
 */
class PearlCatchOracleProbe {
    @Test
    void probe() {
        Assumptions.assumeTrue(Boolean.getBoolean("pearlcatch.probe"));
        Shooter s = PearlCatchBaselineSweepTest.shooters().get(Integer.getInteger("pearlcatch.shooter", 0));
        for (float pitch : new float[]{-60, -20, 0, 10, 15, 20, 25, 30, 35, 40, 50, 70, 85}) {
            StringBuilder sb = new StringBuilder(String.format("%s pitch %5.1f:", s.name(), pitch));
            for (int order = 0; order < 2; ++order) {
                boolean chargeFirst = order == 1;
                double bestAll = 0;
                int bestGap = -1;
                for (int gap = 1; gap <= 8; ++gap) {
                    double best = search(s, pitch, gap, chargeFirst);
                    if (best > bestAll) { bestAll = best; bestGap = gap; }
                }
                sb.append(String.format("  %s best %.3f@gap%d", chargeFirst ? "chargeFst" : "pearlFst", bestAll, bestGap));
            }
            System.out.println(sb);
        }
    }

    /** Coarse-to-fine search of the aimed projectile's look maximizing the blind replay rate. */
    static double search(Shooter s, float pitch, int gap, boolean chargeFirst) {
        PearlSimulation.Path path = PearlSimulation.ballistic(s.pearlSpawn(0), s.pearlLaunch(0, pitch, 0), 0).trace(160);
        WindChargeIntercept.Shot seed;
        if (!chargeFirst) {
            seed = WindChargeIntercept.solve(null, null, path, gap, s.charge(gap), 60, WindChargeIntercept.Reach.from(0, pitch), false);
        } else {
            PearlSimulation.Path later = PearlSimulation.ballistic(s.pearlSpawn(gap), s.pearlLaunch(0, pitch, gap), 0).trace(100);
            seed = WindChargeIntercept.solveOlder(null, null, later, gap, s.charge(0), 60, WindChargeIntercept.Reach.from(0, pitch), false);
        }
        if (seed == null) return 0;
        float by = seed.yaw(), bp = seed.pitch();
        double best = rate(s, pitch, gap, chargeFirst, by, bp, 256);
        for (float step : new float[]{0.4f, 0.15f, 0.05f}) {
            boolean moved = true;
            while (moved) {
                moved = false;
                for (int dy = -1; dy <= 1; ++dy) for (int dp = -1; dp <= 1; ++dp) {
                    if (dy == 0 && dp == 0) continue;
                    double r = rate(s, pitch, gap, chargeFirst, by + dy * step, bp + dp * step, 256);
                    if (r > best + 1e-9) { best = r; by += dy * step; bp += dp * step; moved = true; }
                }
            }
        }
        return rate(s, pitch, gap, chargeFirst, by, bp, 2048);
    }

    static double rate(Shooter s, float pitch, int gap, boolean chargeFirst, float cy, float cp, int n) {
        SplittableRandom random = new SplittableRandom(99);
        int hits = 0;
        for (int i = 0; i < n; ++i) {
            int g = PearlCatchTruth.slipped(gap, random);
            if (!chargeFirst) {
                Vec3d pv = s.pearlLaunch(0, pitch, 0).add(PearlCatchTruth.roll(random).multiply(1.5));
                Vec3d cv = s.chargeLaunch(cy, cp, gap).add(PearlCatchTruth.roll(random).multiply(1.5));
                if (PearlCatchTruth.catches(s.pearlSpawn(0), pv, ThrowPhysics.windChargeSpawn(s.feetIn(gap), 1.62), cv, g, false)) ++hits;
            } else {
                Vec3d cv = s.chargeLaunch(cy, cp, 0).add(PearlCatchTruth.roll(random).multiply(1.5));
                Vec3d pv = s.pearlLaunch(0, pitch, gap).add(PearlCatchTruth.roll(random).multiply(1.5));
                if (PearlCatchTruth.catches(s.pearlSpawn(gap), pv, ThrowPhysics.windChargeSpawn(s.feetIn(0), 1.62), cv, g, true)) ++hits;
            }
        }
        return hits / (double) n;
    }
}
