package interstellarTikiTiks.apexx.client.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * PlaceResolver — the shared "where do I click to affect block {@code X}?" geometry.
 *
 * <p>Until now every placement/pickup module in the client relied on the vanilla
 * crosshair: it could only act on the block it was already looking at. Nothing
 * could take an arbitrary {@link BlockPos} — one the player is NOT pointing at —
 * and work out how to place a block there. AutoAnchor and the former crystal modules each grew
 * half of this logic privately; this is the consolidated, reusable core.
 *
 * <p>Everything here is pure geometry + raytracing against the live world. It
 * never rotates, selects a slot, or clicks — a placement state machine consumes
 * {@link PlaceSolution} and drives those. Two responsibilities:
 *
 * <ul>
 *   <li>{@link #resolvePlacement} — for an empty target, find the best solid
 *       neighbour + face + hit-vector to click so the new block lands in it,
 *       preferring the face that needs the LEAST camera turn (most legit);</li>
 *   <li>{@link #isVisible} / {@link #canReach} — the reach + line-of-sight checks
 *       every acting module already re-implements (see AutoDrain#canSeeBlock).</li>
 * </ul>
 */
public final class PlaceResolver {

    private PlaceResolver() {
    }

    /**
     * Resolve how to place a block so it occupies {@code target}.
     *
     * <p>Scans the six neighbours of {@code target}; for each solid, reachable,
     * visible neighbour it forms the face pointing back at the target and keeps
     * the candidate that requires the smallest rotation from the player's current
     * look (so the resulting turn is as small — and as human — as possible).
     *
     * @param mc     the client
     * @param target the (empty / replaceable) position the block should end up in
     * @param reach  max eye→hit-vector distance allowed (blocks)
     * @return the best {@link PlaceSolution}, or {@code null} if the target is
     *         occupied or no neighbouring face is solid, reachable and visible
     */
    public static PlaceSolution resolvePlacement(MinecraftClient mc, BlockPos target, double reach) {
        if (mc.player == null || mc.world == null || target == null) {
            return null;
        }

        // Target must be free for a block to occupy it (air, water, or any other
        // replaceable state). If something solid is already there, nothing to do.
        if (!mc.world.getBlockState(target).isReplaceable()) {
            return null;
        }

        Vec3d eye = mc.player.getEyePos();
        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();

        PlaceSolution best = null;
        double bestTurn = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos against = target.offset(dir);
            BlockState againstState = mc.world.getBlockState(against);

            // Need a solid block to click against — a replaceable/air neighbour has
            // no clickable face (and would itself just be another empty space).
            if (againstState.isAir() || againstState.isReplaceable()) {
                continue;
            }

            // The face of `against` that points at the target is the opposite of the
            // direction we walked to reach it. Clicking it drops the block into
            // `against.offset(side)`, which is exactly `target`.
            Direction side = dir.getOpposite();
            // Inset hit position slightly inside the block face (0.46) so floating point
            // raycasts on the server are guaranteed to collide with 'against' rather than
            // clipping into the neighboring boundary or missing.
            Vec3d hitVec = Vec3d.ofCenter(against).add(
                    side.getOffsetX() * 0.46,
                    side.getOffsetY() * 0.46,
                    side.getOffsetZ() * 0.46);

            double effectiveReach = Math.min(reach, 4.25);
            if (eye.squaredDistanceTo(hitVec) > effectiveReach * effectiveReach) {
                continue;
            }
            if (!faceReachableByRay(mc, against, hitVec, eye)) {
                continue;
            }

            // Prefer the face needing the least camera movement — smallest combined
            // yaw+pitch delta from where the player is already looking.
            float[] needed = RotationManager.calculateRotationsToPos(hitVec, curYaw);
            double turn = Math.abs(MathHelper.wrapDegrees(needed[0] - curYaw))
                    + Math.abs(needed[1] - curPitch);
            if (turn < bestTurn) {
                bestTurn = turn;
                best = new PlaceSolution(against, side, hitVec);
            }
        }

        return best;
    }

    /**
     * Finds the best visible, reachable face on an existing block (e.g. existing Anchor or Obsidian).
     */
    public static BlockHitResult resolveExistingBlockHit(MinecraftClient mc, BlockPos pos, double reach) {
        if (mc.player == null || mc.world == null || pos == null) return null;
        Vec3d eye = mc.player.getEyePos();
        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        double effectiveReach = Math.min(reach, 4.25);

        BlockHitResult best = null;
        double bestTurn = Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            // The face must be exposed (neighbor is air, fluid, or transparent)
            if (!neighborState.isAir() && !neighborState.isReplaceable() && neighborState.isOpaqueFullCube()) {
                continue;
            }

            Vec3d hitVec = Vec3d.ofCenter(pos).add(
                    side.getOffsetX() * 0.46,
                    side.getOffsetY() * 0.46,
                    side.getOffsetZ() * 0.46);

            if (eye.squaredDistanceTo(hitVec) > effectiveReach * effectiveReach) {
                continue;
            }
            if (!faceReachableByRay(mc, pos, hitVec, eye)) {
                continue;
            }

            float[] needed = RotationManager.calculateRotationsToPos(hitVec, curYaw);
            double turn = Math.abs(MathHelper.wrapDegrees(needed[0] - curYaw))
                    + Math.abs(needed[1] - curPitch);
            if (turn < bestTurn) {
                bestTurn = turn;
                best = new BlockHitResult(hitVec, side, pos, false);
            }
        }

        return best;
    }

    /**
     * True if an eye→{@code hitVec} ray actually lands on {@code against} (i.e. the
     * face is exposed, not occluded by another block). Fluids are ignored so water
     * in the line of sight never blocks a place.
     */
    private static boolean faceReachableByRay(MinecraftClient mc, BlockPos against, Vec3d hitVec, Vec3d eye) {
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                eye, hitVec,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(against);
    }

    /**
     * Line-of-sight to the centre of {@code pos}: a clear ray (MISS) or one that
     * lands on {@code pos} itself counts as visible. This is the exact check the
     * acting modules use to confirm they can interact with a scanned block —
     * previously duplicated as {@code AutoDrain#canSeeBlock}.
     */
    public static boolean isVisible(MinecraftClient mc, BlockPos pos) {
        return isVisible(mc, pos, Vec3d.ofCenter(pos));
    }

    /** As {@link #isVisible(MinecraftClient, BlockPos)} but toward a specific point on the block. */
    public static boolean isVisible(MinecraftClient mc, BlockPos pos, Vec3d aimPoint) {
        if (mc.player == null || mc.world == null || pos == null) {
            return false;
        }
        BlockHitResult result = mc.world.raycast(new RaycastContext(
                mc.player.getEyePos(), aimPoint,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        return result.getType() == HitResult.Type.MISS || result.getBlockPos().equals(pos);
    }

    /** True if {@code point} is within {@code reach} blocks of the player's eyes. */
    public static boolean canReach(MinecraftClient mc, Vec3d point, double reach) {
        if (mc.player == null || point == null) {
            return false;
        }
        return mc.player.getEyePos().squaredDistanceTo(point) <= reach * reach;
    }
}
