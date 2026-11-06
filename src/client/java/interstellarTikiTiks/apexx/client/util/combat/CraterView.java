package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.Set;

/**
 * The world as it will be once a set of blocks is gone: those positions read as air, everything else is
 * the live world. It is a read-only view for planning (a pearl flight cast against a predicted blast
 * crater); nothing here changes the world.
 */
public final class CraterView implements BlockView {

    private final BlockView world;
    private final Set<BlockPos> removed;

    public CraterView(BlockView world, Set<BlockPos> removed) {
        this.world = world;
        this.removed = removed;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return removed.contains(pos) ? null : world.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return removed.contains(pos) ? Blocks.AIR.getDefaultState() : world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return removed.contains(pos) ? Fluids.EMPTY.getDefaultState() : world.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }
}
