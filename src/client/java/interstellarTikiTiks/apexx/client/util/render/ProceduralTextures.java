package interstellarTikiTiks.apexx.client.util.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-generated, resolution independent textures for the realistic visual modules.
 *
 * <p>Everything is computed on the CPU into a {@link NativeImage}, so no art files ship with the
 * client and the result is identical on every OS and GPU vendor. Textures are sampled with linear
 * filtering through {@link #layer}, which is why they stay smooth when stretched over a large
 * shockwave or a long beam. Call {@link #ensure()} from the render thread before using an id.
 */
public final class ProceduralTextures {
    public static final Identifier GLOW = id("glow");
    public static final Identifier SMOKE = id("smoke");
    public static final Identifier RING = id("ring");
    public static final Identifier STREAK = id("streak");
    public static final Identifier BOLT = id("bolt");
    public static final Identifier FEATHER = id("feather");
    public static final Identifier MEMBRANE = id("membrane");
    public static final Identifier PLANE = id("plane");
    public static final Identifier SCORCH = id("scorch");
    public static final Identifier SLASH = id("slash");
    public static final Identifier FISSURE = id("fissure");

    private static final Identifier ENTITY_SHADER = Identifier.of("minecraft", "core/entity");
    private static boolean built;
    private static boolean failed;

    private static final RenderPipeline ALPHA_DEPTH = pipeline("alpha_depth", BlendFunction.TRANSLUCENT, true);
    private static final RenderPipeline ALPHA_FLAT = pipeline("alpha_flat", BlendFunction.TRANSLUCENT, false);
    private static final RenderPipeline ADD_DEPTH = pipeline("add_depth", BlendFunction.LIGHTNING, true);
    private static final RenderPipeline ADD_FLAT = pipeline("add_flat", BlendFunction.LIGHTNING, false);
    private static final Map<String, RenderLayer> LAYERS = new ConcurrentHashMap<>();

    private ProceduralTextures() {}

    public enum Blend { ALPHA, ADDITIVE }

    /** Linear-filtered textured layer for the full entity vertex format (position, colour, uv, overlay, light, normal). */
    public static RenderLayer layer(Identifier texture, Blend blend, boolean depthTest) {
        String key = texture + "|" + blend + "|" + depthTest;
        return LAYERS.computeIfAbsent(key, k -> {
            RenderPipeline pipeline = blend == Blend.ADDITIVE
                    ? (depthTest ? ADD_DEPTH : ADD_FLAT)
                    : (depthTest ? ALPHA_DEPTH : ALPHA_FLAT);
            return RenderLayer.of("apexx_proc_" + Integer.toHexString(k.hashCode()),
                    RenderSetup.builder(pipeline)
                            .texture("Sampler0", texture, () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR))
                            .useLightmap()
                            .useOverlay()
                            .expectedBufferSize(4096)
                            .translucent()
                            .build());
        });
    }

    /** Builds and uploads every texture once. Safe to call every frame; returns false if generation failed. */
    public static boolean ensure() {
        if (built) return !failed;
        built = true;
        try {
            register(GLOW, 128, 128, ProceduralTextures::glowPixel);
            register(SMOKE, 256, 256, ProceduralTextures::smokePixel);
            register(RING, 256, 256, ProceduralTextures::ringPixel);
            register(STREAK, 256, 64, ProceduralTextures::streakPixel);
            register(BOLT, 16, 128, ProceduralTextures::boltPixel);
            register(FEATHER, 128, 256, ProceduralTextures::featherPixel);
            register(MEMBRANE, 256, 256, ProceduralTextures::membranePixel);
            register(PLANE, 16, 128, ProceduralTextures::planePixel);
            register(SCORCH, 256, 256, ProceduralTextures::scorchPixel);
            register(SLASH, 256, 64, ProceduralTextures::slashPixel);
            register(FISSURE, 128, 64, ProceduralTextures::fissurePixel);
        } catch (Throwable t) {
            failed = true;
        }
        return !failed;
    }

    // ------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface PixelFn {
        int argb(float u, float v);
    }

    private static Identifier id(String name) {
        return Identifier.of(Apexx.MOD_ID, "generated/fx_" + name);
    }

    private static void register(Identifier id, int w, int h, PixelFn fn) {
        NativeImage image = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            float v = (y + 0.5f) / h;
            for (int x = 0; x < w; x++) {
                image.setColorArgb(x, y, fn.argb((x + 0.5f) / w, v));
            }
        }
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "apexx_fx_" + id.getPath(), image);
        texture.upload();
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
    }

    private static RenderPipeline pipeline(String name, BlendFunction blend, boolean depth) {
        return interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
                RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                        .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_proc_" + name))
                        .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                        .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                        .withCull(false)
                        .withBlend(blend)
                        .withDepthTestFunction(depth ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
                        .withDepthWrite(false)
                        .build());
    }

    // ---- pixel generators --------------------------------------------------------------------------

    private static int glowPixel(float u, float v) {
        float d = (float) Math.hypot(u - 0.5f, v - 0.5f) * 2f;
        float a = (float) Math.exp(-d * d * 5.0f) * (1f - smooth(0.82f, 1f, d));
        return pack(a, 1f, 1f, 1f);
    }

    /** 2x2 atlas of lit, turbulent smoke puffs. */
    private static int smokePixel(float u, float v) {
        int cell = (u >= 0.5f ? 1 : 0) + (v >= 0.5f ? 2 : 0);
        float lu = (u * 2f) % 1f, lv = (v * 2f) % 1f;
        float d = (float) Math.hypot(lu - 0.5f, lv - 0.5f) * 2f;
        float ox = cell * 17.3f, oy = cell * 9.1f;
        float n = fbm(lu * 3.4f + ox, lv * 3.4f + oy, 5, 11 + cell);
        float warp = fbm(lu * 2.0f + oy, lv * 2.0f + ox, 3, 31 + cell);
        float density = clamp01((1f - d * d) * 1.2f + (n - 0.5f) * 1.25f + (warp - 0.5f) * 0.45f);
        float a = smooth(0.02f, 0.85f, density) * (1f - smooth(0.78f, 1f, d));
        float nl = fbm((lu - 0.04f) * 3.4f + ox, (lv - 0.05f) * 3.4f + oy, 5, 11 + cell);
        float shade = clamp(0.78f + (nl - n) * 3.2f, 0.42f, 1f);
        return pack(a, shade, shade, shade);
    }

    /** Shockwave crest: soft rising body, a thin hot crest, a short sharp outer falloff, broken up by noise. */
    private static int ringPixel(float u, float v) {
        float dx = u - 0.5f, dy = v - 0.5f;
        float d = (float) Math.hypot(dx, dy) * 2f;
        float ang = (float) Math.atan2(dy, dx);
        float crestAt = 0.80f;
        float body = d <= crestAt ? (float) Math.pow(d / crestAt, 5.5) * 0.5f : 0f;
        float crest = (float) Math.exp(-Math.pow((d - crestAt) / 0.028f, 2));
        float outer = d > crestAt ? (float) Math.exp(-Math.pow((d - crestAt) / 0.07f, 2)) * 0.55f : 0f;
        float breakup = 0.78f + 0.22f * fbm((float) Math.cos(ang) * 3f + 5f, (float) Math.sin(ang) * 3f + d * 6f, 4, 7);
        float a = clamp01((body + crest + outer) * breakup) * (1f - smooth(0.93f, 1f, d));
        float heat = clamp01(crest * 1.2f);
        return pack(a, 1f, 1f - 0.04f * (1f - heat), 1f - 0.12f * (1f - heat));
    }

    /** Spark/streak: u runs tail(0) -> head(1), v across. */
    private static int streakPixel(float u, float v) {
        float across = (float) Math.exp(-Math.pow((v - 0.5f) / 0.17f, 2));
        float core = (float) Math.exp(-Math.pow((v - 0.5f) / 0.045f, 2)) * (float) Math.pow(u, 1.4);
        float along = (float) Math.pow(u, 2.2);
        float headRound = u > 0.9f ? (float) Math.sqrt(Math.max(0f, 1f - Math.pow((u - 0.9f) / 0.1f, 2))) : 1f;
        float a = clamp01(along * across + core) * headRound;
        return pack(a, 1f, 1f, 1f);
    }

    /** Beam cross-section: hot narrow core inside a wide soft halo. u is unused. */
    private static int boltPixel(float u, float v) {
        float d = Math.abs(v - 0.5f) * 2f;
        float core = (float) Math.exp(-Math.pow(d / 0.085f, 2));
        float halo = 0.5f * (float) Math.exp(-Math.pow(d / 0.40f, 2));
        float a = clamp01(core + halo) * (1f - smooth(0.9f, 1f, d));
        return pack(a, 1f, 1f, 1f);
    }

    /** Single contour feather: v runs root(0) -> tip(1), shaft slightly off-centre, asymmetric barbed vane. */
    private static int featherPixel(float u, float v) {
        float shaftX = 0.5f + 0.014f * (float) Math.sin(v * 3.1f);
        float dx = u - shaftX;
        float root = smooth(0f, 0.2f, v);
        float tipRound = (float) Math.sqrt(clamp01(1f - (float) Math.pow(v, 3.0)));
        float hw = 0.47f * tipRound * (0.18f + 0.82f * (float) Math.pow(root, 0.55));
        float side = dx < 0f ? 0.70f : 1f;
        float limit = hw * side;
        float ad = Math.abs(dx);
        if (limit <= 0.001f || ad > limit + 0.02f) return 0;

        float e = 1f - ad / limit;
        float cover = clamp01((limit - ad) / 0.012f);
        float n = fbm(u * 38f, v * 70f, 3, 3);
        float fray = e < 0.28f ? clamp01(e * 3.4f + (n - 0.5f) * 1.5f) : 1f;
        float barbPhase = v * 118f - ad * 135f * (dx < 0f ? 1.1f : 0.9f);
        float barb = 0.5f + 0.5f * (float) Math.sin(barbPhase * Math.PI * 2.0 / 3.0 + n * 2.2f);
        float gap = e < 0.55f ? 0.82f + 0.18f * barb : 0.9f + 0.1f * barb;
        float shade = (0.93f - 0.14f * (1f - e)) * gap;
        float a = cover * fray;
        if (v < 0.22f) a *= 0.55f + 0.45f * smooth(0.04f, 0.22f, v) * (0.8f + 0.2f * n);
        float shaft = 1f - smooth(0.006f, 0.016f, ad);
        if (shaft > 0f && v < 0.985f) {
            a = Math.max(a, shaft);
            shade = shade + (1f - shade) * shaft * 0.7f;
            return pack(a, shade, shade * 0.985f, shade * 0.95f);
        }
        return pack(a, shade, shade, shade);
    }

    /** Wing membrane skin: u across the panel, v from root to trailing edge; veins fan out from the root. */
    private static int membranePixel(float u, float v) {
        float n = fbm(u * 5f, v * 5f, 4, 21);
        float f = (u * 6.5f + (n - 0.5f) * 0.9f + v * (n - 0.5f) * 0.8f) % 1f;
        float dist = Math.abs(f - 0.5f);
        float vein = 1f - smooth(0.012f, 0.055f, dist);
        float capillary = 1f - smooth(0.03f, 0.10f, Math.abs(fbm(u * 14f, v * 14f, 3, 5) - 0.5f));
        float edgeBone = (float) Math.exp(-Math.pow(u / 0.06f, 2)) + (float) Math.exp(-Math.pow((1f - u) / 0.06f, 2));
        float skin = 0.88f - 0.12f * n;
        float shade = skin - vein * 0.34f - capillary * 0.10f - clamp01(edgeBone) * 0.30f;
        float thick = 0.66f + 0.18f * n + vein * 0.2f + clamp01(edgeBone) * 0.32f;
        thick *= 1f - 0.45f * smooth(0.7f, 1f, v);
        return pack(clamp01(thick), clamp(shade, 0.15f, 1f), clamp(shade * 0.96f, 0.1f, 1f), clamp(shade * 0.92f, 0.1f, 1f));
    }

    /** Vertical light-sheet profile: bright rim at v=0 falling into a soft body. u is unused. */
    private static int planePixel(float u, float v) {
        float rim = (float) Math.exp(-Math.pow(v / 0.028f, 2));
        float body = 0.42f * (float) Math.pow(1f - v, 1.7);
        float a = clamp01(rim + body) * (1f - smooth(0.88f, 1f, v));
        return pack(a, 1f, 1f, 1f);
    }

    /** Blade-trail cross-section: razor leading edge at v~0.06 with air striations streaming off the trailing side. */
    private static int slashPixel(float u, float v) {
        float rim = (float) Math.exp(-Math.pow((v - 0.06f) / 0.032f, 2));
        float trail = clamp01(1f - (v - 0.06f) / 0.94f);
        float body = (float) Math.pow(trail, 2.3) * 0.62f;
        float stri = 0.62f + 0.38f * fbm(u * 60f, v * 5f, 3, 17);
        float a = clamp01(rim + body * stri) * smooth(0f, 0.02f, v);
        return pack(a, 1f, 1f, 1f);
    }

    /** Crack cross-section: solid dark core with a ragged, noise-driven edge. u runs along the crack, v across. */
    private static int fissurePixel(float u, float v) {
        float d = Math.abs(v - 0.5f) * 2f;
        float edge = 0.52f + (fbm(u * 16f, 3.7f, 3, 9) - 0.5f) * 0.55f + (fbm(u * 5f, v * 4f, 2, 4) - 0.5f) * 0.25f;
        float a = 1f - smooth(edge - 0.20f, edge + 0.04f, d);
        float rim = smooth(edge - 0.32f, edge - 0.05f, d) * a;
        float shade = 0.045f + 0.07f * rim;
        return pack(a * 0.97f, shade * 1.15f, shade, shade * 0.85f);
    }

    /** Charred ground decal: dark irregular blotch with a faint hot edge. */
    private static int scorchPixel(float u, float v) {
        float d = (float) Math.hypot(u - 0.5f, v - 0.5f) * 2f;
        float n = fbm(u * 6f, v * 6f, 5, 41);
        float body = clamp01(1f - d * 1.05f + (n - 0.5f) * 0.9f);
        float a = smooth(0f, 0.55f, body) * (1f - smooth(0.85f, 1f, d));
        float edge = (float) Math.exp(-Math.pow((body - 0.12f) / 0.07f, 2)) * 0.5f;
        float shade = 0.05f + 0.12f * n;
        return pack(a, clamp01(shade + edge * 0.9f), clamp01(shade + edge * 0.35f), shade);
    }

    // ---- math --------------------------------------------------------------------------------------

    private static int pack(float a, float r, float g, float b) {
        return (to8(a) << 24) | (to8(r) << 16) | (to8(g) << 8) | to8(b);
    }

    private static int to8(float x) {
        return (int) (clamp01(x) * 255f + 0.5f);
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : Math.min(x, 1f);
    }

    private static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : Math.min(x, hi);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float valueNoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed);
        float c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static float fbm(float x, float y, int octaves, int seed) {
        float amp = 0.5f, sum = 0f, norm = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += amp * valueNoise(x, y, seed + i * 7);
            norm += amp;
            x *= 2.03f;
            y *= 2.03f;
            amp *= 0.5f;
        }
        return sum / norm;
    }
}
