package interstellarTikiTiks.apexx.client;

import interstellarTikiTiks.apexx.client.module.hooks.HudRenderAware;
import interstellarTikiTiks.apexx.client.util.LogCleaner;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;

/**
 * Registers the world-render and HUD hooks. Module ticking is owned by
 * MinecraftClientMixin -> ModuleManager.onTick(); see the note in onInitializeClient.
 */
public class ApexxClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        interstellarTikiTiks.apexx.client.gui.mobile.MobileEntryPoints.register();
        interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer.registerPipelines();
        interstellarTikiTiks.apexx.client.render.visuals.GpuWorldVisualEngine.register();
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Identifier.of("dynamic_fps", "gpuui_font_metrics"),
                (SynchronousResourceReloader) manager ->
                        interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer.invalidateFontMetrics());
        // NOTE: module ticking is deliberately NOT registered here.
        //
        // This used to run its own END_CLIENT_TICK loop over every module while
        // MinecraftClientMixin was independently calling ModuleManager.onTick() at the
        // tick HEAD. Both fired every game tick, so each module ticked twice and every
        // tick-counted delay in the client (swap windows, cooldowns, re-equip timers)
        // elapsed in half the configured time.
        //
        // The mixin is now the single dispatcher: tick HEAD runs before the player's
        // movement code reads the keybinds, which is what module input injection needs.
        // The per-module error isolation that lived here moved into
        // ModuleManager.onTick() so it applies to that path too.

        // The HUD renderer. This was written as a HudRenderCallback but NEVER REGISTERED —
        // it had zero references anywhere in the codebase, so nothing ever called it. That single
        // omission silently killed two whole subsystems:
        //   * every module's onRender(DrawContext, float) — ModuleManager.onRender is only ever
        //     invoked from here, so Nametags (and any other screen-space module drawing) drew
        //     nothing at all, while still appearing enabled in the menu; and
        //   * the entire HUD — HUDManager.render is likewise only called from here, so the
        //     ArrayList, FPS, Keystrokes, TargetHUD, Coordinates, CPS, Armor and Effect elements
        //     were all dead.
        // Register the client HUD renderer once during startup.
        HudRenderCallback.EVENT.register(new interstellarTikiTiks.apexx.client.hud.HUDRenderer());

        WorldRenderEvents.END_MAIN.register(context -> {
            float tickDelta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(true);
            try {
                // Under SS Proof, world-space render-category module visuals (ESP, trajectories, ...) draw
                // into the overlay framebuffer instead of the game framebuffer, so a capture of the game
                // window stays clean. The world itself always renders normally; only these draws move.
                interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.beginWorldRender();
                try {
                    Apexx.getInstance().getModuleManager().onWorldRender(context.matrices(), tickDelta);
                } finally {
                    interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.endWorldRender();
                }
            } catch (Throwable t) {
                ClientInternalLog.error("WorldRenderer", "World-render dispatcher failed", t);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.destroy();
            } catch (Throwable ignored) {
            }
        }));

        LogCleaner.clean();
    }
}
