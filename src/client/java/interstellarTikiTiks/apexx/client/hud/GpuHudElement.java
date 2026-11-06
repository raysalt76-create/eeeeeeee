package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * A persisted HUD placement whose pixels are produced by {@link GpuHudRenderer}. The Array List widget is the one
 * home of the module list: its look (size, sorting, animation, backing) is set here, and its colours are the HUD
 * colours unless "Use Own Colours" is on.
 */
public final class GpuHudElement extends HUDElement {
    public enum Kind { PERFORMANCE, COORDINATES, KEYBINDS, ARRAY_LIST }

    /** Separator between a module name and its mode in an array-list label. */
    public static final String SUFFIX_SEPARATOR = "  ·  ";

    private final Kind kind;
    private final BooleanSetting onlyEnabledModules = new BooleanSetting("Only Enabled", false);
    private final BooleanSetting includeMouseBinds = new BooleanSetting("Include Mouse Binds", true);
    private final BooleanSetting includeSettingBinds = new BooleanSetting("Include Setting Binds", true);
    private final BooleanSetting showMode = new BooleanSetting("Show Mode", true);
    private final SliderSetting maxRows = new SliderSetting("Max Rows", 8, 3, 16, 0);
    private final BooleanSetting showHeader = new BooleanSetting("Show Header", true);

    // ---- Array List ----
    private final SliderSetting textSize = new SliderSetting("Text Size", 11.0, 8.0, 24.0, 0)
            .setDescription("Text size of the module names in the list.");
    private final ModeSetting sort = new ModeSetting("Sort", "Length", "Length", "Alphabetical", "Category")
            .setDescription("Length sorts longest name first for a tidy staircase; Alphabetical sorts A to Z; "
                    + "Category groups modules by their ClickGUI category (longest first inside each group).");
    private final ModeSetting animation = new ModeSetting("Animation", "Slide", "Slide", "Fade", "Bounce", "None")
            .setDescription("How rows appear and disappear when you toggle a module: slide in from the screen "
                    + "edge, fade, slide with a small overshoot, or no animation.");
    private final SliderSetting animationSpeed = new SliderSetting("Animation Speed", 1.0, 0.25, 3.0, 2)
            .setDescription("Higher is faster. 1 = about a quarter of a second per row.")
            .setVisibility(() -> !this.animation.is("None"));
    private final BooleanSetting showSuffix = new BooleanSetting("Show Suffix", true)
            .setDescription("Shows a module's current mode after its name in a dimmer colour, e.g. \"AutoMace  ·  Stun Slam\".");
    private final BooleanSetting accentText = new BooleanSetting("Accent Text", true)
            .setDescription("Colours the module names with the accent instead of the text colour.");
    private final SliderSetting padding = new SliderSetting("Padding", 5.0, 0.0, 16.0, 0)
            .setDescription("Space in pixels between the text and the edge of its row card.");
    private final SliderSetting spacing = new SliderSetting("Spacing", 2.0, 0.0, 12.0, 0)
            .setDescription("Gap in pixels between two rows.");
    private final BooleanSetting background = new BooleanSetting("Background", true)
            .setDescription("Draws a glass card behind each row so names stay readable on bright backgrounds.");
    private final SliderSetting bgOpacity = new SliderSetting("BG Opacity", 0.55, 0.0, 1.0, 2)
            .setDescription("How opaque each row card is, on top of the HUD's Glass Opacity.")
            .setVisibility(this.background::isEnabled);
    private final BooleanSetting outline = new BooleanSetting("Outline", false)
            .setDescription("Draws a thin accent border around each row card.");

    public GpuHudElement(String name, Kind kind, double x, double y, boolean enabled) {
        super(name, x, y);
        this.kind = kind;
        setEnabled(enabled);
        setWidth(1);
        setHeight(1);
    }

    public Kind kind() { return kind; }
    public boolean onlyEnabledModules() { return onlyEnabledModules.isEnabled(); }
    public boolean includeMouseBinds() { return includeMouseBinds.isEnabled(); }
    public boolean includeSettingBinds() { return includeSettingBinds.isEnabled(); }
    public boolean showMode() { return showMode.isEnabled(); }
    public int maxRows() { return (int) Math.round(maxRows.getValue()); }
    public boolean showHeader() { return showHeader.isEnabled(); }

    public float listTextSize() { return (float) textSize.getPreciseValue(); }
    public String listSort() { return sort.getCurrentMode(); }
    public String listAnimation() { return animation.getCurrentMode(); }
    public float listAnimationSpeed() { return (float) animationSpeed.getPreciseValue(); }
    public boolean listAccentText() { return accentText.isEnabled(); }
    public float listPadding() { return (float) padding.getPreciseValue(); }
    public float listSpacing() { return (float) spacing.getPreciseValue(); }
    public boolean listBackground() { return background.isEnabled(); }
    public float listBgOpacity() { return (float) bgOpacity.getPreciseValue(); }
    public boolean listOutline() { return outline.isEnabled(); }

    /** The array-list label: the module name, plus its first mode after {@link #SUFFIX_SEPARATOR} when shown. */
    public String listLabel(Module module) {
        if (!showSuffix.isEnabled()) return module.getName();
        for (Setting setting : module.getSettings()) {
            if (setting instanceof ModeSetting mode && !mode.getCurrentMode().isBlank()) {
                return module.getName() + SUFFIX_SEPARATOR + mode.getCurrentMode();
            }
        }
        return module.getName();
    }

    @Override protected List<Setting> elementSettings() {
        return switch (kind) {
            case KEYBINDS -> List.of(onlyEnabledModules, includeMouseBinds, includeSettingBinds, showMode, maxRows,
                    showHeader);
            case ARRAY_LIST -> List.of(textSize, sort, animation, animationSpeed, showSuffix, accentText, padding,
                    spacing, background, bgOpacity, outline);
            default -> List.of();
        };
    }

    /** Rendering is batched by GpuHudRenderer so all HUD geometry shares one display list. */
    @Override public void render(DrawContext context, float delta) { }
}
