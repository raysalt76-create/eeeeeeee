package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Plan;
import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Shooter;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Offline training of {@link PearlCatchModel}. Opt-in: {@code ./gradlew test --offline --tests '*PearlCatchTrainer*'
 * -I scripts/pearlcatch-sim.init.gradle -Ppearlcatch.train=true} (optionally {@code -Ppearlcatch.samples=N}).
 *
 * <p><b>Ensemble.</b> Random scenarios (shooter on the ground or in the air, any look in the solver range,
 * four mouse sensitivities, the first projectile's spawn packet in or not): the shipped planner plans the
 * catch, the first projectile is rolled, and the second throw's two engine votes are recorded with every
 * blend between them (11 points), each scored by the ensemble's fine rate and by {@link PearlCatchTruth}
 * (fresh spread rolls and slips, independent physics). The weight gain, the region's bias and the
 * agreement tie are then fitted by grid search on half of the samples and reported on the other half.</p>
 *
 * <p><b>Order.</b> For each vertical speed of {@link PearlCatchModel}'s table, both orders are planned on
 * their own over the downward pitches and replayed; the threshold is the first pitch from which charge
 * first catches at least as often as pearl first on two samples running.</p>
 */
class PearlCatchTrainer {
    private static final int BLENDS = 11;
    private static final double[] STEPS = {0.05, 0.15, 0.3, 0.6144};

    /** One recorded decision: both confidences and, per blend point, the fine rate and the replayed truth. */
    record Sample(double physics, double region, double[] fine, double[] truth, boolean known) {}

    @Test
    void train() {
        Assumptions.assumeTrue(Boolean.getBoolean("pearlcatch.train"), "opt-in: -Ppearlcatch.train=true");
        if (!Boolean.getBoolean("pearlcatch.skipEnsemble")) trainEnsemble();
        if (!Boolean.getBoolean("pearlcatch.skipOrder")) trainOrder();
    }

    // ── Ensemble ─────────────────────────────────────────────────────────────

    private void trainEnsemble() {
        int samples = Integer.getInteger("pearlcatch.samples", 1600);
        int inner = Integer.getInteger("pearlcatch.inner", 256);
        long start = System.nanoTime();
        List<Sample> all = IntStream.range(0, samples).parallel()
                .mapToObj(i -> sample(new SplittableRandom(0x5EED0000L + i), inner))
                .filter(Objects::nonNull).toList();
        System.out.printf("ensemble: %d usable samples of %d in %.1f s%n", all.size(), samples, (System.nanoTime() - start) / 1e9);
        List<Sample> train = new ArrayList<>();
        List<Sample> test = new ArrayList<>();
        for (int i = 0; i < all.size(); ++i) (i % 2 == 0 ? train : test).add(all.get(i));

        double[] gains = {0, 1, 2, 4, 8, 16, 32};
        double[] biases = {-4, -2, -1, -0.5, 0, 0.5, 1, 2, 4};
        double[] ties = {0, 0.0025, 0.005, 0.01, 0.02, 0.04};
        double best = -1;
        double[] bestParams = null;
        for (double gain : gains) for (double bias : biases) for (double tie : ties) {
            double score = evaluate(train, gain, bias, tie);
            if (score > best + 1e-12) { best = score; bestParams = new double[]{gain, bias, tie}; }
        }
        double current = evaluate(test, PearlCatchModel.GAIN, PearlCatchModel.BIAS_REGION - PearlCatchModel.BIAS_PHYSICS,
                PearlCatchModel.AGREEMENT_TIE);
        System.out.printf("fitted on %d: GAIN=%.2f BIAS_REGION=%.2f AGREEMENT_TIE=%.4f -> train %.4f%n",
                train.size(), bestParams[0], bestParams[1], bestParams[2], best);
        report("held-out", test, bestParams, current);
        report("all", all, bestParams, evaluate(all, PearlCatchModel.GAIN,
                PearlCatchModel.BIAS_REGION - PearlCatchModel.BIAS_PHYSICS, PearlCatchModel.AGREEMENT_TIE));
    }

    private static void report(String label, List<Sample> set, double[] params, double current) {
        double physics = 0, region = 0, oracle = 0, fineBest = 0;
        int regionMissing = 0;
        for (Sample s : set) {
            physics += s.truth()[0];
            region += Double.isNaN(s.region()) ? s.truth()[0] : s.truth()[BLENDS - 1];
            if (Double.isNaN(s.region())) ++regionMissing;
            double max = 0;
            int arg = 0;
            for (int k = 0; k < BLENDS; ++k) {
                max = Math.max(max, s.truth()[k]);
                if (s.fine()[k] > s.fine()[arg]) arg = k;
            }
            oracle += max;
            fineBest += s.truth()[arg];
        }
        int n = set.size();
        System.out.printf("%s (%d samples, %d without a region vote): physics only %.4f | region only %.4f | "
                        + "best fine of 11 blends %.4f | fitted ensemble %.4f | current constants %.4f | oracle %.4f%n",
                label, n, regionMissing, physics / n, region / n, fineBest / n, evaluate(set, params[0], params[1], params[2]),
                current, oracle / n);
        for (boolean known : new boolean[]{true, false}) {
            double p = 0, e = 0;
            int m = 0;
            for (Sample s : set) {
                if (s.known() != known) continue;
                p += s.truth()[0];
                e += decide(s, params[0], params[1], params[2]);
                ++m;
            }
            if (m > 0) System.out.printf("  %s: physics only %.4f, fitted ensemble %.4f (%d)%n",
                    known ? "first launch known" : "first launch unknown", p / m, e / m, m);
        }
    }

    private static double evaluate(List<Sample> set, double gain, double bias, double tie) {
        double sum = 0;
        for (Sample s : set) sum += decide(s, gain, bias, tie);
        return sum / set.size();
    }

    /** {@link PearlCatchEnsemble#decide} on a recorded sample: the truth of the look it would take. */
    static double decide(Sample s, double gain, double bias, double tie) {
        if (Double.isNaN(s.region())) return s.truth()[0];
        double t = 1.0 / (1.0 + Math.exp(gain * s.physics() - (gain * s.region() + bias)));
        int k = (int) Math.round(t * (BLENDS - 1));
        double a = s.fine()[0], b = s.fine()[BLENDS - 1], m = s.fine()[k];
        double best = Math.max(a, Math.max(b, m));
        if (m >= best - tie) return s.truth()[k];
        if (b >= best - tie) return s.truth()[BLENDS - 1];
        return s.truth()[0];
    }

    private static Shooter randomShooter(SplittableRandom r) {
        double heading = r.nextDouble() * Math.PI * 2;
        if (r.nextDouble() < 0.4) {
            double speed = r.nextDouble() < 0.5 ? 0.0 : 0.1 + r.nextDouble() * 0.18;
            return new Shooter("ground", new Vec3d(Math.sin(heading) * speed, 0.0, Math.cos(heading) * speed), 0.0, true);
        }
        double vy = -2.5 + r.nextDouble() * 2.92;
        double previous = vy / 0.98 + 0.08;
        double speed = r.nextDouble() * 0.3;
        return new Shooter("air", new Vec3d(Math.sin(heading) * speed, previous, Math.cos(heading) * speed), vy, false);
    }

    /** One ensemble sample, or null when the scenario has no solved catch or no live vote. */
    private static Sample sample(SplittableRandom r, int inner) {
        Shooter s = randomShooter(r);
        float yaw = (float) (r.nextDouble() * 360.0 - 180.0);
        float pitch = (float) (r.nextDouble() * 150.0 - 75.0);
        double step = STEPS[r.nextInt(STEPS.length)];
        boolean known = r.nextBoolean();
        PearlCatchPlanner.Plan plan = new PearlCatchPlanner(PearlCatchAccuracySweepTest.context(s, 0, yaw, pitch, step)).plan(yaw, pitch);
        if (!plan.solved() || plan.mode() != PearlCatchPlanner.Mode.SOLVER) return null;
        int gap = Math.max(1, plan.gap());
        long seed = r.nextLong();
        if (!plan.chargeFirst()) {
            Vec3d spawn = s.pearlSpawn(0);
            Vec3d mean = s.pearlLaunch(plan.pearlYaw(), plan.pearlPitch(), 0);
            Vec3d real = mean.add(PearlCatchTruth.roll(r).multiply(1.5));
            PearlSimulation.Path path = PearlSimulation.ballistic(spawn, known ? real : mean, 0).trace(160);
            PearlCatchPlanner planner = new PearlCatchPlanner(
                    PearlCatchAccuracySweepTest.context(s, gap, plan.chargeYaw(), plan.chargePitch(), step));
            WindChargeIntercept.Shooter shooter = planner.chargeShooter(planner.shooterIn(0));
            PearlCatchPlanner.Ballot ballot = planner.chargeBallot(path, gap, shooter,
                    new float[]{plan.chargeYaw(), plan.chargePitch()}, known);
            if (ballot == null) return null;
            return record(ballot, known, (look, random) -> {
                int g = PearlCatchTruth.slipped(gap, random);
                Vec3d pearl = known ? real : mean.add(PearlCatchTruth.roll(random).multiply(1.5));
                Vec3d charge = s.chargeLaunch(look[0], look[1], gap).add(PearlCatchTruth.roll(random).multiply(1.5));
                return PearlCatchTruth.catches(spawn, pearl, ThrowPhysics.windChargeSpawn(s.feetIn(gap), PearlCatchBaselineSweepTest.EYE),
                        charge, g, false);
            }, inner, seed);
        }
        int lead = plan.lead();
        Vec3d chargeSpawn = ThrowPhysics.windChargeSpawn(s.feetIn(lead), PearlCatchBaselineSweepTest.EYE);
        Vec3d mean = s.chargeLaunch(plan.chargeYaw(), plan.chargePitch(), lead);
        Vec3d real = mean.add(PearlCatchTruth.roll(r).multiply(1.5));
        WindChargeIntercept.Charge charge = new WindChargeIntercept.Charge(chargeSpawn, known ? real : mean, 400);
        PearlCatchPlanner planner = new PearlCatchPlanner(
                PearlCatchAccuracySweepTest.context(s, lead + gap, plan.pearlYaw(), plan.pearlPitch(), step));
        PearlCatchPlanner.Ballot ballot = planner.pearlBallot(charge, gap, plan.pearlYaw(), plan.pearlPitch(), null, known, null);
        if (ballot == null) return null;
        return record(ballot, known, (look, random) -> {
            int g = PearlCatchTruth.slipped(gap, random);
            Vec3d chargeVelocity = known ? real : mean.add(PearlCatchTruth.roll(random).multiply(1.5));
            Vec3d pearl = s.pearlLaunch(look[0], look[1], lead + gap).add(PearlCatchTruth.roll(random).multiply(1.5));
            return PearlCatchTruth.catches(s.pearlSpawn(lead + gap), pearl, chargeSpawn, chargeVelocity, g, true);
        }, inner, seed);
    }

    private interface Trial {
        boolean hit(float[] look, SplittableRandom random);
    }

    private static Sample record(PearlCatchPlanner.Ballot ballot, boolean known, Trial trial, int inner, long seed) {
        PearlCatchEnsemble.Vote a = ballot.physics();
        PearlCatchEnsemble.Vote b = ballot.region() == null ? a : ballot.region();
        double[] fine = new double[BLENDS];
        double[] truth = new double[BLENDS];
        for (int k = 0; k < BLENDS; ++k) {
            float[] look = PearlCatchEnsemble.blend(a.yaw(), a.pitch(), b.yaw(), b.pitch(), k / (double) (BLENDS - 1));
            look = ballot.snap().snap(look[0], look[1]);
            fine[k] = ballot.fine().rate(look[0], look[1]);
            // Common random numbers: every blend point sees the same rolls.
            SplittableRandom random = new SplittableRandom(seed);
            int hits = 0;
            for (int i = 0; i < inner; ++i) if (trial.hit(look, random)) ++hits;
            truth[k] = hits / (double) inner;
        }
        return new Sample(a.confidence(), ballot.region() == null ? Double.NaN : ballot.region().confidence(), fine, truth, known);
    }

    // ── Order ────────────────────────────────────────────────────────────────

    private void trainOrder() {
        double[] speeds = {-3.0, -2.5, -2.0, -1.5, -1.0, -0.6, -0.3, 0.0, 0.42};
        int outer = Integer.getInteger("pearlcatch.orderOuter", 16);
        int innerRolls = Integer.getInteger("pearlcatch.orderInner", 24);
        StringBuilder table = new StringBuilder();
        double[] biasGrid = {0.0, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5};
        double[] biasTruth = new double[biasGrid.length];
        int biasCount = 0;
        for (double v : speeds) {
            Shooter s = v == 0.0 ? new Shooter("ground", Vec3d.ZERO, 0.0, true)
                    : new Shooter("air", new Vec3d(0, v, 0), (v - 0.08) * 0.98, false);
            List<Float> pitches = new ArrayList<>();
            for (float p = 0; p <= 76.01f; p += 2) pitches.add(p);
            double[][] rates = pitches.parallelStream().map(p -> {
                double[] pair = new double[4];
                for (int order = 0; order < 2; ++order) {
                    PearlCatchPlanner planner = new PearlCatchPlanner(PearlCatchAccuracySweepTest.context(s, 0, 0f, p, 0.15));
                    PearlCatchPlanner.Plan plan = planner.planSolver(0f, p, order == 1);
                    if (plan == null) continue;
                    Plan replayPlan = new Plan(plan.chargeFirst(), plan.lead(), Math.max(1, plan.gap()), plan.pearlYaw(),
                            plan.pearlPitch(), plan.chargeYaw(), plan.chargePitch(), plan.rate(), false, false);
                    var reaim = PearlCatchAccuracySweepTest.shipped(0.15);
                    double refined = PearlCatchBaselineSweepTest.replay(s, replayPlan, reaim, true, outer, innerRolls, 7L + (long) (p * 13));
                    double blind = PearlCatchBaselineSweepTest.replay(s, replayPlan, reaim, false, outer, innerRolls, 11L + (long) (p * 17));
                    pair[order] = (1 - PearlCatchModel.BLIND_SHARE) * refined + PearlCatchModel.BLIND_SHARE * blind;
                    pair[order + 2] = plan.rate();
                }
                return pair;
            }).toArray(double[][]::new);
            float threshold = 90f;
            for (int i = 0; i + 1 < pitches.size(); ++i) {
                if (rates[i][1] >= rates[i][0] && rates[i + 1][1] >= rates[i + 1][0] && rates[i][1] > 0) {
                    threshold = pitches.get(i);
                    break;
                }
            }
            StringBuilder curve = new StringBuilder();
            for (int i = 0; i < pitches.size(); ++i) {
                curve.append(String.format(" %2.0f:%.2f/%.2f", pitches.get(i), rates[i][0], rates[i][1]));
            }
            System.out.printf("order v=%+.2f threshold %.0f |%s%n", v, threshold, curve);
            // The bias strength: which order the planner's own rates pick with the threshold's bias, and its truth.
            for (int i = 0; i < pitches.size(); ++i) {
                if (rates[i][2] <= 0 && rates[i][3] <= 0) continue;
                double over = (pitches.get(i) - threshold) / 4.0;
                double side = Math.max(-1.0, Math.min(1.0, over));
                for (int b = 0; b < biasGrid.length; ++b) {
                    boolean charge = rates[i][2] <= 0 || (rates[i][3] > 0 && rates[i][3] + biasGrid[b] * side > rates[i][2]);
                    biasTruth[b] += rates[i][charge ? 1 : 0];
                }
                ++biasCount;
            }
            table.append(String.format("%.1f, ", threshold));
        }
        System.out.println("CHARGE_FIRST_PITCH = {" + table + "}");
        for (int b = 0; b < biasGrid.length; ++b) {
            System.out.printf("ORDER_BIAS %.2f -> mean truth %.4f over %d pitches with a catch%n", biasGrid[b], biasTruth[b] / biasCount, biasCount);
        }
    }
}
