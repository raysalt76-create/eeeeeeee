package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LightningCageEffect extends BaseEffect {

    private final Random random = new Random();
    private final List<LightningBolt> activeBolts = new ArrayList<>();
    private long lastSpawnTime = 0;

    private static class LightningBolt {
        Vec3d start;
        Vec3d end;
        long spawnTime;
        float intensity;
        List<Vec3d> segments;

        LightningBolt(Vec3d start, Vec3d end, long spawnTime) {
            this.start = start;
            this.end = end;
            this.spawnTime = spawnTime;
            this.intensity = 0.8f + (float)Math.random() * 0.2f;
            this.segments = generateJaggedPath(start, end, 5);
        }

        private List<Vec3d> generateJaggedPath(Vec3d start, Vec3d end, int subdivisions) {
            List<Vec3d> points = new ArrayList<>();
            points.add(start);

            for (int i = 1; i < subdivisions; i++) {
                double t = (double) i / subdivisions;
                Vec3d midpoint = start.lerp(end, t);

                double offsetMagnitude = 0.1 * (1.0 - Math.abs(t - 0.5) * 2);
                Vec3d offset = new Vec3d(
                        (Math.random() - 0.5) * offsetMagnitude,
                        (Math.random() - 0.5) * offsetMagnitude,
                        (Math.random() - 0.5) * offsetMagnitude
                );

                points.add(midpoint.add(offset));
            }

            points.add(end);
            return points;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderLightning(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderLightning(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderLightning(MatrixStack matrices, VertexConsumer mainBuffer,
                                 VertexConsumer glowBuffer, EffectRenderContext ctx,
                                 boolean textured) {
        long currentTime = ctx.time;
        double height = ctx.player.getHeight();
        double radius = ctx.player.getWidth() * 0.6 * ctx.scaleMultiplier;

        Color color = textured ? ctx.getThemedColor() : ctx.color;

        int spawnInterval = (int)(200 / ctx.speedMultiplier);
        if (currentTime - lastSpawnTime > spawnInterval) {
            spawnNewBolt(radius, height);
            lastSpawnTime = currentTime;
        }

        activeBolts.removeIf(bolt -> currentTime - bolt.spawnTime > 300);

        for (LightningBolt bolt : activeBolts) {
            long age = currentTime - bolt.spawnTime;
            float ageAlpha = 1.0f - (float)age / 300f;
            float alpha = ctx.alpha * ageAlpha * bolt.intensity;

            if (mainBuffer != null) {
                if (textured) {
                    renderLightningSegmentsTextured(matrices, mainBuffer, bolt.segments,
                            color, alpha, 0.02 * ctx.scaleMultiplier, ctx);
                } else {
                    renderLightningSegments(matrices, mainBuffer, bolt.segments,
                            color, alpha, 0.02 * ctx.scaleMultiplier);
                }
            }

            if (glowBuffer != null) {
                float glowAlpha = alpha * (float) ctx.glowOpacity;
                if (textured) {
                    renderLightningSegmentsTextured(matrices, glowBuffer, bolt.segments,
                            color, glowAlpha, 0.04 * ctx.scaleMultiplier, ctx);
                } else {
                    renderLightningSegments(matrices, glowBuffer, bolt.segments,
                            color, glowAlpha, 0.04 * ctx.scaleMultiplier);
                }
            }
        }
    }

    private void spawnNewBolt(double radius, double height) {

        double angle1 = random.nextDouble() * Math.PI * 2;
        double angle2 = random.nextDouble() * Math.PI * 2;
        double y1 = random.nextDouble() * height;
        double y2 = random.nextDouble() * height;

        Vec3d start = new Vec3d(
                Math.cos(angle1) * radius,
                y1,
                Math.sin(angle1) * radius
        );

        Vec3d end = new Vec3d(
                Math.cos(angle2) * radius,
                y2,
                Math.sin(angle2) * radius
        );

        activeBolts.add(new LightningBolt(start, end, System.currentTimeMillis()));
    }

    private void renderLightningSegments(MatrixStack matrices, VertexConsumer buffer,
                                         List<Vec3d> segments, Color color,
                                         float alpha, double thickness) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments.size() - 1; i++) {
            Vec3d p1 = segments.get(i);
            Vec3d p2 = segments.get(i + 1);

            Vec3d dir = p2.subtract(p1).normalize();
            Vec3d perp = new Vec3d(-dir.z, 0, dir.x).normalize().multiply(thickness);

            Vec3d v1 = p1.add(perp);
            Vec3d v2 = p1.subtract(perp);
            Vec3d v3 = p2.subtract(perp);
            Vec3d v4 = p2.add(perp);

            buffer.vertex(matrix, (float)v1.x, (float)v1.y, (float)v1.z).color(r, g, b, alpha);
            buffer.vertex(matrix, (float)v2.x, (float)v2.y, (float)v2.z).color(r, g, b, alpha);
            buffer.vertex(matrix, (float)v3.x, (float)v3.y, (float)v3.z).color(r, g, b, alpha);

            buffer.vertex(matrix, (float)v1.x, (float)v1.y, (float)v1.z).color(r, g, b, alpha);
            buffer.vertex(matrix, (float)v3.x, (float)v3.y, (float)v3.z).color(r, g, b, alpha);
            buffer.vertex(matrix, (float)v4.x, (float)v4.y, (float)v4.z).color(r, g, b, alpha);
        }
    }

    private void renderLightningSegmentsTextured(MatrixStack matrices, VertexConsumer buffer,
                                                 List<Vec3d> segments, Color color,
                                                 float alpha, double thickness,
                                                 EffectRenderContext ctx) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        double totalLength = 0;
        for (int i = 0; i < segments.size() - 1; i++) {
            totalLength += segments.get(i).distanceTo(segments.get(i + 1));
        }

        double accumulatedLength = 0;

        for (int i = 0; i < segments.size() - 1; i++) {
            Vec3d p1 = segments.get(i);
            Vec3d p2 = segments.get(i + 1);

            double segmentLength = p1.distanceTo(p2);

            Vec3d dir = p2.subtract(p1).normalize();
            Vec3d perp = new Vec3d(-dir.z, 0, dir.x).normalize().multiply(thickness);

            Vec3d v1 = p1.add(perp);
            Vec3d v2 = p1.subtract(perp);
            Vec3d v3 = p2.subtract(perp);
            Vec3d v4 = p2.add(perp);

            float v0 = (float)(accumulatedLength / totalLength) + ctx.uvOffsetV;
            float ev1 = (float)((accumulatedLength + segmentLength) / totalLength) + ctx.uvOffsetV;
            float u0 = 0 + ctx.uvOffsetU;
            float u1 = 1 + ctx.uvOffsetU;

            accumulatedLength += segmentLength;

            buffer.vertex(matrix, (float) ev1, (float) ev1, (float) ev1).color(r, g, b, alpha).texture(u0, v0);
            buffer.vertex(matrix, (float)v2.x, (float)v2.y, (float)v2.z).color(r, g, b, alpha).texture(u1, v0);
            buffer.vertex(matrix, (float)v3.x, (float)v3.y, (float)v3.z).color(r, g, b, alpha).texture(u1, ev1);

            buffer.vertex(matrix, (float)ev1, (float)ev1, (float)ev1).color(r, g, b, alpha).texture(u0, v0);
            buffer.vertex(matrix, (float)v3.x, (float)v3.y, (float)v3.z).color(r, g, b, alpha).texture(u1, ev1);
            buffer.vertex(matrix, (float)v4.x, (float)v4.y, (float)v4.z).color(r, g, b, alpha).texture(u0, ev1);
        }
    }
}
