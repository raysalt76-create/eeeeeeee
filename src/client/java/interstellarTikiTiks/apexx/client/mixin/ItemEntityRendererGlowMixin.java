package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.ItemGlowProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Glow: sets the render-state outline colour of chosen dropped items, which makes vanilla draw them
 * into the entity-outline (glowing) pass. Client-side only; the entity itself is untouched.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererGlowMixin {

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void apexx$itemGlow(ItemEntity item, ItemEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (Apexx.isTornDown() || state == null || state.outlineColor != 0) return;
        ItemGlowProvider provider = ModuleAccess.getEnabledHook(ItemGlowProvider.class);
        if (provider == null) return;
        int rgb = ModuleCallbackGuard.call(provider, "itemGlow", () -> provider.itemGlowColor(item), 0);
        if (rgb != 0) state.outlineColor = rgb & 0x00FFFFFF;
    }
}
