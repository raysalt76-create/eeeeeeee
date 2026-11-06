package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

public class OverlayEffect extends BaseEffect {

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {

        renderOverlayPass(matrices, ctx, false);

        if (ctx.glowEnabled) {
            renderOverlayPass(matrices, ctx, true);
        }
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        render(matrices, mainBuffer, glowBuffer, ctx);
    }

    private void renderOverlayPass(MatrixStack matrices, EffectRenderContext ctx, boolean isGlowPass) {
        if (ctx.vertexConsumers == null || ctx.playerModel == null || ctx.renderState == null) {
            return;
        }

        matrices.push();

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - ctx.renderState.bodyYaw));

        float scale;
        if (isGlowPass) {
            float baseScale = 0.9f + (0.05f * (float) ctx.scaleMultiplier);
            scale = baseScale + (float) ctx.glowSize;
        } else {
            scale = 0.9f + (0.05f * (float) ctx.scaleMultiplier);
        }

        matrices.scale(scale, scale, scale);
        matrices.scale(-1.0F, -1.0F, 1.0F);
        matrices.translate(0.0F, -1.501F, 0.0F);

        Identifier texture;
        if (ctx.player instanceof AbstractClientPlayerEntity) {
            AbstractClientPlayerEntity clientPlayer = (AbstractClientPlayerEntity) ctx.player;
            texture = clientPlayer.getSkin().body().texturePath();
        } else {
            matrices.pop();
            return;
        }

        ctx.playerModel.setAngles(ctx.renderState);

        RenderLayer layer;
        boolean useShader = ctx.theme.usesShader();
        String shaderName = ctx.theme.getShaderName();

        if (useShader && shaderName != null) {

            layer = ShaderRenderLayers.getShaderEntityLayer(shaderName, texture, isGlowPass);
        } else {

            if (isGlowPass) {

                layer = RenderLayers.entityTranslucent(texture);
            } else {

                layer = ctx.opaqueMode
                        ? RenderLayers.entityCutoutNoCull(texture)
                        : RenderLayers.entityTranslucent(texture);
            }
        }

        VertexConsumer buffer = ctx.vertexConsumers.getBuffer(layer);

        int color;
        float alpha;

        if (isGlowPass) {
            alpha = (float) ctx.glowOpacity;
            color = ctx.getThemedColor().getRGB();
        } else {
            alpha = ctx.alpha;
            color = ctx.color.getRGB();
        }

        int argb = (color & 0x00FFFFFF) | ((int)(alpha * 255) << 24);

        ctx.playerModel.render(matrices, buffer, ctx.light,
                OverlayTexture.DEFAULT_UV, argb);

        matrices.pop();
    }
}
