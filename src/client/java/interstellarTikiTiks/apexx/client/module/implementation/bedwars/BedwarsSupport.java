package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;

import java.util.Set;

/** Pure helpers shared by the Bedwars modules: building-block choice, movement geometry, input remapping. */
final class BedwarsSupport {

    /** Blocks that are technically placeable but bad or unsafe to bridge with. */
    private static final Set<Block> DENY = Set.of(
            Blocks.TNT, Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE,
            Blocks.SMITHING_TABLE, Blocks.LOOM, Blocks.NOTE_BLOCK, Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK,
            Blocks.SOUL_SAND, Blocks.MAGMA_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
            Blocks.FROSTED_ICE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.TARGET);

    private BedwarsSupport() {
    }

    /** A block item that is safe and sensible to bridge or clutch with. */
    static boolean isBuildingBlock(ItemStack stack, boolean allowPartial) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        Block block = item.getBlock();
        if (block instanceof FallingBlock || block instanceof BlockEntityProvider || DENY.contains(block)) return false;
        BlockState state = block.getDefaultState();
        if (state.isAir()) return false;
        if (state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty()) return false;
        return allowPartial || state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }

    /**
     * Hotbar slot (0-8) to build from, or -1. {@code mode}: "Held Only", "First In Hotbar" or
     * "Biggest Stack" (ties keep the held slot so the hotbar does not flicker).
     */
    static int findBlockSlot(PlayerInventory inv, String mode, boolean allowPartial) {
        int held = inv.getSelectedSlot();
        if ("Held Only".equals(mode)) {
            return isBuildingBlock(inv.getStack(held), allowPartial) ? held : -1;
        }
        if ("First In Hotbar".equals(mode)) {
            if (isBuildingBlock(inv.getStack(held), allowPartial)) return held;
            for (int i = 0; i < 9; i++) {
                if (isBuildingBlock(inv.getStack(i), allowPartial)) return i;
            }
            return -1;
        }
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!isBuildingBlock(stack, allowPartial)) continue;
            int count = stack.getCount();
            if (count > bestCount || (count == bestCount && i == held)) {
                best = i;
                bestCount = count;
            }
        }
        return best;
    }

    /** Usable building blocks in the hotbar plus the offhand. */
    static int countBlocks(PlayerEntity player, boolean allowPartial) {
        PlayerInventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (isBuildingBlock(stack, allowPartial)) total += stack.getCount();
        }
        ItemStack off = player.getOffHandStack();
        if (isBuildingBlock(off, allowPartial)) total += off.getCount();
        return total;
    }

    /** World-space horizontal movement vector the given input produces at {@code yaw} (vanilla's formula). */
    static Vec3d inputVector(int forward, int strafe, float yaw) {
        float sin = MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        float cos = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        return new Vec3d(strafe * cos - forward * sin, 0.0, forward * cos + strafe * sin);
    }

    static int forwardAxis(PlayerInput in) {
        return (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
    }

    static int strafeAxis(PlayerInput in) {
        return (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
    }

    /**
     * Direction the player is travelling, as a horizontal unit vector: the real velocity when it is
     * meaningful, otherwise what the held movement keys ask for at the visible camera yaw.
     */
    static Vec3d travelDirection(ClientPlayerEntity player, float cameraYaw) {
        Vec3d v = player.getVelocity();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        if (h > 0.04) return new Vec3d(v.x / h, 0.0, v.z / h);
        if (player.input == null) return Vec3d.ZERO;
        PlayerInput in = player.input.playerInput;
        int f = forwardAxis(in);
        int s = strafeAxis(in);
        if (f == 0 && s == 0) return Vec3d.ZERO;
        Vec3d w = inputVector(f, s, cameraYaw);
        double len = w.length();
        return len < 1.0E-6 ? Vec3d.ZERO : w.multiply(1.0 / len);
    }

    /**
     * Movement correction for a server-only (silent) rotation: picks the movement key combination
     * that, applied at {@code toYaw}, points closest to where the keys pointed at {@code fromYaw}.
     * Only whole keys are chosen, exactly like a player pressing a different key, so the movement
     * stays a normal keyboard input. Sprint is kept only while forward stays held.
     */
    static PlayerInput correctMovement(PlayerInput in, float fromYaw, float toYaw) {
        int f = forwardAxis(in);
        int s = strafeAxis(in);
        if ((f == 0 && s == 0) || Math.abs(MathHelper.wrapDegrees(toYaw - fromYaw)) < 1.0f) return in;
        Vec3d want = inputVector(f, s, fromYaw).normalize();
        int bestF = f;
        int bestS = s;
        double bestDot = -2.0;
        for (int cf = -1; cf <= 1; cf++) {
            for (int cs = -1; cs <= 1; cs++) {
                if (cf == 0 && cs == 0) continue;
                double dot = inputVector(cf, cs, toYaw).normalize().dotProduct(want);
                if (dot > bestDot + 1.0E-9) {
                    bestDot = dot;
                    bestF = cf;
                    bestS = cs;
                }
            }
        }
        return new PlayerInput(bestF > 0, bestF < 0, bestS > 0, bestS < 0, in.jump(), in.sneak(),
                in.sprint() && bestF > 0);
    }

    /** The player is standing where the next {@code ticks} of horizontal motion would step off a drop deeper than {@code drop}. */
    static boolean edgeAhead(World world, PlayerEntity player, double ticks, double drop) {
        if (!player.isOnGround()) return false;
        Vec3d v = player.getVelocity();
        if (v.x * v.x + v.z * v.z < 1.0E-5) return false;
        // A little past the drop so a floor exactly {@code drop} below (e.g. a one-block step) still counts as ground.
        Box moved = player.getBoundingBox().offset(v.x * ticks, -(drop + 0.05), v.z * ticks);
        return world.isSpaceEmpty(player, moved);
    }

    /** A collision lies within {@code clearance} blocks above the player's head. */
    static boolean ceilingAbove(World world, PlayerEntity player, double clearance) {
        Box box = player.getBoundingBox();
        Box probe = new Box(box.minX, box.maxY, box.minZ, box.maxX, box.maxY + Math.max(0.05, clearance), box.maxZ);
        return !world.isSpaceEmpty(player, probe);
    }

    /** No collidable block in the column under the player down to the world's bottom (a void drop). */
    static boolean overVoid(World world, PlayerEntity player) {
        Box box = player.getBoundingBox();
        double bottom = world.getBottomY();
        if (box.minY <= bottom) return true;
        Box column = new Box(box.minX, bottom, box.minZ, box.maxX, box.minY, box.maxZ);
        return world.isSpaceEmpty(player, column);
    }

    /** Nothing collidable within {@code depth} blocks under the centre of the player's feet (standing on an edge). */
    static boolean centerOverAir(World world, PlayerEntity player, double depth) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        return world.isSpaceEmpty(player, new Box(x - 0.05, y - depth, z - 0.05, x + 0.05, y - 0.001, z + 0.05));
    }

    /** Nothing collidable within {@code depth} blocks under the player's feet. */
    static boolean dropBelow(World world, PlayerEntity player, double depth) {
        Box box = player.getBoundingBox();
        Box column = new Box(box.minX, box.minY - depth, box.minZ, box.maxX, box.minY, box.maxZ);
        return world.isSpaceEmpty(player, column);
    }

    /** The block cell would overlap the player's hitbox (vanilla rejects such a placement). */
    static boolean intersectsPlayer(PlayerEntity player, BlockPos cell) {
        return new Box(cell).intersects(player.getBoundingBox());
    }
}
