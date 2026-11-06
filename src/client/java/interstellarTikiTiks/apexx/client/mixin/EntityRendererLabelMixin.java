package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.NametagHideProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererLabelMixin {

    /**
     * Hides the vanilla nametag when our own Nametags module is drawing a replacement.
     */
    @Inject(
            method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void apexx$hideVanillaPlayerNametagWhenDisabled(EntityRenderState state,
                                                            MatrixStack matrices,
                                                            OrderedRenderCommandQueue queue,
                                                            CameraRenderState cameraRenderState,
                                                            CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState)) {
            return;
        }
        try {
            NametagHideProvider hideProvider = ModuleAccess.getHook(NametagHideProvider.class);
            if (hideProvider != null && hideProvider.shouldHideVanillaNametag()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
