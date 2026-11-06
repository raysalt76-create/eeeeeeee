package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.function.Supplier;

/**
 * Frame-rate aiming for the anchor and crystal modules.
 *
 * <p>A module hands {@link RotationManager} an aim point once; the shared controller then turns the
 * look every render frame (GCD-quantised, with a small sway), so the camera moves
 * smoothly instead of in one jump per 20 Hz tick. The module still decides every click on the tick,
 * from the live ray of the look that frames produced: reaching the target is never assumed from an
 * angle threshold.</p>
 *
 * <p>The speed is held under 1450 degrees a second (72.5 a tick, below Intave's 100 degree
 * RotationModuloResetHeuristic) after the silent-mode speed-up the controller applies.</p>
 */
public final class SmoothAim {

    /** Fastest look speed, degrees a second: 72.5 a tick. */
    public static final double MAX_DEG_PER_SEC = 1450.0;
    /** The controller multiplies a silent request's speed by this. */
    private static final double SILENT_SPEEDUP = 1.5;
    /** Micro-noise floor handed to the controller (tremor, not aim spread). */
    private static final double MIN_HUMANIZATION = 0.25;
    private static final double[] FACE_GRID = {0.2, 0.5, 0.8};

    private SmoothAim() {
    }

    /** Controller strength for a look speed in degrees a second; the controller turns at strength x 60. */
    public static double strength(double degPerSec, boolean silent) {
        double divisor = silent ? SILENT_SPEEDUP : 1.0;
        double rate = Math.min(degPerSec, MAX_DEG_PER_SEC) / divisor;
        return Math.max(60.0, rate) / 60.0;
    }

    /**
     * Turns the look toward the world point {@code point} supplies, at up to {@code degPerSec}.
     *
     * @return true while {@code owner} holds the aim channel
     */
    public static boolean aimPoint(Object owner, Supplier<Vec3d> point, double degPerSec, boolean silent) {
        RotationManager.setRotationSupplier(owner, RotationManager.Priority.HIGH, point,
                strength(degPerSec, silent), RotationManager.RotationMode.SMOOTH, MIN_HUMANIZATION, silent, false);
        return RotationManager.getCurrentOwner() == owner;
    }

    /**
     * Turns the look to the direction {@code yaw}/{@code pitch}. The point is read from the live eye
     * every frame, so it cannot drift with the eye while falling.
     */
    public static boolean aimAngles(Object owner, float yaw, float pitch, double degPerSec, boolean silent) {
        float aimPitch = MathHelper.clamp(pitch, -89.9f, 89.9f);
        Vec3d direction = RotationMath.direction(yaw, aimPitch).multiply(64.0);
        MinecraftClient mc = MinecraftClient.getInstance();
        return aimPoint(owner, () -> mc.player == null ? null : mc.player.getEyePos().add(direction), degPerSec,
                silent);
    }

    /** Smallest turn, degrees, from the look {@code yaw}/{@code pitch} to the direction of {@code point}. */
    public static double turnTo(Vec3d eye, Vec3d point, float yaw, float pitch) {
        float[] goal = RotationMath.anglesTo(eye, point, yaw);
        double dy = MathHelper.wrapDegrees(goal[0] - yaw);
        double dp = goal[1] - pitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    /**
     * The point of a face of {@code pos} whose ray from the eye hits that face of that block first and
     * needs the least turn from the current look. Faces are tried only on the eye's side, on a grid
     * kept off the edges so the turn settles inside the face. Null when no face of the block is
     * reachable (then the caller falls back to its own point).
     *
     * @param side only this face, or null for every face facing the eye
     */
    public static Vec3d nearestFacePoint(MinecraftClient mc, BlockPos pos, Direction side) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return null;
        Vec3d eye = player.getEyePos();
        double reach = player.getBlockInteractionRange() - 0.05;
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        Vec3d best = null;
        double bestTurn = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (side != null && face != side) continue;
            Vec3d center = new Vec3d(pos.getX() + 0.5 + face.getOffsetX() * 0.5,
                    pos.getY() + 0.5 + face.getOffsetY() * 0.5, pos.getZ() + 0.5 + face.getOffsetZ() * 0.5);
            Vec3d toEye = eye.subtract(center);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.0) {
                continue;
            }
            for (double u : FACE_GRID) {
                for (double v : FACE_GRID) {
                    Vec3d point = facePoint(pos, face, u, v);
                    if (eye.squaredDistanceTo(point) > reach * reach) continue;
                    if (!firstHitIs(mc, player, eye, point, pos, face)) continue;
                    double turn = turnTo(eye, point, yaw, pitch);
                    if (turn < bestTurn) {
                        bestTurn = turn;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    private static Vec3d facePoint(BlockPos pos, Direction face, double u, double v) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        switch (face.getAxis()) {
            case X -> {
                x += face.getOffsetX() * 0.5;
                y += u - 0.5;
                z += v - 0.5;
            }
            case Y -> {
                y += face.getOffsetY() * 0.5;
                x += u - 0.5;
                z += v - 0.5;
            }
            default -> {
                z += face.getOffsetZ() * 0.5;
                x += u - 0.5;
                y += v - 0.5;
            }
        }
        return new Vec3d(x, y, z);
    }

    /** The vanilla interaction ray from {@code eye} through {@code point} first touches {@code face} of {@code pos}. */
    private static boolean firstHitIs(MinecraftClient mc, ClientPlayerEntity player, Vec3d eye, Vec3d point,
                                     BlockPos pos, Direction face) {
        Vec3d through = point.add(point.subtract(eye).normalize().multiply(0.25));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, through, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && hit.getSide() == face;
    }
}
