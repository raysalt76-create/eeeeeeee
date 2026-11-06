package interstellarTikiTiks.apexx.client.gui.gpuui.render;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuUiEffectPasses;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuBackdropBlur;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuFluidSimulation;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.render.SafePipelines;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Submits compact UI geometry to Minecraft's deferred GUI render state. */
public final class GpuUiRenderer {
    private static final Identifier SHADOW_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_shadow");
    private static final Identifier SHADOW_FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_shadow");
    private static final Identifier RECT_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_rect");
    private static final Identifier RECT_FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_rect");
    private static final Identifier TEXT_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_text");
    private static final Identifier TEXT_FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_text");
    private static final Identifier IMAGE_VERTEX = Identifier.of("dynamic_fps", "core/gpuui_image");
    private static final Identifier IMAGE_FRAGMENT = Identifier.of("dynamic_fps", "core/gpuui_image");
    private static boolean warnedRoundedClipFallback;

    /* UV0 carries local or texture pixels, UV1 carries dimensions, and UV2 carries effect data. */
    private static final VertexFormat UI_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .add("UV2", VertexFormatElement.UV2)
            .build();
    private static final VertexFormat SHADOW_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .add("UV2", VertexFormatElement.UV2)
            .build();

    private static final RenderPipeline RECT_PIPELINE = register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_rect"))
            .withVertexShader(RECT_VERTEX)
            .withFragmentShader(RECT_FRAGMENT)
            .withVertexFormat(UI_FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline TEXT_PIPELINE = register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_text"))
            .withVertexShader(TEXT_VERTEX)
            .withFragmentShader(TEXT_FRAGMENT)
            .withVertexFormat(UI_FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline SHADOW_PIPELINE = register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_shadow"))
            .withVertexShader(SHADOW_VERTEX)
            .withFragmentShader(SHADOW_FRAGMENT)
            .withVertexFormat(SHADOW_FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());
    private static final RenderPipeline IMAGE_PIPELINE = register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("dynamic_fps", "pipeline/gpuui_image"))
            .withVertexShader(IMAGE_VERTEX)
            .withFragmentShader(IMAGE_FRAGMENT)
            .withVertexFormat(UI_FORMAT, VertexFormat.DrawMode.QUADS)
            .withSampler("Sampler0")
            .withSampler("Sampler1")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build());

    private GpuUiRenderer() { }

    private static RenderPipeline register(RenderPipeline pipeline) {
        return interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(pipeline);
    }

    /** Registers fixed pipelines before the first shader resource reload. */
    public static void registerPipelines() {
        if (RECT_PIPELINE == null || TEXT_PIPELINE == null || SHADOW_PIPELINE == null || IMAGE_PIPELINE == null) {
            throw new IllegalStateException("GPU UI pipelines were not registered");
        }
        GpuUiClipMask.registerPipelines();
        GpuUiEffectPasses.registerPipelines();
        GpuBackdropBlur.registerPipelines();
        GpuFluidSimulation.registerPipelines();
    }

    /** Drops cached atlas metrics after the UI font resources are reloaded. */
    public static void invalidateFontMetrics() {
        GpuUiFontMetrics.invalidate();
    }

    /**
     * True while a display list authored in framebuffer pixels is being submitted (see
     * {@link #renderPixels}). Only touched on the render thread, inside {@code render}.
     */
    private static boolean pixelSpace;
    /** Framebuffer pixels per Minecraft GUI unit for the current pixel-space submission. */
    private static float pixelsPerGuiUnit = 1f;

    /** Must be called during screen extraction, while DrawContext is recording GUI state. */
    public static void render(DrawContext context, UiDisplayList displayList) {
        render(context, displayList, false, 1f);
    }

    /**
     * Submits a display list whose coordinates are framebuffer pixels rather than GUI units, so a
     * 1-unit border is a true 1 px line and rects, text and icons are snapped to whole device pixels
     * regardless of Minecraft's GUI scale. {@code pixelsPerGuiUnit} is framebuffer width / scaled width.
     */
    public static void renderPixels(DrawContext context, UiDisplayList displayList, float pixelsPerGuiUnit) {
        render(context, displayList, true, Math.max(1f, pixelsPerGuiUnit));
    }

    private static void render(DrawContext context, UiDisplayList displayList, boolean pixels, float perGui) {
        if (context == null || displayList == null || displayList.commands().isEmpty()) return;
        pixelSpace = pixels;
        pixelsPerGuiUnit = perGui;
        try {
            renderCommands(context, displayList);
        } finally {
            pixelSpace = false;
            pixelsPerGuiUnit = 1f;
        }
    }

    private static void renderCommands(DrawContext context, UiDisplayList displayList) {
        GuiRenderState state = context.state;
        Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
        if (pixelSpace && pixelsPerGuiUnit != 1f) pose.scale(1f / pixelsPerGuiUnit);
        ArrayDeque<ClipState> clips = new ArrayDeque<>();
        ClipState activeClip = ClipState.root();
        Map<List<UiDrawCommand.ClipPush>, GpuUiClipMask> clipMasks = new HashMap<>();
        boolean roundedClipMasksAvailable = GpuUiClipMask.available();
        var window = MinecraftClient.getInstance().getWindow();
        int framebufferWidth = window.getFramebufferWidth();
        int framebufferHeight = window.getFramebufferHeight();
        int scaledWidth = window.getScaledWidth();
        int scaledHeight = window.getScaledHeight();

        int commandIndex = 0;
        for (UiDrawCommand command : displayList.commands()) {
            if (command instanceof UiDrawCommand.Rect rect) {
                if (!activeClip.empty) {
                    addQuad(state, rect.x(), rect.y(), rect.width(), rect.height(), rect.radius(), rect.argb(), pose,
                            activeClip, TextureSetup.empty(), 0, 0, 1, 1, false, false, 0);
                }
            } else if (command instanceof UiDrawCommand.GradientRect gradient) {
                if (!activeClip.empty && gradient.width() > 0 && gradient.height() > 0) {
                    ScreenRect bounds = screenBounds(gradient.x(), gradient.y(), gradient.width(), gradient.height(), pose);
                    ScreenRect scissor = intersectionForDraw(activeClip, bounds);
                    if (activeClip.scissor == null || scissor != null) {
                        boolean shaded = SafePipelines.usable(RECT_PIPELINE);
                        state.addSimpleElement(new GradientState(pose, gradient,
                                shaded ? RECT_PIPELINE : RenderPipelines.GUI,
                                shaded ? activeClip.mask.withMask(TextureSetup.empty()) : TextureSetup.empty(),
                                bounds, scissor));
                    }
                }
            } else if (command instanceof UiDrawCommand.Text text) {
                if (!activeClip.empty) drawText(context, state, text, pose, activeClip);
            } else if (command instanceof UiDrawCommand.Image image) {
                if (!activeClip.empty) {
                    TextureSetup texture = imageTexture(image.texture());
                    if (texture != null) {
                        addQuad(state, image.x(), image.y(), image.width(), image.height(), image.radius(),
                                image.argb(), pose, activeClip, texture,
                                image.u0(), image.v0(), image.u1(), image.v1(), false, true, 0);
                    }
                }
            } else if (command instanceof UiDrawCommand.Border border) {
                if (!activeClip.empty && border.thickness() > 0) {
                    addQuad(state, border.x(), border.y(), border.width(), border.height(), border.radius(),
                            border.argb(), pose, activeClip, TextureSetup.empty(), 0, 0, 1, 1,
                            false, false, border.thickness());
                }
            } else if (command instanceof UiDrawCommand.Material material) {
                if (!activeClip.empty && material.material() != null) {
                    int pixelWidth = pixelSpace ? Math.max(1, (int) Math.ceil(material.width()))
                            : physicalSize(material.width(), framebufferWidth, scaledWidth);
                    int pixelHeight = pixelSpace ? Math.max(1, (int) Math.ceil(material.height()))
                            : physicalSize(material.height(), framebufferHeight, scaledHeight);
                    TextureSetup texture = GpuUiMaterialRenderer.prepare(material.material(), pixelWidth, pixelHeight);
                    if (texture != null) {
                        addQuad(state, material.x(), material.y(), material.width(), material.height(),
                                material.radius(), material.material().tint(), pose, activeClip, texture,
                                0, 1, 1, 0, false, true, 0);
                    }
                }
            } else if (command instanceof UiDrawCommand.ClipPush rawClip) {
                UiDrawCommand.ClipPush clip = snapClip(rawClip);
                clips.push(activeClip);
                if (!Float.isFinite(clip.x()) || !Float.isFinite(clip.y()) || !Float.isFinite(clip.width())
                        || !Float.isFinite(clip.height()) || !Float.isFinite(clip.radius())
                        || clip.width() < 0 || clip.height() < 0) {
                    throw new IllegalArgumentException("Invalid GPU UI clip at command " + commandIndex);
                }
                ScreenRect next = rect(clip.x(), clip.y(), clip.width(), clip.height()).transform(pose);
                ScreenRect intersection = activeClip.scissor == null ? next
                        : activeClip.scissor.intersection(next);
                boolean empty = activeClip.empty || next.width() <= 0 || next.height() <= 0
                        || intersection == null;
                List<UiDrawCommand.ClipPush> chain = append(activeClip.chain, clip);
                GpuUiClipMask mask = activeClip.mask;
                // Only rounded clips need a mask: a square clip is fully expressed by the intersected
                // scissor above. Building a full-resolution mask texture and pass for each of the ~7
                // square clips per frame was one of the heaviest per-frame costs. The child keeps the
                // parent's mask, so a square clip inside a rounded one still clips correctly.
                if (!empty && roundedClipMasksAvailable && clip.radius() > 0f) {
                    GpuUiClipMask parentMask = activeClip.mask;
                    try {
                        mask = clipMasks.computeIfAbsent(chain, ignored -> parentMask.push(
                                pose, clip, framebufferWidth, framebufferHeight, scaledWidth, scaledHeight));
                    } catch (RuntimeException | LinkageError exception) {
                        // Keep rendering with the intersected rectangular scissor if optional rounded-mask
                        // resources are unavailable on this device or driver.
                        roundedClipMasksAvailable = false;
                        mask = parentMask;
                        if (!warnedRoundedClipFallback) {
                            warnedRoundedClipFallback = true;
                            ClientInternalLog.warn("GPU UI", "Rounded clipping unavailable; using rectangular clipping", exception);
                        }
                    }
                }
                activeClip = new ClipState(intersection, mask, empty, chain);
            } else if (command instanceof UiDrawCommand.ClipPop) {
                if (clips.isEmpty()) {
                    throw new IllegalStateException("GPU UI clip stack underflow at command " + commandIndex);
                }
                activeClip = clips.pop();
            } else if (command instanceof UiDrawCommand.Effect effect) {
                if (!activeClip.empty) {
                    GpuBackdropBlur.addEffect(state, pose, effect.x(), effect.y(), effect.width(), effect.height(),
                            effect.radius(), effect.kind(), effect.strength(), effect.argb(), activeClip.scissor, activeClip.mask);
                }
            } else if (command instanceof UiDrawCommand.Shadow shadow) {
                if (!activeClip.empty) addShadow(state, shadow, pose, activeClip);
            } else if (command instanceof UiDrawCommand.Fluid fluid) {
                if (!activeClip.empty) {
                    GpuFluidSimulation.render(state, pose, fluid.x(), fluid.y(), fluid.width(), fluid.height(),
                            fluid.radius(), fluid.cursorU(), fluid.cursorV(), fluid.cursorVelU(), fluid.cursorVelV(),
                            fluid.dye0(), fluid.dye1(), fluid.dye2(), fluid.colorCount(),
                            fluid.opacity(), fluid.flow(), fluid.inject(), activeClip.scissor, activeClip.mask);
                }
            }
            commandIndex++;
        }
        if (!clips.isEmpty()) throw new IllegalStateException("GPU UI display list ended with an unclosed clip");
    }

    /**
     * Scissors are whole GUI units, so a clip edge that falls between two of them would let content
     * bleed up to one GUI unit past it. Aligning pixel-space clips to that grid makes the scissor and
     * the clip rectangle the same thing.
     */
    private static UiDrawCommand.ClipPush snapClip(UiDrawCommand.ClipPush clip) {
        if (!pixelSpace || pixelsPerGuiUnit <= 1f || !Float.isFinite(clip.x()) || !Float.isFinite(clip.y())
                || !Float.isFinite(clip.width()) || !Float.isFinite(clip.height())) return clip;
        float k = pixelsPerGuiUnit;
        float left = Math.round(clip.x() / k) * k, top = Math.round(clip.y() / k) * k;
        float right = Math.round((clip.x() + clip.width()) / k) * k, bottom = Math.round((clip.y() + clip.height()) / k) * k;
        return new UiDrawCommand.ClipPush(left, top, Math.max(0f, right - left), Math.max(0f, bottom - top), clip.radius());
    }

    private static void addShadow(GuiRenderState state, UiDrawCommand.Shadow shadow,
                                  Matrix3x2fc pose, ClipState clip) {
        float width = shadow.width(), height = shadow.height();
        if (!(width > 0) || !(height > 0)) return;
        // Shadows are decorative: when the shadow shader did not compile on this driver, draw none.
        if (!SafePipelines.usable(SHADOW_PIPELINE)) return;
        float spread = clampFinite(shadow.spread(), 0, 64);
        float softness = clampFinite(shadow.softness(), 0, 64);
        float radius = Math.min(clampFinite(shadow.radius(), 0, Math.min(width, height) * 0.5f),
                Math.min(width, height) * 0.5f);
        float padding = spread + softness;
        float x = shadow.x() - padding, y = shadow.y() - padding;
        float quadWidth = width + padding * 2, quadHeight = height + padding * 2;
        ScreenRect bounds = screenBounds(x, y, quadWidth, quadHeight, pose);
        ScreenRect scissor = intersectionForDraw(clip, bounds);
        if (clip.scissor != null && scissor == null) return;
        state.addSimpleElement(new ShadowState(pose, x, y, quadWidth, quadHeight,
                padding, width, height, radius, softness, spread, shadow.argb(),
                clip.mask.withMask(TextureSetup.empty()), bounds, scissor));
    }

    private static float clampFinite(float value, float minimum, float maximum) {
        return Float.isFinite(value) ? Math.max(minimum, Math.min(value, maximum)) : minimum;
    }

    private static void drawText(DrawContext context, GuiRenderState state, UiDrawCommand.Text text,
                                 Matrix3x2fc pose, ClipState clip) {
        if (text.size() <= 0 || text.text().isEmpty()) return;
        if (!SafePipelines.usable(TEXT_PIPELINE)) {
            drawVanillaText(context, text, pose, clip);
            return;
        }
        UiDrawCommand.TextWeight weight = text.weight();
        GpuUiFontMetrics.Face face = GpuUiFontMetrics.face(weight);
        TextureSetup texture = fontTexture(face);
        if (texture == null) return;
        // Pixel-space text starts on a whole device pixel so glyph edges don't sit between pixels.
        float originX = pixelSpace ? Math.round(text.x()) : text.x();
        float originY = pixelSpace ? Math.round(text.y()) : text.y();
        GpuUiFontMetrics.layout(text.text(), originX, originY, text.size(), weight,
                (left, top, width, height, u0, v0, u1, v1) -> addQuad(state, left, top, width, height, 0,
                        text.argb(), pose, clip, texture, u0, v0, u1, v1, true, false, 0, TEXT_PIPELINE));
    }

    private static void addQuad(GuiRenderState state, float x, float y, float width, float height,
                                float radius, int argb, Matrix3x2fc pose, ClipState clip,
                                TextureSetup texture, float u0, float v0, float u1, float v1,
                                boolean text, boolean image, float borderThickness) {
        addQuad(state, x, y, width, height, radius, argb, pose, clip,
                texture, u0, v0, u1, v1, text, image, borderThickness, TEXT_PIPELINE);
    }

    private static void addQuad(GuiRenderState state, float x, float y, float width, float height,
                                float radius, int argb, Matrix3x2fc pose, ClipState clip,
                                TextureSetup texture, float u0, float v0, float u1, float v1,
                                boolean text, boolean image, float borderThickness, RenderPipeline textPipeline) {
        if (!(width > 0) || !(height > 0)) return;
        if (!text && (!image || pixelSpace)) {
            float rx, ry, rw, rh;
            if (radius > 0f && radius * 2f >= Math.min(width, height) - 0.01f) {
                // Circle or capsule: snap the size first, then centre it, so a circle stays exactly
                // w == h and both caps get identical rounding. Snapping the edges independently can
                // turn a 14.4px circle into a 15x14 lozenge with one flat side.
                rw = Math.max(1.0f, Math.round(width));
                rh = Math.max(1.0f, Math.round(height));
                rx = Math.round(x + (width - rw) * 0.5f);
                ry = Math.round(y + (height - rh) * 0.5f);
                radius = Math.min(rw, rh) * 0.5f;
            } else {
                rx = Math.round(x);
                ry = Math.round(y);
                rw = Math.max(1.0f, Math.round(x + width) - rx);
                rh = Math.max(1.0f, Math.round(y + height) - ry);
            }
            x = rx;
            y = ry;
            width = rw;
            height = rh;
            if (borderThickness > 0.0f) {
                borderThickness = Math.max(1.0f, pixelSpace ? Math.round(borderThickness) : borderThickness);
            }
        }
        ScreenRect bounds = screenBounds(x, y, width, height, pose);
        ScreenRect scissor = intersectionForDraw(clip, bounds);
        if (clip.scissor != null && scissor == null) return;
        RenderPipeline pipeline = text ? textPipeline : image ? IMAGE_PIPELINE : RECT_PIPELINE;
        if (!SafePipelines.usable(pipeline)) {
            // The UI shader failed to compile on this driver: draw a flat vanilla fill / plain textured
            // quad instead (square corners, no clip mask) so the menu stays usable.
            if (text) return;
            if (!image && borderThickness > 0.0f) {
                float t = Math.min(borderThickness, Math.min(width, height) * 0.5f);
                addFlatQuad(state, x, y, width, t, argb, pose, clip);
                addFlatQuad(state, x, y + height - t, width, t, argb, pose, clip);
                addFlatQuad(state, x, y + t, t, height - 2 * t, argb, pose, clip);
                addFlatQuad(state, x + width - t, y + t, t, height - 2 * t, argb, pose, clip);
                return;
            }
            state.addSimpleElement(new QuadState(image ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI,
                    image ? texture : TextureSetup.empty(), pose, x, y, width, height, 0, argb, 0,
                    u0, v0, u1, v1, false, image, bounds, scissor));
            return;
        }
        TextureSetup base = text || image ? texture : TextureSetup.empty();
        state.addSimpleElement(new QuadState(pipeline, clip.mask.withMask(base), pose, x, y, width, height,
                Math.min(Math.max(radius, 0), Math.min(width, height) * 0.5f), argb,
                Math.max(0.0f, Math.min(borderThickness, Math.min(width, height) * 0.5f)),
                u0, v0, u1, v1, text, image, bounds, scissor));
    }

    /** Flat vanilla GUI fill, used only when the rounded-rect shader is unavailable. */
    private static void addFlatQuad(GuiRenderState state, float x, float y, float width, float height,
                                    int argb, Matrix3x2fc pose, ClipState clip) {
        if (!(width > 0) || !(height > 0)) return;
        ScreenRect bounds = screenBounds(x, y, width, height, pose);
        ScreenRect scissor = intersectionForDraw(clip, bounds);
        if (clip.scissor != null && scissor == null) return;
        state.addSimpleElement(new QuadState(RenderPipelines.GUI, TextureSetup.empty(), pose, x, y, width, height,
                0, argb, 0, 0, 0, 1, 1, false, false, bounds, scissor));
    }

    /**
     * Vanilla-font text, used only when the MSDF text shader is unavailable on this driver. Sized to the
     * requested pixel height; clipped by the active rectangular scissor.
     */
    private static void drawVanillaText(DrawContext context, UiDrawCommand.Text text, Matrix3x2fc pose,
                                        ClipState clip) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (context == null || client == null || client.textRenderer == null) return;
        var matrices = context.getMatrices();
        boolean scissored = clip.scissor != null;
        if (scissored) {
            matrices.pushMatrix();
            matrices.identity();
            context.enableScissor(clip.scissor.getLeft(), clip.scissor.getTop(),
                    clip.scissor.getRight(), clip.scissor.getBottom());
            matrices.popMatrix();
        }
        matrices.pushMatrix();
        try {
            matrices.set(pose);
            float scale = text.size() / (float) client.textRenderer.fontHeight;
            matrices.translate(text.x(), text.y());
            matrices.scale(scale, scale);
            context.drawText(client.textRenderer, text.text(), 0, 0, text.argb(), false);
        } finally {
            matrices.popMatrix();
            if (scissored) context.disableScissor();
        }
    }

    private static ScreenRect intersectionForDraw(ClipState clip, ScreenRect bounds) {
        return clip.scissor == null ? null : clip.scissor.intersection(bounds);
    }

    private static List<UiDrawCommand.ClipPush> append(List<UiDrawCommand.ClipPush> parent,
                                                        UiDrawCommand.ClipPush child) {
        ArrayList<UiDrawCommand.ClipPush> copy = new ArrayList<>(parent.size() + 1);
        copy.addAll(parent);
        copy.add(child);
        return List.copyOf(copy);
    }

    private static int physicalSize(float logicalSize, int framebufferSize, int scaledSize) {
        if (!(logicalSize > 0) || framebufferSize <= 0 || scaledSize <= 0) return 0;
        double pixels = Math.ceil(logicalSize * framebufferSize / (double) scaledSize);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, pixels));
    }

    private static TextureSetup imageTexture(Identifier identifier) {
        if (identifier == null) return null;
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(identifier);
        var sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        if (texture.getGlTextureView() == null || sampler == null) return null;
        return TextureSetup.of(texture.getGlTextureView(), sampler);
    }

    /**
     * A draw's bounds in GUI units, from its pose-transformed corners rounded OUTWARD. The menu is laid
     * out in framebuffer pixels and the pose scales it down by the GUI scale, so converting an integer
     * pixel rect with ScreenRect.transform could land the right/bottom edge inside the shape; the draw's
     * scissor then shaved its right and bottom (a toggle knob lost those curves and looked square while
     * its top-left stayed round).
     */
    private static ScreenRect screenBounds(float x, float y, float width, float height, Matrix3x2fc pose) {
        org.joml.Vector2f a = pose.transformPosition(x, y, new org.joml.Vector2f());
        org.joml.Vector2f c = pose.transformPosition(x + width, y + height, new org.joml.Vector2f());
        int left = (int) Math.floor(Math.min(a.x, c.x));
        int top = (int) Math.floor(Math.min(a.y, c.y));
        int right = (int) Math.ceil(Math.max(a.x, c.x));
        int bottom = (int) Math.ceil(Math.max(a.y, c.y));
        return new ScreenRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static ScreenRect rect(float x, float y, float width, float height) {
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        int right = (int) Math.ceil(x + width);
        int bottom = (int) Math.ceil(y + height);
        return new ScreenRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    public static float textWidth(String text, float size) {
        return textWidth(text, size, UiDrawCommand.TextWeight.REGULAR);
    }

    public static float textWidth(String text, float size, UiDrawCommand.TextWeight weight) {
        return GpuUiFontMetrics.textWidth(text, size, weight);
    }

    private static TextureSetup fontTexture(GpuUiFontMetrics.Face face) {
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(Identifier.of("dynamic_fps", face.base() + ".png"));
        if (texture.getGlTextureView() == null) return null;
        var sampler = RenderSystem.getSamplerCache().get(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        return TextureSetup.of(texture.getGlTextureView(), sampler);
    }

    private record ClipState(ScreenRect scissor, GpuUiClipMask mask, boolean empty,
                             List<UiDrawCommand.ClipPush> chain) {
        private static ClipState root() {
            return new ClipState(null, GpuUiClipMask.none(), false, List.of());
        }
    }

    private record QuadState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose,
                             float x, float y, float width, float height, float radius, int color,
                             float borderThickness,
                             float u0, float v0, float u1, float v1,
                             boolean text, boolean image,
                             ScreenRect bounds, ScreenRect scissorArea)
            implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer vertices) {
            int rw = Math.min(65535, Math.max(1, Math.round(width)));
            int rh = Math.min(65535, Math.max(1, Math.round(height)));
            int packedDimensions = (rh << 16) | rw;
            vertex(vertices, x, y, 0, 0, u0, v0, packedDimensions);
            vertex(vertices, x + width, y, width, 0, u1, v0, packedDimensions);
            vertex(vertices, x + width, y + height, width, height, u1, v1, packedDimensions);
            vertex(vertices, x, y + height, 0, height, u0, v1, packedDimensions);
        }

        private void vertex(VertexConsumer vertices, float px, float py, float localX, float localY,
                            float u, float v, int dimensions) {
            int effectData = image ? packNormalized(localX, width, localY, height)
                    : Math.min(32767, Math.max(0, Math.round(borderThickness * 256.0f)));
            vertices.vertex(pose, px, py).color(color).texture(text || image ? u : localX,
                    text || image ? v : localY).overlay(dimensions).lineWidth(radius).light(effectData);
        }

        private static int packNormalized(float x, float width, float y, float height) {
            int u = Math.round(clampFinite(x / width, 0.0f, 1.0f) * 65535.0f);
            int v = Math.round(clampFinite(y / height, 0.0f, 1.0f) * 65535.0f);
            return (v << 16) | u;
        }
    }

    private record GradientState(Matrix3x2fc pose, UiDrawCommand.GradientRect gradient,
                                 RenderPipeline pipeline, TextureSetup textureSetup,
                                 ScreenRect bounds, ScreenRect scissorArea)
            implements SimpleGuiElementRenderState {

        @Override public void setupVertices(VertexConsumer vertices) {
            float rx = Math.round(gradient.x());
            float ry = Math.round(gradient.y());
            float rw = Math.max(1.0f, Math.round(gradient.x() + gradient.width()) - rx);
            float rh = Math.max(1.0f, Math.round(gradient.y() + gradient.height()) - ry);
            float radius = Math.min(Math.max(gradient.radius(), 0), Math.min(rw, rh) * 0.5f);
            int dimW = Math.min(65535, Math.max(1, Math.round(rw)));
            int dimH = Math.min(65535, Math.max(1, Math.round(rh)));
            int dimensions = (dimH << 16) | dimW;
            vertex(vertices, rx, ry, 0, 0, gradient.topLeft(), dimensions, radius);
            vertex(vertices, rx + rw, ry, rw, 0, gradient.topRight(), dimensions, radius);
            vertex(vertices, rx + rw, ry + rh, rw, rh, gradient.bottomRight(), dimensions, radius);
            vertex(vertices, rx, ry + rh, 0, rh, gradient.bottomLeft(), dimensions, radius);
        }

        private void vertex(VertexConsumer vertices, float x, float y, float localX, float localY,
                            int color, int dimensions, float radius) {
            vertices.vertex(pose, x, y).color(color).texture(localX, localY)
                    .overlay(dimensions).lineWidth(radius).light(Float.floatToRawIntBits(0.0f));
        }
    }

    private record ShadowState(Matrix3x2fc pose, float x, float y, float width, float height,
                               float padding, float shapeWidth, float shapeHeight, float radius,
                               float softness, float spread, int color,
                               TextureSetup textureSetup,
                               ScreenRect bounds, ScreenRect scissorArea)
            implements SimpleGuiElementRenderState {
        @Override public RenderPipeline pipeline() { return SHADOW_PIPELINE; }

        @Override public void setupVertices(VertexConsumer vertices) {
            int dimW = Math.min(65535, Math.max(1, Math.round(shapeWidth)));
            int dimH = Math.min(65535, Math.max(1, Math.round(shapeHeight)));
            int packedDimensions = (dimH << 16) | dimW;
            int packedEffects = (Math.round(spread * 256) << 16) | Math.round(softness * 256);
            vertex(vertices, x, y, -padding, -padding, packedDimensions, packedEffects);
            vertex(vertices, x + width, y, shapeWidth + padding, -padding, packedDimensions, packedEffects);
            vertex(vertices, x + width, y + height, shapeWidth + padding, shapeHeight + padding,
                    packedDimensions, packedEffects);
            vertex(vertices, x, y + height, -padding, shapeHeight + padding, packedDimensions, packedEffects);
        }

        private void vertex(VertexConsumer vertices, float px, float py, float localX, float localY,
                            int dimensions, int effects) {
            vertices.vertex(pose, px, py).color(color).texture(localX, localY)
                    .overlay(dimensions).lineWidth(radius).light(effects);
        }
    }

}
