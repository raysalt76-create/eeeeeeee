package interstellarTikiTiks.apexx.client.render.visuals;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import interstellarTikiTiks.apexx.client.module.hooks.ShaderEspProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;

/**
 * Shader ESP composite. Masked entities are rendered by the vanilla entity-outline path (so skin,
 * armor, held items and elytra all land in the mask); this engine replaces vanilla's sobel/blur
 * chain and flat blit with a fill / outline / glow pass drawn straight onto the main framebuffer.
 */
public final class ShaderEspEngine {
    // Standard alpha-over for colour, but leaves the destination ALPHA channel untouched
    // (src ZERO, dst ONE). The composite draws straight onto the live main framebuffer; under
    // Fabulous graphics that buffer's alpha channel is what the translucency layers composite
    // against, so rewriting it at the silhouette punches see-through holes where the entities are.
    private static final BlendFunction ESP_BLEND = new BlendFunction(
            SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ZERO, DestFactor.ONE);
    private static final Logger LOG = LoggerFactory.getLogger("ApexxShaderEsp");
    private static final Identifier VERTEX = Identifier.of("dynamic_fps", "core/gpuui_material");
    private static final Identifier FRAGMENT = Identifier.of("apexx", "core/visuals/shader_esp");
    private static final int BLOCK_BYTES = 8 * 4 * Float.BYTES;
    private static final ByteBuffer PARAM_SCRATCH = ByteBuffer.allocateDirect(BLOCK_BYTES).order(ByteOrder.nativeOrder());
    private static final int DEPTH_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;
    private static final VertexFormat FULLSCREEN = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION).add("UV0", VertexFormatElement.UV0).build();
    private static final RenderPipeline PIPELINE = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.of("apexx", "pipeline/shader_esp_composite"))
            .withVertexShader(VERTEX).withFragmentShader(FRAGMENT)
            .withVertexFormat(FULLSCREEN, VertexFormat.DrawMode.TRIANGLES)
            .withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2")
            .withUniform("ShaderEspParameters", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false)
            .withBlend(ESP_BLEND).build());

    private static final ShaderEspParams PARAMS = new ShaderEspParams();

    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static TextureFormat depthFormat;
    private static int depthWidth, depthHeight;
    private static GpuBuffer fullscreenVertices, uniforms;
    private static int uniformStride;
    private static boolean warned;
    private static volatile boolean failed;

    static {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSafely());
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Identifier.of("apexx", "shader_esp_engine"),
                (SynchronousResourceReloader) manager -> releaseSafely());
    }

    private ShaderEspEngine() { }

    public static void register() { /* Initializes lifecycle hooks without touching the GPU. */ }

    /** The provider driving this frame's pass, or null while the vanilla outline path must stay untouched. */
    private static ShaderEspProvider provider() {
        // Composite shader unavailable on this driver: leave the vanilla outline path untouched.
        if (failed || !interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(PIPELINE)) return null;
        ShaderEspProvider provider = ModuleAccess.getEnabledHook(ShaderEspProvider.class);
        return provider != null && provider.shaderEspActive() ? provider : null;
    }

    /** True while the engine owns the entity-outline framebuffer for this frame. */
    public static boolean takesOver() {
        return provider() != null;
    }

    public static boolean wantsMask(net.minecraft.entity.Entity entity) {
        ShaderEspProvider provider = provider();
        return provider != null && provider.shaderEspWantsMask(entity);
    }

    /** 0xFFRRGGBB mask color for an entity this engine has already accepted. */
    public static int maskColor(net.minecraft.entity.Entity entity) {
        ShaderEspProvider provider = ModuleAccess.getEnabledHook(ShaderEspProvider.class);
        int rgb = provider == null ? 0xFFFFFF : provider.shaderEspMaskColor(entity, System.currentTimeMillis());
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /** Snapshots main depth before the hand clears it; only needed when occluded targets must hide. */
    public static void captureSceneDepth() {
        ShaderEspProvider provider = provider();
        if (provider == null || !RenderSystem.isOnRenderThread() || RenderSystem.tryGetDevice() == null) return;
        PARAMS.throughWalls = true;
        provider.shaderEspParameters(PARAMS, System.currentTimeMillis());
        if (PARAMS.throughWalls) return;
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        if (main == null || main.getDepthAttachment() == null) return;
        try {
            GpuTexture source = main.getDepthAttachment();
            int w = source.getWidth(0), h = source.getHeight(0);
            if (!ensureDepth(w, h, source.getFormat())) return;
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, depthTexture, 0, 0, 0, 0, 0, w, h);
        } catch (RuntimeException | LinkageError error) {
            fail("scene depth snapshot", error);
        }
    }

    /** Draws the fill / outline / glow pass from the entity-outline framebuffer onto main color. */
    public static void composite(Framebuffer outline) {
        if (outline == null || !RenderSystem.isOnRenderThread() || RenderSystem.tryGetDevice() == null) return;
        ShaderEspProvider provider = provider();
        if (provider == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        GpuTextureView maskColor = outline.getColorAttachmentView();
        GpuTextureView maskDepth = outline.getDepthAttachmentView();
        if (main == null || maskColor == null || maskDepth == null || main.getColorAttachmentView() == null) return;

        long now = System.currentTimeMillis();
        PARAMS.throughWalls = true;
        provider.shaderEspParameters(PARAMS, now);
        if (!PARAMS.drawsAnything()) return;

        int w = main.getColorAttachment().getWidth(0), h = main.getColorAttachment().getHeight(0);
        boolean depthTested = !PARAMS.throughWalls && depthView != null && !depthView.isClosed()
                && depthWidth == w && depthHeight == h;
        try {
            RenderSystem.assertOnRenderThread();
            ensureResources();
            GpuBufferSlice slice = writeParameters(w, h, depthTested);
            GpuTextureView sceneDepth = depthTested ? depthView : maskDepth;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Apexx shader ESP", main.getColorAttachmentView(), OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                pass.bindTexture("Sampler0", maskColor, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.bindTexture("Sampler1", maskDepth, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.bindTexture("Sampler2", sceneDepth, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.setUniform("ShaderEspParameters", slice);
                pass.setVertexBuffer(0, fullscreenVertices);
                pass.draw(0, 6);
            }
        } catch (RuntimeException | LinkageError error) {
            fail("composite", error);
        }
    }

    private static void fail(String stage, Throwable error) {
        failed = true;
        if (!warned) {
            warned = true;
            LOG.error("Shader ESP {} failed; falling back to the vanilla entity outline until resources reload", stage, error);
        }
    }

    private static boolean ensureDepth(int w, int h, TextureFormat format) {
        if (depthTexture != null && !depthTexture.isClosed() && depthView != null && !depthView.isClosed()
                && depthWidth == w && depthHeight == h && depthFormat == format) return true;
        closeDepth();
        int maxTextureSize = RenderSystem.getDevice().getMaxTextureSize();
        if (w > maxTextureSize || h > maxTextureSize) {
            throw new IllegalArgumentException("Shader ESP depth " + w + "x" + h + " exceeds device limit " + maxTextureSize);
        }
        GpuTexture texture = RenderSystem.getDevice().createTexture("Apexx shader ESP scene depth", DEPTH_USAGE, format, w, h, 1, 1);
        try {
            depthView = RenderSystem.getDevice().createTextureView(texture);
        } catch (RuntimeException | LinkageError error) {
            texture.close();
            throw error;
        }
        depthTexture = texture;
        depthFormat = format;
        depthWidth = w;
        depthHeight = h;
        return true;
    }

    private static void ensureResources() {
        if (fullscreenVertices == null || fullscreenVertices.isClosed()) {
            ByteBuffer data = ByteBuffer.allocateDirect(6 * 5 * Float.BYTES).order(ByteOrder.nativeOrder());
            vertex(data, -1, -1, 0, 1); vertex(data, 1, -1, 1, 1); vertex(data, 1, 1, 1, 0);
            vertex(data, -1, -1, 0, 1); vertex(data, 1, 1, 1, 0); vertex(data, -1, 1, 0, 0);
            data.flip();
            fullscreenVertices = RenderSystem.getDevice().createBuffer(
                    () -> "Apexx shader ESP fullscreen quad", GpuBuffer.USAGE_VERTEX, data);
        }
        if (uniforms == null || uniforms.isClosed()) {
            int alignment = Math.max(1, RenderSystem.getDevice().getUniformOffsetAlignment());
            uniformStride = (BLOCK_BYTES + alignment - 1) / alignment * alignment;
            uniforms = RenderSystem.getDevice().createBuffer(() -> "Apexx shader ESP uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, uniformStride);
        }
    }

    private static void vertex(ByteBuffer b, float x, float y, float u, float v) {
        b.putFloat(x).putFloat(y).putFloat(0).putFloat(u).putFloat(v);
    }

    private static GpuBufferSlice writeParameters(int w, int h, boolean depthTested) {
        ShaderEspParams p = PARAMS;
        // Reused scratch (zeroed like a fresh buffer); writeToBuffer copies it immediately. A direct buffer per frame
        // churned off-heap memory until a GC, and direct-memory pressure forces System.gc() pauses.
        ByteBuffer b = PARAM_SCRATCH;
        b.clear();
        org.lwjgl.system.MemoryUtil.memSet(b, 0);
        put4(b, w, h, (float) ((System.nanoTime() / 1.0e9) % 3600.0), depthTested ? 0.0f : 1.0f);
        put4(b, p.fill ? 1 : 0, clamp01(p.fillAlpha), p.fillStyle, Math.max(0.0f, p.fillBrightness));
        put4(b, p.outline ? 1 : 0, Math.max(0.5f, p.outlineWidth), clamp01(p.outlineAlpha), Math.max(0.0f, p.outlineIntensity));
        put4(b, p.glow ? 1 : 0, Math.max(1.0f, p.glowRadius), clamp01(p.glowAlpha), Math.max(0.0f, p.glowIntensity));
        put4(b, Math.max(0.05f, p.smokeScale), Math.max(0.0f, p.smokeSpeed), Math.max(1, Math.min(6, p.smokeOctaves)),
                Math.max(0.1f, p.smokeContrast));
        put4(b, red(p.secondaryRgb), green(p.secondaryRgb), blue(p.secondaryRgb), p.gradientAngleRadians);
        put4(b, red(p.friendRgb), green(p.friendRgb), blue(p.friendRgb), 1.0f);
        put4(b, red(p.enemyRgb), green(p.enemyRgb), blue(p.enemyRgb), 1.0f);
        b.flip();
        GpuBufferSlice slice = uniforms.slice(0, BLOCK_BYTES);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(slice, b);
        return slice;
    }

    private static void put4(ByteBuffer b, float a, float c, float d, float e) {
        b.putFloat(a).putFloat(c).putFloat(d).putFloat(e);
    }

    private static float clamp01(float v) { return v < 0.0f ? 0.0f : Math.min(v, 1.0f); }
    private static float red(int rgb) { return ((rgb >> 16) & 0xFF) / 255.0f; }
    private static float green(int rgb) { return ((rgb >> 8) & 0xFF) / 255.0f; }
    private static float blue(int rgb) { return (rgb & 0xFF) / 255.0f; }

    private static void closeDepth() {
        GpuTextureView oldView = depthView;
        GpuTexture oldTexture = depthTexture;
        depthView = null;
        depthTexture = null;
        depthWidth = depthHeight = 0;
        depthFormat = null;
        try { if (oldView != null && !oldView.isClosed()) oldView.close(); } catch (RuntimeException | LinkageError ignored) { }
        try { if (oldTexture != null && !oldTexture.isClosed()) oldTexture.close(); } catch (RuntimeException | LinkageError ignored) { }
    }

    public static void release() {
        RenderSystem.assertOnRenderThread();
        closeDepth();
        if (fullscreenVertices != null) { try { fullscreenVertices.close(); } catch (RuntimeException | LinkageError ignored) { } }
        if (uniforms != null) { try { uniforms.close(); } catch (RuntimeException | LinkageError ignored) { } }
        fullscreenVertices = null;
        uniforms = null;
        warned = false;
        failed = false;
    }

    private static void releaseSafely() {
        if (RenderSystem.tryGetDevice() == null) return;
        if (RenderSystem.isOnRenderThread()) release(); else RenderSystem.queueFencedTask(ShaderEspEngine::release);
    }
}
