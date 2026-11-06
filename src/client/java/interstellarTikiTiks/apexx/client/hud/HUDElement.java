package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class HUDElement {
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 2.0;

    private final String name;
    private double x, y, width, height;
    private boolean enabled = true;
    private boolean dragging = false;
    private double dragOffsetX, dragOffsetY;
    /**
     * Per-widget size multiplier. It is a regular setting so it persists through the same
     * hudElements settings block the config already writes for every element.
     */
    private final SliderSetting scaleSetting = new SliderSetting("Scale", 1.0, MIN_SCALE, MAX_SCALE, 2)
            .setDescription("Size of this widget.");
    /** Shared per-widget auto-hide: only drawn in-game for a while after you hit something or got hurt. */
    private final BooleanSetting onlyInCombat = new BooleanSetting("Only In Combat", false)
            .setDescription("Hides this widget in-game unless you hit something or took damage recently. "
                    + "The HUD editor always shows it.");
    private final SliderSetting combatTimeout = new SliderSetting("Combat Timeout", 8.0, 1.0, 30.0, 0)
            .setDescription("Seconds after your last hit given or taken that the widget stays visible.")
            .setVisibility(() -> this.onlyInCombat.isEnabled());
    /**
     * Per-widget colour override. Off (default): the widget uses the HUD-wide colours (HUD editor > Colours,
     * which follow the ClickGUI theme unless set to Custom). On: the widget uses its own colours below.
     */
    private final BooleanSetting ownColours = new BooleanSetting("Use Own Colours", false)
            .setDescription("Give this widget its own colours instead of the HUD-wide ones "
                    + "(HUD editor > Colours, which follow your ClickGUI theme by default).");
    private final ColorSetting ownAccent = new ColorSetting("Accent Colour", new Color(76, 132, 255))
            .setDescription("This widget's accent (values, bars, highlights).")
            .setVisibility(() -> this.ownColours.isEnabled());
    private final ColorSetting ownText = new ColorSetting("Text Colour", new Color(255, 255, 255))
            .setDescription("This widget's main text colour.")
            .setVisibility(() -> this.ownColours.isEnabled());
    private final ColorSetting ownSecondary = new ColorSetting("Secondary Colour", new Color(184, 184, 184))
            .setDescription("This widget's labels, captions and dimmer text.")
            .setVisibility(() -> this.ownColours.isEnabled());
    private final ColorSetting ownBackground = new ColorSetting("Background Colour", new Color(0x05, 0x08, 0x0C, 0x44))
            .setDescription("This widget's background tint; its alpha is how strongly it covers the scene.")
            .setVisibility(() -> this.ownColours.isEnabled());
    private List<Setting> settingsView;
    private double defaultX, defaultY;
    private boolean defaultsCaptured;

    public HUDElement(String name, double defaultX, double defaultY) {
        this.name = name;
        this.x = defaultX;
        this.y = defaultY;
        this.width = 50;
        this.height = 12;
    }

    /** The widget's own settings (without the shared Scale row). */
    protected abstract List<Setting> elementSettings();

    /** False for non-visual holders (e.g. the HUD style block) that must not get a Scale row. */
    protected boolean scalable() { return true; }

    /**
     * False for widgets whose colours are not set per widget (the HUD style block, or a widget whose colour
     * override lives in its module). Themeable widgets get the "Use Own Colours" block.
     */
    protected boolean themeable() { return scalable(); }

    /**
     * The colour settings this widget's "Use Own Colours" switch enables, when the widget already has its own
     * (CPS values, keystroke keys). Null (default) gives the generic accent / text / secondary / background
     * set, which {@link HudTheme#enter} swaps into the HUD palette while the widget draws.
     */
    protected List<? extends Setting> ownColourSettings() { return null; }

    /** True when "Use Own Colours" is on for this widget. */
    public final boolean usesOwnColours() {
        return themeable() && ownColours.isEnabled();
    }

    /** True when the widget's own colours are the generic set (the HUD palette is swapped while it draws). */
    public final boolean usesGenericOwnColours() {
        return usesOwnColours() && ownColourSettings() == null;
    }

    public final BooleanSetting getOwnColoursSetting() { return ownColours; }
    public final Color ownAccent() { return ownAccent.getValue(); }
    public final Color ownText() { return ownText.getValue(); }
    public final Color ownSecondary() { return ownSecondary.getValue(); }
    public final Color ownBackground() { return ownBackground.getValue(); }

    /**
     * Scale first, then the widget's own settings, then its colour override block, then the shared combat
     * auto-hide rows. Built once: the list is read every frame by the editor.
     */
    public final List<Setting> getSettings() {
        List<Setting> view = settingsView;
        if (view == null) {
            List<Setting> own = elementSettings();
            ArrayList<Setting> all = new ArrayList<>((own == null ? 0 : own.size()) + 8);
            if (scalable()) all.add(scaleSetting);
            if (own != null) all.addAll(own);
            if (themeable()) {
                all.add(ownColours);
                List<? extends Setting> specific = ownColourSettings();
                if (specific == null) {
                    all.add(ownAccent);
                    all.add(ownText);
                    all.add(ownSecondary);
                    all.add(ownBackground);
                } else {
                    for (Setting colour : specific) {
                        colour.setVisibility(() -> this.ownColours.isEnabled());
                        all.add(colour);
                    }
                }
            }
            if (scalable()) {
                all.add(onlyInCombat);
                all.add(combatTimeout);
            }
            view = settingsView = Collections.unmodifiableList(all);
        }
        return view;
    }

    /** True when "Only In Combat" is on and you have not fought within the timeout (in-game drawing only). */
    public boolean isHiddenOutOfCombat() {
        if (!scalable() || !onlyInCombat.isEnabled()) return false;
        double seconds = combatTimeout.getPreciseValue();
        long windowMs = Double.isFinite(seconds) ? Math.round(Math.max(0.0, seconds) * 1000.0) : 8000L;
        return !CombatState.inCombat(windowMs);
    }

    public SliderSetting getScaleSetting() { return scaleSetting; }

    /** Records the construction-time position and every setting value as this widget's defaults. */
    public void captureDefaults() {
        defaultX = x;
        defaultY = y;
        defaultsCaptured = true;
        for (Setting setting : getSettings()) setting.captureDefault();
    }

    /** Restores position, scale and settings to the captured defaults (enabled state is kept). */
    public void resetToDefaults() {
        if (!defaultsCaptured) return;
        x = defaultX;
        y = defaultY;
        // Reverse order: a colour reset can trip a "custom colour -> theme off" listener; the toggle it
        // flips is listed earlier, so it is restored after.
        List<Setting> settings = getSettings();
        for (int i = settings.size() - 1; i >= 0; i--) settings.get(i).resetToDefault();
    }

    public boolean isModifiedFromDefaults() {
        if (!defaultsCaptured) return false;
        if (x != defaultX || y != defaultY) return true;
        for (Setting setting : getSettings()) if (setting.isModified()) return true;
        return false;
    }

    public abstract void render(DrawContext context, float delta);

    public static void renderGlowLayers(DrawContext context,
            float x, float y, float width, float height, float radius,
            float scale, Color tintColor, float glowSize, float glowIntensity) {
        for (int i = 20; i >= 1; i--) {
            float layerProgress = (float) i / 20;
            float layerSize     = glowSize * layerProgress;
            float glowAlpha     = glowIntensity * (1.0f - layerProgress) / 20;
            Color glowColor = new Color(
                    tintColor.getRed(),
                    tintColor.getGreen(),
                    tintColor.getBlue(),
                    (int) (glowAlpha * 255));
            Renderer.get().drawRoundedRectStyled(
                    context,
                    x - layerSize * scale,
                    y - layerSize * scale,
                    width  * scale + layerSize * 2,
                    height * scale + layerSize * 2,
                    radius + layerSize,
                    glowColor,
                    GuiShaderStyle.GLOW);
        }
    }

    private long lastFrameNanos = 0L;

    /** Real seconds since this widget last asked (clamped); the render "delta" is tick progress, not time. */
    protected final float frameSeconds() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1f / 60f : (now - lastFrameNanos) / 1.0e9f;
        lastFrameNanos = now;
        return Math.max(0.0005f, Math.min(0.1f, dt));
    }

    public void startDragging(double mouseX, double mouseY) { this.dragging = true; this.dragOffsetX = mouseX - this.x; this.dragOffsetY = mouseY - this.y; }
    public void stopDragging() { this.dragging = false; }
    public void mouseDragged(double mouseX, double mouseY) { if (this.dragging) { this.x = mouseX - this.dragOffsetX; this.y = mouseY - this.dragOffsetY; } }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    /** Precise (unrounded) value: SliderSetting.getValue rounds through BigDecimal, too costly per frame. */
    public double getScale() {
        double s = scaleSetting.getPreciseValue();
        return Double.isFinite(s) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)) : 1.0;
    }
    public String getName() { return name; }
    public double getX() { return x; }
    public double getY() { return y; }
    public boolean isEnabled() { return enabled; }
    public boolean isDragging() { return dragging; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    public void setWidth(double width) { this.width = width; }
    public void setHeight(double height) { this.height = height; }
    public void setScale(double scale) { scaleSetting.setValue(Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale))); }
}
