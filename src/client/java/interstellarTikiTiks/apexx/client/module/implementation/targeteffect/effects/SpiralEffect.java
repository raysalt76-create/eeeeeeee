package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SpiralEffect extends BaseEffect {
    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderSpiral(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderSpiral(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderSpiral(MatrixStack matrices, VertexConsumer mainBuffer,
                              VertexConsumer glowBuffer, EffectRenderContext ctx,
                              boolean textured) {
        double height = ctx.player.getHeight();
        double radius = ctx.player.getWidth() * 0.5 * ctx.scaleMultiplier;
        double timeOffset = (ctx.time * ctx.speedMultiplier) / 1000.0;
        double tubeRadius = 0.04 * ctx.scaleMultiplier;

        Color color = textured ? ctx.getThemedColor() : ctx.color;
        int points = ctx.segments * 4;
        int tubeSides = Math.max(6, ctx.segments / 6);

        List<Vec3d> pathPoints = new ArrayList<>();

        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double y = t * height;
            double angle = t * Math.PI * 8 + timeOffset;

            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            pathPoints.add(new Vec3d(x, y, z));
        }

        if (mainBuffer != null) {
            if (textured) {
                RenderHelpers.renderContinuousTubeTextured(matrices, mainBuffer, pathPoints,
                        tubeRadius, color, ctx.alpha, tubeSides, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderContinuousTube(matrices, mainBuffer, pathPoints,
                        tubeRadius, color, ctx.alpha, tubeSides);
            }
        }

        if (glowBuffer != null) {
            double glowRadius = tubeRadius + ctx.glowSize;
            float glowAlpha = ctx.alpha * (float) ctx.glowOpacity;
            if (textured) {
                RenderHelpers.renderContinuousTubeTextured(matrices, glowBuffer, pathPoints,
                        glowRadius, color, glowAlpha, Math.max(4, tubeSides - 2), ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderContinuousTube(matrices, glowBuffer, pathPoints,
                        glowRadius, color, glowAlpha, Math.max(4, tubeSides - 2));
            }
        }
    }
}
