package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;
import interstellarTikiTiks.apexx.client.module.hooks.InventoryOverlayProvider;
import interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.GameMode;
import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import interstellarTikiTiks.apexx.client.module.hooks.CrosshairProvider;
import interstellarTikiTiks.apexx.client.module.hooks.NoRenderProvider;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void apexx$hideHudBehindGpuGui(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.isMenu(MinecraftClient.getInstance().currentScreen)) {
            ci.cancel();
        }
    }

    /**
     * Under SS Proof, with no Apexx screen open: render and flush the client's own standalone HUD
     * elements and screen-space module visuals here, before vanilla's own hotbar/hunger/XP bar queue
     * into the same shared GUI render state a few lines below in vanilla's own method body. See
     * {@link OverlayCompositor#renderAndFlushHudEarly} for why this has to happen first.
     */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void apexx$ssProofHudEarly(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        OverlayCompositor.renderAndFlushHudEarly(context, tickCounter.getTickProgress(true));
    }

    /** Restyle snapshot taken at the title-band fill and consumed by the body fill of the same sidebar draw. */
    @org.spongepowered.asm.mixin.Unique
    private static interstellarTikiTiks.apexx.client.hud.HudFill.Style apexx$scoreStyle;
    @org.spongepowered.asm.mixin.Unique
    private static int apexx$scoreTitleTop;

    /** HUD restyle: vanilla's first sidebar fill is the title band; it is folded into one rounded card. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V", ordinal = 0),
            require = 0)
    private void apexx$restyleScoreboardTitle(DrawContext context, int x1, int y1, int x2, int y2, int color,
                                              com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        interstellarTikiTiks.apexx.client.hud.HudFill.Style style = interstellarTikiTiks.apexx.client.hud.HudFill.style();
        if (style == null || !style.scoreboard()) {
            apexx$scoreStyle = null;
            original.call(context, x1, y1, x2, y2, color);
            return;
        }
        apexx$scoreStyle = style;
        apexx$scoreTitleTop = y1;
    }

    /** Second sidebar fill is the body; with the restyle on it draws the whole card (title band included). */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V", ordinal = 1),
            require = 0)
    private void apexx$restyleScoreboardBody(DrawContext context, int x1, int y1, int x2, int y2, int color,
                                             com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        interstellarTikiTiks.apexx.client.hud.HudFill.Style style = apexx$scoreStyle;
        apexx$scoreStyle = null;
        if (style == null) {
            original.call(context, x1, y1, x2, y2, color);
            return;
        }
        int top = Math.min(apexx$scoreTitleTop, y1);
        interstellarTikiTiks.apexx.client.hud.HudFill.scoreboard(context, style, x1, top, x2, y2, y1);
    }

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"), cancellable = true)
    private void onRenderScoreboardSidebar(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (ModuleAccess.isEnabled("StreamerModeModule")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void onRenderHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (inventoryOverlayProvider != null && inventoryOverlayProvider.shouldCancelVanillaHotbar()) {
            ci.cancel();
        }
    }

    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private static Identifier POWDER_SNOW_OUTLINE;

    @Shadow
    protected abstract boolean shouldRenderSpectatorCrosshair(HitResult hitResult);

    /**
     * Custom crosshair. Only replaces the crosshair in the cases where vanilla itself would draw its 2D
     * crosshair; the F3 3D axes crosshair and third person stay vanilla.
     */
    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$customCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        CrosshairProvider provider = ModuleAccess.getEnabledHook(CrosshairProvider.class);
        if (provider == null) return;
        MinecraftClient mc = this.client;
        if (mc == null || mc.player == null || mc.options == null) return;
        if (!mc.options.getPerspective().isFirstPerson()) return;
        if (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR
                && !this.shouldRenderSpectatorCrosshair(mc.crosshairTarget)) {
            return;
        }
        if (mc.debugHudEntryList != null
                && mc.debugHudEntryList.isEntryVisible(DebugHudEntries.THREE_DIMENSIONAL_CROSSHAIR)) {
            return;
        }
        float tickDelta = tickCounter.getTickProgress(true);
        if (ModuleCallbackGuard.call(provider, "crosshair", () -> provider.renderCrosshair(context, tickDelta), false)) {
            ci.cancel();
        }
    }

    /** NoRender: hides head-slot camera overlays (carved pumpkin); powder-snow frost is left alone. */
    @Inject(method = "renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$hideCameraOverlay(DrawContext context, Identifier texture, float opacity, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (texture == null || texture.equals(POWDER_SNOW_OUTLINE)) return;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender pumpkin", provider::hidePumpkinOverlay, false)) {
                ci.cancel();
                return;
            }
        }
    }
}
