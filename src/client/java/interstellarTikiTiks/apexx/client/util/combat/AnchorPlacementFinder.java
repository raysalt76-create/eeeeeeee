package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.util.world.CrystalUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * AnchorPlacementFinder - Scans candidate placement positions around target feet,
 * evaluates supporting faces, scores candidates by explosion exposure, and validates
 * legitimate line-of-sight raycasts.
 */
public final class AnchorPlacementFinder {

    private static final double PLACE_RAY_EPSILON = 1.0E-4;
    private static final double PLACE_FACE_CORNER_OFFSET = 0.49;
    // Flat (u, v) pairs: a 2D array literal in <clinit> trips ProGuard's preverifier
    // (StackTypeException) and fails the release build.
    private static final double[] PLACE_FACE_SAMPLE_OFFSETS = {
        0.0, 0.0,
        PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET,
        PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET,
        -PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET,
        -PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET
    };

    private AnchorPlacementFinder() {}

    public record PlacementHit(BlockPos placementPos, BlockHitResult hitResult) {}

    public static PlacementHit findPlacementHit(MinecraftClient mc, BlockPos pos, boolean requireRaycast, float tickDelta) {
        if (pos == null || mc.world == null || mc.player == null) return null;
        Vec3d eyePos = mc.player.getCameraPosVec(tickDelta);
        PlacementHit bestHit = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.offset(direction);
            Direction face = direction.getOpposite();

            BlockState adjState = mc.world.getBlockState(adjacent);
            if (adjState.isAir() || adjState.isReplaceable()) continue;
            if (!adjState.isSideSolidFullSquare(mc.world, adjacent, face) && !adjState.isOpaqueFullCube()) {
                continue;
            }

            PlacementHit hit = findPlacementHitOnFace(mc, eyePos, pos, adjacent, direction, face, requireRaycast);
            if (hit == null) continue;

            double distanceSq = eyePos.squaredDistanceTo(hit.hitResult().getPos());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestHit = hit;
            }
        }

        return bestHit;
    }

    private static PlacementHit findPlacementHitOnFace(MinecraftClient mc, Vec3d eyePos, BlockPos placementPos,
                                                      BlockPos adjacent, Direction direction, Direction face,
                                                      boolean requireRaycast) {
        for (int sample = 0; sample < PLACE_FACE_SAMPLE_OFFSETS.length; sample += 2) {
            Vec3d facePoint = getFaceSamplePoint(adjacent, face,
                    PLACE_FACE_SAMPLE_OFFSETS[sample], PLACE_FACE_SAMPLE_OFFSETS[sample + 1]);
            BlockHitResult hitResult = new BlockHitResult(facePoint, face, adjacent, false);

            if (requireRaycast && !canRaycastPlacementFace(mc, eyePos, hitResult, direction)) {
                continue;
            }

            return new PlacementHit(placementPos, hitResult);
        }
        return null;
    }

    private static boolean canRaycastPlacementFace(MinecraftClient mc, Vec3d eyePos, BlockHitResult placementHit, Direction direction) {
        Vec3d facePoint = placementHit.getPos();
        Vec3d rayEnd = facePoint.add(
            direction.getOffsetX() * PLACE_RAY_EPSILON,
            direction.getOffsetY() * PLACE_RAY_EPSILON,
            direction.getOffsetZ() * PLACE_RAY_EPSILON
        );

        BlockHitResult hitResult = mc.world.raycast(new RaycastContext(
            eyePos,
            rayEnd,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!hitResult.getBlockPos().equals(placementHit.getBlockPos())) return false;
        return hitResult.getSide() == placementHit.getSide();
    }

    private static Vec3d getFaceSamplePoint(BlockPos adjacent, Direction face, double offsetA, double offsetB) {
        Vec3d faceCenter = Vec3d.ofCenter(adjacent).add(
            face.getOffsetX() * 0.5,
            face.getOffsetY() * 0.5,
            face.getOffsetZ() * 0.5
        );

        return switch (face.getAxis()) {
            case X -> faceCenter.add(0.0, offsetA, offsetB);
            case Y -> faceCenter.add(offsetA, 0.0, offsetB);
            case Z -> faceCenter.add(offsetA, offsetB, 0.0);
        };
    }

    public static boolean hasSupportingFace(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = pos.offset(dir);
            BlockState adjState = world.getBlockState(adjacent);
            if (adjState.isAir() || adjState.isReplaceable()) continue;
            Direction face = dir.getOpposite();
            if (adjState.isSideSolidFullSquare(world, adjacent, face) || adjState.isOpaqueFullCube()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasBlockingEntity(World world, Box box) {
        if (world == null || box == null) return false;
        for (Entity e : world.getOtherEntities(null, box)) {
            if (e instanceof PlayerEntity || e instanceof LivingEntity || e.canHit()) {
                return true;
            }
        }
        return false;
    }

    public static LivingEntity findClosestTarget(MinecraftClient mc, double maxRange, boolean ignoreFriends) {
        if (mc.world == null || mc.player == null) return null;
        PlayerEntity bestPlayer = null;
        double bestDistSq = maxRange * maxRange;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            if (ignoreFriends && FriendManager.getInstance().isFriend(p.getUuid())) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d <= bestDistSq) {
                bestDistSq = d;
                bestPlayer = p;
            }
        }
        if (bestPlayer != null) return bestPlayer;

        // Fallback: search other living entities within range (for testing, bots, mobs)
        LivingEntity bestLiving = null;
        double bestLivingDistSq = maxRange * maxRange;
        Box searchBox = mc.player.getBoundingBox().expand(maxRange);
        for (Entity e : mc.world.getOtherEntities(mc.player, searchBox)) {
            if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
            if (living instanceof PlayerEntity) continue;
            double d = mc.player.squaredDistanceTo(living);
            if (d <= bestLivingDistSq) {
                bestLivingDistSq = d;
                bestLiving = living;
            }
        }
        return bestLiving;
    }

    public static PlacementHit findBestPlacementHit(MinecraftClient mc, float tickDelta, boolean canPlaceLegit,
                                                    double maxRange, boolean ignoreFriends) {
        return findBestPlacementHit(mc, tickDelta, canPlaceLegit, maxRange, ignoreFriends, 0.0, 36.0);
    }

    public static PlacementHit findBestPlacementHit(MinecraftClient mc, float tickDelta, boolean canPlaceLegit,
                                                    double maxRange, boolean ignoreFriends,
                                                    double minTargetDamage, double maxSelfDamage) {
        if (mc.player == null || mc.world == null) return null;
        LivingEntity target = findClosestTarget(mc, maxRange, ignoreFriends);
        if (target == null) return null;

        Vec3d playerEye = mc.player.getCameraPosVec(tickDelta);
        double blockRange = Math.min(mc.player.getBlockInteractionRange(), maxRange);
        double blockRangeSq = blockRange * blockRange;

        Vec3d targetPos = target.getLerpedPos(tickDelta);
        Vec3d targetBase = new Vec3d(target.getX(), target.getY(), target.getZ());
        Box targetBox = target.getBoundingBox().offset(targetPos.subtract(targetBase));
        Vec3d targetFeet = new Vec3d(targetPos.x, targetBox.minY, targetPos.z);

        int floorX = MathHelper.floor(targetFeet.x);
        int floorY = MathHelper.floor(targetFeet.y);
        int floorZ = MathHelper.floor(targetFeet.z);

        PlacementHit bestHit = null;
        double bestScore = Double.MAX_VALUE;

        PlacementHit bestDamageHit = null;
        double bestDamageScore = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(floorX + dx, floorY + dy, floorZ + dz);

                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isAir() && !state.isReplaceable()) continue;
                    if (!hasSupportingFace(mc.world, pos)) continue;

                    PlacementHit hit = findPlacementHit(mc, pos, canPlaceLegit, tickDelta);
                    if (hit == null) continue;

                    Box placeBox = new Box(pos);
                    if (mc.player.getBoundingBox().intersects(placeBox)) continue;
                    if (hasBlockingEntity(mc.world, placeBox)) continue;
                    if (playerEye.squaredDistanceTo(hit.hitResult().getPos()) > blockRangeSq) continue;

                    Vec3d blockCenter = Vec3d.ofCenter(pos);
                    float exposure = CrystalUtils.getExposure(blockCenter, target, mc.world);
                    double feetDistSq = targetFeet.squaredDistanceTo(blockCenter);

                    // Prefer high-exposure hits; break ties by proximity to feet (Echo formula)
                    double score = (1.0 - exposure) * 100.0 + feetDistSq * 0.01;

                    if (score < bestScore) {
                        bestScore = score;
                        bestHit = hit;
                    }

                    // Optional damage filtering
                    if (minTargetDamage > 0.0 || maxSelfDamage < 36.0) {
                        float targetDmg = CrystalUtils.calculateAnchorDamage(mc.world, blockCenter, target);
                        float selfDmg = CrystalUtils.calculateAnchorDamage(mc.world, blockCenter, mc.player);
                        if (targetDmg >= minTargetDamage && selfDmg <= maxSelfDamage) {
                            if (score < bestDamageScore) {
                                bestDamageScore = score;
                                bestDamageHit = hit;
                            }
                        }
                    }
                }
            }
        }
        return (bestDamageHit != null) ? bestDamageHit : bestHit;
    }

    /** Casts an OUTLINE ray from the player's eye in the given direction out to block-interaction range. */
    public static BlockHitResult raycastFromRotation(MinecraftClient mc, float yaw, float pitch, float tickDelta) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getCameraPosVec(tickDelta);
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        return mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    /** True if a ray from the eye at the given rotation passes through (a slightly inflated) {@code clickedPos}. */
    public static boolean rayIntersectsBlock(MinecraftClient mc, float yaw, float pitch, BlockPos clickedPos, float tickDelta) {
        if (mc.player == null || clickedPos == null) return false;
        Vec3d eye = mc.player.getCameraPosVec(tickDelta);
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        return new Box(clickedPos).expand(1.0E-4).raycast(eye, end).isPresent();
    }
}
