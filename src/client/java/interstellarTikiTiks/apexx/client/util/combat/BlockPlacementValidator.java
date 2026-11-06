package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Validates aimed block interactions against the live ray the client currently presents to
 * Minecraft. It never dispatches an interaction; callers pass the returned hit to vanilla's
 * {@code ClientPlayerInteractionManager.interactBlock}.
 *
 * <p>While a silent rotation is active, the ray follows RotationManager's final server-facing
 * angles, regardless of the caller's click-dispatch mode. Otherwise it follows the player's
 * current look. A caller's planned hit is only an intent: the validated result is always the
 * actual raycast hit, and it must reach the intended supporting block and face. When the held
 * item is a BlockItem, the placement cell derived from that live hit must also match the one
 * derived from the intended hit.</p>
 */
public final class BlockPlacementValidator {

    private BlockPlacementValidator() {
    }

    /**
     * Returns the live block hit only when it reaches the requested supporting block and face.
     * For block items, the same ray must also resolve to the intended placement cell.
     */
    public static BlockHitResult validateHit(MinecraftClient mc, BlockHitResult intendedHit,
                                             boolean silentRotation) {
        if (mc == null || mc.player == null || mc.world == null || intendedHit == null
                || intendedHit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockHitResult liveHit = rayFromLook(mc, silentRotation);
        if (liveHit == null || liveHit.getType() != HitResult.Type.BLOCK
                || !liveHit.getBlockPos().equals(intendedHit.getBlockPos())
                || liveHit.getSide() != intendedHit.getSide()) {
            return null;
        }

        if (mc.player.getMainHandStack().getItem() instanceof BlockItem) {
            BlockPos intendedCell = placementCell(mc, intendedHit);
            BlockPos liveCell = placementCell(mc, liveHit);
            if (intendedCell == null || !intendedCell.equals(liveCell)) {
                return null;
            }
        }

        double reach = mc.player.getBlockInteractionRange();
        if (mc.player.getEyePos().squaredDistanceTo(liveHit.getPos()) > reach * reach) {
            return null;
        }
        return liveHit;
    }

    /**
     * Returns the current live hit on {@code block}, or {@code null} when the block is not the
     * first block on the ray or is outside interaction reach.
     */
    public static BlockHitResult hitForInteraction(MinecraftClient mc, BlockPos block,
                                                    boolean silentRotation) {
        if (mc == null || block == null) {
            return null;
        }
        BlockHitResult hit = rayFromLook(mc, silentRotation);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(block)) {
            return null;
        }
        return hit;
    }

    /**
     * Returns the current live hit only when placing on it would fill {@code cell}.
     */
    public static BlockHitResult hitForPlacement(MinecraftClient mc, BlockPos cell,
                                                  boolean silentRotation) {
        if (mc == null || cell == null) {
            return null;
        }
        BlockHitResult hit = rayFromLook(mc, silentRotation);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos liveCell = placementCell(mc, hit);
        return cell.equals(liveCell) ? hit : null;
    }

    /**
     * Casts the current physical or server-facing look to vanilla block-interaction reach.
     */
    public static BlockHitResult rayFromLook(MinecraftClient mc, boolean silentRotation) {
        if (mc == null || mc.player == null || mc.world == null) {
            return null;
        }

        Vec3d eye = mc.player.getEyePos();
        Vec3d look;
        if (RotationManager.isSilentRotationActive()) {
            look = RotationManager.getSilentRotationVec();
        } else {
            look = mc.player.getRotationVec(1.0F);
        }
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * Computes the destination cell for vanilla block placement from a support hit.
     */
    public static BlockPos placementCell(MinecraftClient mc, BlockHitResult hit) {
        if (mc == null || mc.world == null || hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos clicked = hit.getBlockPos();
        var state = mc.world.getBlockState(clicked);
        boolean replacesInPlace = state.isReplaceable()
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE);
        return replacesInPlace ? clicked : clicked.offset(hit.getSide());
    }
}
