package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class CoordinatesHudElement extends HUDElement {

    private static final Color TEXT_COLOR = new Color(242, 242, 247, 255);

    private final SliderSetting cornerRadius       = new SliderSetting("Corner Radius", 10.0, 0.0, 20.0, 0);
    private final SliderSetting bgOpacity          = new SliderSetting("BG Opacity", 0.72, 0.0, 1.0, 2);
    private final BooleanSetting gradientBackground = new BooleanSetting("Gradient BG", false);
    private final BooleanSetting showBorder        = new BooleanSetting("Show Border", true);
    private final SliderSetting borderWidth        = new SliderSetting("Border Width", 1.0, 0.5, 5.0, 1);
    private final SliderSetting fontSize           = new SliderSetting("Text Size", 13.0, 8.0, 32.0, 0);

    private final BooleanSetting glowEffect        = new BooleanSetting("Glow Effect", false);
    private final SliderSetting glowSize           = new SliderSetting("Glow Size", 6.0, 0.0, 20.0, 0);
    private final SliderSetting glowIntensity      = new SliderSetting("Glow Intensity", 0.3, 0.0, 1.0, 2);

    private final BooleanSetting showDecimals      = new BooleanSetting("Show Decimals", true);
    private final SliderSetting decimalPlaces      = new SliderSetting("Decimal Places", 1.0, 0.0, 3.0, 0);
    private final ModeSetting mode                 = new ModeSetting("Mode", "Full", "Full", "Compact");
    private final BooleanSetting showFacing        = new BooleanSetting("Facing", true);
    private final BooleanSetting showDimension     = new BooleanSetting("Dimension", false);
    private final BooleanSetting showBiome         = new BooleanSetting("Biome", false);
    private final BooleanSetting coloredAxes       = new BooleanSetting("Coloured Axes", true);
    private final ModeSetting axisColours          = new ModeSetting("Axis Colours", "Classic", "Classic", "Theme")
            .setDescription("Classic red/green/blue axes, or shades of the HUD accent.");
    private final BooleanSetting uppercase         = new BooleanSetting("Uppercase", true);
    private final BooleanSetting netherConversion  = new BooleanSetting("Nether Conversion", false)
            .setDescription("Adds a second line with the matching Nether position while in the Overworld "
                    + "(divided by 8), or the Overworld position while in the Nether (multiplied by 8).");

    private final SliderSetting pulseStrength      = new SliderSetting("Pulse Strength", 0.1, 0.0, 0.5, 2);

    private float pulseAnimation = 0.0f;
    private double lastX = 0, lastY = 0, lastZ = 0;

    public CoordinatesHudElement(String name, double defaultX, double defaultY) {
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
    public BooleanSetting getShowDecimals()      { return showDecimals; }
    public SliderSetting getDecimalPlaces()      { return decimalPlaces; }
    public SliderSetting getPulseStrength()      { return pulseStrength; }
    public ModeSetting getMode()                  { return mode; }
    public BooleanSetting getShowFacing()         { return showFacing; }
    public BooleanSetting getShowDimension()      { return showDimension; }
    public BooleanSetting getShowBiome()          { return showBiome; }
    public BooleanSetting getColoredAxes()        { return coloredAxes; }
    public BooleanSetting getUppercase()          { return uppercase; }
    public boolean themedAxisColours()            { return axisColours.is("Theme"); }
    public boolean showNetherConversion()         { return netherConversion.isEnabled(); }

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
        settings.add(showDecimals);
        settings.add(decimalPlaces);
        settings.add(mode);
        settings.add(showFacing);
        settings.add(showDimension);
        settings.add(showBiome);
        settings.add(coloredAxes);
        settings.add(axisColours);
        settings.add(uppercase);
        settings.add(netherConversion);
        return settings;
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        lastX = x;
        lastY = y;
        lastZ = z;
        pulseAnimation = Math.max(0, pulseAnimation - delta * 2.0f);

        String format = showDecimals.isEnabled()
                ? "XYZ: %." + (int) decimalPlaces.getValue() + "f, %." + (int) decimalPlaces.getValue() + "f, %." + (int) decimalPlaces.getValue() + "f"
                : "XYZ: %.0f, %.0f, %.0f";
        String text = String.format(format, x, y, z);

        float fSize     = (float) fontSize.getValue();
        float textWidth = Renderer.get().getTextWidth(text, fSize);
        setWidth(textWidth + 12);
        setHeight(fSize + 7);

        float posX   = (float) getX();
        float posY   = (float) getY();
        float radius = (float) cornerRadius.getValue();

        Color bgColor = new Color(0, 0, 0, (int) (bgOpacity.getValue() * 255));

        if (glowEffect.isEnabled()) {
            renderGlowLayers(context, posX, posY, (float) getWidth(), (float) getHeight(), radius,
                    1.0f, TEXT_COLOR, (float) glowSize.getValue(), (float) glowIntensity.getValue());
        }

        if (gradientBackground.isEnabled()) {
            Color bgColor2 = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), bgColor.getAlpha() / 2);
            Renderer.get().drawRoundedRectGradientStyled(context, posX, posY, (float) getWidth(), (float) getHeight(), radius,
                    bgColor, bgColor2, true, GuiShaderStyle.HUD);
        } else {
            Renderer.get().drawRoundedRectStyled(context, posX, posY, (float) getWidth(), (float) getHeight(), radius,
                    bgColor, GuiShaderStyle.HUD);
        }

        if (showBorder.isEnabled()) {
            float bWidth = (float) borderWidth.getValue();
            Renderer.get().drawRoundedRectOutline(context, posX, posY, (float) getWidth(), (float) getHeight(), radius, bWidth,
                    new Color(255, 255, 255, 60));
        }

        Renderer.get().drawCenteredText(context, text, posX + (float) getWidth() / 2, posY + (float) getHeight() / 2, fSize, TEXT_COLOR, true);
    }
}
