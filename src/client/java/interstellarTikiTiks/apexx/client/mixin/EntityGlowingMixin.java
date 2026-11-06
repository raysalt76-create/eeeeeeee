package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.GlowDecisionProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Entity.class)
public class EntityGlowingMixin {

    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void onIsGlowing(CallbackInfoReturnable<Boolean> cir) {

        if (Apexx.isTornDown()) {
            return;
        }

        Entity entity = (Entity) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();

        List<GlowDecisionProvider> providers = ModuleAccess.getEnabledHooks(GlowDecisionProvider.class);
        if (providers.isEmpty()) return;
        // Client-side entities only: in singleplayer the integrated server's entities (other thread, server
        // world) share this method, and forcing glow there changes server state and what LAN players see.
        if (entity.getEntityWorld() == null || !entity.getEntityWorld().isClient()) return;
        for (int i = 0; i < providers.size(); i++) {
            GlowDecisionProvider provider = providers.get(i);
            if (provider.shouldApplyGlow()
                    && entity instanceof PlayerEntity
                    && entity != mc.player
                    && entity.isAlive()) {
                cir.setReturnValue(true);
                return;
            }
        }
        if (apexx$glowColor(entity, providers) != 0) {
            cir.setReturnValue(true);
        }
    }

    /** Friend Glow and other per-entity glow colours set the outline colour vanilla reads here. */
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$glowTeamColor(CallbackInfoReturnable<Integer> cir) {
        if (Apexx.isTornDown()) return;
        List<GlowDecisionProvider> providers = ModuleAccess.getEnabledHooks(GlowDecisionProvider.class);
        if (providers.isEmpty()) return;
        int rgb = apexx$glowColor((Entity) (Object) this, providers);
        if (rgb != 0) cir.setReturnValue(rgb);
    }

    /**
     * First non-zero per-entity glow RGB from the enabled providers, client entities only; 0 = none.
     * Hot path (several calls per entity per frame): no lambda, a plain try/catch as the fault boundary.
     */
    @Unique
    private static int apexx$glowColor(Entity entity, List<GlowDecisionProvider> providers) {
        if (entity == null || entity.getEntityWorld() == null || !entity.getEntityWorld().isClient()) return 0;
        for (int i = 0; i < providers.size(); i++) {
            int rgb;
            try {
                rgb = providers.get(i).glowColorFor(entity);
            } catch (Throwable t) {
                continue;
            }
            if (rgb != 0) {
                int c = rgb & 0x00FFFFFF;
                return c == 0 ? 0x010101 : c;
            }
        }
        return 0;
    }
}
