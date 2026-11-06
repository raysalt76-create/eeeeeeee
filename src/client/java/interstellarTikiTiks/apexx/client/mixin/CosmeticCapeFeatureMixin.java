package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetics seam: every player renderer runs the cape feature once per player with the body-space matrix, so hats,
 * wings and the cloth cape are submitted from here (in the world and in the GUI player preview alike). When the
 * cloth cape is drawn, the vanilla cape is skipped.
 */
@Mixin(CapeFeatureRenderer.class)
public class CosmeticCapeFeatureMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/PlayerEntityRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void apexx$cosmetics(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state,
                                 float limbAngle, float limbDistance, CallbackInfo ci) {
        CosmeticsProvider cosmetics = ModuleAccess.getHook(CosmeticsProvider.class);
        if (cosmetics == null) return;
        Object model = ((FeatureRenderer<?, ?>) (Object) this).getContextModel();
        if (!(model instanceof PlayerEntityModel playerModel)) return;
        if (cosmetics.renderPlayerFeatures(matrices, queue, light, state, playerModel)) ci.cancel();
    }
}
