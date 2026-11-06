package interstellarTikiTiks.apexx.client.util;

import com.mojang.blaze3d.systems.RenderSystem;
import interstellarTikiTiks.apexx.client.Apexx;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.awt.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_RGB;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

public class NanoVGRendererImpl implements IApexxRenderer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;

    private long nvg = 0;
    private int regularFont = -1;

    private boolean initialized = false;
    private boolean frameActive = false;

    private boolean savedDepthTest;
    private boolean savedCullFace;
    private boolean savedBlend;
    private int savedBlendSrcRgb;
    private int savedBlendDstRgb;
    private int savedBlendSrcAlpha;
    private int savedBlendDstAlpha;
    private int savedBlendEquation;
    private boolean savedScissorTest;
    private int[] savedViewport = new int[4];
    private int savedActiveTexture;
    private int savedTextureBinding;
    private int savedProgram;
    private int savedArrayBuffer;
    private int savedElementArrayBuffer;
    private int savedVertexArray;
    private int savedDrawFbo = -1;
    private int savedReadFbo = -1;
    private int[] savedScissorBox = new int[4];
    private boolean savedStencilTest;

    private int stencilRbo = 0;
    private int stencilRboWidth = -1;
    private int stencilRboHeight = -1;
    private int stencilRboFbo = -1;
    private Framebuffer blurFramebuffer = null;
    private static final Identifier BLUR_SHADER = Identifier.ofVanilla("blur");

    private int blurFBO = -1;
    private int blurTexture = -1;
    private int blurWidth = -1;
    private int blurHeight = -1;

    private boolean usingOffscreenFbo = false;
    private int offscreenPrevFbo = 0;
    private int offscreenFbo = 0;
    private int offscreenColorTex = 0;
    private int offscreenStencilRbo = 0;
    private int offscreenWidthPx = -1;
    private int offscreenHeightPx = -1;

    private int blitProgram = 0;
    private int blitVao = 0;
    private int blitVbo = 0;
    private int blitUniformTex = -1;

    private boolean isSalt(char c) {
        return c == '$' || c == '^' || c == '~' || c == '`';
    }

    @Override
    public void init() {
        if (initialized)
            return;

        try {

            if (!RenderSystem.isOnRenderThread()) {
                return;
            }

            nvg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);

            if (nvg == 0) {
                throw new RuntimeException("Could not initialize NanoVG");
            }

            loadFonts();
            ensureBlitResources();
            initialized = true;
        } catch (Exception e) {
            LOGGER.debug("NanoVG init failed, forcing Vanilla renderer");
            Renderer.forceVanillaRenderer();
            Renderer.get().init();
        }
    }

    private void ensureBlitResources() {
        if (blitProgram != 0 && blitVao != 0 && blitVbo != 0)
            return;

        String vsSrc = """
                #version 150 core
                in vec2 aPos;
                in vec2 aTex;
                out vec2 vTex;
                void main() {
                    vTex = aTex;
                    gl_Position = vec4(aPos, 0.0, 1.0);
                }
                """;

        String fsSrc = """
                #version 150 core
                uniform sampler2D uTex;
                in vec2 vTex;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(uTex, vTex);
                }
                """;

        int vs = compileShader(GL20.GL_VERTEX_SHADER, vsSrc);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fsSrc);

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glBindAttribLocation(program, 0, "aPos");
        GL20.glBindAttribLocation(program, 1, "aTex");
        GL20.glLinkProgram(program);

        int linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
        if (linked == 0) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            throw new IllegalStateException("NanoVG blit shader link failed: " + log);
        }

        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        blitProgram = program;
        blitUniformTex = GL20.glGetUniformLocation(blitProgram, "uTex");

        float[] vertices = new float[] {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
        };

        blitVao = GL30.glGenVertexArrays();
        blitVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(blitVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, blitVbo);

        var buf = BufferUtils.createFloatBuffer(vertices.length);
        buf.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 4 * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, (long) (2 * Float.BYTES));

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        int compiled = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (compiled == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("NanoVG blit shader compile failed: " + log);
        }
        return shader;
    }

    private void ensureOffscreenFbo(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("Invalid offscreen size: " + widthPx + "x" + heightPx);
        }
        if (offscreenFbo != 0 && offscreenWidthPx == widthPx && offscreenHeightPx == heightPx) {
            return;
        }

        deleteOffscreenFbo();

        int prevTex = 0;
        int prevFbo = 0;
        int prevRbo = 0;
        try {
            prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            prevRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        } catch (Throwable ignored) {
        }

        try {
            offscreenWidthPx = widthPx;
            offscreenHeightPx = heightPx;

            offscreenColorTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, offscreenColorTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, widthPx, heightPx, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

            offscreenStencilRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, offscreenStencilRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, widthPx, heightPx);

            offscreenFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, offscreenFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    offscreenColorTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER,
                    offscreenStencilRbo);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Offscreen FBO incomplete: 0x" + Integer.toHexString(status));
            }
        } finally {
            try {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRbo);
            } catch (Throwable ignored) {
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            } catch (Throwable ignored) {
            }
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            } catch (Throwable ignored) {
            }
        }
    }

    private void deleteOffscreenFbo() {
        if (offscreenFbo != 0) {
            try {
                GL30.glDeleteFramebuffers(offscreenFbo);
            } catch (Throwable ignored) {
            }
            offscreenFbo = 0;
        }
        if (offscreenStencilRbo != 0) {
            try {
                GL30.glDeleteRenderbuffers(offscreenStencilRbo);
            } catch (Throwable ignored) {
            }
            offscreenStencilRbo = 0;
        }
        if (offscreenColorTex != 0) {
            try {
                GL11.glDeleteTextures(offscreenColorTex);
            } catch (Throwable ignored) {
            }
            offscreenColorTex = 0;
        }
        offscreenWidthPx = -1;
        offscreenHeightPx = -1;
    }

    private void compositeOffscreenToGameFramebuffer() {
        if (!usingOffscreenFbo)
            return;
        if (offscreenColorTex == 0 || blitProgram == 0 || blitVao == 0)
            return;

        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, offscreenPrevFbo);
            if (savedReadFbo >= 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo);
            }
        } catch (Throwable ignored) {
        }

        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        GL11.glEnable(GL11.GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);

        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glActiveTexture(GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, offscreenColorTex);

        GL20.glUseProgram(blitProgram);

        int uTexLoc = GL20.glGetUniformLocation(blitProgram, "uTex");
        if (uTexLoc >= 0) {
            GL20.glUniform1i(uTexLoc, 0);
        }

        if (blitVao == 0) {
            LOGGER.debug("Blit VAO is 0, skipping composite!");
            return;
        }

        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);

        GL30.glBindVertexArray(blitVao);

        // Drain any GL errors accumulated from NanoVG's own rendering so our
        // check below only reports errors originating from this composite draw.
        //noinspection StatementWithEmptyBody
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}

        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        int error = GL11.glGetError();
        if (error != 0) {
            LOGGER.debug("GL Error during offscreen composite draw: " + error);
        }

        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void loadFonts() {
        LOGGER.debug("Loading NanoVG fonts...");
        try {
            ByteBuffer fontData = loadResource("/assets/dynamic_fps/font/font.ttf");
            if (fontData != null) {
                regularFont = nvgCreateFontMem(nvg, "regular", fontData, false);
            }
        } catch (Exception e) {

        }
    }

    private ByteBuffer loadResource(String path) {
        try (InputStream is = NanoVGRendererImpl.class.getResourceAsStream(path)) {
            if (is == null)
                return null;

            ReadableByteChannel rbc = Channels.newChannel(is);
            ByteBuffer buffer = memAlloc(8192);

            while (rbc.read(buffer) != -1) {
                if (buffer.remaining() == 0) {
                    ByteBuffer newBuffer = memAlloc(buffer.capacity() * 2);
                    buffer.flip();
                    newBuffer.put(buffer);
                    memFree(buffer);
                    buffer = newBuffer;
                }
            }
            buffer.flip();
            return buffer;
        } catch (Exception e) {
            return null;
        }
    }

    private void ensureStencilAttachment(int width, int height) {
        if (width <= 0 || height <= 0)
            return;

        int drawFbo;
        int prevDrawFbo;
        int prevReadFbo;
        int prevRbo;
        try {
            prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            drawFbo = prevDrawFbo;
            prevRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        } catch (Throwable ignored) {
            return;
        }

        if (drawFbo == 0)
            return;

        int stencilBits = 0;
        try {
            stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
        } catch (Throwable ignored) {
        }
        if (stencilBits > 0)
            return;

        try {
            if (stencilRbo == 0 || stencilRboWidth != width || stencilRboHeight != height || stencilRboFbo != drawFbo) {
                if (stencilRbo != 0) {
                    try {
                        GL30.glDeleteRenderbuffers(stencilRbo);
                    } catch (Throwable ignored) {
                    }
                }

                stencilRbo = GL30.glGenRenderbuffers();
                stencilRboWidth = width;
                stencilRboHeight = height;
                stencilRboFbo = drawFbo;

                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, stencilRbo);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_STENCIL_INDEX8, width, height);
            } else {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, stencilRbo);
            }

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER,
                    stencilRbo);
        } catch (Throwable ignored) {
        } finally {
            try {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRbo);
            } catch (Throwable ignored) {
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public boolean beginFrame(float width, float height, float pixelRatio) {
        RenderSystem.assertOnRenderThread();

        if (!initialized)
            init();

        if (!initialized) {
            return Renderer.get().beginFrame(width, height, pixelRatio);
        }

        try {
            if (Apexx.getInstance() != null &&
                    Apexx.getInstance().getModuleManager() != null &&
                    Apexx.getInstance().getModuleManager().shouldBlockNanoVG()) {
                frameActive = false;
                return false;
            }
        } catch (Exception e) {

        }

        try {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        } catch (Throwable ignored) {
        }

        try {
            savedDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        } catch (Throwable ignored) {
            savedDrawFbo = -1;
            savedReadFbo = -1;
        }

        savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        savedScissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        savedStencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);

        savedBlendSrcRgb = GL11.glGetInteger(GL_BLEND_SRC_RGB);
        savedBlendDstRgb = GL11.glGetInteger(GL_BLEND_DST_RGB);
        savedBlendSrcAlpha = GL11.glGetInteger(GL_BLEND_SRC_ALPHA);
        savedBlendDstAlpha = GL11.glGetInteger(GL_BLEND_DST_ALPHA);
        savedBlendEquation = GL11.glGetInteger(GL_BLEND_EQUATION_RGB);

        savedActiveTexture = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
        savedTextureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        savedArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        savedElementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        savedVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        usingOffscreenFbo = false;
        offscreenPrevFbo = 0;
        int stencilBits = 0;
        try {
            stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
        } catch (Throwable ignored) {
        }

        if (stencilBits <= 0) {
            try {
                ensureStencilAttachment(savedViewport[2], savedViewport[3]);
            } catch (Throwable ignored) {
            }
            try {
                stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
            } catch (Throwable ignored) {
            }
        }

        if (stencilBits <= 0) {
            try {
                ensureBlitResources();
                usingOffscreenFbo = true;
                offscreenPrevFbo = savedDrawFbo;
                ensureOffscreenFbo((int) width, (int) height);

                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, offscreenFbo);
                GL11.glViewport(0, 0, (int) width, (int) height);
                GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            } catch (Exception e) {
                if (LOGGER != null)
                    LOGGER.debug("Failed to create offscreen FBO, falling back to Vanilla: {}", e.getMessage());
                Renderer.forceVanillaRenderer();
                return Renderer.get().beginFrame(width, height, pixelRatio);
            }
        }

        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL11.glColorMask(true, true, true, true);
            glBlendEquation(GL_FUNC_ADD);

            GL11.glEnable(GL11.GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            nvgBeginFrame(nvg, width, height, pixelRatio);
        } catch (Exception e) {
            Renderer.forceVanillaRenderer();
            return Renderer.get().beginFrame(width, height, pixelRatio);
        }

        frameActive = true;
        return true;
    }

    @Override
    public void endFrame() {
        if (!frameActive)
            return;

        nvgEndFrame(nvg);
        frameActive = false;

        RenderSystem.assertOnRenderThread();

        glStencilMask(0xFF);
        glClear(GL_STENCIL_BUFFER_BIT);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);

        try {
            compositeOffscreenToGameFramebuffer();
        } catch (Throwable t) {
        } finally {
            usingOffscreenFbo = false;
            offscreenPrevFbo = 0;
        }

        try {
            if (savedDrawFbo >= 0) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo);
            }
            if (savedReadFbo >= 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo);
            }
        } catch (Throwable ignored) {
        }

        glActiveTexture(savedActiveTexture);
        glBindTexture(GL_TEXTURE_2D, savedTextureBinding);

        glBindVertexArray(savedVertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, savedArrayBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, savedElementArrayBuffer);

        glUseProgram(savedProgram);

        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        if (savedScissorTest) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        if (savedBlend)
            GL11.glEnable(GL11.GL_BLEND);
        else
            GL11.glDisable(GL11.GL_BLEND);

        glBlendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb, savedBlendSrcAlpha, savedBlendDstAlpha);
        glBlendEquation(savedBlendEquation);

        if (savedDepthTest)
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        else
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        glDepthMask(true);

        if (savedCullFace)
            GL11.glEnable(GL11.GL_CULL_FACE);
        else
            GL11.glDisable(GL11.GL_CULL_FACE);

        glColorMask(true, true, true, true);

        if (savedStencilTest)
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        else
            GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    @Override
    public void drawGlowingRect(DrawContext context, float x, float y, float width, float height, float radius,
            Color innerColor, Color outerColor, float glowSize) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor inner = NVGColor.malloc(stack);
            NVGColor outer = NVGColor.malloc(stack);
            nvgRGBA((byte) innerColor.getRed(), (byte) innerColor.getGreen(), (byte) innerColor.getBlue(),
                    (byte) innerColor.getAlpha(), inner);
            nvgRGBA((byte) outerColor.getRed(), (byte) outerColor.getGreen(), (byte) outerColor.getBlue(),
                    (byte) outerColor.getAlpha(), outer);

            float centerX = x + width / 2;
            float centerY = y + height / 2;

            NVGPaint paint = NVGPaint.malloc(stack);
            nvgRadialGradient(nvg, centerX, centerY, 0, glowSize, inner, outer, paint);

            nvgBeginPath(nvg);
            nvgRoundedRect(nvg, x - glowSize, y - glowSize, width + glowSize * 2, height + glowSize * 2,
                    radius + glowSize);
            nvgFillPaint(nvg, paint);
            nvgFill(nvg);
        }
    }

    @Override
    public void drawTexture(DrawContext context, int nvgImageId, float x, float y, float width, float height, float u,
            float v, float regionWidth, float regionHeight, float textureWidth, float textureHeight) {
        if (!frameActive || nvgImageId <= 0)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float u1 = u / textureWidth;
            float v1 = v / textureHeight;
            float u2 = (u + regionWidth) / textureWidth;
            float v2 = (v + regionHeight) / textureHeight;
            NVGPaint paint = NVGPaint.malloc(stack);
            float scaleX = width / (u2 - u1);
            float scaleY = height / (v2 - v1);
            float offsetX = -u1 * scaleX;
            float offsetY = -v1 * scaleY;
            nvgImagePattern(nvg, x + offsetX, y + offsetY, scaleX, scaleY, 0, nvgImageId, 1.0f, paint);
            nvgBeginPath(nvg);
            nvgRect(nvg, x, y, width, height);
            nvgFillPaint(nvg, paint);
            nvgFill(nvg);
        }
    }

    @Override
    public void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float radius,
            Color color) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            nvgBeginPath(nvg);
            nvgRoundedRect(nvg, x, y, width, height, radius);
            nvgFillColor(nvg, nvgColor);
            nvgFill(nvg);
        }
    }

    @Override
    public void drawRect(DrawContext context, float x, float y, float width, float height, Color color) {
        drawRoundedRect(context, x, y, width, height, 0.0f, color);
    }

    @Override
    public void drawRectOutline(DrawContext context, float x, float y, float width, float height, float strokeWidth,
            Color color) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            nvgBeginPath(nvg);
            nvgRect(nvg, x, y, width, height);
            nvgStrokeColor(nvg, nvgColor);
            nvgStrokeWidth(nvg, strokeWidth);
            nvgStroke(nvg);
        }
    }

    @Override
    public void drawRoundedRectGradient(DrawContext context, float x, float y, float width, float height, float radius,
            Color color1, Color color2, boolean vertical) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor c1 = NVGColor.malloc(stack);
            NVGColor c2 = NVGColor.malloc(stack);
            nvgRGBA((byte) color1.getRed(), (byte) color1.getGreen(), (byte) color1.getBlue(), (byte) color1.getAlpha(),
                    c1);
            nvgRGBA((byte) color2.getRed(), (byte) color2.getGreen(), (byte) color2.getBlue(), (byte) color2.getAlpha(),
                    c2);
            NVGPaint paint = NVGPaint.malloc(stack);
            if (vertical)
                nvgLinearGradient(nvg, x, y, x, y + height, c1, c2, paint);
            else
                nvgLinearGradient(nvg, x, y, x + width, y, c1, c2, paint);
            nvgBeginPath(nvg);
            nvgRoundedRect(nvg, x, y, width, height, radius);
            nvgFillPaint(nvg, paint);
            nvgFill(nvg);
        }
    }

    @Override
    public void drawCircle(DrawContext context, float x, float y, float radius, Color color) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            nvgBeginPath(nvg);
            nvgCircle(nvg, x, y, radius);
            nvgFillColor(nvg, nvgColor);
            nvgFill(nvg);
        }
    }

    @Override
    public void drawArc(DrawContext context, float cx, float cy, float radius, float startAngle, float sweepAngle,
            float strokeWidth, Color color) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            float startRad = (float) Math.toRadians(startAngle - 90);
            float endRad = (float) Math.toRadians(startAngle + sweepAngle - 90);
            nvgBeginPath(nvg);
            nvgArc(nvg, cx, cy, radius, startRad, endRad, NVG_CW);
            nvgStrokeColor(nvg, nvgColor);
            nvgStrokeWidth(nvg, strokeWidth);
            nvgStroke(nvg);
        }
    }

    @Override
    public void drawBlur(DrawContext context, float x, float y, float width, float height, float blurRadius) {

    }

    @Override
    public void drawRoundedRectOutline(DrawContext context, float x, float y, float width, float height, float radius,
            float strokeWidth, Color color) {
        if (!frameActive)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            nvgBeginPath(nvg);
            nvgRoundedRect(nvg, x, y, width, height, radius);
            nvgStrokeColor(nvg, nvgColor);
            nvgStrokeWidth(nvg, strokeWidth);
            nvgStroke(nvg);
        }
    }

    private void renderObfuscatedString(float x, float y, String text, boolean shadow, NVGColor color,
            NVGColor shadowColor) {
        float cursorX = x;
        float[] bounds = new float[4];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSalt(c))
                continue;
            String charStr = String.valueOf(c);
            if (shadow && shadowColor != null) {
                nvgFillColor(nvg, shadowColor);
                nvgText(nvg, cursorX + 1, y + 1, charStr);
            }
            nvgFillColor(nvg, color);
            nvgText(nvg, cursorX, y, charStr);
            float advance = nvgTextBounds(nvg, 0, 0, charStr, bounds);
            cursorX += advance;
        }
    }

    @Override
    public void drawText(DrawContext context, String text, float x, float y, float fontSize, Color color,
            boolean shadow) {
        if (!frameActive)
            return;
        if (regularFont != -1)
            nvgFontFaceId(nvg, regularFont);
        nvgFontSize(nvg, fontSize);
        nvgTextAlign(nvg, NVG_ALIGN_LEFT | NVG_ALIGN_TOP);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (shadow) {
                NVGColor shadowColor = NVGColor.malloc(stack);
                nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 150, shadowColor);
                nvgFillColor(nvg, shadowColor);
                nvgText(nvg, x + 1, y + 1, text);
            }
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            nvgFillColor(nvg, nvgColor);
            nvgText(nvg, x, y, text);
        }
    }

    @Override
    public void drawCenteredText(DrawContext context, String text, float x, float y, float fontSize, Color color,
            boolean shadow) {
        if (!frameActive)
            return;
        if (regularFont != -1)
            nvgFontFaceId(nvg, regularFont);
        nvgFontSize(nvg, fontSize);
        nvgTextAlign(nvg, NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE);
        float textWidth = getTextWidth(text, fontSize);
        float startX = x - (textWidth / 2.0f);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGColor nvgColor = NVGColor.malloc(stack);
            nvgRGBA((byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue(), (byte) color.getAlpha(),
                    nvgColor);
            NVGColor shadowColor = null;
            if (shadow) {
                shadowColor = NVGColor.malloc(stack);
                nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 150, shadowColor);
            }
            renderObfuscatedString(startX, y, text, shadow, nvgColor, shadowColor);
        }
    }

    @Override
    public float getTextWidth(String text, float fontSize) {
        if (!initialized)
            init();
        if (!initialized || nvg == 0)
            return 0.0f;
        if (text == null || text.isEmpty())
            return 0.0f;

        if (regularFont != -1)
            nvgFontFaceId(nvg, regularFont);
        nvgFontSize(nvg, fontSize);
        float totalWidth = 0;
        float[] bounds = new float[4];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSalt(c))
                continue;
            String charStr = String.valueOf(c);
            totalWidth += nvgTextBounds(nvg, 0, 0, charStr, bounds);
        }
        return totalWidth;
    }

    @Override
    public float getTextHeight(float fontSize) {
        return fontSize;
    }

    @Override
    public void scissor(DrawContext context, float x, float y, float width, float height) {
        if (frameActive)
            nvgScissor(nvg, x, y, width, height);
    }

    @Override
    public void setFontBlur(float blur) {
        if (frameActive)
            nvgFontBlur(nvg, blur);
    }

    @Override
    public void scissor(float x, float y, float width, float height) {
        scissor(null, x, y, width, height);
    }

    @Override
    public int createImageFromTexture(int textureId, int width, int height) {
        if (!initialized)
            init();
        if (!initialized)
            return -1;

        int flags = NVG_IMAGE_GENERATE_MIPMAPS | NVG_IMAGE_REPEATX | NVG_IMAGE_REPEATY;
        return nvglCreateImageFromHandle(nvg, textureId, width, height, flags);
    }

    @Override
    public void deleteImage(int imageId) {
        if (initialized && imageId > 0)
            nvgDeleteImage(nvg, imageId);
    }

    @Override
    public void drawTextureRounded(DrawContext context, int nvgImageId, float x, float y, float width, float height,
            float u, float v, float rW, float rH, float tW, float tH, float radius) {
        if (!frameActive || nvgImageId <= 0)
            return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float u1 = u / tW;
            float v1 = v / tH;
            float u2 = (u + rW) / tW;
            float v2 = (v + rH) / tH;
            NVGPaint paint = NVGPaint.malloc(stack);
            float scaleX = width / (u2 - u1);
            float scaleY = height / (v2 - v1);
            float offsetX = -u1 * scaleX;
            float offsetY = -v1 * scaleY;
            nvgImagePattern(nvg, x + offsetX, y + offsetY, scaleX, scaleY, 0, nvgImageId, 1.0f, paint);
            nvgBeginPath(nvg);
            nvgRoundedRect(nvg, x, y, width, height, radius);
            nvgFillPaint(nvg, paint);
            nvgFill(nvg);
        } catch (Exception e) {
            LOGGER.debug("NanoVG drawSubImage failed", e);
        }
    }

    @Override
    public int createImageFromFile(String resourcePath) {
        if (!initialized)
            init();
        if (!initialized)
            return -1;
        try (InputStream is = NanoVGRendererImpl.class.getResourceAsStream(resourcePath)) {
            if (is == null)
                return -1;

            int flags = NVG_IMAGE_GENERATE_MIPMAPS;

            byte[] buffer = is.readAllBytes();
            ByteBuffer imageBuffer = memAlloc(buffer.length);
            try {
                imageBuffer.put(buffer);
                imageBuffer.flip();
                // NanoVG decodes the image into its own texture during this call; the source bytes are not kept.
                return nvgCreateImageMem(nvg, flags, imageBuffer);
            } finally {
                memFree(imageBuffer);
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void resetScissor() {
        if (frameActive)
            nvgResetScissor(nvg);
    }

    public void emergencyCleanup() {
        if (frameActive) {
            try {
                nvgEndFrame(nvg);
            } catch (Exception e) {
                LOGGER.debug("NanoVG emergencyCleanup nvgEndFrame failed", e);
            }
            frameActive = false;
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override
    public void save() {
        if (frameActive)
            nvgSave(nvg);
    }

    @Override
    public void restore() {
        if (frameActive)
            nvgRestore(nvg);
    }

    @Override
    public void translate(float x, float y) {
        if (frameActive)
            nvgTranslate(nvg, x, y);
    }

    @Override
    public void rotate(float angle) {
        if (frameActive)
            nvgRotate(nvg, angle);
    }

    @Override
    public void scale(float x, float y) {
        if (frameActive)
            nvgScale(nvg, x, y);
    }

    @Override
    public void cleanup() {
        if (stencilRbo != 0) {
            try {
                GL30.glDeleteRenderbuffers(stencilRbo);
            } catch (Throwable ignored) {
            }
            stencilRbo = 0;
            stencilRboWidth = -1;
            stencilRboHeight = -1;
            stencilRboFbo = -1;
        }

        deleteOffscreenFbo();
        usingOffscreenFbo = false;
        offscreenPrevFbo = 0;

        if (blitVbo != 0) {
            try {
                GL15.glDeleteBuffers(blitVbo);
            } catch (Throwable ignored) {
            }
            blitVbo = 0;
        }
        if (blitVao != 0) {
            try {
                GL30.glDeleteVertexArrays(blitVao);
            } catch (Throwable ignored) {
            }
            blitVao = 0;
        }
        if (blitProgram != 0) {
            try {
                GL20.glDeleteProgram(blitProgram);
            } catch (Throwable ignored) {
            }
            blitProgram = 0;
            blitUniformTex = -1;
        }

        if (blurFramebuffer != null) {
            blurFramebuffer.delete();
            blurFramebuffer = null;
        }

        if (initialized) {
            nvgDelete(nvg);
            initialized = false;
        }
    }

    public long getContext() {
        return nvg;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static boolean isSupportedOnCurrentBackend() {
        if (RenderBackendDetector.isVulkanBackend()) {
            return false;
        }
        return !Renderer.isAndroidOrPojav();
    }

    public boolean isFallbackMode() {
        return false;
    }
}
