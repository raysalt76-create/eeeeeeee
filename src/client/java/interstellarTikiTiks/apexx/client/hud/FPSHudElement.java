package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class FPSHudElement extends HUDElement {

    private static final Color COLOR_LOW    = new Color(255, 69,  58,  255);
    private static final Color COLOR_MED    = new Color(255, 159, 10,  255);
    private static final Color COLOR_HIGH   = new Color( 50, 215, 75,  255);
    private static final Color COLOR_NORMAL = new Color(242, 242, 247, 255);

    private final SliderSetting cornerRadius       = new SliderSetting("Corner Radius", 10.0, 0.0, 20.0, 0);
    private final SliderSetting bgOpacity          = new SliderSetting("BG Opacity", 0.72, 0.0, 1.0, 2);
    private final BooleanSetting gradientBackground = new BooleanSetting("Gradient BG", false);
    private final BooleanSetting showBorder        = new BooleanSetting("Show Border", true);
    private final SliderSetting borderWidth        = new SliderSetting("Border Width", 1.0, 0.5, 5.0, 1);
    private final SliderSetting fontSize           = new SliderSetting("Text Size", 14.0, 8.0, 32.0, 0);

    private final BooleanSetting glowEffect        = new BooleanSetting("Glow Effect", false);
    private final SliderSetting glowSize           = new SliderSetting("Glow Size", 6.0, 0.0, 20.0, 0);
    private final SliderSetting glowIntensity      = new SliderSetting("Glow Intensity", 0.3, 0.0, 1.0, 2);

    private final BooleanSetting colorCodedFPS     = new BooleanSetting("Color Coded", true);
    private final BooleanSetting flashOnChange     = new BooleanSetting("Flash on Change", true);
    private final BooleanSetting pulseOnChange     = new BooleanSetting("Pulse on Change", false);
    private final SliderSetting pulseStrength      = new SliderSetting("Pulse Strength", 0.1, 0.0, 0.5, 2);

    private float fpsAnimation  = 0.0f;
    private float pulseAnimation = 0.0f;
    private int lastFps = 0;

    public FPSHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
    }

    public SliderSetting getCornerRadius()       { return cornerRadius; }
    public SliderSetting getBgOpacity()          { return bgOpacity; }
    public BooleanSetting getGradientBackground(){ return gradientBackground; }
    public BooleanSetting getShowBorder()        { return showBorder; }
    public SliderSetting getBorderWidth()        { return borderWidth; }
    public SliderSetting getFontSize()           { return fontSize; }
    public BooleanSetting getGlowEffect()        { return glowEffect; }
    public SliderSetting getGlowSize()           { return glowSize; }
    public SliderSetting getGlowIntensity()      { return glowIntensity; }
    public BooleanSetting getColorCodedFPS()     { return colorCodedFPS; }
    public BooleanSetting getFlashOnChange()     { return flashOnChange; }
    public BooleanSetting getPulseOnChange()     { return pulseOnChange; }
    public SliderSetting getPulseStrength()      { return pulseStrength; }

    @Override
    protected List<Setting> elementSettings() {
        List<Setting> settings = new ArrayList<>();
        settings.add(fontSize);
        settings.add(cornerRadius);
        settings.add(bgOpacity);
        settings.add(gradientBackground);
        settings.add(showBorder);
        settings.add(borderWidth);
        settings.add(glowEffect);
        settings.add(glowSize);
        settings.add(glowIntensity);
        settings.add(colorCodedFPS);
        return settings;
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        int currentFps = mc.getCurrentFps();
        String text = "FPS: " + currentFps;

        if (currentFps != lastFps) {
            if (flashOnChange.isEnabled()) fpsAnimation   = 1.0f;
            if (pulseOnChange.isEnabled()) pulseAnimation = 1.0f;
            lastFps = currentFps;
        }
        fpsAnimation  = Math.max(0, fpsAnimation  - delta * 2.0f);
        pulseAnimation = Math.max(0, pulseAnimation - delta * 2.0f);

        float fSize     = (float) fontSize.getValue();
        float textWidth = Renderer.get().getTextWidth(text, fSize);
        setWidth(textWidth + 12);
        setHeight(fSize + 6);

        float x      = (float) getX();
        float y      = (float) getY();
        float radius = (float) cornerRadius.getValue();

        Color textColor = colorCodedFPS.isEnabled()
                ? (currentFps < 30 ? COLOR_LOW : currentFps < 60 ? COLOR_MED : COLOR_HIGH)
                : COLOR_NORMAL;

        float scale = 1.0f;
        if (pulseOnChange.isEnabled() && pulseAnimation > 0) {
            scale = 1.0f + pulseAnimation * (float) pulseStrength.getValue();
        }

        if (glowEffect.isEnabled()) {
            renderGlowLayers(context, x, y, (float) getWidth(), (float) getHeight(), radius,
                    scale, textColor, (float) glowSize.getValue(), (float) glowIntensity.getValue());
        }

        Color bgColor = new Color(0, 0, 0, (int) (bgOpacity.getValue() * 255));
        if (flashOnChange.isEnabled() && fpsAnimation > 0) {
            int alpha = bgColor.getAlpha() + (int) (50 * fpsAnimation);
            bgColor = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), Math.min(255, alpha));
        }

        if (gradientBackground.isEnabled()) {
            Color bgColor2 = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), bgColor.getAlpha() / 2);
            Renderer.get().drawRoundedRectGradientStyled(context, x, y, (float) getWidth(), (float) getHeight(), radius,
                    bgColor, bgColor2, true, GuiShaderStyle.HUD);
        } else {
            Renderer.get().drawRoundedRectStyled(context, x, y, (float) getWidth(), (float) getHeight(), radius,
                    bgColor, GuiShaderStyle.HUD);
        }

        if (showBorder.isEnabled()) {
            float bWidth = (float) borderWidth.getValue();
            if (pulseOnChange.isEnabled() && pulseAnimation > 0) bWidth += pulseAnimation * 2.0f;
            Renderer.get().drawRoundedRectOutline(context, x, y, (float) getWidth(), (float) getHeight(), radius, bWidth,
                    new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 150));
        }

        Renderer.get().drawCenteredText(context, text, x + (float) getWidth() / 2, y + (float) getHeight() / 2, fSize, textColor, true);
    }
}
