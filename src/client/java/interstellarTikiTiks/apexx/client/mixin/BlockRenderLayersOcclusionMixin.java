package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray: background blocks are meshed in the translucent layer so their alpha shows. The vanilla and the
 * Sodium chunk builder both pick a block's layer here.
 */
@Mixin(BlockRenderLayers.class)
public abstract class BlockRenderLayersOcclusionMixin {

    @Inject(method = "getBlockLayer", at = @At("HEAD"), cancellable = true)
    private static void apexx$xrayBackgroundLayer(BlockState state, CallbackInfoReturnable<BlockRenderLayer> cir) {
        if (XrayState.isBackground(state)) {
            cir.setReturnValue(BlockRenderLayer.TRANSLUCENT);
        }
    }
}
