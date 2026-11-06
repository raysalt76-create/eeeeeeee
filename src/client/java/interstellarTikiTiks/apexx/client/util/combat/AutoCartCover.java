package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SlabBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe-mode cover: a wooden plank, log or slab that removes the most blast exposure.
 *
 * <p>Damage scales with {@code ExplosionImpl.calculateReceivedDamage}: the fraction of a lattice of
 * points on the player's box with a clear collider ray to the blast. The placed shape is scored against the
 * box the player is predicted to occupy when the arrow reaches the cart, and must never touch the
 * player's server-side or post-move box, the cart, or the remaining arrow flight.</p>
 */
public final class AutoCartCover {
    private AutoCartCover() {
    }

    public record Plan(BlockPos cell, BlockPos support, Direction face, Vec3d aimPoint, float yaw, float pitch,
                       Item item, BlockState state, List<Box> collision, double exposureBefore, double exposureAfter) {
        public Plan { collision = List.copyOf(collision); }
    }

    /**
     * Everything the cover depends on.
     *
     * @param placeOffset release offset of the cover use; the arrow has passed earlier segments
     * @param serverBox   player box the server checks the placement against (before this tick's move)
     * @param placeBox    player box after the placement tick's move, which a queued check uses
     * @param eye         eye the placement ray starts from
     * @param exposedBox  player box when the arrow reaches the cart
     */
    public record Context(BlockPos rail, List<Vec3d> path, int intercept, int placeOffset,
                          Box serverBox, Box placeBox, Vec3d eye, Box exposedBox) {
        public Vec3d blast() {
            return AutoCartPlanner.blastPoint(rail);
        }
    }

    /** Fraction of the box's explosion samples with a clear collider ray to the blast. */
    public static double exposure(World world, Entity player, Vec3d blast, Box box) {
        List<Vec3d> samples = exposureSamples(box);
        return (double) visible(world, player, blast, samples).size() / samples.size();
    }

    public static boolean isCoverStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem
                && (stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.WOODEN_SLABS));
    }

    public static Plan find(World world, ClientPlayerEntity player, Context context, float referenceYaw,
                            List<ItemStack> available) {
        if (context.intercept() < 1 || available.isEmpty()) return null;
        Vec3d blast = context.blast();
        List<Vec3d> exposedSamples = exposureSamples(context.exposedBox());
        List<Vec3d> exposedVisible = visible(world, player, blast, exposedSamples);
        List<Vec3d> stillSamples = exposureSamples(context.placeBox());
        List<Vec3d> stillVisible = visible(world, player, blast, stillSamples);
        double before = (double) exposedVisible.size() / exposedSamples.size();
        if (before <= 0) return null;
        double stillBefore = (double) stillVisible.size() / stillSamples.size();

        record Scored(BlockPos cell, ItemStack stack, List<Box> collision, double after, double score, double distance) {
        }
        record Exposure(double after, double score) { }
        List<Scored> scored = new ArrayList<>();
        Box exposed = context.exposedBox();
        BlockPos feet = BlockPos.ofFloored(exposed.getCenter().x, exposed.minY + 1.0e-3, exposed.getCenter().z);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos cell = feet.add(x, y, z);
                    if (!towardBlast(exposed, blast, cell) || !cellUsable(world, context, cell)) continue;
                    // Wood species share shapes: score each shape once while retaining item fallbacks.
                    Map<List<Box>, Exposure> shapeScores = new HashMap<>();
                    for (ItemStack stack : available) {
                        if (!isCoverStack(stack)) continue;
                        BlockState base = ((BlockItem) stack.getItem()).getBlock().getDefaultState();
                        List<BlockState> shapes = base.getBlock() instanceof SlabBlock
                                ? List.of(base.with(SlabBlock.TYPE, SlabType.BOTTOM), base.with(SlabBlock.TYPE, SlabType.TOP))
                                : List.of(base);
                        for (BlockState shape : shapes) {
                            List<Box> collision = collision(world, cell, shape);
                            if (!placeable(world, player, context, cell, shape, collision)) continue;
                            Exposure scoredShape = shapeScores.computeIfAbsent(collision, boxes -> {
                                double after = exposureWithCover(exposedVisible, exposedSamples.size(), blast, boxes);
                                if (after >= before - 1.0e-9) return new Exposure(after, Double.POSITIVE_INFINITY);
                                // Weight in the box of a player who stops moving before the blast.
                                double still = exposureWithCover(stillVisible, stillSamples.size(), blast, boxes);
                                return new Exposure(after, after * 0.7 + Math.min(still, stillBefore) * 0.3);
                            });
                            if (!Double.isFinite(scoredShape.score())) continue;
                            scored.add(new Scored(cell, stack, collision, scoredShape.after(), scoredShape.score(),
                                    exposed.getCenter().squaredDistanceTo(cell.toCenterPos())));
                        }
                    }
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).thenComparingDouble(Scored::distance));
        for (Scored candidate : scored) {
            Plan plan = reachablePlan(world, player, context, candidate.cell(), candidate.stack(), candidate.collision(),
                    before, candidate.after(), referenceYaw);
            if (plan != null) return plan;
        }
        return null;
    }

    /** Live recheck of a planned cover against the current context, without its reach. */
    public static boolean stillProtects(World world, ClientPlayerEntity player, Context context, Plan plan) {
        if (plan == null || context.intercept() < 1
                || !placeable(world, player, context, plan.cell(), plan.state(), plan.collision())) return false;
        Vec3d blast = context.blast();
        List<Vec3d> samples = exposureSamples(context.exposedBox());
        List<Vec3d> visible = visible(world, player, blast, samples);
        return !visible.isEmpty()
                && exposureWithCover(visible, samples.size(), blast, plan.collision()) < (double) visible.size() / samples.size();
    }

    private static boolean cellUsable(World world, Context context, BlockPos cell) {
        return !cell.equals(context.rail()) && world.isChunkLoaded(cell) && world.getWorldBorder().contains(cell)
                && world.getBlockState(cell).isReplaceable() && world.getFluidState(cell).isEmpty();
    }

    private static boolean placeable(World world, ClientPlayerEntity player, Context context, BlockPos cell,
                                     BlockState state, List<Box> collision) {
        if (!cellUsable(world, context, cell) || collision.isEmpty()) return false;
        Box cart = AutoCartPlanner.cartBox(context.rail());
        for (Box shape : collision) {
            if (shape.intersects(cart) || shape.intersects(context.serverBox())
                    || shape.intersects(context.placeBox()) || shape.intersects(context.exposedBox())) return false;
        }
        return state.canPlaceAt(world, cell) && world.canPlace(state, cell, ShapeContext.of(player))
                && !obstructsArrow(context.path(), context.intercept(), collision, context.placeOffset());
    }

    private static Plan reachablePlan(World world, ClientPlayerEntity player, Context context, BlockPos cell,
                                      ItemStack stack, List<Box> expectedShape, double before, double after, float referenceYaw) {
        // Prefer the floor; otherwise a real neighboring solid face. A face that consumes the use
        // (containers, doors, workstations) would open it instead of placing the cover.
        for (Direction face : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.DOWN}) {
            BlockPos support = cell.offset(face.getOpposite());
            BlockState state = world.getBlockState(support);
            if (state.isReplaceable() || state.hasBlockEntity() || consumesUse(state)
                    || !state.isSideSolidFullSquare(world, support, face)) continue;
            AutoCartPlanner.Aim aim = AutoCartPlanner.faceAim(world, player, context.eye(), support, face, cell, referenceYaw,
                    hit -> {
                        BlockState placed = placementState(world, player, cell, stack, hit);
                        return placed != null && collision(world, cell, placed).equals(expectedShape);
                    });
            if (aim != null) {
                BlockState placed = placementState(world, player, cell, stack, aim.hit());
                return new Plan(cell, support, face, aim.point(), aim.yaw(), aim.pitch(), stack.getItem(), placed,
                        expectedShape, before, after);
            }
        }
        return null;
    }

    /** Fresh aim at a planned cover from a new eye, on the same support face. */
    public static Plan reaim(World world, ClientPlayerEntity player, Plan plan, Vec3d eye, float referenceYaw) {
        AutoCartPlanner.Aim aim = AutoCartPlanner.faceAim(world, player, eye, plan.support(), plan.face(), plan.cell(), referenceYaw,
                hit -> canPlace(world, player, plan, hit));
        if (aim == null) return null;
        return new Plan(plan.cell(), plan.support(), plan.face(), aim.point(), aim.yaw(), aim.pitch(),
                plan.item(), plan.state(), plan.collision(), plan.exposureBefore(), plan.exposureAfter());
    }

    static boolean consumesUse(BlockState state) {
        return state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.TRAPDOORS) || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.RAILS)
                || state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.SMITHING_TABLE)
                || state.isOf(Blocks.CARTOGRAPHY_TABLE) || state.isOf(Blocks.LOOM)
                || state.isOf(Blocks.STONECUTTER) || state.isOf(Blocks.GRINDSTONE)
                || state.isOf(Blocks.ANVIL) || state.isOf(Blocks.CHIPPED_ANVIL) || state.isOf(Blocks.DAMAGED_ANVIL)
                || state.isOf(Blocks.NOTE_BLOCK) || state.isOf(Blocks.RESPAWN_ANCHOR) || state.isOf(Blocks.DRAGON_EGG);
    }

    public static boolean matches(BlockHitResult hit, Plan plan) {
        return plan != null && hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(plan.support()) && hit.getSide() == plan.face()
                && plan.cell().equals(BlockPlacementValidator.placementCell(MinecraftClient.getInstance(), hit));
    }

    public static boolean canPlace(World world, ClientPlayerEntity player, Plan plan, BlockHitResult hit) {
        return placementState(world, player, plan.cell(), plan.item().getDefaultStack(), hit) == plan.state();
    }

    private static BlockState placementState(World world, ClientPlayerEntity player, BlockPos cell, ItemStack stack, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem item) || hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
        BlockState support = world.getBlockState(hit.getBlockPos());
        if (!context.getBlockPos().equals(cell) || !context.canPlace()
                || support.hasBlockEntity() || consumesUse(support)
                || !world.getBlockState(cell).canReplace(context) || !world.getFluidState(cell).isEmpty()) return null;
        BlockState placed = item.getBlock().getPlacementState(context);
        return placed != null && placed.canPlaceAt(world, cell) && world.canPlace(placed, cell, ShapeContext.of(player)) ? placed : null;
    }

    private static List<Box> collision(World world, BlockPos cell, BlockState state) {
        return state.getCollisionShape(world, cell).getBoundingBoxes().stream().map(box -> box.offset(cell)).toList();
    }

    /** Horizontally between the player and the blast, or under/over it; never fully behind. */
    static boolean towardBlast(Box player, Vec3d blast, BlockPos cell) {
        Vec3d center = player.getCenter();
        double bx = blast.x - center.x;
        double bz = blast.z - center.z;
        double cx = cell.getX() + 0.5 - center.x;
        double cz = cell.getZ() + 0.5 - center.z;
        double length = Math.sqrt(bx * bx + bz * bz);
        if (length < 0.1) return true;
        return (cx * bx + cz * bz) / length > -0.25;
    }

    /** Cover placed at {@code coverOffset} is solid for flight segments after it, up to the intercept. */
    static boolean obstructsArrow(List<Vec3d> path, int intercept, Box cover, int coverOffset) {
        return obstructsArrow(path, intercept, List.of(cover), coverOffset);
    }

    static boolean obstructsArrow(List<Vec3d> path, int intercept, List<Box> collision, int coverOffset) {
        for (int i = Math.max(1, coverOffset + 1); i <= intercept && i < path.size(); i++) {
            for (Box cover : collision) {
                if (cover.contains(path.get(i - 1)) || cover.raycast(path.get(i - 1), path.get(i)).isPresent()) return true;
            }
        }
        return false;
    }

    /** Same sample lattice and X/Z centering as 1.21.11 ExplosionImpl.calculateReceivedDamage. */
    static List<Vec3d> exposureSamples(Box bounds) {
        double dx = 1 / (bounds.getLengthX() * 2 + 1);
        double dy = 1 / (bounds.getLengthY() * 2 + 1);
        double dz = 1 / (bounds.getLengthZ() * 2 + 1);
        double offsetX = (1 - Math.floor(1 / dx) * dx) / 2;
        double offsetZ = (1 - Math.floor(1 / dz) * dz) / 2;
        List<Vec3d> points = new ArrayList<>();
        for (double x = 0; x <= 1; x += dx) {
            for (double y = 0; y <= 1; y += dy) {
                for (double z = 0; z <= 1; z += dz) {
                    points.add(new Vec3d(bounds.minX + x * bounds.getLengthX() + offsetX,
                            bounds.minY + y * bounds.getLengthY(),
                            bounds.minZ + z * bounds.getLengthZ() + offsetZ));
                }
            }
        }
        return List.copyOf(points);
    }

    static List<Vec3d> visible(World world, Entity player, Vec3d blast, List<Vec3d> samples) {
        return samples.stream().filter(point -> world.raycast(new RaycastContext(point, blast,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player)).getType()
                == HitResult.Type.MISS).toList();
    }

    static double exposureWithCover(List<Vec3d> visible, int totalSamples, Vec3d blast, Box cover) {
        return exposureWithCover(visible, totalSamples, blast, List.of(cover));
    }

    static double exposureWithCover(List<Vec3d> visible, int totalSamples, Vec3d blast, List<Box> collision) {
        long unblocked = visible.stream().filter(point -> collision.stream().noneMatch(cover -> cover.contains(point)
                || cover.contains(blast) || cover.raycast(point, blast).isPresent())).count();
        return (double) unblocked / totalSamples;
    }
}
