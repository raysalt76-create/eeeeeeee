package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray on Sodium's chunk builder (checked against Sodium 0.8.6 for 1.21.11): its per-face cull is
 * {@code AbstractBlockRenderContext.isFaceCulled}. A listed block is never culled. It is a pseudo
 * mixin: with no Sodium installed the target is absent and this is skipped.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumOcclusionContextMixin {

    @Shadow(remap = false)
    protected BlockState state;

    @Inject(method = "isFaceCulled", at = @At("HEAD"), cancellable = true, remap = false)
    private void apexx$xrayKeepFaces(Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (face != null && state != null && XrayState.isVisible(state.getBlock())) {
            cir.setReturnValue(false);
        }
    }
}
