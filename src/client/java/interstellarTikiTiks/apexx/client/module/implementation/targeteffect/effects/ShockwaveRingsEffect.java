package interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.RenderHelpers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ShockwaveRingsEffect extends BaseEffect {

    private final List<Shockwave> activeWaves = new ArrayList<>();
    private long lastSpawnTime = 0;

    private static class Shockwave {
        long spawnTime;
        double initialRadius;

        Shockwave(long spawnTime) {
            this.spawnTime = spawnTime;
            this.initialRadius = 0.0;
        }

        double getRadius(long currentTime, double speed) {
            double age = (currentTime - spawnTime) / 1000.0;
            return initialRadius + age * 2.0 * speed;
        }

        float getAlpha(long currentTime, double maxRadius) {
            double radius = getRadius(currentTime, 1.0);
            return (float) Math.max(0, 1.0 - radius / maxRadius);
        }

        boolean isDead(long currentTime, double maxRadius) {
            return getRadius(currentTime, 1.0) > maxRadius;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer mainBuffer,
                       VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderShockwave(matrices, mainBuffer, glowBuffer, ctx, false);
    }

    @Override
    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {
        renderShockwave(matrices, mainBuffer, glowBuffer, ctx, true);
    }

    private void renderShockwave(MatrixStack matrices, VertexConsumer mainBuffer,
                                 VertexConsumer glowBuffer, EffectRenderContext ctx,
                                 boolean textured) {
        long currentTime = ctx.time;
        double maxRadius = ctx.player.getWidth() * 3.0 * ctx.scaleMultiplier;
        Color color = textured ? ctx.getThemedColor() : ctx.color;

        int spawnInterval = (int)(800 / ctx.speedMultiplier);
        if (currentTime - lastSpawnTime > spawnInterval) {
            activeWaves.add(new Shockwave(currentTime));
            lastSpawnTime = currentTime;
        }

        activeWaves.removeIf(wave -> wave.isDead(currentTime, maxRadius));

        matrices.push();
        matrices.translate(0, 0.05, 0);

        for (Shockwave wave : activeWaves) {
            double radius = wave.getRadius(currentTime, ctx.speedMultiplier);
            float alpha = wave.getAlpha(currentTime, maxRadius) * ctx.alpha;
            double thickness = 0.08 * ctx.scaleMultiplier;

            double pulse = Math.sin(currentTime / 100.0 + wave.spawnTime) * 0.2 + 1.0;
            thickness *= pulse;

            if (mainBuffer != null) {
                if (textured) {
                    RenderHelpers.renderTorusTextured(matrices, mainBuffer,
                            radius, thickness, 0, color, alpha,
                            Math.max(16, ctx.segments), 8, ctx.uvOffsetU, ctx.uvOffsetV);
                } else {
                    RenderHelpers.renderTorusOptimized(matrices, mainBuffer,
                            radius, thickness, 0, color, alpha,
                            Math.max(16, ctx.segments), 8);
                }
            }

            if (glowBuffer != null) {
                double glowThickness = thickness + ctx.glowSize;
                float glowAlpha = alpha * (float)ctx.glowOpacity;
                if (textured) {
                    RenderHelpers.renderTorusTextured(matrices, glowBuffer,
                            radius, glowThickness, 0, color, glowAlpha,
                            Math.max(12, ctx.segments / 2), 6, ctx.uvOffsetU, ctx.uvOffsetV);
                } else {
                    RenderHelpers.renderTorusOptimized(matrices, glowBuffer,
                            radius, glowThickness, 0, color, glowAlpha,
                            Math.max(12, ctx.segments / 2), 6);
                }
            }
        }

        matrices.pop();

        if (ctx.time % 100 < 50) {
            renderGroundGlow(matrices, mainBuffer, glowBuffer, ctx, textured, color);
        }
    }

    private void renderGroundGlow(MatrixStack matrices, VertexConsumer mainBuffer,
                                  VertexConsumer glowBuffer, EffectRenderContext ctx,
                                  boolean textured, Color color) {
        double radius = ctx.player.getWidth() * 0.4 * ctx.scaleMultiplier;
        double pulse = Math.sin(ctx.time / 200.0) * 0.5 + 0.5;
        float glowAlpha = ctx.alpha * (float)pulse * 0.3f;

        matrices.push();
        matrices.translate(0, 0.01, 0);

        if (mainBuffer != null) {
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, mainBuffer,
                        radius, 0.05 * ctx.scaleMultiplier, 0,
                        color, glowAlpha, 24, 6, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, mainBuffer,
                        radius, 0.05 * ctx.scaleMultiplier, 0,
                        color, glowAlpha, 24, 6);
            }
        }

        if (glowBuffer != null) {
            if (textured) {
                RenderHelpers.renderTorusTextured(matrices, glowBuffer,
                        radius * 1.2, 0.08 * ctx.scaleMultiplier, 0,
                        color, glowAlpha * (float)ctx.glowOpacity, 16, 4, ctx.uvOffsetU, ctx.uvOffsetV);
            } else {
                RenderHelpers.renderTorusOptimized(matrices, glowBuffer,
                        radius * 1.2, 0.08 * ctx.scaleMultiplier, 0,
                        color, glowAlpha * (float)ctx.glowOpacity, 16, 4);
            }
        }

        matrices.pop();
    }
}
