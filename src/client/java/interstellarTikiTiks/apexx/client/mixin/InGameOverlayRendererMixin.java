package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.NoRenderProvider;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopVisualProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender / Totem Pop Customizer seams for the first-person screen overlays and the floating totem. */
@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {

    @Inject(method = "renderFireOverlay(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/texture/Sprite;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void apexx$hideFire(MatrixStack matrices, VertexConsumerProvider consumers, Sprite sprite, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender fire", provider::hideFireOverlay, false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "renderUnderwaterOverlay(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void apexx$hideUnderwater(MinecraftClient client, MatrixStack matrices, VertexConsumerProvider consumers,
                                             CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender underwater", provider::hideUnderwaterOverlay, false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "renderInWallOverlay(Lnet/minecraft/client/texture/Sprite;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void apexx$hideInWall(Sprite sprite, MatrixStack matrices, VertexConsumerProvider consumers, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender in-wall", provider::hideInWallOverlay, false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "renderFloatingItem(Lnet/minecraft/client/util/math/MatrixStack;FLnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$hideFloatingItem(MatrixStack matrices, float tickDelta, OrderedRenderCommandQueue queue, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender totem", provider::hideTotemAnimation, false)) {
                ci.cancel();
                return;
            }
        }
        for (TotemPopVisualProvider provider : ModuleAccess.getEnabledHooks(TotemPopVisualProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "floating totem", provider::hideFloatingTotem, false)) {
                ci.cancel();
                return;
            }
        }
    }

    /** Scales the floating totem inside vanilla's own push/pop, just before the item is drawn. */
    @Inject(method = "renderFloatingItem(Lnet/minecraft/client/util/math/MatrixStack;FLnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"),
            require = 0)
    private void apexx$scaleFloatingItem(MatrixStack matrices, float tickDelta, OrderedRenderCommandQueue queue, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        TotemPopVisualProvider provider = ModuleAccess.getEnabledHook(TotemPopVisualProvider.class);
        if (provider == null) return;
        float scale = ModuleCallbackGuard.call(provider, "floating totem", provider::floatingTotemScale, 1.0f);
        if (!Float.isFinite(scale) || scale <= 0.0f || Math.abs(scale - 1.0f) < 1.0e-3f) return;
        scale = Math.min(scale, 4.0f);
        matrices.scale(scale, scale, scale);
    }
}
