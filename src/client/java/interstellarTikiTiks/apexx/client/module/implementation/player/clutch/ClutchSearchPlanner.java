package interstellarTikiTiks.apexx.client.module.implementation.player.clutch;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.*;

/**
 * 3D Graph and Search Planner for autonomous block clutching, bridging, and ladder catching.
 * Packaged within player/clutch to remain fully dynamic and self-contained in payload.
 */
public class ClutchSearchPlanner {

    private static final Direction[] SIDES = Direction.values();

    /**
     * Searches for the optimal placement target to save the player from falling.
     */
    public static PlacementTarget findBestClutchTarget(World world, PlayerEntity player, double maxReach,
                                                      int maxDepth, boolean allowLadders) {
        if (world == null || player == null) return null;

        Vec3d eyePos = player.getEyePos();
        Vec3d vel = player.getVelocity();

        // Predict where the player's feet will be in the next 1-2 ticks
        double predictTicks = Math.max(1.0, Math.min(2.5, -vel.y * 2.0));
        Vec3d predictedFeet = new Vec3d(
                player.getX() + vel.x * predictTicks,
                player.getY() + vel.y * predictTicks,
                player.getZ() + vel.z * predictTicks
        );

        BlockPos playerFeetPos = BlockPos.ofFloored(predictedFeet);
        int searchRadius = (int) Math.ceil(maxReach);

        List<PlacementCandidate> candidates = new ArrayList<>();

        // Priority 1: Check for ladder clutching on nearby vertical walls if enabled
        if (allowLadders) {
            PlacementTarget ladderTarget = findLadderTarget(world, eyePos, predictedFeet, maxReach);
            if (ladderTarget != null) {
                return ladderTarget;
            }
        }

        // Priority 2: Direct 1-tick floor / wall placement directly beneath or adjacent to feet
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -searchRadius; dy <= 1; dy++) { // Prioritize at or below feet
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    mutable.set(playerFeetPos.getX() + dx, playerFeetPos.getY() + dy, playerFeetPos.getZ() + dz);
                    BlockPos targetAirPos = mutable.toImmutable();

                    // Must be replaceable / air so a block can be placed there
                    BlockState targetState = world.getBlockState(targetAirPos);
                    if (!targetState.isReplaceable() && !targetState.isAir()) {
                        continue;
                    }

                    // Check if placement at targetAirPos provides standing support
                    boolean isBeneathFeet = targetAirPos.getY() <= player.getY() &&
                            Math.abs(targetAirPos.getX() + 0.5 - player.getX()) <= 1.2 &&
                            Math.abs(targetAirPos.getZ() + 0.5 - player.getZ()) <= 1.2;

                    // Inspect adjacent neighbors for an existing solid block to place against
                    for (Direction side : SIDES) {
                        BlockPos supportPos = targetAirPos.offset(side);
                        BlockState supportState = world.getBlockState(supportPos);

                        if (isSolidPlacementSupport(world, supportPos, supportState)) {
                            Direction faceToClick = side.getOpposite();
                            Vec3d hitVec = getHitVecForFace(supportPos, faceToClick);

                            double distSq = eyePos.squaredDistanceTo(hitVec);
                            if (distSq <= maxReach * maxReach) {
                                // Validate line of sight raycast
                                if (canSeeBlockFace(world, eyePos, hitVec, supportPos)) {
                                    double score = calculateScore(player, eyePos, hitVec, targetAirPos, isBeneathFeet, 0);
                                    candidates.add(new PlacementCandidate(
                                            new PlacementTarget(supportPos, faceToClick, hitVec, 0, false), score));
                                }
                            }
                        }
                    }
                }
            }
        }

        // Priority 3: Multi-block bridge extension if no direct 1-step placement is found
        if (candidates.isEmpty() && maxDepth > 1) {
            PlacementTarget multiBlockTarget = findMultiBlockPath(world, player, eyePos, playerFeetPos, maxReach, maxDepth);
            if (multiBlockTarget != null) {
                return multiBlockTarget;
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort candidates ascending by score (lower score = better)
        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates.get(0).target;
    }

    /**
     * Searches for vertical ladder placement targets on solid walls.
     */
    private static PlacementTarget findLadderTarget(World world, Vec3d eyePos, Vec3d predictedFeet, double maxReach) {
        BlockPos feetPos = BlockPos.ofFloored(predictedFeet);
        Direction[] horizontal = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

        for (int yOffset : new int[]{0, 1, -1}) {
            BlockPos airPos = feetPos.up(yOffset);
            if (!world.getBlockState(airPos).isAir()) continue;

            for (Direction wallDir : horizontal) {
                BlockPos wallPos = airPos.offset(wallDir);
                BlockState wallState = world.getBlockState(wallPos);
                if (wallState.isSolidBlock(world, wallPos) && wallState.isFullCube(world, wallPos)) {
                    Direction faceToClick = wallDir.getOpposite();
                    Vec3d hitVec = getHitVecForFace(wallPos, faceToClick);

                    if (eyePos.squaredDistanceTo(hitVec) <= maxReach * maxReach) {
                        if (canSeeBlockFace(world, eyePos, hitVec, wallPos)) {
                            return new PlacementTarget(wallPos, faceToClick, hitVec, 0, true);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Multi-block graph search discovering 2-to-3 block extension chains from nearby solid land.
     */
    private static PlacementTarget findMultiBlockPath(World world, PlayerEntity player, Vec3d eyePos,
                                                     BlockPos originFeet, double maxReach, int maxDepth) {
        Queue<SearchNode> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        // Start search from solid blocks within reach
        int radius = (int) Math.ceil(maxReach);
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    m.set(originFeet.getX() + x, originFeet.getY() + y, originFeet.getZ() + z);
                    BlockPos p = m.toImmutable();
                    BlockState state = world.getBlockState(p);
                    if (isSolidPlacementSupport(world, p, state)) {
                        queue.add(new SearchNode(p, null, null, 0));
                        visited.add(p);
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            SearchNode node = queue.poll();
            if (node.depth >= maxDepth) continue;

            for (Direction dir : SIDES) {
                BlockPos nextAir = node.pos.offset(dir);
                if (visited.contains(nextAir)) continue;

                BlockState nextState = world.getBlockState(nextAir);
                if (nextState.isAir() || nextState.isReplaceable()) {
                    visited.add(nextAir);

                    Vec3d hitVec = getHitVecForFace(node.pos, dir);
                    if (eyePos.squaredDistanceTo(hitVec) <= maxReach * maxReach) {
                        if (canSeeBlockFace(world, eyePos, hitVec, node.pos)) {
                            double distToFeet = nextAir.getSquaredDistance(originFeet);
                            if (distToFeet <= 4.0) {
                                return new PlacementTarget(node.pos, dir, hitVec, node.depth + 1, false);
                            }
                        }
                    }
                    queue.add(new SearchNode(nextAir, node.pos, dir, node.depth + 1));
                }
            }
        }
        return null;
    }

    private static boolean isSolidPlacementSupport(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.isReplaceable()) return false;
        if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER) return false;
        return state.isSolidBlock(world, pos) && !state.isOf(Blocks.BARRIER);
    }

    public static Vec3d getHitVecForFace(BlockPos pos, Direction face) {
        double x = pos.getX() + 0.5 + face.getOffsetX() * 0.5;
        double y = pos.getY() + 0.5 + face.getOffsetY() * 0.5;
        double z = pos.getZ() + 0.5 + face.getOffsetZ() * 0.5;
        return new Vec3d(x, y, z);
    }

    private static boolean canSeeBlockFace(World world, Vec3d eyePos, Vec3d hitVec, BlockPos expectedBlock) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                eyePos,
                hitVec,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                (net.minecraft.entity.Entity) null
        ));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(expectedBlock);
    }

    private static double calculateScore(PlayerEntity player, Vec3d eyePos, Vec3d hitVec,
                                         BlockPos targetPos, boolean isBeneathFeet, int depth) {
        double dist = eyePos.distanceTo(hitVec);
        double depthPenalty = depth * 2.5;

        Vec3d dir = hitVec.subtract(eyePos).normalize();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));

        float deltaYaw = Math.abs(MathHelper.wrapDegrees(targetYaw - player.getYaw()));
        float deltaPitch = Math.abs(targetPitch - player.getPitch());
        double anglePenalty = (deltaYaw + deltaPitch) * 0.05;

        double beneathBonus = isBeneathFeet ? -10.0 : 0.0;

        return dist + depthPenalty + anglePenalty + beneathBonus;
    }

    private static class PlacementCandidate {
        final PlacementTarget target;
        final double score;

        PlacementCandidate(PlacementTarget target, double score) {
            this.target = target;
            this.score = score;
        }
    }

    private static class SearchNode {
        final BlockPos pos;
        final BlockPos parent;
        final Direction dir;
        final int depth;

        SearchNode(BlockPos pos, BlockPos parent, Direction dir, int depth) {
            this.pos = pos;
            this.parent = parent;
            this.dir = dir;
            this.depth = depth;
        }
    }
}
