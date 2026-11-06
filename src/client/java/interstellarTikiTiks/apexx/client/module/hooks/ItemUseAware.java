package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.hit.BlockHitResult;

public interface ItemUseAware {

    /**
     * Called when the player interacts with a block.
     *
     * @return true to cancel the interaction, false to allow it.
     */
    boolean onItemUse(BlockHitResult hitResult);

    /**
     * Called after block interaction completes.
     */
    default void onPostItemUse(net.minecraft.util.Hand hand, BlockHitResult hitResult, net.minecraft.util.ActionResult result) {}
}
