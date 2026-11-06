package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectFactory;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import interstellarTikiTiks.apexx.client.shader.CoreShaderManager;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.awt.*;

public class EffectRenderer {
    private final EffectFactory effectFactory = new EffectFactory();
    private final CoreShaderManager shaderManager = CoreShaderManager.getInstance();

    private static final RenderLayer GLOW_LAYER = ShaderRenderLayers.getShaderLayerNoDepth("fallback", true);

    public void render(MatrixStack matrices, Vec3d cameraPos, Vec3d playerPos, EffectRenderContext ctx) {

        BaseEffect effect = effectFactory.getEffect(ctx.effectType);

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1536)) {

        if ("Overlay".equals(ctx.effectType)) {
            VertexConsumerProvider.Immediate vertexConsumers =
                    VertexConsumerProvider.immediate(allocator);

            boolean useShader = ctx.theme.usesShader();
            String shaderName = ctx.theme.getShaderName();

            if (useShader && shaderName != null) {
                updateShaderUniforms(shaderName, ctx);
            }

            RenderLayer mainLayer = useShader
                    ? ShaderRenderLayers.getShaderLayerNoDepth(shaderName, false)
                    : RenderTypes.TRIANGLES_NO_DEPTH;

            VertexConsumer mainBuffer = vertexConsumers.getBuffer(mainLayer);
            effect.render(matrices, mainBuffer, null, ctx);
            vertexConsumers.draw();

            if (ctx.glowEnabled) {
                RenderLayer glowLayer = useShader
                        ? ShaderRenderLayers.getShaderLayerNoDepth(shaderName, true)
                        : GLOW_LAYER;

                if (useShader && shaderName != null) {
                    updateShaderUniforms(shaderName, ctx);
                }

                VertexConsumer glowBuffer = vertexConsumers.getBuffer(glowLayer);
                effect.render(matrices, null, glowBuffer, ctx);
                vertexConsumers.draw();
            }

            return;
        }

        VertexConsumerProvider.Immediate vertexConsumers =
                VertexConsumerProvider.immediate(allocator);

        matrices.push();
        matrices.translate(
                playerPos.x - cameraPos.x,
                playerPos.y - cameraPos.y,
                playerPos.z - cameraPos.z
        );

        boolean useShader = ctx.theme.usesShader();
        String shaderName = ctx.theme.getShaderName();

        if (useShader && shaderName != null) {
            updateShaderUniforms(shaderName, ctx);
        }

        RenderLayer mainLayer = useShader
                ? ShaderRenderLayers.getShaderLayerNoDepth(shaderName, false)
                : RenderTypes.TRIANGLES_NO_DEPTH;

        VertexConsumer mainBuffer = vertexConsumers.getBuffer(mainLayer);
        effect.render(matrices, mainBuffer, null, ctx);
        vertexConsumers.draw();

        if (ctx.glowEnabled) {
            RenderLayer glowLayer = useShader
                    ? ShaderRenderLayers.getShaderLayerNoDepth(shaderName, true)
                    : GLOW_LAYER;

            if (useShader && shaderName != null) {
                updateShaderUniforms(shaderName, ctx);
            }

            VertexConsumer glowBuffer = vertexConsumers.getBuffer(glowLayer);
            effect.render(matrices, null, glowBuffer, ctx);
            vertexConsumers.draw();
        }

        matrices.pop();
        }
    }

    private void updateShaderUniforms(String shaderName, EffectRenderContext ctx) {
        float time = (float) (ctx.time / 1000.0);

        float[] colorArray = new float[] {
                ctx.color.getRed() / 255f,
                ctx.color.getGreen() / 255f,
                ctx.color.getBlue() / 255f,
                ctx.alpha
        };

        float speed = (float) ctx.speedMultiplier;

        shaderManager.setShaderUniforms(
                net.minecraft.util.Identifier.of(Apexx.MOD_ID, shaderName),
                time,
                colorArray,
                speed
        );
    }

    public static int calculateLOD(double distance, int maxSegments) {
        if (distance < 16) return maxSegments;
        if (distance < 32) return maxSegments / 2;
        if (distance < 64) return maxSegments / 3;
        return Math.max(8, maxSegments / 4);
    }

    public static Color getRainbowColor(long time) {
        float hue = (time % 3000) / 3000.0f;
        return Color.getHSBColor(hue, 0.8f, 1.0f);
    }
}
