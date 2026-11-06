package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xray: the renderer culls whole chunk sections it decides cannot be seen into from the camera, which
 * would hide ores behind solid rock. With no block counted as opaque, no section is ruled out. Both the
 * vanilla and the Sodium chunk builder build their visibility data with this class.
 */
@Mixin(ChunkOcclusionDataBuilder.class)
public abstract class ChunkOcclusionFilterMixin {

    @Inject(method = "markClosed", at = @At("HEAD"), cancellable = true)
    private void apexx$xrayNothingIsOpaque(BlockPos pos, CallbackInfo ci) {
        if (XrayState.isActive()) {
            ci.cancel();
        }
    }
}
