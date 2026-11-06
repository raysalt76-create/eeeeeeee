package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.render.visuals.ShaderEspEngine;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class ShaderOutlineEntityRendererMixin {

    /**
     * Routes the whole render of ShaderESP targets through vanilla's entity-outline framebuffer,
     * so armor, held items and elytra are part of the mask rather than only the base model.
     */
    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void apexx$maskShaderEspTarget(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        try {
            if (ShaderEspEngine.wantsMask(entity)) {
                state.outlineColor = ShaderEspEngine.maskColor(entity);
            }
        } catch (Throwable ignored) {
            // A broken effect must never take the render thread down with it.
        }
    }
}
