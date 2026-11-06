package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cosmetics seam: after vanilla fills a player's render state, the cosmetics decide what the player wears and put the
 * Apexx cape texture into its skin textures (so the vanilla cape and elytra can use it) and advance cape/wing motion.
 */
@Mixin(PlayerEntityRenderer.class)
public class CosmeticPlayerRendererMixin {

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"),
            require = 0
    )
    private void apexx$cosmeticsState(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        CosmeticsProvider cosmetics = ModuleAccess.getHook(CosmeticsProvider.class);
        if (cosmetics != null) cosmetics.onPlayerRenderState(entity, state);
    }
}
