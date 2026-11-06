package interstellarTikiTiks.apexx.client.gui.gpuui.render.effects;

/*
 * The simulation algorithm and the GLSL passes below (advection, curl, vorticity confinement,
 * divergence, Jacobi pressure, gradient subtraction, gaussian splats, bloom prefilter/blur/final and
 * the shaded display) are adapted from Pavel Dobryakov's "WebGL Fluid Simulation":
 *   https://github.com/PavelDoGreat/WebGL-Fluid-Simulation
 *
 * MIT License
 *
 * Copyright (c) 2017 Pavel Dobryakov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
 * OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiClipMask;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.PlatformPreset;
import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import interstellarTikiTiks.apexx.client.util.render.SafePipelines;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.opengl.GL33.*;

/**
 * The ClickGUI "Water" atmosphere: a faithful port of the WebGL stable-fluids dye simulation.
 *
 * <p><b>Why raw OpenGL:</b> Blaze3D's {@code TextureFormat} in 1.21.11 is RGBA8 / RED8 / RED8I /
 * DEPTH32 only, and 8-bit velocity/pressure/dye fields are what made the earlier sim smear and band.
 * The fields here are half-float ({@code RGBA16F} dye, {@code RG16F} velocity, {@code R16F}
 * pressure/divergence/curl, each falling back to a wider half-float format if the narrower one is not
 * colour-renderable) in textures and framebuffers this class owns, driven by its own
 * {@code #version 330 core} programs and an attribute-less fullscreen triangle.
 *
 * <p><b>Integration:</b> during screen extraction {@link #render} records the frame's parameters and adds
 * a GUI element that samples an RGBA8 Blaze3D texture (so it draws in the GUI's own pass, inside the
 * rounded clip, into whatever target the flush uses, SS-Proof overlay included). The simulation step and
 * the shaded/bloomed display into that texture run in {@link #runPending()}, called from the
 * GuiRenderer HEAD hook before the deferred GUI draws. Every piece of GL state touched there is read
 * first and restored afterwards, so Blaze3D's state caches stay valid.
 *
 * <p><b>Fail-safe:</b> any shader compile/link failure, incomplete framebuffer, missing half-float
 * support, Vulkan backend or exception disables the sim for the session with one log line; {@link
 * #usable()} then turns false and the screen draws the stateless water material instead.
 */
public final class GpuFluidSimulation {
    private static final Identifier LIQUID_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_liquid");
    private static final Identifier DISPLAY_FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_fluid_display");

    private static final VertexFormat UI_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build();

    /** Composites the finished RGBA8 output into the rounded pane (shared with the clip-mask path). */
    private static final RenderPipeline DISPLAY_PIPELINE = SafePipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_fluid_display"))
                    .withVertexShader(LIQUID_VERTEX)
                    .withFragmentShader(DISPLAY_FRAGMENT)
                    .withVertexFormat(UI_FORMAT, VertexFormat.DrawMode.QUADS)
                    .withSampler("Sampler0")
                    .withSampler("Sampler1")
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .build());

    // ---- Fixed constants from the WebGL demo ----
    private static final float PRESSURE_DECAY = 0.8f;
    private static final float COLOR_UPDATE_SPEED = 10f;
    private static final int BLOOM_ITERATIONS = 8;
    private static final float BLOOM_INTENSITY = 0.8f;
    private static final float BLOOM_THRESHOLD = 0.6f;
    private static final float BLOOM_SOFT_KNEE = 0.7f;
    private static final float MAX_DT = 1f / 60f;
    /** A gap this long between requested frames means the menu was closed and reopened. */
    private static final long SESSION_GAP_NANOS = 350_000_000L;

    // Quality tiers: {velocity short side, dye short side, bloom short side}.
    private static final int[][] TIERS = { { 64, 256, 128 }, { 128, 512, 256 }, { 128, 1024, 256 } };

    // ---- Settings pushed by the screen each frame (see configure) ----
    private static int cfgQuality = 2;
    private static float cfgCurl = 30f;
    private static float cfgSplatRadius = 0.25f;
    private static float cfgSplatForce = 6000f;
    private static float cfgDyeDissipation = 1f;
    private static float cfgVelocityDissipation = 0.2f;
    private static int cfgPressureIterations = 20;
    private static boolean cfgShading = true;
    private static boolean cfgBloom = true;
    private static boolean cfgRainbow = true;
    private static boolean cfgRandomSplats = true;
    private static boolean cfgPaused;

    // ---- Per-frame request recorded during extraction ----
    private static boolean pending;
    private static int unconsumedFrames;
    private static long lastRequestNanos;
    private static boolean newSession = true;
    private static float reqCursorU = Float.NaN, reqCursorV = Float.NaN;
    private static boolean reqInject;
    private static float reqFlow = 1f;
    private static int reqDye0, reqDye1, reqDye2, reqColorCount = 3;
    private static int simW, simH, dyeW, dyeH, bloomW, bloomH;
    private static float aspect = 1f;

    // ---- Adaptive quality ----
    private static int qualityDrop;
    private static float avgFrameSeconds = 1f / 60f;
    private static int slowFrames;

    // ---- Simulation clock / pointer ----
    private static long lastStepNanos;
    private static float prevU = Float.NaN, prevV = Float.NaN;
    private static float colorTimer;
    private static int paletteIndex;
    private static final float[] pointerColor = { 0.15f, 0f, 0f };
    private static final float[] scratchColor = new float[3];

    // ---- Failure / lifecycle ----
    private static boolean failed;
    private static boolean cleanupRegistered;
    private static volatile boolean releaseRequested;
    private static final Object LOCK = new Object();

    // ---- Blaze3D output texture sampled by the GUI element ----
    private static GpuUiEffectPasses.Target output;
    private static int outputFbo;
    private static int outputFboTexture;

    // ---- Raw GL resources ----
    private static boolean glReady;
    private static int vao;
    private static Program copyProgram, clearProgram, splatProgram, advectionProgram, divergenceProgram,
            curlProgram, vorticityProgram, pressureProgram, gradientProgram,
            bloomPrefilterProgram, bloomBlurProgram, bloomFinalProgram, displayProgram;
    private static int[] fmtRgba, fmtRg, fmtR;
    private static DoubleFbo velocity, dye, pressure;
    private static Fbo divergence, curl, bloom;
    private static final Fbo[] bloomChain = new Fbo[BLOOM_ITERATIONS];
    private static int bloomChainLength;
    private static boolean samplerObjects;

    // ---- Saved GL state scratch (preallocated; no per-frame allocation) ----
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final ByteBuffer COLOR_MASK = BufferUtils.createByteBuffer(16);
    private static final FloatBuffer CLEAR_COLOR = BufferUtils.createFloatBuffer(16);

    private GpuFluidSimulation() { }

    /** Registers the composite pipeline so it enters the precompile/reload set, and the cleanup hooks. */
    public static void registerPipelines() {
        if (DISPLAY_PIPELINE == null) throw new IllegalStateException("Fluid display pipeline was not registered");
        ensureCleanupRegistered();
    }

    /** True when the dye simulation can run here; otherwise the caller draws the stateless water material. */
    public static boolean usable() {
        if (failed) return false;
        try {
            return PlatformPreset.offscreenEffects() && !RenderBackendDetector.isVulkanBackend()
                    && GpuUiEffectPasses.isAvailable() && SafePipelines.usable(DISPLAY_PIPELINE);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Pushes the user's settings. {@code quality} 0 = Low, 1 = Medium, 2 = High; {@code paused} freezes the
     * simulation (closing animation) while the last frame keeps displaying.
     */
    public static void configure(int quality, float curlStrength, float splatRadius, float splatForce,
                                 float dyeDissipation, float velocityDissipation, int pressureIterations,
                                 boolean shading, boolean bloomOn, boolean rainbow, boolean randomSplats,
                                 boolean paused) {
        cfgQuality = Math.max(0, Math.min(2, quality));
        cfgCurl = clamp(curlStrength, 0f, 100f);
        cfgSplatRadius = clamp(splatRadius, 0.01f, 2f);
        cfgSplatForce = clamp(splatForce, 0f, 50_000f);
        cfgDyeDissipation = clamp(dyeDissipation, 0f, 10f);
        cfgVelocityDissipation = clamp(velocityDissipation, 0f, 10f);
        cfgPressureIterations = Math.max(1, Math.min(80, pressureIterations));
        cfgShading = shading;
        cfgBloom = bloomOn;
        cfgRainbow = rainbow;
        cfgRandomSplats = randomSplats;
        cfgPaused = paused;
    }

    /**
     * Records this frame's request and adds the composite element. The simulation itself runs later, in
     * {@link #runPending()}, before the deferred GUI draws consume the output texture.
     *
     * @param cursorU,cursorV cursor in pane UV (top-left origin); outside 0..1 means outside the pane
     */
    public static void render(GuiRenderState state, Matrix3x2fc pose,
                              float x, float y, float width, float height, float cornerRadius,
                              float cursorU, float cursorV, float cursorVelU, float cursorVelV,
                              int dye0, int dye1, int dye2, int colorCount,
                              float opacity, float flow, boolean inject,
                              ScreenRect clip, GpuUiClipMask clipMask) {
        if (state == null || !(width > 0) || !(height > 0)) return;
        if (!Float.isFinite(opacity) || opacity <= 0f) return;
        if (!usable()) return;
        RenderSystem.assertOnRenderThread();
        releaseRequested = false;
        synchronized (LOCK) {
            try {
                ScreenRect bounds = rectangle(x, y, width, height).transform(pose);
                ScreenRect scissor = clip == null ? null : clip.intersection(bounds);
                if (clip != null && scissor == null) return;

                long now = System.nanoTime();
                if (lastRequestNanos == 0L || now - lastRequestNanos > SESSION_GAP_NANOS) {
                    newSession = true;
                    qualityDrop = 0;
                    slowFrames = 0;
                }
                lastRequestNanos = now;

                // Panel size in framebuffer pixels caps the dye resolution.
                MinecraftClient client = MinecraftClient.getInstance();
                float pxPerUnit = 1f;
                if (client != null && client.getWindow() != null && client.getWindow().getScaledWidth() > 0) {
                    pxPerUnit = client.getWindow().getFramebufferWidth() / (float) client.getWindow().getScaledWidth();
                }
                float panelW = Math.max(16f, bounds.width() * pxPerUnit);
                float panelH = Math.max(16f, bounds.height() * pxPerUnit);
                computeDimensions(panelW, panelH);
                if (!ensureOutput(dyeW, dyeH)) return;

                // runPending() is driven by the GuiRenderer HEAD hook; if that never consumes a request the
                // output would stay empty, so give up on the sim and let the static water shader draw.
                unconsumedFrames = pending ? unconsumedFrames + 1 : 0;
                if (unconsumedFrames > 60) {
                    fail("Water fluid simulation never ran (GUI render hook unavailable)", null);
                    return;
                }
                reqCursorU = cursorU;
                reqCursorV = cursorV;
                reqInject = inject;
                reqFlow = clamp(flow, 0.05f, 4f);
                reqDye0 = dye0;
                reqDye1 = dye1;
                reqDye2 = dye2;
                reqColorCount = colorCount;
                pending = true;

                int alpha = Math.round(clamp(opacity, 0f, 1f) * 255f);
                int argb = (alpha << 24) | 0x00FFFFFF;
                float radius = clamp(cornerRadius, 0f, Math.min(width, height) * 0.5f);
                TextureSetup setup = (clipMask == null ? GpuUiClipMask.none() : clipMask)
                        .withMask(GpuUiEffectPasses.texture(output));
                state.addSimpleElement(new FluidState(DISPLAY_PIPELINE, setup, pose,
                        x, y, width, height, radius, argb, bounds, scissor));
            } catch (RuntimeException | LinkageError failure) {
                fail("Water fluid simulation could not prepare its output", failure);
            }
        }
    }

    /** GuiRenderer HEAD: steps the simulation and renders the display into the output texture. */
    public static void runPending() {
        if (!pending) return;
        pending = false;
        if (failed || output == null || output.closed()) return;
        if (!RenderSystem.isOnRenderThread()) return;
        synchronized (LOCK) {
            SavedState saved = SavedState.INSTANCE;
            boolean captured = false;
            try {
                saved.capture();
                captured = true;
                if (!ensureGl()) return;
                prepareCommonState();
                ensureFields();
                ensureOutputFbo();

                long now = System.nanoTime();
                float realDt = lastStepNanos == 0L ? MAX_DT : (now - lastStepNanos) / 1.0e9f;
                lastStepNanos = now;
                adaptQuality(realDt);
                float dt = Math.min(Math.max(realDt, 0.0005f), MAX_DT);

                prepareCommonState();
                if (newSession) {
                    newSession = false;
                    clearAllFields();
                    prevU = reqCursorU;
                    prevV = reqCursorV;
                    colorTimer = 0f;
                    nextPointerColor();
                    if (cfgRandomSplats) multipleSplats(5 + ThreadLocalRandom.current().nextInt(20));
                }
                if (!cfgPaused) {
                    updateColors(dt);
                    applyPointer();
                    step(Math.min(dt * reqFlow, 0.05f));
                }
                renderDisplay();
            } catch (Throwable failure) {
                fail("Water fluid simulation failed", failure);
            } finally {
                if (captured) saved.restore(samplerObjects);
            }
        }
    }

    /**
     * Frees the simulation's GPU memory once the current GUI flush is done (menu closed), so an element
     * already recorded this frame never samples a closed texture.
     */
    public static void releaseSoon() {
        releaseRequested = true;
    }

    /** GuiRenderer RETURN: performs a release requested by {@link #releaseSoon()}. */
    public static void finishFrame() {
        if (!releaseRequested) return;
        releaseRequested = false;
        if (RenderSystem.isOnRenderThread()) {
            synchronized (LOCK) { releaseAll(); }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------------------------------------

    private static void step(float dt) {
        glDisable(GL_BLEND);
        float vtx = 1f / velocity.width, vty = 1f / velocity.height;

        use(curlProgram, vtx, vty);
        bind(0, velocity.read.texture);
        blit(curl);

        use(vorticityProgram, vtx, vty);
        glUniform1f(vorticityProgram.curl, cfgCurl);
        glUniform1f(vorticityProgram.dt, dt);
        bind(0, velocity.read.texture);
        bind(1, curl.texture);
        blit(velocity.write);
        velocity.swap();

        use(divergenceProgram, vtx, vty);
        bind(0, velocity.read.texture);
        blit(divergence);

        use(clearProgram, vtx, vty);
        glUniform1f(clearProgram.value, PRESSURE_DECAY);
        bind(0, pressure.read.texture);
        blit(pressure.write);
        pressure.swap();

        use(pressureProgram, vtx, vty);
        bind(1, divergence.texture);
        for (int i = 0; i < cfgPressureIterations; i++) {
            bind(0, pressure.read.texture);
            blit(pressure.write);
            pressure.swap();
        }

        use(gradientProgram, vtx, vty);
        bind(0, pressure.read.texture);
        bind(1, velocity.read.texture);
        blit(velocity.write);
        velocity.swap();

        use(advectionProgram, vtx, vty);
        glUniform1f(advectionProgram.dt, dt);
        glUniform1f(advectionProgram.dissipation, cfgVelocityDissipation);
        bind(0, velocity.read.texture);
        bind(1, velocity.read.texture);
        blit(velocity.write);
        velocity.swap();

        glUniform1f(advectionProgram.dissipation, cfgDyeDissipation);
        bind(0, velocity.read.texture);
        bind(1, dye.read.texture);
        blit(dye.write);
        dye.swap();
    }

    private static void applyPointer() {
        float u = reqCursorU, v = reqCursorV;
        boolean inside = Float.isFinite(u) && Float.isFinite(v) && u >= 0f && u <= 1f && v >= 0f && v <= 1f;
        if (!inside) {
            prevU = Float.NaN;
            prevV = Float.NaN;
            return;
        }
        if (!reqInject || !Float.isFinite(prevU) || !Float.isFinite(prevV)) {
            prevU = u;
            prevV = v;
            return;
        }
        // Texture space is bottom-up, the cursor top-down.
        float dx = u - prevU;
        float dy = -(v - prevV);
        prevU = u;
        prevV = v;
        if (dx == 0f && dy == 0f) return;
        if (aspect < 1f) dx *= aspect;
        if (aspect > 1f) dy /= aspect;
        splat(u, 1f - v, dx * cfgSplatForce, dy * cfgSplatForce,
                pointerColor[0], pointerColor[1], pointerColor[2]);
    }

    private static void multipleSplats(int amount) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < amount; i++) {
            float[] c = generateColor(scratchColor);
            float r = c[0] * 10f, g = c[1] * 10f, b = c[2] * 10f;
            float x = rng.nextFloat(), y = rng.nextFloat();
            float dx = 1000f * (rng.nextFloat() - 0.5f);
            float dy = 1000f * (rng.nextFloat() - 0.5f);
            splat(x, y, dx, dy, r, g, b);
        }
    }

    private static void splat(float x, float y, float dx, float dy, float r, float g, float b) {
        float radius = cfgSplatRadius / 100f;
        if (aspect > 1f) radius *= aspect;
        use(splatProgram, 1f / velocity.width, 1f / velocity.height);
        glUniform1f(splatProgram.aspectRatio, aspect);
        glUniform2f(splatProgram.point, x, y);
        glUniform1f(splatProgram.radius, radius);
        glUniform3f(splatProgram.color, dx, dy, 0f);
        bind(0, velocity.read.texture);
        blit(velocity.write);
        velocity.swap();

        glUniform3f(splatProgram.color, r, g, b);
        bind(0, dye.read.texture);
        blit(dye.write);
        dye.swap();
    }

    private static void updateColors(float dt) {
        colorTimer += dt * COLOR_UPDATE_SPEED;
        if (colorTimer >= 1f) {
            colorTimer = colorTimer % 1f;
            nextPointerColor();
        }
    }

    private static void nextPointerColor() {
        float[] c = generateColor(scratchColor);
        pointerColor[0] = c[0];
        pointerColor[1] = c[1];
        pointerColor[2] = c[2];
    }

    /** A splat colour: a random saturated hue (Rainbow) or the next of the user's colours, at 0.15 like the demo. */
    private static float[] generateColor(float[] out) {
        if (cfgRainbow) {
            hsvToRgb(ThreadLocalRandom.current().nextFloat(), 1f, 1f, out);
        } else {
            int count = Math.max(1, Math.min(3, reqColorCount));
            int i = Math.floorMod(paletteIndex++, count);
            int argb = i == 0 ? reqDye0 : i == 1 ? reqDye1 : reqDye2;
            out[0] = ((argb >> 16) & 0xFF) / 255f;
            out[1] = ((argb >> 8) & 0xFF) / 255f;
            out[2] = (argb & 0xFF) / 255f;
        }
        out[0] *= 0.15f;
        out[1] *= 0.15f;
        out[2] *= 0.15f;
        return out;
    }

    private static void hsvToRgb(float h, float s, float v, float[] out) {
        int i = (int) Math.floor(h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s), q = v * (1f - f * s), t = v * (1f - (1f - f) * s);
        switch (Math.floorMod(i, 6)) {
            case 0 -> { out[0] = v; out[1] = t; out[2] = p; }
            case 1 -> { out[0] = q; out[1] = v; out[2] = p; }
            case 2 -> { out[0] = p; out[1] = v; out[2] = t; }
            case 3 -> { out[0] = p; out[1] = q; out[2] = v; }
            case 4 -> { out[0] = t; out[1] = p; out[2] = v; }
            default -> { out[0] = v; out[1] = p; out[2] = q; }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------------------------------------

    private static void renderDisplay() {
        boolean bloomOn = cfgBloom && bloom != null && bloomChainLength >= 2;
        if (bloomOn) applyBloom();
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo);
        glViewport(0, 0, output.width(), output.height());
        glUseProgram(displayProgram.id);
        glUniform2f(displayProgram.texelSize, 1f / dye.width, 1f / dye.height);
        glUniform1i(displayProgram.shading, cfgShading ? 1 : 0);
        glUniform1i(displayProgram.bloomOn, bloomOn ? 1 : 0);
        bind(0, dye.read.texture);
        bind(1, bloomOn ? bloom.texture : dye.read.texture);
        draw();
    }

    private static void applyBloom() {
        float knee = BLOOM_THRESHOLD * BLOOM_SOFT_KNEE + 0.0001f;
        glDisable(GL_BLEND);
        use(bloomPrefilterProgram, 0f, 0f);
        glUniform3f(bloomPrefilterProgram.curve, BLOOM_THRESHOLD - knee, knee * 2f, 0.25f / knee);
        glUniform1f(bloomPrefilterProgram.threshold, BLOOM_THRESHOLD);
        bind(0, dye.read.texture);
        blit(bloom);

        Fbo last = bloom;
        glUseProgram(bloomBlurProgram.id);
        for (int i = 0; i < bloomChainLength; i++) {
            Fbo dest = bloomChain[i];
            glUniform2f(bloomBlurProgram.texelSize, 1f / last.width, 1f / last.height);
            bind(0, last.texture);
            blit(dest);
            last = dest;
        }
        glBlendFunc(GL_ONE, GL_ONE);
        glBlendEquation(GL_FUNC_ADD);
        glEnable(GL_BLEND);
        for (int i = bloomChainLength - 2; i >= 0; i--) {
            Fbo base = bloomChain[i];
            glUniform2f(bloomBlurProgram.texelSize, 1f / last.width, 1f / last.height);
            bind(0, last.texture);
            blit(base);
            last = base;
        }
        glDisable(GL_BLEND);
        use(bloomFinalProgram, 1f / last.width, 1f / last.height);
        glUniform1f(bloomFinalProgram.intensity, BLOOM_INTENSITY);
        bind(0, last.texture);
        blit(bloom);
    }

    // ------------------------------------------------------------------------------------------------
    // Dimensions / adaptive quality
    // ------------------------------------------------------------------------------------------------

    private static int effectiveQuality() {
        return Math.max(0, cfgQuality - qualityDrop);
    }

    private static void computeDimensions(float panelW, float panelH) {
        // Bucketed so an animated panel size does not reallocate every field each frame.
        panelW = Math.max(128f, (float) Math.ceil(panelW / 128f) * 128f);
        panelH = Math.max(128f, (float) Math.ceil(panelH / 128f) * 128f);
        aspect = panelW / panelH;
        int[] tier = TIERS[effectiveQuality()];
        int panelShort = (int) Math.min(panelW, panelH);
        int maxTex = 4096;
        try { maxTex = Math.max(256, RenderSystem.getDevice().getMaxTextureSize()); } catch (Throwable ignored) { }

        long sim = resolution(tier[0], aspect);
        simW = quantize((int) (sim >> 32), 8, maxTex);
        simH = quantize((int) sim, 8, maxTex);

        int dyeShort = Math.min(tier[1], Math.max(64, panelShort));
        long d = resolution(dyeShort, aspect);
        dyeW = quantize((int) (d >> 32), 16, maxTex);
        dyeH = quantize((int) d, 16, maxTex);

        int bloomShort = Math.min(tier[2], dyeShort);
        long bl = resolution(bloomShort, aspect);
        bloomW = quantize((int) (bl >> 32), 8, maxTex);
        bloomH = quantize((int) bl, 8, maxTex);
    }

    /** Aspect-correct size whose short side is {@code res}, packed as (width << 32) | height. */
    private static long resolution(int res, float aspectRatio) {
        float a = aspectRatio < 1f ? 1f / aspectRatio : aspectRatio;
        int min = res;
        int max = Math.round(res * a);
        int w = aspectRatio >= 1f ? max : min;
        int h = aspectRatio >= 1f ? min : max;
        return ((long) w << 32) | (h & 0xFFFFFFFFL);
    }

    private static int quantize(int v, int step, int maxTex) {
        int q = Math.max(step, Math.round(v / (float) step) * step);
        return Math.min(q, maxTex);
    }

    private static void adaptQuality(float realDt) {
        if (!(realDt > 0f) || realDt > 0.5f) return;
        avgFrameSeconds = avgFrameSeconds * 0.95f + realDt * 0.05f;
        if (avgFrameSeconds > 1f / 30f) slowFrames++;
        else if (slowFrames > 0) slowFrames--;
        if (slowFrames > 120 && effectiveQuality() > 0) {
            qualityDrop++;
            slowFrames = 0;
            avgFrameSeconds = 1f / 60f;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // GL resources
    // ------------------------------------------------------------------------------------------------

    private static boolean ensureOutput(int width, int height) {
        if (output != null && !output.closed() && output.width() == width && output.height() == height) return true;
        releaseOutput();
        output = GpuUiEffectPasses.allocatePersistentTarget(width, height);
        if (output == null || output.closed()) {
            output = null;
            return false;
        }
        if (!(output.texture() instanceof GlTexture)) {
            releaseOutput();
            fail("Water fluid simulation needs the OpenGL backend", null);
            return false;
        }
        GpuUiEffectPasses.clearTarget(output, 0x00000000);
        return true;
    }

    private static void releaseOutput() {
        if (outputFbo != 0) {
            try { glDeleteFramebuffers(outputFbo); } catch (Throwable ignored) { }
        }
        outputFbo = 0;
        outputFboTexture = 0;
        GpuUiEffectPasses.releasePersistentTarget(output);
        output = null;
    }

    private static void ensureOutputFbo() {
        int glId = ((GlTexture) output.texture()).getGlId();
        if (outputFbo != 0 && outputFboTexture == glId) return;
        if (outputFbo != 0) glDeleteFramebuffers(outputFbo);
        outputFbo = glGenFramebuffers();
        outputFboTexture = glId;
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, glId, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("output framebuffer incomplete (0x" + Integer.toHexString(status) + ")");
        }
    }

    private static boolean ensureGl() {
        if (glReady) return true;
        GLCapabilities caps = GL.getCapabilities();
        if (caps == null || !caps.OpenGL30) {
            fail("Water fluid simulation needs OpenGL 3.0 framebuffers", null);
            return false;
        }
        samplerObjects = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

        fmtRgba = supportedFormat(GL_RGBA16F, GL_RGBA, null);
        if (fmtRgba == null) {
            fail("Water fluid simulation: half-float render targets are not supported on this GPU", null);
            return false;
        }
        fmtRg = supportedFormat(GL_RG16F, GL_RG, fmtRgba);
        fmtR = supportedFormat(GL_R16F, GL_RED, fmtRg);

        copyProgram = Program.create("copy", Shaders.COPY);
        clearProgram = Program.create("clear", Shaders.CLEAR);
        splatProgram = Program.create("splat", Shaders.SPLAT);
        advectionProgram = Program.create("advection", Shaders.ADVECTION);
        divergenceProgram = Program.create("divergence", Shaders.DIVERGENCE);
        curlProgram = Program.create("curl", Shaders.CURL);
        vorticityProgram = Program.create("vorticity", Shaders.VORTICITY);
        pressureProgram = Program.create("pressure", Shaders.PRESSURE);
        gradientProgram = Program.create("gradient", Shaders.GRADIENT);
        bloomPrefilterProgram = Program.create("bloomPrefilter", Shaders.BLOOM_PREFILTER);
        bloomBlurProgram = Program.create("bloomBlur", Shaders.BLOOM_BLUR);
        bloomFinalProgram = Program.create("bloomFinal", Shaders.BLOOM_FINAL);
        displayProgram = Program.create("display", Shaders.DISPLAY);

        // Sampler units are fixed per program.
        bindSamplers(advectionProgram, "uVelocity", 0, "uSource", 1);
        bindSamplers(vorticityProgram, "uVelocity", 0, "uCurl", 1);
        bindSamplers(pressureProgram, "uPressure", 0, "uDivergence", 1);
        bindSamplers(gradientProgram, "uPressure", 0, "uVelocity", 1);
        bindSamplers(displayProgram, "uTexture", 0, "uBloom", 1);
        bindSamplers(copyProgram, "uTexture", 0, null, 0);
        bindSamplers(clearProgram, "uTexture", 0, null, 0);
        bindSamplers(splatProgram, "uTarget", 0, null, 0);
        bindSamplers(divergenceProgram, "uVelocity", 0, null, 0);
        bindSamplers(curlProgram, "uVelocity", 0, null, 0);
        bindSamplers(bloomPrefilterProgram, "uTexture", 0, null, 0);
        bindSamplers(bloomBlurProgram, "uTexture", 0, null, 0);
        bindSamplers(bloomFinalProgram, "uTexture", 0, null, 0);

        vao = glGenVertexArrays();
        glReady = true;
        return true;
    }

    private static void bindSamplers(Program program, String a, int unitA, String b, int unitB) {
        glUseProgram(program.id);
        int la = glGetUniformLocation(program.id, a);
        if (la >= 0) glUniform1i(la, unitA);
        if (b != null) {
            int lb = glGetUniformLocation(program.id, b);
            if (lb >= 0) glUniform1i(lb, unitB);
        }
    }

    /** Probes whether a half-float format is colour-renderable; falls back to {@code fallback} when not. */
    private static int[] supportedFormat(int internal, int format, int[] fallback) {
        int texture = glGenTextures();
        int fbo = glGenFramebuffers();
        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, internal, 4, 4, 0, format, GL_HALF_FLOAT, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            boolean ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            return ok ? new int[] { internal, format } : fallback;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
        }
    }

    /** (Re)allocates the fields for the current dimensions, carrying velocity and dye across a resize. */
    private static void ensureFields() {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        if (velocity == null || velocity.width != simW || velocity.height != simH) {
            velocity = resizeDouble(velocity, simW, simH, fmtRg);
            deleteDouble(pressure);
            pressure = DoubleFbo.create(simW, simH, fmtR);
            deleteFbo(divergence);
            divergence = Fbo.create(simW, simH, fmtR);
            deleteFbo(curl);
            curl = Fbo.create(simW, simH, fmtR);
        }
        if (dye == null || dye.width != dyeW || dye.height != dyeH) {
            dye = resizeDouble(dye, dyeW, dyeH, fmtRgba);
        }
        if (bloom == null || bloom.width != bloomW || bloom.height != bloomH) {
            deleteFbo(bloom);
            for (int i = 0; i < bloomChainLength; i++) {
                deleteFbo(bloomChain[i]);
                bloomChain[i] = null;
            }
            bloomChainLength = 0;
            bloom = Fbo.create(bloomW, bloomH, fmtRgba);
            for (int i = 0; i < BLOOM_ITERATIONS; i++) {
                int w = bloomW >> (i + 1), h = bloomH >> (i + 1);
                if (w < 2 || h < 2) break;
                bloomChain[i] = Fbo.create(w, h, fmtRgba);
                bloomChainLength = i + 1;
            }
        }
    }

    private static DoubleFbo resizeDouble(DoubleFbo old, int w, int h, int[] fmt) {
        DoubleFbo next = DoubleFbo.create(w, h, fmt);
        if (old != null) {
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(copyProgram.id);
            bind(0, old.read.texture);
            blit(next.read);
            deleteDouble(old);
        }
        return next;
    }

    private static void clearAllFields() {
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glClearColor(0f, 0f, 0f, 0f);
        clearFbo(velocity.read); clearFbo(velocity.write);
        clearFbo(dye.read); clearFbo(dye.write);
        clearFbo(pressure.read); clearFbo(pressure.write);
        clearFbo(divergence); clearFbo(curl);
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo);
        glViewport(0, 0, output.width(), output.height());
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void clearFbo(Fbo fbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo.fbo);
        glViewport(0, 0, fbo.width, fbo.height);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    /** The state every pass assumes; the caller's state is restored afterwards. */
    private static void prepareCommonState() {
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_COLOR_LOGIC_OP);
        glColorMask(true, true, true, true);
        glBindVertexArray(vao);
        if (samplerObjects) {
            glBindSampler(0, 0);
            glBindSampler(1, 0);
        }
    }

    private static void use(Program program, float texelX, float texelY) {
        glUseProgram(program.id);
        if (program.texelSize >= 0) glUniform2f(program.texelSize, texelX, texelY);
    }

    private static void bind(int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    private static void blit(Fbo target) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.fbo);
        glViewport(0, 0, target.width, target.height);
        draw();
    }

    private static void draw() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    // ------------------------------------------------------------------------------------------------
    // Failure / cleanup
    // ------------------------------------------------------------------------------------------------

    private static void fail(String message, Throwable cause) {
        if (!failed) {
            failed = true;
            String detail = (cause == null ? message
                    : message + " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")")
                    + "; using the static water shader";
            try { ClientInternalLog.warn("GPU UI", detail); } catch (Throwable ignored) { }
        }
        pending = false;
        try { disposeGl(); } catch (Throwable ignored) { }
        try { releaseOutput(); } catch (Throwable ignored) { }
    }

    private static void ensureCleanupRegistered() {
        if (cleanupRegistered) return;
        cleanupRegistered = true;
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSafely());
        ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(
                Identifier.of("dynamic_fps", "gpuui_fluid_simulation"),
                (SynchronousResourceReloader) manager -> releaseSafely());
    }

    private static void releaseSafely() {
        try {
            if (RenderSystem.tryGetDevice() == null) return;
            if (RenderSystem.isOnRenderThread()) {
                synchronized (LOCK) { releaseAll(); }
            } else {
                RenderSystem.queueFencedTask(() -> { synchronized (LOCK) { releaseAll(); } });
            }
        } catch (Throwable ignored) {
            // cleanup is best effort
        }
    }

    private static void releaseAll() {
        pending = false;
        newSession = true;
        lastStepNanos = 0L;
        try { disposeGl(); } catch (Throwable ignored) { }
        try { releaseOutput(); } catch (Throwable ignored) { }
    }

    private static void disposeGl() {
        deleteDouble(velocity);
        deleteDouble(dye);
        deleteDouble(pressure);
        velocity = dye = pressure = null;
        deleteFbo(divergence);
        deleteFbo(curl);
        deleteFbo(bloom);
        divergence = curl = bloom = null;
        for (int i = 0; i < BLOOM_ITERATIONS; i++) {
            deleteFbo(bloomChain[i]);
            bloomChain[i] = null;
        }
        bloomChainLength = 0;
        Program[] all = { copyProgram, clearProgram, splatProgram, advectionProgram, divergenceProgram,
                curlProgram, vorticityProgram, pressureProgram, gradientProgram,
                bloomPrefilterProgram, bloomBlurProgram, bloomFinalProgram, displayProgram };
        for (Program p : all) {
            if (p != null && p.id != 0) glDeleteProgram(p.id);
        }
        copyProgram = clearProgram = splatProgram = advectionProgram = divergenceProgram = null;
        curlProgram = vorticityProgram = pressureProgram = gradientProgram = null;
        bloomPrefilterProgram = bloomBlurProgram = bloomFinalProgram = displayProgram = null;
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
        if (outputFbo != 0) glDeleteFramebuffers(outputFbo);
        outputFbo = 0;
        outputFboTexture = 0;
        glReady = false;
    }

    private static void deleteDouble(DoubleFbo d) {
        if (d == null) return;
        deleteFbo(d.read);
        deleteFbo(d.write);
    }

    private static void deleteFbo(Fbo f) {
        if (f == null) return;
        if (f.fbo != 0) glDeleteFramebuffers(f.fbo);
        if (f.texture != 0) glDeleteTextures(f.texture);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private static ScreenRect rectangle(float x, float y, float width, float height) {
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        int right = (int) Math.ceil(x + width);
        int bottom = (int) Math.ceil(y + height);
        return new ScreenRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    // ------------------------------------------------------------------------------------------------
    // GL helper types
    // ------------------------------------------------------------------------------------------------

    private static final class Fbo {
        final int texture, fbo, width, height;

        private Fbo(int texture, int fbo, int width, int height) {
            this.texture = texture;
            this.fbo = fbo;
            this.width = width;
            this.height = height;
        }

        static Fbo create(int w, int h, int[] fmt) {
            int texture = glGenTextures();
            int fbo = 0;
            try {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
                glTexImage2D(GL_TEXTURE_2D, 0, fmt[0], w, h, 0, fmt[1], GL_HALF_FLOAT, (ByteBuffer) null);
                fbo = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("fluid framebuffer " + w + "x" + h + " incomplete (0x"
                            + Integer.toHexString(status) + ")");
                }
                glViewport(0, 0, w, h);
                glDisable(GL_SCISSOR_TEST);
                glColorMask(true, true, true, true);
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
                return new Fbo(texture, fbo, w, h);
            } catch (RuntimeException failure) {
                if (fbo != 0) glDeleteFramebuffers(fbo);
                glDeleteTextures(texture);
                throw failure;
            }
        }
    }

    private static final class DoubleFbo {
        Fbo read, write;
        final int width, height;

        private DoubleFbo(Fbo read, Fbo write) {
            this.read = read;
            this.write = write;
            this.width = read.width;
            this.height = read.height;
        }

        static DoubleFbo create(int w, int h, int[] fmt) {
            Fbo a = Fbo.create(w, h, fmt);
            try {
                return new DoubleFbo(a, Fbo.create(w, h, fmt));
            } catch (RuntimeException failure) {
                deleteFbo(a);
                throw failure;
            }
        }

        void swap() {
            Fbo t = read;
            read = write;
            write = t;
        }
    }

    private static final class Program {
        final int id;
        final int texelSize, dt, dissipation, curl, value, aspectRatio, color, point, radius,
                curve, threshold, intensity, shading, bloomOn;

        private Program(int id) {
            this.id = id;
            texelSize = glGetUniformLocation(id, "texelSize");
            dt = glGetUniformLocation(id, "dt");
            dissipation = glGetUniformLocation(id, "dissipation");
            curl = glGetUniformLocation(id, "curl");
            value = glGetUniformLocation(id, "value");
            aspectRatio = glGetUniformLocation(id, "aspectRatio");
            color = glGetUniformLocation(id, "color");
            point = glGetUniformLocation(id, "point");
            radius = glGetUniformLocation(id, "radius");
            curve = glGetUniformLocation(id, "curve");
            threshold = glGetUniformLocation(id, "threshold");
            intensity = glGetUniformLocation(id, "intensity");
            shading = glGetUniformLocation(id, "shading");
            bloomOn = glGetUniformLocation(id, "bloomOn");
        }

        static Program create(String name, String fragmentSource) {
            int vertex = compile(GL_VERTEX_SHADER, Shaders.BASE_VERTEX, name);
            int fragment;
            try {
                fragment = compile(GL_FRAGMENT_SHADER, fragmentSource, name);
            } catch (RuntimeException failure) {
                glDeleteShader(vertex);
                throw failure;
            }
            int program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            glDetachShader(program, vertex);
            glDetachShader(program, fragment);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new IllegalStateException("fluid program '" + name + "' failed to link: " + log);
            }
            return new Program(program);
        }

        private static int compile(int type, String source, String name) {
            int shader = glCreateShader(type);
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new IllegalStateException("fluid shader '" + name + "' failed to compile: " + log);
            }
            return shader;
        }
    }

    /** Snapshot of every piece of GL state the passes touch. One instance, reused every frame. */
    private static final class SavedState {
        static final SavedState INSTANCE = new SavedState();

        int drawFbo, readFbo, program, vertexArray, activeTexture, unpackBuffer;
        int texture0, texture1, sampler0, sampler1;
        int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEqRgb, blendEqAlpha;
        boolean blend, depth, cull, scissor, stencil, polygonOffset, logicOp;
        final int[] viewport = new int[4];
        final boolean[] colorMask = new boolean[4];
        final float[] clearColor = new float[4];

        void capture() {
            drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            program = glGetInteger(GL_CURRENT_PROGRAM);
            vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            boolean samplers = GL.getCapabilities() != null
                    && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects);
            glActiveTexture(GL_TEXTURE0);
            texture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
            sampler0 = samplers ? glGetInteger(GL_SAMPLER_BINDING) : 0;
            glActiveTexture(GL_TEXTURE1);
            texture1 = glGetInteger(GL_TEXTURE_BINDING_2D);
            sampler1 = samplers ? glGetInteger(GL_SAMPLER_BINDING) : 0;
            glActiveTexture(activeTexture);
            blend = glIsEnabled(GL_BLEND);
            depth = glIsEnabled(GL_DEPTH_TEST);
            cull = glIsEnabled(GL_CULL_FACE);
            scissor = glIsEnabled(GL_SCISSOR_TEST);
            stencil = glIsEnabled(GL_STENCIL_TEST);
            polygonOffset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
            logicOp = glIsEnabled(GL_COLOR_LOGIC_OP);
            blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
            blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
            blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
            blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
            blendEqRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
            blendEqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
            VIEWPORT.clear();
            glGetIntegerv(GL_VIEWPORT, VIEWPORT);
            for (int i = 0; i < 4; i++) viewport[i] = VIEWPORT.get(i);
            COLOR_MASK.clear();
            glGetBooleanv(GL_COLOR_WRITEMASK, COLOR_MASK);
            for (int i = 0; i < 4; i++) colorMask[i] = COLOR_MASK.get(i) != 0;
            CLEAR_COLOR.clear();
            glGetFloatv(GL_COLOR_CLEAR_VALUE, CLEAR_COLOR);
            for (int i = 0; i < 4; i++) clearColor[i] = CLEAR_COLOR.get(i);
        }

        void restore(boolean samplers) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glUseProgram(program);
            glBindVertexArray(vertexArray);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture0);
            if (samplers) glBindSampler(0, sampler0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, texture1);
            if (samplers) glBindSampler(1, sampler1);
            glActiveTexture(activeTexture);
            toggle(GL_BLEND, blend);
            toggle(GL_DEPTH_TEST, depth);
            toggle(GL_CULL_FACE, cull);
            toggle(GL_SCISSOR_TEST, scissor);
            toggle(GL_STENCIL_TEST, stencil);
            toggle(GL_POLYGON_OFFSET_FILL, polygonOffset);
            toggle(GL_COLOR_LOGIC_OP, logicOp);
            glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            glBlendEquationSeparate(blendEqRgb, blendEqAlpha);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        }

        private static void toggle(int capability, boolean enabled) {
            if (enabled) glEnable(capability);
            else glDisable(capability);
        }
    }

    /** GLSL 330 core ports of the WebGL demo's shaders. */
    private static final class Shaders {
        static final String BASE_VERTEX = """
                #version 330 core
                uniform vec2 texelSize;
                out vec2 vUv;
                out vec2 vL;
                out vec2 vR;
                out vec2 vT;
                out vec2 vB;
                void main() {
                    vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    vUv = pos;
                    vL = vUv - vec2(texelSize.x, 0.0);
                    vR = vUv + vec2(texelSize.x, 0.0);
                    vT = vUv + vec2(0.0, texelSize.y);
                    vB = vUv - vec2(0.0, texelSize.y);
                    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
                }
                """;

        private static final String HEAD = """
                #version 330 core
                in vec2 vUv;
                in vec2 vL;
                in vec2 vR;
                in vec2 vT;
                in vec2 vB;
                out vec4 fragColor;
                """;

        static final String COPY = HEAD + """
                uniform sampler2D uTexture;
                void main() { fragColor = texture(uTexture, vUv); }
                """;

        static final String CLEAR = HEAD + """
                uniform sampler2D uTexture;
                uniform float value;
                void main() { fragColor = value * texture(uTexture, vUv); }
                """;

        static final String SPLAT = HEAD + """
                uniform sampler2D uTarget;
                uniform float aspectRatio;
                uniform vec3 color;
                uniform vec2 point;
                uniform float radius;
                void main() {
                    vec2 p = vUv - point.xy;
                    p.x *= aspectRatio;
                    vec3 splat = exp(-dot(p, p) / radius) * color;
                    vec3 base = texture(uTarget, vUv).xyz;
                    fragColor = vec4(base + splat, 1.0);
                }
                """;

        static final String ADVECTION = HEAD + """
                uniform sampler2D uVelocity;
                uniform sampler2D uSource;
                uniform vec2 texelSize;
                uniform float dt;
                uniform float dissipation;
                void main() {
                    vec2 coord = vUv - dt * texture(uVelocity, vUv).xy * texelSize;
                    vec4 result = texture(uSource, coord);
                    float decay = 1.0 + dissipation * dt;
                    fragColor = result / decay;
                }
                """;

        static final String DIVERGENCE = HEAD + """
                uniform sampler2D uVelocity;
                void main() {
                    float L = texture(uVelocity, vL).x;
                    float R = texture(uVelocity, vR).x;
                    float T = texture(uVelocity, vT).y;
                    float B = texture(uVelocity, vB).y;
                    vec2 C = texture(uVelocity, vUv).xy;
                    if (vL.x < 0.0) { L = -C.x; }
                    if (vR.x > 1.0) { R = -C.x; }
                    if (vT.y > 1.0) { T = -C.y; }
                    if (vB.y < 0.0) { B = -C.y; }
                    float div = 0.5 * (R - L + T - B);
                    fragColor = vec4(div, 0.0, 0.0, 1.0);
                }
                """;

        static final String CURL = HEAD + """
                uniform sampler2D uVelocity;
                void main() {
                    float L = texture(uVelocity, vL).y;
                    float R = texture(uVelocity, vR).y;
                    float T = texture(uVelocity, vT).x;
                    float B = texture(uVelocity, vB).x;
                    float vorticity = R - L - T + B;
                    fragColor = vec4(0.5 * vorticity, 0.0, 0.0, 1.0);
                }
                """;

        static final String VORTICITY = HEAD + """
                uniform sampler2D uVelocity;
                uniform sampler2D uCurl;
                uniform float curl;
                uniform float dt;
                void main() {
                    float L = texture(uCurl, vL).x;
                    float R = texture(uCurl, vR).x;
                    float T = texture(uCurl, vT).x;
                    float B = texture(uCurl, vB).x;
                    float C = texture(uCurl, vUv).x;
                    vec2 force = 0.5 * vec2(abs(T) - abs(B), abs(R) - abs(L));
                    force /= length(force) + 0.0001;
                    force *= curl * C;
                    force.y *= -1.0;
                    vec2 velocity = texture(uVelocity, vUv).xy;
                    velocity += force * dt;
                    velocity = min(max(velocity, -1000.0), 1000.0);
                    fragColor = vec4(velocity, 0.0, 1.0);
                }
                """;

        static final String PRESSURE = HEAD + """
                uniform sampler2D uPressure;
                uniform sampler2D uDivergence;
                void main() {
                    float L = texture(uPressure, vL).x;
                    float R = texture(uPressure, vR).x;
                    float T = texture(uPressure, vT).x;
                    float B = texture(uPressure, vB).x;
                    float divergence = texture(uDivergence, vUv).x;
                    float pressure = (L + R + B + T - divergence) * 0.25;
                    fragColor = vec4(pressure, 0.0, 0.0, 1.0);
                }
                """;

        static final String GRADIENT = HEAD + """
                uniform sampler2D uPressure;
                uniform sampler2D uVelocity;
                void main() {
                    float L = texture(uPressure, vL).x;
                    float R = texture(uPressure, vR).x;
                    float T = texture(uPressure, vT).x;
                    float B = texture(uPressure, vB).x;
                    vec2 velocity = texture(uVelocity, vUv).xy;
                    velocity.xy -= vec2(R - L, T - B);
                    fragColor = vec4(velocity, 0.0, 1.0);
                }
                """;

        static final String BLOOM_PREFILTER = HEAD + """
                uniform sampler2D uTexture;
                uniform vec3 curve;
                uniform float threshold;
                void main() {
                    vec3 c = texture(uTexture, vUv).rgb;
                    float br = max(c.r, max(c.g, c.b));
                    float rq = clamp(br - curve.x, 0.0, curve.y);
                    rq = curve.z * rq * rq;
                    c *= max(rq, br - threshold) / max(br, 0.0001);
                    fragColor = vec4(c, 0.0);
                }
                """;

        static final String BLOOM_BLUR = HEAD + """
                uniform sampler2D uTexture;
                void main() {
                    vec4 sum = vec4(0.0);
                    sum += texture(uTexture, vL);
                    sum += texture(uTexture, vR);
                    sum += texture(uTexture, vT);
                    sum += texture(uTexture, vB);
                    sum *= 0.25;
                    fragColor = sum;
                }
                """;

        static final String BLOOM_FINAL = HEAD + """
                uniform sampler2D uTexture;
                uniform float intensity;
                void main() {
                    vec4 sum = vec4(0.0);
                    sum += texture(uTexture, vL);
                    sum += texture(uTexture, vR);
                    sum += texture(uTexture, vT);
                    sum += texture(uTexture, vB);
                    sum *= 0.25;
                    fragColor = sum * intensity;
                }
                """;

        // Output is straight alpha for the GUI's translucent blend: rgb = c / a, a = max(c), so the composite
        // equals the demo's premultiplied "c + background * (1 - a)". Dithered before the RGBA8 store.
        static final String DISPLAY = HEAD + """
                uniform sampler2D uTexture;
                uniform sampler2D uBloom;
                uniform vec2 texelSize;
                uniform int shading;
                uniform int bloomOn;
                vec3 linearToGamma(vec3 color) {
                    color = max(color, vec3(0.0));
                    return max(1.055 * pow(color, vec3(0.416666667)) - 0.055, vec3(0.0));
                }
                float noise(vec2 p) {
                    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
                }
                void main() {
                    vec3 c = texture(uTexture, vUv).rgb;
                    if (shading == 1) {
                        vec3 lc = texture(uTexture, vL).rgb;
                        vec3 rc = texture(uTexture, vR).rgb;
                        vec3 tc = texture(uTexture, vT).rgb;
                        vec3 bc = texture(uTexture, vB).rgb;
                        float dx = length(rc) - length(lc);
                        float dy = length(tc) - length(bc);
                        vec3 n = normalize(vec3(dx, dy, length(texelSize)));
                        vec3 l = vec3(0.0, 0.0, 1.0);
                        float diffuse = clamp(dot(n, l) + 0.7, 0.7, 1.0);
                        c *= diffuse;
                    }
                    if (bloomOn == 1) {
                        vec3 bloom = texture(uBloom, vUv).rgb;
                        bloom += (noise(gl_FragCoord.xy) * 2.0 - 1.0) / 255.0;
                        bloom = max(bloom, 0.0);
                        bloom = linearToGamma(bloom);
                        c += bloom;
                    }
                    c = max(c, vec3(0.0));
                    float a = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
                    vec3 rgb = a > 0.0001 ? clamp(c / a, 0.0, 1.0) : vec3(0.0);
                    float d = (noise(gl_FragCoord.xy + vec2(17.0, 31.0)) - 0.5) / 255.0;
                    fragColor = vec4(clamp(rgb + d, 0.0, 1.0), clamp(a + d, 0.0, 1.0));
                }
                """;
    }

    private record FluidState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose,
                              float x, float y, float width, float height, float cornerRadius,
                              int argb, ScreenRect bounds, ScreenRect scissorArea)
            implements SimpleGuiElementRenderState {
        @Override public void setupVertices(VertexConsumer vertices) {
            int rw = Math.min(65535, Math.max(1, Math.round(width)));
            int rh = Math.min(65535, Math.max(1, Math.round(height)));
            int packedDimensions = (rh << 16) | rw;
            vertex(vertices, x, y, 0, 0, packedDimensions);
            vertex(vertices, x + width, y, width, 0, packedDimensions);
            vertex(vertices, x + width, y + height, width, height, packedDimensions);
            vertex(vertices, x, y + height, 0, height, packedDimensions);
        }

        private void vertex(VertexConsumer vertices, float px, float py, float localX, float localY,
                            int dimensions) {
            vertices.vertex(pose, px, py).color(argb).texture(localX, localY)
                    .overlay(dimensions).lineWidth(cornerRadius);
        }

        @Override public RenderPipeline pipeline() { return pipeline; }
    }
}
