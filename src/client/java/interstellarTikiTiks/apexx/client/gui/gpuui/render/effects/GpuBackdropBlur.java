package interstellarTikiTiks.apexx.client.gui.gpuui.render.effects;

import interstellarTikiTiks.apexx.client.util.render.SafePipelines;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiClipMask;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fc;

import java.util.HashMap;
import java.util.Map;

/** GPU-only scene capture, dual-filter Kawase blur, and thresholded bloom for the custom GUI. */
public final class GpuBackdropBlur {
    private static final Identifier VERTEX = Identifier.of("dynamic_fps", "core/gpuui_backdrop");
    private static final Identifier FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_backdrop");
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build();
    private static final RenderPipeline BLUR_PIPELINE = register(RenderPipeline.builder(net.minecraft.client.gl.RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_backdrop"))
            .withVertexShader(VERTEX)
            .withFragmentShader(FRAGMENT)
            .withVertexFormat(FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline BLOOM_PIPELINE = register(RenderPipeline.builder(net.minecraft.client.gl.RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_bloom"))
            .withVertexShader(VERTEX)
            .withFragmentShader(FRAGMENT)
            .withVertexFormat(FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.ADDITIVE)
            .build());
    private static final Identifier LIQUID = Identifier.of("dynamic_fps", "core/gpuui_liquid");
    private static final RenderPipeline GLASS_PIPELINE = register(RenderPipeline.builder(net.minecraft.client.gl.RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_liquid"))
            .withVertexShader(LIQUID)
            .withFragmentShader(LIQUID)
            .withVertexFormat(FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline KAWASE_DOWN = GpuUiEffectPasses.materialPipeline(
            Identifier.of("dynamic_fps", "core/gpuui_effect_kawase_down"));
    private static final RenderPipeline KAWASE_UP = GpuUiEffectPasses.materialPipeline(
            Identifier.of("dynamic_fps", "core/gpuui_effect_kawase_up"));
    private static final RenderPipeline BLOOM_EXTRACT = GpuUiEffectPasses.materialPipeline(
            Identifier.of("dynamic_fps", "core/gpuui_effect_bloom_extract"));
    private static final int MAX_LEVELS = 6;

    private static final Map<Integer, GpuUiEffectPasses.Target> frameCache = new HashMap<>();

    private GpuBackdropBlur() { }

    /** Call during client bootstrap so fixed GUI pipelines enter the shader reload/precompile set. */
    public static void registerPipelines() {
        // Static initialization above registers the deferred GUI and fullscreen pass pipelines.
    }

    /** Legacy convenience overload retained for existing screens. */
    public static void addBlur(GuiRenderState state, Matrix3x2fc pose, float x, float y,
                               float width, float height, float cornerRadius, float strength,
                               ScreenRect clip) {
        addEffect(state, pose, x, y, width, height, cornerRadius,
                UiDrawCommand.EffectKind.BLUR, strength, clip, GpuUiClipMask.none());
    }

    /** Adds a dual-Kawase backdrop blur or bright-pass bloom quad in GUI painter order. */
    public static void addEffect(GuiRenderState state, Matrix3x2fc pose, float x, float y,
                                 float width, float height, float cornerRadius,
                                 UiDrawCommand.EffectKind kind, float strength,
                                 ScreenRect clip, GpuUiClipMask clipMask) {
        addEffect(state, pose, x, y, width, height, cornerRadius, kind, strength, 0xFFFFFFFF, clip, clipMask);
    }

    public static void addEffect(GuiRenderState state, Matrix3x2fc pose, float x, float y,
                                 float width, float height, float cornerRadius,
                                 UiDrawCommand.EffectKind kind, float strength, int tintArgb,
                                 ScreenRect clip, GpuUiClipMask clipMask) {
        if (!GpuUiEffectPasses.isAvailable() || state == null || !(width > 0) || !(height > 0)
                || !Float.isFinite(strength) || strength <= 0) return;
        // Backdrop effects sample the game image. In SS Proof the interface is drawn into a transparent
        // overlay framebuffer, where a game-sampling quad would smear the world across the overlay.
        if (interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isOverlayCaptureActive()) return;
        RenderSystem.assertOnRenderThread();
        if (!interstellarTikiTiks.apexx.client.util.PlatformPreset.offscreenEffects()) return;
        boolean bloom = kind == UiDrawCommand.EffectKind.BLOOM;
        // Effect off (no blur/bloom/glass) when any of its shaders failed to compile on this driver.
        RenderPipeline composite = kind == UiDrawCommand.EffectKind.GLASS ? GLASS_PIPELINE
                : bloom ? BLOOM_PIPELINE : BLUR_PIPELINE;
        if (!SafePipelines.usable(composite) || !SafePipelines.usable(KAWASE_DOWN)
                || !SafePipelines.usable(KAWASE_UP) || (bloom && !SafePipelines.usable(BLOOM_EXTRACT))) return;
        try {
            GpuUiEffectPasses.Target scene = GpuUiEffectPasses.requestSceneTexture();
            if (scene == null) return;

            int passKey = (bloom ? 1 << 30 : 0) | Math.round(Math.min(64, strength) * 4.0f);
            GpuUiEffectPasses.Target blurred = frameCache.get(passKey);
            if (blurred == null || blurred.closed()) {
                blurred = queueDualKawase(scene, bloom, strength);
                if (blurred == null) return;
                frameCache.put(passKey, blurred);
            }

            ScreenRect bounds = rectangle(x, y, width, height).transform(pose);
            ScreenRect scissor = clip == null ? null : clip.intersection(bounds);
            int alpha = bloom ? Math.round(clamp(strength / 16.0f, 0.08f, 0.9f) * 255.0f) : 255;
            boolean glass = kind == UiDrawCommand.EffectKind.GLASS;
            state.addSimpleElement(new EffectState(
                    composite,
                    (clipMask == null ? GpuUiClipMask.none() : clipMask).withMask(GpuUiEffectPasses.texture(blurred)),
                    pose, x, y, width, height,
                    clamp(cornerRadius, 0, Math.min(width, height) * 0.5f),
                    bloom ? alpha << 24 | 0x00FFFFFF : glass ? tintArgb : 0xFFFFFFFF,
                    bounds, scissor));
        } catch (RuntimeException | LinkageError failure) {
            GpuUiEffectPasses.disableAfterFailure(failure);
        }
    }

    /**
     * Dual-filter Kawase blur: a chain of half-resolution downsamples followed by tent upsamples
     * back to scene resolution. Each level doubles the kernel footprint, so large radii stay smooth
     * instead of showing the ghosted copies a sparse single-level kernel produces.
     */
    private static GpuUiEffectPasses.Target queueDualKawase(GpuUiEffectPasses.Target scene,
                                                            boolean bloom, float strength) {
        // Strength 0..30 maps to 1..5 levels; the offset widens each level until the next one starts.
        float clamped = clamp(strength, 0.0f, 30.0f);
        int levels = 1 + (int) (clamped / 7.5f);
        float offset = 1.0f + (clamped % 7.5f) / 7.5f * 1.25f;
        // Keep the blur visually consistent on very high-resolution framebuffers.
        if (scene.height() > 1600) levels++;
        levels = Math.max(1, Math.min(MAX_LEVELS, levels));

        GpuUiEffectPasses.Target[] chain = new GpuUiEffectPasses.Target[levels + 1];
        chain[0] = scene;
        int width = scene.width(), height = scene.height();
        int built = 0;
        for (int level = 1; level <= levels; level++) {
            if (width < 4 || height < 4) break;
            width = Math.max(1, (width + 1) / 2);
            height = Math.max(1, (height + 1) / 2);
            GpuUiEffectPasses.Target target = GpuUiEffectPasses.createTarget(width, height);
            if (target == null) return null;
            boolean extract = bloom && level == 1;
            GpuUiEffectPasses.queueFullscreen(extract ? BLOOM_EXTRACT : KAWASE_DOWN,
                    chain[level - 1].view(), target,
                    GpuUiEffectPasses.writeParameters(width, height, 0, 0,
                            new float[][] { { offset, 0.68f, 0.0f, 0.0f } }));
            chain[level] = target;
            built = level;
        }
        if (built == 0) return null;

        GpuUiEffectPasses.Target current = chain[built];
        for (int level = built - 1; level >= 0; level--) {
            int outWidth = chain[level].width(), outHeight = chain[level].height();
            GpuUiEffectPasses.Target target = GpuUiEffectPasses.createTarget(outWidth, outHeight);
            if (target == null) return null;
            GpuUiEffectPasses.queueFullscreen(KAWASE_UP, current.view(), target,
                    GpuUiEffectPasses.writeParameters(outWidth, outHeight, 0, 0,
                            new float[][] { { offset, 0.0f, 0.0f, 0.0f } }));
            current = target;
        }
        return current;
    }

    /** Called before deferred GUI draws to snapshot the scene and run queued offscreen passes. */
    public static void captureScene() {
        GpuUiEffectPasses.captureAndRunQueuedPasses();
        // The Water dye simulation steps here too (own GL state bracket), before the GUI samples its output.
        GpuFluidSimulation.runPending();
    }

    /** Called after deferred GUI draws so frame targets become available for the next extraction. */
    public static void finishFrame() {
        frameCache.clear();
        GpuUiEffectPasses.finishFrame();
        GpuFluidSimulation.finishFrame();
    }

    private static RenderPipeline register(RenderPipeline pipeline) {
        return SafePipelines.register(pipeline);
    }

    private static ScreenRect rectangle(float x, float y, float width, float height) {
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        int right = (int) Math.ceil(x + width);
        int bottom = (int) Math.ceil(y + height);
        return new ScreenRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private record EffectState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose,
                               float x, float y, float width, float height,
                               float cornerRadius, int argb, ScreenRect bounds, ScreenRect scissorArea)
            implements SimpleGuiElementRenderState {
        @Override public void setupVertices(VertexConsumer vertices) {
            int rw = Math.min(65535, Math.max(1, Math.round(width)));
            int rh = Math.min(65535, Math.max(1, Math.round(height)));
            int packedDimensions = (rh << 16) | rw;
            vertex(vertices, x, y, 0, 0, packedDimensions);
            vertex(vertices, x + width, y, width, 0, packedDimensions);
            vertex(vertices, x + width, y + height, width, height, packedDimensions);
            vertex(vertices, x, y + height, 0, height, packedDimensions);
        }

        private void vertex(VertexConsumer vertices, float px, float py, float localX, float localY,
                            int dimensions) {
            vertices.vertex(pose, px, py).color(argb).texture(localX, localY)
                    .overlay(dimensions).lineWidth(cornerRadius);
        }

        @Override public RenderPipeline pipeline() { return pipeline; }
    }
}
