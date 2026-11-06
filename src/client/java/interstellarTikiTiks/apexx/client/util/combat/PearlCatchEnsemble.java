package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.MathHelper;

/**
 * Final look for a Pearl Catch throw from two independent aim engines: the physics solver
 * ({@link WindChargeIntercept}, seed and refine) and the region model ({@link CatchRegionSolver}).
 *
 * <p>Each engine votes a look with its own confidence. The votes are weighted
 * {@code w = exp(GAIN * confidence + BIAS)} per engine ({@link PearlCatchModel}, trained offline against
 * an independent replay of the server's launch spread and timing slip) and averaged into a blended look.
 * The three looks (physics, region, blend), each snapped onto the mouse lattice, are then scored by the
 * same fine rate ({@link ThrowPhysics#FINE_POINTS}, every slip side included) and the best one is taken;
 * a look within {@link PearlCatchModel#AGREEMENT_TIE} of the best counts as level, and levels go to the
 * blend, then the region, because a look both engines agree on is the one least sensitive to what the
 * model does not know (a slightly different shooter, a late spawn packet).</p>
 */
public final class PearlCatchEnsemble {
    public enum Source { PHYSICS, REGION, BLEND }

    /** One engine's vote: a look and that engine's own estimate of its catch rate, 0..1. */
    public record Vote(float yaw, float pitch, double confidence) {}

    /** The chosen look, its fine rate, which look won and the region's share of the blend. */
    public record Decision(float yaw, float pitch, double rate, Source source, double regionWeight) {}

    @FunctionalInterface
    public interface FineRate {
        double rate(float yaw, float pitch);
    }

    /** Snaps a look onto the looks a rotation step can land on (the mouse lattice, within reach). */
    @FunctionalInterface
    public interface Snap {
        float[] snap(float yaw, float pitch);
    }

    private PearlCatchEnsemble() {}

    /** Region's share of the blend: w_region / (w_physics + w_region). */
    public static double regionWeight(double physicsConfidence, double regionConfidence) {
        double physics = PearlCatchModel.GAIN * physicsConfidence + PearlCatchModel.BIAS_PHYSICS;
        double region = PearlCatchModel.GAIN * regionConfidence + PearlCatchModel.BIAS_REGION;
        return 1.0 / (1.0 + Math.exp(physics - region));
    }

    /** The look {@code t} of the way from {@code a} to {@code b}, the yaw along the short way round. */
    public static float[] blend(float yawA, float pitchA, float yawB, float pitchB, double t) {
        float yaw = yawA + (float) (t * MathHelper.wrapDegrees(yawB - yawA));
        float pitch = pitchA + (float) (t * (pitchB - pitchA));
        return new float[]{yaw, MathHelper.clamp(pitch, -90.0f, 90.0f)};
    }

    /**
     * Picks the final look from the two votes (either may be null). Null when both are; otherwise the
     * best of the snapped physics, region and blended looks by {@code fine}, levels resolved as above.
     */
    public static Decision decide(Vote physics, Vote region, FineRate fine, Snap snap) {
        if (physics == null && region == null) return null;
        if (physics == null || region == null) {
            Vote only = physics != null ? physics : region;
            float[] look = snap.snap(only.yaw(), only.pitch());
            return new Decision(look[0], look[1], fine.rate(look[0], look[1]),
                    physics != null ? Source.PHYSICS : Source.REGION, physics != null ? 0.0 : 1.0);
        }
        double t = regionWeight(physics.confidence(), region.confidence());
        float[] a = snap.snap(physics.yaw(), physics.pitch());
        float[] b = snap.snap(region.yaw(), region.pitch());
        float[] m = blend(physics.yaw(), physics.pitch(), region.yaw(), region.pitch(), t);
        m = snap.snap(m[0], m[1]);
        double rateA = fine.rate(a[0], a[1]);
        double rateB = same(a, b) ? rateA : fine.rate(b[0], b[1]);
        double rateM = same(m, a) ? rateA : same(m, b) ? rateB : fine.rate(m[0], m[1]);
        double best = Math.max(rateA, Math.max(rateB, rateM));
        double tie = PearlCatchModel.AGREEMENT_TIE;
        if (rateM >= best - tie) return new Decision(m[0], m[1], rateM, Source.BLEND, t);
        if (rateB >= best - tie) return new Decision(b[0], b[1], rateB, Source.REGION, t);
        return new Decision(a[0], a[1], rateA, Source.PHYSICS, t);
    }

    private static boolean same(float[] a, float[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}
