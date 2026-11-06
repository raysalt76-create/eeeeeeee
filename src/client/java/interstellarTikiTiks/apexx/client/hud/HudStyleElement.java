package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.List;

/**
 * HUD-wide colours and glass look shared by every widget. It is not drawn and has no position: it is a HUD
 * element only so its settings persist through the same hudElements config block as the widgets (no separate
 * file, nothing new written to disk). The HUD editor opens it from its "Colours" header button.
 *
 * <p>Colour Mode "Follow GUI Theme" (default) derives every HUD colour from the active ClickGUI theme, live;
 * "Custom" uses the accent / text / secondary / background colours set here. A widget can still override
 * either with its own "Use Own Colours" block.
 */
public final class HudStyleElement extends HUDElement {
    public static final String NAME = "HUD Style";
    public static final String MODE_FOLLOW = "Follow GUI Theme";
    public static final String MODE_CUSTOM = "Custom";

    private final ModeSetting colourMode = new ModeSetting("Colour Mode", MODE_FOLLOW, MODE_CUSTOM)
            .setDescription("Follow GUI Theme: every HUD widget takes its accent, text, secondary and background "
                    + "colours from your ClickGUI theme and recolours when you change it. Custom: use the colours below.");
    private final BooleanSetting customAccent = new BooleanSetting("Custom Accent", false)
            .setDescription("Keep following the GUI theme but use your own accent colour.")
            .setVisibility(this::followTheme);
    private final ColorSetting accentColour = new ColorSetting("Accent Color", new Color(255, 255, 255))
            .setDescription("HUD accent (values, bars, highlights) in Custom mode or with Custom Accent on.")
            .setVisibility(() -> !followTheme() || customAccent());
    private final ColorSetting textColour = new ColorSetting("Text Colour", new Color(255, 255, 255))
            .setDescription("HUD main text colour in Custom mode.")
            .setVisibility(() -> !followTheme());
    private final ColorSetting secondaryColour = new ColorSetting("Secondary Colour", new Color(184, 184, 184))
            .setDescription("HUD labels, captions and dimmer text in Custom mode.")
            .setVisibility(() -> !followTheme());
    private final ColorSetting glassTint = new ColorSetting("Glass Tint", new Color(0x05, 0x08, 0x0C, 0x44))
            .setDescription("HUD background colour in Custom mode; its alpha is how strongly it tints the glass.")
            .setVisibility(() -> !followTheme());
    private final ColorSetting rimColour = new ColorSetting("Rim Colour", new Color(255, 255, 255))
            .setDescription("Glass sheen and rim colour in Custom mode.")
            .setVisibility(() -> !followTheme());
    private final SliderSetting glassOpacity = new SliderSetting("Glass Opacity", 100, 0, 200, 0)
            .setDescription("How much the glass body is tinted (%), on the same scale as the menu's Glass Opacity. "
                    + "Lower is clearer glass, higher is more frosted. Also sets how opaque the restyled "
                    + "scoreboard, boss bars and notification cards are.");
    private final SliderSetting glassBlur = new SliderSetting("Glass Blur", 16.0, 0.0, 32.0, 0)
            .setDescription("Backdrop blur behind every glass widget.");

    public HudStyleElement() {
        super(NAME, 0, 0);
        // Never "enabled": the overlay compositor treats any enabled HUD element as visible content.
        setEnabled(false);
        setWidth(0);
        setHeight(0);
    }

    @Override protected boolean scalable() { return false; }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(colourMode, customAccent, accentColour, textColour, secondaryColour, glassTint, rimColour,
                glassOpacity, glassBlur);
    }

    @Override public void setEnabled(boolean enabled) { super.setEnabled(false); }

    public ModeSetting colourModeSetting() { return colourMode; }
    public boolean followTheme() { return !colourMode.is(MODE_CUSTOM); }
    public boolean customAccent() { return customAccent.isEnabled(); }
    public Color accentColour() { return accentColour.getValue(); }
    public Color textColour() { return textColour.getValue(); }
    public Color secondaryColour() { return secondaryColour.getValue(); }
    public Color glassTint() { return glassTint.getValue(); }
    public Color rimColour() { return rimColour.getValue(); }
    /** Glass Opacity as a multiplier: the setting is a percent (100% = 1.0), like the menu's. */
    public float glassOpacity() {
        double percent = glassOpacity.getPreciseValue();
        return Double.isFinite(percent) ? (float) (Math.max(0.0, Math.min(200.0, percent)) / 100.0) : 1f;
    }
    public float glassBlur() { return (float) glassBlur.getPreciseValue(); }

    @Override public void render(DrawContext context, float delta) { }
}
