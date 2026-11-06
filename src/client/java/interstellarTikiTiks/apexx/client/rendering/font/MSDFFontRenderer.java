package interstellarTikiTiks.apexx.client.rendering.font;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.shader.CoreShaderManager;
import interstellarTikiTiks.apexx.client.util.VString;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.msdfgen.MSDFGen;
import org.lwjgl.util.msdfgen.MSDFGenBitmap;
import org.lwjgl.util.msdfgen.MSDFGenMultichannelConfig;
import org.lwjgl.util.msdfgen.MSDFGenTransform;
import org.lwjgl.util.msdfgen.MSDFGenVector2;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MSDFFontRenderer {
    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR = 126;
    private static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1;
    private static final int COLUMNS = 16;
    

    

    

    

    

    private static final int BASE_FONT_SIZE = 96;
    private static final int PADDING = 28;
    private static final int SDF_SPREAD = 20;
    private static final float TARGET_LINE_HEIGHT = 9.0f;
    private static final double EDGE_COLORING_ANGLE = 3.0d;

    private static final Identifier MSDF_PIPELINE_ID = Identifier.of(Apexx.MOD_ID, "msdf_text");
    private static final long FAILURE_RETRY_DELAY_MS = 3000L;
    private static final Map<Identifier, FontAtlas> ATLASES = new ConcurrentHashMap<>();
    private static final Set<Identifier> FAILED_ATLASES = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> BUILDING = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, PendingAtlas> PENDING = new ConcurrentHashMap<>();
    private static final Map<Identifier, Long> RETRY_AFTER = new ConcurrentHashMap<>();

    private static final ExecutorService ATLAS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apexx-msdf-atlas-builder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private MSDFFontRenderer() {
    }

    public static void preloadFont(Identifier fontId) {
        getAtlas(fontId);
    }

    public static boolean drawText(DrawContext context, Identifier fontId, String text, float x, float y, float scale,
                                   int argb, boolean shadow) {
        if (context == null || fontId == null || text == null || text.isEmpty()) {
            return false;
        }
        text = VString.decode(text);
        if (text.isEmpty()) {
            return false;
        }

        FontAtlas atlas = getAtlas(fontId);
        if (atlas == null) {
            // MSDF unavailable (msdfgen native missing/failed, or the atlas is still building) —
            // fall back to Minecraft's own font so text is NEVER invisible. Direct callers such as
            // the login screen and ApexxButton rely on this; without it their text vanishes on any
            // machine where the atlas can't be produced.
            return drawVanillaFallback(context, text, x, y, scale, argb, shadow);
        }

        if (msdfPipeline() == null) {
            // Pipeline missing or failed to compile: raw atlas drawing would show the distance field.
            return drawVanillaFallback(context, text, x, y, scale, argb, shadow);
        }

        if (shadow) {
            float shadowOffset = Math.max(1.0f, scale * 0.75f);
            drawInternal(context, atlas, text, x + shadowOffset, y + shadowOffset, scale, toShadowColor(argb));
        }

        drawInternal(context, atlas, text, x, y, scale, argb);
        return true;
    }

    public static float getTextWidth(Identifier fontId, String text) {
        if (fontId == null || text == null || text.isEmpty()) {
            return 0.0f;
        }
        text = VString.decode(text);
        if (text.isEmpty()) {
            return 0.0f;
        }

        FontAtlas atlas = getAtlas(fontId);
        if (atlas == null) {
            // Match the vanilla-font fallback used by drawText so centering/layout stays correct.
            return vanillaWidth(text);
        }

        float maxWidth = 0.0f;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }

            maxWidth = Math.max(maxWidth, measureLine(atlas, text, lineStart, lineEnd).width());

            if (lineEnd == text.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return maxWidth * atlas.metricScale;
    }

    public static float getTextHeight(Identifier fontId) {
        FontAtlas atlas = getAtlas(fontId);
        if (atlas == null) {
            return 9.0f;
        }
        

        

        return atlas.lineHeight * atlas.metricScale;
    }

    



    public static float getVisualTextHeight(Identifier fontId) {
        FontAtlas atlas = getAtlas(fontId);
        if (atlas == null) {
            return 7.5f;
        }
        return atlas.visualHeight() * atlas.metricScale;
    }

    // ---- vanilla-font fallback ---------------------------------------------------------------
    // Guarantees text is visible even when the MSDF atlas can't be produced (missing/broken
    // msdfgen native, or still building). Draws with Minecraft's own TextRenderer, scaled to
    // roughly match the requested MSDF size (both use a ~9px line height), so layout stays close.

    private static boolean drawVanillaFallback(DrawContext context, String text, float x, float y,
                                               float scale, int argb, boolean shadow) {
        try {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            if (tr == null) {
                return false;
            }
            float s = scale <= 0.0f ? 1.0f : scale;
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(x, y);
            context.getMatrices().scale(s, s);
            int lineY = 0;
            for (String line : text.split("\n", -1)) {
                context.drawText(tr, line, 0, lineY, argb, shadow);
                lineY += 9;
            }
            context.getMatrices().popMatrix();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float vanillaWidth(String text) {
        try {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            if (tr == null) {
                return text.length() * 6.0f;
            }
            float max = 0.0f;
            for (String line : text.split("\n", -1)) {
                max = Math.max(max, tr.getWidth(line));
            }
            return max;
        } catch (Throwable t) {
            return text.length() * 6.0f;
        }
    }

    private static FontAtlas getAtlas(Identifier fontId) {
        // The atlas is built with java.awt fonts and the msdfgen native, neither of which an Android
        // launcher's JRE reliably has; callers already fall back to Minecraft's own font on null.
        if (!interstellarTikiTiks.apexx.client.util.PlatformPreset.msdfFonts()) return null;
        uploadPendingAtlas(fontId);

        FontAtlas ready = ATLASES.get(fontId);
        if (ready != null) {
            return ready;
        }

        if (FAILED_ATLASES.contains(fontId)) {
            long retryAfter = RETRY_AFTER.getOrDefault(fontId, 0L);
            if (System.currentTimeMillis() < retryAfter) {
                return null;
            }
            FAILED_ATLASES.remove(fontId);
            RETRY_AFTER.remove(fontId);
        }

        if (BUILDING.add(fontId)) {
            ATLAS_EXECUTOR.submit(() -> {
                try {
                    PendingAtlas pending = buildAtlasOffThread(fontId);
                    if (pending == null) {
                        if (!ATLASES.containsKey(fontId)) {
                            markFailed(fontId);
                        }
                    } else if (ATLASES.containsKey(fontId) || FAILED_ATLASES.contains(fontId)) {
                        discardPendingAtlas(pending);
                    } else {
                        PENDING.put(fontId, pending);
                    }
                } catch (LinkageError missingNative) {
                    // The msdfgen native (or a class it needs) is not available on this platform: it will not
                    // appear later, so stop trying for the rest of the run instead of rebuilding every frame.
                    FAILED_ATLASES.add(fontId);
                    RETRY_AFTER.put(fontId, Long.MAX_VALUE);
                } catch (Throwable e) {
                    markFailed(fontId);
                } finally {
                    BUILDING.remove(fontId);
                }
            });
        }

        return null;
    }

    private static void uploadPendingAtlas(Identifier fontId) {
        PendingAtlas pending = PENDING.remove(fontId);
        if (pending == null) {
            return;
        }

        try {
            if (ATLASES.containsKey(fontId)) {
                discardPendingAtlas(pending);
                return;
            }
            registerAtlas(fontId, pending);
        } catch (Throwable e) {
            try {
                discardPendingAtlas(pending);
            } catch (Throwable ignored) {
                // Nothing more to release.
            }
            markFailed(fontId);
        }
    }

    private static FontAtlas registerAtlas(Identifier fontId, PendingAtlas pending) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(
                () -> "apexx_msdf_" + fontId.getPath(), pending.nativeImage);
        texture.upload();

        Identifier textureId = Identifier.of(Apexx.MOD_ID, "generated/msdf_" + fontId.getPath());
        MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);

        FontAtlas atlas = new FontAtlas(
                textureId,
                pending.atlasWidth,
                pending.atlasHeight,
                pending.lineHeight,
                pending.ascent,
                pending.spaceAdvance,
                pending.metricScale,
                pending.visualTop,
                pending.visualBottom,
                pending.glyphs,
                pending.kerningTable);
        ATLASES.put(fontId, atlas);
        FAILED_ATLASES.remove(fontId);
        RETRY_AFTER.remove(fontId);
        return atlas;
    }

    private static void discardPendingAtlas(PendingAtlas pending) {
        if (pending == null || pending.nativeImage == null) {
            return;
        }
        try {
            pending.nativeImage.close();
        } catch (Exception ignored) {
        }
    }

    private static void markFailed(Identifier fontId) {
        FAILED_ATLASES.add(fontId);
        RETRY_AFTER.put(fontId, System.currentTimeMillis() + FAILURE_RETRY_DELAY_MS);
    }

    private static boolean msdfPipelineRegistered;

    private static RenderPipeline msdfPipeline() {
        CoreShaderManager mgr = CoreShaderManager.getInstance();
        if (!msdfPipelineRegistered) {
            msdfPipelineRegistered = true;
            try {
                mgr.registerMsdfTextShader(MSDF_PIPELINE_ID);
            } catch (Throwable t) {
                return null;
            }
        }
        return mgr.getPipeline(MSDF_PIPELINE_ID);
    }

    private static void drawInternal(DrawContext context, FontAtlas atlas, String text, float x, float y, float scale, int color) {
        RenderPipeline pipeline = msdfPipeline();
        if (pipeline == null) {
            return;
        }

        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(atlas.textureId);
        if (texture == null || texture.getGlTextureView() == null) {
            return;
        }

        GpuSampler linearSampler = RenderSystem.getSamplerCache().get(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        TextureSetup textureSetup = TextureSetup.of(texture.getGlTextureView(), linearSampler);
        float renderScale = scale * atlas.metricScale;
        float lineHeightScaled = atlas.lineHeight * renderScale;
        

        float baselineOffset = -atlas.visualTop * renderScale;
        

        Matrix3x2f guiMat = new Matrix3x2f(context.getMatrices());

        int lineStart = 0;
        int lineIndex = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }

            LineMetrics lineMetrics = measureLine(atlas, text, lineStart, lineEnd);
            float lineX = x + (lineMetrics.xOffset() * renderScale);
            float baselineY = y + baselineOffset + (lineIndex * lineHeightScaled);
            drawLine(context, atlas, text, lineStart, lineEnd, lineX, baselineY, renderScale, color, pipeline, textureSetup, guiMat);

            if (lineEnd == text.length()) {
                break;
            }
            lineStart = lineEnd + 1;
            lineIndex++;
        }
    }

    private static void drawLine(DrawContext context, FontAtlas atlas, String text, int start, int end,
                                 float x, float baselineY, float renderScale, int color,
                                 RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f guiMat) {
        float cursorX = x;
        char previousChar = 0;
        boolean hasPrevious = false;

        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            Glyph glyph = atlas.getGlyph(c);
            if (glyph == null) {
                cursorX += atlas.spaceAdvance * renderScale;
                hasPrevious = false;
                continue;
            }

            if (hasPrevious) {
                cursorX += atlas.getKerning(previousChar, c) * renderScale;
            }

            float quadX = cursorX + (glyph.offsetX * renderScale) - (PADDING * renderScale);
            float quadY = baselineY + (glyph.offsetY * renderScale) - (PADDING * renderScale);
            float quadW = (glyph.width + (PADDING * 2)) * renderScale;
            float quadH = (glyph.height + (PADDING * 2)) * renderScale;

            int x1 = (int) Math.floor(quadX);
            int y1 = (int) Math.floor(quadY);
            int x2 = (int) Math.ceil(quadX + quadW);
            int y2 = (int) Math.ceil(quadY + quadH);

            if (glyph.width > 0 && glyph.height > 0 && x2 > x1 && y2 > y1) {
                

                

                

                

                float atlasX0 = (glyph.u - PADDING) + (x1 - quadX) / renderScale;
                float atlasX1 = (glyph.u + glyph.width + PADDING) + (x2 - (quadX + quadW)) / renderScale;
                float atlasY0 = (glyph.v - PADDING) + (y1 - quadY) / renderScale;
                float atlasY1 = (glyph.v + glyph.height + PADDING) + (y2 - (quadY + quadH)) / renderScale;
                float u1 = Math.max(0.0f, atlasX0 / (float) atlas.width);
                float u2 = Math.min(1.0f, atlasX1 / (float) atlas.width);
                float v1 = Math.max(0.0f, atlasY0 / (float) atlas.height);
                float v2 = Math.min(1.0f, atlasY1 / (float) atlas.height);

                context.state.addSimpleElement(new TexturedQuadGuiElementRenderState(
                        pipeline,
                        textureSetup,
                        guiMat,
                        x1, y1, x2, y2,
                        u1, u2, v1, v2,
                        color,
                        context.scissorStack.peekLast()
                ));
            }

            cursorX += glyph.advance * renderScale;
            previousChar = atlas.normalizeKerningChar(c);
            hasPrevious = true;
        }
    }

    private static PendingAtlas buildAtlasOffThread(Identifier fontId) throws IOException, FontFormatException {
        Identifier ttfResource = resolveTtfResource(fontId);
        if (ttfResource == null) {
            return null;
        }

        try (InputStream stream = openFontStream(ttfResource)) {
            Font font = enableLayoutFeatures(Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont((float) BASE_FONT_SIZE));
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probeGraphics = probe.createGraphics();
            BufferedImage atlasProbe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D atlasProbeGraphics = atlasProbe.createGraphics();
            try {
                configureMeasurementGraphics(probeGraphics);
                probeGraphics.setFont(font);
                FontRenderContext layoutRenderContext = probeGraphics.getFontRenderContext();
                FontMetrics metrics = probeGraphics.getFontMetrics();
                int ascent = metrics.getAscent();
                int lineHeight = Math.max(1, metrics.getHeight());

                configureAtlasGraphics(atlasProbeGraphics);
                atlasProbeGraphics.setFont(font);
                FontRenderContext atlasRenderContext = atlasProbeGraphics.getFontRenderContext();

                GlyphSpec[] specs = new GlyphSpec[CHAR_COUNT];
                int maxGlyphWidth = 1;
                int maxGlyphHeight = 1;
                for (int c = FIRST_CHAR; c <= LAST_CHAR; c++) {
                    GlyphSpec spec = buildGlyphSpec(font, layoutRenderContext, atlasRenderContext, metrics, (char) c);
                    specs[c - FIRST_CHAR] = spec;
                    if (spec.width > maxGlyphWidth) {
                        maxGlyphWidth = spec.width;
                    }
                    if (spec.height > maxGlyphHeight) {
                        maxGlyphHeight = spec.height;
                    }
                }
                float spaceAdvance = Math.max(1.0f, specs[' ' - FIRST_CHAR].advance);

                int cellWidth = maxGlyphWidth + (PADDING * 2);
                int cellHeight = maxGlyphHeight + (PADDING * 2);
                int rows = (CHAR_COUNT + COLUMNS - 1) / COLUMNS;
                int atlasWidth = nextPowerOfTwo(cellWidth * COLUMNS);
                int atlasHeight = nextPowerOfTwo(cellHeight * rows);
                NativeImage nativeImage = new NativeImage(atlasWidth, atlasHeight, true);

                Glyph[] glyphs = new Glyph[CHAR_COUNT];
                for (int index = 0; index < CHAR_COUNT; index++) {
                    char ch = (char) (FIRST_CHAR + index);
                    int row = index / COLUMNS;
                    int col = index % COLUMNS;
                    int cellX = col * cellWidth;
                    int cellY = row * cellHeight;

                    GlyphSpec spec = specs[index];
                    glyphs[index] = new Glyph(
                            cellX + PADDING,
                            cellY + PADDING,
                            spec.width,
                            spec.height,
                            spec.advance,
                            spec.boundsX,
                            spec.boundsY);

                    if (spec.width <= 0 || spec.height <= 0) {
                        continue;
                    }

                    float drawX = PADDING - spec.boundsX;
                    float drawY = PADDING - spec.boundsY;
                    GlyphVector vector = font.createGlyphVector(atlasRenderContext, new char[] { ch });
                    Shape outline = vector.getOutline(drawX, drawY);
                    writeMsdfGlyph(nativeImage, outline, cellX, cellY,
                            spec.width + (PADDING * 2),
                            spec.height + (PADDING * 2));
                }

                float metricScale = TARGET_LINE_HEIGHT / (float) lineHeight;
                int visualTop = 0;
                int visualBottom = Math.max(1, lineHeight);
                boolean foundVisualGlyph = false;
                for (GlyphSpec spec : specs) {
                    if (spec == null || spec.height <= 0) {
                        continue;
                    }

                    int glyphTop = spec.boundsY;
                    int glyphBottom = spec.boundsY + spec.height;
                    if (!foundVisualGlyph) {
                        visualTop = glyphTop;
                        visualBottom = glyphBottom;
                        foundVisualGlyph = true;
                    } else {
                        visualTop = Math.min(visualTop, glyphTop);
                        visualBottom = Math.max(visualBottom, glyphBottom);
                    }
                }
                float[] kerningTable = buildKerningTable(font, layoutRenderContext, specs);
                return new PendingAtlas(nativeImage, atlasWidth, atlasHeight, lineHeight, ascent,
                        spaceAdvance, metricScale, visualTop, visualBottom, glyphs, kerningTable);
            } finally {
                probeGraphics.dispose();
                atlasProbeGraphics.dispose();
            }
        }
    }

    private static void writeMsdfGlyph(NativeImage nativeImage, Shape outline, int atlasX, int atlasY,
                                       int bitmapWidth, int bitmapHeight) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer shapePtr = stack.mallocPointer(1);
            checkStatus(MSDFGen.msdf_shape_alloc(shapePtr), "allocate msdf shape");
            long shapeHandle = shapePtr.get(0);

            try {
                boolean hasEdges = appendOutlineToShape(shapeHandle, outline, bitmapHeight, stack);
                if (!hasEdges) {
                    return;
                }

                checkStatus(MSDFGen.msdf_shape_normalize(shapeHandle), "normalize msdf shape");
                checkStatus(MSDFGen.msdf_shape_orient_contours(shapeHandle), "orient msdf contours");
                checkStatus(MSDFGen.msdf_shape_edge_colors_simple(shapeHandle, EDGE_COLORING_ANGLE), "assign msdf edge colors");

                MSDFGenBitmap bitmap = MSDFGenBitmap.calloc(stack);
                checkStatus(MSDFGen.msdf_bitmap_alloc(MSDFGen.MSDF_BITMAP_TYPE_MTSDF, bitmapWidth, bitmapHeight, bitmap),
                        "allocate msdf bitmap");
                try {
                    MSDFGenTransform transform = MSDFGenTransform.calloc(stack);
                    transform.scale().set(1.0d, 1.0d);
                    transform.translation().set(0.0d, 0.0d);
                    transform.distance_mapping().set(-SDF_SPREAD, SDF_SPREAD);

                    

                    

                    

                    

                    MSDFGenMultichannelConfig config = MSDFGenMultichannelConfig.calloc(stack);
                    config.overlap_support(1);

                    checkStatus(MSDFGen.msdf_generate_mtsdf_with_config(bitmap, shapeHandle, transform, config), "generate mtsdf glyph");
                    copyGeneratedBitmap(nativeImage, atlasX, atlasY, bitmap);
                } finally {
                    MSDFGen.msdf_bitmap_free(bitmap);
                }
            } finally {
                MSDFGen.msdf_shape_free(shapeHandle);
            }
        }
    }

    private static boolean appendOutlineToShape(long shapeHandle, Shape outline, int bitmapHeight, MemoryStack stack) {
        PathIterator iterator = outline.getPathIterator(null);
        double[] coords = new double[6];
        PointerBuffer contourPtr = stack.mallocPointer(1);
        PointerBuffer segmentPtr = stack.mallocPointer(1);
        MSDFGenVector2 point0 = MSDFGenVector2.malloc(stack);
        MSDFGenVector2 point1 = MSDFGenVector2.malloc(stack);
        MSDFGenVector2 point2 = MSDFGenVector2.malloc(stack);
        MSDFGenVector2 point3 = MSDFGenVector2.malloc(stack);

        long contourHandle = 0L;
        boolean contourHasEdges = false;
        boolean shapeHasEdges = false;
        double startX = 0.0d;
        double startY = 0.0d;
        double lastX = 0.0d;
        double lastY = 0.0d;

        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    if (contourHandle != 0L && contourHasEdges) {
                        addClosingEdgeIfNeeded(contourHandle, lastX, lastY, startX, startY, bitmapHeight, segmentPtr, point0, point1);
                    }
                    contourHandle = 0L;
                    contourHasEdges = false;
                    startX = coords[0];
                    startY = coords[1];
                    lastX = startX;
                    lastY = startY;
                }
                case PathIterator.SEG_LINETO -> {
                    double x1 = coords[0];
                    double y1 = coords[1];
                    contourHandle = ensureContour(shapeHandle, contourHandle, contourPtr);
                    addLinearSegment(contourHandle, lastX, lastY, x1, y1, bitmapHeight, segmentPtr, point0, point1);
                    contourHasEdges = true;
                    shapeHasEdges = true;
                    lastX = x1;
                    lastY = y1;
                }
                case PathIterator.SEG_QUADTO -> {
                    double cx = coords[0];
                    double cy = coords[1];
                    double x1 = coords[2];
                    double y1 = coords[3];
                    contourHandle = ensureContour(shapeHandle, contourHandle, contourPtr);
                    addQuadraticSegment(contourHandle, lastX, lastY, cx, cy, x1, y1,
                            bitmapHeight, segmentPtr, point0, point1, point2);
                    contourHasEdges = true;
                    shapeHasEdges = true;
                    lastX = x1;
                    lastY = y1;
                }
                case PathIterator.SEG_CUBICTO -> {
                    double cx1 = coords[0];
                    double cy1 = coords[1];
                    double cx2 = coords[2];
                    double cy2 = coords[3];
                    double x1 = coords[4];
                    double y1 = coords[5];
                    contourHandle = ensureContour(shapeHandle, contourHandle, contourPtr);
                    addCubicSegment(contourHandle, lastX, lastY, cx1, cy1, cx2, cy2, x1, y1,
                            bitmapHeight, segmentPtr, point0, point1, point2, point3);
                    contourHasEdges = true;
                    shapeHasEdges = true;
                    lastX = x1;
                    lastY = y1;
                }
                case PathIterator.SEG_CLOSE -> {
                    if (contourHandle != 0L && contourHasEdges) {
                        addClosingEdgeIfNeeded(contourHandle, lastX, lastY, startX, startY, bitmapHeight, segmentPtr, point0, point1);
                    }
                    contourHandle = 0L;
                    contourHasEdges = false;
                    lastX = startX;
                    lastY = startY;
                }
                default -> {
                }
            }
            iterator.next();
        }

        if (contourHandle != 0L && contourHasEdges) {
            addClosingEdgeIfNeeded(contourHandle, lastX, lastY, startX, startY, bitmapHeight, segmentPtr, point0, point1);
        }

        return shapeHasEdges;
    }

    private static long ensureContour(long shapeHandle, long contourHandle, PointerBuffer contourPtr) {
        if (contourHandle != 0L) {
            return contourHandle;
        }
        checkStatus(MSDFGen.msdf_shape_add_contour(shapeHandle, contourPtr), "add msdf contour");
        return contourPtr.get(0);
    }

    private static void addClosingEdgeIfNeeded(long contourHandle, double lastX, double lastY, double startX, double startY,
                                               int bitmapHeight, PointerBuffer segmentPtr,
                                               MSDFGenVector2 point0, MSDFGenVector2 point1) {
        if (Math.abs(lastX - startX) <= 0.0001d && Math.abs(lastY - startY) <= 0.0001d) {
            return;
        }
        addLinearSegment(contourHandle, lastX, lastY, startX, startY, bitmapHeight, segmentPtr, point0, point1);
    }

    private static void addLinearSegment(long contourHandle, double x0, double y0, double x1, double y1,
                                         int bitmapHeight, PointerBuffer segmentPtr,
                                         MSDFGenVector2 point0, MSDFGenVector2 point1) {
        long segmentHandle = allocateSegment(MSDFGen.MSDF_SEGMENT_TYPE_LINEAR, segmentPtr);
        boolean added = false;
        try {
            setPoint(point0, x0, y0, bitmapHeight);
            setPoint(point1, x1, y1, bitmapHeight);
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 0, point0), "set linear segment point 0");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 1, point1), "set linear segment point 1");
            checkStatus(MSDFGen.msdf_contour_add_edge(contourHandle, segmentHandle), "attach linear segment");
            added = true;
        } finally {
            if (!added) {
                MSDFGen.msdf_segment_free(segmentHandle);
            }
        }
    }

    private static void addQuadraticSegment(long contourHandle, double x0, double y0, double cx, double cy,
                                            double x1, double y1, int bitmapHeight, PointerBuffer segmentPtr,
                                            MSDFGenVector2 point0, MSDFGenVector2 point1, MSDFGenVector2 point2) {
        long segmentHandle = allocateSegment(MSDFGen.MSDF_SEGMENT_TYPE_QUADRATIC, segmentPtr);
        boolean added = false;
        try {
            setPoint(point0, x0, y0, bitmapHeight);
            setPoint(point1, cx, cy, bitmapHeight);
            setPoint(point2, x1, y1, bitmapHeight);
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 0, point0), "set quadratic segment point 0");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 1, point1), "set quadratic segment point 1");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 2, point2), "set quadratic segment point 2");
            checkStatus(MSDFGen.msdf_contour_add_edge(contourHandle, segmentHandle), "attach quadratic segment");
            added = true;
        } finally {
            if (!added) {
                MSDFGen.msdf_segment_free(segmentHandle);
            }
        }
    }

    private static void addCubicSegment(long contourHandle, double x0, double y0, double cx1, double cy1,
                                        double cx2, double cy2, double x1, double y1, int bitmapHeight,
                                        PointerBuffer segmentPtr, MSDFGenVector2 point0, MSDFGenVector2 point1,
                                        MSDFGenVector2 point2, MSDFGenVector2 point3) {
        long segmentHandle = allocateSegment(MSDFGen.MSDF_SEGMENT_TYPE_CUBIC, segmentPtr);
        boolean added = false;
        try {
            setPoint(point0, x0, y0, bitmapHeight);
            setPoint(point1, cx1, cy1, bitmapHeight);
            setPoint(point2, cx2, cy2, bitmapHeight);
            setPoint(point3, x1, y1, bitmapHeight);
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 0, point0), "set cubic segment point 0");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 1, point1), "set cubic segment point 1");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 2, point2), "set cubic segment point 2");
            checkStatus(MSDFGen.msdf_segment_set_point(segmentHandle, 3, point3), "set cubic segment point 3");
            checkStatus(MSDFGen.msdf_contour_add_edge(contourHandle, segmentHandle), "attach cubic segment");
            added = true;
        } finally {
            if (!added) {
                MSDFGen.msdf_segment_free(segmentHandle);
            }
        }
    }

    private static long allocateSegment(int type, PointerBuffer segmentPtr) {
        checkStatus(MSDFGen.msdf_segment_alloc(type, segmentPtr), "allocate msdf segment");
        return segmentPtr.get(0);
    }

    private static void setPoint(MSDFGenVector2 point, double x, double y, int bitmapHeight) {
        point.set(x, bitmapHeight - y);
    }

    private static void copyGeneratedBitmap(NativeImage nativeImage, int atlasX, int atlasY, MSDFGenBitmap bitmap) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pixelPtr = stack.mallocPointer(1);
            PointerBuffer sizePtr = stack.mallocPointer(1);
            IntBuffer channelCount = stack.mallocInt(1);

            checkStatus(MSDFGen.msdf_bitmap_get_pixels(bitmap, pixelPtr), "read msdf bitmap pixels");
            checkStatus(MSDFGen.msdf_bitmap_get_byte_size(bitmap, sizePtr), "read msdf bitmap size");
            checkStatus(MSDFGen.msdf_bitmap_get_channel_count(bitmap, channelCount), "read msdf bitmap channel count");

            long pixelAddress = pixelPtr.get(0);
            long byteSize = sizePtr.get(0);
            int channels = channelCount.get(0);
            if (pixelAddress == MemoryUtil.NULL || byteSize <= 0L || channels <= 0) {
                throw new IllegalStateException("msdfgen produced an empty bitmap");
            }

            int floatCount = Math.toIntExact(byteSize / Float.BYTES);
            FloatBuffer pixels = MemoryUtil.memFloatBuffer(pixelAddress, floatCount);
            int width = bitmap.width();
            int height = bitmap.height();
            for (int sourceY = 0; sourceY < height; sourceY++) {
                int targetY = atlasY + (height - 1 - sourceY);
                for (int x = 0; x < width; x++) {
                    int base = ((sourceY * width) + x) * channels;
                    int red = toByte(pixels.get(base));
                    int green = channels > 1 ? toByte(pixels.get(base + 1)) : red;
                    int blue = channels > 2 ? toByte(pixels.get(base + 2)) : green;
                    int alpha = channels > 3 ? toByte(pixels.get(base + 3)) : Math.max(red, Math.max(green, blue));
                    nativeImage.setColorArgb(atlasX + x, targetY,
                            (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
        }
    }

    private static int toByte(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return Math.max(0, Math.min(255, Math.round(clamped * 255.0f)));
    }

    private static void checkStatus(int status, String action) {
        if (status != MSDFGen.MSDF_SUCCESS) {
            throw new IllegalStateException(action + " failed with msdfgen status " + status);
        }
    }

    private static Font enableLayoutFeatures(Font baseFont) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        return baseFont.deriveFont(attributes);
    }

    private static GlyphSpec buildGlyphSpec(Font font, FontRenderContext layoutRenderContext,
                                            FontRenderContext atlasRenderContext, FontMetrics metrics, char ch) {
        GlyphVector layoutVector = font.createGlyphVector(layoutRenderContext, new char[] { ch });
        GlyphVector atlasVector = font.createGlyphVector(atlasRenderContext, new char[] { ch });
        Rectangle bounds = atlasVector.getPixelBounds(atlasRenderContext, 0.0f, 0.0f);
        float advance = (float) layoutVector.getGlyphMetrics(0).getAdvanceX();
        if (advance <= 0.0f) {
            advance = Math.max(0.0f, metrics.charWidth(ch));
        }

        int width = Math.max(0, bounds.width);
        int height = Math.max(0, bounds.height);
        int boundsX = width > 0 ? bounds.x : 0;
        int boundsY = height > 0 ? bounds.y : 0;
        return new GlyphSpec(width, height, boundsX, boundsY, advance);
    }

    private static float[] buildKerningTable(Font font, FontRenderContext fontRenderContext, GlyphSpec[] specs) {
        float[] kerningTable = new float[CHAR_COUNT * CHAR_COUNT];
        char[] pair = new char[2];

        for (int leftIndex = 0; leftIndex < CHAR_COUNT; leftIndex++) {
            pair[0] = (char) (FIRST_CHAR + leftIndex);
            for (int rightIndex = 0; rightIndex < CHAR_COUNT; rightIndex++) {
                pair[1] = (char) (FIRST_CHAR + rightIndex);
                GlyphVector pairVector = font.layoutGlyphVector(
                        fontRenderContext,
                        pair,
                        0,
                        pair.length,
                        Font.LAYOUT_LEFT_TO_RIGHT);
                float pairAdvance = (float) pairVector.getGlyphPosition(pairVector.getNumGlyphs()).getX();
                float adjustment = pairAdvance - specs[leftIndex].advance - specs[rightIndex].advance;
                if (Math.abs(adjustment) < 0.01f) {
                    adjustment = 0.0f;
                }
                kerningTable[(leftIndex * CHAR_COUNT) + rightIndex] = adjustment;
            }
        }

        return kerningTable;
    }

    private static InputStream openFontStream(Identifier resourceId) throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getResourceManager() != null) {
            java.util.Optional<Resource> optional = client.getResourceManager().getResource(resourceId);
            if (optional.isPresent()) {
                return optional.get().getInputStream();
            }
        }

        String classpathPath = "/assets/" + resourceId.getNamespace() + "/" + resourceId.getPath();
        InputStream classpathStream = MSDFFontRenderer.class.getResourceAsStream(classpathPath);
        if (classpathStream != null) {
            return classpathStream;
        }

        throw new IOException("Missing font resource " + resourceId);
    }

    private static void configureMeasurementGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void configureAtlasGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static Identifier resolveTtfResource(Identifier fontId) {
        String path = fontId.getPath();
        if ("sans".equals(path)) {
            return Identifier.of("dynamic_fps", "font/opensans-regular.ttf");
        }
        if ("semibold".equals(path)) {
            return Identifier.of("dynamic_fps", "font/semibold.ttf");
        }
        if ("apexx".equals(path) || "apexx".equals(path)) {
            return Identifier.of("dynamic_fps", "font/font.ttf");
        }
        return null;
    }

    private static int nextPowerOfTwo(int value) {
        int size = 1;
        while (size < value) {
            size <<= 1;
        }
        return size;
    }

    private static int toShadowColor(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int shadowAlpha = Math.max(0, Math.min(255, Math.round(alpha * 0.45f)));
        return shadowAlpha << 24;
    }

    private static LineMetrics measureLine(FontAtlas atlas, String text, int start, int end) {
        float penX = 0.0f;
        float minX = 0.0f;
        float maxX = 0.0f;
        char previousChar = 0;
        boolean hasPrevious = false;
        boolean hasGlyphInLine = false;

        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            Glyph glyph = atlas.getGlyph(c);
            if (glyph == null) {
                penX += atlas.spaceAdvance;
                hasPrevious = false;
                continue;
            }

            if (hasPrevious) {
                penX += atlas.getKerning(previousChar, c);
            }

            float glyphLeft = penX + glyph.offsetX;
            float glyphRight = glyphLeft + glyph.width;
            if (!hasGlyphInLine) {
                minX = glyphLeft;
                maxX = glyphRight;
                hasGlyphInLine = true;
            } else {
                minX = Math.min(minX, glyphLeft);
                maxX = Math.max(maxX, glyphRight);
            }

            penX += glyph.advance;
            previousChar = atlas.normalizeKerningChar(c);
            hasPrevious = true;
        }

        return new LineMetrics(minX, maxX, penX, hasGlyphInLine);
    }

    private record Glyph(int u, int v, int width, int height, float advance, int offsetX, int offsetY) {
    }

    private record GlyphSpec(int width, int height, int boundsX, int boundsY, float advance) {
    }

    private record PendingAtlas(NativeImage nativeImage, int atlasWidth, int atlasHeight, int lineHeight,
                                int ascent, float spaceAdvance, float metricScale, int visualTop, int visualBottom,
                                Glyph[] glyphs,
                                float[] kerningTable) {
    }

    private record FontAtlas(Identifier textureId, int width, int height, int lineHeight, int ascent,
                             float spaceAdvance, float metricScale, int visualTop, int visualBottom,
                             Glyph[] glyphs,
                             float[] kerningTable) {
        private Glyph getGlyph(char c) {
            

            if (c >= FIRST_CHAR && c <= LAST_CHAR) {
                return glyphs[c - FIRST_CHAR];
            }
            int fallback = '?' - FIRST_CHAR;
            return (fallback >= 0 && fallback < glyphs.length) ? glyphs[fallback] : null;
        }

        private char normalizeKerningChar(char c) {
            if (c >= FIRST_CHAR && c <= LAST_CHAR && glyphs[c - FIRST_CHAR] != null) {
                return c;
            }
            return '?';
        }

        private float getKerning(char previous, char current) {
            if (kerningTable == null || kerningTable.length == 0) {
                return 0.0f;
            }

            int leftIndex = normalizeKerningChar(previous) - FIRST_CHAR;
            int rightIndex = normalizeKerningChar(current) - FIRST_CHAR;
            if (leftIndex < 0 || leftIndex >= CHAR_COUNT || rightIndex < 0 || rightIndex >= CHAR_COUNT) {
                return 0.0f;
            }

            return kerningTable[(leftIndex * CHAR_COUNT) + rightIndex];
        }

        private float visualHeight() {
            return Math.max(1.0f, visualBottom - visualTop);
        }

    }

    private record LineMetrics(float minX, float maxX, float penX, boolean hasGlyphInLine) {
        private float width() {
            if (!hasGlyphInLine) {
                return Math.max(0.0f, penX);
            }
            return Math.max(maxX, penX) - Math.min(0.0f, minX);
        }

        private float xOffset() {
            if (!hasGlyphInLine) {
                return 0.0f;
            }
            return -Math.min(0.0f, minX);
        }
    }
}
