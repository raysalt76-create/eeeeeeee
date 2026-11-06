package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Shared swept-reach + predictive geometry for the AutoMace / AutoElytra divebomb combo.
 *
 * <p>Pure, stateless helpers. All distances are in blocks, all "ticks" are client ticks.
 * The motivation (see /tmp/macelytra_diag.json): a high-speed divebomb crosses the entire
 * ~0.35-block usable reach margin BETWEEN two ticks, so an instantaneous point-distance gate
 * frequently never samples an in-range tick. These helpers let the caller fire on the tick
 * where the swept eye segment over the upcoming tick comes within reach of the (lead-corrected)
 * target hitbox, which removes the between-tick overshoot miss.
 */
public final class CombatPrediction {

    /** Number of sub-steps used to sample the eye segment in {@link #closestApproachOverTick}. */
    private static final int SEGMENT_SUBSTEPS = 6;

    private CombatPrediction() {
    }

    /**
     * Per-tick displacement (velocity magnitude) of the player: the hypot of this tick's position
     * delta (current position minus previous-tick position). Blocks/tick.
     */
    public static double perTickDisplacement(PlayerEntity p) {
        if (p == null) {
            return 0.0;
        }
        double dx = p.getX() - p.lastX;
        double dy = p.getY() - p.lastY;
        double dz = p.getZ() - p.lastZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Per-tick velocity VECTOR: this tick's observed position delta (pos - lastPos). This is the SAME
     * observed-motion basis {@link #predictBox} uses to lead the target, so a swept-reach test built
     * from {@code eyeNow + perTickVelocityVec(player)} and a box advanced by
     * {@code perTickVelocityVec(target)} shares ONE motion model — no getVelocity()/pos-lastPos
     * mismatch between the two halves of the segment-vs-box test. Blocks/tick.
     */
    public static Vec3d perTickVelocityVec(PlayerEntity p) {
        if (p == null) {
            return Vec3d.ZERO;
        }
        return new Vec3d(p.getX() - p.lastX, p.getY() - p.lastY, p.getZ() - p.lastZ);
    }

    /**
     * The target's bounding box offset by its per-tick velocity, lead {@code leadTicks} into the
     * future. Velocity is taken from the entity's this-tick position delta (pos - lastPos), which is
     * the actual observed motion rather than the often-stale {@code getVelocity()}.
     */
    public static Box predictBox(Entity target, double leadTicks) {
        if (target == null) {
            return null;
        }
        Box box = target.getBoundingBox();
        if (leadTicks == 0.0) {
            return box;
        }
        double vx = target.getX() - target.lastX;
        double vy = target.getY() - target.lastY;
        double vz = target.getZ() - target.lastZ;
        return box.offset(vx * leadTicks, vy * leadTicks, vz * leadTicks);
    }

    /**
     * Swept-reach test (co-swept): minimum distance from the eye segment {@code eyeNow -> eyeNext} to
     * the target box as BOTH advance over the tick. The player point at fraction {@code t}
     * along the eye segment is tested against {@code boxNow} advanced by {@code targetVel * t}, so the
     * target hitbox moves TOGETHER with the player over the tick instead of being frozen at a fixed
     * lead. This removes the systematic over-lead bias against a fast laterally-strafing target (the
     * old fixed +1.0 lead pushed the box out from under the segment), recovering marginal smashes on
     * fast lateral dives. {@code targetVel} should come from {@link #perTickVelocityVec} so both halves
     * of the test share one motion model. The box is held fixed; the player point is offset by
     * {@code -targetVel * t} instead (distance-equivalent, but allocation-free).
     */
    public static double closestApproachOverTick(Vec3d eyeNow, Vec3d eyeNext, Box boxNow, Vec3d targetVel) {
        if (boxNow == null || eyeNow == null) {
            return Double.MAX_VALUE;
        }
        if (eyeNext == null) {
            return pointToBoxDistance(eyeNow, boxNow);
        }
        Vec3d vel = (targetVel == null) ? Vec3d.ZERO : targetVel;

        double dx = eyeNext.x - eyeNow.x;
        double dy = eyeNext.y - eyeNow.y;
        double dz = eyeNext.z - eyeNow.z;

        double min = Double.MAX_VALUE;
        for (int i = 0; i <= SEGMENT_SUBSTEPS; i++) {
            double t = (double) i / SEGMENT_SUBSTEPS;
            // Distance from point P to (box offset by V*t) == distance from (P - V*t) to box, so we
            // shift the sampled player point instead of allocating a fresh Box each sub-step.
            double px = eyeNow.x + dx * t - vel.x * t;
            double py = eyeNow.y + dy * t - vel.y * t;
            double pz = eyeNow.z + dz * t - vel.z * t;
            double d = pointToBoxDistance(px, py, pz, boxNow);
            if (d < min) {
                min = d;
            }
            if (min <= 0.0) {
                return 0.0;
            }
        }
        return min;
    }

    /**
     * Speed-scaled grab radius. Widens {@code baseRange} toward {@code swingRange} as the player's
     * per-tick displacement grows, so a fast dive grabs earlier and never overshoots in one tick;
     * never exceeds {@code swingRange}.
     *
     * <pre>min(swingRange, baseRange + clamp(displacement * 0.5, 0, swingRange - baseRange))</pre>
     */
    public static double speedScaledRange(double baseRange, double swingRange, double displacement) {
        double headroom = swingRange - baseRange;
        if (headroom <= 0.0) {
            return Math.min(swingRange, baseRange);
        }
        double bonus = MathHelper.clamp(displacement * 0.5, 0.0, headroom);
        return Math.min(swingRange, baseRange + bonus);
    }

    // ---- internal point-to-box helpers ----

    private static double pointToBoxDistance(Vec3d p, Box box) {
        return pointToBoxDistance(p.x, p.y, p.z, box);
    }

    /** Euclidean distance from a point to the nearest point of the (axis-aligned) box. 0 if inside. */
    private static double pointToBoxDistance(double px, double py, double pz, Box box) {
        double cx = clamp(px, box.minX, box.maxX);
        double cy = clamp(py, box.minY, box.maxY);
        double cz = clamp(pz, box.minZ, box.maxZ);
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
