package interstellarTikiTiks.apexx.client.event;

import interstellarTikiTiks.apexx.client.module.hooks.ItemUseAware;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

public class ItemUseListener {

    /**
     * Dispatches a block interaction event to all enabled {@link ItemUseAware}
     * modules.
     *
     * @return true if any module cancelled the interaction, false otherwise.
     */
    public static boolean onItemUse(HitResult hitResult) {
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            return false;
        }

        for (ItemUseAware itemUseAware : ModuleAccess.getEnabledHooks(ItemUseAware.class)) {
            if (ModuleCallbackGuard.call(itemUseAware, "item-use hook",
                    () -> itemUseAware.onItemUse(blockHitResult), false)) {
                return true;
            }
        }
        return false;
    }

    public static void onPostItemUse(net.minecraft.util.Hand hand, HitResult hitResult, net.minecraft.util.ActionResult result) {
        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            return;
        }

        for (ItemUseAware itemUseAware : ModuleAccess.getEnabledHooks(ItemUseAware.class)) {
            ModuleCallbackGuard.run(itemUseAware, "post item-use hook",
                    () -> itemUseAware.onPostItemUse(hand, blockHitResult, result));
        }
    }
}
