package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xray on Sodium's chunk builder (checked against Sodium 0.8.6 for 1.21.11). A block that is neither
 * listed nor a background block is not meshed at all; a background block marks the builder thread's
 * scope so {@code SodiumXrayColorMixin} applies the opacity to its vertices. Pseudo, like
 * {@link SodiumXrayContextMixin}: with no Sodium installed the target is absent and this is skipped.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumOcclusionBlockRendererMixin {

    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false)
    private void apexx$xrayBegin(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
                                 CallbackInfo ci) {
        XrayState.endBlock();
        if (!XrayState.isActive() || XrayState.isVisible(state.getBlock())) return;
        if (!XrayState.isBackground(state)) {
            ci.cancel();
            return;
        }
        XrayState.beginBlock(state);
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false)
    private void apexx$xrayEnd(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin,
                               CallbackInfo ci) {
        XrayState.endBlock();
    }
}
