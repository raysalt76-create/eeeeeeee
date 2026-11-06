package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.*;

public class OrbitRingEffect extends BaseEffect {
    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderRing(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderRing(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderRing(MatrixStack matrices, VertexConsumer mainBuffer,
                            VertexConsumer glowBuffer, EffectRenderContext ctx,
                            boolean textured) {
        double torsoHeight = ctx.player.getHeight() / 2.0;
        double radius = ctx.player.getWidth() * 0.8 * ctx.scaleMultiplier;
        double thickness = 0.08 * ctx.scaleMultiplier;
        double rotation = (ctx.time * ctx.speedMultiplier) / 1000.0;

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        matrices.push();
        matrices.translate(0, torsoHeight, 0);

        if (mainBuffer != null) {
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, mainBuffer, radius, thickness,
                        rotation, color, ctx.alpha, ctx.segments, 8, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, mainBuffer, radius, thickness,
                        rotation, color, ctx.alpha, ctx.segments, 8);
            }
        }

        if (glowBuffer != null) {
            double glowThickness = thickness + ctx.glowSize;
            float glowAlpha = ctx.alpha * (float) ctx.glowOpacity;
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, glowBuffer, radius, glowThickness,
                        rotation, color, glowAlpha, Math.max(16, ctx.segments / 2), 6, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, glowBuffer, radius, glowThickness,
                        rotation, color, glowAlpha, Math.max(16, ctx.segments / 2), 6);
            }
        }

        matrices.pop();
    }
}
