package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.render.visuals.ShaderEspEngine;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class ShaderOutlineWorldRendererMixin {

    @Shadow
    private Framebuffer entityOutlineFramebuffer;

    /**
     * Vanilla runs a sobel + box blur over the outline framebuffer, which keeps only a thin edge.
     * ShaderESP needs the raw silhouette, so the chain is skipped while the engine owns the buffer.
     */
    @Redirect(
            method = "render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gl/PostEffectProcessor;render(Lnet/minecraft/client/render/FrameGraphBuilder;IILnet/minecraft/client/gl/PostEffectProcessor$FramebufferSet;)V",
                    ordinal = 0
            )
    )
    private void apexx$skipOutlineChain(PostEffectProcessor processor, FrameGraphBuilder builder, int width, int height,
                                        PostEffectProcessor.FramebufferSet framebuffers) {
        if (ShaderEspEngine.takesOver()) return;
        processor.render(builder, width, height, framebuffers);
    }

    /** Replaces vanilla's flat blit of the outline framebuffer with the shader composite. */
    @Inject(method = "drawEntityOutlinesFramebuffer()V", at = @At("HEAD"), cancellable = true)
    private void apexx$compositeShaderEsp(CallbackInfo ci) {
        if (!ShaderEspEngine.takesOver()) return;
        ci.cancel();
        if (this.entityOutlineFramebuffer != null) {
            ShaderEspEngine.composite(this.entityOutlineFramebuffer);
        }
    }
}
