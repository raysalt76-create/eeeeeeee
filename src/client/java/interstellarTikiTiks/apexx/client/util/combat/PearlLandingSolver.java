package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where a pearl should land inside a blast crater, and the throw that lands it there.
 *
 * <p><b>Choosing the cell.</b> {@link #chooseLanding} takes the cells inside the blast radius that
 * stand on a floor the blast cannot remove, with head room the blast certainly clears, within the maximum
 * distance of the player. Of those it keeps the ones no higher than the lowest by more than the depth
 * tolerance and takes the one <em>closest to the player</em>: a far, deeper hole loses to a near, nearly
 * as low one.</p>
 *
 * <p><b>Solving the throw.</b> {@link #solve} sweeps the pitch at the yaw that faces the cell, flies the
 * exact pearl ({@link PearlSimulation}) through the predicted terrain and keeps the pitches that end on the
 * top face of the cell, then refines the one needing the least turn by the chance it lands once the
 * launch spread is rolled. The terrain is cast twice, once with only the surely removed blocks gone and
 * once with every block the blast could remove gone, and a throw counts only as well as the worse of the
 * two, so it holds for any roll in between.</p>
 */
public final class PearlLandingSolver {

    /** A solved throw: the look, the chance it lands on the cell, the pearl age it ends at and where. */
    public record Shot(float yaw, float pitch, double chance, int hitAge, Vec3d landing) {
    }

    private static final int MAX_FLIGHT_TICKS = 120;
    private static final double BLAST_REACH = 5.5;
    /** The pearl must end this close (blocks) to the middle of the cell's top face on each horizontal axis. */
    private static final double LANDING_RADIUS = 0.45;
    private static final double LANDING_HEIGHT = 0.2;

    private PearlLandingSolver() {
    }

    /**
     * The cell to land in, or null when the blast leaves nowhere safe to stand within reach.
     *
     * @param crater   the blast's predicted removals ({@code sure} and {@code possible})
     * @param maxDistance farthest the landing may be from {@code feet} (blocks)
     * @param depthTolerance blocks above the lowest candidate that still count as lowest
     */
    public static BlockPos chooseLanding(World world, BlockPos anchor, BlastModel.Crater crater, Vec3d feet,
                                         double maxDistance, double depthTolerance) {
        Set<BlockPos> sure = crater.sure();
        Set<BlockPos> possible = crater.possible();
        Vec3d blast = Vec3d.ofCenter(anchor);
        List<BlockPos> candidates = new ArrayList<>();
        int radius = (int) Math.ceil(BLAST_REACH);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos cell = anchor.add(dx, dy, dz);
                    Vec3d center = Vec3d.ofCenter(cell);
                    if (center.distanceTo(blast) > BLAST_REACH || center.distanceTo(feet) > maxDistance) continue;
                    if (standable(world, cell, sure, possible)) candidates.add(cell);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        int lowest = Integer.MAX_VALUE;
        for (BlockPos cell : candidates) lowest = Math.min(lowest, cell.getY());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos cell : candidates) {
            if (cell.getY() > lowest + depthTolerance) continue;
            double distance = Vec3d.ofBottomCenter(cell).squaredDistanceTo(feet);
            if (distance < bestDistance || (distance == bestDistance && best != null && cell.getY() < best.getY())) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Feet and head room are air in the surely-removed terrain; the floor under is solid, dry, harmless and
     * is not among the blocks the blast could remove in any roll, so the pearl lands where it was solved to.
     */
    private static boolean standable(World world, BlockPos cell, Set<BlockPos> sure, Set<BlockPos> possible) {
        BlockPos floor = cell.down();
        if (possible.contains(floor)) return false;
        BlockState floorState = world.getBlockState(floor);
        if (floorState.isAir() || !floorState.isSolidBlock(world, floor) || !floorState.getFluidState().isEmpty()
                || floorState.isOf(Blocks.MAGMA_BLOCK) || floorState.isOf(Blocks.CAMPFIRE)
                || floorState.isOf(Blocks.SOUL_CAMPFIRE) || floorState.isOf(Blocks.CACTUS)) {
            return false;
        }
        return clearAfter(world, cell, sure) && clearAfter(world, cell.up(), sure);
    }

    private static boolean clearAfter(World world, BlockPos pos, Set<BlockPos> removed) {
        if (removed.contains(pos)) return true;
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && state.getFluidState().isEmpty()
                && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.SOUL_FIRE) && !state.isOf(Blocks.LAVA);
    }

    /**
     * The throw that lands on {@code cell}, or null when no pitch at the cell's bearing does. Among the
     * pitches that land it takes the one needing the least turn from {@code currentPitch} and refines it
     * at 0.1 degree for the best chance.
     */
    public static Shot solve(World world, BlockView sure, BlockView possible, PlayerEntity shooter, Vec3d movement,
                             BlockPos cell, float currentYaw, float currentPitch) {
        Vec3d spawn = ThrowPhysics.pearlSpawn(shooter.getEntityPos(), shooter.getEyeY() - shooter.getY());
        Vec3d floorCentre = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
        float yaw = ThrowPhysics.lookAlong(floorCentre.subtract(spawn), currentYaw)[0];
        float nearest = Float.NaN;
        for (float pitch = -89.0f; pitch <= 89.0f; pitch += 0.5f) {
            // The mean throw must land in the weakest blast and in the strongest alike.
            if (!lands(fly(world, sure, shooter, movement, yaw, pitch, Vec3d.ZERO), cell)
                    || (possible != sure && !lands(fly(world, possible, shooter, movement, yaw, pitch, Vec3d.ZERO), cell))) {
                continue;
            }
            if (Float.isNaN(nearest) || Math.abs(pitch - currentPitch) < Math.abs(nearest - currentPitch)) nearest = pitch;
        }
        if (Float.isNaN(nearest)) return null;
        float bestPitch = nearest;
        double bestChance = -1.0;
        for (int i = -5; i <= 5; i++) {
            float pitch = MathHelper.clamp(nearest + i * 0.1f, -90.0f, 90.0f);
            double chance = coarseChance(world, sure, possible, shooter, movement, cell, yaw, pitch);
            if (chance > bestChance + 1.0e-9
                    || (Math.abs(chance - bestChance) <= 1.0e-9 && Math.abs(pitch - currentPitch) < Math.abs(bestPitch - currentPitch))) {
                bestChance = chance;
                bestPitch = pitch;
            }
        }
        PearlSimulation.Path mean = fly(world, sure, shooter, movement, yaw, bestPitch, Vec3d.ZERO);
        Vec3d landing = mean.end() != null ? mean.end().getPos() : floorCentre;
        return new Shot(yaw, bestPitch, Math.max(0.0, chance(world, sure, possible, shooter, movement, cell, yaw, bestPitch)),
                mean.lastAge(), landing);
    }

    /**
     * Chance a throw with this look lands on {@code cell}: the launch spread rolled over {@value ThrowPhysics#FINE_COUNT} low-discrepancy
     * points, flown in both terrains, the worse one counted.
     */
    public static double chance(World world, BlockView sure, BlockView possible, PlayerEntity shooter, Vec3d movement,
                                BlockPos cell, float yaw, float pitch) {
        return worse(world, sure, possible, shooter, movement, cell, yaw, pitch,
                ThrowPhysics.FINE_POINTS, ThrowPhysics.FINE_WEIGHTS);
    }

    /** {@link #chance} with the 27-point grid rule: cheaper, used to rank looks. */
    private static double coarseChance(World world, BlockView sure, BlockView possible, PlayerEntity shooter,
                                       Vec3d movement, BlockPos cell, float yaw, float pitch) {
        return worse(world, sure, possible, shooter, movement, cell, yaw, pitch,
                ThrowPhysics.COARSE_POINTS, ThrowPhysics.COARSE_WEIGHTS);
    }

    private static double worse(World world, BlockView sure, BlockView possible, PlayerEntity shooter, Vec3d movement,
                                BlockPos cell, float yaw, float pitch, double[][] points, double[] weights) {
        return Math.min(chance(world, sure, shooter, movement, cell, yaw, pitch, points, weights),
                possible == sure ? 1.0 : chance(world, possible, shooter, movement, cell, yaw, pitch, points, weights));
    }

    private static double chance(World world, BlockView terrain, PlayerEntity shooter, Vec3d movement,
                                 BlockPos cell, float yaw, float pitch, double[][] points, double[] weights) {
        double total = 0.0;
        for (int i = 0; i < points.length; i++) {
            Vec3d spread = new Vec3d(points[i][0], points[i][1], points[i][2]).multiply(ThrowPhysics.SPREAD);
            if (lands(fly(world, terrain, shooter, movement, yaw, pitch, spread), cell)) total += weights[i];
        }
        return total;
    }

    private static PearlSimulation.Path fly(World world, BlockView terrain, PlayerEntity shooter, Vec3d movement,
                                            float yaw, float pitch, Vec3d spread) {
        PearlSimulation simulation = PearlSimulation.thrown(world, shooter, yaw, pitch, movement, spread);
        if (terrain != world) simulation.withTerrain(terrain);
        return simulation.trace(MAX_FLIGHT_TICKS);
    }

    /** The flight ended on the top face of {@code cell}'s floor, near the middle of it. */
    private static boolean lands(PearlSimulation.Path path, BlockPos cell) {
        HitResult end = path.end();
        if (!(end instanceof BlockHitResult block) || block.getSide() != Direction.UP) return false;
        Vec3d at = block.getPos();
        return Math.abs(at.x - (cell.getX() + 0.5)) <= LANDING_RADIUS
                && Math.abs(at.z - (cell.getZ() + 0.5)) <= LANDING_RADIUS
                && Math.abs(at.y - cell.getY()) <= LANDING_HEIGHT;
    }
}
