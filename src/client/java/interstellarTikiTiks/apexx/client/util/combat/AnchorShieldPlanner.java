package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where to put the glowstone that shields you from your own respawn anchor.
 *
 * <p>Every free cell around the anchor (the 26 neighbours, the cells on top of blocks that sit in the
 * way, and the cells the blast-to-body lines cross) is scored by the share of your body's explosion
 * samples it still leaves open: the samples are vanilla's lattice over your box (and over the box you
 * will be in next tick), each tested with a collider ray to the anchor's centre with the anchor's own
 * cell ignored, as the anchor removes itself before it blows. A cell is kept only when it lowers that
 * share, leaves at least one point of the anchor clickable from the eye, does not close your only way
 * out, and has a solid, reachable, visible support face (not the anchor, not a block that opens or
 * toggles on a click). Ties go to the support face that needs the least turn from the anchor.</p>
 *
 * <p>It reads the world and plans; it never aims or clicks. The scoring weights are starting values
 * (UNVERIFIED in game).</p>
 */
public final class AnchorShieldPlanner {

    /** Score per unit of exposure left (0..1) and per degree of turn between the shield and the anchor. */
    static final double EXPOSURE_WEIGHT = 100.0;
    static final double TURN_WEIGHT = 0.08;
    private static final double[] FACE_GRID = {0.2, 0.5, 0.8};

    private AnchorShieldPlanner() {
    }

    /** A planned glowstone: the cell, the support face to click and its aim point, and what it leaves exposed. */
    public record Shield(BlockPos cell, BlockPos support, Direction side, Vec3d aimPoint,
                         double exposureAfter, double turnDeg, double score) {
        public BlockHitResult supportHit() {
            return new BlockHitResult(aimPoint, side, support, false);
        }
    }

    /**
     * The plan: the exposure you have now, the best shield (null when none qualifies) and, with no
     * shield, why.
     */
    public record Plan(double exposureBefore, Shield shield, String reason) {
    }

    /** Lower is better: exposure left dominates, turn breaks near-ties. */
    static double rankScore(double exposureAfter, double turnDeg) {
        return exposureAfter * EXPOSURE_WEIGHT + Math.max(0.0, turnDeg) * TURN_WEIGHT;
    }

    /** Share of {@code visible} samples (out of {@code total}) a solid {@code cover} box does not cut off from the blast. */
    static double exposureWith(List<Vec3d> visible, int total, Vec3d blast, Box cover) {
        if (total <= 0) return 0.0;
        int open = 0;
        for (Vec3d sample : visible) {
            if (!cover.contains(sample) && cover.raycast(sample, blast).isEmpty()) ++open;
        }
        return (double) open / total;
    }

    /**
     * Plans the shield for the anchor at {@code anchor}.
     *
     * @param boxes your boxes to protect: the current one first, then predicted ones
     */
    public static Plan plan(MinecraftClient mc, BlockPos anchor, List<Box> boxes) {
        ClientPlayerEntity player = mc.player;
        World world = mc.world;
        if (player == null || world == null || anchor == null || boxes.isEmpty()) {
            return new Plan(0.0, null, "not in a world");
        }
        Vec3d eye = player.getEyePos();
        Vec3d blast = Vec3d.ofCenter(anchor);
        double reach = player.getBlockInteractionRange() - 0.05;

        List<List<Vec3d>> visible = new ArrayList<>(boxes.size());
        int[] totals = new int[boxes.size()];
        double before = 0.0;
        for (int i = 0; i < boxes.size(); ++i) {
            Box box = boxes.get(i);
            List<Vec3d> open = BlastModel.visibleSamples(world, player, blast, box, anchor);
            visible.add(open);
            totals[i] = BlastModel.sampleCount(box);
            if (totals[i] > 0) before = Math.max(before, (double) open.size() / totals[i]);
        }
        if (before <= 0.0) return new Plan(0.0, null, "already covered");

        List<Vec3d> clickPoints = anchorClickPoints(mc, anchor, eye, reach);
        if (clickPoints.isEmpty()) return new Plan(before, null, "the anchor is not clickable from where you stand");

        Set<BlockPos> cells = new LinkedHashSet<>();
        Set<BlockPos> stacked = new LinkedHashSet<>();
        for (int ox = -1; ox <= 1; ++ox) {
            for (int oy = -1; oy <= 1; ++oy) {
                for (int oz = -1; oz <= 1; ++oz) {
                    if (ox == 0 && oy == 0 && oz == 0) continue;
                    cells.add(anchor.add(ox, oy, oz));
                }
            }
        }
        for (Box box : boxes) {
            walk(blast, box.getCenter(), cells);
            walk(blast, new Vec3d(box.getCenter().x, box.maxY - 0.1, box.getCenter().z), cells);
        }
        walk(blast, eye, cells);
        for (BlockPos cell : List.copyOf(cells)) {
            if (blocking(world, anchor, cell)) stacked.add(cell.up());
        }
        cells.addAll(stacked);

        Shield best = null;
        int free = 0;
        int helpful = 0;
        int clickable = 0;
        for (BlockPos cell : cells) {
            if (!freeCell(world, player, anchor, cell, boxes)) continue;
            ++free;
            Box cellBox = new Box(cell);
            double after = 0.0;
            for (int i = 0; i < boxes.size(); ++i) {
                after = Math.max(after, exposureWith(visible.get(i), totals[i], blast, cellBox));
            }
            if (after >= before - 1.0e-9) continue;
            ++helpful;
            List<Vec3d> stillClickable = new ArrayList<>();
            for (Vec3d point : clickPoints) {
                if (cellBox.raycast(eye, point).isEmpty()) stillClickable.add(point);
            }
            if (stillClickable.isEmpty()) continue;
            ++clickable;
            Shield shield = bestSupport(mc, anchor, cell, eye, stillClickable, after);
            if (shield != null && (best == null || shield.score() < best.score())) best = shield;
        }
        if (best != null) return new Plan(before, best, null);
        String reason;
        if (free == 0) reason = "no free cell around the anchor";
        else if (helpful == 0) reason = "no free cell between the anchor and you";
        else if (clickable == 0) reason = "every shield spot would hide the anchor from your click";
        else reason = "no solid face to place the glowstone against within reach";
        return new Plan(before, null, reason);
    }

    /** Points of the anchor's faces toward the eye, in reach, whose interaction ray hits that face first. */
    public static List<Vec3d> anchorClickPoints(MinecraftClient mc, BlockPos anchor, Vec3d eye, double reach) {
        List<Vec3d> points = new ArrayList<>();
        for (Direction face : Direction.values()) {
            Vec3d center = Vec3d.ofCenter(anchor).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5);
            Vec3d toEye = eye.subtract(center);
            if (toEye.x * face.getOffsetX() + toEye.y * face.getOffsetY() + toEye.z * face.getOffsetZ() <= 0.0) {
                continue;
            }
            for (double u : FACE_GRID) {
                for (double v : FACE_GRID) {
                    Vec3d point = facePoint(anchor, face, u, v);
                    if (eye.squaredDistanceTo(point) > reach * reach) continue;
                    if (firstHit(mc, eye, point, anchor, face)) points.add(point);
                }
            }
        }
        return points;
    }

    private static Shield bestSupport(MinecraftClient mc, BlockPos anchor, BlockPos cell, Vec3d eye,
                                      List<Vec3d> clickPoints, double after) {
        World world = mc.world;
        double reach = mc.player.getBlockInteractionRange() - 0.05;
        Shield best = null;
        for (Direction side : Direction.values()) {
            BlockPos support = cell.offset(side.getOpposite());
            if (support.equals(anchor) || !clickableSupport(world, support)) continue;
            Vec3d point = null;
            double turn = Double.MAX_VALUE;
            for (double u : FACE_GRID) {
                for (double v : FACE_GRID) {
                    Vec3d candidate = facePoint(support, side, u, v);
                    if (eye.squaredDistanceTo(candidate) > reach * reach) continue;
                    if (!firstHit(mc, eye, candidate, support, side)) continue;
                    double t = minAngle(eye, candidate, clickPoints);
                    if (t < turn) {
                        turn = t;
                        point = candidate;
                    }
                }
            }
            if (point == null) continue;
            double score = rankScore(after, turn);
            if (best == null || score < best.score()) {
                best = new Shield(cell.toImmutable(), support.toImmutable(), side, point, after, turn, score);
            }
        }
        return best;
    }

    /** A block a glowstone can be placed against with a plain click: solid, no block entity, nothing that opens or toggles. */
    static boolean clickableSupport(World world, BlockPos support) {
        BlockState state = world.getBlockState(support);
        if (state.isAir() || state.isLiquid() || state.isReplaceable() || !state.isSolidBlock(world, support)) return false;
        if (world.getBlockEntity(support) != null) return false;
        return !(state.isOf(Blocks.RESPAWN_ANCHOR) || state.isOf(Blocks.CRAFTING_TABLE) || state.isIn(BlockTags.ANVIL)
                || state.isOf(Blocks.NOTE_BLOCK) || state.isOf(Blocks.CARTOGRAPHY_TABLE)
                || state.isOf(Blocks.SMITHING_TABLE) || state.isOf(Blocks.LOOM) || state.isOf(Blocks.STONECUTTER)
                || state.isOf(Blocks.GRINDSTONE) || state.isOf(Blocks.FLETCHING_TABLE) || state.isOf(Blocks.TNT)
                || state.isOf(Blocks.REDSTONE_ORE) || state.isOf(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS) || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.BUTTONS));
    }

    private static boolean freeCell(World world, ClientPlayerEntity player, BlockPos anchor, BlockPos cell, List<Box> boxes) {
        if (cell.equals(anchor)) return false;
        BlockState state = world.getBlockState(cell);
        if (!world.getFluidState(cell).isEmpty()) return false;
        if (!(state.isReplaceable() || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE))) return false;
        Box cellBox = new Box(cell).expand(0.001);
        for (Box box : boxes) {
            if (cellBox.intersects(box)) return false;
        }
        BlockPos base = player.getBlockPos();
        if (cell.equals(base) || cell.equals(base.up()) || closesOnlyEscape(world, cell, base)) return false;
        return world.canPlace(Blocks.GLOWSTONE.getDefaultState(), cell, ShapeContext.of(player));
    }

    private static boolean blocking(World world, BlockPos anchor, BlockPos cell) {
        if (cell.equals(anchor)) return false;
        BlockState state = world.getBlockState(cell);
        return !state.isOf(Blocks.GLOWSTONE) && !state.isReplaceable() && !state.isLiquid()
                && state.isSolidBlock(world, cell);
    }

    private static boolean closesOnlyEscape(World world, BlockPos cell, BlockPos base) {
        int open = 0;
        boolean candidate = false;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos route = base.offset(direction);
            BlockState feet = world.getBlockState(route);
            BlockState head = world.getBlockState(route.up());
            if (feet.isReplaceable() && !feet.isLiquid() && head.isReplaceable() && !head.isLiquid()) {
                ++open;
                candidate |= route.equals(cell);
            }
        }
        return candidate && open <= 1;
    }

    /** The cells a straight line from {@code from} to {@code to} passes through (0.2-block steps). */
    static void walk(Vec3d from, Vec3d to, Set<BlockPos> out) {
        Vec3d delta = to.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.2));
        for (int i = 1; i < steps; ++i) {
            out.add(BlockPos.ofFloored(from.add(delta.multiply((double) i / steps))));
        }
    }

    private static double minAngle(Vec3d eye, Vec3d point, List<Vec3d> others) {
        Vec3d a = point.subtract(eye).normalize();
        double best = Double.MAX_VALUE;
        for (Vec3d other : others) {
            Vec3d b = other.subtract(eye).normalize();
            double dot = Math.max(-1.0, Math.min(1.0, a.dotProduct(b)));
            best = Math.min(best, Math.toDegrees(Math.acos(dot)));
        }
        return best;
    }

    static Vec3d facePoint(BlockPos pos, Direction face, double u, double v) {
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

    private static boolean firstHit(MinecraftClient mc, Vec3d eye, Vec3d point, BlockPos pos, Direction face) {
        Vec3d through = point.add(point.subtract(eye).normalize().multiply(0.25));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, through, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && hit.getSide() == face;
    }

    /** Degrees between the current applied look and {@code point}, for callers that rank by turn. */
    public static double turnFromLook(Vec3d eye, Vec3d point) {
        return SmoothAim.turnTo(eye, point, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
    }
}
