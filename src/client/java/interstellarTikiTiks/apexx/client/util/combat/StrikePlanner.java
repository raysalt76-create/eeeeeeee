package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Chooses which tick head a mace strike lands on and where on the target the view must be at that head,
 * so the rotation can actually get there.
 *
 * <p>A click is judged at a tick head against the positions of the tick before it. At dive speeds the target
 * is in reach for one to three heads, and the bearing between them can swing by more than the rotation can
 * turn in a tick, so aiming at "the target now" is often aiming at a head that is already lost. The planner
 * looks at the next {@code heads.length} heads (forecast eye and box per head), keeps only body points the
 * view can reach by each head's deadline (from the controller's live state, {@link Reach}), and picks the
 * strike that completes earliest:
 * <ul>
 *   <li>{@link Need#SMASH}: any reachable head.</li>
 *   <li>{@link Need#STUN}: from behind the shield, a single smash (the backstab); in front of it, the axe on
 *   a head from which the mace can still reach a head {@code pairGap..6} later (rest-to-rest turn limits).</li>
 *   <li>{@link Need#BACKSTAB}: only heads behind the shield.</li>
 *   <li>{@link Need#MACE}: the axe already landed; any reachable head in {@code [maceFrom, maceTo]}.</li>
 * </ul>
 * Ties go to the point needing the least turn. A previous plan (same head, same body point) is kept while it
 * is still valid on fresh geometry, which is what stops the view switching between equally good options.
 *
 * <p>Pure geometry: no client state, no clicks. AutoMace only steers with it; every click still requires the
 * live ray on the target in reach.
 */
public final class StrikePlanner {
    private StrikePlanner() {}

    public enum Need { SMASH, STUN, AXE, BACKSTAB, MACE }

    public enum Kind { SMASH, AXE, MACE }

    /** One upcoming tick head: the eye and target box the click will be judged with, and the shield side. */
    public record Head(Vec3d eye, Box box, boolean shieldCovers, java.util.function.Predicate<Vec3d> visible) {
        /** A head with no block-occlusion test: every point of the box counts as visible. */
        public Head(Vec3d eye, Box box, boolean shieldCovers) {
            this(eye, box, shieldCovers, null);
        }
    }

    /**
     * The grid point (see {@link #XS}/{@link #YS}) of {@code box} nearest the eye whose line from the eye is
     * not blocked by {@code head.visible()}, or null when every point is hidden. Used for plain tracking,
     * where the aim should sit on the closest part of the hitbox that is actually exposed.
     */
    public static Vec3d closestVisiblePoint(Head head) {
        Box b = head.box();
        Vec3d e = head.eye();
        if (b == null || e == null) {
            return null;
        }
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;
        for (int p = 0; p < POINTS; p++) {
            Vec3d point = gridPoint(b, p);
            double d = e.distanceTo(point);
            if (d < bestDist && (head.visible() == null || head.visible().test(point))) {
                bestDist = d;
                best = point;
            }
        }
        return best;
    }

    /** Grid point {@code p} inside {@code b}, inset from every face. */
    static Vec3d gridPoint(Box b, int p) {
        double ix = Math.min(INSET, b.getLengthX() * 0.4);
        double iy = Math.min(INSET, b.getLengthY() * 0.4);
        double iz = Math.min(INSET, b.getLengthZ() * 0.4);
        int perX = YS.length * XS.length;
        double fx = XS[p / perX], fy = YS[(p % perX) / XS.length], fz = XS[p % XS.length];
        return new Vec3d(
                b.minX + ix + (b.getLengthX() - 2 * ix) * fx,
                b.minY + iy + (b.getLengthY() - 2 * iy) * fy,
                b.minZ + iz + (b.getLengthZ() - 2 * iz) * fz);
    }

    /** Blocks by which grid point {@code p} is farther from the eye than the box's nearest face. */
    private static double nearness(Head head, int p) {
        if (p >= POINTS || head.eye() == null || head.box() == null) {
            return 0.0;
        }
        Box b = head.box();
        Vec3d e = head.eye();
        double dx = Math.max(Math.max(b.minX - e.x, 0.0), e.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - e.y, 0.0), e.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - e.z, 0.0), e.z - b.maxZ);
        double boxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0.0, e.distanceTo(gridPoint(b, p)) - boxDist);
    }

    /** Yaw/pitch offsets (degrees, from the live view) the controller can reach by a head's deadline. */
    public record Reach(double yawLow, double yawHigh, double pitchLow, double pitchHigh) {
        boolean allows(double dYaw, double dPitch, double slack) {
            return dYaw >= yawLow - slack && dYaw <= yawHigh + slack
                    && dPitch >= pitchLow - slack && dPitch <= pitchHigh + slack;
        }

        /** Share of the reachable range a turn uses, 0..1+ on the busier axis. */
        public double usage(double dYaw, double dPitch) {
            double yawRange = dYaw >= 0.0 ? yawHigh : -yawLow;
            double pitchRange = dPitch >= 0.0 ? pitchHigh : -pitchLow;
            return Math.max(Math.abs(dYaw) / Math.max(1.0e-6, yawRange), Math.abs(dPitch) / Math.max(1.0e-6, pitchRange));
        }
    }

    /** The strike to steer for: head index (0 = next head), body point, and the view angles that land it. */
    public record Plan(int head, int point, Kind kind, float yaw, float pitch) {}

    static final double[] XS = {0.2, 0.5, 0.8};
    static final double[] YS = {0.2, 0.35, 0.5, 0.65, 0.8, 0.9};
    static final int POINTS = XS.length * YS.length * XS.length;
    /** The live direction and one shared axe/mace direction per possible follow-up gap. */
    private static final int VIEW_POINT = POINTS;
    private static final int CANDIDATE_COUNT = POINTS + 1 + StunSlamEngine.MAX_FOLLOW_UP_TICKS;
    /** Candidate points stay this far inside every box face. */
    static final double INSET = 0.1;
    /** The ray must enter the box this much inside reach. */
    static final double REACH_SLACK = 0.05;
    /** A kept plan may sit this far (degrees) outside the reach estimate before it is dropped. */
    static final double LOCK_SLACK = 2.0;
    /**
     * Degrees of turn a point at the very edge of the body is penalised, so equally good strikes go to
     * the one nearest the body's centre (the most forgiving point for a ray that is a hair off).
     */
    static final double CENTER_WEIGHT = 24.0;
    /**
     * Degrees of turn per block a point sits beyond the nearest face of the body: the strike goes to the
     * part of the hitbox closest to the eye (hidden parts are dropped in {@link #candidates}).
     */
    static final double NEAR_WEIGHT = 40.0;
    /** Latest head the mace may follow the axe (matches the follow-up window). */
    static final int MAX_PAIR_GAP = StunSlamEngine.MAX_FOLLOW_UP_TICKS;

    /**
     * @param view       live view {yaw, pitch}
     * @param reach      reachable offsets per head, same length as {@code heads}
     * @param pairGap    lowest axe-to-mace head gap the follow-up allows (>= 1)
     * @param maceFrom   {@link Need#MACE}: first allowed head index
     * @param maceTo     {@link Need#MACE}: last allowed head index
     * @param reachDistance melee reach, blocks
     * @param keep       the plan steered for last frame re-expressed for these heads (head index shifted), or null
     */
    public static Optional<Plan> plan(Head[] heads, Reach[] reach, float[] view, Need need, int pairGap,
                                      int maceFrom, int maceTo, double reachDistance, Plan keep) {
        return plan(heads, reach, view, need, pairGap, false, maceFrom, maceTo, reachDistance, keep);
    }

    /** Normal mode requires the mace at exactly pairGap; Adaptive may use a later reachable head. */
    public static Optional<Plan> plan(Head[] heads, Reach[] reach, float[] view, Need need, int pairGap,
                                      boolean exactPairGap, int maceFrom, int maceTo, double reachDistance, Plan keep) {
        return plan(heads, reach, view, need, pairGap, exactPairGap, maceFrom, maceTo, reachDistance, keep, null);
    }

    /**
     * The body-grid candidates of every head. They depend only on the heads' geometry and the reach, so a
     * caller that plans every render frame against the same per-tick heads computes them once per tick and
     * passes them to {@link #plan(Head[], Reach[], float[], Need, int, boolean, int, int, double, Plan, float[][][])}.
     * The returned rows are never written by the planner.
     */
    public static float[][][] prepareCandidates(Head[] heads, double reachDistance) {
        float[][][] prepared = new float[heads.length][][];
        for (int k = 0; k < heads.length; k++) {
            prepared[k] = candidates(heads[k], reachDistance);
        }
        return prepared;
    }

    /** As above, reusing {@code prepared} (from {@link #prepareCandidates}, same heads and reach) when non-null. */
    public static Optional<Plan> plan(Head[] heads, Reach[] reach, float[] view, Need need, int pairGap,
                                      boolean exactPairGap, int maceFrom, int maceTo, double reachDistance, Plan keep,
                                      float[][][] prepared) {
        int count = Math.min(heads.length, reach.length);
        if (count == 0) {
            return Optional.empty();
        }
        float[][][] points = new float[count][][];
        for (int k = 0; k < count; k++) {
            // The view and stun-pair slots below are written per call, so a prepared row is copied first.
            points[k] = prepared != null && k < prepared.length && prepared[k] != null
                    && prepared[k].length == CANDIDATE_COUNT
                    ? java.util.Arrays.copyOf(prepared[k], CANDIDATE_COUNT)
                    : candidates(heads[k], reachDistance);
            if (hits(heads[k], view, reachDistance, 0.0)) {
                points[k][VIEW_POINT] = view.clone();
            }
            if (need == Need.STUN) {
                int lastGap = exactPairGap ? Math.max(1, pairGap) : MAX_PAIR_GAP;
                for (int gap = Math.max(1, pairGap); gap <= Math.min(MAX_PAIR_GAP, lastGap) && k + gap < count; gap++) {
                    Head axe = heads[k];
                    Head mace = heads[k + gap];
                    Vec3d shared = StunSlamEngine.dualClickAimPoint(axe.eye(), axe.eye(), axe.box(),
                            mace.eye(), mace.box(), axe.box(), reachDistance - REACH_SLACK);
                    if (shared != null) {
                        points[k][VIEW_POINT + gap] = angles(axe.eye(), shared);
                    }
                }
            }
        }
        Context ctx = new Context(heads, reach, points, view, need, Math.max(1, pairGap), exactPairGap, maceFrom, maceTo,
                reachDistance);

        // After the axe, a ray that still hits needs no recentering. The body grid can be several
        // degrees away even though the current ray is valid, especially at the end of a fast fall.
        if (need == Need.MACE && maceFrom <= 0 && maceTo >= 0) {
            Scored held = ctx.evaluate(0, VIEW_POINT, 0.0);
            if (held != null) {
                return Optional.of(held.plan);
            }
        }

        if (keep != null && keep.head() >= 0 && keep.head() < count && keep.point() >= 0 && keep.point() < CANDIDATE_COUNT) {
            Scored kept = ctx.evaluate(keep.head(), keep.point(), LOCK_SLACK);
            if (kept != null && kept.kind == keep.kind()) {
                return Optional.of(kept.plan);
            }
        }
        Scored best = null;
        for (int k = 0; k < count; k++) {
            for (int p = 0; p < CANDIDATE_COUNT; p++) {
                Scored s = ctx.evaluate(k, p, 0.0);
                if (s != null && (best == null || s.betterThan(best))) {
                    best = s;
                }
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.plan);
    }

    /** Degrees the rotation can cover in {@code ticks} starting from rest (between two strikes). */
    static double restReach(double ticks) {
        if (ticks <= 0.0) {
            return 0.0;
        }
        return ticks < 1.0 ? 36.0 * ticks * ticks : 62.0 * ticks - 26.0;
    }

    static boolean legReachable(float yaw0, float pitch0, float yaw1, float pitch1, int ticks) {
        double d = restReach(ticks);
        return Math.abs(MathHelper.wrapDegrees(yaw1 - yaw0)) <= d && Math.abs(pitch1 - pitch0) <= 0.9 * d;
    }

    /** {yaw, pitch} per grid point inside the head's box, or null where the ray would enter out of reach. */
    static float[][] candidates(Head head, double reachDistance) {
        float[][] out = new float[CANDIDATE_COUNT][];
        Box b = head.box();
        Vec3d e = head.eye();
        if (b == null || e == null) {
            return out;
        }
        double ix = Math.min(INSET, b.getLengthX() * 0.4);
        double iy = Math.min(INSET, b.getLengthY() * 0.4);
        double iz = Math.min(INSET, b.getLengthZ() * 0.4);
        double minX = b.minX + ix, maxX = b.maxX - ix, minY = b.minY + iy, maxY = b.maxY - iy, minZ = b.minZ + iz, maxZ = b.maxZ - iz;
        int p = 0;
        for (double fx : XS) {
            for (double fy : YS) {
                for (double fz : XS) {
                    Vec3d point = new Vec3d(minX + (maxX - minX) * fx, minY + (maxY - minY) * fy, minZ + (maxZ - minZ) * fz);
                    Vec3d to = point.subtract(e);
                    double length = to.length();
                    if (length > 1.0e-4) {
                        Vec3d dir = to.multiply(1.0 / length);
                        Optional<Vec3d> entry = b.contains(e) ? Optional.of(e) : b.raycast(e, e.add(dir.multiply(reachDistance)));
                        if (entry.isPresent() && entry.get().distanceTo(e) <= reachDistance - REACH_SLACK
                                && (head.visible() == null || head.visible().test(entry.get()))) {
                            double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
                            float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                            float pitch = (float) -Math.toDegrees(Math.atan2(to.y, horizontal));
                            out[p] = new float[]{yaw, pitch};
                        }
                    }
                    p++;
                }
            }
        }
        return out;
    }

    private static float[] angles(Vec3d eye, Vec3d point) {
        Vec3d to = point.subtract(eye);
        return new float[]{(float) Math.toDegrees(Math.atan2(-to.x, to.z)),
                (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)))};
    }

    private static boolean hits(Head head, float[] angles, double reachDistance, double inset) {
        if (head.eye() == null || head.box() == null || angles == null) {
            return false;
        }
        Box box = head.box().contract(inset);
        Vec3d end = head.eye().add(StunSlamEngine.lookVector(angles[0], angles[1])
                .multiply(reachDistance - REACH_SLACK));
        return box.contains(head.eye()) || box.raycast(head.eye(), end).isPresent();
    }

    private record Scored(int completes, double turn, Kind kind, Plan plan) {
        boolean betterThan(Scored other) {
            return completes != other.completes ? completes < other.completes : turn < other.turn;
        }
    }

    /** 0 at the body's centre, 1 at a corner of the candidate grid. */
    static double centerOffset(int p) {
        if (p >= POINTS) {
            return 0.0;
        }
        int perX = YS.length * XS.length;
        int ix = p / perX;
        int iy = (p % perX) / XS.length;
        int iz = p % XS.length;
        double dx = (XS[ix] - 0.5) / 0.3;
        double dy = (YS[iy] - 0.5) / 0.4;
        double dz = (XS[iz] - 0.5) / 0.3;
        return Math.min(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz) / Math.sqrt(3.0));
    }

    private record Context(Head[] heads, Reach[] reach, float[][][] points, float[] view, Need need,
                           int pairGap, boolean exactPairGap, int maceFrom, int maceTo, double reachDistance) {
        Scored evaluate(int k, int p, double slack) {
            float[] c = points[k][p];
            if (c == null) {
                return null;
            }
            double dYaw = MathHelper.wrapDegrees(c[0] - view[0]);
            double dPitch = c[1] - view[1];
            if (!reach[k].allows(dYaw, dPitch, slack)) {
                return null;
            }
            double turn = Math.hypot(dYaw, dPitch) + NEAR_WEIGHT * nearness(heads[k], p);
            boolean covered = heads[k].shieldCovers();
            return switch (need) {
                case SMASH -> scored(k, turn, Kind.SMASH, k, p, c);
                case MACE -> k >= maceFrom && k <= maceTo ? scored(k, turn, Kind.MACE, k, p, c) : null;
                case BACKSTAB -> covered ? null : scored(k, turn, Kind.SMASH, k, p, c);
                case AXE -> covered ? scored(k, turn, Kind.AXE, k, p, c) : null;
                case STUN -> covered ? pair(k, p, c, turn) : scored(k, turn, Kind.SMASH, k, p, c);
            };
        }

        /** Axe at head k on point c, if some mace point pairGap..6 heads later is reachable from it. */
        private Scored pair(int k, int p, float[] c, double turn) {
            int last = Math.min(k + (exactPairGap ? pairGap : MAX_PAIR_GAP), points.length - 1);
            for (int j = k + pairGap; j <= last; j++) {
                // A direction shared by the two translated eye positions is preferable to an axe
                // point that needs another large turn in the sole tick left for the mace.
                if (hits(heads[j], c, reachDistance, 0.04)) {
                    return scored(j, turn, Kind.AXE, k, p, c);
                }
                double followUpTurn = Double.POSITIVE_INFINITY;
                for (float[] c2 : points[j]) {
                    if (c2 != null && legReachable(c[0], c[1], c2[0], c2[1], j - k)) {
                        followUpTurn = Math.min(followUpTurn,
                                Math.hypot(MathHelper.wrapDegrees(c2[0] - c[0]), c2[1] - c[1]));
                    }
                }
                if (Double.isFinite(followUpTurn)) {
                    return scored(j, turn + 3.0 * followUpTurn, Kind.AXE, k, p, c);
                }
            }
            return null;
        }

        private static Scored scored(int completes, double turn, Kind kind, int k, int p, float[] c) {
            return new Scored(completes, turn, kind, new Plan(k, p, kind, c[0], c[1]));
        }
    }
}
