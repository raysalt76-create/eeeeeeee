package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * The vanilla facts the crystal modules share: what a crystal can stand on, whether one fits, which
 * crystal sits on which base, and whether the held item still breaks one. Reads only; every click and
 * attack still goes through the shared seams.
 */
public final class CrystalPlacement {

    private CrystalPlacement() {
    }

    /** Obsidian and bedrock are the only blocks an end crystal can be placed on. */
    public static boolean isBase(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    /**
     * Vanilla's own placement check: air above the base, and no entity in the two blocks a crystal
     * takes.
     */
    public static boolean crystalFits(World world, BlockPos base) {
        BlockPos above = base.up();
        return world.isAir(above) && world.getOtherEntities(null,
                new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1.0, above.getY() + 2.0,
                        above.getZ() + 1.0)).isEmpty();
    }

    /** The live crystal standing on {@code base}, or null. */
    public static EndCrystalEntity crystalOn(World world, BlockPos base) {
        return world.getEntitiesByClass(EndCrystalEntity.class, new Box(base.up()), Entity::isAlive)
                .stream().findFirst().orElse(null);
    }

    /** {@code entity} when it is a live crystal standing on obsidian or bedrock. */
    public static EndCrystalEntity crystalOnBase(World world, Entity entity) {
        return entity instanceof EndCrystalEntity crystal && crystal.isAlive()
                && isBase(world, crystal.getBlockPos().down()) ? crystal : null;
    }

    /**
     * Whether a hit with {@code held} breaks a crystal: Weakness stops it unless Strength outranks it
     * or a tool or sword is held.
     */
    public static boolean breaksCrystals(PlayerEntity player, ItemStack held) {
        StatusEffectInstance weakness = player.getStatusEffect(StatusEffects.WEAKNESS);
        if (weakness == null) return true;
        StatusEffectInstance strength = player.getStatusEffect(StatusEffects.STRENGTH);
        return (strength != null && strength.getAmplifier() > weakness.getAmplifier())
                || held.isIn(ItemTags.PICKAXES) || held.isIn(ItemTags.AXES) || held.isIn(ItemTags.SHOVELS)
                || held.isIn(ItemTags.HOES) || held.isIn(ItemTags.SWORDS);
    }

    /** First hotbar slot holding {@code item}, or -1. */
    public static int hotbarSlot(PlayerEntity player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) return slot;
        }
        return -1;
    }

    /**
     * The slot to break with: -1 (whatever is held) unless Weakness stops the held item, then a hotbar
     * weapon or tool that still breaks crystals; -2 when nothing in the hotbar can.
     */
    public static int breakingSlot(PlayerEntity player) {
        if (breaksCrystals(player, player.getMainHandStack())) return -1;
        for (int slot = 0; slot < 9; slot++) {
            if (breaksCrystals(player, player.getInventory().getStack(slot))) return slot;
        }
        return -2;
    }

    /**
     * How long a placed crystal may take to show up: this player's round trip as the server reports it,
     * plus the server's tick and this client's.
     */
    public static long spawnWaitMs(MinecraftClient mc) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        PlayerListEntry entry = handler == null || mc.player == null ? null : handler.getPlayerListEntry(mc.player.getUuid());
        long latency = entry == null ? 0L : Math.max(0, entry.getLatency());
        return Math.min(latency + 150L, 1500L);
    }

    /**
     * How long an action's echo (a crystal's removal or spawn, a charge) takes to show in the world:
     * the round trip plus one tick. Past it the action is retried instead of waited on.
     */
    public static long echoWaitMs(MinecraftClient mc) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        PlayerListEntry entry = handler == null || mc.player == null ? null : handler.getPlayerListEntry(mc.player.getUuid());
        long latency = entry == null ? 0L : Math.max(0, entry.getLatency());
        return Math.min(latency + 60L, 1000L);
    }
}
