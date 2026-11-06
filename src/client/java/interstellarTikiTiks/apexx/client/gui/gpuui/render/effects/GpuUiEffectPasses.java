package interstellarTikiTiks.apexx.client.gui.gpuui.render.effects;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import interstellarTikiTiks.apexx.client.util.render.SafePipelines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Small RenderPipeline-based offscreen pass runner for the custom UI.
 * All queued passes execute on Minecraft's render thread before deferred GUI draws.
 */
public final class GpuUiEffectPasses {
    private static final Logger LOGGER = LoggerFactory.getLogger("ApexxClientGpuUi");
    private static final Identifier MATERIAL_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_material");
    private static final int PARAMETER_VEC4S = 16;
    private static final int PARAMETER_BYTES = PARAMETER_VEC4S * 4 * Float.BYTES;
    private static final int UNIFORM_PAGE_BYTES = 64 * 1024;
    /** Mobile GPUs / small heaps get a smaller target pool (each target can be a full-screen RGBA8 texture). */
    private static final int MAX_POOLED_TARGETS =
            interstellarTikiTiks.apexx.client.util.MobilePlatform.isMobile()
                    || Runtime.getRuntime().maxMemory() < 1536L * 1024 * 1024 ? 16 : 48; // one blur chain alone needs 12 leased targets
    private static final int TARGET_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final VertexFormat FULLSCREEN_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .build();

    private static final List<Target> pooledTargets = new ArrayList<>();
    private static final ByteBuffer PARAMETER_SCRATCH =
            ByteBuffer.allocateDirect(PARAMETER_BYTES).order(ByteOrder.nativeOrder());
    private static final List<UniformPage> uniformPages = new ArrayList<>();
    private static final List<QueuedPass> queuedPasses = new ArrayList<>();
    private static final Map<Identifier, RenderPipeline> materialPipelines = new HashMap<>();
    /** Procedural materials (no input texture), built without Sampler0 -- see {@link #materialPipeline(Identifier, boolean)}. */
    private static final Map<Identifier, RenderPipeline> proceduralPipelines = new HashMap<>();
    /** Two-input materials (Sampler0 + Sampler1) -- see {@link #materialPipelineDual(Identifier)}. */
    private static final Map<Identifier, RenderPipeline> dualMaterialPipelines = new HashMap<>();
    private static final Set<RenderPipeline> dualInputPipelines = new HashSet<>();
    private static final java.util.Set<RenderPipeline> pipelinesWithoutInput = new HashSet<>();
    private static final Set<FailedTarget> failedTargetAllocations = new HashSet<>();

    private static GpuBuffer fullscreenVertices;
    private static Target sceneTarget;
    private static Target whiteTarget;
    private static boolean sceneCaptureRequested;
    private static int uniformSlot;
    private static int lastSurfaceWidth = -1;
    private static int lastSurfaceHeight = -1;
    private static long surfaceGeneration;
    private static boolean warnedPassFailure;
    private static boolean effectsUnavailable;

    static {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSafely());
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Identifier.of("dynamic_fps", "gpuui_effect_passes"),
                (SynchronousResourceReloader) manager -> releaseSafely());
    }

    private GpuUiEffectPasses() { }

    /** Installs cache lifecycle hooks without requiring an initialized render device. */
    public static void registerPipelines() {
        // Class initialization installs lifecycle hooks. GPU passes initialize on the render thread.
    }

    /**
     * Returns a reusable RGBA8 target leased for this frame. The lease is recycled after GuiRenderer.render.
     */
    public static Target createTarget(int width, int height) {
        if (effectsUnavailable || width <= 0 || height <= 0) return null;
        RenderSystem.assertOnRenderThread();
        ensureDevice();
        trackCurrentSurfaceSize();
        for (Target target : pooledTargets) {
            if (!target.leased && target.width == width && target.height == height
                    && target.format == TextureFormat.RGBA8 && target.generation == surfaceGeneration
                    && !target.closed()) {
                target.leased = true;
                return target;
            }
        }
        Target target = allocateTarget(width, height, TextureFormat.RGBA8, TARGET_USAGE,
                "Apexx UI pass " + width + "x" + height, surfaceGeneration);
        if (target == null) return null;
        target.leased = true;
        pooledTargets.add(target);
        return target;
    }

    /**
     * Like {@link #createTarget} but rounds the size up to a 64 px bucket so an animated panel size reuses pooled
     * targets instead of allocating a new texture every frame. The pass fills the whole target, so callers sample
     * it over their own rectangle exactly as before.
     */
    public static Target createBucketedTarget(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        return createTarget(((width + 63) / 64) * 64, ((height + 63) / 64) * 64);
    }

    /** Frees every pooled target that is not leased (menu closed); they are re-created on demand. */
    public static void trimPool() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.queueFencedTask(GpuUiEffectPasses::trimPool);
            return;
        }
        pooledTargets.removeIf(target -> {
            if (target.leased) return false;
            closeTarget(target);
            return true;
        });
    }

    /** Stable full-resolution copy of the main scene, refreshed at GuiRenderer HEAD this frame. */
    public static Target requestSceneTexture() {
        RenderSystem.assertOnRenderThread();
        if (blocked()) return null;
        ensureDevice();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getFramebuffer() == null
                || client.getFramebuffer().getColorAttachment() == null) return null;
        GpuTexture source = client.getFramebuffer().getColorAttachment();
        int width = source.getWidth(0), height = source.getHeight(0);
        if (width <= 0 || height <= 0) return null;
        trackSurfaceSize(width, height);
        if (sceneTarget == null || sceneTarget.width != width || sceneTarget.height != height
                || sceneTarget.format != source.getFormat() || sceneTarget.closed()) {
            closeTarget(sceneTarget);
            sceneTarget = allocateTarget(width, height, source.getFormat(),
                    TARGET_USAGE | GpuTexture.USAGE_COPY_DST, "Apexx UI scene capture", surfaceGeneration);
        }
        if (sceneTarget != null) sceneCaptureRequested = true;
        return sceneTarget;
    }

    /** Lazily-created 1x1 white texture, useful as a procedural pass input or unclipped mask. */
    public static GpuTextureView whiteTextureView() {
        RenderSystem.assertOnRenderThread();
        ensureDevice();
        if (whiteTarget == null || whiteTarget.closed()) {
            whiteTarget = allocateTarget(1, 1, TextureFormat.RGBA8,
                    TARGET_USAGE | GpuTexture.USAGE_COPY_DST, "Apexx UI white fallback", surfaceGeneration);
            if (whiteTarget != null) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(whiteTarget.texture, 0xFFFFFFFF);
            }
        }
        return whiteTarget == null ? null : whiteTarget.view;
    }

    /**
     * Builds one 256-byte std140 block: slot 0 is [width,height,time,delta], slots 1..15 are custom vec4s.
     * The returned slice stays valid until the current GUI render has completed.
     */
    public static GpuBufferSlice writeParameters(float widthPx, float heightPx,
                                                  float timeSeconds, float deltaSeconds,
                                                  float[][] vec4Params) {
        RenderSystem.assertOnRenderThread();
        if (blocked()) return null;
        ensureDevice();
        // Render thread only, and writeToBuffer copies immediately, so one buffer can be reused
        // instead of allocating native memory on every parameter write.
        ByteBuffer data = PARAMETER_SCRATCH;
        data.clear();
        data.putFloat(finiteOrZero(widthPx)).putFloat(finiteOrZero(heightPx))
                .putFloat(finiteOrZero(timeSeconds)).putFloat(finiteOrZero(deltaSeconds));
        int supplied = vec4Params == null ? 0 : Math.min(PARAMETER_VEC4S - 1, vec4Params.length);
        for (int i = 0; i < PARAMETER_VEC4S - 1; i++) {
            float[] vec4 = i < supplied ? vec4Params[i] : null;
            for (int component = 0; component < 4; component++) {
                float value = vec4 != null && component < vec4.length ? vec4[component] : 0.0f;
                data.putFloat(finiteOrZero(value));
            }
        }
        data.flip();

        int alignment = Math.max(1, RenderSystem.getDevice().getUniformOffsetAlignment());
        int stride = align(PARAMETER_BYTES, alignment);
        int byteOffset = uniformSlot * stride;
        int pageIndex = byteOffset / UNIFORM_PAGE_BYTES;
        int pageOffset = byteOffset % UNIFORM_PAGE_BYTES;
        while (uniformPages.size() <= pageIndex) {
            GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                    () -> "Apexx UI parameter page " + uniformPages.size(),
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_PAGE_BYTES);
            uniformPages.add(new UniformPage(buffer));
        }
        uniformSlot++;
        UniformPage page = uniformPages.get(pageIndex);
        GpuBufferSlice slice = page.buffer.slice(pageOffset, PARAMETER_BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, data);
        return slice;
    }

    /** Cached pipeline factory for fullscreen fragment materials using gpuui_material.vsh. */
    public static RenderPipeline materialPipeline(Identifier fragmentShader) {
        return materialPipeline(fragmentShader, true);
    }

    /**
     * As {@link #materialPipeline(Identifier)}; {@code samplesInput} false builds the pipeline without a
     * Sampler0 for procedural shaders (granite, metal, portoro, fog...) that never read one. Declaring
     * it anyway made Minecraft log "shader program does not use sampler Sampler0" for each of them.
     */
    public static RenderPipeline materialPipeline(Identifier fragmentShader, boolean samplesInput) {
        if (fragmentShader == null) throw new IllegalArgumentException("fragmentShader cannot be null");
        Map<Identifier, RenderPipeline> cache = samplesInput ? materialPipelines : proceduralPipelines;
        return cache.computeIfAbsent(fragmentShader, shader -> {
            String path = shader.getNamespace() + "/" + shader.getPath();
            String safePath = path.replaceAll("[^a-zA-Z0-9_./-]", "_");
            Identifier location = Identifier.of("dynamic_fps",
                    (samplesInput ? "pipeline/gpuui_material/" : "pipeline/gpuui_material_procedural/") + safePath);
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(location)
                    .withVertexShader(MATERIAL_VERTEX)
                    .withFragmentShader(shader)
                    .withVertexFormat(FULLSCREEN_FORMAT, VertexFormat.DrawMode.TRIANGLES);
            if (samplesInput) builder = builder.withSampler("Sampler0");
            RenderPipeline pipeline = builder
                    .withUniform("UiParameters", UniformType.UNIFORM_BUFFER)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withoutBlend()
                    .build();
            RenderPipeline registered = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
            if (!samplesInput) pipelinesWithoutInput.add(registered);
            return registered;
        });
    }

    /**
     * As {@link #materialPipeline(Identifier)} but declares a second input (Sampler0 + Sampler1) for
     * fullscreen passes that read two fields at once (e.g. a fluid pressure solve sampling pressure
     * and divergence). Pass the inputs through {@link #queueFullscreen(RenderPipeline, GpuTextureView,
     * GpuTextureView, Target, GpuBufferSlice)}.
     */
    public static RenderPipeline materialPipelineDual(Identifier fragmentShader) {
        if (fragmentShader == null) throw new IllegalArgumentException("fragmentShader cannot be null");
        return dualMaterialPipelines.computeIfAbsent(fragmentShader, shader -> {
            String path = shader.getNamespace() + "/" + shader.getPath();
            String safePath = path.replaceAll("[^a-zA-Z0-9_./-]", "_");
            Identifier location = Identifier.of("dynamic_fps", "pipeline/gpuui_material_dual/" + safePath);
            RenderPipeline pipeline = RenderPipeline.builder()
                    .withLocation(location)
                    .withVertexShader(MATERIAL_VERTEX)
                    .withFragmentShader(shader)
                    .withVertexFormat(FULLSCREEN_FORMAT, VertexFormat.DrawMode.TRIANGLES)
                    .withSampler("Sampler0")
                    .withSampler("Sampler1")
                    .withUniform("UiParameters", UniformType.UNIFORM_BUFFER)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withoutBlend()
                    .build();
            RenderPipeline registered = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
            dualInputPipelines.add(registered);
            return registered;
        });
    }

    /**
     * Allocates a render target the caller owns for the lifetime of a persistent effect (e.g. a fluid
     * simulation's ping-pong field buffers), separate from the per-frame pool that {@link #createTarget}
     * leases. The caller must close it with {@link #releasePersistentTarget(Target)} and reallocate when
     * {@link Target#closed()} becomes true after a resource reload or device loss. Only RGBA8 is
     * supported by the current Blaze3D {@code TextureFormat} set (no float/half-float attachment).
     */
    public static Target allocatePersistentTarget(int width, int height) {
        if (blocked() || width <= 0 || height <= 0) return null;
        RenderSystem.assertOnRenderThread();
        if (RenderSystem.tryGetDevice() == null) return null;
        trackCurrentSurfaceSize();
        return allocateTarget(width, height, TextureFormat.RGBA8, TARGET_USAGE | GpuTexture.USAGE_COPY_DST,
                "Apexx UI persistent " + width + "x" + height, surfaceGeneration);
    }

    /** Closes a target obtained from {@link #allocatePersistentTarget(int, int)}. Safe on null. */
    public static void releasePersistentTarget(Target target) {
        closeTarget(target);
    }

    /** Clears a target to a packed ARGB value (render thread only). Safe on null/closed targets. */
    public static void clearTarget(Target target, int argb) {
        if (target == null || target.closed() || RenderSystem.tryGetDevice() == null) return;
        RenderSystem.assertOnRenderThread();
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.texture, argb);
    }

    /** Wraps an output target with the standard linear sampler for deferred GUI rendering. */
    public static TextureSetup texture(Target target) {
        if (target == null || target.closed()) return TextureSetup.empty();
        return TextureSetup.of(target.view, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
    }

    /** Queues a full-screen pass. A null input binds the current scene snapshot. */
    public static void queueFullscreen(RenderPipeline pipeline, GpuTextureView input,
                                       Target output, GpuBufferSlice uniforms) {
        if (effectsUnavailable || pipeline == null || output == null || output.closed() || uniforms == null) return;
        RenderSystem.assertOnRenderThread();
        if (!SafePipelines.usable(pipeline)) return;
        if (input == null) {
            Target scene = requestSceneTexture();
            input = scene == null ? whiteTextureView() : scene.view;
        }
        if (input == null) return;
        if (input.texture() == output.texture) {
            throw new IllegalArgumentException("A UI render pass cannot sample its output attachment");
        }
        queuedPasses.add(new QueuedPass(pipeline, input, null, output, uniforms));
    }

    /**
     * Queues a two-input full-screen pass. Both inputs bind (Sampler0, Sampler1); neither may be the
     * output attachment. Use with a pipeline from {@link #materialPipelineDual(Identifier)}.
     */
    public static void queueFullscreen(RenderPipeline pipeline, GpuTextureView input0, GpuTextureView input1,
                                       Target output, GpuBufferSlice uniforms) {
        if (blocked() || pipeline == null || output == null || output.closed() || uniforms == null) return;
        if (input0 == null || input1 == null) return;
        RenderSystem.assertOnRenderThread();
        if (!SafePipelines.usable(pipeline)) return;
        if (input0.texture() == output.texture || input1.texture() == output.texture) {
            throw new IllegalArgumentException("A UI render pass cannot sample its output attachment");
        }
        queuedPasses.add(new QueuedPass(pipeline, input0, input1, output, uniforms));
    }

    /** Called by the GuiRenderer HEAD mixin; copies the current scene, then executes queued GPU passes in order. */
    public static void captureAndRunQueuedPasses() {
        RenderSystem.assertOnRenderThread();
        if (effectsUnavailable) {
            queuedPasses.clear();
            sceneCaptureRequested = false;
            return;
        }
        if (!sceneCaptureRequested && queuedPasses.isEmpty()) return;
        try {
            if (sceneCaptureRequested) copySceneToSnapshot();
            if (!queuedPasses.isEmpty()) ensureFullscreenBuffer();
        } catch (RuntimeException | LinkageError failure) {
            warnPassFailure(failure);
            effectsUnavailable = true;
            queuedPasses.clear();
            sceneCaptureRequested = false;
            return;
        }
        for (QueuedPass queued : queuedPasses) {
            try {
                if (queued.output.closed() || queued.input.isClosed()
                        || (queued.input2 != null && queued.input2.isClosed())) continue;
                runPass(queued);
            } catch (RuntimeException | LinkageError failure) {
                warnPassFailure(failure);
            }
        }
        queuedPasses.clear();
        sceneCaptureRequested = false;
    }

    private static void warnPassFailure(Throwable failure) {
        if (warnedPassFailure) return;
        warnedPassFailure = true;
        LOGGER.warn("Apexx optional GPU UI effect pass failed; custom effects are paused until resources reload", failure);
    }

    static boolean isAvailable() {
        return !effectsUnavailable;
    }

    private static boolean blocked() {
        return effectsUnavailable || !interstellarTikiTiks.apexx.client.util.PlatformPreset.offscreenEffects();
    }

    static void disableAfterFailure(Throwable failure) {
        RenderSystem.assertOnRenderThread();
        warnPassFailure(failure);
        effectsUnavailable = true;
        queuedPasses.clear();
        sceneCaptureRequested = false;
    }

    /** Called at GuiRenderer RETURN after deferred states have consumed target textures. */
    public static void finishFrame() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.queueFencedTask(GpuUiEffectPasses::finishFrame);
            return;
        }
        for (Target target : pooledTargets) target.leased = false;
        pruneStaleAndExcessTargets();
        uniformSlot = 0;
    }

    /** Closes pooled textures, uniform buffers, and the shared fullscreen quad. */
    public static void release() {
        RenderSystem.assertOnRenderThread();
        queuedPasses.clear();
        for (Target target : pooledTargets) closeTarget(target);
        pooledTargets.clear();
        closeTarget(sceneTarget);
        sceneTarget = null;
        closeTarget(whiteTarget);
        whiteTarget = null;
        for (UniformPage page : uniformPages) {
            try { page.buffer.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
        uniformPages.clear();
        uniformSlot = 0;
        lastSurfaceWidth = -1;
        lastSurfaceHeight = -1;
        surfaceGeneration++;
        failedTargetAllocations.clear();
        if (fullscreenVertices != null) {
            try { fullscreenVertices.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
        fullscreenVertices = null;
        sceneCaptureRequested = false;
        warnedPassFailure = false;
        effectsUnavailable = false;
    }

    private static void copySceneToSnapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getFramebuffer() == null || sceneTarget == null) return;
        GpuTexture source = client.getFramebuffer().getColorAttachment();
        if (source == null || source.getWidth(0) != sceneTarget.width || source.getHeight(0) != sceneTarget.height
                || source.getFormat() != sceneTarget.format) return;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                source, sceneTarget.texture, 0, 0, 0, 0, 0, sceneTarget.width, sceneTarget.height);
    }

    private static void runPass(QueuedPass queued) {
        GpuTextureView source = queued.input;
        GpuTextureView destination = queued.output.view;
        if (!SafePipelines.usable(queued.pipeline)) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Apexx UI pass " + queued.pipeline.getLocation(), destination, OptionalInt.empty())) {
            pass.setPipeline(queued.pipeline);
            if (!pipelinesWithoutInput.contains(queued.pipeline)) {
                pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            }
            if (queued.input2 != null) {
                pass.bindTexture("Sampler1", queued.input2, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            }
            if (queued.uniforms != null) pass.setUniform("UiParameters", queued.uniforms);
            pass.setVertexBuffer(0, fullscreenVertices);
            pass.draw(0, 6);
        }
    }

    private static void ensureFullscreenBuffer() {
        if (fullscreenVertices != null && !fullscreenVertices.isClosed()) return;
        ByteBuffer data = ByteBuffer.allocateDirect(6 * 5 * Float.BYTES).order(ByteOrder.nativeOrder());
        // Flip texture V so the target can be sampled by Minecraft's top-left-origin GUI UVs.
        putVertex(data, -1, -1, 0, 1);
        putVertex(data,  1, -1, 1, 1);
        putVertex(data,  1,  1, 1, 0);
        putVertex(data, -1, -1, 0, 1);
        putVertex(data,  1,  1, 1, 0);
        putVertex(data, -1,  1, 0, 0);
        data.flip();
        fullscreenVertices = RenderSystem.getDevice().createBuffer(
                () -> "Apexx UI fullscreen quad", GpuBuffer.USAGE_VERTEX, data);
    }

    private static void putVertex(ByteBuffer data, float x, float y, float u, float v) {
        data.putFloat(x).putFloat(y).putFloat(0).putFloat(u).putFloat(v);
    }

    private static Target allocateTarget(int width, int height, TextureFormat format, int usage,
                                        String label, long generation) {
        FailedTarget failed = new FailedTarget(width, height, format, usage, generation);
        if (failedTargetAllocations.contains(failed)) return null;
        if (width > RenderSystem.getDevice().getMaxTextureSize() || height > RenderSystem.getDevice().getMaxTextureSize()) {
            failedTargetAllocations.add(failed);
            LOGGER.warn("Skipping oversized GPU UI target {}x{}", width, height);
            return null;
        }
        GpuTexture texture = null;
        GpuTextureView view = null;
        try {
            texture = RenderSystem.getDevice().createTexture(label, usage, format, width, height, 1, 1);
            view = RenderSystem.getDevice().createTextureView(texture);
            return new Target(texture, view, width, height, format, generation);
        } catch (RuntimeException | LinkageError exception) {
            if (view != null) {
                try { view.close(); } catch (RuntimeException | LinkageError ignored) { }
            }
            if (texture != null) {
                try { texture.close(); } catch (RuntimeException | LinkageError ignored) { }
            }
            failedTargetAllocations.add(failed);
            LOGGER.warn("Could not allocate GPU UI target {}x{}", width, height, exception);
            return null;
        }
    }

    private static void closeTarget(Target target) {
        if (target == null || target.closed()) return;
        try { target.view.close(); } catch (RuntimeException | LinkageError ignored) { }
        try { target.texture.close(); } catch (RuntimeException | LinkageError ignored) { }
    }

    private static void ensureDevice() {
        if (RenderSystem.tryGetDevice() == null) throw new IllegalStateException("GPU UI passes require a live render device");
    }

    private static void trackCurrentSurfaceSize() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;
        trackSurfaceSize(client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
    }

    private static void trackSurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0 || (width == lastSurfaceWidth && height == lastSurfaceHeight)) return;
        lastSurfaceWidth = width;
        lastSurfaceHeight = height;
        surfaceGeneration++;
        failedTargetAllocations.clear();
        pooledTargets.removeIf(target -> {
            if (target.leased) return false;
            closeTarget(target);
            return true;
        });
    }

    private static void pruneStaleAndExcessTargets() {
        pooledTargets.removeIf(target -> {
            if (target.leased) return false;
            if (target.generation != surfaceGeneration) {
                closeTarget(target);
                return true;
            }
            return false;
        });
        while (pooledTargets.size() > MAX_POOLED_TARGETS) {
            int stale = -1;
            for (int i = 0; i < pooledTargets.size(); i++) {
                if (!pooledTargets.get(i).leased) { stale = i; break; }
            }
            if (stale < 0) break;
            closeTarget(pooledTargets.remove(stale));
        }
    }

    private static void releaseSafely() {
        if (RenderSystem.tryGetDevice() == null) return;
        if (RenderSystem.isOnRenderThread()) release();
        else RenderSystem.queueFencedTask(GpuUiEffectPasses::release);
    }

    private static float finiteOrZero(float value) { return Float.isFinite(value) ? value : 0.0f; }
    private static int align(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    public static final class Target {
        private final GpuTexture texture;
        private final GpuTextureView view;
        private final int width;
        private final int height;
        private final TextureFormat format;
        private final long generation;
        private boolean leased;

        private Target(GpuTexture texture, GpuTextureView view, int width, int height,
                       TextureFormat format, long generation) {
            this.texture = texture;
            this.view = view;
            this.width = width;
            this.height = height;
            this.format = format;
            this.generation = generation;
        }

        public GpuTexture texture() { return texture; }
        public GpuTextureView view() { return view; }
        public int width() { return width; }
        public int height() { return height; }
        public boolean closed() { return texture.isClosed() || view.isClosed(); }
    }

    private record UniformPage(GpuBuffer buffer) { }
    private record FailedTarget(int width, int height, TextureFormat format, int usage, long generation) { }
    private record QueuedPass(RenderPipeline pipeline, GpuTextureView input, GpuTextureView input2, Target output,
                              GpuBufferSlice uniforms) { }
}
