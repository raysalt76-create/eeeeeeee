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
 * Instanced rounded-rectangle batcher — the companion to {@link MsdfText}.
 *
 * <p>Same shape as the text batcher: one instance per rect, quad generated from
 * {@code gl_VertexID}, one draw call per flush. Stride is 28 bytes
 * ({@code vec4 rect | float radius | int color | pad}).
 *
 * <p>Edges are antialiased from a signed distance field rather than sampled, so corners stay
 * smooth at any radius and any scale — which is what a hand-rolled quad-with-triangles rect
 * cannot do without either MSAA or a texture.
 *
 * <p>GL state is owned by {@link Msdf}, which brackets both batchers with a single save/restore
 * and flushes between primitive types to keep painter's order correct.
 */
final class RectBatch {

    private static final String SHADER_BASE = "/assets/dynamic_fps/msdf/shaders/";
    private static final int STRIDE = 28;
    private static final int INITIAL = 256;

    private int program;
    private int vao;
    private int vbo;
    private int uProj = -1;

    private ByteBuffer buffer;
    private int capacity;
    private int count;

    private boolean initialised;
    private boolean failed;

    boolean isUsable() {
        return initialised && !failed;
    }

    boolean init() {
        if (initialised || failed) return isUsable();
        try {
            int vert = compile(GL_VERTEX_SHADER, read(SHADER_BASE + "rect.vert"));
            int frag = compile(GL_FRAGMENT_SHADER, read(SHADER_BASE + "rect.frag"));

            program = glCreateProgram();
            glAttachShader(program, vert);
            glAttachShader(program, frag);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                throw new IOException("rect program link failed: " + glGetProgramInfoLog(program));
            }
            glDetachShader(program, vert);
            glDetachShader(program, frag);
            glDeleteShader(vert);
            glDeleteShader(frag);

            uProj = glGetUniformLocation(program, "proj");

            capacity = INITIAL;
            buffer = BufferUtils.createByteBuffer(capacity * STRIDE);

            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevVbo = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_DYNAMIC_DRAW);

            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 4, GL_FLOAT, false, STRIDE, 0L);
            glVertexAttribDivisor(0, 1);

            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 1, GL_FLOAT, false, STRIDE, 16L);
            glVertexAttribDivisor(1, 1);

            glEnableVertexAttribArray(2);
            glVertexAttribIPointer(2, 1, GL_INT, STRIDE, 20L);
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

    /** Set the projection for this frame. Assumes the caller already bracketed GL state. */
    void setProjection(int width, int height) {
        if (!isUsable()) return;
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        Matrix4f ortho = new Matrix4f().ortho(0.0f, width, height, 0.0f, -1.0f, 1.0f);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            ortho.get(fb);
            glUniformMatrix4fv(uProj, false, fb);
        }
        glUseProgram(prevProgram);
        count = 0;
        buffer.clear();
    }

    void rect(float x, float y, float w, float h, float radius, int argb) {
        if (!isUsable() || w <= 0.0f || h <= 0.0f) return;
        if (count >= capacity) grow();
        int p = buffer.position();
        buffer.putFloat(p, x);
        buffer.putFloat(p + 4, y);
        buffer.putFloat(p + 8, w);
        buffer.putFloat(p + 12, h);
        buffer.putFloat(p + 16, radius);
        buffer.putInt(p + 20, argb);
        buffer.putFloat(p + 24, 0.0f);
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

    void flush() {
        if (!isUsable() || count == 0) {
            count = 0;
            buffer.clear();
            return;
        }
        buffer.flip();

        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);

        buffer.clear();
        count = 0;
    }

    boolean hasPending() {
        return count > 0;
    }

    void destroy() {
        if (vao != 0) glDeleteVertexArrays(vao);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (program != 0) glDeleteProgram(program);
        vao = vbo = program = 0;
        initialised = false;
    }

    private static int compile(int type, String source) throws IOException {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException("rect shader compile failed: " + log);
        }
        return shader;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = RectBatch.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("rect shader missing: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
