package interstellarTikiTiks.apexx.client.gui.gpuui.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand.TextWeight;

/**
 * MSDF glyph metrics and text layout for the GPU UI. Kept apart from {@link GpuUiRenderer} (whose
 * static initialiser registers GPU pipelines) so layout code can measure text without a GPU, e.g.
 * in the offline GUI preview tool, which reads the same metrics file from the classpath.
 */
public final class GpuUiFontMetrics {
    /** The bundled fallback face. */
    public static final String METRICS_PATH = "assets/dynamic_fps/groov_font/regular.json";
    public static final String ATLAS_PATH = "assets/dynamic_fps/groov_font/regular.png";
    private static final String REGULAR_BASE = "gpuui/fonts/inter_regular";
    private static final String SEMIBOLD_BASE = "gpuui/fonts/manrope_semibold";
    private static final String FALLBACK_BASE = "groov_font/regular";
    public static final int ATLAS_SIZE = 4096;
    public static final float DISTANCE_RANGE = 32.0f;

    /** The whole-menu typeface choice ("Font Family" in Settings > Appearance). */
    public enum Family {
        /** Inter (body) / Manrope SemiBold (headings) -- the look the menu shipped with. */
        DEFAULT,
        /** Monocraft: an open-source recreation of Minecraft's own UI pixel font. */
        MINECRAFT,
        SERIF,
        SANS_SERIF
    }

    private static volatile Family activeFamily = Family.DEFAULT;

    public static void setFamily(Family family) {
        activeFamily = family == null ? Family.DEFAULT : family;
    }

    public static Family family() {
        return activeFamily;
    }

    private static String baseFor(Family family, TextWeight weight) {
        boolean bold = weight == TextWeight.SEMIBOLD;
        return switch (family) {
            case MINECRAFT -> bold ? "gpuui/fonts/minecraft_semibold" : "gpuui/fonts/minecraft_regular";
            case SERIF -> bold ? "gpuui/fonts/serif_semibold" : "gpuui/fonts/serif_regular";
            case SANS_SERIF -> bold ? "gpuui/fonts/sans_serif_semibold" : "gpuui/fonts/sans_serif_regular";
            case DEFAULT -> bold ? SEMIBOLD_BASE : REGULAR_BASE;
        };
    }

    /** Keyed by resource base path (unique per family+weight), not by weight alone, so a family switch
     * does not evict faces for the other weight and a repeat switch never re-reads the classpath. */
    private static volatile Map<String, Face> faces = Map.of();

    /**
     * User-controlled text size multiplier ("Font Size" in Settings > Appearance). Applied here,
     * not at each call site, so every caller's own {@code textWidth(...)} measurement (used to
     * center/fit/clip text) always matches what {@link #layout} actually draws.
     */
    private static volatile float textScale = 1.0f;

    public static void setTextScale(float scale) {
        if (!Float.isFinite(scale)) return;
        textScale = Math.max(0.5f, Math.min(2.0f, scale));
    }

    public static float textScale() {
        return textScale;
    }

    private GpuUiFontMetrics() { }

    public record Glyph(float advance, float left, float bottom, float right, float top,
                        float atlasLeft, float atlasBottom, float atlasRight, float atlasTop,
                        boolean hasBounds) { }

    /**
     * One loaded font face. {@code base} is the resource path (under {@code dynamic_fps}) without extension,
     * so the renderer binds the matching atlas and the offline preview reads the same image.
     */
    /**
     * Where the middle of a capital letter sits below a text command's {@code y}, as a fraction of
     * its (unscaled) size. Every face and weight is placed so its own cap height straddles this line,
     * so swapping family or weight -- or changing Font Size, which grows text about this line --
     * never moves text up or down. 0.6055 is Inter Regular's natural value, so the default font
     * draws exactly where it always did.
     */
    public static final float CAP_CENTER = 0.6055f;
    /** Reference cap height (Inter's) used to lay out multi-line blocks. */
    public static final float CAP_HEIGHT = 0.727f;

    /** {@code y} for a text command whose capitals are vertically centred on {@code centerY}. */
    public static float topForCenter(float centerY, float size) {
        return centerY - size * CAP_CENTER;
    }

    /** {@code y} for a text command vertically centred in the box {@code [boxY, boxY + boxH]}. */
    public static float topInBox(float boxY, float boxH, float size) {
        return topForCenter(boxY + boxH * 0.5f, size);
    }

    public record Face(String base, float ascender, float capHeight, Map<Integer, Glyph> glyphs) {
        public String atlasResource() {
            return "assets/dynamic_fps/" + base + ".png";
        }
    }

    /** One positioned glyph quad: screen rectangle plus atlas UVs (v flipped for the GPU). */
    @FunctionalInterface
    public interface GlyphSink {
        void accept(float left, float top, float width, float height, float u0, float v0, float u1, float v1);
    }

    /**
     * The atlas has no glyph for these characters (they used to draw as '?'). Until it is regenerated,
     * each is drawn as the closest characters it does have.
     */
    private static final Map<Integer, String> SUBSTITUTES = Map.of(
            0x22, "''",          // "  -> two apostrophes
            0x2026, "...",       // ellipsis
            0xd7, "x",           // multiplication sign
            0x2022, "·",    // bullet -> middle dot
            0x2192, ">",
            0x2190, "<");

    /** Replaces characters the atlas lacks with their substitutes; the same string when nothing needs it. */
    private static String expand(String text, Map<Integer, Glyph> atlas) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            String sub = atlas.containsKey(cp) ? null : SUBSTITUTES.get(cp);
            if (sub != null && out == null) out = new StringBuilder(text.length() + 8).append(text, 0, i);
            if (out != null) {
                if (sub != null) out.append(sub);
                else out.appendCodePoint(cp);
            }
            i += len;
        }
        return out == null ? text : out.toString();
    }

    /** Lays out {@code text} exactly as the GPU renderer draws it. */
    public static void layout(String text, float x, float y, float size, GlyphSink sink) {
        layout(text, x, y, size, TextWeight.REGULAR, sink);
    }

    public static void layout(String text, float x, float y, float size, TextWeight weight, GlyphSink sink) {
        if (text == null || text.isEmpty() || size <= 0f) return;
        float baseSize = size;
        size *= textScale;
        Face face = face(weight);
        Map<Integer, Glyph> atlas = face.glyphs();
        text = expand(text, atlas);
        float penX = x;
        // Cap centre pinned at CAP_CENTER of the unscaled size: callers centre labels in their boxes
        // against that line, and a larger Font Size grows the glyphs about it instead of downwards.
        float baseline = y + baseSize * CAP_CENTER + size * face.capHeight() * 0.5f;
        float tracking = 0.012f * size;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Glyph glyph = atlas.getOrDefault(codePoint, atlas.get((int) '?'));
            if (glyph == null) continue;
            if (glyph.hasBounds()) {
                float left = penX + glyph.left() * size;
                float top = baseline - glyph.top() * size;
                float width = (glyph.right() - glyph.left()) * size;
                float height = (glyph.top() - glyph.bottom()) * size;
                sink.accept(left, top, width, height,
                        glyph.atlasLeft() / ATLAS_SIZE, 1.0f - glyph.atlasTop() / ATLAS_SIZE,
                        glyph.atlasRight() / ATLAS_SIZE, 1.0f - glyph.atlasBottom() / ATLAS_SIZE);
            }
            penX += glyph.advance() * size + tracking;
        }
    }

    public static float textWidth(String text, float size) {
        return textWidth(text, size, TextWeight.REGULAR);
    }

    public static float textWidth(String text, float size, TextWeight weight) {
        if (text == null || text.isEmpty() || size <= 0f) return 0f;
        size *= textScale;
        Map<Integer, Glyph> atlas = face(weight).glyphs();
        text = expand(text, atlas);
        float width = 0f;
        float tracking = 0.012f * size;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Glyph g = atlas.getOrDefault(cp, atlas.get((int) '?'));
            if (g != null) width += g.advance() * size + tracking;
            i += Character.charCount(cp);
        }
        return width;
    }

    public static void invalidate() {
        faces = Map.of();
    }

    /** The face used for {@code weight} in the active {@link #family()}; falls back to the bundled
     * face if that font cannot be read (e.g. a family's atlas failed to ship with the payload). */
    public static Face face(TextWeight weight) {
        TextWeight w = weight == null ? TextWeight.REGULAR : weight;
        String base = baseFor(activeFamily, w);
        Face cached = faces.get(base);
        if (cached != null) return cached;
        synchronized (GpuUiFontMetrics.class) {
            cached = faces.get(base);
            if (cached != null) return cached;
            Face loaded;
            try {
                loaded = load(base);
            } catch (Exception exception) {
                try {
                    loaded = load(FALLBACK_BASE);
                } catch (Exception fallback) {
                    fallback.addSuppressed(exception);
                    throw new IllegalStateException("Unable to load Apexx MSDF glyph metrics", fallback);
                }
            }
            Map<String, Face> updated = new HashMap<>(faces);
            updated.put(base, loaded);
            faces = Map.copyOf(updated);
            return loaded;
        }
    }

    private static Face load(String base) throws IOException {
        Map<Integer, Glyph> parsed = new HashMap<>();
        float ascender;
        try (var resource = openMetrics(base);
             var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject atlas = root.getAsJsonObject("atlas");
            if (atlas.get("width").getAsInt() != ATLAS_SIZE || atlas.get("height").getAsInt() != ATLAS_SIZE
                    || atlas.get("distanceRange").getAsFloat() != DISTANCE_RANGE
                    || !"bottom".equals(atlas.get("yOrigin").getAsString())) {
                throw new IllegalStateException("Unexpected MSDF atlas layout");
            }
            ascender = root.getAsJsonObject("metrics").get("ascender").getAsFloat();
            JsonArray entries = root.getAsJsonArray("glyphs");
            for (JsonElement element : entries) {
                JsonObject item = element.getAsJsonObject();
                JsonObject plane = item.has("planeBounds") ? item.getAsJsonObject("planeBounds") : null;
                JsonObject bitmap = item.has("atlasBounds") ? item.getAsJsonObject("atlasBounds") : null;
                parsed.put(item.get("unicode").getAsInt(), new Glyph(
                        item.get("advance").getAsFloat(),
                        plane == null ? 0 : plane.get("left").getAsFloat(),
                        plane == null ? 0 : plane.get("bottom").getAsFloat(),
                        plane == null ? 0 : plane.get("right").getAsFloat(),
                        plane == null ? 0 : plane.get("top").getAsFloat(),
                        bitmap == null ? 0 : bitmap.get("left").getAsFloat(),
                        bitmap == null ? 0 : bitmap.get("bottom").getAsFloat(),
                        bitmap == null ? 0 : bitmap.get("right").getAsFloat(),
                        bitmap == null ? 0 : bitmap.get("top").getAsFloat(), plane != null && bitmap != null));
            }
        }
        // Cap height from 'H': its plane bounds carry the MSDF padding on both edges, and since 'H'
        // sits on the baseline that padding equals -bottom, so top + bottom is the real cap height.
        Glyph h = parsed.get((int) 'H');
        float capHeight = h != null && h.hasBounds() && h.top() + h.bottom() > 0.3f
                ? h.top() + h.bottom() : CAP_HEIGHT;
        return new Face(base, ascender, capHeight, Map.copyOf(parsed));
    }

    private static InputStream openMetrics(String base) throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getResourceManager() != null) {
            return client.getResourceManager()
                    .getResource(net.minecraft.util.Identifier.of("dynamic_fps", base + ".json"))
                    .orElseThrow().getInputStream();
        }
        // No running client (offline GUI preview): the same file straight from the classpath.
        String path = "assets/dynamic_fps/" + base + ".json";
        InputStream stream = GpuUiFontMetrics.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new IOException("Missing " + path);
        return stream;
    }
}
