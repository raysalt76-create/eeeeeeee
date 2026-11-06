package interstellarTikiTiks.apexx.client.util.render.types;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

public final class ShaderRenderLayers {

    private static final Identifier POSITION_COLOR_SHADER = Identifier.of("minecraft", "core/position_color");
    // Use vanilla shader instead of custom esp_surface (not available without resource pack)
    private static final Identifier ESP_SURFACE_SHADER = POSITION_COLOR_SHADER;

    private static final RenderPipeline POSITION_COLOR_TRIANGLES_GLOW = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_position_color_triangles_glow"))
                    .withVertexShader(POSITION_COLOR_SHADER)
                    .withFragmentShader(POSITION_COLOR_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.LIGHTNING)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderLayer GLOW_LAYER_NO_DEPTH = RenderLayer.of(
            "apexx_shader_glow_nd",
            RenderSetup.builder(POSITION_COLOR_TRIANGLES_GLOW)
                    .expectedBufferSize(256)
                    .translucent()
                    .build()
    );

    private static final RenderPipeline ESP_TRIANGLES_DEPTH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_esp_triangles_depth"))
                    .withVertexShader(ESP_SURFACE_SHADER)
                    .withFragmentShader(ESP_SURFACE_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline ESP_TRIANGLES_NO_DEPTH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_esp_triangles_no_depth"))
                    .withVertexShader(ESP_SURFACE_SHADER)
                    .withFragmentShader(ESP_SURFACE_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderLayer ESP_LAYER_DEPTH = RenderLayer.of(
            "apexx_esp_depth",
            RenderSetup.builder(ESP_TRIANGLES_DEPTH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    private static final RenderLayer ESP_LAYER_NO_DEPTH = RenderLayer.of(
            "apexx_esp_no_depth",
            RenderSetup.builder(ESP_TRIANGLES_NO_DEPTH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    private ShaderRenderLayers() {}

    public static RenderLayer getShaderLayerNoDepth(String shaderName, boolean isGlow) {

        return isGlow ? GLOW_LAYER_NO_DEPTH : RenderTypes.TRIANGLES_NO_DEPTH;
    }

    public static RenderLayer getShaderLayer(String shaderName, boolean isGlow) {

        return isGlow ? GLOW_LAYER_NO_DEPTH : RenderTypes.TRIANGLES;
    }

    public static RenderLayer getShaderEntityLayer(String shaderName, Identifier texture, boolean isGlow) {

        return isGlow ? RenderLayers.entityTranslucentEmissive(texture) : RenderLayers.entityTranslucent(texture);
    }

    public static RenderLayer getEspLayer(boolean seeThrough) {
        if (RenderBackendDetector.isVulkanBackend()) {
            return seeThrough ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES;
        }
        return seeThrough ? ESP_LAYER_NO_DEPTH : ESP_LAYER_DEPTH;
    }

    /** Line-mode counterpart of {@link #getEspLayer} — for wireframe box ESP outlines. */
    public static RenderLayer getEspLineLayer(boolean seeThrough) {
        return seeThrough ? RenderTypes.LINES_NO_DEPTH : RenderTypes.LINES;
    }
}
