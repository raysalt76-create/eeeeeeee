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

public final class RenderTypes {

    private static final Identifier POSITION_COLOR_SHADER = Identifier.of("minecraft", "core/position_color");

    private static final RenderPipeline POSITION_COLOR_TRIANGLES_SEE_THROUGH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_position_color_triangles_see_through"))
                    .withVertexShader(POSITION_COLOR_SHADER)
                    .withFragmentShader(POSITION_COLOR_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline POSITION_COLOR_TRIANGLES_DEPTH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_position_color_triangles_depth"))
                    .withVertexShader(POSITION_COLOR_SHADER)
                    .withFragmentShader(POSITION_COLOR_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline POSITION_COLOR_LINES_SEE_THROUGH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_position_color_lines_see_through"))
                    .withVertexShader(POSITION_COLOR_SHADER)
                    .withFragmentShader(POSITION_COLOR_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.LINES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private static final RenderPipeline POSITION_COLOR_LINES_DEPTH = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_position_color_lines_depth"))
                    .withVertexShader(POSITION_COLOR_SHADER)
                    .withFragmentShader(POSITION_COLOR_SHADER)
                    .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.LINES)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    public static final RenderLayer TRIANGLES = RenderLayer.of(
            "apexx_triangles",
            RenderSetup.builder(POSITION_COLOR_TRIANGLES_DEPTH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    public static final RenderLayer TRIANGLES_NO_DEPTH = RenderLayer.of(
            "apexx_triangles_no_depth",
            RenderSetup.builder(POSITION_COLOR_TRIANGLES_SEE_THROUGH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    public static final RenderLayer LINES = RenderLayer.of(
            "apexx_lines",
            RenderSetup.builder(POSITION_COLOR_LINES_DEPTH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    public static final RenderLayer LINES_NO_DEPTH = RenderLayer.of(
            "apexx_lines_no_depth",
            RenderSetup.builder(POSITION_COLOR_LINES_SEE_THROUGH)
                    .expectedBufferSize(1536)
                    .translucent()
                    .build()
    );

    private RenderTypes() {}

    public static RenderLayer getLinesWithWidth(double width) {

        return LINES;
    }

    public static void setupNoDepth() {

    }

    public static void cleanupNoDepth() {

    }
}
