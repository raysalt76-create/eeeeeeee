package interstellarTikiTiks.apexx.client.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.module.hooks.CustomFontProvider;
import interstellarTikiTiks.apexx.client.rendering.font.MSDFFontRenderer;
import interstellarTikiTiks.apexx.client.shader.CoreShaderManager;
import interstellarTikiTiks.apexx.client.util.IApexxRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.*;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

public class VanillaRendererImpl implements IApexxRenderer {

    private static final Identifier DEFAULT_MSDF_FONT = Identifier.of("dynamic_fps", "sans");
    private static final float UV_RADIUS_PACK_STRIDE = 2.0f;
    private static final float UV_RADIUS_PACK_SCALE = 4.0f;
    private float cachedClientFontScale = 1.0f;
    private boolean cachedUseClientShadow = true;
    private boolean cachedUseGlobalShadow = true;
    private Identifier cachedFontId = null;
    private boolean cachedMsdfEnabled = false;
    private long lastFontCacheFrame = -1;

    private final Matrix4f currentMatrix = new Matrix4f();
    private final Stack<Matrix4f> matrixStack = new Stack<>();
    private float globalPixelRatio;

    private DrawContext activeContext;

    private final Map<Integer, Identifier> textureMap = new HashMap<>();
    private int nextTextureId = 1;

    private boolean shouldUseCustomPipelines() {
        return true;
    }

    private GuiShaderStyle getDefaultRoundedStyle() {
        return RenderBackendDetector.isVulkanBackend() ? GuiShaderStyle.HUD : GuiShaderStyle.DEFAULT;
    }

    private RenderPipeline getRoundedRectPipeline(GuiShaderStyle style) {
        GuiShaderStyle effectiveStyle = style != null ? style : getDefaultRoundedStyle();
        if (effectiveStyle == GuiShaderStyle.ICON) {
            effectiveStyle = getDefaultRoundedStyle();
        }
        RenderPipeline pipeline = CoreShaderManager.getInstance().getPipeline(effectiveStyle.pipelineId());
        if (pipeline == null && effectiveStyle == GuiShaderStyle.DEFAULT && RenderBackendDetector.isVulkanBackend()) {
            pipeline = CoreShaderManager.getInstance().getPipeline(GuiShaderStyle.HUD.pipelineId());
        }
        if (pipeline != null) {
            return pipeline;
        }

        if (effectiveStyle != GuiShaderStyle.DEFAULT) {
            return CoreShaderManager.getInstance().getPipeline(GuiShaderStyle.DEFAULT.pipelineId());
        }

        return null;
    }

    private RenderPipeline getTexturePipeline(GuiShaderStyle style) {
        if (style != null) {
            RenderPipeline styledPipeline = CoreShaderManager.getInstance().getPipeline(style.pipelineId());
            if (styledPipeline != null) {
                return styledPipeline;
            }
        }
        return CoreShaderManager.getInstance().getPipeline(Identifier.of("dynamic_fps", "image"));
    }

    private float encodeRadiusForPackedUv(float radius) {
        float clampedRadius = Math.max(0.0f, Math.min(255.0f, radius));
        float packedRadius = Math.round(clampedRadius * UV_RADIUS_PACK_SCALE);
        return packedRadius * UV_RADIUS_PACK_STRIDE;
    }

    private float packUvXWithRadius(float uvX, float radius) {
        return encodeRadiusForPackedUv(radius) + uvX;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean beginFrame(float width, float height, float pixelRatio) {
        this.globalPixelRatio = pixelRatio;
        this.currentMatrix.identity();
        this.matrixStack.clear();
        this.activeContext = null;
        return true;
    }

    @Override
    public void endFrame() {
        this.activeContext = null;
    }

    @Override
    public void save() {
        matrixStack.push(new Matrix4f(currentMatrix));
    }

    @Override
    public void restore() {
        if (!matrixStack.isEmpty()) {
            currentMatrix.set(matrixStack.pop());
        }
    }

    @Override
    public void translate(float x, float y) {
        currentMatrix.translate(x, y, 0);
    }

    @Override
    public void scale(float x, float y) {
        currentMatrix.scale(x, y, 1.0f);
    }

    @Override
    public void cleanup() {
        matrixStack.clear();
        textureMap.clear();
    }

    @Override
    public void drawRect(DrawContext context, float x, float y, float width, float height, Color color) {
        if (context == null)
            return;

        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);

        context.fill(
                Math.round(p1.x), Math.round(p1.y),
                Math.round(p2.x), Math.round(p2.y),
                color.getRGB());
    }

    @Override
    public void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float radius,
            Color color) {
        drawRoundedRectInternal(context, x, y, width, height, radius, color, getDefaultRoundedStyle());
    }

    @Override
    public void drawRoundedRectStyled(DrawContext context, float x, float y, float width, float height, float radius,
            Color color, GuiShaderStyle style) {
        drawRoundedRectInternal(context, x, y, width, height, radius, color, style);
    }

    private void drawRoundedRectInternal(DrawContext context, float x, float y, float width, float height, float radius,
            Color color, GuiShaderStyle style) {
        if (context == null) {
            return;
        }

        if (radius <= 0.5f) {
            drawRect(context, x, y, width, height, color);
            return;
        }

        RenderPipeline curvePipeline = shouldUseCustomPipelines()
                ? getRoundedRectPipeline(style)
                : null;

        if (curvePipeline == null) {
            drawRoundedRectSoftware(context, x, y, width, height, radius, color);
            return;
        }

        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
        float packedU0 = packUvXWithRadius(0.0f, radius);
        float packedU1 = packUvXWithRadius(1.0f, radius);

        int quadX0 = (int) Math.floor(Math.min(p1.x, p2.x));
        int quadY0 = (int) Math.floor(Math.min(p1.y, p2.y));
        int quadX1 = (int) Math.ceil(Math.max(p1.x, p2.x));
        int quadY1 = (int) Math.ceil(Math.max(p1.y, p2.y));
        if (quadX1 <= quadX0 || quadY1 <= quadY0) {
            return;
        }

        context.state.addSimpleElement(new TexturedQuadGuiElementRenderState(
                curvePipeline,
                TextureSetup.empty(),
                new Matrix3x2f(context.getMatrices()),
                quadX0,
                quadY0,
                quadX1,
                quadY1,
                packedU0,
                packedU1,
                0.0f,
                1.0f,
                color.getRGB(),
                context.scissorStack.peekLast()));
    }

    @Override
    public void drawGlowingRect(DrawContext context, float x, float y, float width, float height, float radius,
                                Color innerColor, Color outerColor, float glowSize) {
        if (context == null) return;

        int layers = Math.min(5, (int) glowSize);
        for (int i = layers; i >= 1; i--) {
            float expand = i * 1.2f;
            int a = (int) (innerColor.getAlpha() * (1f - (float) i / glowSize) * 0.4f);
            if (a < 5) continue;
            Color layerColor = new Color(innerColor.getRed(), innerColor.getGreen(), innerColor.getBlue(),
                    Math.max(0, Math.min(255, a)));
            drawRoundedRectStyled(context, x - expand, y - expand, width + expand * 2, height + expand * 2,
                    radius + expand, layerColor, GuiShaderStyle.GLOW);
        }
    }

    @Override
    public void drawRectOutline(DrawContext context, float x, float y, float width, float height, float strokeWidth,
            Color color) {
        if (context == null)
            return;

        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);

        float sX = currentMatrix.m00();
        float sY = currentMatrix.m11();

        int x0 = Math.round(p1.x);
        int y0 = Math.round(p1.y);
        int w0 = Math.round(width * sX);
        int h0 = Math.round(height * sY);
        if (w0 <= 0 || h0 <= 0)
            return;

        int x1 = x0 + w0 - 1;
        int y1 = y0 + h0 - 1;
        int rgb = color.getRGB();

        context.drawHorizontalLine(x0, x1, y0, rgb);
        context.drawHorizontalLine(x0, x1, y1, rgb);
        context.drawVerticalLine(x0, y0, y1, rgb);
        context.drawVerticalLine(x1, y0, y1, rgb);
    }

    @Override
    public void drawRoundedRectOutline(DrawContext context, float x, float y, float width, float height, float radius,
            float strokeWidth, Color color) {
        if (context == null)
            return;
        if (radius <= 0.5f) {
            drawRectOutline(context, x, y, width, height, strokeWidth, color);
            return;
        }
        drawRoundedRect(context, x - strokeWidth, y - strokeWidth, width + strokeWidth * 2, height + strokeWidth * 2,
                radius + strokeWidth, color);
    }

    @Override
    public void drawRoundedRectGradient(DrawContext context, float x, float y, float width, float height, float radius,
            Color c1, Color c2, boolean vertical) {
        drawRoundedRectGradientInternal(context, x, y, width, height, radius, c1, c2, vertical,
                getDefaultRoundedStyle());
    }

    @Override
    public void drawRoundedRectGradientStyled(DrawContext context, float x, float y, float width, float height,
            float radius, Color c1, Color c2, boolean vertical, GuiShaderStyle style) {
        drawRoundedRectGradientInternal(context, x, y, width, height, radius, c1, c2, vertical, style);
    }

    private void drawRoundedRectGradientInternal(DrawContext context, float x, float y, float width, float height,
                                                 float radius, Color c1, Color c2, boolean vertical, GuiShaderStyle style) {
        if (context == null || width <= 0.0f || height <= 0.0f) {
            return;
        }

        if (c1.getRGB() == c2.getRGB()) {
            drawRoundedRect(context, x, y, width, height, radius, c1);
            return;
        }

        if (radius <= 0.5f) {
            Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
            Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
            if (vertical) {
                context.fillGradient(Math.round(p1.x), Math.round(p1.y), Math.round(p2.x), Math.round(p2.y),
                        c1.getRGB(), c2.getRGB());
            } else {
                int xStart = Math.round(Math.min(p1.x, p2.x));
                int xEnd   = Math.round(Math.max(p1.x, p2.x));
                int yStart = Math.round(Math.min(p1.y, p2.y));
                int yEnd   = Math.round(Math.max(p1.y, p2.y));
                int span   = Math.max(1, xEnd - xStart);
                for (int ix = 0; ix < span; ix++) {
                    float t = span <= 1 ? 0.0f : (ix + 0.5f) / (float) span;
                    context.fill(xStart + ix, yStart, xStart + ix + 1, yEnd, interpolate(c1, c2, t).getRGB());
                }
            }
            return;
        }

        RenderPipeline curvePipeline = shouldUseCustomPipelines() ? getRoundedRectPipeline(style) : null;
        if (curvePipeline == null) {
            drawRoundedRectGradientSoftware(context, x, y, width, height, radius, c1, c2, vertical);
            return;
        }

        Vector4f fullP1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f fullP2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
        

        

        

        

        

        int fullX0 = (int) Math.floor(Math.min(fullP1.x, fullP2.x));
        int fullY0 = (int) Math.floor(Math.min(fullP1.y, fullP2.y));
        int fullX1 = (int) Math.ceil(Math.max(fullP1.x, fullP2.x));
        int fullY1 = (int) Math.ceil(Math.max(fullP1.y, fullP2.y));

        int totalPixelSpan = vertical ? (fullY1 - fullY0) : (fullX1 - fullX0);
        if (totalPixelSpan <= 0) return;
        if (totalPixelSpan > 2000) {
            drawRoundedRect(context, x, y, width, height, radius, interpolate(c1, c2, 0.5f));
            return;
        }

        float packedU0 = packUvXWithRadius(0.0f, radius);
        float packedU1 = packUvXWithRadius(1.0f, radius);
        Matrix3x2f mat = new Matrix3x2f(context.getMatrices());

        int prevRgb  = Integer.MIN_VALUE;
        int runStart = 0;

        for (int px = 0; px <= totalPixelSpan; px++) {
            int curRgb = prevRgb;
            if (px < totalPixelSpan) {
                float t = totalPixelSpan <= 1 ? 0.0f : (px + 0.5f) / (float) totalPixelSpan;
                curRgb = interpolate(c1, c2, t).getRGB();
            }

            

            

            

            

            if ((curRgb != prevRgb || px == totalPixelSpan) && prevRgb != Integer.MIN_VALUE) {
                int stripX0, stripY0, stripX1, stripY1;
                if (vertical) {
                    stripX0 = fullX0;  stripX1 = fullX1;
                    stripY0 = fullY0 + runStart;
                    stripY1 = fullY0 + px;
                } else {
                    stripX0 = fullX0 + runStart;
                    stripX1 = fullX0 + px;
                    stripY0 = fullY0;  stripY1 = fullY1;
                }

                context.enableScissor(stripX0, stripY0, stripX1, stripY1);
                context.state.addSimpleElement(new TexturedQuadGuiElementRenderState(
                        curvePipeline,
                        TextureSetup.empty(),
                        mat,
                        fullX0, fullY0, fullX1, fullY1,
                        packedU0, packedU1,
                        0.0f, 1.0f,
                        prevRgb,
                        context.scissorStack.peekLast()));
                context.disableScissor();

                runStart = px;
            }

            prevRgb = curRgb;
        }
    }
    private void drawRoundedRectSoftware(DrawContext context, float x, float y, float width, float height, float radius,
            Color color) {
        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
        int x0 = Math.round(Math.min(p1.x, p2.x));
        int y0 = Math.round(Math.min(p1.y, p2.y));
        int x1 = Math.round(Math.max(p1.x, p2.x));
        int y1 = Math.round(Math.max(p1.y, p2.y));

        int scaledRadius = scaleRadius(radius, x1 - x0, y1 - y0);
        fillRoundedRectPixels(context, x0, y0, x1, y1, scaledRadius, color.getRGB());
    }

    private void drawRoundedRectGradientSoftware(DrawContext context, float x, float y, float width, float height,
            float radius, Color c1, Color c2, boolean vertical) {
        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
        int x0 = Math.round(Math.min(p1.x, p2.x));
        int y0 = Math.round(Math.min(p1.y, p2.y));
        int x1 = Math.round(Math.max(p1.x, p2.x));
        int y1 = Math.round(Math.max(p1.y, p2.y));
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 0 || h <= 0) {
            return;
        }

        int r = scaleRadius(radius, w, h);
        if (r <= 0) {
            if (vertical) {
                context.fillGradient(x0, y0, x1, y1, c1.getRGB(), c2.getRGB());
            } else {
                for (int ix = 0; ix < w; ix++) {
                    float t = w <= 1 ? 0.0f : (float) ix / (float) (w - 1);
                    int rgb = interpolate(c1, c2, t).getRGB();
                    context.fill(x0 + ix, y0, x0 + ix + 1, y1, rgb);
                }
            }
            return;
        }

        if (vertical) {
            for (int iy = 0; iy < h; iy++) {
                float t = h <= 1 ? 0.0f : (float) iy / (float) (h - 1);
                int rgb = interpolate(c1, c2, t).getRGB();
                float insetF = roundedInsetFloatForRow(iy, h, r);
                int inset = (int) Math.floor(insetF);
                float frac = insetF - inset;
                int lx = x0 + inset;
                int rx = x1 - inset;
                if (rx > lx) {
                    context.fill(lx, y0 + iy, rx, y0 + iy + 1, rgb);
                }

                int aaColor = scaleAlpha(rgb, 1.0f - frac);
                if ((aaColor >>> 24) != 0) {
                    if (lx - 1 >= x0) {
                        context.fill(lx - 1, y0 + iy, lx, y0 + iy + 1, aaColor);
                    }
                    if (rx < x1) {
                        context.fill(rx, y0 + iy, rx + 1, y0 + iy + 1, aaColor);
                    }
                }
            }
            return;
        }

        for (int ix = 0; ix < w; ix++) {
            float t = w <= 1 ? 0.0f : (float) ix / (float) (w - 1);
            int rgb = interpolate(c1, c2, t).getRGB();
            float insetF = roundedInsetFloatForColumn(ix, w, r);
            int inset = (int) Math.floor(insetF);
            float frac = insetF - inset;
            int ty = y0 + inset;
            int by = y1 - inset;
            if (by > ty) {
                context.fill(x0 + ix, ty, x0 + ix + 1, by, rgb);
            }

            int aaColor = scaleAlpha(rgb, 1.0f - frac);
            if ((aaColor >>> 24) != 0) {
                if (ty - 1 >= y0) {
                    context.fill(x0 + ix, ty - 1, x0 + ix + 1, ty, aaColor);
                }
                if (by < y1) {
                    context.fill(x0 + ix, by, x0 + ix + 1, by + 1, aaColor);
                }
            }
        }
    }

    private void fillRoundedRectPixels(DrawContext context, int x0, int y0, int x1, int y1, int radius, int color) {
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 0 || h <= 0) {
            return;
        }

        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r <= 0) {
            context.fill(x0, y0, x1, y1, color);
            return;
        }

        context.fill(x0 + r, y0, x1 - r, y1, color);
        context.fill(x0, y0 + r, x0 + r, y1 - r, color);
        context.fill(x1 - r, y0 + r, x1, y1 - r, color);

        for (int iy = 0; iy < r; iy++) {
            float insetF = roundedInsetFloatFromEdge(iy, r);
            int inset = (int) Math.floor(insetF);
            float frac = insetF - inset;
            int lx = x0 + inset;
            int rx = x1 - inset;
            int topY = y0 + iy;
            int bottomY = y1 - iy - 1;

            if (rx > lx) {
                context.fill(lx, topY, rx, topY + 1, color);
                context.fill(lx, bottomY, rx, bottomY + 1, color);
            }

            int aaColor = scaleAlpha(color, 1.0f - frac);
            if ((aaColor >>> 24) != 0) {
                if (lx - 1 >= x0) {
                    context.fill(lx - 1, topY, lx, topY + 1, aaColor);
                    context.fill(lx - 1, bottomY, lx, bottomY + 1, aaColor);
                }
                if (rx < x1) {
                    context.fill(rx, topY, rx + 1, topY + 1, aaColor);
                    context.fill(rx, bottomY, rx + 1, bottomY + 1, aaColor);
                }
            }
        }
    }

    private int roundedInsetForRow(int iy, int height, int radius) {
        return (int) Math.floor(roundedInsetFloatForRow(iy, height, radius));
    }

    private int roundedInsetForColumn(int ix, int width, int radius) {
        return (int) Math.floor(roundedInsetFloatForColumn(ix, width, radius));
    }

    private int roundedInsetFromEdge(int edgeCoord, int radius) {
        return (int) Math.floor(roundedInsetFloatFromEdge(edgeCoord, radius));
    }

    private float roundedInsetFloatForRow(int iy, int height, int radius) {
        if (iy < radius) {
            return roundedInsetFloatFromEdge(iy, radius);
        }
        if (iy >= height - radius) {
            return roundedInsetFloatFromEdge(height - iy - 1, radius);
        }
        return 0.0f;
    }

    private float roundedInsetFloatForColumn(int ix, int width, int radius) {
        if (ix < radius) {
            return roundedInsetFloatFromEdge(ix, radius);
        }
        if (ix >= width - radius) {
            return roundedInsetFloatFromEdge(width - ix - 1, radius);
        }
        return 0.0f;
    }

    private float roundedInsetFloatFromEdge(int edgeCoord, int radius) {
        double dy = radius - edgeCoord - 0.5d;
        double dx = Math.sqrt(Math.max(0.0d, radius * radius - dy * dy));
        return (float) Math.max(0.0d, radius - dx);
    }

    private int scaleAlpha(int argb, float factor) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return argb;
        }

        float clamped = Math.max(0.0f, Math.min(1.0f, factor));
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * clamped)));
        return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
    }

    private int scaleRadius(float radius, int width, int height) {
        float sx = Math.abs(currentMatrix.m00());
        float sy = Math.abs(currentMatrix.m11());
        float scaled = radius * Math.max(0.0001f, Math.min(sx, sy));
        int maxRadius = Math.max(0, Math.min(width, height) / 2);
        return Math.max(0, Math.min(maxRadius, Math.round(scaled)));
    }

    @Override
    public void drawText(DrawContext context, String text, float x, float y, float fontSize, Color color,
            boolean shadow) {
        if (context == null) {
            return;
        }
        text = VString.decode(text);
        if (text.isEmpty()) {
            return;
        }

        Vector4f pos = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        float scaleX = Math.abs(currentMatrix.m00());
        float scaleY = Math.abs(currentMatrix.m11());
        float matrixTextScale = Math.max(0.0001f, (scaleX + scaleY) * 0.5f);
        boolean renderShadow = useClientShadow(shadow) && useGlobalShadow(shadow);
        Identifier selectedFont = getEffectiveFontId();
        boolean preferMsdf = shouldPreferMsdf(selectedFont);
        float rawFontScale = getTextScale(fontSize) * getClientFontScale() * matrixTextScale;
        float fontScale = preferMsdf ? rawFontScale : normalizeFontScale(rawFontScale);
        

        float pixelSnapScale = Math.max(1.0f, globalPixelRatio);
        float drawX = snapToPixel(pos.x, pixelSnapScale);
        float drawY = snapToPixel(pos.y, pixelSnapScale);

        if (preferMsdf) {
            boolean rendered = MSDFFontRenderer.drawText(
                    context,
                    selectedFont,
                    text,
                    drawX,
                    drawY,
                    fontScale,
                    color.getRGB(),
                    renderShadow);
            if (rendered) {
                return;
            }
        }

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(drawX, drawY);
        context.getMatrices().scale(fontScale, fontScale);
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (cf != null) {
            cf.renderText(context, tr, text, 0.0f, 0.0f, color.getRGB(), renderShadow);
        } else {
            context.drawText(tr, text, 0, 0, color.getRGB(), renderShadow);
        }

        context.getMatrices().popMatrix();
    }

    @Override
    public void drawBlur(DrawContext context, float x, float y, float width, float height, float blurRadius) {
        if (context == null || width <= 0.0f || height <= 0.0f || blurRadius <= 0.0f) {
            return;
        }

        int layers = Math.max(2, Math.min(8, Math.round(blurRadius / 3.0f)));
        float maxExpand = Math.max(1.0f, Math.min(blurRadius, 14.0f));
        float configuredRadius = ClickGUIModule.getGuiCornerRadiusScaled(0.90);
        float baseRadius = Math.max(2.0f, Math.min(Math.min(width, height) * 0.5f, configuredRadius));

        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            float expand = maxExpand * t;
            int alpha = Math.max(2, Math.min(34, Math.round((0.10f + 0.20f * t) * 255.0f)));
            Color layer = new Color(18, 22, 30, alpha);
            drawRoundedRectStyled(context, x - expand, y - expand, width + expand * 2.0f,
                    height + expand * 2.0f, baseRadius + expand, layer, GuiShaderStyle.PANEL_GLASS);
        }
    }

    public void drawBlurSimple(DrawContext context, float x, float y, float width, float height, float blurRadius) {
        drawBlur(context, x, y, width, height, blurRadius);
    }

    @Override
    public void rotate(float angle) {
        currentMatrix.rotate(angle, 0.0f, 0.0f, 1.0f);
    }

    @Override
    public void drawCircle(DrawContext context, float x, float y, float radius, Color color) {
        if (context == null)
            return;

        Vector4f center = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        float scaleX = currentMatrix.m00();
        float scaleY = currentMatrix.m11();
        float scaledRadius = radius * Math.max(scaleX, scaleY);

        int segments = Math.max(16, (int) (scaledRadius * 2));
        double angleStep = 2 * Math.PI / segments;

        for (int i = 0; i < segments; i++) {
            double angle1 = i * angleStep;
            double angle2 = (i + 1) * angleStep;

            float x1 = center.x + (float) (Math.cos(angle1) * scaledRadius);
            float y1 = center.y + (float) (Math.sin(angle1) * scaledRadius);
            float x2 = center.x + (float) (Math.cos(angle2) * scaledRadius);
            float y2 = center.y + (float) (Math.sin(angle2) * scaledRadius);

            context.fill((int) center.x, (int) center.y, (int) x1, (int) y1, color.getRGB());
            context.fill((int) x1, (int) y1, (int) x2, (int) y2, color.getRGB());
        }
    }

    @Override
    public void drawArc(DrawContext context, float cx, float cy, float radius, float startAngle, float sweepAngle,
            float strokeWidth, Color color) {
        if (context == null)
            return;

        Vector4f center = new Vector4f(cx, cy, 0, 1).mul(currentMatrix);
        float scaleX = currentMatrix.m00();
        float scaleY = currentMatrix.m11();
        float scaledRadius = radius * Math.max(scaleX, scaleY);
        float scaledStroke = strokeWidth * Math.max(scaleX, scaleY);

        double startRad = Math.toRadians(startAngle - 90);
        double sweepRad = Math.toRadians(sweepAngle);

        int segments = Math.max(8, (int) (Math.abs(sweepAngle) / 5));
        double angleStep = sweepRad / segments;

        for (int i = 0; i < segments; i++) {
            double angle1 = startRad + (i * angleStep);
            double angle2 = startRad + ((i + 1) * angleStep);

            float x1 = center.x + (float) (Math.cos(angle1) * scaledRadius);
            float y1 = center.y + (float) (Math.sin(angle1) * scaledRadius);
            float x2 = center.x + (float) (Math.cos(angle2) * scaledRadius);
            float y2 = center.y + (float) (Math.sin(angle2) * scaledRadius);

            drawThickLine(context, x1, y1, x2, y2, scaledStroke, color);
        }
    }

    private void drawThickLine(DrawContext context, float x1, float y1, float x2, float y2, float thickness,
            Color color) {

        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);

        if (len < 0.001f)
            return;

        float perpX = -dy / len * (thickness / 2.0f);
        float perpY = dx / len * (thickness / 2.0f);

        int p1x = (int) (x1 + perpX);
        int p1y = (int) (y1 + perpY);
        int p2x = (int) (x1 - perpX);
        int p2y = (int) (y1 - perpY);
        int p3x = (int) (x2 - perpX);
        int p3y = (int) (y2 - perpY);
        int p4x = (int) (x2 + perpX);
        int p4y = (int) (y2 + perpY);

        context.fill(Math.min(p1x, Math.min(p2x, p3x)),
                Math.min(p1y, Math.min(p2y, p3y)),
                Math.max(p1x, Math.max(p2x, Math.max(p3x, p4x))),
                Math.max(p1y, Math.max(p2y, Math.max(p3y, p4y))),
                color.getRGB());
    }

    @Override
    public void drawCenteredText(DrawContext context, String text, float x, float y, float fontSize, Color color,
            boolean shadow) {
        if (context == null)
            return;

        float width = getTextWidth(text, fontSize);
        float heightOffset = getVisualTextHeight(fontSize) / 2.0f;

        drawText(context, text, x - (width / 2.0f), y - heightOffset, fontSize, color, shadow);
    }

    @Override
    public float getTextWidth(String text, float fontSize) {
        text = VString.decode(text);
        if (text.isEmpty()) {
            return 0.0f;
        }
        Identifier selectedFont = getEffectiveFontId();
        if (shouldPreferMsdf(selectedFont)) {
            float scaledFont = getTextScale(fontSize) * getClientFontScale();
            float msdfWidth = MSDFFontRenderer.getTextWidth(selectedFont, text);
            if (msdfWidth > 0.0f) {
                return msdfWidth * scaledFont;
            }
        }
        float scaledFont = normalizeFontScale(getTextScale(fontSize) * getClientFontScale());
        return getCustomFontTextWidth(text) * scaledFont;
    }

    @Override
    public float getTextHeight(float fontSize) {
        Identifier selectedFont = getEffectiveFontId();
        if (shouldPreferMsdf(selectedFont)) {
            float scaledFont = getTextScale(fontSize) * getClientFontScale();
            float msdfHeight = MSDFFontRenderer.getTextHeight(selectedFont);
            if (msdfHeight > 0.0f) {
                return msdfHeight * scaledFont;
            }
        }
        float scaledFont = normalizeFontScale(getTextScale(fontSize) * getClientFontScale());
        return getCustomFontTextHeight() * scaledFont;
    }

    @Override
    public float getVisualTextHeight(float fontSize) {
        Identifier selectedFont = getEffectiveFontId();
        if (shouldPreferMsdf(selectedFont)) {
            float scaledFont = getTextScale(fontSize) * getClientFontScale();
            float visualHeight = MSDFFontRenderer.getVisualTextHeight(selectedFont);
            if (visualHeight > 0.0f) {
                return visualHeight * scaledFont;
            }
        }
        

        float scaledFont = normalizeFontScale(getTextScale(fontSize) * getClientFontScale());
        return getCustomFontTextHeight() * scaledFont;
    }

    @Override
    public void setFontBlur(float blur) {
    }

    @Override
    public void drawTexture(DrawContext context, int nvgImageId, float x, float y, float width, float height,
                            float u, float v, float rW, float rH, float tW, float tH) {
        drawTextureInternal(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH, null, 0.0f);
    }

    @Override
    public void drawTextureStyled(DrawContext context, int nvgImageId, float x, float y, float width, float height,
                                  float u, float v, float rW, float rH, float tW, float tH, GuiShaderStyle style) {
        drawTextureInternal(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH, style, 0.0f);
    }

    private TextureSetup buildTextureSetup(Identifier textureId) {
        try {
            AbstractTexture abstractTexture = MinecraftClient.getInstance()
                    .getTextureManager()
                    .getTexture(textureId);

            GpuSampler linearSampler = RenderSystem.getSamplerCache().get(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    false
            );

            return TextureSetup.of(
                    abstractTexture.getGlTextureView(),
                    linearSampler
            );
        } catch (Exception e) {
            return TextureSetup.empty();
        }
    }

    private void drawTextureInternal(DrawContext context, int nvgImageId, float x, float y, float width, float height,
                                     float u, float v, float rW, float rH, float tW, float tH, GuiShaderStyle style,
                                     float cornerRadius) {
        drawTextureInternalArgb(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH, style, cornerRadius, 0xFFFFFFFF);
    }

    private void drawTextureInternalArgb(DrawContext context, int nvgImageId, float x, float y, float width, float height,
                                         float u, float v, float rW, float rH, float tW, float tH, GuiShaderStyle style,
                                         float cornerRadius, int argbTint) {
        if (context == null) return;

        Identifier texture = textureMap.get(nvgImageId);
        if (texture == null) return;

        RenderPipeline imagePipeline = shouldUseCustomPipelines()
                ? getTexturePipeline(style)
                : null;

        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);

        int drawX = (int) Math.floor(Math.min(p1.x, p2.x));
        int drawY = (int) Math.floor(Math.min(p1.y, p2.y));
        int drawX2 = (int) Math.ceil(Math.max(p1.x, p2.x));
        int drawY2 = (int) Math.ceil(Math.max(p1.y, p2.y));
        int drawW = drawX2 - drawX;
        int drawH = drawY2 - drawY;

        if (drawW <= 0 || drawH <= 0) return;

        float u0 = u / tW;
        float v0 = v / tH;
        float u1 = (u + rW) / tW;
        float v1 = (v + rH) / tH;
        float packedU0 = u0;
        float packedU1 = u1;
        if (cornerRadius > 0.5f) {
            packedU0 = packUvXWithRadius(u0, cornerRadius);
            packedU1 = packUvXWithRadius(u1, cornerRadius);
        }

        if (imagePipeline != null) {
            TextureSetup setup = buildTextureSetup(texture);
            context.state.addSimpleElement(new TexturedQuadGuiElementRenderState(
                    imagePipeline,
                    setup,
                    new Matrix3x2f(context.getMatrices()),
                    drawX, drawY, drawX + drawW, drawY + drawH,
                    packedU0, packedU1, v0, v1,
                    argbTint,
                    context.scissorStack.peekLast()));
        } else {
            int regionW = Math.max(1, Math.round(rW));
            int regionH = Math.max(1, Math.round(rH));
            int textureW = Math.max(regionW, Math.round(tW));
            int textureH = Math.max(regionH, Math.round(tH));

            context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                    drawX, drawY, u, v, drawW, drawH, regionW, regionH, textureW, textureH);
        }
    }

    @Override
    public void drawTextureColored(DrawContext context, int nvgImageId, float x, float y, float width, float height,
            float u, float v, float rW, float rH, float tW, float tH, java.awt.Color tint) {
        int argb = tint != null ? tint.getRGB() : 0xFFFFFFFF;
        drawTextureInternalArgb(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH, null, 0.0f, argb);
    }
    @Override
    public void drawTextureRounded(DrawContext context, int nvgImageId, float x, float y, float width, float height,
            float u, float v, float rW, float rH, float tW, float tH, float radius) {
        drawTextureInternal(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH, null, radius);
    }

    @Override
    public int createImageFromTexture(int textureId, int width, int height) {
        return -1;
    }

    @Override
    public void deleteImage(int imageId) {
        textureMap.remove(imageId);
    }

    @Override
    public int createImageFromFile(String resourcePath) {
        if (resourcePath == null)
            return -1;

        String cleanPath = resourcePath;
        if (cleanPath.startsWith("/"))
            cleanPath = cleanPath.substring(1);

        if (cleanPath.startsWith("assets/")) {
            String namespacedPath = cleanPath.substring("assets/".length());
            int slashIndex = namespacedPath.indexOf("/");
            if (slashIndex > 0 && slashIndex < namespacedPath.length() - 1) {
                String namespace = namespacedPath.substring(0, slashIndex);
                String path = namespacedPath.substring(slashIndex + 1);
                Identifier id = tryParseIdentifier(namespace, path);
                if (id != null) {
                    int newId = nextTextureId++;
                    textureMap.put(newId, id);
                    return newId;
                }
            }
        }

        if (cleanPath.contains(":")) {
            Identifier id = tryParseIdentifier(cleanPath);
            if (id != null) {
                int newId = nextTextureId++;
                textureMap.put(newId, id);
                return newId;
            }
        }

        return -1;
    }

    private Identifier tryParseIdentifier(String value) {
        try {
            return Identifier.of(value);
        } catch (Exception ignored) {
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.equals(value)) {
            try {
                return Identifier.of(lower);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private Identifier tryParseIdentifier(String namespace, String path) {
        try {
            return Identifier.of(namespace, path);
        } catch (Exception ignored) {
        }

        String lowerNamespace = namespace.toLowerCase(Locale.ROOT);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (!lowerNamespace.equals(namespace) || !lowerPath.equals(path)) {
            try {
                return Identifier.of(lowerNamespace, lowerPath);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    @Override
    public void scissor(DrawContext context, float x, float y, float width, float height) {
        Vector4f p1 = new Vector4f(x, y, 0, 1).mul(currentMatrix);
        Vector4f p2 = new Vector4f(x + width, y + height, 0, 1).mul(currentMatrix);
        int sx0 = (int) Math.floor(Math.min(p1.x, p2.x));
        int sy0 = (int) Math.floor(Math.min(p1.y, p2.y));
        int sx1 = (int) Math.ceil(Math.max(p1.x, p2.x));
        int sy1 = (int) Math.ceil(Math.max(p1.y, p2.y));
        if (sx1 <= sx0 || sy1 <= sy0) {
            return;
        }

        if (context != null) {
            activeContext = context;
            if (context.scissorStack.peekLast() != null) {
                context.disableScissor();
            }
            context.enableScissor(sx0, sy0, sx1, sy1);
        }
    }

    @Override
    public void scissor(float x, float y, float width, float height) {
        scissor(null, x, y, width, height);
    }

    @Override
    public void resetScissor() {
        if (activeContext != null && activeContext.scissorStack.peekLast() != null) {
            activeContext.disableScissor();
        }
    }

    private float snapToPixel(float value, float scale) {
        if (scale <= 0.0f) {
            return value;
        }
        return Math.round(value * scale) / scale;
    }

    private Color interpolate(Color start, Color end, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        int r = (int) (start.getRed() + (end.getRed() - start.getRed()) * clamped);
        int g = (int) (start.getGreen() + (end.getGreen() - start.getGreen()) * clamped);
        int b = (int) (start.getBlue() + (end.getBlue() - start.getBlue()) * clamped);
        int a = (int) (start.getAlpha() + (end.getAlpha() - start.getAlpha()) * clamped);
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)),
                Math.max(0, Math.min(255, a)));
    }

    private float normalizeFontScale(float scale) {
        if (scale <= 0.0f) {
            return 1.0f;
        }
        float snapped = Math.round(scale * 32.0f) / 32.0f;
        if (Math.abs(snapped - 1.0f) < 0.01f) {
            return 1.0f;
        }
        return snapped;
    }

    private float getClientFontScale() {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        return cf != null ? cf.clientFontScale() : 1.0f;
    }

    private boolean useClientShadow(boolean shadow) {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        return cf != null ? cf.clientShadowFor(shadow) : shadow;
    }

    private boolean useGlobalShadow(boolean shadow) {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        return cf != null ? cf.globalShadowFor(shadow) : shadow;
    }

    private Identifier getSelectedFontId() {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        return cf != null ? cf.activeFontId() : null;
    }

    private Identifier getEffectiveFontId() {
        Identifier selected = getSelectedFontId();
        if (selected != null) {
            return selected;
        }

        // MSDF text isn't a Vulkan-only capability - it renders fine on OpenGL too, same as the
        // gpuui/HUD pipeline's own text. Gating the default font behind "Vulkan or nothing" meant any
        // caller still on this legacy IApexxRenderer path (Notifications, ...) silently fell back to
        // vanilla's blocky bitmap font for every user who hadn't manually picked a custom font - which
        // is nearly everyone, since OpenGL is the default backend.
        return DEFAULT_MSDF_FONT;
    }

    private boolean shouldPreferMsdf(Identifier fontId) {
        if (fontId == null) {
            return false;
        }

        if (RenderBackendDetector.isVulkanBackend()) {
            return true;
        }

        return isMsdfEnabled();
    }

    private boolean isMsdfEnabled() {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        // CustomFontProvider has no implementation anywhere in the codebase (dead hook). Its absence
        // must default to MSDF ON, not off - treating "no hook registered" as "disabled" silently
        // forced every legacy IApexxRenderer caller back to vanilla's blocky bitmap font on OpenGL.
        return cf == null || cf.msdfEnabled();
    }

    private float getCustomFontTextWidth(String text) {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        return cf != null ? cf.measureTextWidth(tr, text) : tr.getWidth(text);
    }

    private float getCustomFontTextHeight() {
        CustomFontProvider cf = ModuleAccess.getHook(CustomFontProvider.class);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        return cf != null ? cf.measureTextHeight(tr) : tr.fontHeight;
    }

    private float getTextScale(float fontSize) {
        return fontSize / (getEffectiveFontId() != null ? 10.0f : 8.0f);
    }
}
