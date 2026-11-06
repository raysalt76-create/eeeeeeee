package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Plan;
import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Reaim;
import interstellarTikiTiks.apexx.client.util.combat.PearlCatchBaselineSweepTest.Shooter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner as it ships ({@link PearlCatchPlanner}: honest rates, the learned order rule, both aim
 * engines and the ensemble) replayed through {@link PearlCatchTruth} over every pitch and the same
 * shooters as the baseline. A small smoke sweep runs by default; {@code -Ppearlcatch.full=true} (with
 * {@code -I scripts/pearlcatch-sim.init.gradle}) runs the full grid the accuracy notes report.
 */
class PearlCatchAccuracySweepTest {
    static final int CATCH_TIME = 5;
    static final int MAX_GAP = 24;
    static final float FLICK = 30.0f;
    static final double STEP = 0.15;

    static PearlCatchPlanner.Context context(Shooter s, int ticks, float sentYaw, float sentPitch, double step) {
        return new PearlCatchPlanner.Context(null, null, s.feetIn(ticks), s.movementIn(ticks), s.velocityYIn(ticks),
                s.onGround(), Double.NaN, PearlCatchBaselineSweepTest.EYE, sentYaw, sentPitch, FLICK, step, CATCH_TIME, 1, MAX_GAP);
    }

    /** The shipped planner's plan, in the replay's terms. */
    static Plan plan(Shooter s, float yaw, float pitch, double step) {
        PearlCatchPlanner.Plan plan = new PearlCatchPlanner(context(s, 0, yaw, pitch, step)).plan(yaw, pitch);
        int gap = Math.max(1, plan.gap());
        return new Plan(plan.chargeFirst(), plan.lead(), gap, plan.pearlYaw(), plan.pearlPitch(), plan.chargeYaw(),
                plan.chargePitch(), plan.rate(), !plan.solved() && plan.mode() == PearlCatchPlanner.Mode.SOLVER,
                plan.mode() == PearlCatchPlanner.Mode.MACRO_DOWN);
    }

    static final java.util.concurrent.atomic.AtomicLong reaimNanos = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong reaimMax = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicInteger reaims = new java.util.concurrent.atomic.AtomicInteger();

    static void timed(long start) {
        long spent = System.nanoTime() - start;
        reaimNanos.addAndGet(spent);
        reaimMax.accumulateAndGet(spent, Math::max);
        reaims.incrementAndGet();
    }

    static Reaim shipped(double step) {
        return new Reaim() {
            public float[] charge(Shooter s, Plan plan, PearlSimulation.Path pearl, int gap, float yaw, float pitch, boolean known) {
                long start = System.nanoTime();
                try {
                    return chargeAim(s, plan, pearl, gap, yaw, pitch, known);
                } finally {
                    timed(start);
                }
            }

            private float[] chargeAim(Shooter s, Plan plan, PearlSimulation.Path pearl, int gap, float yaw, float pitch, boolean known) {
                PearlCatchPlanner planner = new PearlCatchPlanner(context(s, gap, yaw, pitch, step));
                WindChargeIntercept.Shooter shooter = planner.chargeShooter(planner.shooterIn(0));
                PearlCatchPlanner.Aim aim = macroDown(plan)
                        ? planner.aimChargePitch(pearl, gap, shooter, yaw, pitch, PearlCatchPlanner.MACRO_PITCH_WINDOW, known)
                        : planner.aimCharge(pearl, gap, shooter, new float[]{yaw, pitch}, known);
                return aim == null ? null : new float[]{aim.shot().yaw(), aim.shot().pitch()};
            }

            public float[] pearl(Shooter s, Plan plan, int lead, int gap, float yaw, float pitch, WindChargeIntercept.Charge charge,
                                 boolean known) {
                long start = System.nanoTime();
                try {
                    return pearlAim(s, plan, lead, gap, yaw, pitch, charge, known);
                } finally {
                    timed(start);
                }
            }

            private float[] pearlAim(Shooter s, Plan plan, int lead, int gap, float yaw, float pitch, WindChargeIntercept.Charge charge,
                                     boolean known) {
                PearlCatchPlanner planner = new PearlCatchPlanner(context(s, lead + gap, yaw, pitch, step));
                PearlCatchPlanner.Aim aim = planner.aimPearl(charge, gap, yaw, pitch,
                        macroDown(plan) ? PearlCatchPlanner.SENT_LOOK_TOLERANCE : null, known, null);
                return aim == null ? null : new float[]{aim.shot().yaw(), aim.shot().pitch()};
            }
        };
    }

    static boolean macroDown(Plan plan) {
        return plan.macro();
    }

    @Test
    void sweep() {
        boolean full = Boolean.getBoolean("pearlcatch.full");
        float pitchStep = Float.parseFloat(System.getProperty("pearlcatch.pitchStep", full ? "3" : "22"));
        int outer = Integer.getInteger("pearlcatch.outer", full ? 24 : 6);
        int inner = Integer.getInteger("pearlcatch.inner", full ? 32 : 8);
        boolean baseline = Boolean.getBoolean("pearlcatch.compare");
        double step = Double.parseDouble(System.getProperty("pearlcatch.step", String.valueOf(STEP)));
        List<Shooter> shooters = PearlCatchBaselineSweepTest.shooters();
        int bands = PearlCatchBaselineSweepTest.BANDS.length;
        double[][] refinedSum = new double[shooters.size()][bands];
        double[][] blindSum = new double[shooters.size()][bands];
        double[][] predictedSum = new double[shooters.size()][bands];
        double[][] legacySum = new double[shooters.size()][bands];
        int[][] count = new int[shooters.size()][bands];
        int[][] fallbacks = new int[shooters.size()][bands];
        StringBuilder perPitch = new StringBuilder();
        long start = System.nanoTime();
        long planNanos = 0;
        long planMax = 0;
        String planMaxAt = "";
        int plans = 0;
        Reaim reaim = shipped(step);
        for (int si = 0; si < shooters.size(); ++si) {
            Shooter s = shooters.get(si);
            for (float pitch = -88.0f; pitch <= 88.01f; pitch += pitchStep) {
                long t0 = System.nanoTime();
                Plan plan = plan(s, 0.0f, pitch, step);
                long spent = System.nanoTime() - t0;
                planNanos += spent;
                // The first plans include JIT warm-up; they are left out of the worst case.
                if (plans >= 20 && spent > planMax) { planMax = spent; planMaxAt = s.name() + " " + pitch; }
                ++plans;
                double refined = PearlCatchBaselineSweepTest.replay(s, plan, reaim, true, outer, inner, 31L * si + (long) (pitch * 7));
                double blind = PearlCatchBaselineSweepTest.replay(s, plan, reaim, false, outer, inner, 17L * si + (long) (pitch * 5));
                int b = PearlCatchBaselineSweepTest.band(pitch);
                refinedSum[si][b] += refined;
                blindSum[si][b] += blind;
                predictedSum[si][b] += plan.predicted();
                count[si][b]++;
                if (plan.fallback()) fallbacks[si][b]++;
                if (baseline) {
                    Plan old = PearlCatchBaselineSweepTest.legacyPlan(s, 0.0f, pitch);
                    legacySum[si][b] += PearlCatchBaselineSweepTest.replay(s, old, PearlCatchBaselineSweepTest.LEGACY, true,
                            outer, inner, 31L * si + (long) (pitch * 7));
                }
                perPitch.append(String.format("%-9s %6.1f %s gap %2d pearl %6.1f pred %.3f refined %.3f blind %.3f%n",
                        s.name(), pitch, plan.fallback() ? "FALLBACK  " : plan.chargeFirst() ? "chargeFst " : "pearlFst  ",
                        plan.gap(), plan.pearlPitch(), plan.predicted(), refined, blind));
            }
        }
        System.out.print(perPitch);
        PearlCatchBaselineSweepTest.report("refined", refinedSum, count, fallbacks);
        PearlCatchBaselineSweepTest.report("blind", blindSum, count, fallbacks);
        PearlCatchBaselineSweepTest.report("predicted", predictedSum, count, fallbacks);
        if (baseline) PearlCatchBaselineSweepTest.report("legacy refined (same rolls)", legacySum, count, fallbacks);
        System.out.printf("sweep: %.1f s, plan %.1f ms average, %.1f ms worst after warm-up (%s); live re-aim %.2f ms average, %.1f ms worst over %d%n",
                (System.nanoTime() - start) / 1.0e9, planNanos / 1.0e6 / plans, planMax / 1.0e6, planMaxAt,
                reaimNanos.get() / 1.0e6 / Math.max(1, reaims.get()), reaimMax.get() / 1.0e6, reaims.get());
        double all = 0;
        int n = 0;
        for (int si = 0; si < shooters.size(); ++si) for (int b = 0; b < bands; ++b) { all += refinedSum[si][b]; n += count[si][b]; }
        assertTrue(all / n > 0.5, "the shipped planner catches most throws in open air: " + all / n);
    }
}
