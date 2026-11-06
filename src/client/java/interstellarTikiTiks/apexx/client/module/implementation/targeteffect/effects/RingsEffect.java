package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.*;

public class RingsEffect extends BaseEffect {
    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderRings(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderRings(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderRings(MatrixStack matrices, VertexConsumer mainBuffer,
                             VertexConsumer glowBuffer, EffectRenderContext ctx,
                             boolean textured) {
        double height = ctx.player.getHeight();
        double timeOffset = (ctx.time * ctx.speedMultiplier) / 1000.0;
        int ringCount = 5;
        int tubeSides = Math.max(6, ctx.segments / 6);

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        for (int ring = 0; ring < ringCount; ring++) {
            double t = (double) ring / (ringCount - 1);
            double y = t * height;
            double radius = ctx.player.getWidth() * 0.7 * ctx.scaleMultiplier;
            double thickness = 0.05 * ctx.scaleMultiplier;
            double ringRotation = timeOffset * (ring % 2 == 0 ? 1 : -1) * (1 + ring * 0.2);

            matrices.push();
            matrices.translate(0, y, 0);

            if (mainBuffer != null) {
                if (textured) {
                    RenderHelpers.renderTorusTextured(matrices, mainBuffer, radius, thickness,
                            ringRotation, color, ctx.alpha, ctx.segments, tubeSides, ctx.uvOffsetU, ctx.uvOffsetV);
                } else {
                    RenderHelpers.renderTorusOptimized(matrices, mainBuffer, radius, thickness,
                            ringRotation, color, ctx.alpha, ctx.segments, tubeSides);
                }
            }

            if (glowBuffer != null) {
                double glowThickness = thickness + ctx.glowSize;
                float glowAlpha = ctx.alpha * (float) ctx.glowOpacity;
                if (textured) {
                    RenderHelpers.renderTorusTextured(matrices, glowBuffer, radius, glowThickness,
                            ringRotation, color, glowAlpha, Math.max(16, ctx.segments / 2),
                            Math.max(4, tubeSides - 2), ctx.uvOffsetU, ctx.uvOffsetV);
                } else {
                    RenderHelpers.renderTorusOptimized(matrices, glowBuffer, radius, glowThickness,
                            ringRotation, color, glowAlpha, Math.max(16, ctx.segments / 2),
                            Math.max(4, tubeSides - 2));
                }
            }

            matrices.pop();
        }
    }
}
