package interstellarTikiTiks.apexx.client.util.render.types;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TexturedRenderLayers {

    // EntityModel emits the complete entity vertex layout (colour, UV, overlay,
    // light and normal).  POSITION_TEXTURE_COLOR only works for flat quads; using
    // it for a player model misaligns every following attribute and tears the
    // mesh into screen-spanning triangles.
    // 1.21.11's translucent entity pipeline is assembled from the core/entity shader.
    // There is no core/rendertype_entity_translucent shader resource anymore; referring to
    // that old name makes every layer in this class fail compilation the first time it draws.
    private static final Identifier ENTITY_SHADER = Identifier.of("minecraft", "core/entity");

    private static final RenderPipeline TEX_COLOR_TRIANGLES_NO_DEPTH_ALPHA = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_tex_color_triangles_nd_alpha"))
                    .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline TEX_COLOR_TRIANGLES_NO_DEPTH_ADDITIVE = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_tex_color_triangles_nd_add"))
                    .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.LIGHTNING)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline TEX_COLOR_TRIANGLES_DEPTH_ALPHA = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_tex_color_triangles_depth_alpha"))
                    .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline TEX_COLOR_TRIANGLES_DEPTH_ADDITIVE = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_tex_color_triangles_depth_add"))
                    .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.LIGHTNING)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final Map<Identifier, RenderLayer> LAYER_ND_ALPHA = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderLayer> LAYER_ND_ADD = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderLayer> LAYER_DEPTH_ALPHA = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderLayer> LAYER_DEPTH_ADD = new ConcurrentHashMap<>();

    private TexturedRenderLayers() {}

    public static RenderLayer getTexturedLayer(Identifier texture, boolean additive) {

        Map<Identifier, RenderLayer> cache = additive ? LAYER_ND_ADD : LAYER_ND_ALPHA;
        RenderPipeline pipeline = additive ? TEX_COLOR_TRIANGLES_NO_DEPTH_ADDITIVE : TEX_COLOR_TRIANGLES_NO_DEPTH_ALPHA;
        return cache.computeIfAbsent(texture, id -> createLayer("apexx_textured_effect_" + (additive ? "add" : "alpha"), pipeline, id));
    }

    public static RenderLayer getTexturedLayerNoDepth(Identifier texture, boolean additive) {

        Map<Identifier, RenderLayer> cache = additive ? LAYER_DEPTH_ADD : LAYER_DEPTH_ALPHA;
        RenderPipeline pipeline = additive ? TEX_COLOR_TRIANGLES_DEPTH_ADDITIVE : TEX_COLOR_TRIANGLES_DEPTH_ALPHA;
        return cache.computeIfAbsent(texture, id -> createLayer("apexx_textured_depth_" + (additive ? "add" : "alpha"), pipeline, id));
    }

    public static RenderLayer getTexturedGlowLayer(Identifier texture) {
        return getTexturedLayer(texture, true);
    }

    private static RenderLayer createLayer(String name, RenderPipeline pipeline, Identifier texture) {
        return RenderLayer.of(
                name,
                RenderSetup.builder(pipeline)
                        .texture("Sampler0", texture)
                        .useLightmap()
                        .useOverlay()
                        .expectedBufferSize(1536)
                        .translucent()
                        .build()
        );
    }
}
