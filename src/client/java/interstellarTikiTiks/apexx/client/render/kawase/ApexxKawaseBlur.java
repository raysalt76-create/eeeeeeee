package interstellarTikiTiks.apexx.client.render.kawase;

import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import org.lwjgl.BufferUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * A small render-thread-only Kawase blur compositor for the native Apexx menu.
 *
 * <p>The pass copies the current Minecraft color buffer into an owned texture, applies two
 * widening down samples and two tightening up samples, then writes the softened result back to
 * the framebuffer that was active when {@link #apply(int, int, int)} was called. The screen can
 * therefore use a real blurred game backdrop independent of the vanilla menu blur
 * setting.</p>
 *
 * <p>This class deliberately owns its OpenGL state bracket. A broken shader or framebuffer must
 * degrade to a sharp menu, never leave Minecraft with a foreign framebuffer, program, viewport,
 * or blend state.</p>
 */
public final class ApexxKawaseBlur {
    private static final IntBuffer VIEWPORT_SCRATCH = BufferUtils.createIntBuffer(4);
    private static final String BASE = "/assets/dynamic_fps/kawase/";
    private static final int PASS_DOWN = 0;
    private static final int PASS_UP = 1;
    private static final int PASS_COPY = 2;

    private static boolean attempted;
    private static boolean available;
    private static int width;
    private static int height;
    private static int sourceFbo;
    private static int sourceTexture;
    private static final int[] pingFbos = new int[2];
    private static final int[] pingTextures = new int[2];
    private static final int[] programs = new int[3];
    private static final int[] samplerUniforms = new int[3];
    private static final int[] texelUniforms = new int[3];
    private static final int[] offsetUniforms = new int[3];
    private static int quadVao;

    private ApexxKawaseBlur() {
    }

    public static boolean apply(int framebufferWidth, int framebufferHeight, int passes) {
        if (RenderBackendDetector.isVulkanBackend() || framebufferWidth <= 0 || framebufferHeight <= 0) {
            return false;
        }
        if (!ensure(framebufferWidth, framebufferHeight)) {
            return false;
        }

        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int[] viewport = new int[4];
        IntBuffer viewportBuffer = VIEWPORT_SCRATCH; // reused: a direct buffer per blur call churned off-heap memory
        viewportBuffer.clear();
        glGetIntegerv(GL_VIEWPORT, viewportBuffer);
        viewport[0] = viewportBuffer.get(0);
        viewport[1] = viewportBuffer.get(1);
        viewport[2] = viewportBuffer.get(2);
        viewport[3] = viewportBuffer.get(3);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);

        try {
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);

            // Preserve the active read buffer and copy the visible Minecraft frame into our source.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sourceFbo);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sourceTexture);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

            int count = Math.max(1, Math.min(4, passes));
            int input = sourceTexture;
            for (int i = 0; i < count; i++) {
                int target = i & 1;
                renderPass(PASS_DOWN, input, pingFbos[target], 0.65f + i * 0.24f);
                input = pingTextures[target];
            }
            for (int i = count - 1; i >= 0; i--) {
                // Never sample from and render into the same ping texture. The alternating target
                // is the opposite of the texture produced by the matching down pass.
                int target = (i + 1) & 1;
                renderPass(PASS_UP, input, pingFbos[target], 0.55f + i * 0.18f);
                input = pingTextures[target];
            }
            renderPass(PASS_COPY, input, drawFbo, 0.0f);
            return true;
        } catch (Throwable ignored) {
            available = false;
            return false;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            glUseProgram(previousProgram);
            glBindVertexArray(previousVao);
            glActiveTexture(previousActiveTexture);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            restore(GL_BLEND, blend);
            restore(GL_DEPTH_TEST, depth);
            restore(GL_CULL_FACE, cull);
            restore(GL_SCISSOR_TEST, scissor);
        }
    }

    private static void renderPass(int pass, int inputTexture, int targetFbo, float offset) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, width, height);
        glUseProgram(programs[pass]);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, inputTexture);
        glUniform1i(samplerUniforms[pass], 0);
        if (texelUniforms[pass] >= 0) {
            glUniform2f(texelUniforms[pass], 1.0f / width, 1.0f / height);
        }
        if (offsetUniforms[pass] >= 0) {
            glUniform1f(offsetUniforms[pass], offset);
        }
        glBindVertexArray(quadVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void restore(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    private static boolean ensure(int requestedWidth, int requestedHeight) {
        if (attempted && !available) {
            return false;
        }
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            if (!attempted) {
                attempted = true;
                initialisePrograms();
            }
            if (width != requestedWidth || height != requestedHeight || sourceFbo == 0) {
                resize(requestedWidth, requestedHeight);
            }
            return available;
        } catch (Throwable ignored) {
            available = false;
            disposeGl();
            return false;
        } finally {
            // Program and target creation bind their own resources. Restore the caller's state
            // before apply() snapshots it for the actual blur pass.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            glUseProgram(previousProgram);
            glBindVertexArray(previousVao);
            glActiveTexture(previousActiveTexture);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
        }
    }

    private static void initialisePrograms() throws IOException {
        programs[PASS_DOWN] = createProgram("kawase.vert", "kawase_down.frag");
        programs[PASS_UP] = createProgram("kawase.vert", "kawase_up.frag");
        programs[PASS_COPY] = createProgram("kawase.vert", "kawase_copy.frag");
        for (int i = 0; i < programs.length; i++) {
            samplerUniforms[i] = glGetUniformLocation(programs[i], "tex");
            texelUniforms[i] = glGetUniformLocation(programs[i], "texelSize");
            offsetUniforms[i] = glGetUniformLocation(programs[i], "offset");
        }
        quadVao = glGenVertexArrays();
        available = true;
    }

    private static void resize(int requestedWidth, int requestedHeight) {
        deleteTargets();
        width = requestedWidth;
        height = requestedHeight;
        sourceTexture = createTexture(width, height);
        sourceFbo = createFbo(sourceTexture);
        for (int i = 0; i < 2; i++) {
            pingTextures[i] = createTexture(width, height);
            pingFbos[i] = createFbo(pingTextures[i]);
        }
    }

    private static int createTexture(int w, int h) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return texture;
    }

    private static int createFbo(int texture) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Apexx Kawase framebuffer is incomplete");
        }
        return fbo;
    }

    private static int createProgram(String vertexName, String fragmentName) throws IOException {
        int vertex = compile(GL_VERTEX_SHADER, read(BASE + vertexName));
        int fragment = compile(GL_FRAGMENT_SHADER, read(BASE + fragmentName));
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IOException("Apexx Kawase program link failed: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) throws IOException {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException("Apexx Kawase shader compile failed: " + log);
        }
        return shader;
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = ApexxKawaseBlur.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing Apexx Kawase shader: " + path);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                output.write(chunk, 0, count);
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static void dispose() {
        try {
            disposeGl();
        } finally {
            attempted = false;
            available = false;
            width = height = 0;
        }
    }

    private static void disposeGl() {
        deleteTargets();
        if (quadVao != 0) {
            glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        for (int i = 0; i < programs.length; i++) {
            if (programs[i] != 0) {
                glDeleteProgram(programs[i]);
                programs[i] = 0;
            }
        }
    }

    private static void deleteTargets() {
        if (sourceFbo != 0) glDeleteFramebuffers(sourceFbo);
        if (sourceTexture != 0) glDeleteTextures(sourceTexture);
        sourceFbo = sourceTexture = 0;
        for (int i = 0; i < 2; i++) {
            if (pingFbos[i] != 0) glDeleteFramebuffers(pingFbos[i]);
            if (pingTextures[i] != 0) glDeleteTextures(pingTextures[i]);
            pingFbos[i] = pingTextures[i] = 0;
        }
    }
}
