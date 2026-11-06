package interstellarTikiTiks.apexx.client.module.implementation.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.cosmetics.CapeDesign;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticPrefs;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticSelection;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.session.Session;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider;

/**
 * Premium cosmetics (capes and the cape maker, cape physics, hats, wings, sharing with other Apexx users). A hidden,
 * always-available module so the whole implementation ships only in the premium payload; the loader's Cosmetics tab
 * and player-renderer mixins reach it through {@link CosmeticsProvider}. All helpers are nested classes so they are
 * removed from the free payload together with the module.
 */
public class Cosmetics extends Module implements CosmeticsProvider {

    public Cosmetics() {
        super("Cosmetics", "Capes, hats and wings from the Cosmetics tab.", Category.RENDER);
        setHidden(true);
    }

    @Override
    public boolean cosmeticsAvailable() {
        return "premium".equalsIgnoreCase(ApexxAuth.getSessionTier());
    }

    @Override
    public void onPlayerRenderState(Entity entity, PlayerEntityRenderState state) {
        if (cosmeticsAvailable()) CosmeticRenderer.onUpdateRenderState(entity, state);
    }

    @Override
    public boolean renderPlayerFeatures(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                        PlayerEntityRenderState state, PlayerEntityModel model) {
        return cosmeticsAvailable() && CosmeticRenderer.renderFeatures(matrices, queue, light, state, model);
    }

    @Override
    public Identifier capeTexture(CapeDesign design) {
        return cosmeticsAvailable() ? CosmeticTextures.cape(design) : null;
    }

    @Override
    public boolean previewAvailable() {
        return CosmeticsPreview.available();
    }

    @Override
    public void drawPreview(DrawContext context, int x1, int y1, int x2, int y2, float yawDeg, float tiltDeg) {
        if (cosmeticsAvailable()) CosmeticsPreview.draw(context, x1, y1, x2, y2, yawDeg, tiltDeg);
    }

    @Override
    public void drawThumbnail(DrawContext context, int x1, int y1, int x2, int y2, CosmeticSelection selection, boolean wings) {
        if (cosmeticsAvailable()) CosmeticsPreview.drawThumbnail(context, x1, y1, x2, y2, selection, wings);
    }

    @Override
    public String syncStatus() {
        return CosmeticsSync.status();
    }

    @Override
    public void importCapeImage(Consumer<String> onEncoded, Consumer<String> onError) {
        if (cosmeticsAvailable()) CapeImageImport.pick(onEncoded, onError);
    }


/**
 * Turns an imported picture into a few hundred bytes of text that fit the cloud prefs and the shared
 * cosmetics record: cropped to the cape's 5:8 face, scaled down, reduced to a small palette (median cut),
 * bit-packed, deflated and base64url-encoded. The picture itself is never written anywhere.
 *
 * <p>Format (before deflate): version 1, width, height, colour count n (1..16), n RGB triples, then the
 * pixel indices packed MSB-first at 1, 2 or 4 bits each.
 */
static final class CapeImageCodec {
    /** Hard cap on the encoded text, shared with the website's validation. */
    public static final int MAX_ENCODED_CHARS = CapeDesign.MAX_IMAGE_CHARS;
    public static final int MAX_DIM = 64;
    private static final int MAX_RAW = 4 + 16 * 3 + (MAX_DIM * MAX_DIM + 1) / 2;
    private static final int VERSION = 1;

    /** Resolution / palette steps tried in order until the result fits {@link #MAX_ENCODED_CHARS}. */
    private static final int[][] LADDER = {{40, 64, 16}, {40, 64, 8}, {20, 32, 16}, {20, 32, 8}, {10, 16, 8}, {10, 16, 4}};

    private CapeImageCodec() { }

    public record Image(int width, int height, int[] argb) {
        public int at(float u, float v) {
            int x = Math.min(width - 1, Math.max(0, (int) (u * width)));
            int y = Math.min(height - 1, Math.max(0, (int) (v * height)));
            return argb[y * width + x];
        }
    }

    /** Encodes a picture (ARGB, row-major). Returns null when the input is unusable. */
    public static String encode(int[] argb, int width, int height) {
        if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) return null;
        for (int[] step : LADDER) {
            int[] scaled = resampleCover(argb, width, height, step[0], step[1]);
            String out = pack(scaled, step[0], step[1], step[2]);
            if (out != null && out.length() <= MAX_ENCODED_CHARS) return out;
        }
        return null;
    }

    /** Cheap syntactic check (charset and length) used when reading untrusted JSON. */
    public static boolean isWellFormed(String data) {
        return CapeDesign.isImageWellFormed(data);
    }

    /** Decodes, validating every field. Returns null for anything malformed. */
    public static Image decode(String data) {
        if (!isWellFormed(data)) return null;
        byte[] raw;
        try {
            byte[] packed = Base64.getUrlDecoder().decode(data);
            raw = inflate(packed);
        } catch (IllegalArgumentException | DataFormatException bad) {
            return null;
        }
        if (raw == null || raw.length < 4 || raw[0] != VERSION) return null;
        int w = raw[1] & 255, h = raw[2] & 255, n = raw[3] & 255;
        if (w < 1 || h < 1 || w > MAX_DIM || h > MAX_DIM || n < 1 || n > 16) return null;
        int bits = bitsFor(n);
        int paletteEnd = 4 + n * 3;
        int needed = paletteEnd + (w * h * bits + 7) / 8;
        if (raw.length < needed) return null;
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) {
            int o = 4 + i * 3;
            palette[i] = 0xFF000000 | ((raw[o] & 255) << 16) | ((raw[o + 1] & 255) << 8) | (raw[o + 2] & 255);
        }
        int[] out = new int[w * h];
        int bitPos = 0;
        for (int i = 0; i < out.length; i++) {
            int value = 0;
            for (int b = 0; b < bits; b++, bitPos++) {
                int byteVal = raw[paletteEnd + (bitPos >> 3)] & 255;
                value = (value << 1) | ((byteVal >> (7 - (bitPos & 7))) & 1);
            }
            out[i] = palette[Math.min(value, n - 1)];
        }
        return new Image(w, h, out);
    }

    // ------------------------------------------------------------------ encode steps

    static String pack(int[] rgb, int w, int h, int colors) {
        int[] palette = medianCut(rgb, colors);
        int n = palette.length;
        int bits = bitsFor(n);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(VERSION);
        bytes.write(w);
        bytes.write(h);
        bytes.write(n);
        for (int c : palette) {
            bytes.write((c >> 16) & 255);
            bytes.write((c >> 8) & 255);
            bytes.write(c & 255);
        }
        int acc = 0, accBits = 0;
        for (int px : rgb) {
            int idx = nearest(palette, px);
            for (int b = bits - 1; b >= 0; b--) {
                acc = (acc << 1) | ((idx >> b) & 1);
                if (++accBits == 8) {
                    bytes.write(acc);
                    acc = 0;
                    accBits = 0;
                }
            }
        }
        if (accBits > 0) bytes.write(acc << (8 - accBits));
        byte[] deflated = deflate(bytes.toByteArray());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(deflated);
    }

    static int bitsFor(int n) {
        return n <= 2 ? 1 : n <= 4 ? 2 : 4;
    }

    /** Crops to the target aspect (centre, "cover"), then area-averages down; transparent pixels go over black. */
    static int[] resampleCover(int[] src, int sw, int sh, int tw, int th) {
        double targetAspect = tw / (double) th;
        double cw = sw, ch = sh, cx = 0, cy = 0;
        if (sw / (double) sh > targetAspect) {
            cw = sh * targetAspect;
            cx = (sw - cw) / 2.0;
        } else {
            ch = sw / targetAspect;
            cy = (sh - ch) / 2.0;
        }
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            double y0 = cy + ch * y / th, y1 = cy + ch * (y + 1) / th;
            for (int x = 0; x < tw; x++) {
                double x0 = cx + cw * x / tw, x1 = cx + cw * (x + 1) / tw;
                double r = 0, g = 0, b = 0, weight = 0;
                int ix0 = (int) Math.floor(x0), ix1 = Math.min(sw - 1, (int) Math.ceil(x1) - 1);
                int iy0 = (int) Math.floor(y0), iy1 = Math.min(sh - 1, (int) Math.ceil(y1) - 1);
                for (int sy = iy0; sy <= Math.max(iy0, iy1); sy++) {
                    double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
                    if (wy <= 0) wy = 1e-3;
                    for (int sx = ix0; sx <= Math.max(ix0, ix1); sx++) {
                        double wx = Math.min(x1, sx + 1) - Math.max(x0, sx);
                        if (wx <= 0) wx = 1e-3;
                        int p = src[Math.min(sh - 1, Math.max(0, sy)) * sw + Math.min(sw - 1, Math.max(0, sx))];
                        double a = ((p >>> 24) & 255) / 255.0;
                        double wgt = wx * wy;
                        r += ((p >> 16) & 255) * a * wgt;
                        g += ((p >> 8) & 255) * a * wgt;
                        b += (p & 255) * a * wgt;
                        weight += wgt;
                    }
                }
                if (weight <= 0) weight = 1;
                out[y * tw + x] = 0xFF000000 | (clamp8(r / weight) << 16) | (clamp8(g / weight) << 8) | clamp8(b / weight);
            }
        }
        return out;
    }

    private static int clamp8(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Median-cut palette of at most {@code colors} entries. */
    static int[] medianCut(int[] rgb, int colors) {
        List<int[]> boxes = new ArrayList<>();
        boxes.add(rgb.clone());
        while (boxes.size() < colors) {
            int best = -1, bestRange = 0, bestChannel = 0;
            for (int i = 0; i < boxes.size(); i++) {
                int[] box = boxes.get(i);
                if (box.length < 2) continue;
                for (int ch = 0; ch < 3; ch++) {
                    int lo = 255, hi = 0, shift = 16 - ch * 8;
                    for (int p : box) {
                        int v = (p >> shift) & 255;
                        if (v < lo) lo = v;
                        if (v > hi) hi = v;
                    }
                    if (hi - lo > bestRange) {
                        bestRange = hi - lo;
                        best = i;
                        bestChannel = ch;
                    }
                }
            }
            if (best < 0 || bestRange == 0) break;
            int[] box = boxes.remove(best);
            int shift = 16 - bestChannel * 8;
            Integer[] sorted = new Integer[box.length];
            for (int i = 0; i < box.length; i++) sorted[i] = box[i];
            java.util.Arrays.sort(sorted, Comparator.comparingInt(p -> (p >> shift) & 255));
            int mid = sorted.length / 2;
            int[] a = new int[mid], b = new int[sorted.length - mid];
            for (int i = 0; i < sorted.length; i++) {
                if (i < mid) a[i] = sorted[i];
                else b[i - mid] = sorted[i];
            }
            boxes.add(a);
            boxes.add(b);
        }
        int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            long r = 0, g = 0, b = 0;
            int[] box = boxes.get(i);
            for (int p : box) {
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
            }
            int n = Math.max(1, box.length);
            palette[i] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        }
        return palette;
    }

    static int nearest(int[] palette, int p) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];
            long dr = r - ((c >> 16) & 255), dg = g - ((c >> 8) & 255), db = b - (c & 255);
            long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] input) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(MAX_RAW);
            byte[] buf = new byte[512];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_RAW) return null; // inflation bomb
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}


/**
 * Paints a {@link CapeDesign} into a cape texture laid out like a vanilla cape (64x32 units), at
 * {@link #SCALE}x resolution so text and emblems stay crisp. The outer face, the inner face, the edges and the
 * elytra wings are all filled, so the same texture works for the vanilla cape model, the elytra and the
 * cloth model in {@code CapeModel}. Pure Java: no Minecraft types, testable offline.
 */
static final class CapeArt {
    public static final int SCALE = 4;
    public static final int WIDTH = 64 * SCALE, HEIGHT = 32 * SCALE;
    /** Outer face of the cape in base units (x, y, w, h). */
    public static final int[] OUTER = {1, 1, 10, 16};
    public static final int[] INNER = {12, 1, 10, 16};

    private CapeArt() { }

    public static int[] render(CapeDesign d) {
        int[] px = new int[WIDTH * HEIGHT];
        CapeImageCodec.Image image = d.image() == null ? null : CapeImageCodec.decode(d.image());
        int edge = d.border() == CapeDesign.Border.NONE ? CapeDesign.mix(d.base1(), 0xFF000000, 0.35f) : d.borderColor();

        // Cape box: top/bottom/sides take the edge colour.
        fill(px, 0, 0, 22, 17, edge);
        face(px, OUTER, d, image, true, false);
        face(px, INNER, d, image, false, true);

        // Elytra: one 24x22 region; both wing faces get the design, the rest the edge colour.
        fill(px, 22, 0, 24, 22, edge);
        face(px, new int[]{24, 2, 10, 20}, d, image, true, false);
        face(px, new int[]{36, 2, 10, 20}, d, image, true, true);
        return px;
    }

    private static void fill(int[] px, int bx, int by, int bw, int bh, int color) {
        for (int y = by * SCALE; y < (by + bh) * SCALE; y++) {
            for (int x = bx * SCALE; x < (bx + bw) * SCALE; x++) px[y * WIDTH + x] = color;
        }
    }

    /** Paints one face. {@code decorated} draws text and emblem; {@code mirror} flips u (the inside of the cape). */
    private static void face(int[] px, int[] r, CapeDesign d, CapeImageCodec.Image image, boolean decorated, boolean mirror) {
        int fw = r[2] * SCALE, fh = r[3] * SCALE;
        int ox = r[0] * SCALE, oy = r[1] * SCALE;
        int[] local = new int[fw * fh];
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                float u = (x + 0.5f) / fw, v = (y + 0.5f) / fh;
                local[y * fw + x] = background(d, image, u, v, x, y, fw, fh);
            }
        }
        if (decorated && image == null) decorate(local, fw, fh, d);
        border(local, fw, fh, d);
        boolean inside = !decorated || (mirror && r == INNER);
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                int c = local[y * fw + (mirror ? fw - 1 - x : x)];
                if (inside) c = shade(c, 0.62f);
                px[(oy + y) * WIDTH + ox + x] = c;
            }
        }
    }

    // ------------------------------------------------------------------ background

    private static int background(CapeDesign d, CapeImageCodec.Image image, float u, float v, int x, int y, int fw, int fh) {
        int c;
        if (image != null) {
            c = image.at(u, v);
        } else {
            float t = switch (d.gradient()) {
                case NONE -> 0f;
                case VERTICAL -> v;
                case HORIZONTAL -> u;
                case DIAGONAL -> (u * 0.625f + v) / 1.625f;
                case RADIAL -> Math.min(1f, (float) Math.hypot((u - 0.5f) * 0.625f, v - 0.38f) * 1.7f);
            };
            c = CapeDesign.mix(d.base1(), d.base2(), smooth(t));
            float p = pattern(d.pattern(), x * 40f / fw, y * 64f / fh);
            if (p > 0f) c = CapeDesign.mix(c, d.patternColor(), p);
        }
        // Woven-cloth grain and a soft fall-off toward the hem.
        float grain = 0.965f + 0.035f * hash(x, y, 7);
        float hem = 1f - 0.12f * smooth(Math.max(0f, (v - 0.82f) / 0.18f));
        return shade(c, grain * hem);
    }

    /** Pattern coverage 0..1 at face coordinates in a 40x64 grid. */
    static float pattern(CapeDesign.Pattern p, float x, float y) {
        switch (p) {
            case STRIPES: {
                float s = ((x + y) / 7f) % 2f;
                return s < 1f ? 0.42f : 0f;
            }
            case CHEVRON: {
                float s = ((y + Math.abs(x - 20f)) / 8f) % 2f;
                return s < 1f ? 0.38f : 0f;
            }
            case DOTS: {
                float cx = (float) Math.floor(x / 8f) * 8f + 4f, cy = (float) Math.floor(y / 8f) * 8f + 4f;
                if (((int) Math.floor(y / 8f)) % 2 == 1) cx = (float) Math.floor((x + 4f) / 8f) * 8f;
                float dd = (float) Math.hypot(x - cx, y - cy);
                return dd < 2.2f ? 0.75f : dd < 2.9f ? 0.35f : 0f;
            }
            case CHECKER:
                return (((int) Math.floor(x / 8f) + (int) Math.floor(y / 8f)) & 1) == 0 ? 0.92f : 0f;
            case STARS: {
                int cx = (int) Math.floor(x / 5f), cy = (int) Math.floor(y / 5f);
                float h = hash(cx, cy, 31);
                if (h < 0.80f) return 0f;
                float sx = cx * 5f + 1f + hash(cx, cy, 5) * 3f, sy = cy * 5f + 1f + hash(cx, cy, 9) * 3f;
                float dd = Math.abs(x - sx) + Math.abs(y - sy);
                float size = h > 0.95f ? 1.6f : 0.9f;
                return dd < size ? 0.95f : 0f;
            }
            case WAVES: {
                float s = ((y + 3.2f * (float) Math.sin(x / 5.5f)) / 7f) % 2f;
                if (s < 0f) s += 2f;
                return s < 0.55f ? 0.55f : 0f;
            }
            case CIRCUIT: {
                int gx = (int) Math.floor(x / 6f), gy = (int) Math.floor(y / 6f);
                float fx = x - gx * 6f, fy = y - gy * 6f;
                boolean horizontal = hash(gx, gy, 3) > 0.45f, vertical = hash(gx, gy, 4) > 0.62f;
                boolean node = hash(gx, gy, 6) > 0.78f && Math.hypot(fx - 3f, fy - 3f) < 1.4f;
                boolean line = (horizontal && Math.abs(fy - 3f) < 0.55f) || (vertical && Math.abs(fx - 3f) < 0.55f);
                return node ? 1f : line ? 0.8f : 0f;
            }
            case DIAMONDS: {
                float fx = ((x % 10f) + 10f) % 10f - 5f, fy = ((y % 10f) + 10f) % 10f - 5f;
                float dd = Math.abs(fx) + Math.abs(fy);
                return dd < 3.6f ? 0.45f : dd < 4.2f ? 0.15f : 0f;
            }
            case SCALES: {
                float row = (float) Math.floor(y / 5f);
                float off = (((int) row) & 1) == 1 ? 3f : 0f;
                float cx = (float) Math.floor((x + off) / 6f) * 6f + 3f - off;
                float dd = (float) Math.hypot(x - cx, y - row * 5f);
                return dd > 3.1f && dd < 4.0f ? 0.65f : dd <= 3.1f ? 0.12f : 0f;
            }
            default:
                return 0f;
        }
    }

    // ------------------------------------------------------------------ emblem and text

    private static void decorate(int[] px, int w, int h, CapeDesign d) {
        boolean hasEmblem = d.emblem() != CapeDesign.Emblem.NONE;
        boolean hasText = !d.text().isEmpty();
        if (hasEmblem) {
            float cy = hasText ? 0.36f : 0.45f;
            float size = w * (hasText ? 0.30f : 0.36f);
            emblem(px, w, h, d.emblem(), w * 0.5f, h * cy, size, d.emblemColor());
        }
        if (hasText) {
            int n = d.text().length();
            int scale = Math.max(1, Math.min(hasEmblem ? 3 : 5, (w - 6) / (6 * n - 1)));
            int tw = (6 * n - 1) * scale, th = 7 * scale;
            int tx = (w - tw) / 2;
            int ty = hasEmblem ? Math.round(h * 0.70f) - th / 2 : (h - th) / 2;
            text(px, w, h, d.text(), tx, ty, scale, d.textColor());
        }
    }

    private static void emblem(int[] px, int w, int h, CapeDesign.Emblem e, float cx, float cy, float r, int color) {
        int x0 = Math.max(0, (int) (cx - r - 2)), x1 = Math.min(w - 1, (int) (cx + r + 2));
        int y0 = Math.max(0, (int) (cy - r - 2)), y1 = Math.min(h - 1, (int) (cy + r + 2));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int hits = 0;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        float lx = (x + (sx + 0.5f) / 3f - cx) / r, ly = (y + (sy + 0.5f) / 3f - cy) / r;
                        if (insideEmblem(e, lx, ly)) hits++;
                    }
                }
                if (hits == 0) continue;
                int i = y * w + x;
                // 3x3 supersampled coverage gives the emblem smooth edges.
                px[i] = CapeDesign.mix(px[i], color, hits / 9f);
            }
        }
    }

    /** Emblem shapes in [-1, 1] with y pointing down. */
    static boolean insideEmblem(CapeDesign.Emblem e, float x, float y) {
        switch (e) {
            case APEXX: {
                // An "A" mark: a hollow triangle with a cross bar.
                boolean outer = triangle(x, y, 0f, -0.95f, -0.9f, 0.85f, 0.9f, 0.85f);
                boolean inner = triangle(x, y, 0f, -0.42f, -0.48f, 0.85f, 0.48f, 0.85f);
                boolean bar = y > 0.28f && y < 0.46f && outer;
                return (outer && !inner) || bar;
            }
            case STAR:
                return polygon(x, y, star(5, 0.95f, 0.42f));
            case HEART: {
                float hx = x * 1.15f, hy = -(y * 1.15f) + 0.25f;
                float a = hx * hx + hy * hy - 1f;
                return a * a * a - hx * hx * hy * hy * hy <= 0f;
            }
            case BOLT:
                return polygon(x, y, new float[]{0.25f, -0.95f, -0.55f, 0.12f, -0.05f, 0.12f, -0.3f, 0.95f, 0.6f, -0.22f, 0.08f, -0.22f, 0.4f, -0.95f});
            case CROWN:
                return polygon(x, y, new float[]{-0.85f, 0.65f, -0.95f, -0.55f, -0.45f, -0.05f, 0f, -0.8f, 0.45f, -0.05f, 0.95f, -0.55f, 0.85f, 0.65f})
                        || (y > 0.72f && y < 0.9f && Math.abs(x) < 0.85f);
            case MOON:
                return Math.hypot(x, y) < 0.85f && Math.hypot(x - 0.38f, y + 0.18f) > 0.68f;
            case FLAME: {
                // Teardrop with a flickering tip.
                float yy = y + 0.1f;
                if (yy > 0.85f || yy < -1f) return false;
                float width = yy > 0.25f ? (float) Math.sqrt(Math.max(0, 0.36f - (yy - 0.25f) * (yy - 0.25f))) * 1.25f
                        : 0.75f * (yy + 1f) / 1.25f;
                float wobble = 0.08f * (float) Math.sin(yy * 9f);
                return Math.abs(x - wobble * (0.25f - yy)) < width;
            }
            case DIAMOND:
                return Math.abs(x) / 0.72f + Math.abs(y) <= 0.98f;
            default:
                return false;
        }
    }

    private static float[] star(int points, float outer, float inner) {
        float[] v = new float[points * 4];
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            float r = (i & 1) == 0 ? outer : inner;
            v[i * 2] = (float) Math.cos(a) * r;
            v[i * 2 + 1] = (float) Math.sin(a) * r + 0.08f;
        }
        return v;
    }

    private static boolean triangle(float x, float y, float ax, float ay, float bx, float by, float cx, float cy) {
        return polygon(x, y, new float[]{ax, ay, bx, by, cx, cy});
    }

    static boolean polygon(float x, float y, float[] v) {
        boolean in = false;
        int n = v.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = v[i * 2], yi = v[i * 2 + 1], xj = v[j * 2], yj = v[j * 2 + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static void text(int[] px, int w, int h, String text, int x0, int y0, int scale, int color) {
        int shadow = CapeDesign.mix(color, 0xFF000000, 0.75f);
        for (int pass = 0; pass < 2; pass++) {
            int off = pass == 0 ? Math.max(1, scale / 2) : 0;
            int col = pass == 0 ? shadow : color;
            for (int ci = 0; ci < text.length(); ci++) {
                int[] glyph = Font5x7.glyph(text.charAt(ci));
                int gx = x0 + ci * 6 * scale;
                for (int row = 0; row < 7; row++) {
                    for (int c = 0; c < 5; c++) {
                        if (((glyph[row] >> (4 - c)) & 1) == 0) continue;
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int x = gx + c * scale + sx + off, y = y0 + row * scale + sy + off;
                                if (x >= 0 && y >= 0 && x < w && y < h) px[y * w + x] = col;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void border(int[] px, int w, int h, CapeDesign d) {
        if (d.border() == CapeDesign.Border.NONE) return;
        int t = d.border() == CapeDesign.Border.THICK ? 4 : 2;
        int glow = d.border() == CapeDesign.Border.GLOW ? 7 : 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dist = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
                int i = y * w + x;
                if (dist < t) px[i] = d.borderColor();
                else if (glow > 0 && dist < t + glow) {
                    float k = 1f - (dist - t) / (float) glow;
                    px[i] = CapeDesign.mix(px[i], d.borderColor(), 0.55f * k * k);
                }
            }
        }
    }

    // ------------------------------------------------------------------ math

    static int shade(int argb, float k) {
        int r = Math.min(255, Math.max(0, (int) (((argb >> 16) & 255) * k)));
        int g = Math.min(255, Math.max(0, (int) (((argb >> 8) & 255) * k)));
        int b = Math.min(255, Math.max(0, (int) ((argb & 255) * k)));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Minimal 5x7 bitmap font for A-Z and 0-9 (each row is 5 bits, MSB = left). */
    static final class Font5x7 {
        private static final int[][] LETTERS = {
                {14, 17, 17, 31, 17, 17, 17}, {30, 17, 17, 30, 17, 17, 30}, {14, 17, 16, 16, 16, 17, 14}, {30, 17, 17, 17, 17, 17, 30},
                {31, 16, 16, 30, 16, 16, 31}, {31, 16, 16, 30, 16, 16, 16}, {14, 17, 16, 23, 17, 17, 15}, {17, 17, 17, 31, 17, 17, 17},
                {14, 4, 4, 4, 4, 4, 14}, {7, 2, 2, 2, 2, 18, 12}, {17, 18, 20, 24, 20, 18, 17}, {16, 16, 16, 16, 16, 16, 31},
                {17, 27, 21, 21, 17, 17, 17}, {17, 17, 25, 21, 19, 17, 17}, {14, 17, 17, 17, 17, 17, 14}, {30, 17, 17, 30, 16, 16, 16},
                {14, 17, 17, 17, 21, 18, 13}, {30, 17, 17, 30, 20, 18, 17}, {15, 16, 16, 14, 1, 1, 30}, {31, 4, 4, 4, 4, 4, 4},
                {17, 17, 17, 17, 17, 17, 14}, {17, 17, 17, 17, 17, 10, 4}, {17, 17, 17, 21, 21, 21, 10}, {17, 17, 10, 4, 10, 17, 17},
                {17, 17, 10, 4, 4, 4, 4}, {31, 1, 2, 4, 8, 16, 31}};
        private static final int[][] DIGITS = {
                {14, 17, 19, 21, 25, 17, 14}, {4, 12, 4, 4, 4, 4, 14}, {14, 17, 1, 2, 4, 8, 31}, {31, 2, 4, 2, 1, 17, 14},
                {2, 6, 10, 18, 31, 2, 2}, {31, 16, 30, 1, 1, 17, 14}, {6, 8, 16, 30, 17, 17, 14}, {31, 1, 2, 4, 8, 8, 8},
                {14, 17, 17, 14, 17, 17, 14}, {14, 17, 17, 15, 1, 2, 12}};
        private static final int[] BLANK = {0, 0, 0, 0, 0, 0, 0};

        static int[] glyph(char c) {
            if (c >= 'A' && c <= 'Z') return LETTERS[c - 'A'];
            if (c >= '0' && c <= '9') return DIGITS[c - '0'];
            return BLANK;
        }
    }
}


/**
 * Cloth sway for a cape: a chain of {@link #SEGMENTS} hinged strips, each driven by a spring-damper toward
 * the angle the motion asks for (lean back when moving forward, lift when falling, swing sideways when
 * strafing or turning, a light wind ripple). Lower segments are softer, so the hem trails the shoulders.
 * Integrated in fixed sub-steps so any frame rate (or a long stall) stays stable. Pure Java.
 */
static final class CapeSim {
    public static final int SEGMENTS = 8;
    static final float MIN_PITCH = (float) Math.toRadians(-4);
    static final float MAX_PITCH = (float) Math.toRadians(115);
    static final float MAX_ROLL = (float) Math.toRadians(35);
    private static final float SUBSTEP = 1f / 120f;

    /** Absolute angle of each segment from hanging straight down: + = swung back, away from the body. */
    private final float[] pitch = new float[SEGMENTS];
    private final float[] pitchVel = new float[SEGMENTS];
    private final float[] roll = new float[SEGMENTS];
    private final float[] rollVel = new float[SEGMENTS];
    private float time;
    /** Last time this sim was stepped, for pruning per-entity sims. */
    public long lastUsedNanos;

    public CapeSim() {
        float rest = (float) Math.toRadians(6);
        for (int i = 0; i < SEGMENTS; i++) pitch[i] = rest;
    }

    /**
     * Motion in blocks per tick relative to the body (forward is where the body faces), turn rate in degrees per
     * second.
     */
    public record Motion(float forward, float side, float vertical, float yawRate, boolean sneaking) {
        public static final Motion REST = new Motion(0, 0, 0, 0, false);
    }

    public void step(float dt, Motion m) {
        if (!(dt > 0f)) return;
        dt = Math.min(dt, 0.25f);
        Motion motion = sanitize(m);
        while (dt > 1e-6f) {
            float h = Math.min(SUBSTEP, dt);
            substep(h, motion);
            dt -= h;
        }
    }

    private static Motion sanitize(Motion m) {
        if (m == null) return Motion.REST;
        return new Motion(finite(m.forward(), 2f), finite(m.side(), 2f), finite(m.vertical(), 4f), finite(m.yawRate(), 2000f), m.sneaking());
    }

    private static float finite(float v, float limit) {
        return Float.isFinite(v) ? Math.max(-limit, Math.min(limit, v)) : 0f;
    }

    private void substep(float h, Motion m) {
        time += h;
        if (time > 3600f) time -= 3600f;
        float speed = (float) Math.hypot(m.forward(), m.side());
        float lean = 6f + Math.max(0f, m.forward()) * 165f - Math.max(0f, -m.forward()) * 20f;
        float lift = Math.max(0f, -m.vertical()) * 115f;
        float rootDeg = clampF(lean + lift + (m.sneaking() ? 18f : 0f), -4f, 100f);
        float rollDeg = clampF(-m.side() * 140f - m.yawRate() * 0.045f, -30f, 30f);
        float wind = 1.2f + speed * 26f + Math.max(0f, -m.vertical()) * 10f;

        for (int i = 0; i < SEGMENTS; i++) {
            float t = i / (float) (SEGMENTS - 1);
            float parent = i == 0 ? (float) Math.toRadians(rootDeg) : pitch[i - 1];
            // Each segment bends a little further than its parent, more when moving fast.
            float bend = (float) Math.toRadians((0.6f + speed * 9f) * (0.4f + t))
                    + (float) Math.toRadians(wind * (float) Math.sin(time * 5.1f - i * 0.75f) * (0.25f + 0.75f * t) * 0.35f);
            float target = i == 0 ? parent : parent + bend;
            float k = 260f - 150f * t;
            float c = 2f * (float) Math.sqrt(k) * 0.62f;
            pitchVel[i] += (k * (target - pitch[i]) - c * pitchVel[i]) * h;
            pitch[i] += pitchVel[i] * h;
            if (pitch[i] < MIN_PITCH) { pitch[i] = MIN_PITCH; if (pitchVel[i] < 0) pitchVel[i] = 0; }
            if (pitch[i] > MAX_PITCH) { pitch[i] = MAX_PITCH; if (pitchVel[i] > 0) pitchVel[i] = 0; }

            float rollParent = i == 0 ? (float) Math.toRadians(rollDeg) : roll[i - 1];
            float rollTarget = rollParent * (i == 0 ? 1f : 1.04f)
                    + (float) Math.toRadians(wind * 0.25f * (float) Math.sin(time * 3.3f + i * 0.5f) * t);
            float kr = 180f - 90f * t;
            float cr = 2f * (float) Math.sqrt(kr) * 0.7f;
            rollVel[i] += (kr * (rollTarget - roll[i]) - cr * rollVel[i]) * h;
            roll[i] += rollVel[i] * h;
            if (roll[i] > MAX_ROLL) { roll[i] = MAX_ROLL; if (rollVel[i] > 0) rollVel[i] = 0; }
            if (roll[i] < -MAX_ROLL) { roll[i] = -MAX_ROLL; if (rollVel[i] < 0) rollVel[i] = 0; }
        }
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Copies the current segment angles (radians) into the given arrays (length {@link #SEGMENTS}). */
    public void read(float[] pitchOut, float[] rollOut) {
        System.arraycopy(pitch, 0, pitchOut, 0, SEGMENTS);
        System.arraycopy(roll, 0, rollOut, 0, SEGMENTS);
    }

    /** The cape hanging still: a slight lean, or more when sneaking. */
    public static void staticPose(boolean sneaking, float[] pitchOut, float[] rollOut) {
        float base = (float) Math.toRadians(sneaking ? 24 : 6);
        for (int i = 0; i < SEGMENTS; i++) {
            pitchOut[i] = base + (float) Math.toRadians(0.6f * i);
            rollOut[i] = 0f;
        }
    }
}


/**
 * "Import image" for the cape maker. Uses the native file dialog that ships with the game (the same approach as the
 * schematic picker), reads the picture into memory, and immediately reduces it to the compact
 * {@link CapeImageCodec} text. The file is only read where it is; nothing is copied or cached on disk.
 */
static final class CapeImageImport {
    private static final AtomicBoolean OPEN = new AtomicBoolean();
    /** When the current dialog opened; a dialog stuck longer than PICKER_TIMEOUT_MS no longer blocks a new one. */
    private static volatile long openedAt;
    private static final long PICKER_TIMEOUT_MS = 120_000L;
    private static final long MAX_FILE_BYTES = 16L << 20;
    private static final String[] PATTERNS = {"*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"};

    private CapeImageImport() { }

    /** Opens the picker; exactly one callback runs on the client thread (none if the user cancels). */
    public static void pick(Consumer<String> onEncoded, Consumer<String> onError) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        if (MobilePlatform.isMobile()) {
            onError.accept("Image import needs a desktop file picker.");
            return;
        }
        if (!OPEN.compareAndSet(false, true)) {
            // A native dialog that never returns (no zenity/kdialog, a hung portal) must not lock the picker forever.
            if (System.currentTimeMillis() - openedAt < PICKER_TIMEOUT_MS) return;
        }
        openedAt = System.currentTimeMillis();
        Runnable job = () -> {
            String encoded = null, error = null;
            boolean cancelled = false;
            try {
                String raw;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer filters = stack.mallocPointer(PATTERNS.length);
                    for (String p : PATTERNS) filters.put(stack.UTF8(p));
                    filters.flip();
                    raw = TinyFileDialogs.tinyfd_openFileDialog("Choose a cape image", "", filters, "Images", false);
                }
                if (raw == null || raw.isBlank()) {
                    cancelled = true;
                } else {
                    Path path = Path.of(raw.trim());
                    if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
                        error = "That file is missing or larger than 16 MB.";
                    } else {
                        int[][] out = new int[1][];
                        int[] size = new int[2];
                        if (!decode(Files.readAllBytes(path), out, size)) error = "That image format could not be read (use PNG or JPG).";
                        else {
                            encoded = CapeImageCodec.encode(out[0], size[0], size[1]);
                            if (encoded == null) error = "The image could not be converted.";
                        }
                    }
                }
            } catch (Throwable t) {
                error = "The file picker could not open (" + t.getClass().getSimpleName() + ").";
            } finally {
                OPEN.set(false);
            }
            String result = encoded, reason = error;
            boolean skip = cancelled;
            mc.execute(() -> {
                if (skip) return;
                if (reason != null) onError.accept(reason);
                else onEncoded.accept(result);
            });
        };
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            job.run();
            return;
        }
        try {
            Thread worker = new Thread(job, "Apexx-Cape-Picker");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            OPEN.set(false);
            onError.accept("The file picker could not start.");
        }
    }

    private static final long MAX_IMPORT_PIXELS = 16L << 20;

    /** Decodes common formats into ARGB pixels; ImageIO first, the game's PNG reader as a fallback. */
    private static boolean decode(byte[] bytes, int[][] out, int[] size) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0 && (long) img.getWidth() * img.getHeight() <= MAX_IMPORT_PIXELS) {
                int w = img.getWidth(), h = img.getHeight();
                out[0] = img.getRGB(0, 0, w, h, null, 0, w);
                size[0] = w;
                size[1] = h;
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to the PNG reader
        }
        try (net.minecraft.client.texture.NativeImage img = net.minecraft.client.texture.NativeImage.read(new ByteArrayInputStream(bytes))) {
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0 || (long) w * h > MAX_IMPORT_PIXELS) return false;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = img.getColorArgb(x, y);
            out[0] = px;
            size[0] = w;
            size[1] = h;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}


/**
 * Sharing cosmetics between Apexx users through {@code POST /api/cosmetics}.
 *
 * <ul>
 *   <li><b>Publishing</b> (opt-in, off by default): the selection only (cape design, hat, wings), keyed by the
 *   Minecraft UUID. The first claim of a UUID is proven with Mojang's session join, exactly like joining an
 *   online-mode server, so nobody can dress up someone else's account.</li>
 *   <li><b>Viewing</b>: UUIDs of players being rendered are collected and looked up in batches of at most
 *   {@link #BATCH}, no more often than every {@link #FETCH_INTERVAL_MS}; answers (including "none") are cached.</li>
 * </ul>
 * All network work runs on one daemon thread; the render thread only reads the cache. Nothing is written to disk.
 */
static final class CosmeticsSync {
    private static final String ENDPOINT = "https://example.invalid/api/cosmetics";
    static final int BATCH = 48;
    private static final long FETCH_INTERVAL_MS = 15_000;
    private static final long FOUND_TTL_MS = 10 * 60_000;
    private static final long MISSING_TTL_MS = 5 * 60_000;
    private static final long SEEN_WINDOW_MS = 30_000;
    private static final long PUBLISH_DEBOUNCE_MS = 4_000;
    private static final long PUBLISH_MIN_GAP_MS = 10_000;
    private static final long REPUBLISH_MS = 6 * 60 * 60_000L;
    private static final int MAX_CACHE = 2048;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Apexx-Cosmetics");
        t.setDaemon(true);
        return t;
    });

    private record Cached(CosmeticSelection selection, long fetchedAt) { }

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SEEN = new ConcurrentHashMap<>();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long lastFetchAt;
    private static volatile long lastPumpAt;
    private static volatile long lastPublishAt;
    private static volatile int publishedVersion = Integer.MIN_VALUE;
    private static volatile int seenVersion = Integer.MIN_VALUE;
    private static volatile long versionChangedAt;
    private static volatile long publishBackoffUntil;
    private static volatile String status = "";

    private CosmeticsSync() { }

    /** Human-readable state of sharing for the Cosmetics tab. */
    public static String status() {
        return status;
    }

    /** A remote player's cosmetics, or null when unknown / none. Marks the player as wanted. */
    public static CosmeticSelection lookup(UUID uuid) {
        if (uuid == null) return null;
        long now = System.currentTimeMillis();
        SEEN.put(uuid, now);
        Cached c = CACHE.get(uuid);
        return c == null ? null : c.selection();
    }

    /** Called from the render thread; cheap, throttled to once a second. */
    public static void pump() {
        long now = System.currentTimeMillis();
        if (now - lastPumpAt < 1000) return;
        lastPumpAt = now;
        if (BUSY.get()) return;
        CosmeticPrefs prefs = CosmeticPrefs.get();
        boolean signedIn = ApexxAuth.getSessionToken() != null;
        if (!signedIn) return;

        // Publishing first: a pending change or removal of the user's own record.
        int version = prefs.version();
        if (version != seenVersion) {
            seenVersion = version;
            versionChangedAt = now;
        }
        boolean wantPublished = prefs.shareMine();
        boolean needPublish = wantPublished
                && (version != publishedVersion || now - lastPublishAt > REPUBLISH_MS)
                && now - versionChangedAt >= PUBLISH_DEBOUNCE_MS;
        boolean needRemove = !wantPublished && prefs.published();
        if ((needPublish || needRemove) && now - lastPublishAt >= PUBLISH_MIN_GAP_MS && now >= publishBackoffUntil) {
            Session session = session();
            if (session != null && session.getUuidOrNull() != null) {
                CosmeticSelection sel = prefs.selection();
                int ver = version;
                submit(() -> publish(session, wantPublished, sel, ver));
                return;
            }
        }

        // Then lookups for players being rendered.
        if (!prefs.showOthers() || now - lastFetchAt < FETCH_INTERVAL_MS) return;
        List<UUID> wanted = new ArrayList<>();
        UUID self = selfUuid();
        SEEN.entrySet().removeIf(e -> now - e.getValue() > SEEN_WINDOW_MS);
        for (UUID uuid : SEEN.keySet()) {
            if (uuid.equals(self)) continue;
            Cached c = CACHE.get(uuid);
            long ttl = c == null ? 0 : c.selection() == null ? MISSING_TTL_MS : FOUND_TTL_MS;
            if (c == null || now - c.fetchedAt() > ttl) wanted.add(uuid);
            if (wanted.size() >= BATCH) break;
        }
        if (wanted.isEmpty()) return;
        lastFetchAt = now;
        submit(() -> fetch(wanted));
    }

    /** Forget everything fetched (e.g. after the user turns viewing off). */
    public static void clearCache() {
        CACHE.clear();
        SEEN.clear();
    }

    private static void submit(Runnable job) {
        if (!BUSY.compareAndSet(false, true)) return;
        try {
            WORKER.execute(() -> {
                try {
                    job.run();
                } catch (Throwable ignored) {
                    // network or parse failure: retried on a later pump
                } finally {
                    BUSY.set(false);
                }
            });
        } catch (Throwable rejected) {
            BUSY.set(false);
        }
    }

    // ------------------------------------------------------------------ publish

    private static void publish(Session session, boolean visible, CosmeticSelection sel, int version) {
        lastPublishAt = System.currentTimeMillis();
        CosmeticPrefs prefs = CosmeticPrefs.get();
        UUID uuid = session.getUuidOrNull();
        JsonObject body = new JsonObject();
        body.addProperty("action", "set");
        body.addProperty("mcUuid", undashed(uuid));
        body.addProperty("username", session.getUsername());
        body.addProperty("visible", visible);
        if (visible) body.add("cosmetics", sel.toJson());
        JsonObject resp = post(body);
        String reason = reason(resp);
        if (visible && "NEEDS_PROOF".equals(reason)) {
            // First claim of this account: prove it is ours through Mojang's session join.
            JsonObject claim = new JsonObject();
            claim.addProperty("action", "claim");
            claim.addProperty("mcUuid", undashed(uuid));
            JsonObject challenge = post(claim);
            String serverId = challenge != null && ok(challenge) && challenge.has("serverId") ? challenge.get("serverId").getAsString() : null;
            if (serverId == null) {
                fail("Could not start account verification (" + reason(challenge) + ").");
                return;
            }
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.getApiServices().sessionService().joinServer(uuid, session.getAccessToken(), serverId);
            } catch (Throwable t) {
                fail("Minecraft account could not be verified (offline accounts cannot share cosmetics).");
                return;
            }
            body.addProperty("proof", true);
            resp = post(body);
            reason = reason(resp);
        }
        if (resp != null && ok(resp)) {
            publishedVersion = version;
            publishBackoffUntil = 0;
            prefs.setPublished(visible);
            status = visible ? "Shared: other Apexx users see your cosmetics." : "Not shared.";
            CACHE.remove(uuid);
            return;
        }
        fail(switch (reason) {
            case "CLAIMED" -> "This Minecraft account is linked to another Apexx account.";
            case "TOO_MANY" -> "Too many Minecraft accounts are sharing from this Apexx account.";
            case "RATE_LIMITED" -> "Too many changes; sharing again shortly.";
            case "NOT_VERIFIED" -> "Minecraft account could not be verified.";
            case "INVALID_TOKEN" -> "Sign in again to share cosmetics.";
            case "PREMIUM_REQUIRED" -> "Sharing cosmetics needs Premium.";
            default -> "Could not reach the cosmetics service (" + reason + ").";
        });
    }

    private static void fail(String message) {
        status = message;
        publishBackoffUntil = System.currentTimeMillis() + 60_000;
    }

    // ------------------------------------------------------------------ fetch

    private static void fetch(List<UUID> uuids) {
        JsonObject body = new JsonObject();
        body.addProperty("action", "getBatch");
        JsonArray arr = new JsonArray();
        for (UUID u : uuids) arr.add(undashed(u));
        body.add("uuids", arr);
        JsonObject resp = post(body);
        if (resp == null || !ok(resp)) return;
        long now = System.currentTimeMillis();
        JsonObject found = resp.has("cosmetics") && resp.get("cosmetics").isJsonObject() ? resp.getAsJsonObject("cosmetics") : new JsonObject();
        if (CACHE.size() > MAX_CACHE) CACHE.clear();
        for (UUID u : uuids) {
            JsonElement e = found.get(undashed(u));
            CosmeticSelection sel = e == null ? null : CosmeticSelection.fromJson(e);
            CACHE.put(u, new Cached(sel == null || sel.isEmpty() ? null : sel, now));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static JsonObject post(JsonObject body) {
        String token = ApexxAuth.getSessionToken();
        if (token == null) return null;
        body.addProperty("token", token);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonElement el = JsonParser.parseString(resp.body());
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean ok(JsonObject o) {
        try {
            return o != null && o.has("ok") && o.get("ok").getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String reason(JsonObject o) {
        try {
            if (o != null && o.has("reason") && o.get("reason").isJsonPrimitive()) return o.get("reason").getAsString();
        } catch (Throwable ignored) {
        }
        return o == null ? "network error" : "rejected";
    }

    static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static Session session() {
        try {
            return MinecraftClient.getInstance().getSession();
        } catch (Throwable t) {
            return null;
        }
    }

    private static UUID selfUuid() {
        Session s = session();
        return s == null ? null : s.getUuidOrNull();
    }
}


/**
 * In-memory cape textures, one per distinct design, generated on the render thread and kept in a small LRU
 * (evicted textures are destroyed). Nothing is written to disk.
 */
static final class CosmeticTextures {
    private static final int MAX_TEXTURES = 48;
    /** New textures generated per frame at most, so a crowd of capes never stalls one frame. */
    private static final int MAX_NEW_PER_FRAME = 2;
    private static final Map<String, Identifier> CAPES = new LinkedHashMap<>(16, 0.75f, true);
    private static final Identifier WHITE = Identifier.of(Apexx.MOD_ID, "generated/cosmetics_white");
    private static int counter;
    private static long frameMark;
    private static int madeThisFrame;
    private static boolean whiteReady;

    private CosmeticTextures() { }

    /** Texture for the design, or null while it is not generated yet (render thread only). */
    public static Identifier cape(CapeDesign design) {
        if (design == null) return null;
        String key = design.cacheKey();
        Identifier id = CAPES.get(key);
        if (id != null) return id;
        long frame = System.nanoTime() / 16_000_000L;
        if (frame != frameMark) {
            frameMark = frame;
            madeThisFrame = 0;
        }
        if (madeThisFrame >= MAX_NEW_PER_FRAME) return null;
        madeThisFrame++;
        try {
            int[] px = CapeArt.render(design);
            NativeImage image = new NativeImage(CapeArt.WIDTH, CapeArt.HEIGHT, true);
            for (int y = 0; y < CapeArt.HEIGHT; y++) {
                for (int x = 0; x < CapeArt.WIDTH; x++) image.setColorArgb(x, y, px[y * CapeArt.WIDTH + x]);
            }
            id = Identifier.of(Apexx.MOD_ID, "generated/cosmetics_cape_" + (counter++));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "apexx_cosmetic_cape", image);
            texture.upload();
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            CAPES.put(key, id);
            evict();
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void evict() {
        if (CAPES.size() <= MAX_TEXTURES) return;
        Iterator<Map.Entry<String, Identifier>> it = CAPES.entrySet().iterator();
        while (CAPES.size() > MAX_TEXTURES && it.hasNext()) {
            Identifier old = it.next().getValue();
            it.remove();
            CosmeticLayers.forget(old);
            try {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
            } catch (Throwable ignored) {
            }
        }
    }

    /** A plain white texture for untextured (vertex-coloured) hat geometry. */
    public static Identifier white() {
        if (!whiteReady) {
            try {
                NativeImage image = new NativeImage(4, 4, true);
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) image.setColorArgb(x, y, 0xFFFFFFFF);
                NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "apexx_cosmetic_white", image);
                texture.upload();
                MinecraftClient.getInstance().getTextureManager().registerTexture(WHITE, texture);
                whiteReady = true;
            } catch (Throwable ignored) {
            }
        }
        return WHITE;
    }
}


/**
 * Opaque, depth-writing, lit entity layers for cosmetic geometry (cape cloth, hats). Triangles in the full entity
 * vertex format; culling off so thin geometry shows from both sides.
 */
static final class CosmeticLayers {
    private static final Identifier ENTITY_SHADER = Identifier.of("minecraft", "core/entity");
    private static final RenderPipeline SOLID = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.of(Apexx.MOD_ID, "apexx_cosmetic_solid"))
                    .withSampler("Sampler1") // entity.vsh reads overlay (Sampler1); Sampler0/2, Fog and Lighting come from ENTITY_SNIPPET
                    .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                    .withCull(false)
                    .build());
    private static final Map<Identifier, RenderLayer> LAYERS = new ConcurrentHashMap<>();

    private CosmeticLayers() { }

    /** Drops the cached layer of an evicted texture so layers do not accumulate forever. */
    static void forget(Identifier texture) {
        LAYERS.remove(texture);
    }

    /** Solid textured layer, nearest-filtered so pixel-art capes stay crisp. */
    public static RenderLayer solid(Identifier texture) {
        return LAYERS.computeIfAbsent(texture, t -> RenderLayer.of("apexx_cosmetic_" + Integer.toHexString(t.hashCode()),
                RenderSetup.builder(SOLID)
                        .texture("Sampler0", t, () -> RenderSystem.getSamplerCache().get(FilterMode.NEAREST))
                        .useLightmap()
                        .useOverlay()
                        .expectedBufferSize(8192)
                        .build()));
    }

    /** Vertex-coloured geometry (hats) on a white texture. */
    public static RenderLayer flat() {
        return solid(CosmeticTextures.white());
    }
}


/**
 * Flat-shaded triangle emitter for procedural cosmetic geometry in the entity vertex format. Each triangle gets
 * its face normal, so the entity shader lights it like a block model. Coordinates are in the space of the
 * matrix entry (model units, y down inside a player model).
 */
static final class Mesh {
    private final MatrixStack.Entry entry;
    private final VertexConsumer buf;
    private final int light;
    private double centerX, centerY, centerZ;

    Mesh(MatrixStack.Entry entry, VertexConsumer buf, int light) {
        this.entry = entry;
        this.buf = buf;
        this.light = light;
    }

    /** Centre of the shape being emitted; face normals point away from it. */
    Mesh center(double x, double y, double z) {
        centerX = x;
        centerY = y;
        centerZ = z;
        return this;
    }

    void tri(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, int argb) {
        double ux = bx - ax, uy = by - ay, uz = bz - az, vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nx = (float) (uy * vz - uz * vy), ny = (float) (uz * vx - ux * vz), nz = (float) (ux * vy - uy * vx);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9f) return;
        nx /= len; ny /= len; nz /= len;
        // Culling is off, so winding is free: point every normal away from the shape's centre instead.
        double mx = (ax + bx + cx) / 3 - centerX, my = (ay + by + cy) / 3 - centerY, mz = (az + bz + cz) / 3 - centerZ;
        if (nx * mx + ny * my + nz * mz < 0) { nx = -nx; ny = -ny; nz = -nz; }
        v(ax, ay, az, 0f, 0f, argb, nx, ny, nz);
        v(bx, by, bz, 1f, 0f, argb, nx, ny, nz);
        v(cx, cy, cz, 1f, 1f, argb, nx, ny, nz);
    }

    void quad(double[] a, double[] b, double[] c, double[] d, int argb) {
        tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], argb);
        tri(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], argb);
    }

    /** Textured quad with explicit uv and normal (used for the cape cloth). */
    void texQuad(double[] a, double[] b, double[] c, double[] d, float u0, float v0, float u1, float v1, int argb,
                 float nx, float ny, float nz) {
        v(a[0], a[1], a[2], u0, v0, argb, nx, ny, nz);
        v(b[0], b[1], b[2], u1, v0, argb, nx, ny, nz);
        v(c[0], c[1], c[2], u1, v1, argb, nx, ny, nz);
        v(a[0], a[1], a[2], u0, v0, argb, nx, ny, nz);
        v(c[0], c[1], c[2], u1, v1, argb, nx, ny, nz);
        v(d[0], d[1], d[2], u0, v1, argb, nx, ny, nz);
    }

    void v(double x, double y, double z, float u, float vv, int argb, float nx, float ny, float nz) {
        buf.vertex(entry, (float) x, (float) y, (float) z)
                .color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f)
                .texture(u, vv)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(entry, nx, ny, nz);
    }

    /** Tapered tube along a polyline (rings of {@code sides} vertices), closed at the far end. */
    void tube(double[][] path, double[] radii, int sides, int[] colors) {
        int n = path.length;
        double[][][] rings = new double[n][sides][];
        double px = 0, py = 0, pz = 1; // reference vector for a stable frame
        for (int i = 0; i < n; i++) {
            double[] p = path[i];
            double[] q = path[Math.min(n - 1, i + 1)], o = path[Math.max(0, i - 1)];
            double tx = q[0] - o[0], ty = q[1] - o[1], tz = q[2] - o[2];
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl < 1e-9) { tx = 0; ty = -1; tz = 0; tl = 1; }
            tx /= tl; ty /= tl; tz /= tl;
            if (Math.abs(tx * px + ty * py + tz * pz) > 0.95) { px = 1; py = 0; pz = 0; }
            double ux = ty * pz - tz * py, uy = tz * px - tx * pz, uz = tx * py - ty * px;
            double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= ul; uy /= ul; uz /= ul;
            double wx = ty * uz - tz * uy, wy = tz * ux - tx * uz, wz = tx * uy - ty * ux;
            for (int s = 0; s < sides; s++) {
                double a = Math.PI * 2 * s / sides;
                double cs = Math.cos(a) * radii[i], sn = Math.sin(a) * radii[i];
                rings[i][s] = new double[]{p[0] + ux * cs + wx * sn, p[1] + uy * cs + wy * sn, p[2] + uz * cs + wz * sn};
            }
        }
        double keepX = centerX, keepY = centerY, keepZ = centerZ;
        for (int i = 0; i < n - 1; i++) {
            int color = colors[Math.min(colors.length - 1, i)];
            // Normals of a tube point away from its own axis.
            center((path[i][0] + path[i + 1][0]) * 0.5, (path[i][1] + path[i + 1][1]) * 0.5, (path[i][2] + path[i + 1][2]) * 0.5);
            for (int s = 0; s < sides; s++) {
                int s1 = (s + 1) % sides;
                quad(rings[i][s], rings[i][s1], rings[i + 1][s1], rings[i + 1][s], color);
            }
        }
        double[] end = path[n - 1];
        int endColor = colors[colors.length - 1];
        double[] before = path[Math.max(0, n - 2)];
        center(before[0], before[1], before[2]);
        for (int s = 0; s < sides; s++) {
            int s1 = (s + 1) % sides;
            tri(rings[n - 1][s][0], rings[n - 1][s][1], rings[n - 1][s][2], rings[n - 1][s1][0], rings[n - 1][s1][1], rings[n - 1][s1][2],
                    end[0], end[1], end[2], endColor);
        }
        center(keepX, keepY, keepZ);
    }

    /** Vertical cylinder (y axis), top at {@code y0}, bottom at {@code y1}, with caps. */
    void cylinder(double cx, double y0, double y1, double cz, double r0, double r1, int sides, int side, int cap) {
        double keepX = centerX, keepY = centerY, keepZ = centerZ;
        center(cx, (y0 + y1) * 0.5, cz);
        for (int s = 0; s < sides; s++) {
            double a0 = Math.PI * 2 * s / sides, a1 = Math.PI * 2 * (s + 1) / sides;
            double[] t0 = {cx + Math.cos(a0) * r0, y0, cz + Math.sin(a0) * r0}, t1 = {cx + Math.cos(a1) * r0, y0, cz + Math.sin(a1) * r0};
            double[] b0 = {cx + Math.cos(a0) * r1, y1, cz + Math.sin(a0) * r1}, b1 = {cx + Math.cos(a1) * r1, y1, cz + Math.sin(a1) * r1};
            quad(t0, t1, b1, b0, side);
            if (cap != 0) {
                tri(cx, y0, cz, t0[0], t0[1], t0[2], t1[0], t1[1], t1[2], cap);
                tri(cx, y1, cz, b1[0], b1[1], b1[2], b0[0], b0[1], b0[2], cap);
            }
        }
        center(keepX, keepY, keepZ);
    }

    /** Small octahedron (gem). */
    void gem(double x, double y, double z, double r, int color) {
        double keepX = centerX, keepY = centerY, keepZ = centerZ;
        center(x, y, z);
        gemFaces(x, y, z, r, color);
        center(keepX, keepY, keepZ);
    }

    private void gemFaces(double x, double y, double z, double r, int color) {
        double[][] p = {{x + r, y, z}, {x, y, z + r}, {x - r, y, z}, {x, y, z - r}};
        double[] top = {x, y - r * 1.3, z}, bottom = {x, y + r * 1.3, z};
        for (int i = 0; i < 4; i++) {
            double[] a = p[i], b = p[(i + 1) % 4];
            tri(top[0], top[1], top[2], a[0], a[1], a[2], b[0], b[1], b[2], color);
            tri(bottom[0], bottom[1], bottom[2], b[0], b[1], b[2], a[0], a[1], a[2], color);
        }
    }
}


/**
 * Procedural hat geometry in head space: origin at the neck pivot, the head cube spans x/z -0.25..0.25 and y
 * -0.5..0 (y down), the face looks toward -z. Everything is built from triangles at render time; no models or
 * textures ship with the client.
 */
static final class HatModels {
    static final double TOP = -0.5;
    private static final double TAU = Math.PI * 2;

    private HatModels() { }

    /** True when the hat has an additive glow pass. */
    static boolean glows(String id) {
        return "halo".equals(id) || "nimbus".equals(id);
    }

    /** True when the solid part should ignore world light (emissive). */
    static boolean emissive(String id) {
        return "halo".equals(id);
    }

    static void solid(Mesh m, String id, int color, int accent, float time) {
        m.center(0, -0.25, 0);
        switch (id) {
            case "halo" -> halo(m, color, time);
            case "crown" -> crown(m, color);
            case "horns" -> horns(m, color);
            case "cat_ears" -> ears(m, color, 0xFFF7A1B5, 0.06, 0.22, 0.17, -0.70, color, 1.0);
            case "fox_ears" -> ears(m, color, 0xFFFFF3E6, 0.04, 0.24, 0.15, -0.80, 0xFFFFFFFF, 0.68);
            case "bunny_ears" -> bunny(m, color);
            case "top_hat" -> topHat(m, color, accent);
            case "wizard_hat" -> wizardHat(m, color);
            default -> { }
        }
    }

    static void glow(VertexConsumer buf, Matrix4f mat, String id, int color, float time) {
        if ("halo".equals(id)) {
            double y = haloY(time);
            double half = 0.27 / 0.8;
            int c = VisualGeometry.fade(color | 0xFF000000, 0.85f);
            VisualGeometry.quad(buf, mat, -half, y, -half, half, y, -half, half, y, half, -half, y, half,
                    0f, 0f, 1f, 1f, c, c, c, c, VisualGeometry.FULL_BRIGHT);
        } else if ("nimbus".equals(id)) {
            nimbus(buf, mat, color, time);
        }
    }

    // ------------------------------------------------------------------ halo / nimbus

    private static double haloY(float time) {
        return -0.70 + Math.sin(time * 2.1) * 0.015;
    }

    private static void halo(Mesh m, int color, float time) {
        double y = haloY(time);
        int major = 32, minor = 6;
        double big = 0.27, tube = 0.02;
        int bright = mix(color, 0xFFFFFFFF, 0.35f);
        for (int i = 0; i < major; i++) {
            double a0 = TAU * i / major, a1 = TAU * (i + 1) / major;
            m.center(Math.cos((a0 + a1) / 2) * big, y, Math.sin((a0 + a1) / 2) * big);
            for (int j = 0; j < minor; j++) {
                double b0 = TAU * j / minor, b1 = TAU * (j + 1) / minor;
                m.quad(torus(a0, b0, big, tube, y), torus(a1, b0, big, tube, y), torus(a1, b1, big, tube, y), torus(a0, b1, big, tube, y),
                        (j & 1) == 0 ? bright : color);
            }
        }
    }

    private static double[] torus(double a, double b, double big, double tube, double y) {
        double r = big + Math.cos(b) * tube;
        return new double[]{Math.cos(a) * r, y + Math.sin(b) * tube, Math.sin(a) * r};
    }

    private static void nimbus(VertexConsumer buf, Matrix4f mat, int color, float time) {
        int arms = 2, segments = 17;
        double radius = 0.34, y = -0.72 + Math.sin(time * 2.1) * 0.015;
        for (int arm = 0; arm < arms; arm++) {
            double base = time * 2.967 + arm * Math.PI;
            for (int s = 0; s < segments; s++) {
                double a = base - s * 0.11;
                double x = Math.cos(a) * radius, z = Math.sin(a) * radius;
                float progress = s / (float) (segments - 1);
                double half = 0.16 * (1.0 - progress * 0.65);
                int alpha = Math.max(0, 255 - s * 12);
                int c = (alpha << 24) | (color & 0xFFFFFF);
                // Three crossed planes read as a soft orb from any view.
                VisualGeometry.quad(buf, mat, x - half, y - half, z, x + half, y - half, z, x + half, y + half, z, x - half, y + half, z,
                        0f, 0f, 1f, 1f, c, c, c, c, VisualGeometry.FULL_BRIGHT);
                VisualGeometry.quad(buf, mat, x, y - half, z - half, x, y - half, z + half, x, y + half, z + half, x, y + half, z - half,
                        0f, 0f, 1f, 1f, c, c, c, c, VisualGeometry.FULL_BRIGHT);
                VisualGeometry.quad(buf, mat, x - half, y, z - half, x + half, y, z - half, x + half, y, z + half, x - half, y, z + half,
                        0f, 0f, 1f, 1f, c, c, c, c, VisualGeometry.FULL_BRIGHT);
            }
        }
    }

    // ------------------------------------------------------------------ crown

    private static void crown(Mesh m, int gold) {
        double r = 0.285, y0 = TOP + 0.03, y1 = TOP - 0.07;
        int dark = shade(gold, 0.72f);
        m.cylinder(0, y1, y0, 0, r, r, 24, gold, 0);
        // Inner wall so the band has thickness from above.
        m.cylinder(0, y1, y0, 0, r - 0.02, r - 0.02, 24, dark, 0);
        int points = 8;
        for (int i = 0; i < points; i++) {
            double a = TAU * i / points, half = Math.PI / points;
            double[] l = {Math.cos(a - half) * r, y1, Math.sin(a - half) * r};
            double[] rr = {Math.cos(a + half) * r, y1, Math.sin(a + half) * r};
            double[] apex = {Math.cos(a) * (r - 0.012), y1 - 0.15, Math.sin(a) * (r - 0.012)};
            double[] li = {l[0] * 0.93, y1, l[2] * 0.93}, ri = {rr[0] * 0.93, y1, rr[2] * 0.93};
            m.center(0, y1, 0);
            m.tri(l[0], l[1], l[2], rr[0], rr[1], rr[2], apex[0], apex[1], apex[2], gold);
            m.tri(li[0], li[1], li[2], ri[0], ri[1], ri[2], apex[0], apex[1], apex[2], dark);
            m.gem(apex[0], apex[1] - 0.012, apex[2], 0.026, (i & 1) == 0 ? 0xFFE53935 : 0xFF1E88E5);
        }
        // Three jewels across the front of the band.
        for (int i = -1; i <= 1; i++) {
            double a = -Math.PI / 2 + i * 0.42;
            m.gem(Math.cos(a) * (r + 0.008), (y0 + y1) * 0.5, Math.sin(a) * (r + 0.008), 0.022, i == 0 ? 0xFF43A047 : 0xFFE53935);
        }
    }

    // ------------------------------------------------------------------ horns

    private static void horns(Mesh m, int color) {
        for (int side = -1; side <= 1; side += 2) {
            double[][] ctrl = {{side * 0.15, TOP + 0.03, -0.08}, {side * 0.27, TOP - 0.14, -0.04}, {side * 0.30, TOP - 0.24, 0.12},
                    {side * 0.24, TOP - 0.27, 0.27}};
            int n = 12;
            double[][] path = new double[n + 1][];
            double[] radii = new double[n + 1];
            int[] colors = new int[n];
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                path[i] = bezier(ctrl, t);
                radii[i] = 0.06 * (1 - t) + 0.002;
                if (i < n) colors[i] = mix(color, 0xFFE9D9B8, (float) (t * t) * 0.55f);
            }
            m.tube(path, radii, 7, colors);
        }
    }

    private static double[] bezier(double[][] p, double t) {
        double u = 1 - t;
        double a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
        return new double[]{a * p[0][0] + b * p[1][0] + c * p[2][0] + d * p[3][0],
                a * p[0][1] + b * p[1][1] + c * p[2][1] + d * p[3][1],
                a * p[0][2] + b * p[1][2] + c * p[2][2] + d * p[3][2]};
    }

    // ------------------------------------------------------------------ ears

    /**
     * Two pointed ears: a four-sided pyramid per ear on top of the head, the part above {@code tipFrac} coloured
     * {@code tipColor}, with a lighter inner face toward the front.
     */
    private static void ears(Mesh m, int color, int inner, double xIn, double xOut, double apexX, double apexY, int tipColor,
                             double tipFrac) {
        for (int side = -1; side <= 1; side += 2) {
            double base = TOP + 0.02, z0 = -0.09, z1 = 0.0, apexZ = -0.03;
            double[] a = {side * xIn, base, z0}, b = {side * xOut, base, z0}, c = {side * xOut, base, z1}, d = {side * xIn, base, z1};
            double[] apex = {side * apexX, apexY, apexZ};
            m.center(side * (xIn + xOut) * 0.5, (base + apexY) * 0.5, (z0 + z1) * 0.5);
            double[][] ring = {a, b, c, d};
            double[][] mid = new double[4][];
            for (int i = 0; i < 4; i++) mid[i] = lerp(ring[i], apex, tipFrac);
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                m.quad(ring[i], ring[j], mid[j], mid[i], color);
                if (tipFrac < 1.0) m.tri(mid[i][0], mid[i][1], mid[i][2], mid[j][0], mid[j][1], mid[j][2], apex[0], apex[1], apex[2], tipColor);
                else m.tri(mid[i][0], mid[i][1], mid[i][2], mid[j][0], mid[j][1], mid[j][2], apex[0], apex[1], apex[2], color);
            }
            // Inner fur on the front face.
            double[] ia = lerp(a, apex, 0.12), ib = lerp(b, apex, 0.12);
            ia[2] -= 0.004; ib[2] -= 0.004;
            ia[0] += side * 0.025; ib[0] -= side * 0.025;
            double[] itip = lerp(new double[]{side * (xIn + xOut) * 0.5, base, z0 - 0.004}, apex, 0.72);
            itip[2] = z0 + (apexZ - z0) * 0.72 - 0.006;
            m.tri(ia[0], ia[1], ia[2], ib[0], ib[1], ib[2], itip[0], itip[1], itip[2], inner);
        }
    }

    private static void bunny(Mesh m, int color) {
        for (int side = -1; side <= 1; side += 2) {
            double[][] ctrl = {{side * 0.10, TOP + 0.02, -0.02}, {side * 0.13, TOP - 0.30, 0.0}, {side * 0.20, TOP - 0.52, 0.10},
                    {side * 0.30, TOP - 0.52, 0.28}};
            int n = 12;
            double[][] path = new double[n + 1][];
            double[] radii = new double[n + 1];
            int[] colors = new int[n];
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                path[i] = bezier(ctrl, t);
                radii[i] = 0.038 + 0.03 * Math.sin(Math.PI * Math.min(1, t * 1.15)) - (t > 0.92 ? (t - 0.92) * 0.6 : 0);
                radii[i] = Math.max(0.004, radii[i]);
                if (i < n) colors[i] = color;
            }
            m.tube(path, radii, 8, colors);
            // Pink inner strip just in front.
            double[][] inner = new double[n - 3][];
            double[] innerR = new double[n - 3];
            int[] innerC = new int[n - 4];
            for (int i = 0; i < n - 3; i++) {
                double[] p = path[i + 2];
                inner[i] = new double[]{p[0], p[1], p[2] - radii[i + 2] * 0.55};
                innerR[i] = radii[i + 2] * 0.5;
                if (i < n - 4) innerC[i] = 0xFFF4B6C8;
            }
            m.tube(inner, innerR, 6, innerC);
        }
    }

    // ------------------------------------------------------------------ hats

    private static void topHat(Mesh m, int color, int accent) {
        int under = shade(color, 0.8f);
        m.cylinder(0, TOP - 0.02, TOP + 0.005, 0, 0.40, 0.40, 28, under, color);
        m.cylinder(0, TOP - 0.40, TOP - 0.02, 0, 0.255, 0.245, 24, color, mix(color, 0xFFFFFFFF, 0.08f));
        m.cylinder(0, TOP - 0.11, TOP - 0.025, 0, 0.258, 0.256, 24, accent | 0xFF000000, 0);
    }

    private static void wizardHat(Mesh m, int color) {
        m.cylinder(0, TOP - 0.015, TOP + 0.005, 0, 0.43, 0.43, 28, shade(color, 0.8f), color);
        double[][] ctrl = {{0, TOP - 0.015, 0}, {0, TOP - 0.45, 0.0}, {0.02, TOP - 0.62, 0.12}, {0.12, TOP - 0.66, 0.30}};
        int n = 14;
        double[][] path = new double[n + 1][];
        double[] radii = new double[n + 1];
        int[] colors = new int[n];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            path[i] = bezier(ctrl, t);
            radii[i] = 0.27 * Math.pow(1 - t, 1.25) + 0.002;
            if (i < n) colors[i] = mix(color, 0xFF000000, (float) t * 0.15f);
        }
        m.tube(path, radii, 14, colors);
        m.cylinder(0, TOP - 0.075, TOP - 0.02, 0, 0.262, 0.252, 24, 0xFFF5C542, 0);
        double[] star = bezier(ctrl, 0.35);
        m.gem(star[0], star[1], star[2] - radii[5] - 0.01, 0.03, 0xFFF5C542);
        double[] star2 = bezier(ctrl, 0.6);
        m.gem(star2[0] + 0.06, star2[1], star2[2] - 0.05, 0.022, 0xFFFFF4C2);
    }

    // ------------------------------------------------------------------ math

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static int shade(int argb, float k) {
        return VisualGeometry.shade(argb | 0xFF000000, k);
    }

    static int mix(int a, int b, float t) {
        return VisualGeometry.mix(a | 0xFF000000, b | 0xFF000000, t);
    }
}


/**
 * Angel and dragon wings (formerly the Wings render module), one rig per player. Angel: an articulated arm
 * (humerus, forearm, hand) carrying individually modelled primary, secondary, tertial and covert feathers.
 * Dragon: the same arm with four finger bones and billowing, veined membrane panels. A spring-damped flap model
 * opens them when falling, beats them when rising, folds them when sneaking and trails the arm's motion.
 *
 * <p>Geometry is authored in "wing space" (x outward, y up, z back) and mapped into the player's body space by the
 * caller's matrix.
 */
static final class WingRig {
    private static final float SHOULDER_X = 0.09f;
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final int FEATHER_SEGMENTS = 6;
    private static final int GRID = 8;
    private static final int FINGERS = 4;
    private static final float[] FINGER_LEN = {1.02f, 1.18f, 1.08f, 0.84f};

    private float openSm = 0.5f;
    private float baseFlapSm = 30f;
    private float ampSm = 3f;
    private float freqSm = 0.3f;
    private float phase;
    private float flapAngle = 30f;
    private float armVelSm;
    private long lastNanos = -1L;
    long lastUsedNanos;

    private float cosFlap, sinFlap, cosSweep, sinSweep;
    private final double[] tmp = new double[3];

    /** Movement the pose reacts to (blocks per tick). */
    record Motion(float horizontalSpeed, float vertical, boolean airborne, boolean gliding, boolean sneaking) {
        static final Motion REST = new Motion(0, 0, false, false, false);
    }

    void update(Motion p, boolean animate) {
        long now = System.nanoTime();
        lastUsedNanos = now;
        // Too-small steps are skipped without moving the clock, so time accumulates instead of freezing above ~500 fps.
        if (lastNanos >= 0 && (now - lastNanos) / 1.0e9f < 0.002f) return;
        float dt = lastNanos < 0 ? 0.016f : MathHelper.clamp((now - lastNanos) / 1.0e9f, 0.001f, 0.1f);
        lastNanos = now;
        float speed = p.horizontalSpeed(), vy = p.vertical();
        boolean air = p.airborne();
        float open, base, amp, freq;
        if (p.gliding()) {
            open = 1.0f; base = 6f; amp = 1.5f; freq = 0.5f;
        } else if (p.sneaking()) {
            open = 0.12f; base = 36f; amp = 1.5f; freq = 0.22f;
        } else if (air && vy < -0.38f) {
            open = 1.0f; base = 58f; amp = 7f; freq = 1.5f;
        } else if (air && vy > 0.08f) {
            open = 0.96f; base = 12f; amp = 40f; freq = 2.1f;
        } else if (air) {
            open = 0.85f; base = 28f; amp = 16f; freq = 1.35f;
        } else if (speed > 0.06f) {
            open = MathHelper.clamp(0.70f + speed * 1.5f, 0.70f, 0.9f); base = 27f; amp = 5f; freq = 0.9f;
        } else {
            open = 0.72f; base = 31f; amp = 2.5f; freq = 0.27f;
        }
        if (!animate) {
            amp = 0f;
            freq = 0f;
        }
        float k = 1f - (float) Math.exp(-dt * 6.5f);
        openSm += (open - openSm) * k;
        baseFlapSm += (base - baseFlapSm) * k;
        ampSm += (amp - ampSm) * k;
        freqSm += (freq - freqSm) * k;
        phase += (float) (Math.PI * 2.0) * freqSm * dt;
        if (phase > 100f * (float) Math.PI) phase -= 100f * (float) Math.PI;
        float shape = MathHelper.sin(phase) - 0.22f * MathHelper.sin(phase * 2f);
        float target = baseFlapSm + ampSm * shape;
        float prev = flapAngle;
        flapAngle = target;
        float vel = (flapAngle - prev) / dt;
        armVelSm += (vel - armVelSm) * (1f - (float) Math.exp(-dt * 9f));
    }

    private void prepareFrame() {
        float flap = flapAngle * DEG;
        cosFlap = MathHelper.cos(flap);
        sinFlap = MathHelper.sin(flap);
        float sweep = MathHelper.lerp(openSm, 58f, 14f) * DEG;
        cosSweep = MathHelper.cos(sweep);
        sinSweep = MathHelper.sin(sweep);
    }

    private void map(float side, double x, double y, double z, double[] out) {
        double x1 = x * cosFlap - y * sinFlap;
        double y1 = x * sinFlap + y * cosFlap;
        double x2 = x1 * cosSweep;
        double z2 = z + x1 * sinSweep;
        out[0] = side * (SHOULDER_X + x2);
        out[1] = y1;
        out[2] = -z2;
    }

    // ---- palette ---------------------------------------------------------------------------------------

    static final class Palette {
        final int body, accent, darkBone;

        Palette(int body, int accent, int bone) {
            this.body = body | 0xFF000000;
            this.accent = accent | 0xFF000000;
            this.darkBone = bone | 0xFF000000;
        }

        static Palette of(CosmeticSelection.Wings w, int themeAccent) {
            int accent = themeAccent | 0xFF000000;
            return switch (w.colorMode()) {
                case "Theme Sync" -> new Palette(VisualGeometry.mix(accent, 0xFFFFFFFF, 0.55f), accent, 0xFF282834);
                case "Obsidian" -> new Palette(0xFF2C2A3A, 0xFF9678FF, 0xFF14121C);
                case "Ember" -> new Palette(0xFFFFD6A0, 0xFFFF601C, 0xFF3C1810);
                case "Custom" -> {
                    int c = w.customColor() | 0xFF000000;
                    yield new Palette(VisualGeometry.mix(c, 0xFFFFFFFF, 0.5f), c, VisualGeometry.mix(c, 0xFF000000, 0.7f));
                }
                default -> new Palette(0xFFF8F7F4, accent, 0xFF3E3A3C);
            };
        }

        int feather(float t, float tipTint, float shade) {
            float k = tipTint * (float) Math.pow(t, 2.2);
            return VisualGeometry.shade(VisualGeometry.mix(body, accent, k) | 0xFF000000, shade);
        }

        int membrane(float u, float v, float billow) {
            float edge = (float) Math.exp(-Math.pow(Math.min(u, 1f - u) / 0.09f, 2));
            int c = VisualGeometry.mix(body, accent, 0.35f + 0.5f * v);
            c = VisualGeometry.mix(c, darkBone, edge * 0.55f);
            float light = 0.80f + 0.20f * (float) Math.sin(Math.PI * u) * (1f - v * 0.3f) + billow * 0.3f;
            return VisualGeometry.shade(c | 0xFF000000, light);
        }
    }

    // ---- skeleton --------------------------------------------------------------------------------------

    private static final class Arm {
        float elbowX, elbowY, wristX, wristY;
        float a1, a2, a3;

        double sweepZ(double distance) {
            return 0.045 + distance * 0.12;
        }
    }

    private Arm solveArm() {
        float open = openSm;
        Arm arm = new Arm();
        arm.a1 = MathHelper.lerp(open, 78f, 14f) * DEG;
        float elbowBend = MathHelper.lerp(open, -142f, -9f) * DEG;
        arm.a2 = arm.a1 + elbowBend;
        arm.a3 = arm.a2 + MathHelper.lerp(open, -28f, 6f) * DEG;
        float upper = 0.36f, fore = 0.50f;
        arm.elbowX = upper * MathHelper.cos(arm.a1);
        arm.elbowY = upper * MathHelper.sin(arm.a1);
        arm.wristX = arm.elbowX + fore * MathHelper.cos(arm.a2);
        arm.wristY = arm.elbowY + fore * MathHelper.sin(arm.a2);
        return arm;
    }

    // ---- angel -----------------------------------------------------------------------------------------

    void buildAngel(VertexConsumer buf, Matrix4f m, Palette pal, float tip, int light) {
        prepareFrame();
        for (float side = -1f; side <= 1f; side += 2f) buildAngelWing(buf, m, side, pal, tip, light);
    }

    private void buildAngelWing(VertexConsumer buf, Matrix4f m, float side, Palette pal, float tip, int light) {
        Arm arm = solveArm();
        float open = openSm;
        float lag = MathHelper.clamp(-armVelSm * 0.0042f, -0.55f, 0.55f);
        float curlBase = MathHelper.lerp(open, 0.30f, 0.12f);

        for (int i = 0; i < 7; i++) {
            float t = (i + 0.5f) / 7f;
            float ox, oy, ang;
            if (t < 0.4f) {
                ox = arm.elbowX * (t / 0.4f);
                oy = arm.elbowY * (t / 0.4f);
                ang = arm.a1;
            } else {
                ox = MathHelper.lerp((t - 0.4f) / 0.6f, arm.elbowX, arm.wristX);
                oy = MathHelper.lerp((t - 0.4f) / 0.6f, arm.elbowY, arm.wristY);
                ang = arm.a2;
            }
            feather(buf, m, side, ox, oy, ang - 74f * DEG, 0.20f + 0.05f * (1f - t), 0.095f, curlBase, lag * 0.4f,
                    0.036f + i * 0.0006f, pal, tip * 0.2f, light, 0.98f);
        }
        for (int i = 0; i < 4; i++) {
            float t = (i + 0.5f) / 4f;
            float ang = arm.a1 - MathHelper.lerp(t, 98f, 122f) * DEG;
            float len = MathHelper.lerp(t, 0.58f, 0.40f);
            feather(buf, m, side, arm.elbowX * t, arm.elbowY * t, ang, len, 0.20f, curlBase, lag * 0.6f, 0.004f + i * 0.0005f,
                    pal, tip * 0.35f, light, 0.96f);
        }
        int sec = 8;
        for (int i = 0; i < sec; i++) {
            float t = (i + 0.5f) / sec;
            float ox = MathHelper.lerp(t, arm.wristX, arm.elbowX);
            float oy = MathHelper.lerp(t, arm.wristY, arm.elbowY);
            float ang = arm.a2 - MathHelper.lerp(t, 82f, 112f) * DEG;
            float len = MathHelper.lerp(t, 0.92f, 0.64f);
            float z = 0.002f + i * 0.0004f;
            feather(buf, m, side, ox, oy, ang, len, 0.185f, curlBase, lag * (0.5f + 0.5f * (1f - t)), z, pal, tip * 0.7f, light, 1.0f);
            feather(buf, m, side, ox, oy, ang, len * 0.46f, 0.165f, curlBase * 0.8f, lag * 0.25f, z + 0.012f, pal, tip * 0.15f, light, 0.97f);
            feather(buf, m, side, ox, oy, ang + 2f * DEG, len * 0.24f, 0.135f, curlBase * 0.6f, lag * 0.1f, z + 0.022f, pal, 0f, light, 0.95f);
        }
        int pri = 9;
        for (int i = 0; i < pri; i++) {
            float t = i / (float) (pri - 1);
            float ang = arm.a3 + MathHelper.lerp(t, 9f, -80f) * DEG;
            float len = 1.26f - 0.058f * i;
            if (i == 0) len -= 0.07f;
            float ox = arm.wristX + MathHelper.cos(arm.a3) * i * 0.010f;
            float oy = arm.wristY + MathHelper.sin(arm.a3) * i * 0.010f;
            float z = -0.004f + i * 0.0003f;
            float tipLag = lag * (1.15f - 0.05f * i);
            feather(buf, m, side, ox, oy, ang, len, 0.175f - 0.004f * i, curlBase * 0.9f, tipLag, z, pal, tip, light, 1.0f);
            feather(buf, m, side, ox, oy, ang, len * 0.42f, 0.155f, curlBase * 0.7f, tipLag * 0.3f, z + 0.014f, pal, tip * 0.1f, light, 0.97f);
            feather(buf, m, side, ox, oy, ang + 1.5f * DEG, len * 0.22f, 0.125f, curlBase * 0.5f, tipLag * 0.1f, z + 0.024f, pal, 0f, light, 0.95f);
        }
        for (int i = 0; i < 3; i++) {
            feather(buf, m, side, arm.wristX, arm.wristY, arm.a3 + (28f + i * 11f) * DEG, 0.27f - i * 0.04f, 0.085f,
                    curlBase, lag * 0.5f, 0.034f + i * 0.001f, pal, tip * 0.5f, light, 1.0f);
        }
    }

    private void feather(VertexConsumer buf, Matrix4f m, float side, float ox, float oy, float gamma, float len, float width,
                         float curl, float lag, float layerZ, Palette pal, float tipTint, int light, float brightness) {
        double px = ox, py = oy;
        double prevLx = 0, prevLy = 0, prevLz = 0, prevRx = 0, prevRy = 0, prevRz = 0;
        int prevColor = 0;
        float prevT = 0f;
        float seg = len / FEATHER_SEGMENTS;
        float twist = layerZ > 0.02f ? 0.10f : 0.20f;
        for (int i = 0; i <= FEATHER_SEGMENTS; i++) {
            float t = i / (float) FEATHER_SEGMENTS;
            float ang = gamma + curl * t * t - lag * t * t * 1.2f;
            if (i > 0) {
                float tm = (i - 0.5f) / FEATHER_SEGMENTS;
                float am = gamma + curl * tm * tm - lag * tm * tm * 1.2f;
                px += MathHelper.cos(am) * seg;
                py += MathHelper.sin(am) * seg;
            }
            double arch = Math.sin(Math.PI * t) * 0.016 * len + layerZ;
            double halfW = width * 0.5;
            float perpX = -MathHelper.sin(ang), perpY = MathHelper.cos(ang);
            float tw = twist * t;
            double lx = px + perpX * halfW * MathHelper.cos(tw), ly = py + perpY * halfW * MathHelper.cos(tw);
            double lz = arch + halfW * MathHelper.sin(tw);
            double rx = px - perpX * halfW * MathHelper.cos(tw), ry = py - perpY * halfW * MathHelper.cos(tw);
            double rz = arch - halfW * MathHelper.sin(tw);
            map(side, lx, ly, lz, tmp);
            double mlx = tmp[0], mly = tmp[1], mlz = tmp[2];
            map(side, rx, ry, rz, tmp);
            double mrx = tmp[0], mry = tmp[1], mrz = tmp[2];
            float shade = brightness * (0.80f + 0.20f * (float) Math.pow(t, 0.8));
            int color = pal.feather(t, tipTint, shade);
            if (i > 0) {
                VisualGeometry.quad(buf, m, prevLx, prevLy, prevLz, prevRx, prevRy, prevRz, mrx, mry, mrz, mlx, mly, mlz,
                        0f, prevT, 1f, t, prevColor, prevColor, color, color, light);
            }
            prevLx = mlx; prevLy = mly; prevLz = mlz;
            prevRx = mrx; prevRy = mry; prevRz = mrz;
            prevColor = color;
            prevT = t;
        }
    }

    // ---- dragon ----------------------------------------------------------------------------------------

    private float[] fingerAngles(Arm arm) {
        float spread = MathHelper.lerp(openSm, 16f, 52f) * DEG;
        float[] angles = new float[FINGERS];
        for (int i = 0; i < FINGERS; i++) {
            float t = i / (float) (FINGERS - 1);
            angles[i] = arm.a2 + MathHelper.lerp(openSm, -30f, 20f) * DEG - t * spread * 1.9f + 0.18f;
        }
        return angles;
    }

    void buildDragonMembranes(VertexConsumer buf, Matrix4f m, Palette pal, int light) {
        prepareFrame();
        for (float side = -1f; side <= 1f; side += 2f) buildDragonMembrane(buf, m, side, pal, light);
    }

    void buildDragonBones(VertexConsumer buf, Matrix4f m, Palette pal) {
        prepareFrame();
        for (float side = -1f; side <= 1f; side += 2f) buildDragonBones(buf, m, side, pal);
    }

    private void buildDragonMembrane(VertexConsumer buf, Matrix4f m, float side, Palette pal, int light) {
        Arm arm = solveArm();
        float[] ang = fingerAngles(arm);
        float flutter = MathHelper.clamp(armVelSm * 0.0009f, -0.12f, 0.12f);
        float billow = 0.06f + 0.05f * openSm;
        double[] p = new double[3];
        for (int panel = 0; panel < FINGERS; panel++) {
            boolean trailing = panel == FINGERS - 1;
            float angA = ang[panel];
            float lenA = FINGER_LEN[panel];
            float angB, lenB;
            if (!trailing) {
                angB = ang[panel + 1];
                lenB = FINGER_LEN[panel + 1];
            } else {
                angB = arm.a2 - MathHelper.lerp(openSm, 150f, 118f) * DEG;
                lenB = MathHelper.lerp(openSm, 0.52f, 0.78f);
            }
            double[][] grid = new double[(GRID + 1) * (GRID + 1)][3];
            int[] colors = new int[(GRID + 1) * (GRID + 1)];
            for (int iu = 0; iu <= GRID; iu++) {
                float u = iu / (float) GRID;
                float a = MathHelper.lerp(u, angA, angB);
                float baseLen = MathHelper.lerp(u, lenA, lenB);
                float scallop = 1f - 0.17f * (float) Math.pow(Math.sin(Math.PI * u), 1.1);
                for (int iv = 0; iv <= GRID; iv++) {
                    float v = iv / (float) GRID;
                    float r = baseLen * scallop * v;
                    double wx = arm.wristX + MathHelper.cos(a) * r;
                    double wy = arm.wristY + MathHelper.sin(a) * r;
                    double wz = arm.sweepZ(Math.abs(wx)) * 0.5
                            + billow * Math.sin(Math.PI * u) * Math.sin(Math.PI * v * 0.92) * (0.55 + flutter * 3.0)
                            + flutter * v * v;
                    map(side, wx, wy, wz, p);
                    int idx = iu * (GRID + 1) + iv;
                    grid[idx][0] = p[0]; grid[idx][1] = p[1]; grid[idx][2] = p[2];
                    colors[idx] = pal.membrane(u, v, billow);
                }
            }
            for (int iu = 0; iu < GRID; iu++) {
                for (int iv = 0; iv < GRID; iv++) {
                    int i00 = iu * (GRID + 1) + iv, i10 = (iu + 1) * (GRID + 1) + iv;
                    int i11 = (iu + 1) * (GRID + 1) + iv + 1, i01 = iu * (GRID + 1) + iv + 1;
                    float u0 = iu / (float) GRID, u1 = (iu + 1) / (float) GRID;
                    float v0 = iv / (float) GRID, v1 = (iv + 1) / (float) GRID;
                    VisualGeometry.quad(buf, m,
                            grid[i00][0], grid[i00][1], grid[i00][2], grid[i10][0], grid[i10][1], grid[i10][2],
                            grid[i11][0], grid[i11][1], grid[i11][2], grid[i01][0], grid[i01][1], grid[i01][2],
                            u0, v0, u1, v1, colors[i00], colors[i10], colors[i11], colors[i01], light);
                }
            }
        }
    }

    private void buildDragonBones(VertexConsumer buf, Matrix4f m, float side, Palette pal) {
        Arm arm = solveArm();
        float[] ang = fingerAngles(arm);
        int bone = pal.darkBone;
        double[] a = new double[3];
        double[] b = new double[3];
        map(side, 0, 0, 0.02, a);
        map(side, arm.elbowX, arm.elbowY, arm.sweepZ(arm.elbowX), b);
        tube(buf, m, a, b, 0.040f, 0.032f, bone);
        double ex = b[0], ey = b[1], ez = b[2];
        map(side, arm.wristX, arm.wristY, arm.sweepZ(arm.wristX), a);
        tube(buf, m, new double[]{ex, ey, ez}, a, 0.032f, 0.026f, bone);
        double wx = a[0], wy = a[1], wz = a[2];
        for (int i = 0; i < FINGERS; i++) {
            float len = FINGER_LEN[i];
            int segs = 4;
            double[] prev = {wx, wy, wz};
            for (int s = 1; s <= segs; s++) {
                float v = s / (float) segs;
                float curve = ang[i] - 0.14f * v * v;
                double fx = arm.wristX + MathHelper.cos(curve) * len * v;
                double fy = arm.wristY + MathHelper.sin(curve) * len * v;
                map(side, fx, fy, arm.sweepZ(Math.abs(fx)) * 0.5 + 0.01, b);
                float r0 = 0.024f * (1f - (s - 1) / (float) segs) + 0.004f;
                float r1 = 0.024f * (1f - s / (float) segs) + 0.004f;
                tube(buf, m, prev, b, r0, r1, bone);
                prev = new double[]{b[0], b[1], b[2]};
            }
            double dx = prev[0] - wx, dy = prev[1] - wy, dz = prev[2] - wz;
            double dl = Math.max(1e-4, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double[] claw = {prev[0] + dx / dl * 0.07, prev[1] + dy / dl * 0.07 - 0.02, prev[2] + dz / dl * 0.07};
            tube(buf, m, prev, claw, 0.012f, 0.0f, VisualGeometry.shade(bone, 0.7f));
        }
        map(side, arm.wristX + MathHelper.cos(arm.a2 + 0.9f) * 0.17, arm.wristY + MathHelper.sin(arm.a2 + 0.9f) * 0.17, 0.02, b);
        tube(buf, m, new double[]{wx, wy, wz}, b, 0.020f, 0.0f, VisualGeometry.shade(bone, 0.75f));
    }

    private static void tube(VertexConsumer buf, Matrix4f m, double[] p0, double[] p1, float r0, float r1, int color) {
        double dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5) return;
        dx /= len; dy /= len; dz /= len;
        double hx = Math.abs(dy) < 0.9 ? 0 : 1, hy = Math.abs(dy) < 0.9 ? 1 : 0, hz = 0;
        double ux = dy * hz - dz * hy, uy = dz * hx - dx * hz, uz = dx * hy - dy * hx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        double vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
        int sides = 6;
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            double n0x = ux * c0 + vx * s0, n0y = uy * c0 + vy * s0, n0z = uz * c0 + vz * s0;
            double n1x = ux * c1 + vx * s1, n1y = uy * c1 + vy * s1, n1z = uz * c1 + vz * s1;
            double mx = n0x + n1x, my = n0y + n1y;
            double facing = Math.max(0.0, (my * 0.75 + mx * 0.25) / 1.6);
            int shaded = VisualGeometry.shade(color, 0.52f + 0.48f * (float) facing);
            pos(buf, m, p0[0] + n0x * r0, p0[1] + n0y * r0, p0[2] + n0z * r0, shaded);
            pos(buf, m, p0[0] + n1x * r0, p0[1] + n1y * r0, p0[2] + n1z * r0, shaded);
            pos(buf, m, p1[0] + n1x * r1, p1[1] + n1y * r1, p1[2] + n1z * r1, shaded);
            pos(buf, m, p0[0] + n0x * r0, p0[1] + n0y * r0, p0[2] + n0z * r0, shaded);
            pos(buf, m, p1[0] + n1x * r1, p1[1] + n1y * r1, p1[2] + n1z * r1, shaded);
            pos(buf, m, p1[0] + n0x * r1, p1[1] + n0y * r1, p1[2] + n0z * r1, shaded);
        }
    }

    private static void pos(VertexConsumer buf, Matrix4f m, double x, double y, double z, int argb) {
        buf.vertex(m, (float) x, (float) y, (float) z)
                .color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }
}


/**
 * The cape as a strip of {@link CapeSim#SEGMENTS} hinged cloth panels in body space (origin at the neck, y down,
 * +z behind the player), 10x16x1 pixels like the vanilla cape and textured with the same 64x32 layout.
 */
static final class CapeModel {
    private static final double HALF_W = 5.0 / 16.0;
    private static final double LENGTH = 1.0;
    private static final double THICK = 1.0 / 16.0;

    private CapeModel() { }

    static void emit(Mesh m, float[] pitch, float[] roll, double zBack) {
        int n = CapeSim.SEGMENTS;
        double seg = LENGTH / n;
        double[][] center = new double[n + 1][];
        double[][] normal = new double[n + 1][];
        center[0] = new double[]{0, 0, zBack};
        for (int i = 0; i < n; i++) {
            double th = pitch[i], ph = roll[i];
            double dx = Math.sin(ph), dy = Math.cos(th) * Math.cos(ph), dz = Math.sin(th) * Math.cos(ph);
            double[] c = center[i];
            center[i + 1] = new double[]{c[0] + dx * seg, c[1] + dy * seg, c[2] + dz * seg};
            normal[i] = new double[]{0, -Math.sin(th), Math.cos(th)};
        }
        normal[n] = normal[n - 1];
        // Average neighbouring normals at the joints so the cloth bends smoothly.
        double[][] jn = new double[n + 1][];
        for (int j = 0; j <= n; j++) {
            double[] a = normal[Math.max(0, j - 1)], b = normal[Math.min(n, j)];
            double x = a[0] + b[0], y = a[1] + b[1], z = a[2] + b[2];
            double l = Math.sqrt(x * x + y * y + z * z);
            jn[j] = l < 1e-9 ? new double[]{0, 0, 1} : new double[]{x / l, y / l, z / l};
        }
        final float px = 1f / 64f, py = 1f / 32f;
        for (int i = 0; i < n; i++) {
            double[] c0 = center[i], c1 = center[i + 1];
            double[] n0 = jn[i], n1 = jn[i + 1];
            double[] il0 = {c0[0] + HALF_W, c0[1], c0[2]}, ir0 = {c0[0] - HALF_W, c0[1], c0[2]};
            double[] il1 = {c1[0] + HALF_W, c1[1], c1[2]}, ir1 = {c1[0] - HALF_W, c1[1], c1[2]};
            double[] ol0 = add(il0, n0, THICK), or0 = add(ir0, n0, THICK), ol1 = add(il1, n1, THICK), or1 = add(ir1, n1, THICK);
            float v0 = (1f + 16f * i / n) * py, v1 = (1f + 16f * (i + 1) / n) * py;
            float nx = 0f, ny = (float) -((n0[1] + n1[1]) * 0.5), nz = (float) -((n0[2] + n1[2]) * 0.5);
            // Outer face (seen from behind the player): left edge of the art on the viewer's left (+x).
            m.texQuad(ol0, or0, or1, ol1, 1f * px, v0, 11f * px, v1, 0xFFFFFFFF, nx, -ny, -nz);
            // Inner face, toward the player's back.
            m.texQuad(ir0, il0, il1, ir1, 12f * px, v0, 22f * px, v1, 0xFFFFFFFF, nx, ny, nz);
            // Side edges.
            m.texQuad(il0, ol0, ol1, il1, 0f, v0, 1f * px, v1, 0xFFFFFFFF, 1f, 0f, 0f);
            m.texQuad(or0, ir0, ir1, or1, 11f * px, v0, 12f * px, v1, 0xFFFFFFFF, -1f, 0f, 0f);
            if (i == 0) {
                m.texQuad(il0, ir0, or0, ol0, 1f * px, 0f, 11f * px, py, 0xFFFFFFFF, 0f, -1f, 0f);
            }
            if (i == n - 1) {
                m.texQuad(ol1, or1, ir1, il1, 11f * px, 0f, 21f * px, py, 0xFFFFFFFF, 0f, 1f, 0f);
            }
        }
    }

    private static double[] add(double[] p, double[] n, double k) {
        return new double[]{p[0] + n[0] * k, p[1] + n[1] * k, p[2] + n[2] * k};
    }
}


/**
 * Bridges the player renderer to the cosmetics: decides what each rendered player wears (the local player from
 * their prefs, other players from {@link CosmeticsSync} when viewing is on), swaps the cape texture into the render
 * state for the vanilla cape/elytra path, and draws hats, wings and the cloth cape from the cape feature hook.
 * Render thread only. Never throws into the renderer.
 */
static final class CosmeticRenderer {
    private static final AssetInfo.TextureAsset DEFAULT_ELYTRA = new AssetInfo.TextureAssetInfo(
            Identifier.ofVanilla("entity/equipment/wings/elytra"), Identifier.ofVanilla("textures/entity/equipment/wings/elytra.png"));
    private static final long PRUNE_NANOS = 10_000_000_000L;

    private record Resolved(CosmeticSelection selection, CapeDesign cape, long at) { }

    private static final Map<Integer, Resolved> RESOLVED = new HashMap<>();
    private static final Map<Integer, CapeSim> CAPES = new HashMap<>();
    private static final Map<Integer, Long> CAPE_CLOCK = new HashMap<>();
    private static final Map<Integer, WingRig> WINGS = new HashMap<>();
    private static long lastPrune;

    /** A thumbnail render: this render state wears {@code resolved} instead of the player's own cosmetics. */
    private record PreviewOverride(Resolved resolved, WingRig rig) { }

    /** Keyed by the render-state object itself (identity); entries go with the state once the GUI has drawn it. */
    private static final Map<PlayerEntityRenderState, PreviewOverride> OVERRIDES = new java.util.WeakHashMap<>();
    /** One resting wing rig per wing type, shared by the thumbnails. */
    private static final Map<String, WingRig> PREVIEW_RIGS = new HashMap<>();

    /**
     * Makes this one render state show {@code selection} (a single hat or wings, nothing else) for a Cosmetics tab
     * thumbnail. The player's own cosmetics, cape and helmet stay off it.
     */
    static void overridePreview(PlayerEntityRenderState state, CosmeticSelection selection) {
        WingRig rig = null;
        if (selection.wings() != null) {
            rig = PREVIEW_RIGS.computeIfAbsent(selection.wings().type(), k -> new WingRig());
            rig.update(WingRig.Motion.REST, selection.wings().animate());
        }
        state.capeVisible = false;
        if (selection.hat() != null) state.equippedHeadStack = ItemStack.EMPTY;
        OVERRIDES.put(state, new PreviewOverride(new Resolved(selection, null, System.nanoTime()), rig));
    }

    private CosmeticRenderer() { }

    // ------------------------------------------------------------------ render-state hook

    /** Called at the end of the player renderer's render-state update. */
    public static void onUpdateRenderState(Entity entity, PlayerEntityRenderState state) {
        try {
            update(entity, state);
        } catch (Throwable ignored) {
            // a cosmetic must never break rendering
        }
    }

    private static void update(Entity entity, PlayerEntityRenderState state) {
        long now = System.nanoTime();
        prune(now);
        CosmeticsSync.pump();
        CosmeticSelection sel = selectionFor(entity);
        if (sel == null || sel.isEmpty()) {
            RESOLVED.remove(state.id);
            return;
        }
        CapeDesign cape = sel.cape();
        // The local Theme Sync cape follows the live accent; shared ones keep the colours they were published with.
        if (cape != null && "theme".equals(cape.preset()) && isLocal(entity)) cape = CapeDesign.themed(AccentState.accentArgb());
        RESOLVED.put(state.id, new Resolved(sel, cape, now));

        if (cape != null) {
            Identifier tex = CosmeticTextures.cape(cape);
            SkinTextures skin = state.skinTextures;
            if (tex != null && skin != null) {
                AssetInfo.TextureAsset asset = new AssetInfo.TextureAssetInfo(tex, tex);
                AssetInfo.TextureAsset elytra = CosmeticPrefs.get().elytraUsesCape() ? asset
                        : skin.elytra() != null ? skin.elytra() : DEFAULT_ELYTRA;
                state.skinTextures = new SkinTextures(skin.body(), asset, elytra, skin.model(), skin.secure());
                state.capeVisible = true;
            }
            if (CosmeticPrefs.get().capeMode() == CosmeticPrefs.CapeMode.PHYSICS) stepCape(entity, state.id, now);
        }
        if (sel.wings() != null) {
            WingRig rig = WINGS.computeIfAbsent(state.id, k -> new WingRig());
            rig.update(wingMotion(entity), sel.wings().animate());
        }
    }

    private static boolean isLocal(Entity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && (entity == mc.player || entity.getUuid().equals(mc.player.getUuid()));
    }

    private static CosmeticSelection selectionFor(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return null;
        CosmeticPrefs prefs = CosmeticPrefs.get();
        if (isLocal(entity)) return prefs.selection();
        if (!prefs.showOthers()) return null;
        UUID uuid = entity.getUuid();
        return CosmeticsSync.lookup(uuid);
    }

    private static void stepCape(Entity entity, int id, long now) {
        CapeSim sim = CAPES.computeIfAbsent(id, k -> new CapeSim());
        Long last = CAPE_CLOCK.get(id);
        float dt = last == null ? 0.016f : (now - last) / 1.0e9f;
        sim.lastUsedNanos = now;
        if (dt < 0.002f) return; // keep the old stamp so time accumulates instead of freezing at high fps
        CAPE_CLOCK.put(id, now);
        double dx = entity.getX() - entity.lastX, dy = entity.getY() - entity.lastY, dz = entity.getZ() - entity.lastZ;
        float yaw = entity instanceof LivingEntity living ? living.bodyYaw : entity.getYaw();
        float yawRate = entity instanceof LivingEntity living ? MathHelper.wrapDegrees(living.bodyYaw - living.lastBodyYaw) * 20f : 0f;
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        float forward = (float) (dx * fx + dz * fz);
        float side = (float) (dx * fz - dz * fx);
        boolean sneaking = entity.isSneaking();
        sim.step(dt, new CapeSim.Motion(forward, side, (float) dy, yawRate, sneaking));
    }

    private static WingRig.Motion wingMotion(Entity entity) {
        double dx = entity.getX() - entity.lastX, dy = entity.getY() - entity.lastY, dz = entity.getZ() - entity.lastZ;
        boolean climbing = entity instanceof LivingEntity living && living.isClimbing();
        boolean gliding = entity instanceof LivingEntity living && living.isGliding();
        return new WingRig.Motion((float) Math.hypot(dx, dz), (float) dy, !entity.isOnGround() && !climbing, gliding, entity.isSneaking());
    }

    private static void prune(long now) {
        if (now - lastPrune < 2_000_000_000L) return;
        lastPrune = now;
        RESOLVED.values().removeIf(r -> now - r.at() > PRUNE_NANOS);
        CAPES.values().removeIf(s -> now - s.lastUsedNanos > PRUNE_NANOS);
        CAPE_CLOCK.keySet().retainAll(CAPES.keySet());
        WINGS.values().removeIf(w -> now - w.lastUsedNanos > PRUNE_NANOS);
    }

    // ------------------------------------------------------------------ feature hook

    /**
     * Draws the player's hat, wings and (in Physics/Static mode) the cloth cape. Returns true when the vanilla cape
     * must not draw.
     */
    public static boolean renderFeatures(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                         PlayerEntityRenderState state, PlayerEntityModel model) {
        try {
            return render(matrices, queue, light, state, model);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                  PlayerEntityRenderState state, PlayerEntityModel model) {
        if (state.invisible || model == null) return false;
        PreviewOverride preview = OVERRIDES.get(state);
        Resolved r = preview != null ? preview.resolved() : RESOLVED.get(state.id);
        if (r == null) return false;
        CosmeticSelection sel = r.selection();
        float time = (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000f;
        int accent = AccentState.accentArgb() | 0xFF000000;

        if (sel.hat() != null) drawHat(matrices, queue, light, state, model, sel.hat(), accent, time);
        if (sel.wings() != null) {
            drawWings(matrices, queue, light, model, sel.wings(), accent, preview != null ? preview.rig() : WINGS.get(state.id));
        }
        if (r.cape() != null) return drawCape(matrices, queue, light, state, model, r.cape());
        return false;
    }

    private static void drawHat(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state,
                                PlayerEntityModel model, CosmeticSelection.Hat hat, int accent, float time) {
        String id = hat.id();
        int color = hat.color() == CosmeticSelection.THEME_COLOR ? accent : hat.color();
        matrices.push();
        model.getRootPart().applyTransform(matrices);
        model.head.applyTransform(matrices);
        ItemStack helmet = state.equippedHeadStack;
        if (helmet != null && !helmet.isEmpty()) {
            matrices.translate(0f, -0.07f, 0f);
            matrices.scale(1.12f, 1.12f, 1.12f);
        }
        int lightUse = HatModels.emissive(id) ? VisualGeometry.FULL_BRIGHT : light;
        if (!"nimbus".equals(id)) {
            queue.submitCustom(matrices, CosmeticLayers.flat(),
                    (entry, vc) -> HatModels.solid(new Mesh(entry, vc, lightUse), id, color, accent, time));
        }
        if (HatModels.glows(id) && ProceduralTextures.ensure()) {
            Identifier tex = "halo".equals(id) ? ProceduralTextures.RING : ProceduralTextures.GLOW;
            queue.submitCustom(matrices, ProceduralTextures.layer(tex, Blend.ADDITIVE, true),
                    (entry, vc) -> HatModels.glow(vc, entry.getPositionMatrix(), id, color, time));
        }
        matrices.pop();
    }

    private static void drawWings(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityModel model,
                                  CosmeticSelection.Wings wings, int accent, WingRig rig) {
        if (rig == null || !ProceduralTextures.ensure()) return;
        WingRig.Palette palette = WingRig.Palette.of(wings, accent);
        float tip = wings.tipTint();
        matrices.push();
        model.getRootPart().applyTransform(matrices);
        model.body.applyTransform(matrices);
        // Shoulder blades, then from wing space (y up, z back = -z) into body space (y down, z back = +z).
        matrices.translate(0f, 0.04f, 0.11f);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
        float s = wings.scale() * 0.78f / 0.9375f;
        matrices.scale(s, s, s);
        if ("dragon".equals(wings.type())) {
            queue.submitCustom(matrices, ProceduralTextures.layer(ProceduralTextures.MEMBRANE, Blend.ALPHA, true),
                    (entry, vc) -> rig.buildDragonMembranes(vc, entry.getPositionMatrix(), palette, light));
            queue.submitCustom(matrices, RenderTypes.TRIANGLES,
                    (entry, vc) -> rig.buildDragonBones(vc, entry.getPositionMatrix(), palette));
        } else {
            queue.submitCustom(matrices, ProceduralTextures.layer(ProceduralTextures.FEATHER, Blend.ALPHA, true),
                    (entry, vc) -> rig.buildAngel(vc, entry.getPositionMatrix(), palette, tip, light));
        }
        matrices.pop();
    }

    private static boolean drawCape(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state,
                                    PlayerEntityModel model, CapeDesign cape) {
        CosmeticPrefs.CapeMode mode = CosmeticPrefs.get().capeMode();
        if (mode == CosmeticPrefs.CapeMode.VANILLA) return false;
        ItemStack chest = state.equippedChestStack;
        boolean glider = chest != null && chest.contains(DataComponentTypes.GLIDER);
        // Like the vanilla cape: hidden while wearing an elytra (which then shows the cape design if enabled).
        if (glider) return true;
        Identifier tex = CosmeticTextures.cape(cape);
        if (tex == null) return false;
        float[] pitch = new float[CapeSim.SEGMENTS], roll = new float[CapeSim.SEGMENTS];
        CapeSim sim = mode == CosmeticPrefs.CapeMode.PHYSICS ? CAPES.get(state.id) : null;
        if (sim != null) sim.read(pitch, roll);
        else CapeSim.staticPose(state.isInSneakingPose, pitch, roll);
        double zBack = (chest != null && !chest.isEmpty()) ? 0.125 + 0.0625 : 0.125;
        matrices.push();
        model.getRootPart().applyTransform(matrices);
        model.body.applyTransform(matrices);
        queue.submitCustom(matrices, CosmeticLayers.solid(tex),
                (entry, vc) -> CapeModel.emit(new Mesh(entry, vc, light), pitch, roll, zBack));
        matrices.pop();
        return true;
    }
}


/**
 * The live player preview of the Cosmetics tab: the player's own entity drawn through the normal entity renderer
 * (so every cosmetic shows exactly as in the world), turned to an arbitrary yaw, like the inventory's paper doll.
 */
static final class CosmeticsPreview {
    private CosmeticsPreview() { }

    /** True when there is a player to show (a world is loaded). */
    public static boolean available() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.player != null && mc.world != null;
    }

    /** Draws the player in the given GUI-unit box, rotated {@code yawDeg} around the vertical axis. */
    public static void draw(DrawContext context, int x1, int y1, int x2, int y2, float yawDeg, float tiltDeg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc == null ? null : mc.player;
        if (player == null || x2 - x1 < 8 || y2 - y1 < 8) return;
        try {
            EntityRenderer<? super ClientPlayerEntity, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
            EntityRenderState state = renderer.getAndUpdateRenderState(player, 1f);
            state.light = 0xF000F0;
            state.shadowPieces.clear();
            state.outlineColor = 0;
            if (state instanceof LivingEntityRenderState living) {
                living.bodyYaw = 180f + yawDeg;
                living.relativeHeadYaw = 0f;
                living.pitch = 0f;
                living.hurt = false;
                living.width /= living.baseScale;
                living.height /= living.baseScale;
                living.baseScale = 1f;
            }
            float size = (y2 - y1) / 3.0f;
            Quaternionf flip = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf tilt = new Quaternionf().rotateX(tiltDeg * (float) Math.PI / 180f);
            flip.mul(tilt);
            Vector3f offset = new Vector3f(0f, state.height / 2f + 0.0625f, 0f);
            context.addEntity(state, size, offset, flip, tilt, x1, y1, x2, y2);
        } catch (Throwable ignored) {
            // the preview is best effort
        }
    }

    /**
     * A Cosmetics tab thumbnail: the local player wearing only {@code selection}, framed on the head (hats, a
     * three-quarter front view) or on the back (wings, a three-quarter back view) so the item fills the tile.
     */
    public static void drawThumbnail(DrawContext context, int x1, int y1, int x2, int y2, CosmeticSelection selection,
                                     boolean wings) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc == null ? null : mc.player;
        if (player == null || selection == null || x2 - x1 < 8 || y2 - y1 < 8) return;
        try {
            EntityRenderer<? super ClientPlayerEntity, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
            EntityRenderState state = renderer.getAndUpdateRenderState(player, 1f);
            if (!(state instanceof PlayerEntityRenderState playerState)) return;
            CosmeticRenderer.overridePreview(playerState, selection);
            state.light = 0xF000F0;
            state.shadowPieces.clear();
            state.outlineColor = 0;
            playerState.bodyYaw = 180f + (wings ? 180f + 35f : 28f);
            playerState.relativeHeadYaw = 0f;
            playerState.pitch = 0f;
            playerState.hurt = false;
            playerState.isInSneakingPose = false;
            playerState.width /= playerState.baseScale;
            playerState.height /= playerState.baseScale;
            playerState.baseScale = 1f;
            int w = x2 - x1, h = y2 - y1;
            // Blocks shown: the head plus what sits above it, or the shoulders and the wing span.
            float size = wings ? Math.min(h / 2.0f, w / 2.3f) : Math.min(h / 1.05f, w / 1.05f);
            float centerY = wings ? 1.25f : 1.9f;
            Quaternionf flip = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf tilt = new Quaternionf().rotateX((wings ? 4f : 8f) * (float) Math.PI / 180f);
            flip.mul(tilt);
            context.addEntity(state, size, new Vector3f(0f, centerY, 0f), flip, tilt, x1, y1, x2, y2);
        } catch (Throwable ignored) {
            // a thumbnail is best effort
        }
    }
}

}
