package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.awt.*;

public class ScanLinesEffect extends BaseEffect {

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderScanLines(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderScanLines(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderScanLines(MatrixStack matrices, VertexConsumer mainBuffer,
                                 VertexConsumer glowBuffer, EffectRenderContext ctx,
                                 boolean textured) {
        double height = ctx.player.getHeight();
        double width = ctx.player.getWidth();
        double radius = width * 1.0;

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        double speed = ctx.speedMultiplier * 0.003;
        double rawSine = Math.sin(ctx.time * speed);
        double phase = (rawSine + 1.0) / 2.0;
        double scanY = height * phase;
        boolean movingUp = Math.cos(ctx.time * speed) > 0;

        double scanThickness = 0.05 * ctx.scaleMultiplier;
        double trailSpacing = 0.06 * ctx.scaleMultiplier;

        renderRing(matrices, mainBuffer, radius, scanY, scanThickness,
                color, ctx.alpha, false, textured, ctx);

        int trailSegments = 5;
        for (int i = 1; i <= trailSegments; i++) {
            double offset = i * trailSpacing;
            double trailY = movingUp ? scanY - offset : scanY + offset;

            if (trailY < 0 || trailY > height) continue;

            float trailAlpha = ctx.alpha * (1.0f - (float)i / trailSegments) * 0.5f;
            double trailThick = scanThickness * (1.0 - (double)i / trailSegments * 0.5);

            renderRing(matrices, mainBuffer, radius, trailY, trailThick,
                    color, trailAlpha, false, textured, ctx);
        }

        if (glowBuffer != null) {
            renderRing(matrices, glowBuffer, radius, scanY,
                    scanThickness + ctx.glowSize, color,
                    ctx.alpha * (float)ctx.glowOpacity, true, textured, ctx);
        }
    }

    private void renderRing(MatrixStack matrices, VertexConsumer buffer,
                            double radius, double y, double thickness,
                            Color color, float alpha, boolean isGlow,
                            boolean textured, EffectRenderContext ctx) {
        if (buffer == null) return;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        if (!isGlow && !textured) {
            float pulse = (float)(Math.sin(System.currentTimeMillis() / 100.0) * 0.2 + 0.8);
            r *= pulse;
            g *= pulse;
            b *= pulse;
        }

        double halfThick = thickness / 2.0;
        double yBottom = y - halfThick;
        double yTop = y + halfThick;

        int segments = 32;
        double increment = (Math.PI * 2) / segments;

        for (int i = 0; i < segments; i++) {
            double angle1 = i * increment;
            double angle2 = (i + 1) * increment;

            float x1 = (float)(Math.cos(angle1) * radius);
            float z1 = (float)(Math.sin(angle1) * radius);
            float x2 = (float)(Math.cos(angle2) * radius);
            float z2 = (float)(Math.sin(angle2) * radius);

            if (textured) {

                float u1 = ((float) i / segments) + ctx.uvOffsetU;
                float u2 = ((float) (i + 1) / segments) + ctx.uvOffsetU;
                float v0 = 0 + ctx.uvOffsetV;
                float v1 = 1 + ctx.uvOffsetV;

                buffer.vertex(matrix, x1, (float)yBottom, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x2, (float)yBottom, z2).color(r, g, b, alpha).texture(u2, v0);
                buffer.vertex(matrix, x2, (float)yTop, z2).color(r, g, b, alpha).texture(u2, v1);

                buffer.vertex(matrix, x1, (float)yBottom, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x2, (float)yTop, z2).color(r, g, b, alpha).texture(u2, v1);
                buffer.vertex(matrix, x1, (float)yTop, z1).color(r, g, b, alpha).texture(u1, v1);
            } else {

                float nx = (x1 + x2) / 2.0f;
                float nz = (z1 + z2) / 2.0f;
                float len = (float)Math.sqrt(nx*nx + nz*nz);
                nx /= len;
                nz /= len;
                float ny = 0;

                buffer.vertex(matrix, x1, (float)yBottom, z1).color(r, g, b, alpha).normal(nx, ny, nz);
                buffer.vertex(matrix, x2, (float)yBottom, z2).color(r, g, b, alpha).normal(nx, ny, nz);
                buffer.vertex(matrix, x2, (float)yTop, z2).color(r, g, b, alpha).normal(nx, ny, nz);

                buffer.vertex(matrix, x1, (float)yBottom, z1).color(r, g, b, alpha).normal(nx, ny, nz);
                buffer.vertex(matrix, x2, (float)yTop, z2).color(r, g, b, alpha).normal(nx, ny, nz);
                buffer.vertex(matrix, x1, (float)yTop, z1).color(r, g, b, alpha).normal(nx, ny, nz);
            }
        }
    }
}
