package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xray's background opacity on Sodium: the vertex colour is packed by {@code ColorARGB.toABGR(int)} as
 * each quad is buffered, so the alpha is applied there, only while the builder thread is inside a
 * background block (see {@code SodiumXrayBlockRendererMixin}). Sodium keeps a quad's pass at
 * translucent when any vertex alpha is below 255, so it is not downgraded to opaque.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.api.util.ColorARGB", remap = false)
public abstract class SodiumOcclusionColorMixin {

    @Inject(method = "toABGR(I)I", at = @At("RETURN"), cancellable = true, remap = false)
    private static void apexx$xrayAlpha(int argb, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(XrayState.applyScopeAlpha(cir.getReturnValueI()));
    }
}
