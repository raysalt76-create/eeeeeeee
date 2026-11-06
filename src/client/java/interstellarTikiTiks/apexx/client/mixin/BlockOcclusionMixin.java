package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray, vanilla chunk builder: a listed block draws every face, a hidden block draws none, and a
 * background block (translucent) keeps vanilla's own culling. The Sodium chunk builder does its own
 * culling and is covered by {@code SodiumXray*Mixin}.
 */
@Mixin(Block.class)
public abstract class BlockOcclusionMixin {

    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
    private static void apexx$xrayFaces(BlockState state, BlockState otherState, Direction side,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!XrayState.isActive()) return;
        if (XrayState.isVisible(state.getBlock())) {
            cir.setReturnValue(true);
        } else if (!XrayState.isBackground(state)) {
            cir.setReturnValue(false);
        }
    }
}
