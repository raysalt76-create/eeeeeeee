package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;
import java.util.function.BiPredicate;

/**
 * The geometry and rest-pose bookkeeping of the crystal modules' Headbob, shared by AutoCrystal and
 * AutoHitCrystal. It only computes where the look should go; the turn itself is always requested from
 * {@link RotationManager} through {@link SmoothAim}, and every break is still gated by the live ray on
 * the tick.
 *
 * <p><b>Shape.</b> The bob is a diagonal in angle space through the rest pose (where the look was when
 * the bob started): up and toward the hand's side as it rises, back down the same way, with a small arc
 * on the way down so the return is not a straight retrace. {@link #RIGHTY} rises toward the right
 * (a line from left-bottom to right-top), {@link #LEFTY} the mirror. The depth is a fraction of the
 * window the live ray hits the crystal in along that diagonal: 0 turns only as far as the first
 * degree that reaches the crystal, higher values go further into it.</p>
 *
 * <p><b>Rest.</b> Without Silent Aim the rest pose is the pose the bob started from. With Silent Aim the
 * camera is the user's own and the wire look is the only thing that bobs, so rest is the live camera
 * pose: the return always ends where the player is looking, and releasing the owner never snaps.</p>
 */
public final class CrystalHeadbob {

    public static final String RIGHTY = "Righty";
    public static final String LEFTY = "Lefty";

    /** Degrees of yaw per degree of pitch along the diagonal. */
    public static final double TILT = 0.4;
    /** Furthest the diagonal is searched, degrees of pitch above rest. */
    private static final double MAX_UP = 80.0;
    private static final double SCAN_STEP = 1.0;
    /** The goal stays this many degrees inside the window edges so it settles on the crystal. */
    private static final double EDGE_MARGIN = 1.5;
    /** Largest share of the window the deepest setting uses. */
    private static final double MAX_DEPTH = 0.85;
    /** Side arc of the return, as a share of the travel, and its cap in degrees. */
    private static final double LOOP_SHARE = 0.14;
    private static final double LOOP_CAP = 1.8;
    private static final double[] SAMPLES = {0.15, 0.5, 0.85};

    /** The diagonal found by {@link #findWindow}: yaw per pitch degree, and the pitch-degrees window. */
    public record Window(double ratio, double enter, double exit) {
    }

    private final Random random = new Random();
    private boolean active;
    private boolean silent;
    private float startYaw;
    private float startPitch;
    private int sign = 1;
    private double amount01 = 0.15;
    private boolean variation;
    private double depthFraction;
    /** Per-bob tilt multiplier and rest offset, so no two bobs trace the same angles. */
    private double tiltScale = 1.0;
    private float restOffsetYaw;
    private float restOffsetPitch;

    // ------------------------------------------------------------------ turning

    /**
     * Requests a turn to {@code yaw}/{@code pitch} through the shared controller in WIND mode: the same
     * GCD-quantised, frame-rate core as SmoothAim, plus a wandering lateral offset and per-axis speed
     * asymmetry, so the yaw and pitch velocities of a bob are not locked to one ratio and the path curves.
     * A plain SMOOTH turn along the fixed diagonal produced the same straight, constant-ratio pitch swing
     * on every crystal, which aim heuristics read as a pattern.
     *
     * @return true while {@code owner} holds the aim channel
     */
    public static boolean aim(Object owner, float yaw, float pitch, double degPerSec, boolean silent) {
        float aimPitch = MathHelper.clamp(pitch, -89.9f, 89.9f);
        Vec3d direction = RotationMath.direction(yaw, aimPitch).multiply(64.0);
        MinecraftClient mc = MinecraftClient.getInstance();
        double humanization = 0.35;
        RotationManager.setRotationSupplier(owner, RotationManager.Priority.HIGH,
                () -> mc.player == null ? null : mc.player.getEyePos().add(direction),
                SmoothAim.strength(degPerSec, silent), RotationManager.RotationMode.WIND, humanization, silent, false);
        return RotationManager.getCurrentOwner() == owner;
    }

    // ------------------------------------------------------------------ pure geometry

    /** +1 for righty (the look rises toward the right), -1 for lefty. */
    public static int handSign(String hand) {
        return LEFTY.equalsIgnoreCase(hand) ? -1 : 1;
    }

    /** The look {@code up} degrees along the diagonal from rest. */
    public static float[] along(float restYaw, float restPitch, int sign, double ratio, double up) {
        return new float[]{(float) (restYaw + sign * ratio * up), (float) (restPitch - up)};
    }

    /**
     * The first diagonal that has a window the look hits the target in, and that window, from
     * {@code hits(yaw, pitch)}. The hand's own diagonal is tried first, then half the tilt, then straight
     * up, so the bob keeps its tilt wherever the geometry allows. Null when no diagonal reaches it.
     */
    public static Window findWindow(float restYaw, float restPitch, int sign, BiPredicate<Float, Float> hits) {
        return findWindow(restYaw, restPitch, sign, 1.0, hits);
    }

    /** {@link #findWindow} with the tilt scaled by {@code tiltScale} (one bob's own lean). */
    public static Window findWindow(float restYaw, float restPitch, int sign, double tiltScale,
                                    BiPredicate<Float, Float> hits) {
        double limit = Math.min(MAX_UP, restPitch + 89.0);
        double tilt = TILT * tiltScale;
        for (double ratio : new double[]{tilt, tilt * 0.5, 0.0}) {
            double enter = Double.NaN;
            double exit = Double.NaN;
            for (double up = 0.0; up <= limit; up += SCAN_STEP) {
                float[] look = along(restYaw, restPitch, sign, ratio, up);
                if (hits.test(look[0], look[1])) {
                    if (Double.isNaN(enter)) enter = up;
                    exit = up;
                } else if (!Double.isNaN(enter)) {
                    break;
                }
            }
            if (!Double.isNaN(enter)) return new Window(ratio, enter, exit);
        }
        return null;
    }

    /** The goal inside {@code window} at {@code depth} (0 = just inside the first hit, 1 = the far edge). */
    public static float[] goalIn(Window window, float restYaw, float restPitch, int sign, double depth) {
        double lo = window.enter() + EDGE_MARGIN;
        double hi = window.exit() - EDGE_MARGIN;
        double up;
        if (hi <= lo) {
            up = (window.enter() + window.exit()) * 0.5;
        } else {
            up = lo + (hi - lo) * MathHelper.clamp(depth, 0.0, 1.0);
        }
        return along(restYaw, restPitch, sign, window.ratio(), up);
    }

    /**
     * Depth for one bob. {@code amount01} 0..1 maps to 0..{@link #MAX_DEPTH}; with {@code variation} on it
     * is scaled by a log-normal factor (skewed, fat-tailed, never a flat random range) and bounded.
     */
    public static double depthFor(double amount01, boolean variation, Random random) {
        double depth = MathHelper.clamp(amount01, 0.0, 1.0) * MAX_DEPTH;
        if (variation) {
            double factor = Math.exp(0.3 * random.nextGaussian());
            depth *= MathHelper.clamp(factor, 0.55, 1.6);
        }
        return MathHelper.clamp(depth, 0.0, 0.95);
    }

    /**
     * Where to aim on the way back to rest from the look {@code curYaw}/{@code curPitch}: a point a bit
     * ahead on a gently curved path that is exactly the rest pose at the end, so the return is an arc and
     * not a retrace of the way up.
     *
     * @param peakUp pitch degrees the bob rose above rest (its travel); 0 returns rest itself
     */
    public static float[] returnWaypoint(float restYaw, float restPitch, float curYaw, float curPitch,
                                         int sign, double peakUp) {
        double up = restPitch - curPitch;
        if (peakUp < 0.5 || up <= 0.25) return new float[]{restYaw, restPitch};
        double progress = MathHelper.clamp(up / peakUp, 0.0, 1.0);
        double ahead = Math.max(0.0, progress - 0.45);
        double ratio = Math.abs(MathHelper.wrapDegrees(curYaw - restYaw)) / Math.max(up, 0.001);
        ratio = MathHelper.clamp(ratio, 0.0, 1.0);
        double aheadUp = peakUp * ahead;
        double loop = Math.min(LOOP_CAP, peakUp * LOOP_SHARE) * Math.sin(Math.PI * ahead);
        // Perpendicular to the diagonal, on the side that opens the arc away from the hand.
        double yaw = restYaw + sign * ratio * aheadUp - sign * loop;
        double pitch = restPitch - aheadUp + loop * ratio;
        return new float[]{(float) yaw, (float) pitch};
    }

    // ------------------------------------------------------------------ session

    /** Updates the user's choices; called each tick so a setting change applies to the next bob. */
    public void configure(String hand, double headMovement0to100, boolean heightVariation) {
        sign = handSign(hand);
        amount01 = MathHelper.clamp(headMovement0to100 / 100.0, 0.0, 1.0);
        variation = heightVariation;
    }

    public boolean isActive() {
        return active;
    }

    /** Starts a bob from the current look, once; a bob already running keeps its rest pose. */
    public void begin(boolean silentAim) {
        silent = silentAim;
        if (active) return;
        active = true;
        startYaw = RotationManager.getFinalYaw();
        startPitch = RotationManager.getFinalPitch();
        rollCycle();
    }

    /** Re-rolls the depth (Height Variation), the lean and the return point for the next rise. */
    public void nextCycle() {
        rollCycle();
    }

    private void rollCycle() {
        depthFraction = depthFor(amount01, variation, random);
        tiltScale = MathHelper.clamp(Math.exp(0.22 * random.nextGaussian()), 0.65, 1.45);
        // A hand never comes back to the exact same angles: a small, bounded offset per bob.
        restOffsetYaw = (float) MathHelper.clamp(random.nextGaussian() * 0.45, -1.2, 1.2);
        restOffsetPitch = (float) MathHelper.clamp(random.nextGaussian() * 0.35, -0.9, 0.9);
    }

    public void end() {
        active = false;
    }

    public float restYaw() {
        if (silent) return RotationManager.getVisualYaw();
        return startYaw + restOffsetYaw;
    }

    public float restPitch() {
        if (silent) return RotationManager.getVisualPitch();
        return MathHelper.clamp(startPitch + restOffsetPitch, -89.9f, 89.9f);
    }

    /** Degrees the applied look is away from rest, whichever axis is further. */
    public double displacement() {
        double dy = Math.abs(MathHelper.wrapDegrees(RotationManager.getFinalYaw() - restYaw()));
        double dp = Math.abs(RotationManager.getFinalPitch() - restPitch());
        return Math.max(dy, dp);
    }

    public boolean displaced(double tolerance) {
        return active && displacement() > tolerance;
    }

    /** Pitch degrees above rest the applied look is now (0 at or below rest). */
    public double risen() {
        return Math.max(0.0, restPitch() - RotationManager.getFinalPitch());
    }

    /**
     * The look that reaches {@code crystal} (or, with no crystal yet, {@code area}): the diagonal's goal
     * at this bob's depth, else a sampled point of the hitbox. Null when nothing is reachable.
     */
    public float[] upGoal(EndCrystalEntity crystal, Box area, double reach) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;
        Vec3d eye = mc.player.getEyePos();
        float yaw0 = restYaw();
        float pitch0 = restPitch();
        BiPredicate<Float, Float> hits = (yaw, pitch) -> reaches(crystal, area, eye, yaw, pitch, reach);
        Window window = findWindow(yaw0, pitch0, sign, tiltScale, hits);
        if (window != null) return goalIn(window, yaw0, pitch0, sign, depthFraction);
        return sampledGoal(crystal, area == null && crystal != null ? crystal.getBoundingBox() : area, eye, reach);
    }

    private static boolean reaches(EndCrystalEntity crystal, Box area, Vec3d eye, float yaw, float pitch,
                                   double reach) {
        if (crystal != null) {
            HitResult result = RotationManager.getCrosshairTarget(yaw, pitch, reach);
            return result instanceof EntityHitResult hit && hit.getEntity() == crystal;
        }
        if (area == null) return false;
        Vec3d end = eye.add(RotationMath.direction(yaw, pitch).multiply(reach));
        return area.raycast(eye, end).isPresent();
    }

    /** Fallback: of a grid of points through the hitbox, the one that hits and needs the least turn. */
    private float[] sampledGoal(EndCrystalEntity crystal, Box box, Vec3d eye, double reach) {
        if (box == null) return null;
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        float[] best = null;
        double bestTurn = Double.MAX_VALUE;
        for (double fx : SAMPLES) {
            for (double fy : SAMPLES) {
                for (double fz : SAMPLES) {
                    Vec3d point = new Vec3d(box.minX + box.getLengthX() * fx, box.minY + box.getLengthY() * fy,
                            box.minZ + box.getLengthZ() * fz);
                    if (eye.squaredDistanceTo(point) > reach * reach) continue;
                    float[] angles = RotationMath.anglesTo(eye, point, yaw);
                    if (!reaches(crystal, box, eye, angles[0], angles[1], reach)) continue;
                    double turn = Math.abs(MathHelper.wrapDegrees(angles[0] - yaw)) * 4.0
                            + Math.abs(angles[1] - pitch);
                    if (turn < bestTurn) {
                        bestTurn = turn;
                        best = angles;
                    }
                }
            }
        }
        return best;
    }

    /** Where to aim to come back to rest from the applied look, given how high this bob went. */
    public float[] downGoal(double peakUp) {
        return returnWaypoint(restYaw(), restPitch(), RotationManager.getFinalYaw(),
                RotationManager.getFinalPitch(), sign, peakUp);
    }

    /** The applied look is at rest, within {@code tolerance} degrees on both axes. */
    public boolean atRest(double tolerance) {
        return displacement() <= tolerance;
    }
}
