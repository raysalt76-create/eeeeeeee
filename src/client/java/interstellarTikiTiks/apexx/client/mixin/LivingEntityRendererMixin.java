package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.HitColorProvider;
import interstellarTikiTiks.apexx.client.module.hooks.TargetEffectOverlayProvider;
import net.minecraft.client.render.OverlayTexture;
import interstellarTikiTiks.apexx.client.module.hooks.PlayerModelVisualProvider;
import interstellarTikiTiks.apexx.client.module.hooks.TextureThemeHook;
import interstellarTikiTiks.apexx.client.module.hooks.EntityChamsProvider;
import interstellarTikiTiks.apexx.client.module.hooks.EntityPoseAware;
import interstellarTikiTiks.apexx.client.render.visuals.ChamsRenderer;
import interstellarTikiTiks.apexx.client.render.visuals.GpuVisualEngine;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.util.List;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Shadow
    protected EntityModel model;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"
            )
    )
    private void apexx$maybeSkipBaseModel(
            OrderedRenderCommandQueue queue,
            Model model,
            Object state,
            MatrixStack matrices,
            RenderLayer layer,
            int light,
            int overlay,
            int color,
            Sprite sprite,
            int outlineColor,
            ModelCommandRenderer.CrumblingOverlayCommand crumbling
    ) {
        if (!Apexx.isTornDown() && state instanceof PlayerEntityRenderState playerState) {
            TargetEffectOverlayProvider targetEffect = ModuleAccess.getEnabledHook(TargetEffectOverlayProvider.class);
            if (targetEffect != null && targetEffect.shouldHideModel(playerState)) {
                return;
            }
            for (PlayerModelVisualProvider provider : ModuleAccess.getEnabledHooks(PlayerModelVisualProvider.class)) {
                if (provider.shouldRenderPlayerModel(playerState)) return;
            }
        }

        if (!Apexx.isTornDown() && state instanceof LivingEntityRenderState living && living.hurt) {
            int tint = apexx$hitColor(living);
            if (tint != 0) {
                // Drop vanilla's red hurt row from the overlay (keep the white-flash U), then multiply the
                // chosen colour into the model tint instead.
                overlay = (overlay & 0xFFFF) | (OverlayTexture.getV(false) << 16);
                color = apexx$multiplyArgb(color, tint);
            }
        }

        queue.submitModel(model, state, matrices, layer, light, overlay, color, sprite, outlineColor, crumbling);
    }

    private static int apexx$hitColor(LivingEntityRenderState state) {
        try {
            for (HitColorProvider provider : ModuleAccess.getEnabledHooks(HitColorProvider.class)) {
                int argb = provider.hitColorArgb(state);
                if (argb != 0) return argb;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int apexx$multiplyArgb(int a, int b) {
        int alpha = ((a >>> 24) * (b >>> 24)) / 255;
        int red = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
        int green = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
        int blue = ((a & 0xFF) * (b & 0xFF)) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/util/math/MatrixStack;pop()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void apexx$renderTargetEffectOverlay(
            LivingEntityRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        if (Apexx.isTornDown()) return;
        // 1. Check TargetEffect
        if (state instanceof PlayerEntityRenderState playerState && this.model instanceof PlayerEntityModel) {
            TargetEffectOverlayProvider targetEffect = ModuleAccess.getEnabledHook(TargetEffectOverlayProvider.class);
            if (targetEffect != null && targetEffect.shouldHideModel(playerState)) {
                TextureThemeHook theme = targetEffect.getOverlayTheme();
                Color baseColor = targetEffect.getOverlayColor(System.currentTimeMillis(), playerState.id);
                boolean opaqueMode = targetEffect.isOverlayOpaque();
                float mainAlpha = opaqueMode ? 1.0f : 0.6f;
                float baseScale = targetEffect.getOverlayScale();

                Identifier skinTexture = playerState.skinTextures.body().texturePath();

                submitOverlayPass(queue, playerState, matrices, skinTexture, theme, baseColor, mainAlpha, baseScale, opaqueMode, false);

                if (targetEffect.isOverlayGlowEnabled()) {
                    float glowAlpha = targetEffect.getOverlayGlowOpacity();
                    float glowScale = baseScale + targetEffect.getOverlayGlowSize();
                    Color glowColor = theme.applyThemeTint(baseColor);
                    submitOverlayPass(queue, playerState, matrices, skinTexture, theme, glowColor, glowAlpha, glowScale,
                            false, true);
                }
            }

            List<PlayerModelVisualProvider> visualProviders = ModuleAccess.getEnabledHooks(PlayerModelVisualProvider.class);
            for (PlayerModelVisualProvider provider : visualProviders) {
                if (!provider.shouldRenderPlayerModel(playerState)) continue;
                long now = System.currentTimeMillis();
                Color color = provider.getPlayerVisualColor(playerState, now);
                int layers = Math.max(0, provider.getPlayerVisualContourLayers());
                float scale = provider.getPlayerVisualContourScale();

                // Low-alpha expanded model shells add edge lift. This uses the renderer's live
                // pose rather than an AABB; isolated-mask bloom belongs in the later scene pass.
                for (int i = layers; i >= 1; i--) {
                    float layerScale = 1.0f + (scale - 1.0f) * i / Math.max(layers, 1);
                    float layerAlpha = Math.min(0.30f, provider.getPlayerVisualOpacity() * 0.18f / i);
                    GpuVisualEngine.submitPlayerModelPass(queue, this.model, playerState, matrices,
                            color.brighter(), layerAlpha, layerScale, provider.renderPlayerVisualThroughWalls());
                }
                GpuVisualEngine.submitPlayerModelPass(queue, this.model, playerState, matrices,
                        color, provider.getPlayerVisualOpacity(), 1.0f, provider.renderPlayerVisualThroughWalls());
            }
        }

        apexx$chamsAndPose(state, matrices, queue);
    }

    /**
     * Chams model passes and pose observers (Skeleton ESP) for every living entity, at the renderer's
     * final model transform. Each hook is isolated: a failing module never breaks entity rendering.
     */
    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void apexx$chamsAndPose(LivingEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue) {
        List<EntityPoseAware> poseHooks = ModuleAccess.getEnabledHooks(EntityPoseAware.class);
        for (int i = 0; i < poseHooks.size(); i++) {
            try {
                poseHooks.get(i).onEntityPose(state, this.model, matrices);
            } catch (Throwable ignored) {
            }
        }
        List<EntityChamsProvider> chams = ModuleAccess.getEnabledHooks(EntityChamsProvider.class);
        if (chams.isEmpty()) return;
        Identifier texture = null;
        for (int i = 0; i < chams.size(); i++) {
            EntityChamsProvider provider = chams.get(i);
            try {
                if (!provider.chamsApplies(state)) continue;
                if (texture == null) {
                    texture = ((LivingEntityRenderer) (Object) this).getTexture(state);
                    if (texture == null) return;
                }
                ChamsRenderer.submit(queue, this.model, state, matrices, texture, provider);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void submitOverlayPass(
            OrderedRenderCommandQueue queue,
            PlayerEntityRenderState state,
            MatrixStack matrices,
            Identifier skinTexture,
            TextureThemeHook theme,
            Color color,
            float alpha,
            float scale,
            boolean opaqueMode,
            boolean glowPass
    ) {
        RenderLayer layer;
        boolean usesShader = theme.usesShader();
        String shaderName = theme.getShaderName();

        if (usesShader && shaderName != null) {
            layer = ShaderRenderLayers.getShaderEntityLayer(shaderName, skinTexture, glowPass);
        } else {
            if (!glowPass && opaqueMode) {
                layer = RenderLayers.entityCutoutNoCull(skinTexture);
            } else {
                layer = RenderLayers.entityTranslucent(skinTexture);
            }
        }

        int argb = (color.getRGB() & 0x00FFFFFF) | ((int) (alpha * 255.0f) << 24);
        int overlay = LivingEntityRenderer.getOverlay(state, 0.0f);

        matrices.push();
        matrices.scale(scale, scale, scale);

        if (this.model != null) {
            this.model.setAngles(state);
        }

        queue.submitModel((Model) this.model, state, matrices, layer, state.light, overlay, argb, null, state.outlineColor, null);
        matrices.pop();
    }
}
