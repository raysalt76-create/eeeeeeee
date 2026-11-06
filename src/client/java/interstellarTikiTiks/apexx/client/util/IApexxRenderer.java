package interstellarTikiTiks.apexx.client.util;

import net.minecraft.client.gui.DrawContext;
import java.awt.Color;

public interface IApexxRenderer {
    void init();
    boolean beginFrame(float width, float height, float pixelRatio);
    void endFrame();

    void drawRect(DrawContext context, float x, float y, float width, float height, Color color);
    void drawRoundedRect(DrawContext context, float x, float y, float width, float height, float radius, Color color);
    default void drawRoundedRectStyled(DrawContext context, float x, float y, float width, float height, float radius,
            Color color, GuiShaderStyle style) {
        drawRoundedRect(context, x, y, width, height, radius, color);
    }
    void drawGlowingRect(DrawContext context, float x, float y, float width, float height, float radius, Color innerColor, Color outerColor, float glowSize);
    void drawRectOutline(DrawContext context, float x, float y, float width, float height, float strokeWidth, Color color);
    void drawRoundedRectOutline(DrawContext context, float x, float y, float width, float height, float radius, float strokeWidth, Color color);
    void drawRoundedRectGradient(DrawContext context, float x, float y, float width, float height, float radius, Color color1, Color color2, boolean vertical);
    default void drawRoundedRectGradientStyled(DrawContext context, float x, float y, float width, float height,
            float radius, Color color1, Color color2, boolean vertical, GuiShaderStyle style) {
        drawRoundedRectGradient(context, x, y, width, height, radius, color1, color2, vertical);
    }
    void drawCircle(DrawContext context, float x, float y, float radius, Color color);
    void drawArc(DrawContext context, float cx, float cy, float radius, float startAngle, float sweepAngle, float strokeWidth, Color color);
    void drawBlur(DrawContext context, float x, float y, float width, float height, float blurRadius);
    void rotate(float angle);

    void drawText(DrawContext context, String text, float x, float y, float fontSize, Color color, boolean shadow);
    void drawCenteredText(DrawContext context, String text, float x, float y, float fontSize, Color color, boolean shadow);

    void setFontBlur(float blur);

    float getTextWidth(String text, float fontSize);
    float getTextHeight(float fontSize);
    
    default float getVisualTextHeight(float fontSize) {
        return getTextHeight(fontSize);
    }

    void drawTexture(DrawContext context, int nvgImageId, float x, float y, float width, float height, float u, float v, float rW, float rH, float tW, float tH);
    default void drawTextureStyled(DrawContext context, int nvgImageId, float x, float y, float width, float height,
            float u, float v, float rW, float rH, float tW, float tH, GuiShaderStyle style) {
        drawTexture(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH);
    }
    
    default void drawTextureColored(DrawContext context, int nvgImageId, float x, float y, float width, float height,
            float u, float v, float rW, float rH, float tW, float tH, java.awt.Color tint) {
        drawTexture(context, nvgImageId, x, y, width, height, u, v, rW, rH, tW, tH);
    }
    void drawTextureRounded(DrawContext context, int nvgImageId, float x, float y, float width, float height, float u, float v, float rW, float rH, float tW, float tH, float radius);
    int createImageFromTexture(int textureId, int width, int height);
    void deleteImage(int imageId);
    int createImageFromFile(String resourcePath);

    void scissor(DrawContext context, float x, float y, float width, float height);
    void scissor(float x, float y, float width, float height);
    void resetScissor();
    void save();
    void restore();
    void translate(float x, float y);
    void scale(float x, float y);
    void cleanup();

}
