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

public class GalaxyEffect extends BaseEffect {

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderGalaxy(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderGalaxy(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderGalaxy(MatrixStack matrices, VertexConsumer mainBuffer,
                              VertexConsumer glowBuffer, EffectRenderContext ctx,
                              boolean textured) {
        double torsoHeight = ctx.player.getHeight() / 2.0;
        double rotation = (ctx.time * ctx.speedMultiplier * 0.5) / 1000.0;

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        matrices.push();
        matrices.translate(0, torsoHeight, 0);

        int arms = 4;
        int particlesPerArm = Math.max(8, ctx.segments);

        for (int arm = 0; arm < arms; arm++) {
            double armOffset = (2 * Math.PI * arm) / arms;

            for (int i = 0; i < particlesPerArm; i++) {
                double t = (double) i / particlesPerArm;
                double angle = t * Math.PI * 3 + rotation + armOffset;
                double dist = t * ctx.player.getWidth() * 1.2 * ctx.scaleMultiplier;

                double x = Math.cos(angle) * dist;
                double z = Math.sin(angle) * dist;
                double particleSize = 0.04 * ctx.scaleMultiplier * (1.0 - t * 0.5);
                float particleAlpha = ctx.alpha * (1.0f - (float)t * 0.3f);

                matrices.push();
                matrices.translate(x, 0, z);

                if (textured) {
                    renderTexturedParticle(matrices, mainBuffer, glowBuffer, particleSize,
                            color, particleAlpha, ctx);
                } else {
                    if (mainBuffer != null) {
                        RenderHelpers.renderSphereOptimized(matrices, mainBuffer, particleSize,
                                color, particleAlpha, Math.max(6, ctx.segments / 4));
                    }

                    if (glowBuffer != null) {
                        double glowRadius = particleSize + ctx.glowSize * 0.5;
                        float glowAlpha = particleAlpha * (float) ctx.glowOpacity;
                        RenderHelpers.renderSphereOptimized(matrices, glowBuffer, glowRadius,
                                color, glowAlpha, Math.max(4, ctx.segments / 6));
                    }
                }

                matrices.pop();
            }
        }

        matrices.pop();
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
