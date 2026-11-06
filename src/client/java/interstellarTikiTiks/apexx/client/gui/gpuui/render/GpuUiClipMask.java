package interstellarTikiTiks.apexx.client.gui.gpuui.render;

import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuUiEffectPasses;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import com.mojang.blaze3d.textures.GpuTextureView;

/** GPU-composed intersection of every rounded clip active at a display-list position. */
public final class GpuUiClipMask {
    private static final Identifier VERTEX = Identifier.of("dynamic_fps", "core/gpuui_clip_mask");
    private static final Identifier FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_clip_mask");
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .build();
    private static final RenderPipeline PIPELINE = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder()
                    .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_clip_mask"))
                    .withVertexShader(VERTEX)
                    .withFragmentShader(FRAGMENT)
                    .withVertexFormat(FORMAT, VertexFormat.DrawMode.TRIANGLES)
                    .withSampler("Sampler0")
                    .withUniform("UiParameters", UniformType.UNIFORM_BUFFER)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withoutBlend()
                    .build());
    private static final GpuUiClipMask NONE = new GpuUiClipMask(null);

    private final GpuUiEffectPasses.Target target;

    private GpuUiClipMask(GpuUiEffectPasses.Target target) {
        this.target = target;
    }

    /** Triggers static pipeline registration without allocating GPU resources. */
    public static void registerPipelines() {
        // Referencing the pipeline initializes this class before shader resources reload.
        if (PIPELINE == null) throw new IllegalStateException("GPU UI clip pipeline was not registered");
    }

    /**
     * Rounded masks need an offscreen target and the clip-mask shader; when either is unavailable on this
     * platform/driver the renderer uses rectangular scissors only.
     */
    public static boolean available() {
        return interstellarTikiTiks.apexx.client.util.PlatformPreset.roundedClipMasks()
                && interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(PIPELINE);
    }

    public static GpuUiClipMask none() {
        return NONE;
    }

    /** Adds one rounded rectangle to the mask intersection using a GPU render pass. */
    public GpuUiClipMask push(Matrix3x2fc pose, UiDrawCommand.ClipPush clip,
                              int framebufferWidth, int framebufferHeight,
                              int scaledWidth, int scaledHeight) {
        if (pose == null || clip == null) throw new NullPointerException("pose and clip are required");
        if (!available()) throw new IllegalStateException("GPU UI rounded clip pipeline is unavailable");
        if (framebufferWidth <= 0 || framebufferHeight <= 0 || scaledWidth <= 0 || scaledHeight <= 0) {
            throw new IllegalArgumentException("GPU UI clip mask requires a non-empty framebuffer");
        }
        if (!Float.isFinite(clip.x()) || !Float.isFinite(clip.y())
                || !Float.isFinite(clip.width()) || !Float.isFinite(clip.height())
                || !Float.isFinite(clip.radius()) || clip.width() < 0 || clip.height() < 0) {
            throw new IllegalArgumentException("GPU UI clip has invalid bounds");
        }

        Matrix3x2f inverse = new Matrix3x2f(pose);
        float determinant = inverse.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-8f) {
            throw new IllegalArgumentException("GPU UI clip cannot be transformed through a singular pose");
        }
        inverse.invert();

        float radius = Math.max(0.0f, Math.min(clip.radius(), Math.min(clip.width(), clip.height()) * 0.5f));
        float[][] parameters = new float[4][4];
        parameters[0] = new float[]{clip.x(), clip.y(), clip.width(), clip.height()};
        parameters[1] = new float[]{radius, inverse.m00(), inverse.m01(), inverse.m10()};
        parameters[2] = new float[]{inverse.m11(), inverse.m20(), inverse.m21(), scaledWidth};
        parameters[3] = new float[]{scaledHeight, 0.0f, 0.0f, 0.0f};

        GpuUiEffectPasses.Target output = GpuUiEffectPasses.createTarget(framebufferWidth, framebufferHeight);
        if (output == null || output.view() == null) {
            throw new IllegalStateException("Unable to allocate GPU UI rounded clip target");
        }
        var uniforms = GpuUiEffectPasses.writeParameters(framebufferWidth, framebufferHeight,
                0.0f, 0.0f, parameters);
        if (uniforms == null) throw new IllegalStateException("Unable to allocate GPU UI clip parameters");
        GpuTextureView parent = view();
        if (parent == null) throw new IllegalStateException("GPU UI parent clip mask texture is unavailable");
        GpuUiEffectPasses.queueFullscreen(PIPELINE, parent, output, uniforms);
        return new GpuUiClipMask(output);
    }

    /** Preserves Sampler0 and adds this mask as Sampler1 for a UI fragment shader. */
    public TextureSetup withMask(TextureSetup base) {
        TextureSetup source = base == null ? TextureSetup.empty() : base;
        if (source.texure1() != null || source.texure2() != null) {
            throw new IllegalArgumentException("GPU UI mask needs the second texture slot");
        }
        var maskSampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                .get(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        if (maskSampler == null) throw new IllegalStateException("GPU sampler cache is unavailable");
        GpuTextureView maskView = view();
        if (maskView == null) throw new IllegalStateException("GPU UI clip mask texture is unavailable");
        if (source.texure0() == null) {
            GpuTextureView white = GpuUiEffectPasses.whiteTextureView();
            if (white == null) throw new IllegalStateException("GPU UI white texture is unavailable");
            return TextureSetup.of(white, maskSampler, maskView, maskSampler);
        }
        if (source.sampler0() == null) throw new IllegalArgumentException("Sampler0 texture has no sampler");
        return TextureSetup.of(source.texure0(), source.sampler0(), maskView, maskSampler);
    }

    private GpuTextureView view() {
        return target == null ? GpuUiEffectPasses.whiteTextureView() : target.view();
    }
}
