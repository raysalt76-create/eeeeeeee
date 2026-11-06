package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import interstellarTikiTiks.apexx.client.util.render.TexturedRenderUtils;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;

public class TornadoEffect extends BaseEffect {
    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderTornado(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderTornado(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderTornado(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx,
                               boolean textured) {
        double height = ctx.player.getHeight();
        double timeOffset = (ctx.time * ctx.speedMultiplier) / 1000.0;
        int particles = Math.max(16, ctx.segments * 2);

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        for (int i = 0; i < particles; i++) {
            double t = (double) i / particles;
            double y = t * height;
            double radius = ctx.player.getWidth() * 0.2 * ctx.scaleMultiplier * (1.0 + t * 2.0);
            double angle = t * Math.PI * 6 + timeOffset * (1.0 + t);

            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double particleSize = 0.035 * ctx.scaleMultiplier;

            matrices.push();
            matrices.translate(x, y, z);

            if (textured) {
                renderTexturedParticle(matrices, mainBuffer, glowBuffer, particleSize,
                        color, ctx.alpha, ctx);
            } else {
                if (mainBuffer != null) {
                    RenderHelpers.renderSphereOptimized(matrices, mainBuffer, particleSize,
                            color, ctx.alpha, Math.max(6, ctx.segments / 4));
                }

                if (glowBuffer != null) {
                    double glowRadius = particleSize + ctx.glowSize * 0.5;
                    float glowAlpha = ctx.alpha * (float) ctx.glowOpacity;
                    RenderHelpers.renderSphereOptimized(matrices, glowBuffer, glowRadius,
                            color, glowAlpha, Math.max(4, ctx.segments / 6));
                }
            }

            matrices.pop();
        }
    }

    private void renderTexturedParticle(MatrixStack matrices, VertexConsumer mainBuffer,
                                        VertexConsumer glowBuffer, double size, Color color,
                                        float alpha, EffectRenderContext ctx) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        if (mainBuffer != null) {
            TexturedRenderUtils.drawTexturedSphere(mainBuffer, matrix, Vec3d.ZERO, size,
                    color, alpha, Math.max(6, ctx.segments / 4), ctx.uvOffsetU, ctx.uvOffsetV);
        }

        if (glowBuffer != null) {
            double glowRadius = size + ctx.glowSize * 0.5;
            float glowAlpha = alpha * (float) ctx.glowOpacity;
            TexturedRenderUtils.drawTexturedSphere(glowBuffer, matrix, Vec3d.ZERO, glowRadius,
                    color, glowAlpha, Math.max(4, ctx.segments / 6), ctx.uvOffsetU, ctx.uvOffsetV);
        }
    }
}
