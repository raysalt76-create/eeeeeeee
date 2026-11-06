package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;
import interstellarTikiTiks.apexx.client.module.hooks.HudRenderAware;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class HUDRenderer implements HudRenderCallback {

    @Override
    public void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
        if (Apexx.selfDestructed) {
            return;
        }
        float tickDelta = tickCounter.getTickProgress(true);
        MinecraftClient mc = MinecraftClient.getInstance();

        try {
            // Under SS Proof with no menu open, OverlayCompositor already rendered and flushed these
            // same draws early (redirected into the overlay) from InGameHudMixin, before vanilla's own
            // hotbar queued anything. Skip here to avoid drawing everything a second time onto MAIN.
            if (!OverlayCompositor.shouldRedirectHud()) {
                Apexx.getInstance().getModuleManager().onRender(drawContext, tickDelta);

                if (mc != null && mc.currentScreen == null) {
                    HUDManager.getInstance().render(drawContext, tickDelta);
                }
            }

            for (HudRenderAware hudRenderAware : ModuleAccess.getEnabledHooks(HudRenderAware.class)) {
                ModuleCallbackGuard.run(hudRenderAware, "HUD render hook",
                        () -> hudRenderAware.onHudRender(drawContext, tickCounter));
            }
        } catch (Throwable t) {
            ClientInternalLog.error("HUD", "HUD callback dispatcher failed", t);
        }
    }
}
