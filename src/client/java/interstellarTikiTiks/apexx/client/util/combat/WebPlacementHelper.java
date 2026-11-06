package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared trajectory prediction, knockback physics simulation, collision testing,
 * and placement face resolution for cobwebs.
 */
public final class WebPlacementHelper {

    private WebPlacementHelper() {
    }

    /**
     * Calculates the exact 2D horizontal footprint overlap area (X-Z plane) between an entity's
     * bounding box and a 1x1 block coordinate.
     */
    public static double calculate2DFootprintOverlap(Box box, BlockPos blockPos) {
        if (box == null || blockPos == null) return 0.0;
        double overlapX = Math.max(0.0, Math.min(box.maxX, (double) blockPos.getX() + 1.0) - Math.max(box.minX, (double) blockPos.getX()));
        double overlapZ = Math.max(0.0, Math.min(box.maxZ, (double) blockPos.getZ() + 1.0) - Math.max(box.minZ, (double) blockPos.getZ()));
        return overlapX * overlapZ;
    }

    /**
     * Determines whether the entity is already inside or colliding with a cobweb block,
     * using 2D footprint overlap intersection math to prevent redundant placements.
     */
    public static boolean isCollidingWithCobweb(LivingEntity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity == null || mc.world == null) return false;

        Box box = entity.getBoundingBox();

        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX - 1.0e-7, box.maxY - 1.0e-7, box.maxZ - 1.0e-7);

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (mc.world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                double overlap = calculate2DFootprintOverlap(box, pos);
                if (overlap > 0.001) {
                    return true;
                }
            }
        }

        BlockPos feet = entity.getBlockPos();
        if (mc.world.getBlockState(feet).isOf(Blocks.COBWEB) && calculate2DFootprintOverlap(box, feet) > 0.001) {
            return true;
        }
        if (mc.world.getBlockState(feet.up()).isOf(Blocks.COBWEB) && calculate2DFootprintOverlap(box, feet.up()) > 0.001) {
            return true;
        }

        return false;
    }

    /**
     * Obtains the entity's observed per-tick velocity vector from position delta or fallback velocity.
     */
    public static Vec3d getEntityVelocity(LivingEntity entity) {
        if (entity == null) return Vec3d.ZERO;

        double dx = entity.getX() - entity.lastX;
        double dy = entity.getY() - entity.lastY;
        double dz = entity.getZ() - entity.lastZ;

        if (dx * dx + dy * dy + dz * dz > 1.0e-6) {
            return new Vec3d(dx, dy, dz);
        }

        Vec3d v = entity.getVelocity();
        return (v != null) ? v : Vec3d.ZERO;
    }

    /**
     * Standard entrypoint for finding the best valid BlockHitResult for placing a cobweb.
     */
    public static BlockHitResult findBestWebPlacement(LivingEntity target, boolean predict, double leadTicks,
                                                      boolean doubleWeb, double maxReach) {
        return findBestWebPlacement(target, predict, leadTicks, doubleWeb, maxReach, false);
    }

    /**
     * Finds the best valid BlockHitResult for placing a cobweb on or ahead of the target entity,
     * optionally taking post-attack knockback physics into account.
     *
     * @param target               The entity to trap
     * @param predict              Whether to use trajectory prediction
     * @param leadTicks            Maximum prediction lead ticks
     * @param doubleWeb            Whether to also consider head-level placement
     * @param maxReach             Maximum placement reach distance in blocks
     * @param postAttackKnockback  Whether the target just received an attack knockback impulse
     * @return Valid BlockHitResult, or null if no valid placement exists
     */
    public static BlockHitResult findBestWebPlacement(LivingEntity target, boolean predict, double leadTicks,
                                                      boolean doubleWeb, double maxReach, boolean postAttackKnockback) {
        return findBestWebPlacement(target, predict, leadTicks, doubleWeb, maxReach, postAttackKnockback ? 1 : 0);
    }

    /** Finds a placement while simulating one or more rapid post-attack knockback impulses. */
    public static BlockHitResult findBestWebPlacement(LivingEntity target, boolean predict, double leadTicks,
                                                      boolean doubleWeb, double maxReach, int postAttackImpulses) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || target == null || !target.isAlive()) {
            return null;
        }

        List<BlockPos> candidates = generateCandidateCells(target, predict, leadTicks, doubleWeb, postAttackImpulses);
        double maxReachSq = maxReach * maxReach;
        Vec3d eyePos = mc.player.getEyePos();

        for (BlockPos cell : candidates) {
            BlockState state = mc.world.getBlockState(cell);
            if (!state.isAir() && !state.isReplaceable()) {
                continue;
            }

            BlockHitResult hit = findSupportingFace(mc, cell, eyePos, maxReachSq);
            if (hit != null) {
                return hit;
            }
        }

        return null;
    }

    /**
     * Generates an ordered list of candidate block positions to place the cobweb.
     * Uses forward physics integration (gravity, air friction, knockback impulses)
     * to project the exact landing cell and flight path of the target.
     */
    public static List<BlockPos> generateCandidateCells(LivingEntity target, boolean predict, double leadTicks,
                                                        boolean doubleWeb, boolean postAttackKnockback) {
        return generateCandidateCells(target, predict, leadTicks, doubleWeb, postAttackKnockback ? 1 : 0);
    }

    /** Projects the trajectory after the requested number of rapid knockback impulses. */
    public static List<BlockPos> generateCandidateCells(LivingEntity target, boolean predict, double leadTicks,
                                                        boolean doubleWeb, int postAttackImpulses) {
        List<BlockPos> cells = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (target == null || mc.world == null || mc.player == null) return cells;

        Box box = target.getBoundingBox();
        int floorY = (int) Math.floor(box.minY + 1.0e-4);
        BlockPos feetPos = BlockPos.ofFloored(target.getX(), (double) floorY + 0.5, target.getZ());
        BlockPos headPos = feetPos.up();

        if (predict) {
            Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
            Vec3d vel = getEntityVelocity(target);

            double vx = vel.x;
            double vy = vel.y;
            double vz = vel.z;

            if (postAttackImpulses > 0) {
                Vec3d kv = estimateKnockbackVelocity(target, new Vec3d(vx, vy, vz), postAttackImpulses);
                vx = kv.x;
                vy = kv.y;
                vz = kv.z;
            }

            // Simulate flight trajectory forward (standard Minecraft physics: gravity -0.08, drag 0.98, horizontal drag 0.91)
            int maxSteps = Math.max(2, (int) Math.round(leadTicks));
            Vec3d simPos = targetPos;
            Vec3d simVel = new Vec3d(vx, vy, vz);

            BlockPos landingSpot = null;
            List<BlockPos> trajectoryCandidates = new ArrayList<>();

            // Stun launches can remain airborne for several seconds; searching only eight ticks
            // picks a mid-flight foot cell and misses the actual landing position entirely.
            for (int step = 1; step <= Math.max(maxSteps, 60); step++) {
                simPos = simPos.add(simVel);
                simVel = new Vec3d(simVel.x * 0.91, (simVel.y - 0.08) * 0.98, simVel.z * 0.91);

                BlockPos simFeet = BlockPos.ofFloored(simPos.x, simPos.y, simPos.z);
                BlockPos simGround = simFeet.down();

                // A supported cell under the target does not mean it has landed while rising.
                // Only accept ground contact once the simulated vertical motion is descending.
                boolean descending = simVel.y <= 0.0;
                boolean hitsGround = descending && (isSolidGround(mc, simGround, mc.world.getBlockState(simGround))
                        || isSolidGround(mc, simFeet, mc.world.getBlockState(simFeet)));

                if (hitsGround && landingSpot == null && step >= 1) {
                    if (isSolidGround(mc, simFeet, mc.world.getBlockState(simFeet))) {
                        landingSpot = simFeet.up();
                    } else {
                        landingSpot = simFeet;
                    }
                    break;
                }

                if (step <= maxSteps && !trajectoryCandidates.contains(simFeet)) {
                    trajectoryCandidates.add(simFeet);
                }
            }

            // 1. Landing spot priority (where the opponent is flying to and lands)
            if (landingSpot != null && !cells.contains(landingSpot)) {
                cells.add(landingSpot);
            }

            // 2. Flight arc trajectory points (furthest valid intercept along flight path)
            for (int i = trajectoryCandidates.size() - 1; i >= 0; i--) {
                BlockPos p = trajectoryCandidates.get(i);
                if (!cells.contains(p)) {
                    cells.add(p);
                }
            }
        }

        // 3. Fallbacks
        if (!cells.contains(feetPos)) {
            cells.add(feetPos);
        }
        if (doubleWeb && !cells.contains(headPos)) {
            cells.add(headPos);
        }

        return cells;
    }

    /**
     * Estimates the target's velocity after {@code impulses} knockback hits from the local player,
     * starting from {@code base}. Used only until the target's real motion has been observed.
     */
    public static Vec3d estimateKnockbackVelocity(LivingEntity target, Vec3d base, int impulses) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (target == null || mc.player == null || impulses <= 0) return base;
        double vx = base.x;
        double vy = base.y;
        double vz = base.z;

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);
        Vec3d kbDir;
        if (distH > 1.0e-5) {
            kbDir = new Vec3d(dx / distH, 0.0, dz / distH);
        } else {
            float yawRad = (float) Math.toRadians(mc.player.getYaw());
            kbDir = new Vec3d(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        }

        double kbStrength = 0.40;
        if (mc.player.isSprinting()) {
            kbStrength += 0.48; // Extra knockback from sprint hit
        }
        try {
            if (target.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.KNOCKBACK_RESISTANCE) != null) {
                double resist = target.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.KNOCKBACK_RESISTANCE);
                kbStrength *= Math.max(0.0, 1.0 - resist);
            }
        } catch (Throwable ignored) {}

        for (int impulse = 0; impulse < Math.min(impulses, 5); impulse++) {
            vx = (vx * 0.5) + (kbDir.x * kbStrength);
            vz = (vz * 0.5) + (kbDir.z * kbStrength);
            vy = target.isOnGround() ? 0.385 : Math.min(0.40, vy * 0.5 + 0.385);
        }
        return new Vec3d(vx, vy, vz);
    }

    /**
     * Simulates the target's bounding box forward under vanilla air physics WITH block collisions
     * and returns the cell its feet occupy on first downward ground contact, or null if it never
     * lands within the simulated window. Horizontal wall hits zero that axis' velocity.
     */
    public static BlockPos predictLandingCell(LivingEntity target, Vec3d velocity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || target == null) return null;

        Box box = target.getBoundingBox();
        Vec3d vel = velocity == null ? Vec3d.ZERO : velocity;

        for (int step = 0; step < 100; step++) {
            double mx = vel.x;
            double my = vel.y;
            double mz = vel.z;

            List<VoxelShape> shapes = new ArrayList<>();
            for (VoxelShape shape : mc.world.getBlockCollisions(target, box.stretch(mx, my, mz).expand(1.0e-7))) {
                shapes.add(shape);
            }

            // Vanilla resolves Y first, then the larger horizontal axis first.
            double dy = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, box, shapes, my);
            Box moved = box.offset(0.0, dy, 0.0);
            double dx;
            double dz;
            if (Math.abs(mx) >= Math.abs(mz)) {
                dx = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moved, shapes, mx);
                moved = moved.offset(dx, 0.0, 0.0);
                dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, mz);
                moved = moved.offset(0.0, 0.0, dz);
            } else {
                dz = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, moved, shapes, mz);
                moved = moved.offset(0.0, 0.0, dz);
                dx = VoxelShapes.calculateMaxOffset(Direction.Axis.X, moved, shapes, mx);
                moved = moved.offset(dx, 0.0, 0.0);
            }
            box = moved;

            if (my < 0.0 && dy > my + 1.0e-9) {
                Vec3d c = box.getCenter();
                return BlockPos.ofFloored(c.x, box.minY + 0.01, c.z);
            }

            if (Math.abs(dx - mx) > 1.0e-9) mx = 0.0;
            if (Math.abs(dz - mz) > 1.0e-9) mz = 0.0;
            if (my > 0.0 && dy < my - 1.0e-9) my = 0.0; // bumped a ceiling
            vel = new Vec3d(mx * 0.91, (my - 0.08) * 0.98, mz * 0.91);
        }
        return null;
    }

    /**
     * Placement for the EXACT predicted landing cell only. Unlike {@link #findBestWebPlacement},
     * there is no fallback to mid-flight or current-foot cells: a web anywhere else misses the
     * landing, so the caller should retry on the next frame with refreshed motion instead.
     */
    public static BlockHitResult findLandingWebPlacement(LivingEntity target, Vec3d velocity, double maxReach) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || target == null || !target.isAlive()) return null;

        BlockPos cell = predictLandingCell(target, velocity);
        if (cell == null) return null;
        BlockState state = mc.world.getBlockState(cell);
        if (!state.isAir() && !state.isReplaceable()) return null;
        return findSupportingFace(mc, cell, mc.player.getEyePos(), maxReach * maxReach);
    }

    /**
     * Determines whether a block is solid ground for trajectory landing simulation.
     */
    public static boolean isSolidGround(MinecraftClient mc, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || state.isLiquid() || state.isOf(Blocks.COBWEB) || state.isReplaceable()) {
            return false;
        }
        if (mc.world == null) return false;
        return state.isSolidBlock(mc.world, pos) || !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    /**
     * Determines whether a block can physically support placing a cobweb against it (as an interactable face).
     * Includes solid blocks and existing cobwebs (allowing stacked/adjacent web placement).
     */
    public static boolean isSupportingBlock(MinecraftClient mc, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty() || state.isReplaceable()) {
            return false;
        }
        if (mc.world == null) return false;
        return !state.getCollisionShape(mc.world, pos).isEmpty() || state.isOf(Blocks.COBWEB);
    }

    /**
     * Validates line of sight from player's eyes to face hit position, ensuring no blocks occlude the interact.
     */
    public static boolean isRaycastVisible(MinecraftClient mc, Vec3d targetVec, BlockPos expectedBlock) {
        if (mc == null || mc.player == null || mc.world == null || targetVec == null || expectedBlock == null) {
            return false;
        }
        BlockHitResult ray = mc.world.raycast(new RaycastContext(
                mc.player.getEyePos(),
                targetVec,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                mc.player
        ));
        return ray != null && ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(expectedBlock);
    }

    /**
     * Finds a solid neighboring block face that can support a web placement inside the target cell.
     * Contracts hit vector inwards and checks raycast visibility to eliminate anticheat ghost rejections.
     */
    public static BlockHitResult findSupportingFace(MinecraftClient mc, BlockPos cell, Vec3d eyePos, double maxReachSq) {
        if (mc.world == null || cell == null) return null;

        BlockState cellState = mc.world.getBlockState(cell);
        // If the cell contains replaceable vegetation (tall grass, flowers), clicking directly on it replaces it.
        if (cellState.isReplaceable() && !cellState.isAir() && cellState.getFluidState().isEmpty()) {
            Vec3d hitVec = Vec3d.ofCenter(cell);
            if (eyePos.squaredDistanceTo(hitVec) <= maxReachSq && isRaycastVisible(mc, hitVec, cell)) {
                return new BlockHitResult(hitVec, Direction.UP, cell, false);
            }
        }

        // Check neighboring supporting faces: DOWN (floor), then NORTH, SOUTH, WEST, EAST, UP (ceiling)
        Direction[] neighborDirs = new Direction[]{
                Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
        };
        for (Direction dir : neighborDirs) {
            BlockPos neighbor = cell.offset(dir);
            BlockState ns = mc.world.getBlockState(neighbor);
            if (!isSupportingBlock(mc, neighbor, ns)) {
                continue;
            }
            Direction face = dir.getOpposite();
            Vec3d faceCenter = Vec3d.ofCenter(neighbor).add(
                    face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            Vec3d hitVec = faceCenter.subtract(
                    face.getOffsetX() * 0.002, face.getOffsetY() * 0.002, face.getOffsetZ() * 0.002);

            if (eyePos.squaredDistanceTo(faceCenter) > maxReachSq) {
                continue;
            }

            // Cannot click top face if eye pos is far below the top of the block
            if (face == Direction.UP && eyePos.y < neighbor.getY() + 0.1) {
                continue;
            }

            // Raycast validation: ensures face is unoccluded and visible to player
            if (isRaycastVisible(mc, hitVec, neighbor)) {
                return new BlockHitResult(faceCenter, face, neighbor, false);
            }
        }

        return null;
    }
}
