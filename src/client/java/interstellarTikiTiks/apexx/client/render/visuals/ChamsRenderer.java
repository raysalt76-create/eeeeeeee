package interstellarTikiTiks.apexx.client.render.visuals;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.EntityChamsProvider;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Chams model passes: the entity's live model re-submitted in a flat colour, once with no depth test
 * (seen through walls) and once depth-tested (the visible parts), cut to the entity texture's alpha so
 * skin overlay layers keep their shape. Uses the vanilla entity vertex shader unlit
 * ({@code NO_CARDINAL_LIGHTING}) and {@code apexx:core/visuals/chams} for the fragment.
 *
 * <p>Called from {@code LivingEntityRendererMixin} on the render thread; layer caches are therefore
 * plain maps. Lives in the loader (not the payload) because the mixin references it directly.</p>
 */
public final class ChamsRenderer {
    private static final Identifier ENTITY_VERTEX = Identifier.of("minecraft", "core/entity");
    private static final Identifier CHAMS_FRAGMENT = Identifier.of(Apexx.MOD_ID, "core/visuals/chams");
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final int MAX_CACHED_TEXTURES = 192;

    private static final int XRAY_ALPHA = 0;
    private static final int XRAY_ADD = 1;
    private static final int DEPTH_ALPHA = 2;
    private static final int DEPTH_ADD = 3;

    private static final RenderPipeline[] PIPELINES = {
            pipeline("xray_alpha", DepthTestFunction.NO_DEPTH_TEST, BlendFunction.TRANSLUCENT),
            pipeline("xray_add", DepthTestFunction.NO_DEPTH_TEST, BlendFunction.LIGHTNING),
            pipeline("depth_alpha", DepthTestFunction.LEQUAL_DEPTH_TEST, BlendFunction.TRANSLUCENT),
            pipeline("depth_add", DepthTestFunction.LEQUAL_DEPTH_TEST, BlendFunction.LIGHTNING)
    };

    @SuppressWarnings("unchecked")
    private static final Map<Identifier, RenderLayer>[] LAYERS = new Map[]{
            new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()
    };

    private ChamsRenderer() {
    }

    private static RenderPipeline pipeline(String name, DepthTestFunction depth, BlendFunction blend) {
        return interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(RenderPipeline.builder(
                        RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET,
                        RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.of(Apexx.MOD_ID, "chams_" + name))
                .withVertexShader(ENTITY_VERTEX)
                .withFragmentShader(CHAMS_FRAGMENT)
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withSampler("Sampler0")
                .withSampler("Sampler1")
                .withSampler("Sampler2")
                .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                .withCull(false)
                .withBlend(blend)
                .withDepthTestFunction(depth)
                .withDepthWrite(false)
                .build());
    }

    private static RenderLayer layer(int variant, Identifier texture) {
        Map<Identifier, RenderLayer> cache = LAYERS[variant];
        RenderLayer layer = cache.get(texture);
        if (layer == null) {
            if (cache.size() >= MAX_CACHED_TEXTURES) cache.clear();
            layer = RenderLayer.of("apexx_chams_" + variant,
                    RenderSetup.builder(PIPELINES[variant])
                            .texture("Sampler0", texture)
                            .useLightmap()
                            .useOverlay()
                            .expectedBufferSize(1536)
                            .translucent()
                            .build());
            cache.put(texture, layer);
        }
        return layer;
    }

    /** Submits the chams passes the provider asks for. Never throws into the renderer. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submit(OrderedRenderCommandQueue queue, EntityModel model, LivingEntityRenderState state,
                              MatrixStack matrices, Identifier texture, EntityChamsProvider provider) {
        if (queue == null || model == null || state == null || matrices == null || texture == null) return;
        // Chams are off when their shader failed to compile on this driver.
        for (RenderPipeline pipeline : PIPELINES) {
            if (!interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(pipeline)) return;
        }
        int occluded = provider.chamsOccludedArgb(state);
        int visible = provider.chamsVisibleArgb(state);
        boolean additive = provider.chamsAdditive();

        int glowLayers = Math.max(0, Math.min(4, provider.chamsGlowLayers()));
        if (glowLayers > 0 && (occluded >>> 24) != 0) {
            float outer = Math.max(1.0f, provider.chamsGlowScale());
            int baseAlpha = occluded >>> 24;
            for (int i = glowLayers; i >= 1; i--) {
                float scale = 1.0f + (outer - 1.0f) * i / glowLayers;
                int alpha = Math.max(1, Math.min(255, Math.round(baseAlpha * 0.22f / i)));
                int argb = (alpha << 24) | (occluded & 0x00FFFFFF);
                matrices.push();
                matrices.scale(scale, scale, scale);
                queue.submitModel(model, state, matrices, layer(XRAY_ADD, texture), FULL_BRIGHT,
                        OverlayTexture.DEFAULT_UV, argb, null, 0, null);
                matrices.pop();
            }
        }
        if ((occluded >>> 24) != 0) {
            queue.submitModel(model, state, matrices, layer(additive ? XRAY_ADD : XRAY_ALPHA, texture), FULL_BRIGHT,
                    OverlayTexture.DEFAULT_UV, occluded, null, 0, null);
        }
        if ((visible >>> 24) != 0) {
            queue.submitModel(model, state, matrices, layer(additive ? DEPTH_ADD : DEPTH_ALPHA, texture), FULL_BRIGHT,
                    OverlayTexture.DEFAULT_UV, visible, null, 0, null);
        }
    }
}
