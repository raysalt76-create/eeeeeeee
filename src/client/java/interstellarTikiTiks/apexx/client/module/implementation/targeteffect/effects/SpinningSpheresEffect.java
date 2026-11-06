package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.util.render.TexturedRenderUtils;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;

public class SpinningSpheresEffect extends BaseEffect {

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderSpheres(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderSpheres(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderSpheres(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx,
                               boolean textured) {
        double radius = 1.2 * ctx.scaleMultiplier;
        double sphereSize = 0.15 * ctx.scaleMultiplier;
        int sphereCount = 3;

        double time = ctx.time * ctx.speedMultiplier * 0.001;
        Color color = textured ? ctx.getThemedColor() : ctx.color;

        for (int i = 0; i < sphereCount; i++) {
            double angle = (time + (i * (2 * Math.PI / sphereCount))) % (2 * Math.PI);

            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.sin(time * 2 + i) * 0.3;

            Vec3d pos = new Vec3d(x, ctx.player.getHeight() / 2 + y, z);

            if (textured) {
                renderTexturedSphere(matrices, mainBuffer, glowBuffer, pos, sphereSize, color, ctx);
            } else {
                renderSolidSphere(matrices, mainBuffer, glowBuffer, pos, sphereSize, color, ctx);
            }
        }
    }

    private void renderSolidSphere(MatrixStack matrices, VertexConsumer mainBuffer,
                                   VertexConsumer glowBuffer, Vec3d pos, double size,
                                   Color color, EffectRenderContext ctx) {
        matrices.push();
        matrices.translate(pos.x, pos.y, pos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        if (mainBuffer != null) {
            drawColoredSphere(mainBuffer, matrix, size, color, ctx.alpha, ctx.segments);
        }

        if (glowBuffer != null) {
            double glowSize = size + ctx.glowSize;
            drawColoredSphere(glowBuffer, matrix, glowSize, color, (float) ctx.glowOpacity, ctx.segments / 2);
        }

        matrices.pop();
    }

    private void renderTexturedSphere(MatrixStack matrices, VertexConsumer mainBuffer,
                                      VertexConsumer glowBuffer, Vec3d pos, double size,
                                      Color color, EffectRenderContext ctx) {
        matrices.push();
        matrices.translate(pos.x, pos.y, pos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        if (mainBuffer != null) {
            TexturedRenderUtils.drawTexturedSphere(
                    mainBuffer, matrix, Vec3d.ZERO, size, color, ctx.alpha,
                    ctx.segments, ctx.uvOffsetU, ctx.uvOffsetV
            );
        }

        if (glowBuffer != null) {
            double glowSize = size + ctx.glowSize;
            TexturedRenderUtils.drawTexturedSphere(
                    glowBuffer, matrix, Vec3d.ZERO, glowSize, color, (float) ctx.glowOpacity,
                    ctx.segments / 2, ctx.uvOffsetU, ctx.uvOffsetV
            );
        }

        matrices.pop();
    }

    private void drawColoredSphere(VertexConsumer buffer, Matrix4f matrix, double radius,
                                   Color color, float alpha, int segments) {
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments; i++) {
            double lat0 = Math.PI * (-0.5 + (double) i / segments);
            double lat1 = Math.PI * (-0.5 + (double) (i + 1) / segments);

            double y0 = Math.sin(lat0) * radius;
            double y1 = Math.sin(lat1) * radius;
            double r0 = Math.cos(lat0) * radius;
            double r1 = Math.cos(lat1) * radius;

            for (int j = 0; j < segments; j++) {
                double lng0 = 2 * Math.PI * (double) j / segments;
                double lng1 = 2 * Math.PI * (double) (j + 1) / segments;

                float x0 = (float) (Math.cos(lng0) * r0);
                float z0 = (float) (Math.sin(lng0) * r0);
                float x1 = (float) (Math.cos(lng1) * r0);
                float z1 = (float) (Math.sin(lng1) * r0);
                float x2 = (float) (Math.cos(lng0) * r1);
                float z2 = (float) (Math.sin(lng0) * r1);
                float x3 = (float) (Math.cos(lng1) * r1);
                float z3 = (float) (Math.sin(lng1) * r1);

                buffer.vertex(matrix, x0, (float) y0, z0).color(r, g, b, alpha);
                buffer.vertex(matrix, x1, (float) y0, z1).color(r, g, b, alpha);
                buffer.vertex(matrix, x2, (float) y1, z2).color(r, g, b, alpha);

                buffer.vertex(matrix, x1, (float) y0, z1).color(r, g, b, alpha);
                buffer.vertex(matrix, x3, (float) y1, z3).color(r, g, b, alpha);
                buffer.vertex(matrix, x2, (float) y1, z2).color(r, g, b, alpha);
            }
        }
    }
}
