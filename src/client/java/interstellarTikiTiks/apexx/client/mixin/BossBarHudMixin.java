package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.hud.HudFill;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD restyle: replaces vanilla's textured boss bar with a rounded translucent card + accent bar. The
 * bar's name is still drawn by vanilla's render loop right after this call, on top of the card.
 */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {

    @Inject(method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$restyleBossBar(DrawContext context, int x, int y, BossBar bossBar, CallbackInfo ci) {
        HudFill.Style style = HudFill.style();
        if (style == null || !style.bossBars()) return;
        try {
            HudFill.bossBar(context, style, x, y, bossBar);
            ci.cancel();
        } catch (Throwable ignored) {
            // Fall back to vanilla's bar.
        }
    }
}
