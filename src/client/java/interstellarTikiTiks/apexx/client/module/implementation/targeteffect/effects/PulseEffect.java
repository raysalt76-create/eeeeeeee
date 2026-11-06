package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.*;

public class PulseEffect extends BaseEffect {
    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderPulse(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderPulse(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderPulse(MatrixStack matrices, VertexConsumer mainBuffer,
                             VertexConsumer glowBuffer, EffectRenderContext ctx,
                             boolean textured) {
        double torsoHeight = ctx.player.getHeight() / 2.0;
        double phase = (ctx.time * ctx.speedMultiplier) / 1000.0;
        double pulse = (phase % 2.0) / 2.0;

        double baseRadius = ctx.player.getWidth() * 0.3 * ctx.scaleMultiplier;
        double maxRadius = ctx.player.getWidth() * 1.5 * ctx.scaleMultiplier;
        double radius = baseRadius + (maxRadius - baseRadius) * pulse;

        float ringAlpha = ctx.alpha * (1.0f - (float) pulse);
        double thickness = 0.06 * ctx.scaleMultiplier * (1.0 - pulse * 0.5);

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        matrices.push();
        matrices.translate(0, torsoHeight, 0);

        if (mainBuffer != null) {
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, mainBuffer, radius, thickness,
                        0, color, ringAlpha, ctx.segments, 8, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, mainBuffer, radius, thickness,
                        0, color, ringAlpha, ctx.segments, 8);
            }
        }

        if (glowBuffer != null) {
            double glowThickness = thickness + ctx.glowSize;
            float glowAlpha = ringAlpha * (float) ctx.glowOpacity;
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, glowBuffer, radius, glowThickness,
                        0, color, glowAlpha, Math.max(16, ctx.segments / 2), 6, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, glowBuffer, radius, glowThickness,
                        0, color, glowAlpha, Math.max(16, ctx.segments / 2), 6);
            }
        }

        matrices.pop();
    }
}
