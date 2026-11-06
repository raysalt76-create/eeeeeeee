package interstellarTikiTiks.apexx.client.module.implementation.combat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;

/** HitCrystal's terrain policy. The caller supplies live support, clearance and reach checks. */
final class HitCrystalTerrain {
    private static final int PATCH_RADIUS = 3;
    private static final int DEPTH = 12;
    // End crystals explode with power 6; the distance envelope is twice that power.
    private static final double BLAST_RADIUS = 12.0;

    private HitCrystalTerrain() { }

    /**
     * Rebuilds the top-down patch, including the inner eight neighbours, at every candidate height.
     * Orders by 3D distance from the target's feet to the crystal origin, so a lower ledge is not
     * preferred merely because it is closer horizontally. Equal distances use a stable cell order.
     */
    static BlockPos closest(Box target, Predicate<BlockPos> placeable) {
        Vec3d feet = new Vec3d((target.minX + target.maxX) * 0.5, target.minY,
                (target.minZ + target.maxZ) * 0.5);
        BlockPos center = BlockPos.ofFloored(feet);
        ArrayList<BlockPos> cells = new ArrayList<>();
        for (int x = -PATCH_RADIUS; x <= PATCH_RADIUS; x++) {
            for (int z = -PATCH_RADIUS; z <= PATCH_RADIUS; z++) {
                for (int y = MathHelper.floor(target.minY) - DEPTH; y <= MathHelper.floor(target.minY); y++) {
                    BlockPos cell = new BlockPos(center.getX() + x, y, center.getZ() + z);
                    if (belowTarget(target, cell) && inBlastRadius(feet, cell)) cells.add(cell);
                }
            }
        }
        cells.sort(Comparator.<BlockPos>comparingDouble(cell -> origin(cell).squaredDistanceTo(feet))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        for (BlockPos cell : cells) {
            if (placeable.test(cell)) return cell;
        }
        return null;
    }

    static boolean belowTarget(Box target, BlockPos cell) {
        // Strictly above the BOTTOM edge, as requested; additionally keep the explosion at/below waist.
        if (target.minY <= cell.getY() || cell.getY() + 1.0 > (target.minY + target.maxY) * 0.5) return false;
        // Even if airborne, do not build in the column under any part of the opponent's footprint.
        if (target.maxX > cell.getX() && target.minX < cell.getX() + 1.0
                && target.maxZ > cell.getZ() && target.minZ < cell.getZ() + 1.0) return false;
        return !new Box(cell).intersects(target);
    }

    static boolean inBlastRadius(Vec3d targetFeet, BlockPos base) {
        return origin(base).squaredDistanceTo(targetFeet) < BLAST_RADIUS * BLAST_RADIUS;
    }

    private static Vec3d origin(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }
}
