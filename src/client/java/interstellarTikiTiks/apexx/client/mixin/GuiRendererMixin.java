package interstellarTikiTiks.apexx.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuBackdropBlur;
import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the game image before deferred GUI draws and redirects SS Proof draws to the overlay. */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"), require = 0)
    private void apexx$captureGpuUiScene(GpuBufferSlice fog, CallbackInfo ci) {
        // Backdrop effects read the game image, so the snapshot is taken before this flush moves draws.
        GpuBackdropBlur.captureScene();
        OverlayCompositor.beginFrame();
    }

    @Redirect(
            method = "renderPreparedDraws(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;getFramebuffer()Lnet/minecraft/client/gl/Framebuffer;"),
            require = 0)
    private Framebuffer apexx$redirectGuiTarget(MinecraftClient client) {
        return OverlayCompositor.guiTarget(client.getFramebuffer());
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("RETURN"), require = 0)
    private void apexx$finishGpuUiFrame(GpuBufferSlice fog, CallbackInfo ci) {
        GpuBackdropBlur.finishFrame();
        OverlayCompositor.endFrame();
    }
}
