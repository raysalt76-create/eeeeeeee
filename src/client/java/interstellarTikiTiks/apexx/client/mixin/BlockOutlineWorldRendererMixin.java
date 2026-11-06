package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.BlockOutlineProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the Block Outline module replace vanilla's block selection outline. Policy lives in the module. */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineWorldRendererMixin {

    @Inject(method = "renderTargetBlockOutline(Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/util/math/MatrixStack;ZLnet/minecraft/client/render/state/WorldRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$replaceBlockOutline(VertexConsumerProvider.Immediate immediate, MatrixStack matrices,
                                           boolean translucent, WorldRenderState state, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        BlockOutlineProvider provider = ModuleAccess.getEnabledHook(BlockOutlineProvider.class);
        if (provider == null) return;
        ModuleCallbackGuard.run(provider, "blockOutlineCapture",
                () -> provider.captureVanillaOutline(state == null ? null : state.outlineRenderState));
        if (ModuleCallbackGuard.call(provider, "blockOutlineCancel", provider::cancelVanillaOutline, false)) {
            ci.cancel();
        }
    }
}
