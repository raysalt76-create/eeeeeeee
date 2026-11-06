package me.sootysplash.box.mixin;

import me.sootysplash.box.Config;
import net.minecraft.client.render.entity.feature.StuckArrowsFeatureRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StuckArrowsFeatureRenderer.class)
public class StuckArrowMixin {
    @Inject(method = "getObjectCount(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)I", at = @At("RETURN"), cancellable = true)
    private void hookArrowCount(PlayerEntityRenderState playerRenderState, CallbackInfoReturnable<Integer> cir) {
        if (Config.getInstance().hideArrow) {
            cir.setReturnValue(0);
        }
    }
}
