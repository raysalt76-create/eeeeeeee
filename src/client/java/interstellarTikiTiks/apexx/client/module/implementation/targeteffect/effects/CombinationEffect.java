package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.GalaxyEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.ScanLinesEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.ShockwaveRingsEffect;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

public class CombinationEffect extends BaseEffect {

    private final interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.ShockwaveRingsEffect shockwave = new ShockwaveRingsEffect();
    private final interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.ScanLinesEffect scanLines = new ScanLinesEffect();
    private final interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.GalaxyEffect galaxy = new GalaxyEffect();

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderCombination(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderCombination(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderCombination(MatrixStack matrices, VertexConsumer mainBuffer,
                                   VertexConsumer glowBuffer, EffectRenderContext ctx,
                                   boolean textured) {

        matrices.push();
        if (textured) {
            shockwave.renderTextured(matrices, mainBuffer, glowBuffer, ctx);
        } else {
            shockwave.render(matrices, mainBuffer, glowBuffer, ctx);
        }
        matrices.pop();

        matrices.push();

        EffectRenderContext scanCtx = new EffectRenderContext(
                ctx.player, ctx.effectType, ctx.color, ctx.alpha * 0.5f,
                ctx.time, ctx.speedMultiplier, ctx.scaleMultiplier * 0.8, ctx.segments,
                ctx.glowEnabled, ctx.glowSize, ctx.glowOpacity, ctx.opaqueMode,
                ctx.theme
        );
        if (textured) {
            scanLines.renderTextured(matrices, mainBuffer, glowBuffer, scanCtx);
        } else {
            scanLines.render(matrices, mainBuffer, glowBuffer, scanCtx);
        }
        matrices.pop();

        matrices.push();
        EffectRenderContext galaxyCtx = new EffectRenderContext(
                ctx.player, ctx.effectType, ctx.color, ctx.alpha * 0.7f,
                ctx.time, ctx.speedMultiplier * 1.5, ctx.scaleMultiplier, ctx.segments,
                ctx.glowEnabled, ctx.glowSize, ctx.glowOpacity, ctx.opaqueMode,
                ctx.theme
        );
        if (textured) {
            galaxy.renderTextured(matrices, mainBuffer, glowBuffer, galaxyCtx);
        } else {
            galaxy.render(matrices, mainBuffer, glowBuffer, galaxyCtx);
        }
        matrices.pop();

        matrices.push();
        renderCoreSphere(matrices, mainBuffer, glowBuffer, ctx, textured);
        matrices.pop();

        matrices.push();
        renderEnergyPillars(matrices, mainBuffer, glowBuffer, ctx, textured);
        matrices.pop();
    }

    private void renderCoreSphere(MatrixStack matrices, VertexConsumer mainBuffer,
                                  VertexConsumer glowBuffer, EffectRenderContext ctx,
                                  boolean textured) {
        double torsoHeight = ctx.player.getHeight() / 2.0;
        double pulse = Math.sin(ctx.time / 300.0) * 0.3 + 0.7;
        double radius = 0.2 * ctx.scaleMultiplier * pulse;

        java.awt.Color color = textured ? ctx.getThemedColor() : ctx.color;

        matrices.push();
        matrices.translate(0, torsoHeight, 0);

        if (mainBuffer != null) {
            if (textured) {
                interstellarTikiTiks.apexx.client.util.render.TexturedRenderUtils.drawTexturedSphere(
                        mainBuffer, matrices.peek().getPositionMatrix(),
                        net.minecraft.util.math.Vec3d.ZERO, radius, color, ctx.alpha * 0.8f,
                        Math.max(8, ctx.segments / 4), ctx.uvOffsetU, ctx.uvOffsetV
                );
            } else {
                RenderHelpers.renderSphereOptimized(matrices, mainBuffer,
                        radius, color, ctx.alpha * 0.8f, Math.max(8, ctx.segments / 4));
            }
        }

        if (glowBuffer != null) {
            double glowRadius = radius * 1.5;
            if (textured) {
                interstellarTikiTiks.apexx.client.util.render.TexturedRenderUtils.drawTexturedSphere(
                        glowBuffer, matrices.peek().getPositionMatrix(),
                        net.minecraft.util.math.Vec3d.ZERO, glowRadius, color,
                        ctx.alpha * (float)ctx.glowOpacity * 0.6f,
                        Math.max(6, ctx.segments / 6), ctx.uvOffsetU, ctx.uvOffsetV
                );
            } else {
                RenderHelpers.renderSphereOptimized(matrices, glowBuffer,
                        glowRadius, color, ctx.alpha * (float)ctx.glowOpacity * 0.6f,
                        Math.max(6, ctx.segments / 6));
            }
        }

        matrices.pop();
    }

    private void renderEnergyPillars(MatrixStack matrices, VertexConsumer mainBuffer,
                                     VertexConsumer glowBuffer, EffectRenderContext ctx,
                                     boolean textured) {
        double height = ctx.player.getHeight();
        double radius = ctx.player.getWidth() * 0.6 * ctx.scaleMultiplier;
        double pillarRadius = 0.03 * ctx.scaleMultiplier;
        int pillarCount = 4;

        java.awt.Color color = textured ? ctx.getThemedColor() : ctx.color;

        for (int i = 0; i < pillarCount; i++) {
            double angle = (Math.PI * 2 * i / pillarCount) + (ctx.time / 1000.0);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;

            matrices.push();
            matrices.translate(x, 0, z);

            if (mainBuffer != null) {
                if (textured) {
                    renderVerticalCylinderTextured(matrices, mainBuffer, pillarRadius,
                            height, color, ctx.alpha * 0.6f, 8, ctx);
                } else {
                    renderVerticalCylinder(matrices, mainBuffer, pillarRadius,
                            height, color, ctx.alpha * 0.6f, 8);
                }
            }

            if (glowBuffer != null) {
                if (textured) {
                    renderVerticalCylinderTextured(matrices, glowBuffer,
                            pillarRadius + ctx.glowSize * 0.5, height, color,
                            ctx.alpha * (float)ctx.glowOpacity * 0.4f, 6, ctx);
                } else {
                    renderVerticalCylinder(matrices, glowBuffer,
                            pillarRadius + ctx.glowSize * 0.5, height, color,
                            ctx.alpha * (float)ctx.glowOpacity * 0.4f, 6);
                }
            }

            matrices.pop();
        }
    }

    private void renderVerticalCylinder(MatrixStack matrices, VertexConsumer buffer,
                                        double radius, double height,
                                        java.awt.Color color, float alpha, int segments) {
        org.joml.Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments; i++) {
            double angle1 = 2 * Math.PI * i / segments;
            double angle2 = 2 * Math.PI * (i + 1) / segments;

            float x1 = (float)(Math.cos(angle1) * radius);
            float z1 = (float)(Math.sin(angle1) * radius);
            float x2 = (float)(Math.cos(angle2) * radius);
            float z2 = (float)(Math.sin(angle2) * radius);

            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);
            buffer.vertex(matrix, x2, 0, z2).color(r, g, b, alpha);
            buffer.vertex(matrix, x2, (float)height, z2).color(r, g, b, alpha * 0.5f);

            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);
            buffer.vertex(matrix, x2, (float)height, z2).color(r, g, b, alpha * 0.5f);
            buffer.vertex(matrix, x1, (float)height, z1).color(r, g, b, alpha * 0.5f);
        }
    }

    private void renderVerticalCylinderTextured(MatrixStack matrices, VertexConsumer buffer,
                                                double radius, double height,
                                                java.awt.Color color, float alpha, int segments,
                                                EffectRenderContext ctx) {
        org.joml.Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments; i++) {
            double angle1 = 2 * Math.PI * i / segments;
            double angle2 = 2 * Math.PI * (i + 1) / segments;

            float x1 = (float)(Math.cos(angle1) * radius);
            float z1 = (float)(Math.sin(angle1) * radius);
            float x2 = (float)(Math.cos(angle2) * radius);
            float z2 = (float)(Math.sin(angle2) * radius);

            float u1 = ((float) i / segments) + ctx.uvOffsetU;
            float u2 = ((float) (i + 1) / segments) + ctx.uvOffsetU;
            float v0 = 0 + ctx.uvOffsetV;
            float v1 = 1 + ctx.uvOffsetV;

            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha).texture(u1, v0);
            buffer.vertex(matrix, x2, 0, z2).color(r, g, b, alpha).texture(u2, v0);
            buffer.vertex(matrix, x2, (float)height, z2).color(r, g, b, alpha * 0.5f).texture(u2, v1);

            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha).texture(u1, v0);
            buffer.vertex(matrix, x2, (float)height, z2).color(r, g, b, alpha * 0.5f).texture(u2, v1);
            buffer.vertex(matrix, x1, (float)height, z1).color(r, g, b, alpha * 0.5f).texture(u1, v1);
        }
    }
}
