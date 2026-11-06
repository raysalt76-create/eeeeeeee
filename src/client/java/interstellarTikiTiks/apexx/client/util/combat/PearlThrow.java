package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * The throw of the pearl in the main hand, for modules that plan a throw and take the slot themselves
 * ({@link SwapAction}). It uses the item directly, as vanilla does when the crosshair offers a pearl
 * nothing to click, so a module that holds back vanilla's own item use (a run in progress) does not
 * block its own throw. The item cooldown is never cleared: a cooling pearl is not thrown.
 */
public final class PearlThrow {

    private PearlThrow() {
    }

    /** Whether the pearl is on its item cooldown. */
    public static boolean cooling(MinecraftClient mc) {
        return mc.player != null && mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack());
    }

    /**
     * Throws the held pearl with the look just applied. False when no pearl is held, it is cooling down,
     * the look is not the one given, or the use was not accepted.
     */
    public static boolean use(MinecraftClient mc, float yaw, float pitch) {
        if (mc.player == null || mc.interactionManager == null || !mc.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || cooling(mc) || !PearlLimit.canThrow(mc)) {
            return false;
        }
        // A fresh press is not throttled by the use delay in vanilla; only a held use key waits on it.
        if (mc instanceof MinecraftClientAccessor accessor) {
            accessor.setItemUseCooldown(0);
        }
        TickActionOrder.recordUse();
        return InteractSim.useItem(mc, Hand.MAIN_HAND, yaw, pitch, true);
    }
}
