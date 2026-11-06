package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * AutoCart shot model: the server's arrow launch, rail candidates along its flight, and the local
 * player's own motion over the next ticks. The live ray still decides whether a click is valid.
 */
public final class AutoCartPlanner {
    /** {@code ProjectileEntity.calculateVelocity} triangular divergence per axis for a bow (uncertainty 1). */
    public static final double SPREAD = 0.0172275;
    /** Spread sampled for robustness: most of the triangular distribution's mass lies inside it. */
    static final double SPREAD_SAMPLE = SPREAD * 0.6;
    /** TNT minecart box (EntityType.TNT_MINECART) and its flat-rail spawn height. */
    static final double CART_WIDTH = 0.98;
    static final double CART_HEIGHT = 0.7;
    static final double CART_BASE = 0.0625;
    /** ProjectileUtil's largest age-based tolerance margin. */
    private static final double CART_MARGIN = 0.3;

    private AutoCartPlanner() {
    }

    // ------------------------------------------------------------------ launch

    /**
     * A bow shot as the server will build it. Until the spawn packet arrives the divergence is
     * unknown, so {@link #velocities()} also yields the spread extremes; an exact launch is the
     * spawn packet's own position and velocity.
     */
    public record Launch(Vec3d origin, Vec3d direction, double speed, Vec3d inherited, Vec3d exactVelocity) {
        public boolean exact() {
            return exactVelocity != null;
        }

        public Vec3d velocity() {
            return exact() ? exactVelocity : direction.multiply(speed).add(inherited);
        }

        /** Nominal first, then the six per-axis divergence samples (none once exact). */
        public List<Vec3d> velocities() {
            if (exact()) return List.of(exactVelocity);
            List<Vec3d> velocities = new ArrayList<>(7);
            velocities.add(velocity());
            for (int axis = 0; axis < 3; axis++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double offset = sign * SPREAD_SAMPLE;
                    Vec3d spread = new Vec3d(axis == 0 ? offset : 0, axis == 1 ? offset : 0, axis == 2 ? offset : 0);
                    velocities.add(direction.add(spread).multiply(speed).add(inherited));
                }
            }
            return velocities;
        }
    }

    /**
     * {@code BowItem.shoot}: {@code ProjectileEntity.setVelocity(shooter, pitch, yaw, 0, pull * 3, 1)}.
     * The server adds the shooter's {@code getMovement()}, which for a player is the position delta
     * of its last movement packet, not the client's post-friction velocity; vertical movement only
     * counts while the shooter is airborne.
     */
    public static Launch launch(Vec3d origin, float yaw, float pitch, float pull, Vec3d movement, boolean onGround) {
        float radians = 0.017453292F;
        double x = -MathHelper.sin(yaw * radians) * MathHelper.cos(pitch * radians);
        double y = -MathHelper.sin(pitch * radians);
        double z = MathHelper.cos(yaw * radians) * MathHelper.cos(pitch * radians);
        Vec3d inherited = new Vec3d(movement.x, onGround ? 0.0 : movement.y, movement.z);
        return new Launch(origin, new Vec3d(x, y, z).normalize(), pull * 3.0F, inherited, null);
    }

    /** The launch the server is about to build if {@code shooter} releases now. */
    public static Launch launch(Entity shooter, float yaw, float pitch, float pull) {
        // ArrowEntity(world, shooter, ...) spawns at the eye minus 0.1, from the last sent position.
        Vec3d origin = new Vec3d(shooter.getX(), shooter.getEyeY() - 0.1F, shooter.getZ());
        Vec3d movement = new Vec3d(shooter.getX() - shooter.lastX, shooter.getY() - shooter.lastY,
                shooter.getZ() - shooter.lastZ);
        return launch(origin, yaw, pitch, pull, movement, shooter.isOnGround());
    }

    public static Launch exact(Vec3d origin, Vec3d velocity) {
        return new Launch(origin, velocity.normalize(), velocity.length(), Vec3d.ZERO, velocity);
    }

    /**
     * One predicted flight per launch velocity, in order; a null entry never reaches a block.
     * {@code flown} moves have already happened and replay without collisions.
     */
    public static List<ArrowFlightPredictor.Result> flights(World world, Entity owner, Launch launch,
                                                             Predicate<Entity> ignoredCollision, int flown) {
        List<ArrowFlightPredictor.Result> flights = new ArrayList<>(7);
        for (Vec3d velocity : launch.velocities()) {
            flights.add(ArrowFlightPredictor.predict(world, owner, launch.origin(), velocity, 0, ignoredCollision, flown));
        }
        return flights;
    }

    // ------------------------------------------------------------------ rail

    /** A clickable flat-rail cell the nominal arrow crosses; intercepts follow the flight order. */
    public record RailCandidate(BlockPos rail, BlockPos support, Vec3d aimPoint, float yaw, float pitch,
                                int[] intercepts) {
        public int nominal() {
            return intercepts[0];
        }

        /** Flights whose intercept still leaves room for a use at {@code lastOffset}. */
        public int hits(int lastOffset, int margin) {
            int hits = 0;
            for (int intercept : intercepts) {
                if (AutoCartTiming.inTime(lastOffset, intercept, margin)) hits++;
            }
            return hits;
        }

        /** Earliest intercept among flights that hit at all, so timing holds for every one of them. */
        public int earliest() {
            int earliest = Integer.MAX_VALUE;
            for (int intercept : intercepts) {
                if (intercept > 0) earliest = Math.min(earliest, intercept);
            }
            return earliest == Integer.MAX_VALUE ? -1 : earliest;
        }

        public Vec3d blast() {
            return blastPoint(rail);
        }
    }

    /** TntMinecartEntity explodes at its own position: the rail cell's center at flat-rail height. */
    public static Vec3d blastPoint(BlockPos rail) {
        return new Vec3d(rail.getX() + 0.5, rail.getY() + CART_BASE, rail.getZ() + 0.5);
    }

    /** Cart box on a flat rail in {@code rail}. */
    static Box cartBox(BlockPos rail) {
        double half = CART_WIDTH / 2.0;
        return new Box(rail.getX() + 0.5 - half, rail.getY() + CART_BASE, rail.getZ() + 0.5 - half,
                rail.getX() + 0.5 + half, rail.getY() + CART_BASE + CART_HEIGHT, rail.getZ() + 0.5 + half);
    }

    /**
     * Every cell whose cart box the nominal flight may cross, reachable from {@code eye}. Cells the
     * player occupies are skipped: a cart at the feet is a detonation on the player.
     */
    public static List<RailCandidate> railCandidates(World world, ClientPlayerEntity player,
                                                     List<ArrowFlightPredictor.Result> flights, Vec3d eye,
                                                     float referenceYaw, Box occupied) {
        ArrowFlightPredictor.Result nominal = flights.isEmpty() ? null : flights.get(0);
        if (nominal == null) return List.of();
        double reach = player.getBlockInteractionRange();
        List<RailCandidate> candidates = new ArrayList<>();
        for (BlockPos cell : cellsAlong(nominal.path(), eye, reach + 1.5)) {
            if (eye.squaredDistanceTo(cell.toCenterPos()) > (reach + 1.5) * (reach + 1.5)
                    || cartBox(cell).intersects(occupied)) continue;
            int nominalIntercept = ArrowFlightPredictor.cartInterceptTick(world, player, nominal, cell);
            if (nominalIntercept < 1 || !railCellUsable(world, cell)) continue;
            BlockPos support = cell.down();
            Aim aim = faceAim(world, player, eye, support, Direction.UP, cell, referenceYaw);
            if (aim == null) continue;
            int[] intercepts = new int[flights.size()];
            intercepts[0] = nominalIntercept;
            for (int i = 1; i < flights.size(); i++) {
                ArrowFlightPredictor.Result flight = flights.get(i);
                intercepts[i] = flight == null ? -1 : ArrowFlightPredictor.cartInterceptTick(world, player, flight, cell);
            }
            candidates.add(new RailCandidate(cell, support, aim.point(), aim.yaw(), aim.pitch(), intercepts));
        }
        return candidates;
    }

    /** Cells within {@code radius} of {@code eye} whose cart box (with the age margin) a path segment can cross. */
    static Set<BlockPos> cellsAlong(List<Vec3d> path, Vec3d eye, double radius) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        Box near = new Box(eye, eye).expand(radius + 1.0);
        for (int i = 1; i < path.size(); i++) {
            Box swept = new Box(path.get(i - 1), path.get(i)).expand(CART_MARGIN);
            if (!swept.intersects(near)) continue;
            int minY = MathHelper.floor(swept.minY - CART_BASE - CART_HEIGHT);
            int maxY = MathHelper.floor(swept.maxY - CART_BASE);
            for (int x = MathHelper.floor(swept.minX); x <= MathHelper.floor(swept.maxX); x++) {
                for (int z = MathHelper.floor(swept.minZ); z <= MathHelper.floor(swept.maxZ); z++) {
                    for (int y = minY; y <= maxY; y++) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    /**
     * Rail kinds, plainest first. Detector and activator rails hold a standing cart like a plain
     * one (a powered activator rail only starts the TNT's fuse); a powered rail brakes when off but
     * moves the cart when on, so it comes last and never goes where it would be powered. All four
     * share {@code AbstractRailBlock}'s placement rule, so a cell usable for one is usable for all.
     * Held in its own class so the item registry is only touched once a rail is actually looked for.
     */
    private static final class Rails {
        static final List<Item> KINDS = List.of(Items.RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL, Items.POWERED_RAIL);
    }

    /**
     * Whether a rail of {@code kind} placed in {@code cell} (any cell when null) keeps a standing
     * cart where it is. A powered rail that would be powered pushes it away from a block beside it.
     */
    public static boolean railKindFits(World world, Item kind, BlockPos cell) {
        if (!Rails.KINDS.contains(kind)) return false;
        return kind != Items.POWERED_RAIL || cell == null || !poweredRailWouldRun(world, cell);
    }

    /** Hotbar slot of the plainest rail kind that fits {@code cell} (any cell when null), or -1. */
    public static int railSlot(World world, BlockPos cell) {
        for (Item kind : Rails.KINDS) {
            if (!railKindFits(world, kind, cell)) continue;
            int slot = hotbarSlot(kind);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    /** First hotbar slot holding {@code item}, or -1. */
    public static int hotbarSlot(Item item) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
    }

    /** Yaw/pitch from {@code eye} to {@code point}, with the yaw unwrapped next to {@code referenceYaw}. */
    public static float[] anglesTo(Vec3d eye, Vec3d point, float referenceYaw) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{referenceYaw + MathHelper.wrapDegrees(yaw - referenceYaw), MathHelper.clamp(pitch, -90.0F, 90.0F)};
    }

    /** {@code PoweredRailBlock.updateBlockState}: redstone power at the cell, or a powered rail it joins. */
    static boolean poweredRailWouldRun(World world, BlockPos cell) {
        if (world.isReceivingRedstonePower(cell)) return true;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockState state = world.getBlockState(cell.offset(direction).up(dy));
                if (state.isOf(Blocks.POWERED_RAIL) && state.get(PoweredRailBlock.POWERED)) return true;
            }
        }
        return false;
    }

    /** A rail the cart item can use, which stays flat and stationary when placed. */
    public static boolean railCellUsable(World world, BlockPos cell) {
        if (!world.isChunkLoaded(cell) || !world.getWorldBorder().contains(cell)) return false;
        BlockState state = world.getBlockState(cell);
        if (!state.isReplaceable() || !state.getFluidState().isEmpty()
                || !Blocks.RAIL.getDefaultState().canPlaceAt(world, cell)) return false;
        // A rail one block up a neighbor would turn ours into a slope, lifting the cart and moving it.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (world.getBlockState(cell.offset(direction).up()).isIn(BlockTags.RAILS)) return false;
        }
        return world.getOtherEntities(null, new Box(cell).expand(0.05),
                entity -> entity instanceof AbstractMinecartEntity).isEmpty();
    }

    // ------------------------------------------------------------------ aiming

    public record Aim(Vec3d point, float yaw, float pitch, BlockHitResult hit) {
    }

    /**
     * A point on {@code face} of {@code block} that a vanilla ray from {@code eye} reaches first.
     * With {@code placedCell} set, the click must also place into that cell. Points nearer the face
     * center come first: they keep the most tolerance for an eye that moves before the use.
     */
    public static Aim faceAim(World world, Entity player, Vec3d eye, BlockPos block, Direction face,
                              BlockPos placedCell, float referenceYaw) {
        return faceAim(world, player, eye, block, face, placedCell, referenceYaw, hit -> true);
    }

    /** Searches the face until the item's actual placement state also satisfies the caller. */
    public static Aim faceAim(World world, Entity player, Vec3d eye, BlockPos block, Direction face,
                              BlockPos placedCell, float referenceYaw, Predicate<BlockHitResult> accept) {
        double reach = player instanceof ClientPlayerEntity client ? client.getBlockInteractionRange() : 4.5;
        for (double[] uv : FACE_POINTS) {
            Vec3d point = facePoint(block, face, uv[0], uv[1]);
            if (eye.squaredDistanceTo(point) > reach * reach) continue;
            float[] angles = anglesTo(eye, point, referenceYaw);
            BlockHitResult hit = ray(world, player, eye, angles[0], angles[1], reach);
            if (hit == null || !hit.getBlockPos().equals(block) || hit.getSide() != face) continue;
            if (placedCell != null && !placedCell.equals(BlockPlacementValidator.placementCell(MinecraftClient.getInstance(), hit))) continue;
            if (!accept.test(hit)) continue;
            return new Aim(point, angles[0], angles[1], hit);
        }
        return null;
    }

    /**
     * A point inside {@code block}'s outline, {@code height} above its floor, that a vanilla ray from
     * {@code eye} reaches first: thin outlines such as a flat rail (2/16 tall) have no full-block face.
     */
    public static Aim insideAim(World world, Entity player, Vec3d eye, BlockPos block, double height, float referenceYaw) {
        double reach = player instanceof ClientPlayerEntity client ? client.getBlockInteractionRange() : 4.5;
        for (double[] uv : FACE_POINTS) {
            Vec3d point = new Vec3d(block.getX() + uv[0], block.getY() + height, block.getZ() + uv[1]);
            if (eye.squaredDistanceTo(point) > reach * reach) continue;
            float[] angles = anglesTo(eye, point, referenceYaw);
            BlockHitResult hit = ray(world, player, eye, angles[0], angles[1], reach);
            if (hit != null && hit.getBlockPos().equals(block)) return new Aim(point, angles[0], angles[1], hit);
        }
        return null;
    }

    private static final double[][] FACE_POINTS = {
            {0.5, 0.5}, {0.35, 0.5}, {0.65, 0.5}, {0.5, 0.35}, {0.5, 0.65},
            {0.3, 0.3}, {0.7, 0.3}, {0.3, 0.7}, {0.7, 0.7}, {0.15, 0.5}, {0.85, 0.5}, {0.5, 0.15}, {0.5, 0.85}};

    /** The interaction ray ({@code OUTLINE}, no fluids) from an arbitrary eye. */
    public static BlockHitResult ray(World world, Entity player, Vec3d eye, float yaw, float pitch, double reach) {
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(reach));
        HitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
    }

    static Vec3d facePoint(BlockPos block, Direction face, double u, double v) {
        double edge = face.getDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        return switch (face.getAxis()) {
            case X -> new Vec3d(block.getX() + edge, block.getY() + u, block.getZ() + v);
            case Y -> new Vec3d(block.getX() + u, block.getY() + edge, block.getZ() + v);
            case Z -> new Vec3d(block.getX() + u, block.getY() + v, block.getZ() + edge);
        };
    }

    // ------------------------------------------------------------------ own motion

    /**
     * The local player's bounding box after each of the next {@code ticks} movement ticks (index 0 is
     * now), assuming the current movement keys stay held while facing {@code yaw}. Mirrors
     * {@code LivingEntity.travel}: input acceleration, collision-adjusted move, then friction, gravity
     * and drag. Used only to aim ahead and to score cover; live rays still gate every use.
     */
    public static List<Box> predictBoxes(World world, ClientPlayerEntity player, int ticks, float yaw) {
        List<Box> boxes = new ArrayList<>(ticks + 1);
        Box box = player.getBoundingBox();
        boxes.add(box);
        Vec3d velocity = player.getVelocity();
        boolean onGround = player.isOnGround();
        Vec2f input = player.input == null ? Vec2f.ZERO : player.input.getMovementInput();
        boolean jump = player.input != null && player.input.playerInput.jump();
        float factor = (player.isSneaking() ? 0.3F : 1.0F) * 0.98F;
        Vec3d movementInput = new Vec3d(input.x * factor, 0.0, input.y * factor);
        double gravity = player.getFinalGravity();
        for (int tick = 0; tick < ticks; tick++) {
            if (jump && onGround) {
                float jumpVelocity = (float) player.getAttributeValue(EntityAttributes.JUMP_STRENGTH)
                        + player.getJumpBoostVelocityModifier();
                velocity = new Vec3d(velocity.x, Math.max(jumpVelocity, velocity.y), velocity.z);
                if (player.isSprinting()) {
                    float radians = yaw * 0.017453292F;
                    velocity = velocity.add(-MathHelper.sin(radians) * 0.2, 0.0, MathHelper.cos(radians) * 0.2);
                }
            }
            float slipperiness = onGround ? world.getBlockState(BlockPos.ofFloored(
                    box.getCenter().x, box.minY - 0.5000001, box.getCenter().z)).getBlock().getSlipperiness() : 1.0F;
            float speed = onGround ? player.getMovementSpeed() * (0.21600002F / (slipperiness * slipperiness * slipperiness))
                    : player.isSprinting() ? 0.025999999F : 0.02F;
            velocity = velocity.add(inputVelocity(movementInput, speed, yaw));
            Vec3d move = Entity.adjustMovementForCollisions(player, velocity, box, world, List.of());
            box = box.offset(move);
            boxes.add(box);
            boolean verticalCollision = Math.abs(move.y - velocity.y) > 1.0e-7;
            onGround = verticalCollision && velocity.y < 0;
            float friction = slipperiness * 0.91F;
            velocity = new Vec3d(Math.abs(move.x - velocity.x) > 1.0e-7 ? 0.0 : velocity.x * friction,
                    ((verticalCollision ? 0.0 : velocity.y) - gravity) * 0.98,
                    Math.abs(move.z - velocity.z) > 1.0e-7 ? 0.0 : velocity.z * friction);
        }
        return boxes;
    }

    /** {@code Entity.movementInputToVelocity}. */
    static Vec3d inputVelocity(Vec3d movementInput, float speed, float yaw) {
        double length = movementInput.lengthSquared();
        if (length < 1.0E-7) return Vec3d.ZERO;
        Vec3d scaled = (length > 1.0 ? movementInput.normalize() : movementInput).multiply(speed);
        float sin = MathHelper.sin(yaw * 0.017453292F);
        float cos = MathHelper.cos(yaw * 0.017453292F);
        return new Vec3d(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    /** Eye position for a predicted box of {@code player}. */
    public static Vec3d eyeOf(ClientPlayerEntity player, Box box) {
        return new Vec3d(box.getCenter().x, box.minY + (player.getEyeY() - player.getY()), box.getCenter().z);
    }
}
