package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Baseline: a free-flight replica of the engine's activation plan as it stood before the ensemble
 * (both orders over every gap, ranked by the Catch Time), replayed through {@link PearlCatchTruth}.
 * Opt-in ({@code -Dpearlcatch.baseline=true}); the numbers it printed are kept in the accuracy notes.
 */
class PearlCatchBaselineSweepTest {
    static final Vec3d FEET = new Vec3d(0.5, 200.0, 0.5);
    static final double EYE = 1.62;
    private static final int CATCH_TIME = 5;
    private static final int CATCH_LIMIT = CATCH_TIME + 30;
    private static final int MAX_GAP = 24;
    private static final float FLICK = 30.0f;
    private static final float SENT = 1.4f;
    private static final double STEP = 0.15;
    private static final double ACCEPT = 0.6;

    /** A shooter: movement the server reports, vertical speed now, on ground. */
    record Shooter(String name, Vec3d movement, double velocityY, boolean onGround) {
        Vec3d feetIn(int ticks) {
            double y = FEET.y;
            double vy = velocityY;
            if (!onGround) for (int i = 0; i < ticks; ++i) { y += vy; vy = (vy - 0.08) * 0.98; }
            return new Vec3d(FEET.x + movement.x * ticks, y, FEET.z + movement.z * ticks);
        }

        double velocityYIn(int ticks) {
            double vy = velocityY;
            if (!onGround) for (int i = 0; i < ticks; ++i) vy = (vy - 0.08) * 0.98;
            return onGround ? 0.0 : vy;
        }

        Vec3d movementIn(int ticks) {
            if (onGround || ticks <= 0) return movement;
            double vy = velocityY;
            double dy = movement.y;
            for (int i = 0; i < ticks; ++i) { dy = vy; vy = (vy - 0.08) * 0.98; }
            return new Vec3d(movement.x, dy, movement.z);
        }

        WindChargeIntercept.Shooter charge(int ticks) {
            return new WindChargeIntercept.Shooter(ThrowPhysics.windChargeSpawn(feetIn(ticks), EYE), movementIn(ticks), onGround);
        }

        Vec3d pearlSpawn(int ticks) {
            return ThrowPhysics.pearlSpawn(feetIn(ticks), EYE);
        }

        Vec3d pearlLaunch(float yaw, float pitch, int ticks) {
            return ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                    ThrowPhysics.PEARL_POWER, null, movementIn(ticks), onGround);
        }

        Vec3d chargeLaunch(float yaw, float pitch, int ticks) {
            return ThrowPhysics.launchVelocity(ThrowPhysics.serverYaw(yaw), ThrowPhysics.serverPitch(pitch),
                    ThrowPhysics.WIND_CHARGE_POWER, null, movementIn(ticks), onGround);
        }
    }

    static List<Shooter> shooters() {
        return List.of(
                new Shooter("standing", Vec3d.ZERO, 0.0, true),
                new Shooter("walking", new Vec3d(0.0, 0.0, 0.2158), 0.0, true),
                new Shooter("strafing", new Vec3d(0.2158, 0.0, 0.0), 0.0, true),
                new Shooter("jumping", new Vec3d(0.0, 0.42, 0.28), 0.3332, false),
                new Shooter("fall-0.3", new Vec3d(0.0, -0.3, 0.0), -0.3, false),
                new Shooter("fall-1.0", new Vec3d(0.0, -1.0, 0.0), -1.0, false),
                new Shooter("fall-2.5", new Vec3d(0.0, -2.5, 0.0), -2.5, false),
                new Shooter("air-drift", new Vec3d(0.0, -0.1, 0.3), -0.1, false));
    }

    /** A planned catch: order, gap, the first lead (charge first), and both looks. */
    record Plan(boolean chargeFirst, int lead, int gap, float pearlYaw, float pearlPitch, float chargeYaw,
                float chargePitch, double predicted, boolean fallback, boolean macro) {}

    // ── Legacy planner replica (free flight) ─────────────────────────────────

    static Plan legacyPlan(Shooter s, float yaw, float pitch) {
        Plan plan = legacySolve(s, yaw, pitch);
        if ((plan == null || plan.predicted < 0.5) && legacyFree(s, yaw, pitch) < 0.5) {
            for (float d = 1.0f; d <= 15.0f; d += 1.0f) {
                Plan best = null;
                for (int sign = -1; sign <= 1; sign += 2) {
                    float p = pitch + sign * d;
                    if (Math.abs(p) > 90.0f) continue;
                    Plan nudged = legacySolve(s, yaw, p);
                    if (nudged != null && nudged.predicted >= 0.5 && (best == null || nudged.predicted > best.predicted)) best = nudged;
                }
                if (best != null) return best;
            }
        }
        if (plan != null) return plan;
        // No solved catch: both along the look, the charge after the pearl aimed at its flight.
        boolean chargeFirst = pitch >= 45.0f;
        return new Plan(chargeFirst, 0, 1, yaw, pitch, yaw, pitch, 0.0, true, false);
    }

    private static double legacyFree(Shooter s, float yaw, float pitch) {
        Plan plan = legacySolve(s, yaw, pitch);
        return plan == null ? 0.0 : plan.predicted;
    }

    private static Plan legacySolve(Shooter s, float yaw, float pitch) {
        float budget = Math.abs(pitch) >= 55.0f ? 20.0f : Float.POSITIVE_INFINITY;
        PearlSimulation.Path path = PearlSimulation.ballistic(s.pearlSpawn(0), s.pearlLaunch(yaw, pitch, 0), 0).trace(160);
        WindChargeIntercept.Shot pearlFirst = null;
        for (int g = 1; g <= MAX_GAP; ++g) {
            WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(yaw, pitch, g * FLICK + SENT).withMaxYaw(budget);
            WindChargeIntercept.Shot shot = WindChargeIntercept.solve(null, null, path, g, s.charge(g), CATCH_LIMIT, reach, false);
            if (shot == null || !confident(shot, false)) continue;
            if (prefers(shot, false, pearlFirst, false)) pearlFirst = shot;
        }
        WindChargeIntercept.Shot chargeFirst = null;
        int bestLead = 0;
        for (int lead = 0; lead <= 2; ++lead) {
            WindChargeIntercept.Reach reach = new WindChargeIntercept.Reach(yaw, pitch, (lead + 1) * FLICK + SENT).withMaxYaw(budget);
            for (int g = 1; g <= MAX_GAP; ++g) {
                PearlSimulation.Path pearl = PearlSimulation.ballistic(s.pearlSpawn(lead + g), s.pearlLaunch(yaw, pitch, lead + g), 0)
                        .trace(CATCH_LIMIT + MAX_GAP + 2);
                WindChargeIntercept.Shot shot = WindChargeIntercept.solveOlder(null, null, pearl, g, s.charge(lead), CATCH_LIMIT, reach, false);
                if (shot == null || !confident(shot, true)) continue;
                if (prefers(shot, true, chargeFirst, true)) { chargeFirst = shot; bestLead = lead; }
            }
        }
        if (pearlFirst == null && chargeFirst == null) return null;
        boolean useCharge = pearlFirst == null || (chargeFirst != null && prefers(chargeFirst, true, pearlFirst, false));
        WindChargeIntercept.Shot shot = useCharge ? chargeFirst : pearlFirst;
        return new Plan(useCharge, useCharge ? bestLead : 0, shot.throwAge(), yaw, pitch, shot.yaw(), shot.pitch(), shot.score(), false, false);
    }

    private static boolean confident(WindChargeIntercept.Shot shot, boolean pearlLater) {
        int flight = pearlLater ? shot.catchAge() : shot.catchAge() - shot.throwAge();
        return flight >= 1 && flight <= CATCH_LIMIT;
    }

    private static boolean prefers(WindChargeIntercept.Shot candidate, boolean candidateLater,
                                   WindChargeIntercept.Shot current, boolean currentLater) {
        if (current == null) return candidate != null;
        if (candidate == null) return false;
        boolean cg = candidate.score() >= ACCEPT, ug = current.score() >= ACCEPT;
        if (cg != ug) return cg;
        int cf = candidateLater ? candidate.catchAge() : candidate.catchAge() - candidate.throwAge();
        int uf = currentLater ? current.catchAge() : current.catchAge() - current.throwAge();
        if (cg) {
            boolean cw = cf <= CATCH_TIME, uw = uf <= CATCH_TIME;
            if (cw != uw) return cw;
            if (cf != uf) return cw ? cf > uf : cf < uf;
            return candidate.score() > current.score() + 1.0e-6;
        }
        if (Math.abs(candidate.score() - current.score()) > 0.02) return candidate.score() > current.score();
        return Math.abs(cf - CATCH_TIME) < Math.abs(uf - CATCH_TIME);
    }

    /** Legacy live re-aim of the charge against the real pearl: the lattice within the sent tolerance. */
    static float[] legacyChargeReaim(Shooter s, PearlSimulation.Path realPearl, int gap, float yaw, float pitch) {
        List<float[]> looks = lattice(yaw, pitch);
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(yaw, pitch);
        WindChargeIntercept.Shot shot = WindChargeIntercept.best(null, null, realPearl, gap, s.charge(gap), CATCH_LIMIT, looks, reach);
        return shot == null ? null : new float[]{shot.yaw(), shot.pitch()};
    }

    /** Legacy live re-aim of the pearl against the real charge (charge first). */
    static float[] legacyPearlReaim(Shooter s, int lead, int gap, float yaw, float pitch, WindChargeIntercept.Charge charge) {
        WindChargeIntercept.Shot best = null;
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(yaw, pitch);
        for (float[] look : lattice(yaw, pitch)) {
            Vec3d launch = s.pearlLaunch(look[0], look[1], lead + gap);
            PearlSimulation.Path path = PearlSimulation.ballistic(s.pearlSpawn(lead + gap), launch, 0).trace(CATCH_LIMIT + 1);
            best = WindChargeIntercept.better(best, WindChargeIntercept.evaluatePearl(path, launch, look[0], look[1], charge, gap, CATCH_LIMIT), reach);
        }
        return best == null ? null : new float[]{best.yaw(), best.pitch()};
    }

    static List<float[]> lattice(float yaw, float pitch) {
        int span = (int) Math.floor(SENT / STEP);
        List<float[]> looks = new ArrayList<>();
        for (int i = -span; i <= span; ++i) {
            for (int j = -span; j <= span; ++j) {
                looks.add(new float[]{yaw + (float) (i * STEP), MathHelper.clamp(pitch + (float) (j * STEP), -90.0f, 90.0f)});
            }
        }
        return looks;
    }

    // ── Replay ────────────────────────────────────────────────────────────────

    /** The live re-aim of the second throw; {@code known}: aimed at the real first projectile, else at its forecast. */
    interface Reaim {
        float[] charge(Shooter s, Plan plan, PearlSimulation.Path pearl, int gap, float yaw, float pitch, boolean known);

        float[] pearl(Shooter s, Plan plan, int lead, int gap, float yaw, float pitch, WindChargeIntercept.Charge charge,
                      boolean known);
    }

    static final Reaim LEGACY = new Reaim() {
        public float[] charge(Shooter s, Plan plan, PearlSimulation.Path pearl, int gap, float yaw, float pitch, boolean known) {
            return legacyChargeReaim(s, pearl, gap, yaw, pitch);
        }

        public float[] pearl(Shooter s, Plan plan, int lead, int gap, float yaw, float pitch, WindChargeIntercept.Charge charge,
                             boolean known) {
            return legacyPearlReaim(s, lead, gap, yaw, pitch, charge);
        }
    };

    /**
     * Catch rate of a plan: the first projectile's spread is rolled and the second throw re-aimed live,
     * against the real first projectile when {@code refined} (its spawn packet is in) or against its
     * forecast otherwise; then the second throw's spread and the slip are rolled.
     */
    static double replay(Shooter s, Plan plan, Reaim reaim, boolean refined, int outer, int inner, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int hits = 0;
        int total = 0;
        float[] blindLook = null;
        for (int o = 0; o < outer; ++o) {
            if (!plan.chargeFirst()) {
                Vec3d pearlSpawn = s.pearlSpawn(0);
                Vec3d mean = s.pearlLaunch(plan.pearlYaw(), plan.pearlPitch(), 0);
                Vec3d pearlVelocity = mean.add(PearlCatchTruth.roll(random).multiply(1.5));
                float[] look = {plan.chargeYaw(), plan.chargePitch()};
                if (plan.fallback()) {
                    look = aimAt(s, pearlSpawn, refined ? pearlVelocity : mean, plan.gap(), look);
                } else if (refined) {
                    PearlSimulation.Path real = PearlSimulation.ballistic(pearlSpawn, pearlVelocity, 0).trace(160);
                    float[] better = reaim.charge(s, plan, real, plan.gap(), look[0], look[1], true);
                    if (better != null) look = better;
                } else {
                    if (blindLook == null) {
                        PearlSimulation.Path forecast = PearlSimulation.ballistic(pearlSpawn, mean, 0).trace(160);
                        float[] better = reaim.charge(s, plan, forecast, plan.gap(), look[0], look[1], false);
                        blindLook = better != null ? better : look;
                    }
                    look = blindLook;
                }
                for (int i = 0; i < inner; ++i) {
                    // The use carries the shooter of its client tick; a slip only moves the server tick handling it.
                    int gap = PearlCatchTruth.slipped(plan.gap(), random);
                    Vec3d chargeVelocity = s.chargeLaunch(look[0], look[1], plan.gap()).add(PearlCatchTruth.roll(random).multiply(1.5));
                    Vec3d chargeSpawn = ThrowPhysics.windChargeSpawn(s.feetIn(plan.gap()), EYE);
                    if (PearlCatchTruth.catches(pearlSpawn, pearlVelocity, chargeSpawn, chargeVelocity, gap, false)) ++hits;
                    ++total;
                }
            } else {
                int lead = plan.lead();
                Vec3d chargeSpawn = ThrowPhysics.windChargeSpawn(s.feetIn(lead), EYE);
                Vec3d mean = s.chargeLaunch(plan.chargeYaw(), plan.chargePitch(), lead);
                Vec3d chargeVelocity = mean.add(PearlCatchTruth.roll(random).multiply(1.5));
                float[] look = {plan.pearlYaw(), plan.pearlPitch()};
                if (!plan.fallback() && refined) {
                    WindChargeIntercept.Charge charge = new WindChargeIntercept.Charge(chargeSpawn, chargeVelocity, 400);
                    float[] better = reaim.pearl(s, plan, lead, plan.gap(), look[0], look[1], charge, true);
                    if (better != null) look = better;
                } else if (!plan.fallback()) {
                    if (blindLook == null) {
                        WindChargeIntercept.Charge forecast = new WindChargeIntercept.Charge(chargeSpawn, mean, 400);
                        float[] better = reaim.pearl(s, plan, lead, plan.gap(), look[0], look[1], forecast, false);
                        blindLook = better != null ? better : look;
                    }
                    look = blindLook;
                }
                for (int i = 0; i < inner; ++i) {
                    int gap = PearlCatchTruth.slipped(plan.gap(), random);
                    Vec3d pearlSpawn = s.pearlSpawn(lead + plan.gap());
                    Vec3d pearlVelocity = s.pearlLaunch(look[0], look[1], lead + plan.gap()).add(PearlCatchTruth.roll(random).multiply(1.5));
                    if (PearlCatchTruth.catches(pearlSpawn, pearlVelocity, chargeSpawn, chargeVelocity, gap, true)) ++hits;
                    ++total;
                }
            }
        }
        return total == 0 ? 0.0 : hits / (double) total;
    }

    /** The legacy best-effort charge: straight at where the pearl will be when the charge can reach it. */
    static float[] aimAt(Shooter s, Vec3d pearlSpawn, Vec3d pearlVelocity, int gap, float[] look) {
        PearlSimulation.Path path = PearlSimulation.ballistic(pearlSpawn, pearlVelocity, 0).trace(160);
        Vec3d from = s.charge(gap).spawn();
        for (int k = 1; k <= CATCH_LIMIT; ++k) {
            Vec3d target = path.positionAt(gap + k);
            if (target == null) break;
            Vec3d d = target.subtract(from);
            if (d.length() <= 1.5 * k) return ThrowPhysics.lookAlong(d, look[0]);
        }
        return look;
    }

    // ── Sweep ────────────────────────────────────────────────────────────────

    static final float[][] BANDS = {{-90, -76.5f}, {-76.5f, -45}, {-45, -10}, {-10, 10}, {10, 30}, {30, 50}, {50, 76.5f}, {76.5f, 90.1f}};

    static int band(float pitch) {
        for (int b = 0; b < BANDS.length; ++b) if (pitch >= BANDS[b][0] && pitch < BANDS[b][1]) return b;
        return BANDS.length - 1;
    }

    @Test
    void baselineSweep() {
        Assumptions.assumeTrue(Boolean.getBoolean("pearlcatch.baseline"), "opt-in: -Dpearlcatch.baseline=true");
        float pitchStep = Float.parseFloat(System.getProperty("pearlcatch.pitchStep", "3"));
        int outer = Integer.getInteger("pearlcatch.outer", 24);
        int inner = Integer.getInteger("pearlcatch.inner", 32);
        double[][] refinedSum = new double[shooters().size()][BANDS.length];
        double[][] blindSum = new double[shooters().size()][BANDS.length];
        double[][] predictedSum = new double[shooters().size()][BANDS.length];
        int[][] count = new int[shooters().size()][BANDS.length];
        int[][] fallbacks = new int[shooters().size()][BANDS.length];
        StringBuilder perPitch = new StringBuilder();
        long start = System.nanoTime();
        for (int si = 0; si < shooters().size(); ++si) {
            Shooter s = shooters().get(si);
            for (float pitch = -88.0f; pitch <= 88.01f; pitch += pitchStep) {
                Plan plan = legacyPlan(s, 0.0f, pitch);
                double refined = replay(s, plan, LEGACY, true, outer, inner, 31L * si + (long) (pitch * 7));
                double blind = replay(s, plan, LEGACY, false, outer, inner, 17L * si + (long) (pitch * 5));
                int b = band(pitch);
                refinedSum[si][b] += refined;
                blindSum[si][b] += blind;
                predictedSum[si][b] += plan.predicted();
                count[si][b]++;
                if (plan.fallback()) fallbacks[si][b]++;
                perPitch.append(String.format("%-9s %6.1f %s gap %2d pearl %6.1f pred %.3f refined %.3f blind %.3f%n",
                        s.name(), pitch, plan.fallback() ? "FALLBACK  " : plan.chargeFirst() ? "chargeFst " : "pearlFst  ",
                        plan.gap(), plan.pearlPitch(), plan.predicted(), refined, blind));
            }
        }
        System.out.print(perPitch);
        report("refined", refinedSum, count, fallbacks);
        report("blind", blindSum, count, fallbacks);
        report("predicted", predictedSum, count, fallbacks);
        System.out.printf("baseline sweep: %.1f s%n", (System.nanoTime() - start) / 1.0e9);
    }

    static void report(String label, double[][] sum, int[][] count, int[][] fallbacks) {
        StringBuilder sb = new StringBuilder("\n== " + label + " catch rate by band ==\n");
        sb.append(String.format("%-10s", "shooter"));
        for (float[] band : BANDS) sb.append(String.format(" %12s", String.format("%.0f..%.0f", band[0], band[1])));
        sb.append("     all\n");
        double total = 0;
        int n = 0;
        for (int si = 0; si < sum.length; ++si) {
            sb.append(String.format("%-10s", shooters().get(si).name()));
            double rowSum = 0;
            int rowN = 0;
            for (int b = 0; b < BANDS.length; ++b) {
                double mean = count[si][b] == 0 ? Double.NaN : sum[si][b] / count[si][b];
                sb.append(String.format(" %8.3f(%2d)", mean, fallbacks[si][b]));
                rowSum += sum[si][b];
                rowN += count[si][b];
            }
            sb.append(String.format(" %7.3f%n", rowSum / rowN));
            total += rowSum;
            n += rowN;
        }
        sb.append(String.format("overall %.4f over %d scenarios%n", total / n, n));
        System.out.print(sb);
    }
}
