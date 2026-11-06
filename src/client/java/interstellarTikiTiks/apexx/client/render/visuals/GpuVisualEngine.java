package interstellarTikiTiks.apexx.client.render.visuals;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;

/**
 * Shared model-space GPU pass owner for world visuals. Feature modules provide intent and
 * parameters; this class owns the compatible entity vertex format, material and pass submission.
 */
public final class GpuVisualEngine {
    private static final Identifier ENTITY_VERTEX = Identifier.of("minecraft", "core/entity");
    private static final Identifier GRADIENT_FRAGMENT = Identifier.of(Apexx.MOD_ID, "core/visuals/player_gradient");

    private static final RenderPipeline PLAYER_FILL_DEPTH = createPipeline("player_gradient_depth", DepthTestFunction.LEQUAL_DEPTH_TEST);
    private static final RenderPipeline PLAYER_FILL_XRAY = createPipeline("player_gradient_xray", DepthTestFunction.NO_DEPTH_TEST);
    private static final RenderLayer FILL_DEPTH = createLayer("apexx_visual_player_gradient_depth", PLAYER_FILL_DEPTH);
    private static final RenderLayer FILL_XRAY = createLayer("apexx_visual_player_gradient_xray", PLAYER_FILL_XRAY);

    private GpuVisualEngine() {}

    private static RenderPipeline createPipeline(String name, DepthTestFunction depthTest) {
        return interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(RenderPipeline.builder(
                        RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET,
                        RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.of(Apexx.MOD_ID, "gpu_visual_" + name))
                .withVertexShader(ENTITY_VERTEX)
                .withFragmentShader(GRADIENT_FRAGMENT)
                .withSampler("Sampler1")
                .withSampler("Sampler2")
                .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                .withCull(false)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(depthTest)
                .withDepthWrite(false)
                .build());
    }

    private static RenderLayer createLayer(String name, RenderPipeline pipeline) {
        return RenderLayer.of(name, RenderSetup.builder(pipeline).expectedBufferSize(1536).translucent().build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submitPlayerModelPass(
            OrderedRenderCommandQueue queue,
            EntityModel model,
            LivingEntityRenderState state,
            MatrixStack matrices,
            Color color,
            float opacity,
            float scale,
            boolean throughWalls
    ) {
        if (queue == null || model == null || state == null || color == null) return;
        if (!interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(
                throughWalls ? PLAYER_FILL_XRAY : PLAYER_FILL_DEPTH)) return;

        int alpha = MathHelper.clamp(Math.round(MathHelper.clamp(opacity, 0.0f, 1.0f) * 255.0f), 0, 255);
        int argb = (alpha << 24) | (color.getRGB() & 0x00FFFFFF);
        int overlay = LivingEntityRenderer.getOverlay(state, 0.0f);
        RenderLayer layer = throughWalls ? FILL_XRAY : FILL_DEPTH;

        matrices.push();
        if (Math.abs(scale - 1.0f) > 0.0001f) matrices.scale(scale, scale, scale);
        model.setAngles(state);
        queue.submitModel(model, state, matrices, layer, 0x00F000F0, overlay, argb, null, state.outlineColor, null);
        matrices.pop();
    }
}
