package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;

/**
 * The global "Pearl Limit" (ClickGUI): modules do not throw a pearl while the number of pearls carried is
 * at or below the limit, so the last pearls stay for the player. 0 disables the limit. This only removes
 * throws; it never adds one.
 */
public final class PearlLimit {
    private static volatile int minimum;

    private PearlLimit() {
    }

    public static void set(int pearls) {
        minimum = Math.max(0, pearls);
    }

    public static int get() {
        return minimum;
    }

    /** Ender pearls in the whole inventory and the offhand. */
    public static int count(MinecraftClient mc) {
        if (mc == null || mc.player == null) return 0;
        PlayerInventory inventory = mc.player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isOf(Items.ENDER_PEARL)) total += inventory.getStack(i).getCount();
        }
        if (mc.player.getOffHandStack().isOf(Items.ENDER_PEARL)) total += mc.player.getOffHandStack().getCount();
        return total;
    }

    /** Whether a module may throw a pearl now: always true with no limit, else more pearls than the limit. */
    public static boolean canThrow(MinecraftClient mc) {
        int limit = minimum;
        return limit <= 0 || count(mc) > limit;
    }
}
