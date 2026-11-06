package interstellarTikiTiks.apexx.client.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class CoreShaderManager {
    private static final Identifier MSDF_TEXT_PIPELINE_ID = Identifier.of("dynamic_fps", "msdf_text");
    private static final Identifier IMAGE_PIPELINE_ID = Identifier.of("dynamic_fps", "image");
    private static final Set<Identifier> VULKAN_PIPELINES = Set.of(
            MSDF_TEXT_PIPELINE_ID,
            IMAGE_PIPELINE_ID,
            Identifier.of("dynamic_fps", "gui_panel"),
            Identifier.of("dynamic_fps", "gui_panel_glass"),
            Identifier.of("dynamic_fps", "gui_header"),
            Identifier.of("dynamic_fps", "gui_header_glass"),
            Identifier.of("dynamic_fps", "gui_row"),
            Identifier.of("dynamic_fps", "gui_row_active"),
            Identifier.of("dynamic_fps", "gui_row_enabled"),
            Identifier.of("dynamic_fps", "gui_row_last"),
            Identifier.of("dynamic_fps", "gui_search"),
            Identifier.of("dynamic_fps", "gui_control"),
            Identifier.of("dynamic_fps", "gui_icon"),
            Identifier.of("dynamic_fps", "gui_tooltip"),
            Identifier.of("dynamic_fps", "gui_hud"),
            Identifier.of("dynamic_fps", "gui_glow"),
            Identifier.of("dynamic_fps", "gui_particle"),
            Identifier.of("dynamic_fps", "gui_particle_glow"));

    private static CoreShaderManager INSTANCE;

    private final Map<Identifier, RenderPipeline> customPipelines = new HashMap<>();

    private final Map<Identifier, ShaderProgram> shaderPrograms = new HashMap<>();

    private CoreShaderManager() {}

    public static CoreShaderManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CoreShaderManager();
        }
        return INSTANCE;
    }

    private static boolean supportsPipeline(Identifier id) {
        if (!RenderBackendDetector.isVulkanBackend()) {
            return true;
        }

        return VULKAN_PIPELINES.contains(id);
    }

    public void registerPositionColorShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);

    }

    public void registerPositionTextureColorShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);

    }

    public void registerPositionShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);

    }

    public void registerCurveShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);
    }

    public void registerMsdfTextShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);
    }
    public void registerImageShader(Identifier id) {
        if (!supportsPipeline(id)) {
            return;
        }

        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.of(id.getNamespace(), "pipeline/" + id.getPath()))
                .withVertexShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withFragmentShader(Identifier.of(id.getNamespace(), "core/" + id.getPath()))
                .withUniform("DynamicTransforms", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", net.minecraft.client.gl.UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                .withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
                .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                .withDepthWrite(false)
                .build();

        interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
        customPipelines.put(id, pipeline);
    }
    public void setUniformFloat(Identifier id, String name, float value) {
        RenderPipeline pipeline = customPipelines.get(id);
        if (pipeline == null) return;
        RenderSystem.assertOnRenderThread();
    }
    public void setShaderUniforms(Identifier id, float time, float[] color, float speed) {
        ShaderProgram program = shaderPrograms.get(id);
        if (program == null) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        var timeUniform = program.getUniform("Time");
        if (timeUniform != null) {

        }

        var colorUniform = program.getUniform("CustomColor");
        if (colorUniform != null) {

        }

        var speedUniform = program.getUniform("Speed");
        if (speedUniform != null) {

        }
    }

    public RenderPipeline getPipeline(Identifier id) {
        if (!supportsPipeline(id)) {
            return null;
        }
        RenderPipeline pipeline = customPipelines.get(id);
        // A pipeline that failed to compile on this driver reads as absent; callers fall back.
        return interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(pipeline) ? pipeline : null;
    }

    public ShaderProgram getShaderProgram(Identifier id) {
        return shaderPrograms.get(id);
    }

    public void clear() {
        customPipelines.clear();
        shaderPrograms.clear();

    }
}
