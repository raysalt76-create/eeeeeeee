package interstellarTikiTiks.apexx.client.render.msdf;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;

/**
 * An msdf-atlas-gen font: one RGBA atlas texture plus per-glyph metrics.
 *
 * <h2>Why the atlas is uploaded by hand</h2>
 * The PNG is decoded with STB and uploaded straight to a GL texture rather than going through
 * Minecraft's texture manager. The distance field must be sampled with <b>LINEAR filtering, no
 * mipmaps, and CLAMP_TO_EDGE</b>: mipmapping averages distances across glyphs and destroys the
 * field (text turns to mush at small sizes), and wrapping would bleed neighbouring glyphs in at
 * the atlas edge. Owning the texture outright is the simplest way to guarantee those three.
 */
public final class MsdfFont {

    private static final String BASE = "/assets/dynamic_fps/msdf/fonts/";

    /**
     * Direct codepoint -> glyph lookup. A flat array trades a little memory for an O(1) hit with
     * no boxing on the hot path; sized to the Basic Multilingual Plane, which covers every glyph
     * these atlases contain.
     */
    private final Glyph[] glyphs = new Glyph[0x10000];

    private final int textureId;
    private final int atlasWidth;
    private final int atlasHeight;
    private final float ascender;
    private final float descender;
    private final float lineHeight;

    public MsdfFont(String name) throws IOException {
        JsonObject json;
        try (InputStream stream = open(BASE + name + ".json")) {
            json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
        }

        JsonObject atlas = json.getAsJsonObject("atlas");
        this.atlasWidth = atlas.get("width").getAsInt();
        this.atlasHeight = atlas.get("height").getAsInt();

        JsonObject metrics = json.getAsJsonObject("metrics");
        this.ascender = metrics.get("ascender").getAsFloat();
        this.descender = metrics.get("descender").getAsFloat();
        this.lineHeight = metrics.get("lineHeight").getAsFloat();

        for (JsonElement element : json.getAsJsonArray("glyphs")) {
            Glyph glyph = Glyph.parse(element.getAsJsonObject());
            int cp = glyph.getUnicode();
            if (cp >= 0 && cp < glyphs.length) {
                glyphs[cp] = glyph;
            }
        }

        this.textureId = uploadAtlas(BASE + name + ".png");
    }

    private static InputStream open(String path) throws IOException {
        InputStream in = MsdfFont.class.getResourceAsStream(path);
        if (in == null) throw new IOException("MSDF resource missing: " + path);
        return in;
    }

    // NOTE ON SHAPE: this used to be one method. It is split into readAll/decode/uploadTexture and
    // the three pixel-store helpers because ProGuard 7.6.1's partial evaluator throws
    // "Value \"i\" is not a reference value" during preverification when the whole thing sits in a
    // single frame (two try-with-resources plus a try/finally plus a dozen int locals). Each piece
    // below is small enough to evaluate cleanly. Keep them separate.

    private static int uploadAtlas(String path) throws IOException {
        byte[] bytes = readAll(path);

        // STB needs a DIRECT buffer; a heap array will not do.
        ByteBuffer encoded = BufferUtils.createByteBuffer(bytes.length);
        encoded.put(bytes).flip();

        int[] size = new int[2];
        ByteBuffer pixels = decode(path, encoded, size);   // frees itself on every failure path
        try {
            return uploadTexture(pixels, size[0], size[1]);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }

    private static byte[] readAll(String path) throws IOException {
        try (InputStream in = open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    /**
     * Decodes the atlas to RGBA and writes {width, height} into {@code sizeOut}.
     *
     * <p>Everything STB hands back is checked BEFORE the GL driver is allowed near it:
     * {@code glTexImage2D} reads {@code w*h*4} bytes straight out of this pointer with no bounds
     * check of its own, so a short or zero-sized buffer is an instant EXCEPTION_ACCESS_VIOLATION
     * inside the driver — a hard JVM kill that no {@code catch (Throwable)} can intercept.
     */
    private static ByteBuffer decode(String path, ByteBuffer encoded, int[] sizeOut) throws IOException {
        ByteBuffer pixels;
        int w, h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            IntBuffer pc = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(encoded, pw, ph, pc, 4);
            if (pixels == null) {
                throw new IOException("STB failed to decode " + path + ": " + STBImage.stbi_failure_reason());
            }
            w = pw.get(0);
            h = ph.get(0);
        }

        long need = (long) w * (long) h * 4L;
        if (w <= 0 || h <= 0 || pixels.remaining() < need) {
            int got = pixels.remaining();
            STBImage.stbi_image_free(pixels);
            throw new IOException("MSDF atlas " + path + " decoded to an unusable buffer: "
                    + w + "x" + h + " needs " + need + " bytes, got " + got);
        }

        sizeOut[0] = w;
        sizeOut[1] = h;
        return pixels;
    }

    private static int uploadTexture(ByteBuffer pixels, int w, int h) {
        int id = glGenTextures();
        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int[] store = savePixelStore();
        try {
            resetPixelStore();
            glBindTexture(GL_TEXTURE_2D, id);
            // No mipmaps: averaging distance values across glyphs destroys the field.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } finally {
            restorePixelStore(store);
            glBindTexture(GL_TEXTURE_2D, prevTexture);
        }
        return id;
    }

    /**
     * Pixel-store unpack state is global, and Minecraft leaves it dirty: its own NativeImage
     * uploads set GL_UNPACK_ROW_LENGTH / SKIP_PIXELS / SKIP_ROWS for sub-image blits and do not
     * always restore them. With a stale ROW_LENGTH wider than our atlas, glTexImage2D strides
     * ROW_LENGTH*4 bytes per row instead of w*4 and runs straight off the end of the STB buffer —
     * which is the nvoglv64.dll access violation seen when Nametags first initialises the font
     * mid-HUD-render. A bound PIXEL_UNPACK_BUFFER corrupts the same call by reinterpreting our
     * pointer as a buffer offset. So: save every field we touch, force the defaults this upload
     * assumes, then put it all back so Minecraft's own texture path is unaffected.
     */
    private static int[] savePixelStore() {
        return new int[] {
                glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
                glGetInteger(GL_UNPACK_ALIGNMENT),
                glGetInteger(GL_UNPACK_ROW_LENGTH),
                glGetInteger(GL_UNPACK_SKIP_ROWS),
                glGetInteger(GL_UNPACK_SKIP_PIXELS),
                glGetInteger(GL_UNPACK_IMAGE_HEIGHT),
                glGetInteger(GL_UNPACK_SKIP_IMAGES),
        };
    }

    private static void resetPixelStore() {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);     // 0 = "rows are exactly `width` wide"
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
    }

    private static void restorePixelStore(int[] s) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, s[1]);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, s[2]);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, s[3]);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, s[4]);
        glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, s[5]);
        glPixelStorei(GL_UNPACK_SKIP_IMAGES, s[6]);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, s[0]);
    }

    public Glyph getGlyph(int codepoint) {
        return (codepoint >= 0 && codepoint < glyphs.length) ? glyphs[codepoint] : null;
    }

    /**
     * Rendered width of {@code text} at {@code size}.
     *
     * <p>{@code size} is the em height in screen units, so every metric here is a plain multiple
     * of it — that is what makes the font scale continuously instead of snapping to fixed sizes.
     */
    public float getWidth(CharSequence text, float size) {
        float sum = 0.0f;
        float line = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sum = Math.max(sum, line);
                line = 0.0f;
                continue;
            }
            Glyph glyph = getGlyph(c);
            if (glyph != null) line += size * glyph.getAdvance();
        }
        return Math.max(sum, line);
    }

    public float getLineHeight(float size) {
        return lineHeight * size;
    }

    /** Cap-to-baseline height at {@code size} — the useful figure for vertically centring a row. */
    public float getTextHeight(float size) {
        return ascender * size;
    }

    public int getTextureId()  { return textureId; }
    public int getAtlasWidth() { return atlasWidth; }
    public int getAtlasHeight(){ return atlasHeight; }
    public float getAscender() { return ascender; }
    public float getDescender(){ return descender; }

    public void destroy() {
        if (textureId != 0) glDeleteTextures(textureId);
    }
}
