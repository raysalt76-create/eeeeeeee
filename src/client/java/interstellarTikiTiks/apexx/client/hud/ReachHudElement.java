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

public class ReachHudElement extends HUDElement {

    private static final Color COLOR_SUSPICIOUS  = new Color(255, 159, 10,  255);
    private static final Color COLOR_VERY_HIGH   = new Color(255, 69,  58,  255);
    private static final Color COLOR_NORMAL      = new Color( 50, 215, 75,  255);


    private final SliderSetting cornerRadius      = new SliderSetting("Corner Radius", 10.0, 0.0, 20.0, 0);
    private final SliderSetting bgOpacity         = new SliderSetting("BG Opacity", 0.72, 0.0, 1.0, 2);
    private final BooleanSetting gradientBackground = new BooleanSetting("Gradient BG", false);
    private final BooleanSetting showBorder       = new BooleanSetting("Show Border", true);
    private final SliderSetting borderWidth       = new SliderSetting("Border Width", 1.0, 0.5, 5.0, 1);
    private final SliderSetting fontSize          = new SliderSetting("Text Size", 13.0, 8.0, 32.0, 0);

    private final BooleanSetting glowEffect       = new BooleanSetting("Glow Effect", false);
    private final BooleanSetting glowOnSuspicious = new BooleanSetting("Glow on Suspicious", true);
    private final SliderSetting glowSize          = new SliderSetting("Glow Size", 6.0, 0.0, 20.0, 0);
    private final SliderSetting glowIntensity     = new SliderSetting("Glow Intensity", 0.3, 0.0, 1.0, 2);

    private final BooleanSetting colorCodedReach  = new BooleanSetting("Color Coded", true);
    private final BooleanSetting pulseOnHit       = new BooleanSetting("Pulse on Hit", true);
    private final SliderSetting pulseStrength     = new SliderSetting("Pulse Strength", 0.1, 0.0, 0.5, 2);
    private final SliderSetting suspiciousThreshold = new SliderSetting("Suspicious Threshold", 3.0, 2.5, 6.0, 1);
    private final SliderSetting veryHighThreshold = new SliderSetting("Very High Threshold", 3.5, 3.0, 6.0, 1);

    private float pulseAnimation = 0.0f;
    private double lastReach = 0.0;

    public ReachHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
    }

    public SliderSetting getCornerRadius()         { return cornerRadius; }
    public SliderSetting getBgOpacity()            { return bgOpacity; }
    public BooleanSetting getGradientBackground()  { return gradientBackground; }
    public BooleanSetting getShowBorder()          { return showBorder; }
    public SliderSetting getBorderWidth()          { return borderWidth; }
    public SliderSetting getFontSize()             { return fontSize; }
    public BooleanSetting getGlowEffect()          { return glowEffect; }
    public BooleanSetting getGlowOnSuspicious()    { return glowOnSuspicious; }
    public SliderSetting getGlowSize()             { return glowSize; }
    public SliderSetting getGlowIntensity()        { return glowIntensity; }
    public BooleanSetting getColorCodedReach()     { return colorCodedReach; }
    public BooleanSetting getPulseOnHit()          { return pulseOnHit; }
    public SliderSetting getPulseStrength()        { return pulseStrength; }
    public SliderSetting getSuspiciousThreshold()  { return suspiciousThreshold; }
    public SliderSetting getVeryHighThreshold()    { return veryHighThreshold; }

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
        settings.add(glowOnSuspicious);
        settings.add(glowSize);
        settings.add(glowIntensity);
        settings.add(colorCodedReach);
        settings.add(pulseOnHit);
        settings.add(pulseStrength);
        settings.add(suspiciousThreshold);
        settings.add(veryHighThreshold);
        return settings;
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double currentReach = HUDManager.getLastHitReach();

        if (currentReach != lastReach) {
            if (pulseOnHit.isEnabled()) pulseAnimation = 1.0f;
            lastReach = currentReach;
        }
        pulseAnimation = Math.max(0, pulseAnimation - frameSeconds() * 2.0f);

        String text = String.format("Reach: %.2fm", currentReach);

        float fSize     = (float) fontSize.getValue();
        float textWidth = Renderer.get().getTextWidth(text, fSize);
        setWidth(textWidth + 12);
        setHeight(fSize + 7);

        float x      = (float) getX();
        float y      = (float) getY();
        float radius = (float) cornerRadius.getValue();

        Color reachColor;
        if (colorCodedReach.isEnabled()) {
            if (currentReach >= veryHighThreshold.getValue())   reachColor = COLOR_VERY_HIGH;
            else if (currentReach >= suspiciousThreshold.getValue()) reachColor = COLOR_SUSPICIOUS;
            else                                                reachColor = COLOR_NORMAL;
        } else {
            reachColor = HudTheme.textColor();
        }

        float scale = 1.0f;
        if (pulseOnHit.isEnabled() && pulseAnimation > 0) {
            scale = 1.0f + pulseAnimation * (float) pulseStrength.getValue();
        }

        boolean isSuspicious = currentReach >= suspiciousThreshold.getValue();
        if (glowEffect.isEnabled() || (glowOnSuspicious.isEnabled() && isSuspicious)) {
            renderGlowLayers(context, x, y, (float) getWidth(), (float) getHeight(), radius,
                    scale, reachColor, (float) glowSize.getValue(), (float) glowIntensity.getValue());
        }

        Color bgColor = new Color((int) (bgOpacity.getValue() * 255) << 24 | HudTheme.backgroundRgb(), true);
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
            if (pulseOnHit.isEnabled() && pulseAnimation > 0) bWidth += pulseAnimation * 2.0f;
            Renderer.get().drawRoundedRectOutline(context, x, y, (float) getWidth(), (float) getHeight(), radius, bWidth,
                    new Color(reachColor.getRed(), reachColor.getGreen(), reachColor.getBlue(), 150));
        }

        Renderer.get().drawCenteredText(context, text, x + (float) getWidth() / 2, y + (float) getHeight() / 2, fSize, reachColor, true);
    }
}
