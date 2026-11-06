package interstellarTikiTiks.apexx.client.render.visuals;

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
import interstellarTikiTiks.apexx.client.module.hooks.WorldSceneVisualProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/** Owns the scene color/depth snapshots and ordered full-screen world-effect passes. */
public final class GpuWorldVisualEngine {
    private static final Logger LOG = LoggerFactory.getLogger("ApexxWorldVisuals");
    private static final Identifier VERTEX = Identifier.of("dynamic_fps", "core/gpuui_material");
    private static final Identifier FRAGMENT = Identifier.of("apexx", "core/visuals/world_composite");
    // Frame + 2 matrices + 6 effect vec4s (60), then MarkerInfo + 4 marker arrays of MAX_LANDING_MARKERS vec4s.
    private static final int FLOATS = 60 + 4 + WorldSceneVisuals.MAX_LANDING_MARKERS * 4 * 4;
    private static final int BLOCK_BYTES = FLOATS * Float.BYTES;
    private static final ByteBuffer PARAM_SCRATCH = ByteBuffer.allocateDirect(BLOCK_BYTES).order(ByteOrder.nativeOrder());
    private static final int TARGET_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final VertexFormat FULLSCREEN = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION).add("UV0", VertexFormatElement.UV0).build();
    private static final RenderPipeline PIPELINE = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.of("apexx", "pipeline/world_visual_composite"))
            .withVertexShader(VERTEX).withFragmentShader(FRAGMENT)
            .withVertexFormat(FULLSCREEN, VertexFormat.DrawMode.TRIANGLES)
            .withSampler("Sampler0").withSampler("Sampler1")
            .withUniform("WorldVisualParameters", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false)
            .withoutBlend().build());

    private static Target sceneColor, sceneDepth, ping, pong;
    private static GpuBuffer fullscreenVertices, uniforms;
    private static int width, height, uniformStride;
    private static int failedWidth = -1, failedHeight = -1;
    private static TextureFormat failedColorFormat, failedDepthFormat;
    private static boolean warned;
    private static volatile boolean compositeFailed;
    private static long retryAtMs;
    private static long retryDelayMs = 5_000L;

    static {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSafely());
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Identifier.of("apexx", "world_visual_engine"),
                (SynchronousResourceReloader) manager -> releaseSafely());
    }

    private GpuWorldVisualEngine() { }

    public static void register() { /* Initializes lifecycle hooks without touching the GPU. */ }

    /**
     * False once the compositor has failed to allocate targets or run its pass chain, until the surface
     * changes or resources reload. Providers with a geometry fallback switch to it while this is false.
     */
    public static boolean isHealthy() {
        return !compositeFailed && interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(PIPELINE);
    }

    /** Called after WorldRenderer's frame graph and before GameRenderer clears main depth for held items. */
    public static void composite(Matrix4f projection, Matrix4f modelView) {
        if (projection == null || modelView == null || !RenderSystem.isOnRenderThread()) return;
        if (compositeFailed) {
            long nowMs = System.currentTimeMillis();
            if (nowMs < retryAtMs) { /* backing off */ }
            else {
                retryAtMs = nowMs + retryDelayMs;
                retryDelayMs = Math.min(retryDelayMs * 2, 60_000L);
                compositeFailed = false;
                failedWidth = failedHeight = -1;
                failedColorFormat = failedDepthFormat = null;
            }
        }
        if (!interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(PIPELINE)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.getFramebuffer() == null || RenderSystem.tryGetDevice() == null) return;
        Framebuffer main = client.getFramebuffer();
        GpuTexture color = main.getColorAttachment(), depth = main.getDepthAttachment();
        if (color == null || depth == null) return;
        int w = color.getWidth(0), h = color.getHeight(0);
        if (w < 1 || h < 1) return;

        WorldSceneVisuals settings = new WorldSceneVisuals();
        long now = System.currentTimeMillis();
        for (WorldSceneVisualProvider provider : ModuleAccess.getEnabledHooks(WorldSceneVisualProvider.class)) {
            try { provider.contributeWorldSceneVisuals(settings, now); }
            catch (Throwable error) { LOG.warn("World visual provider failed: {}", provider.getClass().getName(), error); }
        }
        if (!settings.hasEffects()) return;

        try {
            RenderSystem.assertOnRenderThread();
            if (!ensureTargets(w, h, color.getFormat(), depth.getFormat())) return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(color, sceneColor.texture, 0, 0, 0, 0, 0, w, h);
            encoder.copyTextureToTexture(depth, sceneDepth.texture, 0, 0, 0, 0, 0, w, h);
            ensureGeometry();

            Matrix4f inverseProjection = new Matrix4f(projection).invert();
            Matrix4f inverseView = new Matrix4f(modelView).invert();
            GpuTextureView input = sceneColor.view;
            // Alternate strictly so a pass never samples the target it writes, however many passes run.
            Target[] intermediates = {ping, pong};
            int passCount = 0;
            for (int operation = 0; operation < WorldSceneVisuals.OPERATIONS; operation++) {
                if (settings.enabled(operation)) passCount++;
            }
            int passIndex = 0;
            for (int operation = 0; operation < WorldSceneVisuals.OPERATIONS; operation++) {
                if (!settings.enabled(operation)) continue;
                final int operationId = operation;
                boolean last = ++passIndex == passCount;
                GpuTextureView output = last ? main.getColorAttachmentView() : intermediates[(passIndex - 1) % intermediates.length].view;
                if (input.texture() == output.texture()) throw new IllegalStateException("World visual feedback loop");
                GpuBufferSlice slice = writeParameters(w, h, operation, inverseProjection, inverseView, settings);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "Apexx world visual pass " + operationId, output, OptionalInt.empty())) {
                    pass.setPipeline(PIPELINE);
                    pass.bindTexture("Sampler0", input, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                    pass.bindTexture("Sampler1", sceneDepth.view, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                    pass.setUniform("WorldVisualParameters", slice);
                    pass.setVertexBuffer(0, fullscreenVertices);
                    pass.draw(0, 6);
                }
                input = output;
            }
            compositeFailed = false;
            retryDelayMs = 5_000L;
        } catch (Throwable error) {
            compositeFailed = true;
            if (!warned) { warned = true; LOG.error("World visual compositor failed; disabling this frame's pass chain", error); }
        }
    }

    private static boolean ensureTargets(int w, int h, TextureFormat colorFormat, TextureFormat depthFormat) {
        if (w == width && h == height && sceneColor != null && !sceneColor.closed()
                && sceneDepth != null && !sceneDepth.closed() && ping != null && !ping.closed()
                && pong != null && !pong.closed() && uniforms != null && !uniforms.isClosed()
                && sceneColor.format == colorFormat && sceneDepth.format == depthFormat) return true;
        if (w == failedWidth && h == failedHeight && colorFormat == failedColorFormat
                && depthFormat == failedDepthFormat) return false;
        closeTargets();
        try {
            int maxTextureSize = RenderSystem.getDevice().getMaxTextureSize();
            if (w > maxTextureSize || h > maxTextureSize) {
                throw new IllegalArgumentException("World visual target " + w + "x" + h
                        + " exceeds device limit " + maxTextureSize);
            }
            int copyUsage = TARGET_USAGE | GpuTexture.USAGE_COPY_DST;
            sceneColor = allocate(w, h, colorFormat, copyUsage, "Apexx world color snapshot");
            sceneDepth = allocate(w, h, depthFormat, copyUsage, "Apexx world depth snapshot");
            ping = allocate(w, h, colorFormat, TARGET_USAGE, "Apexx world pass A");
            pong = allocate(w, h, colorFormat, TARGET_USAGE, "Apexx world pass B");
            int alignment = Math.max(1, RenderSystem.getDevice().getUniformOffsetAlignment());
            uniformStride = (BLOCK_BYTES + alignment - 1) / alignment * alignment;
            uniforms = RenderSystem.getDevice().createBuffer(() -> "Apexx world visual uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, (long) uniformStride * WorldSceneVisuals.OPERATIONS);
            width = w;
            height = h;
            failedWidth = failedHeight = -1;
            failedColorFormat = failedDepthFormat = null;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            closeTargets();
            width = height = 0;
            failedWidth = w;
            failedHeight = h;
            failedColorFormat = colorFormat;
            failedDepthFormat = depthFormat;
            compositeFailed = true;
            if (!warned) {
                warned = true;
                LOG.warn("World visual targets are unavailable for {}x{}; effects stay disabled until the surface changes or resources reload",
                        w, h, failure);
            }
            return false;
        }
    }

    private static Target allocate(int w, int h, TextureFormat format, int usage, String label) {
        GpuTexture texture = RenderSystem.getDevice().createTexture(label, usage, format, w, h, 1, 1);
        try { return new Target(texture, RenderSystem.getDevice().createTextureView(texture), format); }
        catch (Throwable error) { texture.close(); throw error; }
    }

    private static void ensureGeometry() {
        if (fullscreenVertices == null || fullscreenVertices.isClosed()) {
            ByteBuffer data = ByteBuffer.allocateDirect(6 * 5 * Float.BYTES).order(ByteOrder.nativeOrder());
            vertex(data, -1, -1, 0, 1); vertex(data, 1, -1, 1, 1); vertex(data, 1, 1, 1, 0);
            vertex(data, -1, -1, 0, 1); vertex(data, 1, 1, 1, 0); vertex(data, -1, 1, 0, 0); data.flip();
            fullscreenVertices = RenderSystem.getDevice().createBuffer(() -> "Apexx world visual fullscreen quad", GpuBuffer.USAGE_VERTEX, data);
        }
    }

    private static void vertex(ByteBuffer b, float x, float y, float u, float v) { b.putFloat(x).putFloat(y).putFloat(0).putFloat(u).putFloat(v); }

    private static GpuBufferSlice writeParameters(int w, int h, int operation, Matrix4f inverseProjection,
                                                   Matrix4f inverseView, WorldSceneVisuals s) {
        int slot = operation;
        // Reused scratch (zeroed like a fresh buffer); writeToBuffer copies it immediately. A direct buffer per frame
        // churned off-heap memory until a GC, and direct-memory pressure forces System.gc() pauses.
        ByteBuffer b = PARAM_SCRATCH;
        b.clear();
        org.lwjgl.system.MemoryUtil.memSet(b, 0);
        b.putFloat(w).putFloat(h).putFloat((float) ((System.nanoTime() / 1.0e9) % 3600.0)).putFloat(operation);
        inverseProjection.get(b); inverseView.get(b);
        put4(b, s.fogStart, s.fogEnd, s.fogDensity, s.fogDensity > 0 ? 1 : 0);
        put4(b, s.fogR, s.fogG, s.fogB, 1);
        // The world pass renders camera-relative; when the inverse view carries no translation, hand the
        // shader the camera's world position so reconstructed positions are world-stable.
        float offX = 0, offY = 0, offZ = 0;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer != null && client.gameRenderer.getCamera() != null) {
            var cam = client.gameRenderer.getCamera().getCameraPos();
            float tx = inverseView.m30(), ty = inverseView.m31(), tz = inverseView.m32();
            boolean viewHasTranslation = Math.abs(tx - cam.x) < 4.0 && Math.abs(ty - cam.y) < 4.0 && Math.abs(tz - cam.z) < 4.0
                    && (Math.abs(cam.x) + Math.abs(cam.y) + Math.abs(cam.z)) > 8.0;
            if (!viewHasTranslation) { offX = (float) cam.x; offY = (float) cam.y; offZ = (float) cam.z; }
        }
        put4(b, s.globeStrength, offX, offY, offZ);
        put4(b, s.groundStrength, s.groundScale, 0, 0);
        if (s.hitPosition == null) put4(b, 0, 0, 0, 0);
        else put4(b, (float)s.hitPosition.x, (float)s.hitPosition.y, (float)s.hitPosition.z, s.hitRadius);
        put4(b, s.hitAge, s.hitStrength, s.hitR, s.hitG);
        put4(b, s.markerCount, (float) ((System.nanoTime() / 1.0e9) % 3600.0), s.markerStyle, s.hitB);
        for (float v : s.markerPositionRadius) b.putFloat(v);
        for (float v : s.markerNormalIntensity) b.putFloat(v);
        for (float v : s.markerPrimary) b.putFloat(v);
        for (float v : s.markerSecondary) b.putFloat(v);
        b.flip();
        int offset = slot * uniformStride;
        GpuBufferSlice slice = uniforms.slice(offset, BLOCK_BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, b);
        return slice;
    }

    private static void put4(ByteBuffer b, float a, float c, float d, float e) { b.putFloat(a).putFloat(c).putFloat(d).putFloat(e); }

    private static void closeTargets() {
        Target oldSceneColor = sceneColor, oldSceneDepth = sceneDepth, oldPing = ping, oldPong = pong;
        sceneColor = sceneDepth = ping = pong = null;
        GpuBuffer oldUniforms = uniforms;
        uniforms = null;
        closeSafely(oldSceneColor); closeSafely(oldSceneDepth); closeSafely(oldPing); closeSafely(oldPong);
        if (oldUniforms != null) {
            try { if (!oldUniforms.isClosed()) oldUniforms.close(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    public static void release() {
        RenderSystem.assertOnRenderThread(); closeTargets();
        if (fullscreenVertices != null) fullscreenVertices.close(); fullscreenVertices = null;
        width = height = 0;
        failedWidth = failedHeight = -1;
        failedColorFormat = failedDepthFormat = null;
        warned = false;
        compositeFailed = false;
    }

    private static void releaseSafely() {
        if (RenderSystem.tryGetDevice() == null) return;
        if (RenderSystem.isOnRenderThread()) release(); else RenderSystem.queueFencedTask(GpuWorldVisualEngine::release);
    }

    private static void closeSafely(Target t) {
        if (t == null) return;
        try { if (!t.view.isClosed()) t.view.close(); } catch (RuntimeException | LinkageError ignored) { }
        try { if (!t.texture.isClosed()) t.texture.close(); } catch (RuntimeException | LinkageError ignored) { }
    }
    private record Target(GpuTexture texture, GpuTextureView view, TextureFormat format) {
        boolean closed() { return texture.isClosed() || view.isClosed(); }
    }
}
