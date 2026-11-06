package interstellarTikiTiks.apexx.client.module.implementation.player.clutch;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Represents a planned block placement target for clutch/bridging.
 */
public class PlacementTarget {

    private final BlockPos supportPos;
    private final Direction side;
    private final Vec3d hitVec;
    private final int depth;
    private final boolean ladder;

    public PlacementTarget(BlockPos supportPos, Direction side, Vec3d hitVec) {
        this(supportPos, side, hitVec, 0, false);
    }

    public PlacementTarget(BlockPos supportPos, Direction side, Vec3d hitVec, int depth, boolean ladder) {
        this.supportPos = supportPos;
        this.side = side;
        this.hitVec = hitVec;
        this.depth = depth;
        this.ladder = ladder;
    }

    public BlockPos getSupportPos() {
        return supportPos;
    }

    public Direction getSide() {
        return side;
    }

    public Vec3d getHitVec() {
        return hitVec;
    }

    public int getDepth() {
        return depth;
    }

    public boolean isLadder() {
        return ladder;
    }

    public BlockPos getPlacedPos() {
        return side != null ? supportPos.offset(side) : supportPos;
    }
}
