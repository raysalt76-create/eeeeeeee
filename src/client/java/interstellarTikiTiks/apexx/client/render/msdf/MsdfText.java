package interstellarTikiTiks.apexx.client.render.msdf;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Instanced MSDF glyph batcher: one draw call per flush, one instance per glyph.
 *
 * <h2>Layout</h2>
 * Stride is <b>36 bytes</b> — {@code vec4 rect | vec4 uv | int color} — and the quad itself is
 * generated in the vertex shader from {@code gl_VertexID}, so there is no vertex or index buffer.
 * The renderer this was adapted from used 116 bytes because it carried a full per-glyph model
 * matrix and a depth value; position and scale are baked in on the CPU here instead, and ordering
 * within a flush is just instance order, which removes both.
 *
 * <h2>GL state</h2>
 * This drops into Minecraft's frame, so every call site must be bracketed by
 * {@link #begin(int, int)} / {@link #end()}, which save and restore every piece of GL state they
 * touch. Leaking a bound VAO, program or blend mode back into vanilla corrupts the rest of the
 * frame in ways that are extremely hard to trace.
 */
public final class MsdfText {

    private static final String SHADER_BASE = "/assets/dynamic_fps/msdf/shaders/";
    private static final int STRIDE = 36;
    private static final int INITIAL_GLYPHS = 512;

    private int program;
    private int vao;
    private int vbo;
    private int uProj = -1;
    private int uTex = -1;
    private int uPxRange = -1;

    private ByteBuffer buffer;
    private int capacity;
    private int count;

    private MsdfFont boundFont;
    private boolean initialised;
    private boolean failed;

    // Saved GL state for the begin/end bracket.
    private int savedProgram, savedVao, savedArrayBuffer, savedTexture, savedActiveTexture;
    private boolean savedBlend, savedDepthTest, savedCull, savedScissor;
    private int savedBlendSrcRgb, savedBlendDstRgb, savedBlendSrcAlpha, savedBlendDstAlpha;

    public boolean isUsable() {
        return initialised && !failed;
    }

    /** Compiles the program and allocates the instance buffer. Safe to call repeatedly. */
    public boolean init() {
        if (initialised || failed) return isUsable();
        try {
            int vert = compile(GL_VERTEX_SHADER, read(SHADER_BASE + "msdf.vert"));
            int frag = compile(GL_FRAGMENT_SHADER, read(SHADER_BASE + "msdf.frag"));

            program = glCreateProgram();
            glAttachShader(program, vert);
            glAttachShader(program, frag);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                throw new IOException("MSDF program link failed: " + glGetProgramInfoLog(program));
            }
            glDetachShader(program, vert);
            glDetachShader(program, frag);
            glDeleteShader(vert);
            glDeleteShader(frag);

            uProj = glGetUniformLocation(program, "proj");
            uTex = glGetUniformLocation(program, "tex");
            uPxRange = glGetUniformLocation(program, "pxRange");

            capacity = INITIAL_GLYPHS;
            buffer = BufferUtils.createByteBuffer(capacity * STRIDE);

            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevVbo = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_DYNAMIC_DRAW);

            // All three attributes advance once per INSTANCE, not per vertex.
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 4, GL_FLOAT, false, STRIDE, 0L);
            glVertexAttribDivisor(0, 1);

            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, STRIDE, 16L);
            glVertexAttribDivisor(1, 1);

            glEnableVertexAttribArray(2);
            glVertexAttribIPointer(2, 1, GL_INT, STRIDE, 32L);
            glVertexAttribDivisor(2, 1);

            glBindVertexArray(prevVao);
            glBindBuffer(GL_ARRAY_BUFFER, prevVbo);

            initialised = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            try { destroy(); } catch (Throwable ignored) { }
            return false;
        }
    }

    /**
     * Begin a batch for a screen of {@code width} x {@code height}, saving GL state and setting an
     * orthographic projection with Y pointing DOWN, matching screen conventions.
     */
    public boolean begin(int width, int height) {
        if (!init()) return false;
        if (width <= 0 || height <= 0) return false;

        savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
        savedVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        savedArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        savedActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(savedActiveTexture);
        savedBlend = glIsEnabled(GL_BLEND);
        savedDepthTest = glIsEnabled(GL_DEPTH_TEST);
        savedCull = glIsEnabled(GL_CULL_FACE);
        savedScissor = glIsEnabled(GL_SCISSOR_TEST);
        savedBlendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        savedBlendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        savedBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        savedBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);

        glUseProgram(program);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        Matrix4f ortho = new Matrix4f().ortho(0.0f, width, height, 0.0f, -1.0f, 1.0f);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            ortho.get(fb);
            glUniformMatrix4fv(uProj, false, fb);
        }
        glUniform1f(uPxRange, 8.0f);   // matches "distanceRange" in the atlas JSON
        glUniform1i(uTex, 0);

        count = 0;
        boundFont = null;
        buffer.clear();
        return true;
    }

    /**
     * Queue a string. Only one font may be batched at a time; switching fonts flushes first, so
     * mixing fonts costs one draw call per switch rather than producing wrong glyphs.
     */
    public void text(MsdfFont font, CharSequence text, float x, float y, float size, int argb) {
        if (!isUsable() || font == null || text == null || text.length() == 0) return;
        if (boundFont != null && boundFont != font) flush();
        boundFont = font;

        float ascender = font.getAscender();
        float lineHeight = font.getLineHeight(size);
        float atlasW = font.getAtlasWidth();
        float atlasH = font.getAtlasHeight();
        float startX = x;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                x = startX;
                y += lineHeight;
                continue;
            }
            Glyph glyph = font.getGlyph(c);
            if (glyph == null) continue;

            if (glyph.hasGeometry()) {
                float x0 = x + glyph.getPlaneLeft() * size;
                // Plane bounds are measured up from the baseline; screen Y grows downward, so the
                // baseline sits at y + ascender*size and the glyph top is subtracted from it.
                float y0 = y + ascender * size - glyph.getPlaneTop() * size;
                float w = (glyph.getPlaneRight() - glyph.getPlaneLeft()) * size;
                float h = (glyph.getPlaneTop() - glyph.getPlaneBottom()) * size;

                // yOrigin is "bottom" in the atlas, hence the V flip.
                float u0 = glyph.getAtlasLeft() / atlasW;
                float v0 = 1.0f - (glyph.getAtlasTop() / atlasH);
                float uW = (glyph.getAtlasRight() - glyph.getAtlasLeft()) / atlasW;
                float vH = (glyph.getAtlasTop() - glyph.getAtlasBottom()) / atlasH;

                push(x0, y0, w, h, u0, v0, uW, vH, argb);
            }
            x += size * glyph.getAdvance();
        }
    }

    /**
     * Queue a string with a soft drop shadow. The shadow is the same text re-drawn at 25%
     * luminance and a sub-pixel offset — cheap, and it is what keeps light text readable over a
     * bright world without an outline pass.
     */
    public void textWithShadow(MsdfFont font, CharSequence text, float x, float y, float size, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * 0.25f);
        int g = (int) (((argb >> 8) & 0xFF) * 0.25f);
        int b = (int) ((argb & 0xFF) * 0.25f);
        int shadow = (a << 24) | (r << 16) | (g << 8) | b;
        text(font, text, x + 0.75f, y + 0.75f, size, shadow);
        text(font, text, x, y, size, argb);
    }

    private void push(float x, float y, float w, float h,
                      float u, float v, float uw, float vh, int argb) {
        if (count >= capacity) grow();
        int p = buffer.position();
        buffer.putFloat(p, x);
        buffer.putFloat(p + 4, y);
        buffer.putFloat(p + 8, w);
        buffer.putFloat(p + 12, h);
        buffer.putFloat(p + 16, u);
        buffer.putFloat(p + 20, v);
        buffer.putFloat(p + 24, uw);
        buffer.putFloat(p + 28, vh);
        buffer.putInt(p + 32, argb);
        buffer.position(p + STRIDE);
        count++;
    }

    private void grow() {
        int newCapacity = capacity * 2;
        ByteBuffer bigger = BufferUtils.createByteBuffer(newCapacity * STRIDE);
        buffer.flip();
        bigger.put(buffer);
        buffer = bigger;
        capacity = newCapacity;

        int prev = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, prev);
    }

    /** Draw everything queued so far. One glDrawArraysInstanced for the whole batch. */
    public void flush() {
        if (!isUsable() || count == 0 || boundFont == null) {
            count = 0;
            buffer.clear();
            return;
        }
        buffer.flip();

        // Bind our own program explicitly. The rect batcher that shares this begin/end bracket
        // switches the current program to ITS shader in its own flush and does not restore it, so
        // by the time a text flush runs the rect program may be bound. Drawing the 36-byte glyph
        // instance buffer through the 28-byte rect shader reinterprets every attribute and smears
        // the text into garbage quads. The projection/pxRange/tex uniforms set in begin() live on
        // this program object, so re-selecting it is all that is needed.
        glUseProgram(program);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, boundFont.getTextureId());

        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);

        buffer.clear();
        count = 0;
    }

    /** Flush the remainder and put every piece of GL state back exactly as we found it. */
    public void end() {
        if (!isUsable()) return;
        flush();

        glBindVertexArray(savedVao);
        glBindBuffer(GL_ARRAY_BUFFER, savedArrayBuffer);
        glUseProgram(savedProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, savedTexture);
        glActiveTexture(savedActiveTexture);

        glBlendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb, savedBlendSrcAlpha, savedBlendDstAlpha);
        setEnabled(GL_BLEND, savedBlend);
        setEnabled(GL_DEPTH_TEST, savedDepthTest);
        setEnabled(GL_CULL_FACE, savedCull);
        setEnabled(GL_SCISSOR_TEST, savedScissor);

        boundFont = null;
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) glEnable(cap); else glDisable(cap);
    }

    public void destroy() {
        if (vao != 0) glDeleteVertexArrays(vao);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (program != 0) glDeleteProgram(program);
        vao = vbo = program = 0;
        initialised = false;
    }

    // ── Shader loading ──────────────────────────────────────────────────────────

    private static int compile(int type, String source) throws IOException {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException("MSDF shader compile failed: " + log);
        }
        return shader;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = MsdfText.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("MSDF shader missing: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
