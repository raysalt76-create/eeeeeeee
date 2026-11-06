package interstellarTikiTiks.apexx.client.gui.gpuui;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.config.ConfigKeybindManager;
import interstellarTikiTiks.apexx.client.config.CloudConfigSync;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.config.CloudConfigs;
import interstellarTikiTiks.apexx.client.config.ClientPrefs;
import interstellarTikiTiks.apexx.client.config.GuiPrefs;
import interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.AutoMenuScale;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.ResponsiveLayout;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.CatalogDrag;
import interstellarTikiTiks.apexx.client.enemy.Enemy;
import interstellarTikiTiks.apexx.client.enemy.EnemyManager;
import interstellarTikiTiks.apexx.client.friend.Friend;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.ConfigsView;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.StudioTab;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.OnboardingTour;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.WhatsNewPanel;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiHost;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiTextField;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiTextTarget;
import interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.ClassicIcons;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiBounds;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiButton;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiClassicMistMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiElement;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiFogMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiWaterMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuFluidSimulation;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuUiEffectPasses;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiAuroraMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiStarfieldMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiGlass;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiIcons;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiInputRouter;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiPanel;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ConfigKeybindListSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Three-pane GPU client menu with selectable shader backdrops and animated materials.
 */
@Environment(EnvType.CLIENT)
public class GpuClickGuiScreen extends Screen {
    private static volatile String lastCloudConfigStatusMessage = "";

    private enum View { MODULES, CONFIGS, MACROS, SCRIPTS, FRIENDS, SELF_DESTRUCT, COSMETICS }
    private enum SettingsTab { APPEARANCE, CLIENT_CONTROLS, CONFIG_BINDS }

    // Navigation categories exposed by the GPU menu.
    private static final Category[] NAV_CATEGORIES = {
            Category.COMBAT, Category.MOVEMENT, Category.AUTOMATIONS, Category.RENDER, Category.PLAYER,
            Category.BLATANT, Category.SMP, Category.BEDWARS
    };

    // Apple-inspired Cupertino typography palette (warm/cool off-whites, no harsh #FFFFFF)
    private static final int DEFAULT_TITLE = 0xfff6f7f9;     // Luminous porcelain off-white for headers & selected titles
    private static final int DEFAULT_ACCENT = 0xffc5b89b;    // Soft champagne accent for the neutral fallback
    private static final int KNOB_WHITE = 0xfff4f6f8;

    // Theme palette, refreshed every frame from the selected theme and the Appearance settings (see applyPalette).
    private int titleColor = DEFAULT_TITLE;
    private int controlRgb = 0xffffff;
    private int accent2 = DEFAULT_ACCENT;
    private boolean gradientAccents;
    private int toggleOnColor = DEFAULT_ACCENT, sliderFillColor = DEFAULT_ACCENT;
    /** RGB of hover washes; the selected-card / keyboard focus ring colour. */
    private int hoverRgb = 0xffffff, selectionRing = DEFAULT_ACCENT;
    private float hoverK = 1f, panelOpacityK = 1f;
    private boolean showSeparators = true, showSidebarSeparator = true, showLogoSeparator = true, showRims = true;
    /** Liquid Glass theme with the real backdrop available (false in SS-Proof capture, where glass is skipped). */
    private boolean lg;
    private float glassBlurValue = 16f, glassOpacityK = 1f;
    private int panelTintOverride;
    private int rimRgb = 0xffffff;
    private float rimAlphaK = 1f;
    private int textColor = 0xfff1f3f4;
    private int mutedColor = 0xff9ca1a6;
    private int muted2Color = 0xff666b70;
    private int toggleOffColor = 0xff2a2c2f;
    private int lineColor = 0x1affffff;
    private int lineBorderColor = 0x1fffffff;
    private int lineSubtleColor = 0x0bffffff;
    private int railBg = 0x8c050506;
    private int listBg = 0x52080909;
    private int setBg = 0x2e000000;
    private int cardBg = 0x47000000;
    private int cardBorder = 0x29ffffff;
    private int outerBorder = 0x38ffffff;
    private int logoLight = 0xfff3f5f6;
    private int logoDark = 0xff9aa1a6;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> moduleCardRightHits = new ArrayList<>();
    private final UiInputRouter inputRouter = new UiInputRouter();
    private final UiElement inputRoot = new UiElement("gpu-click-gui");
    private final UiPanel headerPanel = new UiPanel("header", 0x00000000, 15);
    private final List<ActionElement> inputNodes = new ArrayList<>();

    // Navigation and view state
    private View view = View.MODULES;

    /** Jumps to one of the full-page views (CONFIGS, MACROS, SCRIPTS, FRIENDS); other layouts use it to hand over. */
    public void showView(String name) {
        try {
            View v = View.valueOf(name);
            if (viewAllowed(v)) view = v;
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Macros and Scripts exist only for Premium sessions (no locked tab for free accounts). Overridable for the GUI preview. */
    protected boolean studioUnlocked() {
        return interstellarTikiTiks.apexx.client.util.PremiumGate.studio();
    }

    /** The Cosmetics tab exists only for Premium sessions. Overridable for the GUI preview. */
    protected boolean cosmeticsUnlocked() {
        return interstellarTikiTiks.apexx.client.util.PremiumGate.cosmetics();
    }

    /** False for a Premium-only page this session may not see; such a page is never opened. */
    private boolean viewAllowed(View v) {
        return switch (v) {
            case MACROS, SCRIPTS -> studioUnlocked();
            case COSMETICS -> cosmeticsUnlocked();
            default -> true;
        };
    }
    private SettingsTab settingsTab = SettingsTab.APPEARANCE;
    private Category category = Category.COMBAT;
    private Module selected;

    // Scrolling and queries
    private int moduleScroll, moduleCardScroll, settingsScroll;
    /** Pixel scroll offsets and last-drawn regions for the two config-pane lists. */
    private float configListScroll, hotkeyScroll;
    private float configListX, configListY, configListW, configListH;
    private float hotkeyListX, hotkeyListY, hotkeyListW, hotkeyListH;
    private static final int MAX_CLOUD_CONFIGS = 10;
    private float moduleCardScrollAnim, railPillY = Float.NaN, railPillH = 29f;
    private float topPillX, topPillW, topPillTX, topPillTW;
    private boolean topPillShow;
    private long catalogSwitchAt;
    private String catalogKey = "";
    private double moduleScrollRemainder, moduleCardScrollRemainder, moduleInspectorScrollRemainder;
    private float appearanceScroll;
    /** Eased copies of the two panel scroll offsets (design units), so content glides instead of jumping. */
    private float settingsScrollShown, appearanceScrollShown;
    private boolean settingsDrawerOpen;

    // First-run tour and the What's New devlog panel (modal overlays), and the rectangles the tour points at.
    private final OnboardingTour tour = new OnboardingTour(this::studioUnlocked);
    private final WhatsNewPanel whatsNew = new WhatsNewPanel();
    private final float[] tourRailRect = new float[4], tourCatalogRect = new float[4];
    private final float[] tourMacrosRect = new float[4], tourScriptsRect = new float[4];
    private final float[][] tourNavRects = new float[5][4];

    private float drawerRegionX, drawerRegionY, drawerRegionW, drawerRegionH;
    private String newConfigName = "";
    private boolean newConfigFocused, closing;

    // ---- Module catalog search, filters, keyboard navigation ----
    private final UiTextField searchField = new UiTextField("Search this category — modules, settings, keys...")
            .maxLength(48).bare(true).padding(34f, 34f).fontSize(12.5f)
            .onChange(this::onSearchChanged).onSubmit(() -> activateNav(false));
    private enum ModuleFilter {
        ALL("All"), ENABLED("Enabled"), FAVORITES("Favorites"), BOUND("Bound");
        final String label;
        ModuleFilter(String label) { this.label = label; }
    }
    private enum ModuleSort {
        DEFAULT("Default"), AZ("A-Z"), ENABLED_FIRST("Enabled first");
        final String label;
        ModuleSort(String label) { this.label = label; }
    }
    /** Static so the choice survives closing and reopening the menu (session only). */
    private static ModuleFilter moduleFilter = ModuleFilter.ALL;
    private static ModuleSort moduleSort = ModuleSort.DEFAULT;
    /** One catalog entry: the module and, when it matched through something other than its name, why. */
    private record CatalogEntry(Module module, int rank, String note, int noteMatch) { }
    private List<CatalogEntry> catalogEntries = List.of();
    private String catalogCacheKey = "";
    private long catalogCacheState, catalogCacheAt;
    /** Keyboard selection over the visible result cards (-1 = none); the ring only shows after a key press. */
    private int navIndex = -1;
    /** A search/filter narrowed to the open category (by clicking a category while it is active). */
    private boolean searchScoped;
    private boolean navVisible, navScrollPending;
    private int lastCatalogCols = 1;
    private float lastCatalogRowPitch = 1f, lastCatalogViewH;

    // ---- Layout prefs (ClientPrefs.gui): module order, folders, Quick Settings, last view ----
    /** The open custom category (folder), or null when a built-in category is shown. */
    private String folderId;
    /** Folder whose name is being edited in the catalog header (null = none). */
    private String renamingFolderId;
    private final UiTextField folderNameField = new UiTextField("Folder name").maxLength(GuiPrefs.MAX_NAME)
            .fontSize(13f).onSubmit(this::commitFolderRename).onBlur(this::commitFolderRename);
    private boolean deleteFolderArmed;
    private long deleteFolderArmedAt;
    /** Catalog scroll to restore once the first frame has measured the list (-1 = none). */
    private float restoreCatalogScroll = -1f;
    private boolean catalogDrawn;
    /** Quick Settings strip folded shut (session only). */
    private static boolean quickCollapsed;
    /** Prefix drawn before a setting row's name (the Quick Settings strip names the module). */
    private String rowLabelPrefix;

    /** A left press on a module card waits for release (click) or movement (drag to reorder). */
    private static final class CardPress implements Runnable {
        final Module module;
        final Runnable click;
        CardPress(Module module, Runnable click) { this.module = module; this.click = click; }
        @Override public void run() { click.run(); }
    }
    private CardPress pendingCardPress;
    private double pendingPressX, pendingPressY;
    /** The card being dragged, the visible order when the drag began and the current drop. */
    private Module dragModule;
    private List<Module> dragVisible = List.of();
    private int dragDropIndex = -1;
    private float dragPointerX, dragPointerY;
    /** Whether the catalog drawn last frame can be reordered (default sort, no search or filter). */
    private boolean catalogReorderable;
    private List<Module> lastCatalogModules = List.of();

    /** One entry of the right-click menu; {@code action} null = a disabled row, label null = a divider. */
    private record MenuItem(String label, boolean checked, boolean enabled, boolean danger, Runnable action) {
        static MenuItem divider() { return new MenuItem(null, false, false, false, null); }
    }
    private List<MenuItem> contextItems;
    private String contextTitle;
    private float contextX, contextY;

    // ---- Inspector QOL ----
    private final UiTextField settingsFilter = new UiTextField("Filter settings...")
            .maxLength(40).fontSize(10.5f).onChange(() -> settingsScroll = 0);
    private Module settingsFilterFor;
    /** "module|group" keys of collapsed setting groups; static = remembered for the session. */
    private static final Set<String> COLLAPSED_GROUPS = new java.util.HashSet<>();
    private boolean resetAllArmed;
    private long resetAllArmedAt;

    // ---- Colour picker extras ----
    private final UiTextField hexField = new UiTextField("#RRGGBB").maxLength(9)
            .allow(UiTextField::isHexChar).fontSize(10.5f)
            .onSubmit(this::applyHexField).onBlur(this::applyHexField).onChange(this::applyHexFieldLive);
    /** Session-only list of recently picked colours (newest first). */
    private static final java.util.ArrayDeque<Integer> RECENT_COLOURS = new java.util.ArrayDeque<>();
    private int pickerOpenedWith;
    private float rainbowSpeedY;
    private int currentAccent = DEFAULT_ACCENT;

    // Interactive edit state
    private Setting draggingSlider;
    private float sliderX, sliderW;
    private ColorSetting colorPicker;
    private StringSetting editingString;
    private KeybindSetting binding;
    /** A bindable BooleanSetting whose own key is being captured. */
    private BooleanSetting bindingBool;
    /** The key a capture just consumed and when; its char event and the tick-loop edge must not act on it. */
    private int capturedKey = Integer.MIN_VALUE;
    private long capturedKeyAtMs;
    private long swallowCharsUntilMs;
    /** Bedwars "may be unstable" notice dismissed with Continue (this session only). */
    private static boolean bedwarsNoticeContinued;
    private boolean bindingModuleKey;
    private Module bindingModuleTarget;
    private String bindingConfigName;
    private String configBindStatusMessage = "";

    // Modals geometry & clipping
    private float modalX, modalY, modalW, modalH;
    private float clipX, clipY, clipW, clipH;
    private boolean clippingHits;

    // Color picker drag state
    private enum ColorDrag { NONE, SV, HUE, ALPHA, RAINBOW_SPEED }
    private ColorDrag colorDrag = ColorDrag.NONE;
    private float colorHue, colorSaturation, colorValue, colorAlpha = 1;
    private float svX, svY, svSize, hueX, hueY, hueH, alphaX, alphaY, alphaW;
    private boolean draggingRainbowSpeed;
    private float rainbowSpeedX, rainbowSpeedW;
    private float inspectorBoundaryX;
    private float moduleInspectorProgress, moduleInspectorTargetProgress;
    private long moduleInspectorAnimationStartedAt;
    private boolean inspectorRevealHitClip;
    private float inspectorRevealX, inspectorRevealY, inspectorRevealW, inspectorRevealH;

    // Animation & timing
    private long openedAt = nanoTime(), closedAt;
    private long lastFrameAt = openedAt;
    private float frameDelta;
    private final java.util.Map<String, Float> toggleAnims = new java.util.HashMap<>();
    private final java.util.Map<String, Float> springVelocity = new java.util.HashMap<>();
    private final UiSmoothScroll catalogScroll = new UiSmoothScroll();
    private final KitHost kitHost = new KitHost();
    private final ConfigsView configsView = new ConfigsView(new ConfigsBackend());
    // Macros and the script IDE are Premium: the editors live in the payload; these shells show a lock without it.
    private final StudioTab macroView = new StudioTab(StudioTab.Kind.MACROS);
    private final StudioTab scriptView = new StudioTab(StudioTab.Kind.SCRIPTS);
    private final PeopleView peopleView = new PeopleView(new PeopleBackend());
    private final interstellarTikiTiks.apexx.client.gui.gpuui.kit.CosmeticsView cosmeticsView =
            new interstellarTikiTiks.apexx.client.gui.gpuui.kit.CosmeticsView(new interstellarTikiTiks.apexx.client.cosmetics.CosmeticsBackend());
    private UiTextTarget kitFocus;
    private java.util.function.IntConsumer kitKeyCapture;

    // Window geometry. The size/offset are design units (scaled by the menu scale) and static, so the
    // menu reopens where the player left it. 0 size = default.
    private static final float DEFAULT_WINDOW_W = 900f, DEFAULT_WINDOW_H = 560f;
    /**
     * The menu's corner-radius scale (unscaled px). Floating panels share one radius, every card inside
     * them shares another, and interactive controls (buttons, toggles, pills, segments) are full
     * capsules -- so no two surfaces at the same level ever disagree on their rounding.
     */
    private static final float BASE_PANEL_RADIUS = 18f, BASE_CARD_RADIUS = 12f;
    /** Live radii (unscaled px): the base radii times the Global Curvature setting, refreshed every frame. */
    private float panelRadius = BASE_PANEL_RADIUS, cardRadius = BASE_CARD_RADIUS, cornerScale = 1f;
    /** Menu Scale the layout is currently using; held while its slider is dragged (see buildFrame). */
    private float appliedMenuScale = Float.NaN;
    private static final float MIN_WINDOW_W = 600f, MIN_WINDOW_H = 400f;
    private static float windowW, windowH, windowOffX, windowOffY;

    /** Cached once: whether this launch is an ephemeral mobile (Android) client, used to auto-shrink the UI. */
    private static Boolean mobileClientCached;
    public static boolean isMobileClient() {
        if (mobileClientCached == null) {
            boolean m = false;
            try {
                m = interstellarTikiTiks.apexx.client.auth.DeviceIdentity.isEphemeralMobile();
            } catch (Throwable ignored) {
            }
            mobileClientCached = m;
        }
        return mobileClientCached;
    }
    private static final int EDGE_L = 1, EDGE_R = 2, EDGE_T = 4, EDGE_B = 8;
    private static final int DRAG_MOVE = 16;
    private int windowDrag;                       // 0 none, DRAG_MOVE, or an EDGE_* mask
    private double pressX, pressY;                // pointer at mouse-down, framebuffer pixels
    private float dragStartOffX, dragStartOffY, dragStartL, dragStartT, dragStartW, dragStartH;
    // Last frame's geometry, used to turn pointer deltas into window size/offset.
    private float lastFitS = 1f, lastSw, lastSh, lastPanelsW, lastDrawerOffsetPx;
    private float lastWinX, lastWinY, lastWinW, lastWinH;
    // Responsive layout (ResponsiveLayout): last frame's breakpoint results and the size the layout last saw.
    private boolean compactRailNow, dockInspectorNow = true, dockDrawerNow = true;
    private float minWinW = MIN_WINDOW_W;
    private float laidOutSw, laidOutSh;
    // Drag-to-scroll over any region the wheel scrolls (touch emulated as mouse, or a mouse), with fling.
    private boolean panning;
    private double panLastY;
    private float panVel, flingVel, flingX, flingY;
    private long panLastNs;
    private int cursorShape = -1;
    /**
     * Standard GLFW move/resize cursors, created once per shape and reused. The old code called
     * {@code glfwCreateStandardCursor} on every edge/corner hover transition without ever freeing
     * the previous handle, leaking one cursor object per transition. There is a small fixed set of
     * shapes (move, h/v-resize, the two diagonals), so caching them for the process is bounded and leak-free.
     */
    private static final java.util.Map<Integer, Long> STANDARD_CURSORS = new java.util.HashMap<>();
    // Water atmosphere: last cursor position (pane UV) and smoothed velocity, so the liquid trails the mouse.
    private float waterPrevMx = Float.NaN, waterPrevMy = Float.NaN, waterVelX, waterVelY;
    private String hoveredTooltipTitle;
    private String hoveredTooltipDesc;

    // Config cache
    private List<String> savedConfigs = List.of();
    private Map<String, PrivateConfigManager.PrivateConfigInfo> cloudProfiles = Map.of();
    private String configStatusMessage = "";
    private String pendingProfileSaveName;
    private boolean pendingSavePublic;
    private List<String> pendingSaveTags = List.of();
    private String pendingProfileActivationName;
    private boolean profileLoadPending;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(double px, double py) { return px >= x && py >= y && px <= x + w && py <= y + h; }
    }

    public GpuClickGuiScreen() {
        super(Text.literal("Apexx Client"));
        inputRoot.add(headerPanel);
        configStatusMessage = lastCloudConfigStatusMessage;
        refreshSavedConfigs();
        WhatsNewPanel.preload();
        restoreLastView();
    }

    private static GuiPrefs guiPrefs() {
        return ClientPrefs.get().gui();
    }

    /** Reopens the category/folder, page and catalog scroll the menu was left on (synced with the account). */
    private void restoreLastView() {
        GuiPrefs prefs = guiPrefs();
        String cat = prefs.lastCategory();
        if (cat.startsWith("folder:")) {
            String id = cat.substring("folder:".length());
            if (prefs.customCategory(id) != null) folderId = id;
        } else if (!cat.isEmpty()) {
            for (Category c : NAV_CATEGORIES) if (c.name().equals(cat)) category = c;
        }
        String last = prefs.lastView();
        if (!last.isEmpty()) {
            try {
                View v = View.valueOf(last);
                // A Premium page remembered from a Premium session falls back to Modules on a free one.
                if (v != View.SELF_DESTRUCT && viewAllowed(v)) view = v;
            } catch (IllegalArgumentException ignored) {
            }
        }
        // The same refresh the page's own nav entry runs when it is opened.
        try {
            if (view == View.SCRIPTS) scriptView.refresh();
            else if (view == View.MACROS) macroView.refresh();
        } catch (Throwable ignored) {
            // the page still opens, just with its last state
        }
        if (view == View.MODULES && prefs.lastScroll() > 0f) restoreCatalogScroll = prefs.lastScroll();
    }

    private void rememberView() {
        try {
            GuiPrefs prefs = guiPrefs();
            // A layout that never drew the classic catalog (Orbit) leaves the remembered scroll alone.
            float scroll = view != View.MODULES ? 0f
                    : catalogDrawn ? catalogScroll.target()
                    : restoreCatalogScroll >= 0f ? restoreCatalogScroll : prefs.lastScroll();
            prefs.rememberView(view.name(), folderId != null ? "folder:" + folderId : category.name(), scroll);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    private boolean modalOverlayOpen() {
        return whatsNew.isOpen() || tour.active();
    }

    /** On-screen rectangle (framebuffer pixels) the current tour step points at, or null. */
    private float[] tourRect() {
        float[] rect = switch (tour.target()) {
            case RAIL -> tourRailRect;
            case CATALOG -> tourCatalogRect;
            case NAV_SETTINGS -> tourNavRects[0];
            case NAV_CONFIGS -> tourNavRects[1];
            case NAV_PEOPLE -> tourNavRects[2];
            case NAV_HUD -> tourNavRects[3];
            case NAV_SELF_DESTRUCT -> tourNavRects[4];
            case RAIL_MACROS -> tourMacrosRect;
            case RAIL_SCRIPTS -> tourScriptsRect;
            default -> null;
        };
        return rect == null || rect[2] <= 0f || rect[3] <= 0f ? null : rect;
    }

    /** Settings > Client > "Replay tour". */
    private void replayTour() {
        settingsDrawerOpen = false;
        whatsNew.close();
        view = View.MODULES;
        tour.start();
    }

    private void refreshSavedConfigs() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            names.addAll(ConfigManager.getInstance().getAvailableConfigs());
        } catch (Exception ignored) {
        }
        savedConfigs = List.copyOf(names);
        if (CloudConfigs.signedIn()) {
            PrivateConfigManager.getInstance().listConfigs(configs -> {
                LinkedHashSet<String> refreshed = new LinkedHashSet<>();
                Map<String, PrivateConfigManager.PrivateConfigInfo> metadata = new HashMap<>();
                try {
                    refreshed.addAll(ConfigManager.getInstance().getAvailableConfigs());
                } catch (Exception ignored) {
                }
                configs.forEach(config -> {
                    refreshed.add(config.slug);
                    metadata.put(config.slug.toLowerCase(Locale.ROOT), config);
                });
                cloudProfiles = Map.copyOf(metadata);
                savedConfigs = List.copyOf(refreshed);
            }, error -> {
                cloudProfiles = Map.of();
                if (error != null && !error.isBlank()) {
                    lastCloudConfigStatusMessage = "Cloud profiles unavailable: " + error;
                    if (view == View.CONFIGS) configStatusMessage = lastCloudConfigStatusMessage;
                }
            });
        } else {
            cloudProfiles = Map.of();
        }
    }

    /** Human reason for the last cloud config failure. */
    private static String cloudError() {
        String error = CloudConfigs.lastError();
        return error == null || error.isBlank() ? "cloud rejected the change" : error;
    }

    /** Run a blocking cloud config op off-thread, then report the result and refresh the list on the client thread. */
    private void runCloudOp(java.util.function.BooleanSupplier op, java.util.function.Consumer<Boolean> onDone) {
        Thread thread = new Thread(() -> {
            boolean ok;
            try { ok = op.getAsBoolean(); } catch (Throwable failure) { ok = false; }
            final boolean result = ok;
            if (client != null) client.execute(() -> {
                onDone.accept(result);
                refreshSavedConfigs();
            });
        }, "apexx-config-op");
        thread.setDaemon(true);
        thread.start();
    }

    /** After a new/updated cloud config persists, apply the visibility + tags the save panel chose. */
    private void applyPendingSaveMetadata(String name, boolean makePublic, List<String> tags) {
        boolean hasTags = tags != null && !tags.isEmpty();
        if (!makePublic && !hasTags) return; // nothing to apply (private, no tags) — the default
        Thread thread = new Thread(() -> {
            try {
                String id = CloudConfigs.idForName(name);
                if (id == null) return;
                if (makePublic) CloudConfigs.setPublicById(id, true);
                if (hasTags) CloudConfigs.setTagsById(id, tags);
            } catch (Throwable ignored) {
            }
            if (client != null) client.execute(this::refreshSavedConfigs);
        }, "apexx-config-meta");
        thread.setDaemon(true);
        thread.start();
    }

    /** Overridable so the offline GUI preview can supply its own modules (see test GuiPreview). */
    protected ModuleManager moduleManager() {
        Apexx apexx = Apexx.getInstance();
        return apexx == null ? null : apexx.getModuleManager();
    }

    protected ClickGUIModule clickGuiModule() {
        ModuleManager manager = moduleManager();
        return manager == null ? null : manager.getModule(ClickGUIModule.class);
    }

    protected List<Module> modulesForCategory(Category requested) {
        ModuleManager manager = moduleManager();
        if (manager == null || requested == null) return List.of();
        List<Module> list = manager.getModulesForCategory(requested).stream()
                .filter(module -> !module.isHidden())
                .toList();
        // The player's own order for this category (drag to reorder), registration order for the rest.
        return GuiPrefs.applyOrder(list, Module::getName, guiPrefs().order(requested.name()));
    }

    /** The modules of a custom category, in its order (members whose module is not loaded are skipped). */
    protected List<Module> modulesForFolder(String id) {
        GuiPrefs.CustomCategory folder = id == null ? null : guiPrefs().customCategory(id);
        if (folder == null) return List.of();
        Map<String, Module> byName = new HashMap<>();
        for (Module m : allModules()) byName.putIfAbsent(m.getName(), m);
        List<Module> out = new ArrayList<>();
        for (String name : folder.modules()) {
            Module m = byName.get(name);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Screen shown again when the menu closes (title, multiplayer, options...); null returns to the game. */
    protected Screen returnScreen;
    private boolean openSoundPlayed;

    /** Opens over {@code parent} and goes back to it on close (the menu can open outside a world). */
    public GpuClickGuiScreen withReturnScreen(Screen parent) {
        this.returnScreen = parent;
        return this;
    }

    public Screen returnScreen() {
        return returnScreen;
    }

    @Override
    protected void init() {
        super.init();
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.OPEN);
        }
        if (ClickGUIModule.isSsProof() && client != null && client.getWindow() != null) {
            double cx = client.mouse != null ? client.mouse.getX() : width * 0.5;
            double cy = client.mouse != null ? client.mouse.getY() : height * 0.5;
            InputUtil.setCursorParameters(client.getWindow(), GLFW.GLFW_CURSOR_HIDDEN, cx, cy);
        }
    }

    /**
     * Vanilla's menu blur is a post pass straight on the game framebuffer, which a recorder captures. While SS Proof
     * moves the menu into the hidden overlay it is skipped; the menu's own blur (a blurred copy of the game frame
     * drawn inside the overlay) replaces it.
     */
    @Override
    protected void applyBlur(DrawContext context) {
        try {
            if (interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.shouldRedirect()) return;
        } catch (Throwable ignored) {
            // fall through to vanilla
        }
        super.applyBlur(context);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Files dropped onto the window (e.g. schematics) go to the module that accepts them. */
    @Override
    public void onFilesDropped(java.util.List<java.nio.file.Path> paths) {
        try {
            interstellarTikiTiks.apexx.client.module.hooks.FileDropHandler handler =
                    interstellarTikiTiks.apexx.client.util.ModuleAccess.getHook(
                            interstellarTikiTiks.apexx.client.module.hooks.FileDropHandler.class);
            if (handler != null) handler.onFilesDropped(paths);
        } catch (Throwable ignored) {
            // a faulty handler must not break the GUI
        }
    }

    @Override
    public void close() {
        if (closing) return;
        cancelKeybindCapture();
        closeContextMenu();
        cancelCardDrag();
        closing = true;
        closedAt = nanoTime();
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.CLOSE);
    }

    @Override
    public void removed() {
        // Before the close-time save below, so the remembered view rides along with it.
        commitFolderRename();
        rememberView();
        closeContextMenu();
        cancelCardDrag();
        scriptView.flush();
        if (cursorShape > 0 && client != null && client.getWindow() != null) {
            try {
                GLFW.glfwSetCursor(client.getWindow().getHandle(), 0L);
            } catch (Throwable ignored) {
                // cosmetic only
            }
        }
        cursorShape = -1;
        // Free the Water simulation's GPU fields while the menu is closed.
        GpuFluidSimulation.releaseSoon();
        GpuUiEffectPasses.trimPool();
        clearTextFocus();
        cancelKeybindCapture();
        interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.hide();
        interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.handOffFocusToGame();
        if (client != null && client.getWindow() != null && client.mouse != null) {
            InputUtil.setCursorParameters(client.getWindow(), InputUtil.GLFW_CURSOR_NORMAL, client.mouse.getX(), client.mouse.getY());
        }
        if (closing) {
            ConfigManager manager = ConfigManager.getInstance();
            manager.save(manager.getLastLoadedConfigName());
            String currentJson = manager.exportCurrentConfigJsonForCloud();
            if (pendingProfileSaveName != null && currentJson != null) {
                String profileName = pendingProfileSaveName;
                boolean savePublic = pendingSavePublic;
                List<String> saveTags = pendingSaveTags;
                configStatusMessage = "Saving cloud profile \"" + profileName + "\"...";
                lastCloudConfigStatusMessage = configStatusMessage;
                PrivateConfigManager.getInstance().saveActiveConfig(profileName, currentJson, () -> {
                    lastCloudConfigStatusMessage = "Saved cloud profile \"" + profileName + "\".";
                    applyPendingSaveMetadata(profileName, savePublic, saveTags);
                    refreshSavedConfigs();
                }, error -> lastCloudConfigStatusMessage = "Cloud profile save failed: " + error);
                pendingProfileSaveName = null;
                pendingSavePublic = false;
                pendingSaveTags = List.of();
            } else if (pendingProfileActivationName != null) {
                String profileName = pendingProfileActivationName;
                lastCloudConfigStatusMessage = "Activating cloud profile \"" + profileName + "\"...";
                PrivateConfigManager.getInstance().activateConfig(profileName,
                        () -> lastCloudConfigStatusMessage = "Active cloud profile: \"" + profileName + "\".",
                        error -> lastCloudConfigStatusMessage = "Could not activate cloud profile: " + error);
                pendingProfileActivationName = null;
            } else if (!profileLoadPending && currentJson != null) {
                CloudConfigSync.saveIfChangedNow(currentJson);
            }
        }
        super.removed();
    }

    private void cancelKeybindCapture() {
        kitKeyCapture = null;
        bindingBool = null;
        if (bindingModuleTarget != null) bindingModuleTarget.setBinding(false);
        binding = null;
        bindingModuleKey = false;
        bindingModuleTarget = null;
        bindingConfigName = null;
    }

    /** Animation clock; overridden by the offline GUI preview so frames are deterministic. */
    protected long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        float k = pixelsPerGuiUnit();
        UiDisplayList frame = buildFrame(mouseX * k, mouseY * k);
        if (frame != null) {
            GpuUiRenderer.renderPixels(context, frame, k);
            hoverSound(mouseX * k, mouseY * k, width * k * height * k);
            // The Cosmetics preview is a real entity render, drawn over the box the page laid out.
            float[] pr = cosmeticsView.previewRect();
            var cosmetics = interstellarTikiTiks.apexx.client.util.ModuleAccess.getHook(
                    interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider.class);
            if (view == View.COSMETICS && !closing && !overlayCaptureActive() && cosmetics != null) {
                if (pr[2] > 0f) {
                    cosmetics.drawPreview(context, Math.round(pr[0] / k),
                            Math.round(pr[1] / k), Math.round((pr[0] + pr[2]) / k), Math.round((pr[1] + pr[3]) / k),
                            cosmeticsView.previewYaw(), 6f);
                }
                // Hat and wing tiles: each item rendered on the player, clipped to the scrolling item list.
                var thumbs = cosmeticsView.thumbnails();
                if (!thumbs.isEmpty()) {
                    float[] clip = cosmeticsView.thumbnailClip();
                    context.enableScissor(Math.round(clip[0] / k), Math.round(clip[1] / k),
                            Math.round((clip[0] + clip[2]) / k), Math.round((clip[1] + clip[3]) / k));
                    try {
                        for (var t : thumbs) {
                            cosmetics.drawThumbnail(context, Math.round(t.x() / k), Math.round(t.y() / k),
                                    Math.round((t.x() + t.w()) / k), Math.round((t.y() + t.h()) / k), t.selection(), t.wings());
                        }
                    } finally {
                        context.disableScissor();
                    }
                }
            }
        }
    }

    private int hoverSoundKey;
    private float hoverSoundX = Float.NaN, hoverSoundY = Float.NaN;
    private int sliderSoundNotch = Integer.MIN_VALUE;

    /** A quiet tick when the pointer moves onto a new clickable target (not when the list scrolls under it). */
    private void hoverSound(float mx, float my, float screenArea) {
        int key = 0;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!hit.contains(mx, my)) continue;
            // Full-screen catch-alls (click outside to close etc.) are not a target.
            if (hit.w() * hit.h() > screenArea * 0.25f) break;
            key = (Math.round(hit.x()) * 31 + Math.round(hit.y())) * 31 + Math.round(hit.w()) * 7 + Math.round(hit.h()) | 1;
            break;
        }
        boolean moved = mx != hoverSoundX || my != hoverSoundY;
        hoverSoundX = mx;
        hoverSoundY = my;
        if (key == hoverSoundKey) return;
        hoverSoundKey = key;
        if (key != 0 && moved && !closing && draggingSlider == null && windowDrag == 0) {
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.HOVER);
        }
    }

    /** Framebuffer pixels per Minecraft GUI unit; the menu is laid out in pixels, not GUI units. */
    protected float pixelsPerGuiUnit() {
        var window = client != null ? client.getWindow() : null;
        if (window == null || window.getScaledWidth() <= 0) return 1f;
        return (float) window.getFramebufferWidth() / window.getScaledWidth();
    }

    /**
     * Lays out one frame of the menu as a display list without touching the GPU, so the same code
     * drives both the game and the offline GUI preview tool. Returns null once a close has finished.
     */
    protected UiDisplayList buildFrame(float mouseX, float mouseY) {
        // Any tab switch drops text focus, so typing can never land in a field on another page.
        if (view != focusView) {
            clearTextFocus();
            focusView = view;
        }
        inspectorRevealW = 0f;
        inspectorRevealHitClip = false;
        if (closing && nanoTime() - closedAt > 170_000_000L) {
            if (client != null) client.setScreen(returnScreen);
            return null;
        }

        // The menu is laid out in framebuffer pixels (submitted through GpuUiRenderer.renderPixels), so
        // hairlines are real 1 px lines and rects, text and icons share one pixel grid at any
        // Minecraft GUI scale.
        float k = pixelsPerGuiUnit();
        float sw = width * k, sh = height * k;
        long now = nanoTime();
        frameDelta = (float) Math.min(.1, Math.max(0, (now - lastFrameAt) / 1_000_000_000.0));
        lastFrameAt = now;
        foldFrame++;
        if (flingVel != 0f && !closing) {
            // Coast after a drag-scroll; each step is a wheel event at the point the drag started.
            mouseScrolled(flingX / k, flingY / k, 0, flingVel * frameDelta / panStepPx(flingX, flingY));
            flingVel = ResponsiveLayout.decayFling(flingVel, frameDelta, 30f);
        }

        updateModuleInspectorAnimation(now);
        ClickGUIModule style = clickGuiModule();
        // Palette and shape come first: the layout below already uses the corner radii.
        currentAccent = style == null ? DEFAULT_ACCENT
                : ClickGUIModule.useCustomHighlight() ? ClickGUIModule.highlightColor1.getValue().getRGB()
                : UiThemeMaterial.theme(ClickGUIModule.theme.getCurrentMode()).accent();
        String activeTheme = style == null ? "" : ClickGUIModule.theme.getCurrentMode();
        // Liquid Glass is the frosted backdrop-blur surface, not a shader material, and draws no fog.
        boolean liquidGlass = UiThemeMaterial.LIQUID_GLASS.name().equalsIgnoreCase(activeTheme);
        // In SS-Proof capture the backdrop effects are skipped (the overlay has no scene to refract),
        // so Liquid Glass falls back to its flat fills there and the menu stays visible.
        lg = liquidGlass && !overlayCaptureActive();
        applyPalette(UiThemeMaterial.theme(activeTheme));

        boolean detachedModuleInspector = view == View.MODULES
                && (moduleInspectorProgress > 0f || moduleInspectorTargetProgress > 0f);
        float detachedInspectorW = 460f;
        float detachedInspectorGap = 14f;
        float inspectorExtent = detachedInspectorGap + detachedInspectorW;
        float layoutProgress = detachedModuleInspector ? moduleInspectorProgress : 0f;
        // Appearance drawer that slides out of the window's left edge.
        float drawerBaseW = 320f;
        float drawerGap = 14f;
        float drawerRaw = anim("settings_drawer", settingsDrawerOpen && !closing ? 1f : 0f, 14f);
        float drawerProgress = drawerRaw * drawerRaw * (3f - 2f * drawerRaw);
        float drawerExtent = drawerGap + drawerBaseW;
        // Side panels dock beside the window and take their width first; the window gives way down to
        // its minimum, and only then does the whole menu shrink. Opening a panel never rescales it.
        // (panelsW is worked out below, once the scale is known: narrow screens overlay these panels instead.)

        // Menu Scale only takes effect once its slider is let go: rescaling the window under the cursor
        // mid-drag would move the slider itself and make it jump. The value label still updates live.
        if (Float.isNaN(appliedMenuScale) || ClickGUIModule.menuScale == null
                || draggingSlider != ClickGUIModule.menuScale) {
            appliedMenuScale = style == null || ClickGUIModule.menuScale == null ? 1f
                    : (float) Math.max(0.6, Math.min(2.0, ClickGUIModule.menuScale.getValue() / 100.0));
        }
        float menuScale = appliedMenuScale;
        // Mobile launchers run a small physical screen at a large Minecraft GUI scale, which makes the
        // whole menu read as huge. Auto-shrink it there; the Menu Scale slider still stacks on top.
        float mobileScale = isMobileClient() ? 0.72f : 1f;
        // Menu Scale Mode "Auto" (the default) also corrects for the monitor's physical size and density, so the
        // menu keeps about the same real size on a laptop panel, a 1440p desk monitor or a 4K screen. Mobile
        // already has its own shrink above, so it is left out there.
        float autoScale = 1f;
        if (!isMobileClient() && ClickGUIModule.menuScaleMode != null
                && "Auto".equals(ClickGUIModule.menuScaleMode.getCurrentMode())) {
            autoScale = AutoMenuScale.correction(sh, AutoMenuScale.currentPxPerMm(client));
        }
        // Window-height rule x monitor correction, never under the readable body-text floor; the slider stacks on top.
        float baseS = ResponsiveLayout.classicAutoScale(sh, mobileScale * autoScale) * menuScale;
        float margin = Math.round(14f * baseS);
        float availW = Math.max(1f, sw - margin * 2f), availH = Math.max(1f, sh - margin * 2f);
        // Breakpoints are taken in design units of the room at the base scale (see ResponsiveLayout).
        float availUnits = ResponsiveLayout.availableUnits(availW, baseS);
        boolean compactRail = ResponsiveLayout.compactRail(availUnits);
        float minWinWd = compactRail ? ResponsiveLayout.COMPACT_MIN_WINDOW_W : MIN_WINDOW_W;
        boolean dockInspector = ResponsiveLayout.dockSidePanel(availUnits, minWinWd, inspectorExtent);
        boolean dockDrawer = ResponsiveLayout.dockSidePanel(availUnits, minWinWd, drawerExtent);
        compactRailNow = compactRail;
        dockInspectorNow = dockInspector;
        dockDrawerNow = dockDrawer;
        minWinW = minWinWd;
        float panelsW = (dockInspector ? inspectorExtent * layoutProgress : 0f)
                + (dockDrawer ? drawerExtent * drawerProgress : 0f);
        float wantW = Math.min(windowW > 0f ? windowW : DEFAULT_WINDOW_W, ResponsiveLayout.MAX_WINDOW_W);
        float wantH = Math.min(windowH > 0f ? windowH : DEFAULT_WINDOW_H, ResponsiveLayout.MAX_WINDOW_H);
        if (laidOutSw != sw || laidOutSh != sh) {
            // The window was resized: anything anchored at an old pixel position is dropped; scroll offsets and the
            // window position are clamped against the new bounds as they are laid out below.
            laidOutSw = sw;
            laidOutSh = sh;
            closeContextMenu();
            cancelCardDrag();
            panning = false;
            flingVel = 0f;
            hoverSoundKey = 0;
        }
        // Below ~0.55 the MSDF glyphs sample so few atlas texels that letters blur together and
        // read as missing or merged ("default" looking like "cefault"). Past this point the window
        // clips/scrolls instead of shrinking text into illegibility.
        float fitS = Math.max(0.55f, Math.min(baseS, Math.min(availW / (minWinWd + panelsW), availH / MIN_WINDOW_H)));
        float winWd = Math.max(minWinWd, Math.min(wantW, availW / fitS - panelsW));
        float winHd = Math.max(MIN_WINDOW_H, Math.min(wantH, availH / fitS));

        float t = Math.min(1f, (nanoTime() - (closing ? closedAt : openedAt)) / (closing ? 170_000_000f : 520_000_000f));
        // Underdamped spring on open (slight overshoot); quick ease-out on close.
        float spring = closing ? 1f - t : 1f - (float) (Math.exp(-7.0 * t) * Math.cos(11.0 * t));
        float fade = closing ? 1f - t : Math.min(1f, t * 2.2f);
        float eased = fade * fade * (3f - 2f * fade);
        float animScale = 0.92f + 0.08f * spring;
        float s = fitS * animScale;

        float actualW = Math.round(winWd * s);
        float actualH = Math.round(winHd * s);
        float groupW = actualW + panelsW * s;
        float groupX = clampRange((sw - groupW) * 0.5f + windowOffX * s, margin * 0.5f, sw - groupW - margin * 0.5f);
        float drawerOffsetPx = dockDrawer ? drawerExtent * s * drawerProgress : 0f;
        float dx = Math.round(groupX + drawerOffsetPx);
        float dyBase = clampRange((sh - actualH) * 0.5f + windowOffY * s, margin * 0.5f, sh - actualH - margin * 0.5f);
        float dy = Math.round(dyBase + (1f - spring) * 18f * s);
        float appRadius = Math.round(panelRadius * s);
        float headerH = Math.round(46f * s);
        float inspectorProgress = detachedModuleInspector ? moduleInspectorProgress : 0f;
        lastFitS = fitS; lastSw = sw; lastSh = sh; lastPanelsW = panelsW; lastDrawerOffsetPx = drawerOffsetPx;
        lastWinX = dx; lastWinY = dy; lastWinW = actualW; lastWinH = actualH;

        var b = UiDisplayList.builder();
        hits.clear();
        moduleCardRightHits.clear();
        hoveredTooltipTitle = null;
        hoveredTooltipDesc = null;

        surface = style == null ? interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.THEME
                : ClickGUIModule.activeSurface();
        // A chosen Background surface replaces the theme's own surface (except Liquid Glass, which keeps its panes).
        boolean materialTheme = style != null && !liquidGlass
                && (!surface.isTheme() || ClickGUIModule.isMaterialTheme(activeTheme));
        boolean mist = style != null && surface.isTheme()
                && ClickGUIModule.backgroundOverlay != null && ClickGUIModule.backgroundOverlay.is("On");
        boolean water = style != null && ClickGUIModule.atmWater.isEnabled();
        boolean aurora = style != null && ClickGUIModule.atmAurora.isEnabled();
        boolean starfield = style != null && ClickGUIModule.atmStarfield.isEnabled();
        boolean fog = !materialTheme && !liquidGlass;

        // SS Proof: the scene blur, vignette and focus dim are queued with the rest of the menu, so they land in the
        // hidden overlay framebuffer too. The blur quad samples a copy of the game frame taken before the GUI flush,
        // so the player sees a blurred game inside the overlay while a recorder sees the plain game. (The one thing
        // that would reach the capturable framebuffer, vanilla's blur post pass, is skipped in applyBlur.)
        boolean captureSafe = true;
        // Without a world (title or multiplayer screen) the game frame is empty; vanilla's panorama and its own
        // blur sit behind the menu instead, so the scene blur would only paint black over them.
        boolean hasScene = client == null || client.world != null;

        // Ambient scene blur + vignette ramp in with the open spring.
        float ambientBlur = captureSafe && hasScene && style != null && ClickGUIModule.blurBackground.isEnabled()
                ? Math.min(8f, (float) ClickGUIModule.blurStrength.getValue() * 0.4f) * eased : 0f;
        if (ambientBlur > 0.3f) b.blur(0, 0, sw, sh, 0, ambientBlur);
        if (captureSafe) {
            b.gradient(0, 0, sw, sh, 0, argb(0x26 * eased, 0), argb(0x26 * eased, 0), argb(0x70 * eased, 0), argb(0x70 * eased, 0));
        }

        // Focus dim fades with the open animation so the window reads as the foreground. Nothing
        // else is painted outside the window: fog, marbling and mist all stay inside its clip.
        int fogTint = mixRgb(currentAccent, 0xff93a4bd, 0.55f) & 0x00ffffff;
        if (captureSafe) b.rect(0, 0, sw, sh, 0, Math.round(eased * (materialTheme ? 0x66 : 0x40)) << 24);

        float glassBlur = glassBlurValue * Math.max(0.35f, eased);
        int glassTint = (mixRgb(0xff080b12, currentAccent, 0.16f) & 0x00ffffff) | 0x8c000000;
        if (panelTintOverride != 0) glassTint = panelTintOverride;
        // Liquid Glass keeps the HUD pane body unless the user picked a Panel Tint.
        int lgTint = panelTintOverride;
        if (!lg) glassTint = scaleAlpha(glassTint, glassOpacityK);

        if (lg) {
            // The HUD's liquid-glass recipe (light body tint, sheen, top highlight, double rim) on the
            // whole window; the rail, header bar, search field and every card get their own panes, so
            // each shows its own refraction bevel instead of one thin edge around a huge panel.
            glassPane(b, dx, dy, actualW, actualH, appRadius, eased, 0x66, true, lgTint);
        } else {
            panelShadow(b, dx, dy, actualW, actualH, appRadius, s, eased);
            if (!materialTheme) b.glass(dx, dy, actualW, actualH, appRadius, glassBlur, glassTint);
        }

        b.clipPush(dx, dy, actualW, actualH, appRadius);
        if (materialTheme) paintSurface(b, activeTheme, dx, dy, actualW, actualH, appRadius, mist);
        else drawGlassWash(b, dx, dy, actualW, actualH, appRadius);
        // Fog that lives inside the glass, drifting slower and warmer for depth parallax.
        if (fog) {
            b.material(dx, dy + actualH * 0.25f, actualW, actualH * 0.75f, 0f,
                    UiFogMaterial.of(0.34f * eased, 0.35f, 1.7f, 1f, fogTint));
        }
        // The Mist atmosphere on the glass themes too: material themes already carry it in paintSurface,
        // but Liquid Glass (and the fog themes) have no material surface, so draw it here as well.
        if (mist && !materialTheme) {
            b.material(dx, dy, actualW, actualH, appRadius, UiClassicMistMaterial.apexx());
        }
        // Flowing-liquid atmosphere: a moving, multi-colour fluid the cursor pushes through.
        if (water && style != null) {
            float wu = actualW > 0f ? clampRange((mouseX - dx) / actualW, 0f, 1f) : 0.5f;
            float wv = actualH > 0f ? clampRange((mouseY - dy) / actualH, 0f, 1f) : 0.5f;
            if (Float.isNaN(waterPrevMx)) { waterPrevMx = wu; waterPrevMy = wv; waterVelX = 0f; waterVelY = 0f; }
            float smooth = Math.min(1f, frameDelta * 12f);
            waterVelX += ((wu - waterPrevMx) - waterVelX) * smooth;
            waterVelY += ((wv - waterPrevMy) - waterVelY) * smooth;
            waterPrevMx = wu; waterPrevMy = wv;
            // Deadzone: an idle cursor must inject EXACTLY zero force. The sim amplifies velocity by
            // 1/dt (~60-200x), so an un-snapped residual from the smoothing read as a constant push
            // (the "it always thinks my mouse is moving right" drift). Snap tiny values to zero.
            if (Math.abs(waterVelX) < 3.0e-4f) waterVelX = 0f;
            if (Math.abs(waterVelY) < 3.0e-4f) waterVelY = 0f;
            float op = (float) (ClickGUIModule.waterOpacity.getValue() / 100.0) * eased;
            float flow = (float) (ClickGUIModule.waterFlowSpeed.getValue() / 100.0);
            int count = (int) Math.round(ClickGUIModule.waterColours.getValue());
            if (GpuFluidSimulation.usable()) {
                // Half-float Navier-Stokes dye simulation (port of the WebGL fluid demo). It is frozen during the
                // close animation and gets the raw (unclamped) cursor so it only splats inside the window.
                GpuFluidSimulation.configure(
                        ClickGUIModule.waterQuality.is("Low") ? 0 : ClickGUIModule.waterQuality.is("Medium") ? 1 : 2,
                        (float) ClickGUIModule.waterCurl.getValue(),
                        (float) ClickGUIModule.waterSplatRadius.getValue(),
                        (float) ClickGUIModule.waterSplatForce.getValue(),
                        (float) ClickGUIModule.waterDyeDissipation.getValue(),
                        (float) ClickGUIModule.waterVelocityDissipation.getValue(),
                        (int) Math.round(ClickGUIModule.waterPressureIterations.getValue()),
                        ClickGUIModule.waterShading.isEnabled(), ClickGUIModule.waterBloom.isEnabled(),
                        !ClickGUIModule.waterColourMode.is("My Colors"), ClickGUIModule.waterRandomSplats.isEnabled(),
                        closing);
                float rawU = actualW > 0f ? (mouseX - dx) / actualW : -1f;
                float rawV = actualH > 0f ? (mouseY - dy) / actualH : -1f;
                b.fluid(dx, dy, actualW, actualH, appRadius, rawU, rawV, waterVelX, waterVelY,
                        ClickGUIModule.waterColour1.getValue().getRGB(),
                        ClickGUIModule.waterColour2.getValue().getRGB(),
                        ClickGUIModule.waterColour3.getValue().getRGB(),
                        count, op, flow, ClickGUIModule.waterMouseRipple.isEnabled() && !closing);
            } else {
                // Fallback (no half-float targets, Vulkan, mobile, or the sim failed once): the stateless lit
                // water surface (gpuui_water.fsh). The cursor sends ripples while it moves.
                b.material(dx, dy, actualW, actualH, appRadius, UiWaterMaterial.of(wu, wv, waterVelX, waterVelY,
                        ClickGUIModule.waterColour1.getValue().getRGB(),
                        ClickGUIModule.waterColour2.getValue().getRGB(),
                        ClickGUIModule.waterColour3.getValue().getRGB(),
                        count, op, flow, ClickGUIModule.waterMouseRipple.isEnabled()));
            }
        }
        // Stackable atmosphere layers (tinted by the live accent), drawn on top of whatever surface.
        if (aurora) {
            b.material(dx, dy, actualW, actualH, appRadius, UiAuroraMaterial.of(currentAccent & 0xffffff,
                    (float) (ClickGUIModule.auroraOpacity.getValue() / 100.0) * eased));
        }
        if (starfield) {
            b.material(dx, dy, actualW, actualH, appRadius, UiStarfieldMaterial.of(currentAccent & 0xffffff,
                    (float) (ClickGUIModule.starfieldOpacity.getValue() / 100.0) * eased));
        }

        // The header strip (brand, destinations, drag handle) sits inside the window; panes start below it.
        float paneY = dy + headerH;
        float paneH = Math.max(0f, actualH - headerH);

        // Module browse uses the complete work area for its two-column catalog.
        float railW = (compactRail ? ResponsiveLayout.COMPACT_RAIL_W : ResponsiveLayout.RAIL_W) * s;
        float divW = Math.max(1f, 1f * s);
        float moduleCatalogW = Math.max(0f, actualW - railW - divW);
        // The module inspector unfolds to the right of the window; the appearance drawer sits on the left.
        // Mirrors the appearance drawer: the full-width panel starts tucked behind the window and slides right.
        // Docked beside the window when the screen has room; on a narrow one it slides over the window's right side.
        float inspectorFullW = dockInspector ? detachedInspectorW * s : Math.min(detachedInspectorW * s, actualW);
        float mainRight = dx + actualW;
        float detachedPanelX = dockInspector
                ? mainRight + detachedInspectorGap * s
                        - (1f - inspectorProgress) * (inspectorFullW + detachedInspectorGap * s)
                : mainRight - inspectorProgress * inspectorFullW;
        float detachedRevealX = dockInspector ? Math.max(mainRight, detachedPanelX) : detachedPanelX;
        float revealedInspectorW = dockInspector
                ? Math.max(0f, detachedPanelX + inspectorFullW - detachedRevealX)
                : inspectorProgress * inspectorFullW;
        inspectorRevealX = detachedRevealX;
        inspectorRevealY = dy;
        inspectorRevealW = revealedInspectorW;
        inspectorRevealH = actualH;
        inspectorBoundaryX = detachedModuleInspector
                ? detachedRevealX
                : dx + actualW;

        // Every page uses the full work area beside the rail.
        if (lg) {
            // The rail is its own inset glass slab; the module list stays clear glass.
            float inset = Math.round(7f * s);
            glassPane(b, dx + inset, paneY + Math.round(4f * s), railW - inset * 2f,
                    paneH - Math.round(4f * s) - inset, cardRadius * s, eased, 0x3a, false, 0);
        } else {
            b.rect(dx, paneY, railW, paneH, 0, railBg);
            b.rect(dx + railW, paneY, moduleCatalogW, paneH, 0, listBg);
            if (showSidebarSeparator) b.rect(dx + railW - divW, paneY, divW, paneH, 0, lineColor);
        }
        drawRail(b, dx, paneY, railW, paneH, compactRail, s, mouseX, mouseY);
        drawCatalog(b, dx + railW, paneY, moduleCatalogW, paneH, false, s, mouseX, mouseY);
        tourRailRect[0] = dx; tourRailRect[1] = paneY; tourRailRect[2] = railW; tourRailRect[3] = paneH;
        tourCatalogRect[0] = dx + railW; tourCatalogRect[1] = paneY;
        tourCatalogRect[2] = moduleCatalogW; tourCatalogRect[3] = paneH;

        drawHeader(b, dx, dy, actualW, headerH, s, mouseX, mouseY);
        if (eased < 0.999f) b.rect(dx, dy, actualW, actualH, appRadius, argb(0xb0 * (1f - eased), 0x0a0e15));
        b.clipPop();
        if (!lg) panelRim(b, dx, dy, actualW, actualH, appRadius, s);

        // Keep the glass surface clean; the interior dividers define its layout.

        if (detachedModuleInspector) {
            float panelW = inspectorFullW;
            float panelX = detachedPanelX;

            if (inspectorProgress > 0.004f) {
                float inspectorRadius = panelRadius * s;
                float revealW = panelW + detachedInspectorGap * s + 64f * s;
                if (dockInspector) b.clipPush(mainRight, dy - 48f * s, revealW, actualH + 96f * s, 0);
                else b.clipPush(dx, dy, actualW, actualH, appRadius);
                panelShadow(b, panelX, dy, panelW, actualH, inspectorRadius, s, inspectorProgress);
                b.clipPush(panelX, dy, panelW, actualH, inspectorRadius);
                if (lg) {
                    glassPane(b, panelX, dy, panelW, actualH, panelRadius * s, 1f, 0x44, false, lgTint);
                } else if (materialTheme) {
                    paintSurface(b, activeTheme, panelX, dy, panelW, actualH, panelRadius * s, mist);
                    b.rect(panelX, dy, panelW, actualH, 0, listBg);
                } else {
                    b.glass(panelX, dy, panelW, actualH, panelRadius * s, glassBlur, glassTint);
                    drawGlassWash(b, panelX, dy, panelW, actualH, panelRadius * s);
                }

                inspectorRevealHitClip = true;
                // Overlaid on the catalog: swallow clicks on the panel's empty parts so they never reach cards below.
                if (!dockInspector) hit(detachedRevealX, dy, revealedInspectorW, actualH, () -> { });
                boolean pointerRevealed = inside(mouseX, mouseY, detachedRevealX, dy, revealedInspectorW, actualH);
                drawModuleInspector(b, panelX, dy, panelW, actualH, false, s,
                        pointerRevealed ? mouseX : -1, pointerRevealed ? mouseY : -1);
                inspectorRevealHitClip = false;
                b.clipPop();
                if (!lg) panelRim(b, panelX, dy, panelW, actualH, inspectorRadius, s);
                b.clipPop();
            }
        }

        drawerRegionW = 0f;
        if (drawerProgress > 0.004f) {
            float fullW = dockDrawer ? drawerBaseW * s : Math.min(drawerBaseW * s, actualW);
            float mainLeft = dx;
            float restX = mainLeft - drawerGap * s - fullW;
            // The panel starts tucked behind the main window and slides left as it opens; on a narrow screen it
            // slides in over the window's left side instead.
            float panelX = dockDrawer ? restX + (1f - drawerProgress) * (fullW + drawerGap * s)
                    : mainLeft - (1f - drawerProgress) * fullW;
            float drawerRadius = panelRadius * s;
            float revealW = fullW + drawerGap * s + 64f * s;
            if (dockDrawer) b.clipPush(mainLeft - revealW, dy - 48f * s, revealW, actualH + 96f * s, 0);
            else b.clipPush(dx, dy, actualW, actualH, appRadius);
            panelShadow(b, panelX, dy, fullW, actualH, drawerRadius, s, drawerProgress);
            b.clipPush(panelX, dy, fullW, actualH, drawerRadius);
            if (lg) {
                glassPane(b, panelX, dy, fullW, actualH, drawerRadius, 1f, 0x44, false, lgTint);
            } else if (materialTheme) {
                paintSurface(b, activeTheme, panelX, dy, fullW, actualH, drawerRadius, mist);
                b.rect(panelX, dy, fullW, actualH, 0, listBg);
            } else {
                b.glass(panelX, dy, fullW, actualH, drawerRadius, glassBlur, glassTint);
                drawGlassWash(b, panelX, dy, fullW, actualH, drawerRadius);
            }

            // Only the part of the drawer that has slid out of the window accepts clicks.
            float visibleX = dockDrawer ? panelX : Math.max(panelX, mainLeft);
            float visibleW = dockDrawer ? Math.max(0f, Math.min(panelX + fullW, mainLeft) - panelX)
                    : Math.max(0f, panelX + fullW - visibleX);
            float savedX = inspectorRevealX, savedY = inspectorRevealY, savedW = inspectorRevealW, savedH = inspectorRevealH;
            boolean savedClip = inspectorRevealHitClip;
            inspectorRevealX = visibleX; inspectorRevealY = dy;
            inspectorRevealW = visibleW; inspectorRevealH = actualH;
            inspectorRevealHitClip = true;
            if (!dockDrawer) hit(visibleX, dy, visibleW, actualH, () -> { });
            boolean pointerInDrawer = inside(mouseX, mouseY, visibleX, dy, inspectorRevealW, actualH);
            drawDrawerContent(b, panelX, dy, fullW, actualH, s,
                    pointerInDrawer ? mouseX : -1, pointerInDrawer ? mouseY : -1);
            drawerRegionX = visibleX; drawerRegionY = dy; drawerRegionW = inspectorRevealW; drawerRegionH = actualH;
            inspectorRevealX = savedX; inspectorRevealY = savedY; inspectorRevealW = savedW; inspectorRevealH = savedH;
            inspectorRevealHitClip = savedClip;
            b.clipPop();
            if (!lg) panelRim(b, panelX, dy, fullW, actualH, drawerRadius, s);
            b.clipPop();
        }

        // No in-panel header row: the "Apexx" brand already lives in the rail (drawRail), Escape
        // closes the screen on its own (see keyPressed), and appearance settings open from the
        // "Settings" item in the floating top nav pill below — a dedicated close/gear pair here
        // would just duplicate both.
        headerPanel.setBounds(new UiBounds(dx, dy, 0f, 0f));

        clippingHits = false;

        // Color picker modal.
        if (colorPicker != null) {
            drawColorPicker(b, sw, sh, s, mouseX, mouseY);
        } else {
            // First-run tour / What's New: modal, so nothing underneath keeps its click regions or tooltip.
            if (!closing) tour.maybeAutoStart();
            if (whatsNew.isOpen()) {
                hits.clear();
                moduleCardRightHits.clear();
                hoveredTooltipDesc = null;
                whatsNew.draw(b, kitHost, sw, sh, s, mouseX, mouseY);
            } else if (tour.active()) {
                hits.clear();
                moduleCardRightHits.clear();
                hoveredTooltipDesc = null;
                tour.draw(b, kitHost, sw, sh, s, tourRect(), mouseX, mouseY);
            } else if (contextItems != null) {
                drawContextMenu(b, sw, sh, s, mouseX, mouseY);
            }
        }
        if (colorPicker != null || modalOverlayOpen()) closeContextMenu();
        if (dragModule != null) drawDragGhost(b, s);

        // Floating VisionOS tooltip
        if (hoveredTooltipDesc != null && !hoveredTooltipDesc.isBlank() && dragModule == null) {
            drawFloatingTooltip(b, mouseX, mouseY, sw, sh, s);
        }

        headerPanel.setEnabled(colorPicker == null && !modalOverlayOpen() && contextItems == null);

        // Custom Stream-Proof Cursor (drawn onto the capture-excluded overlay when SS Proof is enabled). It is sized
        // to the real OS pointer via the window scale factor, not the menu scale, so it never balloons.
        if (ClickGUIModule.isSsProof() && client != null && client.getWindow() != null) {
            ClassicIcons.drawCursor(b, mouseX, mouseY, client.getWindow().getScaleFactor());
        }

        // Resize zones go last so they win over the header's drag area, but they only straddle the
        // window border (the content keeps padding clear of it), so no real control is covered.
        if (colorPicker == null && !modalOverlayOpen() && contextItems == null && dragModule == null) {
            registerWindowZones(dx, dy, actualW, actualH, s, mouseX, mouseY);
        } else if (contextItems != null || dragModule != null) {
            updateResizeCursor(0);
        }
        rebuildInputTree(sw, sh);
        return b.build();
    }

    /**
     * The single drop shadow every floating surface (window, inspector, drawer, top bar, tooltip)
     * uses, so they all sit at the same depth. It is kept tight to the edge on purpose.
     */
    private void panelShadow(UiDisplayList.Builder b, float x, float y, float w, float h, float r,
                             float s, float alpha) {
        if (alpha <= 0.01f || w <= 0f || h <= 0f) return;
        b.shadow(x, y + 2f * s, w, h, r, 8f * s, 0f, argb(0x48 * alpha, 0));
    }

    /** The single themed rim every floating surface uses: a hairline border and a top highlight. */
    private void panelRim(UiDisplayList.Builder b, float x, float y, float w, float h, float r, float s) {
        if (w <= 0f || h <= 0f || !showRims) return;
        b.border(x, y, w, h, r, Math.max(1f, s), outerBorder);
        b.rect(x + r, y + 0.5f * s, Math.max(0f, w - r * 2f), Math.max(1f, s), 0.5f,
                argb(0x1f * Math.min(1f, rimAlphaK), rimRgb));
    }

    /**
     * One liquid-glass pane in the menu: the shared {@link UiGlass} recipe (the HUD pill) with the
     * Appearance settings applied -- Panel Blur, Glass Opacity, Panel Tint, Rim Color, Outer Stroke.
     *
     * @param bodyAlpha body tint alpha at 100% Glass Opacity (0x44 is the HUD pill; sub-panes use less)
     * @param tint      ARGB body colour to use instead of the default near-black (0 = default)
     */
    private void glassPane(UiDisplayList.Builder b, float x, float y, float w, float h, float r, float opacity,
                           int bodyAlpha, boolean shadow, int tint) {
        if (w <= 0f || h <= 0f) return;
        int body = (tint >>> 24) != 0
                ? scaleAlpha(tint, bodyAlpha / (float) 0x44 * glassOpacityK)
                : argb(bodyAlpha * glassOpacityK, UiGlass.DEFAULT_BODY_RGB);
        UiGlass.pane(b, x, y, w, h, r, opacity, glassBlurValue, body, rimRgb, showRims, shadow, rimAlphaK);
    }

    private static int scaleAlpha(int argb, float k) {
        int a = Math.round(((argb >>> 24) & 0xff) * Math.max(0f, k));
        return (Math.min(255, a) << 24) | (argb & 0x00ffffff);
    }

    /** True while the menu is being drawn into the SS-Proof overlay, where backdrop effects are skipped. */
    private static boolean overlayCaptureActive() {
        try {
            return interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isOverlayCaptureActive();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Fills an accent-coloured shape: a flat accent, or the Highlight Color 1 -> 2 gradient when
     * Custom Highlight + Highlight Gradient are on. {@code base} replaces the accent when non-zero
     * (e.g. a custom Toggle On colour, which is never gradiented).
     */
    private void accentFill(UiDisplayList.Builder b, float x, float y, float w, float h, float r, float alpha, int base) {
        if (alpha <= 0.003f || w <= 0f || h <= 0f) return;
        int a1 = base != 0 ? base : accentArgb();
        if (base == 0 && gradientAccents) {
            int c1 = scaleAlpha(a1 | 0xff000000, alpha), c2 = scaleAlpha(accent2 | 0xff000000, alpha);
            b.gradient(x, y, w, h, r, c1, c2, c2, c1);
        } else {
            b.rect(x, y, w, h, r, scaleAlpha(a1 | 0xff000000, alpha * (((a1 >>> 24) & 0xff) / 255f)));
        }
    }

    /** Pointer-hover wash, scaled by the Hover / Hover Strength settings and tinted by Selection Colour. */
    private int hoverWash(int alpha) {
        return argb(alpha * hoverK, hoverRgb);
    }

    /** The Background surface chosen this frame; THEME means the theme's own material. */
    private interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface surface =
            interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.THEME;

    private void paintSurface(UiDisplayList.Builder b, String theme, float x, float y, float w, float h,
                              float radius, boolean mist) {
        if (!surface.isTheme()) {
            // Glass backgrounds sit over the blurred scene instead of replacing it.
            if (surface.seeThrough()) b.glass(x, y, w, h, radius, 8f, 0x2a05080d);
            float detail = (float) ClickGUIModule.surfaceDetail.getValue() / 100f;
            float grain = (float) ClickGUIModule.surfaceGrain.getValue() / 100f;
            float motion = (float) ClickGUIModule.surfaceMotion.getValue() / 100f;
            b.material(x, y, w, h, radius, surface.material(detail, motion, -2f, -2f, 0f, false,
                    UiThemeMaterial.theme(theme).accent() & 0x00ffffff, grain));
            if (ClickGUIModule.useGradientMenus()) gradientWash(b, x, y, w, h, radius);
            return;
        }
        b.material(x, y, w, h, radius, UiThemeMaterial.forTheme(theme));
        if (mist) b.material(x, y, w, h, radius, UiClassicMistMaterial.apexx());
        // Stone themes (Granite, Nero Portoro) have no accent uniform in their shader, so the live
        // accent colour is layered on top here as a soft wash — this is what makes "Granite"
        // recolourable from the theme's accent/highlight picker instead of a fixed gold tint.
        if (UiThemeMaterial.isStone(theme)) {
            int tint = accentArgb() & 0x00ffffff;
            b.gradient(x, y, w, h, radius, tint | 0x1e000000, tint | 0x06000000, tint | 0x10000000, 0x00000000);
        }
        if (ClickGUIModule.useGradientMenus()) gradientWash(b, x, y, w, h, radius);
    }

    /**
     * Refreshes every colour, toggle and size the menu draws with from the theme and the ClickGUI
     * Appearance settings. Colour settings follow the theme until the user changes them.
     */
    private void applyPalette(UiThemeMaterial.Theme theme) {
        boolean stone = theme.stone();
        int tint = theme.tint() & 0x00ffffff;
        int deep = theme.deep() & 0x00ffffff;

        // Shape and toggles.
        cornerScale = ClickGUIModule.globalCurvature == null ? 1f
                : clampRange((float) ClickGUIModule.globalCurvature.getValue() / 10f, 0f, 3f);
        panelRadius = BASE_PANEL_RADIUS * cornerScale;
        cardRadius = BASE_CARD_RADIUS * cornerScale;
        showSeparators = ClickGUIModule.useSeparator();
        showSidebarSeparator = ClickGUIModule.useSidebarSeparator();
        showLogoSeparator = ClickGUIModule.useLogoSeparator();
        showRims = ClickGUIModule.useOuterStroke();
        hoverK = !ClickGUIModule.useHover() ? 0f
                : Math.min(2f, ClickGUIModule.percent(ClickGUIModule.hoverStrength, 1f));
        panelOpacityK = Math.min(1f, ClickGUIModule.percent(ClickGUIModule.panelOpacity, 1f));
        glassOpacityK = Math.min(2f, ClickGUIModule.percent(ClickGUIModule.glassOpacity, 1f));
        glassBlurValue = ClickGUIModule.glassBlur == null ? UiGlass.DEFAULT_BLUR
                : (float) Math.max(0.0, ClickGUIModule.glassBlur.getValue());
        panelTintOverride = ClickGUIModule.colourOr(ClickGUIModule.panelTint, 0);

        // Text.
        textColor = ClickGUIModule.colourOr(ClickGUIModule.textColour, theme.text());
        boolean mutedCustom = ClickGUIModule.colourOr(ClickGUIModule.mutedTextColour, 0) != 0;
        mutedColor = ClickGUIModule.colourOr(ClickGUIModule.mutedTextColour, theme.muted());
        muted2Color = mutedCustom ? scaleAlpha(mutedColor | 0xff000000, 0.8f) : theme.muted2();
        boolean textCustom = ClickGUIModule.colourOr(ClickGUIModule.textColour, 0) != 0;
        titleColor = ClickGUIModule.useCustomHeaderColour() && ClickGUIModule.customTextColor != null
                ? ClickGUIModule.customTextColor.getValue().getRGB() | 0xff000000
                : textCustom ? textColor : DEFAULT_TITLE;

        // Controls.
        toggleOffColor = ClickGUIModule.colourOr(ClickGUIModule.toggleOffColour, theme.toggleOff());
        toggleOnColor = ClickGUIModule.colourOr(ClickGUIModule.toggleOnColour, 0);
        sliderFillColor = ClickGUIModule.colourOr(ClickGUIModule.sliderFillColour, 0);
        int selection = ClickGUIModule.colourOr(ClickGUIModule.selectionColour, 0);
        hoverRgb = selection != 0 ? selection & 0x00ffffff : 0xffffff;
        selectionRing = selection != 0 ? selection | 0xff000000 : accentArgb();
        // The neutral control fill follows the theme's hairline tint (it used to be a fixed silver).
        controlRgb = tint;
        gradientAccents = ClickGUIModule.useCustomHighlight() && ClickGUIModule.useHighlightGradient()
                && ClickGUIModule.highlightColor2 != null;
        accent2 = gradientAccents ? ClickGUIModule.highlightColor2.getValue().getRGB() | 0xff000000
                : mixRgb(accentArgb(), 0xffffffff, 0.35f);

        // Surfaces.
        int rim = ClickGUIModule.colourOr(ClickGUIModule.rimColour, 0);
        rimRgb = rim != 0 ? rim & 0x00ffffff : 0xffffff;
        rimAlphaK = rim != 0 ? Math.min(2f, ((rim >>> 24) & 0xff) / (float) 0x30) : 1f;
        outerBorder = rim != 0 ? rim : theme.outer();
        lineColor = 0x1a000000 | tint;
        lineBorderColor = (stone ? 0x26000000 : 0x1f000000) | tint;
        lineSubtleColor = 0x0b000000 | tint;
        railBg = scaleAlpha((stone ? 0xa6000000 : 0x8c000000) | deep, panelOpacityK);
        listBg = scaleAlpha((stone ? 0x73000000 : 0x52000000) | deep, panelOpacityK);
        setBg = scaleAlpha((stone ? 0x47000000 : 0x2e000000) | deep, panelOpacityK);
        // On Liquid Glass the cards are a darkened frosted scrim: enough dark body that text reads clearly
        // over a bright, busy game scene (blur alone can't give contrast), while the rim keeps the glass look.
        cardBg = scaleAlpha(ClickGUIModule.colourOr(ClickGUIModule.cardFillColor,
                lg ? 0x80000000 : stone ? 0x66000000 : 0x47000000), panelOpacityK);
        cardFillCustom = ClickGUIModule.colourOr(ClickGUIModule.cardFillColor, 0) != 0;
        int border = ClickGUIModule.colourOr(ClickGUIModule.cardBorderColor, 0);
        cardBorderCustom = border != 0;
        cardBorder = border != 0 ? border : (stone ? 0x2e000000 : 0x29000000) | tint;
        logoLight = theme.logoLight();
        logoDark = theme.logoDark();
    }

    private boolean cardFillCustom, cardBorderCustom;

    /** Soft accent wash over the glass lens; the rim comes from {@link #panelRim}. */
    private void drawGlassWash(UiDisplayList.Builder b, float x, float y, float w, float h, float r) {
        if (ClickGUIModule.useGradientMenus()) {
            gradientWash(b, x, y, w, h, r);
            return;
        }
        // Liquid Glass keeps the HUD pill's clean body: no accent wash.
        if (lg) return;
        int a = currentAccent & 0x00ffffff;
        b.gradient(x, y, w, h, r, a | 0x26000000, a | 0x08000000, a | 0x14000000, 0x00000000);
    }

    /** "Gradient Menus": a diagonal accent -> second-accent wash across a panel. */
    private void gradientWash(UiDisplayList.Builder b, float x, float y, float w, float h, float r) {
        int a1 = currentAccent & 0x00ffffff, a2 = accent2 & 0x00ffffff;
        int mid = mixRgb(currentAccent, accent2, 0.5f) & 0x00ffffff;
        b.gradient(x, y, w, h, r, a1 | 0x30000000, mid | 0x16000000, a2 | 0x26000000, mid | 0x10000000);
    }

    /** Frame-rate independent ease of a displayed scroll offset toward its target. */
    private float easeScroll(float shown, float target) {
        float next = shown + (target - shown) * (1f - (float) Math.exp(-frameDelta * 18f));
        return Math.abs(next - target) < 0.05f ? target : next;
    }

    private float anim(String key, float target, float speed) {
        float cur = toggleAnims.getOrDefault(key, target);
        cur += (target - cur) * Math.min(1f, frameDelta * speed);
        if (Math.abs(cur - target) < 0.003f) cur = target;
        toggleAnims.put(key, cur);
        return cur;
    }

    /**
     * Damped spring toward {@code target}. Unlike {@link #anim} it carries velocity, so an
     * underdamped spring (damping below 2*sqrt(stiffness)) overshoots and settles, which is what
     * makes a control feel physical instead of linearly faded. Substepped so a long frame stays stable.
     */
    private float spring(String key, float target, float stiffness, float damping) {
        float pos = toggleAnims.getOrDefault(key, target);
        float vel = springVelocity.getOrDefault(key, 0f);
        float remaining = frameDelta;
        while (remaining > 0f) {
            float dt = Math.min(remaining, 1f / 240f);
            vel += ((target - pos) * stiffness - vel * damping) * dt;
            pos += vel * dt;
            remaining -= dt;
        }
        if (Math.abs(pos - target) < 0.0015f && Math.abs(vel) < 0.01f) { pos = target; vel = 0f; }
        toggleAnims.put(key, pos);
        springVelocity.put(key, vel);
        return pos;
    }

    private static int argb(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | (rgb & 0x00ffffff);
    }

    /**
     * Raised tile (module cards, the rail selection pill). On Liquid Glass it is its own small glass
     * pane, so it gets a real refraction bevel; elsewhere a soft sheen, hairline rim and top specular.
     * An enabled tile carries a faint accent wash.
     */
    private void glassTile(UiDisplayList.Builder b, float x, float y, float w, float h, float r,
                           float s, float hover, float active) {
        int a = accentArgb() & 0x00ffffff;
        hover = Math.max(0f, hover) * hoverK;
        if (lg) {
            glassPane(b, x, y, w, h, r, 1f, Math.round(0x2c + 0x0a * Math.min(1f, hover)), false, 0);
            if (cardFillCustom) b.rect(x, y, w, h, r, cardBg);
            if (hover > 0.01f) b.gradient(x, y, w, h, r, hoverWash(0x14), hoverWash(0x10), hoverWash(0x04), hoverWash(0x06));
            if (active > 0.01f) activeWash(b, x, y, w, h, r, active, a);
            if (cardBorderCustom) b.border(x, y, w, h, r, Math.max(1f, s * 0.8f), cardBorder);
            return;
        }
        if (cardFillCustom) b.rect(x, y, w, h, r, cardBg);
        // Flat inside the window: only floating surfaces cast a shadow (see panelShadow), and a faint
        // accent fill marks the active tile with no coloured bloom behind it.
        float top = 0x14 + 0x0e * hover + 0x06 * active, bot = 0x05 + 0x06 * hover;
        b.gradient(x, y, w, h, r, argb(top, hoverRgb), argb(top, hoverRgb), argb(bot, hoverRgb), argb(bot, hoverRgb));
        if (active > 0.01f) activeWash(b, x, y, w, h, r, active, a);
        int borderColor = cardBorderCustom ? cardBorder
                : argb(0x20 + 0x14 * hover + 0x18 * active, active > 0.01f ? mixRgb(0xffffff, a, active * 0.35f) : 0xffffff);
        b.border(x, y, w, h, r, Math.max(1f, s * 0.8f), borderColor);
        b.rect(x + r * 0.8f, y + s * 0.5f, Math.max(0f, w - r * 1.6f), Math.max(1f, s * 0.8f), 0.5f,
                argb(Math.min(255f, 0x30 + 0x20 * hover), 0xffffff));
    }

    private void activeWash(UiDisplayList.Builder b, float x, float y, float w, float h, float r, float active, int a) {
        int a2 = gradientAccents ? accent2 & 0x00ffffff : a;
        b.gradient(x, y, w, h, r, argb(0x18 * active, a), argb(0x0e * active, a2),
                argb(0x08 * active, a2), argb(0x10 * active, a));
    }

    private static float clampRange(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    /** Title strip inside the window: brand on the left, global destinations centred, drag handle. */
    private void drawHeader(UiDisplayList.Builder b, float x, float y, float w, float h, float s,
                            float mx, float my) {
        if (!lg) b.rect(x, y, w, h, 0, railBg);
        if (showLogoSeparator) b.rect(x, y + h - 1f, w, 1f, 0, lineColor);
        // Whatever the controls below do not claim drags the window.
        hit(x, y, w, h, this::beginWindowMove);

        float logoSize = Math.round(20f * s);
        ClassicIcons.drawLogo(b, x + Math.round(18f * s), y + (h - logoSize) * 0.5f, logoSize, titleColor);
        // A narrow window drops the word mark so the navigation pill keeps the room.
        boolean headerCompact = w < ResponsiveLayout.HEADER_COMPACT_BREAKPOINT * s;
        if (!headerCompact) {
            b.text(x + Math.round(18f * s) + logoSize + Math.round(9f * s), GpuUiFontMetrics.topInBox(y, h, 12f * s), 12f * s,
                    titleColor, "Apexx", UiDrawCommand.TextWeight.SEMIBOLD);
        }

        float pillH = Math.round(30f * s);
        float inset = Math.round(3f * s);
        float itemH = pillH - inset * 2f;
        float gap = Math.round(2f * s);
        float[] itemWidths = {Math.round(82f * s), Math.round(74f * s), Math.round(70f * s),
                Math.round(88f * s), Math.round(92f * s), Math.round(104f * s)};
        // Cosmetics (item 3) is Premium only: free sessions get no entry at all, not a locked one.
        boolean cosmeticsNav = cosmeticsUnlocked();
        if (!cosmeticsNav) itemWidths[3] = 0f;
        float gaps = gap * (itemWidths.length - (cosmeticsNav ? 1 : 2));
        float items = gaps;
        for (float iw : itemWidths) items += iw;
        float room = Math.max(0f, w - Math.round((headerCompact ? 110f : 300f) * s));
        if (items > room && items > 0f) {
            float shrink = room / items;
            items = gaps;
            for (int i = 0; i < itemWidths.length; i++) { itemWidths[i] = Math.round(itemWidths[i] * shrink); items += itemWidths[i]; }
        }
        float pillW = items + inset * 2f;
        float px = Math.round(x + (w - pillW) * 0.5f);
        float py = Math.round(y + (h - pillH) * 0.5f);
        float round = Math.min(1f, cornerScale);
        float radius = pillH * 0.5f * round;
        if (lg) {
            // The navigation bar is its own small glass pill, like the HUD's.
            glassPane(b, px, py, pillW, pillH, radius, 1f, 0x30, false, 0);
        } else {
            b.rect(px, py, pillW, pillH, radius, argb(0x40 * panelOpacityK, 0x000000));
            b.border(px, py, pillW, pillH, radius, 1f, lineBorderColor);
        }

        float pk = Math.min(1f, frameDelta * 16f);
        if (topPillW <= 0f) { topPillX = topPillTX; topPillW = topPillTW; }
        topPillX += (topPillTX - topPillX) * pk;
        topPillW += (topPillTW - topPillW) * pk;
        float topPillA = anim("top_pill_a", topPillShow ? 1f : 0f, 14f);
        topPillShow = false;
        if (topPillA > 0.01f && topPillW > 0f) {
            float tx = topPillX, ty = py + inset, tr = itemH * 0.5f * round;
            b.gradient(tx, ty, topPillW, itemH, tr, argb(0x30 * topPillA, 0x000000), argb(0x30 * topPillA, 0x000000),
                    argb(0x90 * topPillA, 0x000000), argb(0x90 * topPillA, 0x000000));
            if (gradientAccents) {
                int a1 = accentArgb() & 0x00ffffff, a2 = accent2 & 0x00ffffff;
                b.gradient(tx, ty, topPillW, itemH, tr, argb(0x22 * topPillA, a1), argb(0x22 * topPillA, a2),
                        argb(0x10 * topPillA, a2), argb(0x10 * topPillA, a1));
            }
            b.border(tx, ty, topPillW, itemH, tr, 1f, argb(0x40 * topPillA, 0xffffff));
        }

        float ix = px + inset;
        drawTopNavigationItem(b, ix, py + inset, itemWidths[0], itemH, s, mx, my,
                "gear", "Settings", settingsDrawerOpen, () -> {
                    settingsDrawerOpen = !settingsDrawerOpen;
                    appearanceScroll = 0f;
                });
        ix += itemWidths[0] + gap;
        drawTopNavigationItem(b, ix, py + inset, itemWidths[1], itemH, s, mx, my,
                "configs", "Configs", view == View.CONFIGS, () -> {
                    view = View.CONFIGS;
                    refreshSavedConfigs();
                });
        ix += itemWidths[1] + gap;
        drawTopNavigationItem(b, ix, py + inset, itemWidths[2], itemH, s, mx, my,
                "friends", "People", view == View.FRIENDS, () -> view = View.FRIENDS);
        ix += itemWidths[2] + gap;
        if (cosmeticsNav) {
            drawTopNavigationItem(b, ix, py + inset, itemWidths[3], itemH, s, mx, my,
                    "sparkle", "Cosmetics", view == View.COSMETICS, () -> {
                        if (cosmeticsUnlocked()) view = View.COSMETICS;
                    });
            ix += itemWidths[3] + gap;
        }
        drawTopNavigationItem(b, ix, py + inset, itemWidths[4], itemH, s, mx, my,
                "crosshair", "HUD Editor", false, () -> {
                    // HUD elements draw from the live player, so the editor needs a world (not the title screen).
                    if (client != null && client.world == null) {
                        interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.toast("HUD Editor", "Join a world to edit the HUD.");
                    } else if (client != null) client.setScreen(new HudEditorScreen(this));
                });
        ix += itemWidths[4] + gap;
        drawTopNavigationItem(b, ix, py + inset, itemWidths[5], itemH, s, mx, my,
                "alert", "Self-Destruct", view == View.SELF_DESTRUCT, () -> {
                    view = View.SELF_DESTRUCT;
                    settingsDrawerOpen = false;
                    sdArmed = false;
                });

        // Rectangles the first-run tour points at (Settings, Configs, People, HUD Editor, Self-Destruct);
        // Cosmetics (item 3) has no tour step.
        float navX = px + inset;
        int[] tourSlot = {0, 1, 2, -1, 3, 4};
        for (int i = 0; i < itemWidths.length; i++) {
            int slot = tourSlot[i];
            if (slot >= 0 && slot < tourNavRects.length) {
                tourNavRects[slot][0] = navX; tourNavRects[slot][1] = py + inset;
                tourNavRects[slot][2] = itemWidths[i]; tourNavRects[slot][3] = itemH;
            }
            if (itemWidths[i] > 0f) navX += itemWidths[i] + gap;
        }

        // "What's new" (devlog) on the right of the header, with a dot while there is an unread entry.
        float wnSize = 9.5f * s;
        float wnW = Math.round(GpuUiFontMetrics.textWidth("What's new", wnSize, UiDrawCommand.TextWeight.SEMIBOLD) + 24f * s);
        float wnH = Math.round(24f * s);
        float wnX = Math.round(x + w - wnW - 14f * s), wnY = Math.round(y + (h - wnH) * 0.5f);
        if (wnX > px + pillW + 10f * s) {
            boolean wnHover = hovered(mx, my, wnX, wnY, wnW, wnH);
            b.rect(wnX, wnY, wnW, wnH, wnH * 0.5f * Math.min(1f, cornerScale), controlFill(wnHover ? 0x30 : 0x16));
            b.border(wnX, wnY, wnW, wnH, wnH * 0.5f * Math.min(1f, cornerScale), 1f, lineBorderColor);
            textCentered(b, wnX + wnW * 0.5f, wnY + wnH * 0.5f, wnSize, wnHover ? textColor : mutedColor,
                    "What's new", UiDrawCommand.TextWeight.SEMIBOLD);
            if (WhatsNewPanel.hasUnread()) {
                b.circle(wnX + wnW - 5f * s, wnY + 5f * s, 3.6f * s, accentArgb());
            }
            hit(wnX, wnY, wnW, wnH, whatsNew::open);
        }
    }

    // ---- window move / resize ------------------------------------------------------------------

    private void beginWindowMove() {
        windowDrag = DRAG_MOVE;
        dragStartOffX = windowOffX;
        dragStartOffY = windowOffY;
    }

    private void beginWindowResize(int edges) {
        windowDrag = edges;
        dragStartL = lastWinX;
        dragStartT = lastWinY;
        dragStartW = lastWinW;
        dragStartH = lastWinH;
    }

    /** Which window edges (EDGE_* bits) lie under the pointer; the grab band straddles the border. */
    private static int edgesAt(float mx, float my, float x, float y, float w, float h, float s) {
        float band = Math.max(6f, Math.round(7f * s));
        float outer = band * 0.5f, inner = band * 0.6f;
        if (mx < x - outer || mx > x + w + outer || my < y - outer || my > y + h + outer) return 0;
        int mask = 0;
        if (mx <= x + inner) mask |= EDGE_L;
        else if (mx >= x + w - inner) mask |= EDGE_R;
        if (my <= y + inner) mask |= EDGE_T;
        else if (my >= y + h - inner) mask |= EDGE_B;
        return mask;
    }

    private void registerWindowZones(float x, float y, float w, float h, float s, float mx, float my) {
        float band = Math.max(6f, Math.round(7f * s));
        float outer = band * 0.5f, inner = band * 0.6f, corner = band * 2.4f;
        // Edges first, corners last (later hits win), so a corner grabs both axes.
        hit(x - outer, y + corner, outer + inner, h - corner * 2f, () -> beginWindowResize(EDGE_L));
        hit(x + w - inner, y + corner, outer + inner, h - corner * 2f, () -> beginWindowResize(EDGE_R));
        hit(x + corner, y - outer, w - corner * 2f, outer + inner, () -> beginWindowResize(EDGE_T));
        hit(x + corner, y + h - inner, w - corner * 2f, outer + inner, () -> beginWindowResize(EDGE_B));
        hit(x - outer, y - outer, corner + outer, corner + outer, () -> beginWindowResize(EDGE_L | EDGE_T));
        hit(x + w - corner, y - outer, corner + outer, corner + outer, () -> beginWindowResize(EDGE_R | EDGE_T));
        hit(x - outer, y + h - corner, corner + outer, corner + outer, () -> beginWindowResize(EDGE_L | EDGE_B));
        hit(x + w - corner, y + h - corner, corner + outer, corner + outer, () -> beginWindowResize(EDGE_R | EDGE_B));
        int cursorEdges = windowDrag == DRAG_MOVE ? DRAG_MOVE
                : (windowDrag >= EDGE_L && windowDrag < DRAG_MOVE) ? windowDrag
                : edgesAt(mx, my, x, y, w, h, s);
        updateResizeCursor(cursorEdges);
    }

    /** Applies a pointer position (framebuffer pixels) to the window being moved or resized. */
    private void dragWindow(double mx, double my) {
        if (windowDrag == 0 || lastFitS <= 0f) return;
        float s = lastFitS;
        float ddx = (float) (mx - pressX), ddy = (float) (my - pressY);
        if (windowDrag == DRAG_MOVE) {
            windowOffX = dragStartOffX + ddx / s;
            windowOffY = dragStartOffY + ddy / s;
            return;
        }
        float left = dragStartL, top = dragStartT, right = dragStartL + dragStartW, bottom = dragStartT + dragStartH;
        if ((windowDrag & EDGE_L) != 0) left += ddx;
        if ((windowDrag & EDGE_R) != 0) right += ddx;
        if ((windowDrag & EDGE_T) != 0) top += ddy;
        if ((windowDrag & EDGE_B) != 0) bottom += ddy;
        float margin = Math.round(14f * s);
        float maxW = Math.max(minWinW * s, Math.min(ResponsiveLayout.MAX_WINDOW_W * s, lastSw - margin * 2f - lastPanelsW * s));
        float maxH = Math.max(MIN_WINDOW_H * s, Math.min(ResponsiveLayout.MAX_WINDOW_H * s, lastSh - margin * 2f));
        float newW = Math.max(minWinW * s, Math.min(maxW, right - left));
        float newH = Math.max(MIN_WINDOW_H * s, Math.min(maxH, bottom - top));
        if ((windowDrag & EDGE_L) != 0) left = right - newW; else right = left + newW;
        if ((windowDrag & EDGE_T) != 0) top = bottom - newH; else bottom = top + newH;
        windowW = newW / s;
        windowH = newH / s;
        float groupW = newW + lastPanelsW * s;
        windowOffX = (left - lastDrawerOffsetPx - (lastSw - groupW) * 0.5f) / s;
        windowOffY = (top - (lastSh - newH) * 0.5f) / s;
    }

    /** Move/resize cursors while hovering the window border or dragging it; restored to the arrow elsewhere. */
    private void updateResizeCursor(int edges) {
        if (client == null || client.getWindow() == null || ClickGUIModule.isSsProof()) return;
        int shape;
        if (edges == DRAG_MOVE) {
            // Four-way move cursor while dragging the window by its header.
            shape = GLFW.GLFW_RESIZE_ALL_CURSOR;
        } else if (edges == 0) {
            shape = 0;
        } else {
            boolean horizontal = (edges & (EDGE_L | EDGE_R)) != 0, vertical = (edges & (EDGE_T | EDGE_B)) != 0;
            if (horizontal && vertical) {
                // Diagonal cursors so each corner points the way it stretches.
                boolean nwse = ((edges & EDGE_L) != 0 && (edges & EDGE_T) != 0)
                        || ((edges & EDGE_R) != 0 && (edges & EDGE_B) != 0);
                shape = nwse ? GLFW.GLFW_RESIZE_NWSE_CURSOR : GLFW.GLFW_RESIZE_NESW_CURSOR;
            } else if (horizontal) {
                shape = GLFW.GLFW_HRESIZE_CURSOR;
            } else {
                shape = GLFW.GLFW_VRESIZE_CURSOR;
            }
        }
        if (shape == cursorShape) return;
        cursorShape = shape;
        try {
            long handle = client.getWindow().getHandle();
            GLFW.glfwSetCursor(handle, standardCursor(shape));
        } catch (Throwable ignored) {
            // A missing cursor is cosmetic; resizing still works.
        }
    }

    /** A cached standard GLFW cursor handle for {@code shape} (0 = default arrow), created on first use. */
    private static long standardCursor(int shape) {
        if (shape == 0) return 0L;
        Long cached = STANDARD_CURSORS.get(shape);
        if (cached != null) return cached;
        long handle;
        try {
            handle = GLFW.glfwCreateStandardCursor(shape);
        } catch (Throwable ignored) {
            handle = 0L; // fall back to the arrow; cache the miss so we do not retry every frame
        }
        STANDARD_CURSORS.put(shape, handle);
        return handle;
    }

    private void drawTopNavigationItem(UiDisplayList.Builder b, float x, float y, float w, float h,
                                       float s, float mx, float my, String icon, String label,
                                       boolean active, Runnable action) {
        boolean hover = hovered(mx, my, x, y, w, h);
        float radius = h * 0.5f * Math.min(1f, cornerScale);
        if (active) {
            topPillShow = true; topPillTX = x; topPillTW = w;
        } else if (hover) {
            b.rect(x, y, w, h, radius, hoverWash(0x14));
        }

        // Text-only: every item is already named, so an icon beside it would just repeat the label.
        float textSize = 9.5f * s;
        float textW = GpuUiFontMetrics.textWidth(label, textSize, UiDrawCommand.TextWeight.SEMIBOLD);
        if (textW > w - 4f * s && w > 8f * s) {
            // A squeezed pill (narrow window): shrink the label rather than let it spill over its neighbours.
            textSize *= (w - 4f * s) / textW;
            textW = GpuUiFontMetrics.textWidth(label, textSize, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        float textX = x + Math.max(4f * s, (w - textW) * 0.5f);
        b.text(textX, GpuUiFontMetrics.topInBox(y, h, textSize), textSize,
                active ? accentArgb() : hover ? textColor : mutedColor, label, UiDrawCommand.TextWeight.SEMIBOLD);
        hit(x, y, w, h, action);
    }

    private void drawToggleSwitch(UiDisplayList.Builder b, String id, float tgX, float tgY,
                                  float tgW, float tgH, boolean on, float s) {
        float anim = anim("tg_" + id, on ? 1f : 0f, 22f);
        // Every dimension derives from the track's own box, snapped to whole pixels: the track is
        // always a full capsule and the knob a true circle centred in it, whatever size is asked for.
        tgX = Math.round(tgX);
        tgY = Math.round(tgY);
        tgW = Math.round(tgW);
        tgH = Math.round(tgH);
        float r = tgH * 0.5f;
        float trackR = r * Math.min(1f, cornerScale);
        // Off track, then the on colour (or the highlight gradient) fading in over it.
        b.rect(tgX, tgY, tgW, tgH, trackR, toggleOffColor);
        accentFill(b, tgX, tgY, tgW, tgH, trackR, anim, toggleOnColor);
        float knobR = r - Math.max(1f, Math.round(2f * s));
        float knobCx = tgX + r + (tgW - tgH) * anim;
        float knobCy = tgY + r;
        b.circle(knobCx, knobCy + Math.max(1f, 0.5f * s), knobR, 0x30000000);
        b.circle(knobCx, knobCy, knobR, 0xfff4f6f8);
    }

    /** Draws a single line of text with its capitals vertically centred on {@code centerY}. */
    private static void textMid(UiDisplayList.Builder b, float x, float centerY, float size, int color, String text,
                                UiDrawCommand.TextWeight weight) {
        b.text(x, GpuUiFontMetrics.topForCenter(centerY, size), size, color, text, weight);
    }

    /** As {@link #textMid}, also centred horizontally on {@code centerX}. */
    private static void textCentered(UiDisplayList.Builder b, float centerX, float centerY, float size, int color,
                                     String text, UiDrawCommand.TextWeight weight) {
        float tw = GpuUiFontMetrics.textWidth(text, size, weight);
        b.text(centerX - tw * 0.5f, GpuUiFontMetrics.topForCenter(centerY, size), size, color, text, weight);
    }

    private void drawFloatingTooltip(UiDisplayList.Builder b, float mx, float my, float sw, float sh, float s) {
        float titleSize = 9.5f * s, descSize = 8.5f * s, pad = 8f * s, lineH = 11f * s;
        float maxW = Math.min(240f * s, sw - 40f);
        List<String> lines = wrapText(hoveredTooltipDesc, maxW - pad * 2f, descSize, 10);
        if (lines.isEmpty()) return;
        boolean hasTitle = hoveredTooltipTitle != null && !hoveredTooltipTitle.isBlank();
        float textW = hasTitle ? GpuUiFontMetrics.textWidth(hoveredTooltipTitle, titleSize, UiDrawCommand.TextWeight.SEMIBOLD) : 0f;
        for (String line : lines) textW = Math.max(textW, GpuUiFontMetrics.textWidth(line, descSize));
        float ttW = Math.min(maxW, textW + pad * 2f);
        float ttH = pad * 2f - 2f * s + (hasTitle ? 13f * s : 0f) + lines.size() * lineH;

        float tx = mx + 12f * s, ty = my + 14f * s;
        if (tx + ttW > sw - 10f) tx = mx - ttW - 8f * s;
        if (ty + ttH > sh - 10f) ty = my - ttH - 8f * s;
        tx = Math.max(6f, tx);
        ty = Math.max(6f, ty);

        float ttR = 8f * s * cornerScale;
        if (lg) {
            // A darker pane than the window so the text reads over anything.
            glassPane(b, tx, ty, ttW, ttH, ttR, 1f, 0xb4, true, 0);
        } else {
            panelShadow(b, tx, ty, ttW, ttH, ttR, s, 1f);
            b.glass(tx, ty, ttW, ttH, ttR, 10f, 0xe00a0e15);
            if (showRims) b.border(tx, ty, ttW, ttH, ttR, Math.max(1f, s * 0.8f), outerBorder);
        }
        float cy = ty + pad - 1f * s;
        if (hasTitle) {
            b.text(tx + pad, cy, titleSize, titleColor, hoveredTooltipTitle, UiDrawCommand.TextWeight.SEMIBOLD);
            cy += 13f * s;
        }
        for (int i = 0; i < lines.size(); i++) b.text(tx + pad, cy + i * lineH, descSize, mutedColor, lines.get(i));
    }

    // =========================================================================
    // 1. LEFT RAIL (.side, .brand, .nav, .nav-bottom)
    // =========================================================================

    private void drawRail(UiDisplayList.Builder b, float x, float y, float w, float h,
                          boolean compact, float s, float mx, float my) {
        // Navigation zone (the brand mark lives in the window header).
        // Every destination (categories, Scripts, Macros) is the same pixel-snapped box on the same
        // pitch, so the icons are evenly spaced and the selection pill has identical padding on each.
        float itemH = Math.round(34f * s);
        float pitch = itemH + Math.round(4f * s);
        float itemW = compact ? Math.round(Math.min(w - 16f * s, 44f * s)) : Math.round(w - 24f * s);
        float itemX = Math.round(x + (w - itemW) * 0.5f);
        float navTop = Math.round(y + 12f * s);
        float footY = Math.round(y + h - 12f * s - itemH);

        float cy = navTop;

        // One floating glass selection pill glides between destinations.
        // The player's folders follow the built-in categories, then a "New folder" entry while there is room.
        GuiPrefs prefs = guiPrefs();
        List<GuiPrefs.CustomCategory> folders = prefs.customCategories();
        if (folderId != null && prefs.customCategory(folderId) == null) folderId = null;
        railFolderRects.clear();
        boolean canCreate = prefs.canCreateCategory();
        int navCount = NAV_CATEGORIES.length + folders.size() + (canCreate ? 1 : 0);
        int activeIdx = -1;
        if (folderId == null) {
            for (int i = 0; i < NAV_CATEGORIES.length; i++) if (NAV_CATEGORIES[i] == category) activeIdx = i;
        } else {
            for (int i = 0; i < folders.size(); i++) {
                if (folders.get(i).id().equals(folderId)) activeIdx = NAV_CATEGORIES.length + i;
            }
        }
        boolean onCategory = view == View.MODULES && activeIdx >= 0;
        // Scripts and Macros are Premium only: free sessions get no rail entries at all (and the room goes to categories).
        boolean studioNav = studioUnlocked();
        boolean onMacros = studioNav && view == View.MACROS;
        boolean onScripts = studioNav && view == View.SCRIPTS;
        float scriptsY = footY - pitch;
        // Categories shrink to fit above the Scripts divider on a short window instead of overlapping it.
        float catRoom = (studioNav ? scriptsY - Math.round(12f * s) : footY + itemH) - navTop;
        float minPitch = Math.round((navCount > NAV_CATEGORIES.length ? 16f : 20f) * s);
        float catPitch = Math.max(minPitch, Math.min(pitch, (float) Math.floor(catRoom / navCount)));
        float catItemH = Math.min(itemH, catPitch - Math.max(1f, Math.round(2f * s)));
        float pillTargetY = onCategory ? cy + activeIdx * catPitch : onScripts ? scriptsY : footY;
        float pillTargetH = onCategory ? catItemH : itemH;
        if (Float.isNaN(railPillY)) { railPillY = pillTargetY; railPillH = pillTargetH; }
        float rk = Math.min(1f, frameDelta * 15f);
        railPillY += (pillTargetY - railPillY) * rk;
        railPillH += (pillTargetH - railPillH) * rk;
        float railPillA = anim("rail_pill_a", onCategory || onMacros || onScripts ? 1f : 0f, 14f);
        if (railPillA > 0.01f) glassTile(b, itemX, railPillY, itemW, railPillH, 10f * s * cornerScale, s, 0.25f, railPillA);

        // Keep all user-facing Mace categories reachable within the rail.
        for (Category c : NAV_CATEGORIES) {
            boolean active = view == View.MODULES && folderId == null && category == c;
            drawNavItem(b, itemX, cy, itemW, catItemH, c.name,
                    ClassicIcons.forCategory(c.name()), active, compact, s, mx, my, () -> openCategory(c, null));
            cy += catPitch;
        }
        for (GuiPrefs.CustomCategory folder : folders) {
            boolean active = view == View.MODULES && folder.id().equals(folderId);
            String id = folder.id();
            String label = UiKit.fit(folder.name(), Math.max(0f, itemW - 58f * s), 10f * s,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            drawNavItem(b, itemX, cy, itemW, catItemH, label, "box", active, compact, s, mx, my,
                    () -> openCategory(category, id));
            if (hovered(mx, my, itemX, cy, itemW, catItemH)) {
                hoveredTooltipTitle = folder.name();
                hoveredTooltipDesc = "Your folder (" + folder.modules().size()
                        + (folder.modules().size() == 1 ? " module" : " modules")
                        + "). Right-click to rename or delete it. Right-click a module card to add it to a folder.";
            }
            hitModuleCardRight(itemX, cy, itemW, catItemH, () -> openFolderMenu(id));
            railFolderRects.add(new FolderTarget(id, itemX, cy, itemW, catItemH));
            if (dragModule != null && dragPointerX >= itemX && dragPointerX <= itemX + itemW
                    && dragPointerY >= cy && dragPointerY <= cy + catItemH) {
                // A card dragged over a folder adds itself there on release.
                b.border(itemX, cy, itemW, catItemH, 10f * s * cornerScale, Math.max(1.5f, 1.6f * s), accentArgb());
            }
            cy += catPitch;
        }
        if (canCreate) {
            // "New folder": a plus where the icon goes, so it never reads as another category.
            boolean hover = hovered(mx, my, itemX, cy, itemW, catItemH);
            if (hover) b.rect(itemX, cy, itemW, catItemH, 10f * s * cornerScale, hoverWash(0x14));
            float plusX = compact ? itemX + itemW * 0.5f : itemX + 14f * s + Math.round(17f * s) * 0.5f;
            textCentered(b, plusX, cy + catItemH * 0.5f, 13f * s, hover ? textColor : muted2Color, "+",
                    UiDrawCommand.TextWeight.SEMIBOLD);
            if (!compact) {
                b.text(itemX + 38f * s, GpuUiFontMetrics.topInBox(cy, catItemH, 10f * s), 10f * s,
                        hover ? textColor : muted2Color, "New folder", UiDrawCommand.TextWeight.SEMIBOLD);
            }
            if (hover) {
                hoveredTooltipTitle = "New folder";
                hoveredTooltipDesc = "Make your own category. Add modules to it by right-clicking their cards.";
            }
            hit(itemX, cy, itemW, catItemH, this::createFolder);
        }

        // Global destinations live in the top pill; keep Macros pinned on the rail.
        if (!studioNav) {
            tourScriptsRect[2] = 0f;
            tourMacrosRect[2] = 0f;
            return;
        }
        if (showSeparators) b.rect(itemX, scriptsY - Math.round(8f * s), itemW, Math.max(1f, Math.round(s)), 0, lineColor);
        tourScriptsRect[0] = itemX; tourScriptsRect[1] = scriptsY; tourScriptsRect[2] = itemW; tourScriptsRect[3] = itemH;
        tourMacrosRect[0] = itemX; tourMacrosRect[1] = footY; tourMacrosRect[2] = itemW; tourMacrosRect[3] = itemH;
        drawNavItem(b, itemX, scriptsY, itemW, itemH, "Scripts", "macros", view == View.SCRIPTS, compact, s, mx, my, () -> {
            view = View.SCRIPTS;
            scriptView.refresh();
        });
        drawNavItem(b, itemX, footY, itemW, itemH, "Macros", "key", view == View.MACROS, compact, s, mx, my, () -> {
            view = View.MACROS;
            macroView.refresh();
        });
    }

    private static String navDescription(String title) {
        return switch (title) {
            case "Combat" -> "Fighting: aim, clicking, crystals, mace and sword tools.";
            case "Movement" -> "Moving around: lunges, dashes and movement helpers.";
            case "Render" -> "Visuals: ESP, nametags, HUD and effects.";
            case "Player" -> "Inventory and utility: totems, clutches, chest tools.";
            case "Friends" -> "People you never target.";
            case "Enemies" -> "People you always prioritise.";
            case "Misc" -> "Everything else.";
            case "Blatant" -> "Obvious modules that are easy for anticheats to spot.";
            case "Macros" -> "Build macros: a trigger plus a short sequence of slot swaps and actions.";
            case "Scripts" -> "Write your own modules in the Apexx scripting language.";
            default -> title;
        };
    }

    private void drawNavItem(UiDisplayList.Builder b, float ix, float iy, float iw, float ih,
                             String title, String icon, boolean active, boolean compact, float s,
                             float mx, float my, Runnable action) {
        boolean hover = hovered(mx, my, ix, iy, iw, ih);
        // Icon-only rail: name the destination on hover so the icons are never a guessing game.
        if (compact && hover) {
            hoveredTooltipTitle = title;
            hoveredTooltipDesc = navDescription(title);
        }
        float iconSize = Math.round(17f * s);
        float iconY = Math.round(iy + (ih - iconSize) * 0.5f);
        float iconX = Math.round(ix + (compact ? (iw - iconSize) * 0.5f : 14f * s));
        float textY = GpuUiFontMetrics.topInBox(iy, ih, 10f * s);
        if (active) {
            ClassicIcons.draw(b, icon, iconX, iconY, iconSize, accentArgb());
            if (!compact) {
                b.text(ix + 38f * s, textY, 10f * s, titleColor, title, UiDrawCommand.TextWeight.SEMIBOLD);
                b.circle(ix + iw - 14f * s, iy + ih * 0.5f, 2.5f * s, accentArgb());
            }
        } else {
            if (hover) {
                b.rect(ix, iy, iw, ih, 10f * s * cornerScale, hoverWash(0x14));
            }
            ClassicIcons.draw(b, icon, iconX, iconY, iconSize, hover ? textColor : mutedColor);
            if (!compact) {
                b.text(ix + 38f * s, textY, 10f * s, hover ? textColor : mutedColor, title, UiDrawCommand.TextWeight.SEMIBOLD);
            }
        }
        // "icon | Name": a thin divider between the glyph and the label on the expanded rail.
        if (!compact && showSeparators) {
            b.rect(ix + 32f * s, iy + ih * 0.28f, Math.max(1f, s), ih * 0.44f, 0, lineColor);
        }
        hit(ix, iy, iw, ih, action);
    }

    // ---- categories, folders, context menu, card drag -------------------------------------------

    /** A folder's rail entry this frame, so a dragged card can be dropped onto it. */
    private record FolderTarget(String id, float x, float y, float w, float h) { }
    private final List<FolderTarget> railFolderRects = new ArrayList<>();

    /** Shows a built-in category ({@code folder} null) or a custom folder in the module list. */
    private void openCategory(Category c, String folder) {
        if (!java.util.Objects.equals(folderId, folder)) {
            commitFolderRename();
            deleteFolderArmed = false;
        }
        view = View.MODULES;
        category = c;
        folderId = folder;
        animateModuleInspectorTo(0f);
        // A search or filter stays: picking a category narrows it to that category
        // (the "in <category>" chip widens it again); otherwise this just browses.
        searchScoped = !searchField.isEmpty() || moduleFilter != ModuleFilter.ALL;
        navIndex = -1;
        catalogScroll.reset();
        restoreCatalogScroll = -1f;
        moduleScroll = 0;
        moduleCardScroll = 0;
        moduleScrollRemainder = 0;
        moduleCardScrollRemainder = 0;
        moduleInspectorScrollRemainder = 0;
        settingsScroll = 0;
    }

    /** "New folder": creates one, opens it and puts its name in edit mode. Returns its id (null at the limit). */
    private String createFolder() {
        String id = guiPrefs().createCategory(null);
        if (id == null) return null;
        openCategory(category, id);
        startFolderRename(id);
        return id;
    }

    private void deleteFolder(String id) {
        if (id == null) return;
        if (id.equals(renamingFolderId)) {
            renamingFolderId = null;
            if (kitFocus == folderNameField) kitFocus = null;
        }
        guiPrefs().deleteCategory(id);
        deleteFolderArmed = false;
        if (id.equals(folderId)) openCategory(category, null);
        catalogCacheKey = "";
    }

    private void startFolderRename(String id) {
        GuiPrefs.CustomCategory folder = guiPrefs().customCategory(id);
        if (folder == null) return;
        commitFolderRename();
        kitHost.focus(folderNameField);
        renamingFolderId = id;
        folderNameField.setValue(folder.name());
        folderNameField.selectAll();
    }

    /** Saves the name being edited (blank keeps the old name). Safe to call when nothing is being renamed. */
    private void commitFolderRename() {
        String id = renamingFolderId;
        if (id == null) return;
        renamingFolderId = null;
        if (kitFocus == folderNameField) kitFocus = null;
        guiPrefs().renameCategory(id, folderNameField.value());
    }

    private void openContextMenu(String title, List<MenuItem> items) {
        if (items == null || items.isEmpty()) return;
        contextTitle = title;
        contextItems = items;
        contextX = (float) pressX;
        contextY = (float) pressY;
    }

    private void closeContextMenu() {
        contextItems = null;
        contextTitle = null;
    }

    /** Right-click on a module card: settings, favourite, and membership of each folder. */
    private void openModuleMenu(Module m) {
        GuiPrefs prefs = guiPrefs();
        List<MenuItem> items = new ArrayList<>();
        boolean open = selected == m && moduleInspectorTargetProgress > 0f;
        items.add(new MenuItem(open ? "Close settings" : "Settings", false, true, false, () -> toggleModuleInspector(m)));
        items.add(new MenuItem(m.isFavorite() ? "Unfavorite" : "Favorite", false, true, false, () -> {
            m.setFavorite(!m.isFavorite());
            catalogCacheKey = "";
        }));
        List<GuiPrefs.CustomCategory> folders = prefs.customCategories();
        items.add(MenuItem.divider());
        items.add(new MenuItem("Folders", false, false, false, null));
        for (GuiPrefs.CustomCategory folder : folders) {
            String id = folder.id();
            items.add(new MenuItem(folder.name(), prefs.isMember(id, m.getName()), true, false, () -> {
                prefs.toggleMember(id, m.getName());
                catalogCacheKey = "";
            }));
        }
        if (prefs.canCreateCategory()) {
            items.add(new MenuItem("New folder with this module", false, true, false, () -> {
                String id = prefs.createCategory(null);
                if (id == null) return;
                prefs.toggleMember(id, m.getName());
                openCategory(category, id);
                startFolderRename(id);
            }));
        } else if (folders.isEmpty()) {
            items.add(new MenuItem("No folders", false, false, false, () -> { }));
        }
        openContextMenu(m.getName(), items);
    }

    /** Right-click on a folder in the rail. */
    private void openFolderMenu(String id) {
        GuiPrefs.CustomCategory folder = guiPrefs().customCategory(id);
        if (folder == null) return;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Open", false, true, false, () -> openCategory(category, id)));
        items.add(new MenuItem("Rename", false, true, false, () -> {
            openCategory(category, id);
            startFolderRename(id);
        }));
        items.add(MenuItem.divider());
        items.add(new MenuItem("Delete folder", false, true, true, () -> deleteFolder(id)));
        openContextMenu(folder.name(), items);
    }

    /** Right-click on a setting row: reset to default, pin to / unpin from Quick Settings. */
    private void openSettingMenu(Setting setting) {
        if (setting == null) return;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Reset to default", false, setting.isModified(), false, setting::resetToDefault));
        Module owner = pinnable(setting) ? ownerOf(setting) : null;
        if (owner != null) {
            GuiPrefs prefs = guiPrefs();
            boolean pinned = prefs.isPinned(owner.getName(), setting.getName());
            String label = pinned ? "Unpin from Quick Settings"
                    : prefs.canPin() ? "Pin to Quick Settings" : "Quick Settings is full";
            items.add(new MenuItem(label, false, pinned || prefs.canPin(), false,
                    () -> prefs.togglePin(owner.getName(), setting.getName())));
        }
        openContextMenu(setting.getName(), items);
    }

    /** The module that registered {@code setting} (identity), or null. */
    private Module ownerOf(Setting setting) {
        if (selected != null && containsSame(selected.getSettings(), setting)) return selected;
        ClickGUIModule gui = clickGuiModule();
        if (gui != null && containsSame(gui.getSettings(), setting)) return gui;
        ModuleManager manager = moduleManager();
        if (manager == null) return null;
        for (Module m : manager.getModules()) {
            if (m != null && containsSame(m.getSettings(), setting)) return m;
        }
        return null;
    }

    private static boolean containsSame(List<? extends Setting> list, Setting setting) {
        if (list == null) return false;
        for (Setting st : list) if (st == setting) return true;
        return false;
    }

    private void drawContextMenu(UiDisplayList.Builder b, float sw, float sh, float s, float mx, float my) {
        List<MenuItem> items = contextItems;
        if (items == null) return;
        // Modal while open: anything outside closes it, nothing underneath reacts.
        hits.clear();
        moduleCardRightHits.clear();
        hoveredTooltipTitle = null;
        hoveredTooltipDesc = null;
        boolean savedClip = clippingHits, savedReveal = inspectorRevealHitClip;
        clippingHits = false;
        inspectorRevealHitClip = false;
        hit(0, 0, sw, sh, this::closeContextMenu);
        hitModuleCardRight(0, 0, sw, sh, this::closeContextMenu);

        float itemH = Math.round(26f * s), headH = Math.round(14f * s), divH = Math.round(9f * s);
        float pad = Math.round(5f * s), size = 10.5f * s;
        float titleH = contextTitle == null ? 0f : Math.round(24f * s);
        float textW = contextTitle == null ? 0f
                : GpuUiFontMetrics.textWidth(contextTitle, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD);
        float h = pad * 2f + titleH;
        for (MenuItem item : items) {
            if (item.label() == null) { h += divH; continue; }
            boolean header = item.action() == null;
            h += header ? headH + Math.round(4f * s) : itemH;
            textW = Math.max(textW, GpuUiFontMetrics.textWidth(item.label(), header ? 8.5f * s : size,
                    UiDrawCommand.TextWeight.SEMIBOLD));
        }
        float w = Math.round(Math.min(Math.max(170f * s, textW + 52f * s), Math.max(170f * s, sw * 0.5f)));
        // Opened so the first entry sits under the pointer: right-click, then click again in place.
        float x = Math.round(contextX + 2f * s);
        float y = Math.round(contextY - pad - titleH - itemH * 0.5f);
        x = Math.max(6f, Math.min(x, sw - w - 6f));
        y = Math.max(6f, Math.min(y, sh - h - 6f));

        float r = 10f * s * cornerScale;
        if (lg) {
            glassPane(b, x, y, w, h, r, 1f, 0xc0, true, 0);
        } else {
            panelShadow(b, x, y, w, h, r, s, 1f);
            b.glass(x, y, w, h, r, 10f, 0xf00a0e15);
            if (showRims) b.border(x, y, w, h, r, Math.max(1f, s * 0.8f), outerBorder);
        }
        // Clicks on the menu's own padding do nothing (instead of closing it).
        hit(x, y, w, h, () -> { });
        float cy = y + pad;
        if (contextTitle != null) {
            textMid(b, x + 12f * s, cy + titleH * 0.5f, 9.5f * s, mutedColor,
                    UiKit.fit(contextTitle, w - 24f * s, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            cy += titleH;
        }
        float ix = x + pad, iw = w - pad * 2f;
        for (MenuItem item : items) {
            if (item.label() == null) {
                b.rect(ix + 6f * s, cy + divH * 0.5f, iw - 12f * s, Math.max(1f, s), 0, lineColor);
                cy += divH;
                continue;
            }
            if (item.action() == null) {
                // Section label.
                cy += Math.round(4f * s);
                textMid(b, ix + 8f * s, cy + headH * 0.5f, 8.5f * s, muted2Color,
                        item.label().toUpperCase(Locale.ROOT), UiDrawCommand.TextWeight.SEMIBOLD);
                cy += headH;
                continue;
            }
            boolean hover = item.enabled() && hovered(mx, my, ix, cy, iw, itemH);
            if (hover) b.rect(ix, cy, iw, itemH, Math.max(0f, r - pad), hoverWash(0x20));
            int color = !item.enabled() ? muted2Color : item.danger() ? 0xffff8a8a : hover ? titleColor : textColor;
            if (item.checked()) {
                float icon = Math.round(12f * s);
                ClassicIcons.draw(b, "check", Math.round(ix + 8f * s), Math.round(cy + (itemH - icon) * 0.5f), icon,
                        accentArgb());
            }
            textMid(b, ix + 28f * s, cy + itemH * 0.5f, size, color,
                    UiKit.fit(item.label(), iw - 36f * s, size, UiDrawCommand.TextWeight.SEMIBOLD),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            if (item.enabled()) {
                Runnable action = item.action();
                hit(ix, cy, iw, itemH, () -> {
                    closeContextMenu();
                    action.run();
                });
            }
            cy += itemH;
        }
        clippingHits = savedClip;
        inspectorRevealHitClip = savedReveal;
    }

    private void cancelCardDrag() {
        pendingCardPress = null;
        dragModule = null;
        dragVisible = List.of();
        dragDropIndex = -1;
    }

    /** Drops the dragged card: onto a folder in the rail (adds it), or at the marked spot in the list. */
    private void commitCardDrag() {
        Module m = dragModule;
        List<Module> visible = dragVisible;
        int index = dragDropIndex;
        float px = dragPointerX, py = dragPointerY;
        cancelCardDrag();
        if (m == null) return;
        GuiPrefs prefs = guiPrefs();
        for (FolderTarget target : railFolderRects) {
            if (px >= target.x() && px <= target.x() + target.w() && py >= target.y() && py <= target.y() + target.h()) {
                if (!prefs.isMember(target.id(), m.getName())) prefs.toggleMember(target.id(), m.getName());
                catalogCacheKey = "";
                return;
            }
        }
        if (index < 0 || view != View.MODULES || !catalogReorderable) return;
        List<String> names = new ArrayList<>(visible.size());
        for (Module v : visible) names.add(v.getName());
        List<String> moved = GuiPrefs.move(names, m.getName(), index);
        if (moved.equals(names)) return;
        if (folderId != null) {
            GuiPrefs.CustomCategory folder = prefs.customCategory(folderId);
            if (folder != null) prefs.setMembers(folderId, GuiPrefs.mergeVisible(folder.modules(), moved));
        } else {
            prefs.setOrder(category.name(), GuiPrefs.mergeVisible(prefs.order(category.name()), moved));
        }
        catalogCacheKey = "";
    }

    /** The dragged card's name riding along under the pointer. */
    private void drawDragGhost(UiDisplayList.Builder b, float s) {
        Module m = dragModule;
        if (m == null) return;
        float size = 11f * s;
        float tw = GpuUiFontMetrics.textWidth(m.getName(), size, UiDrawCommand.TextWeight.SEMIBOLD);
        float h = Math.round(28f * s), w = Math.round(tw + 28f * s);
        float x = Math.round(dragPointerX + 10f * s), y = Math.round(dragPointerY - h * 0.5f);
        float r = h * 0.5f * Math.min(1f, cornerScale);
        panelShadow(b, x, y, w, h, r, s, 1f);
        b.glass(x, y, w, h, r, 10f, 0xe00a0e15);
        b.rect(x, y, w, h, r, withA(accentArgb(), 0x30));
        b.border(x, y, w, h, r, Math.max(1f, s), withA(accentArgb(), 0xa0));
        textCentered(b, x + w * 0.5f, y + h * 0.5f, size, titleColor, m.getName(), UiDrawCommand.TextWeight.SEMIBOLD);
    }

    // =========================================================================
    // 2. CENTER CATALOG (.list, .list-head, .search, .rows, .row)
    // =========================================================================

    private void drawCatalog(UiDisplayList.Builder b, float x, float y, float w, float h,
                             boolean compact, float s, float mx, float my) {
        if (!viewAllowed(view)) view = View.MODULES; // a Premium page is never shown to a free session
        switch (view) {
            case MODULES -> drawModulesCatalog(b, x, y, w, h, compact, s, mx, my);
            case CONFIGS -> configsView.draw(b, kitHost, x, y, w, h, s, mx, my);
            case MACROS -> macroView.draw(b, kitHost, x, y, w, h, s, mx, my);
            case SCRIPTS -> scriptView.draw(b, kitHost, x, y, w, h, s, mx, my);
            case FRIENDS -> peopleView.draw(b, kitHost, x, y, w, h, s, mx, my);
            case SELF_DESTRUCT -> drawSelfDestruct(b, x, y, w, h, s, mx, my);
            case COSMETICS -> cosmeticsView.draw(b, kitHost, x, y, w, h, s, mx, my);
        }
    }

    // Two-step arm on the Self-Destruct button, like the Sign Out guard, so one stray click never wipes.
    private boolean sdArmed;
    private long sdArmedAt;
    /** When the Client tab's Sign out button was first clicked (0 = not armed). */
    private long signOutArmedAt;

    /** Self-Destruct tab: the former module's options plus a two-step confirm that runs the wipe. */
    private void drawSelfDestruct(UiDisplayList.Builder b, float x, float y, float w, float h, float s,
                                  float mx, float my) {
        float padX = Math.round(22f * s);
        float cardX = x + padX;
        float cardW = w - padX * 2f;
        float top = y + Math.round(20f * s);
        b.text(cardX, top, 16f * s, textColor, "Self-Destruct", UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(cardX, top + Math.round(22f * s), 10.5f * s, mutedColor,
                UiKit.fit("Wipes the client from this machine and shuts it down. This cannot be undone.",
                        cardW, 10.5f * s));

        float contentTop = top + Math.round(44f * s);
        float contentBottom = y + h - Math.round(16f * s);
        float viewportH = Math.max(0f, contentBottom - contentTop);
        b.clipPush(cardX, contentTop, cardW, viewportH, cardRadius * s);
        clipX = cardX; clipY = contentTop; clipW = cardW; clipH = viewportH;
        clippingHits = true;

        float cy = contentTop;
        List<Setting> opts = new ArrayList<>();
        ClickGUIModule sdOwner = clickGuiModule();
        for (Setting setting : new Setting[]{ ClickGUIModule.sdOceanSafe, ClickGUIModule.sdWipeConfig,
                ClickGUIModule.sdDeleteJar, ClickGUIModule.sdReplaceJar, ClickGUIModule.sdDecoyUrl }) {
            if (setting == null) continue;
            // Ocean Safe hides the disk options; Decoy URL only matters while Replace Jar is on.
            ClassicSettingLayout.Row row = sdOwner == null ? null : ClassicSettingLayout.row(sdOwner, setting);
            if (row != null && !row.visible()) continue;
            if (setting == ClickGUIModule.sdDecoyUrl && ClickGUIModule.sdDeleteJar != null
                    && ClickGUIModule.sdDeleteJar.isEnabled()) continue;
            opts.add(setting);
        }
        cy = drawSettingGroup(b, "Options", opts, cardX, cy, cardW, s, mx, my);

        cy += 6f * s;
        float btnH = Math.round(46f * s);
        boolean armed = sdArmed && nanoTime() - sdArmedAt < 3_500_000_000L;
        b.rect(cardX, cy, cardW, btnH, cardRadius * s, armed ? withA(0xffff5555, 0x33) : cardBg);
        b.border(cardX, cy, cardW, btnH, cardRadius * s, 1f, armed ? 0xffff6b6b : withA(0xffff6b6b, 0x30));
        float mid = cy + btnH * 0.5f;
        textCentered(b, cardX + cardW * 0.5f, mid, 12.5f * s, armed ? 0xffff6b6b : 0xffff8a8a,
                armed ? "Click again to self-destruct" : "Self-Destruct Now", UiDrawCommand.TextWeight.SEMIBOLD);
        hit(cardX, cy, cardW, btnH, () -> {
            if (sdArmed && nanoTime() - sdArmedAt < 3_500_000_000L) {
                sdArmed = false;
                triggerSelfDestruct();
            } else {
                sdArmed = true;
                sdArmedAt = nanoTime();
            }
        });

        clippingHits = false;
        b.clipPop();
    }

    private static int withA(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00ffffff);
    }

    /** Closes the menu and runs the self-destruct with the tab's options (shared with the SelfDestruct module). */
    private void triggerSelfDestruct() {
        if (client != null) client.setScreen(null);
        interstellarTikiTiks.apexx.client.module.implementation.SelfDestruct.runConfigured();
    }

    /**
     * "Bedwars is new" notice at the top of the Bedwars tab; returns the y where the module grid starts.
     * Continue hides it for this session, "Don't show again" for good (a hidden ClickGUI setting, so it
     * persists and syncs with the config). It slides/fades in and folds away when dismissed.
     */
    private float drawBedwarsNotice(UiDisplayList.Builder b, float x, float y, float w, float s, float mx, float my) {
        boolean hiddenForGood = ClickGUIModule.bedwarsNoticeHidden != null && ClickGUIModule.bedwarsNoticeHidden.isEnabled();
        float shown = anim("bedwars_notice", hiddenForGood || bedwarsNoticeContinued ? 0f : 1f, 9f);
        if (shown < 0.01f) return y;
        float e = shown * shown * (3f - 2f * shown);
        float fullH = Math.round(54f * s);
        float h = fullH * e;
        float alpha = e;
        b.clipPush(x, y, w, h, cardRadius * s);
        float cy = y - (1f - e) * 10f * s;
        float r = cardRadius * s;
        int accent = accentArgb();
        if (lg) glassPane(b, x, cy, w, fullH, r, alpha, 0x2c, false, 0);
        else b.rect(x, cy, w, fullH, r, argb(0x2e * alpha * panelOpacityK, 0x000000));
        b.rect(x, cy, w, fullH, r, argb(0x18 * alpha, accent & 0x00ffffff));
        b.border(x, cy, w, fullH, r, Math.max(1f, s * 0.8f), argb(0x70 * alpha, accent & 0x00ffffff));
        float icon = Math.round(18f * s);
        float mid = cy + fullH * 0.5f;
        ClassicIcons.draw(b, "alert", Math.round(x + 14f * s), Math.round(mid - icon * 0.5f), icon,
                argb(0xff * alpha, accent & 0x00ffffff));

        float btnH = Math.round(24f * s);
        String never = "Don't show again", cont = "Continue";
        float neverW = UiKit.buttonWidth(never, s * 0.9f), contW = UiKit.buttonWidth(cont, s * 0.9f);
        float contX = Math.round(x + w - 12f * s - contW), btnY = Math.round(mid - btnH * 0.5f);
        float neverX = Math.round(contX - 8f * s - neverW);
        float textX = x + 14f * s + icon + 12f * s;
        float textW = Math.max(0f, neverX - 12f * s - textX);
        textMid(b, textX, mid - 7f * s, 11.5f * s, argb(0xff * alpha, textColor & 0x00ffffff),
                UiKit.fit("Bedwars is new", textW, 11.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                UiDrawCommand.TextWeight.SEMIBOLD);
        textMid(b, textX, mid + 8f * s, 9.5f * s, argb(0xff * alpha, mutedColor & 0x00ffffff),
                UiKit.fit("Bedwars is new and may currently be unstable.", textW, 9.5f * s),
                UiDrawCommand.TextWeight.REGULAR);
        if (e > 0.6f) {
            UiKit.button(b, kitHost, never, neverX, btnY, neverW, btnH, s * 0.9f, UiKit.Style.GHOST, true, mx, my, () -> {
                if (ClickGUIModule.bedwarsNoticeHidden != null) ClickGUIModule.bedwarsNoticeHidden.setEnabled(true);
            });
            UiKit.button(b, kitHost, cont, contX, btnY, contW, btnH, s * 0.9f, UiKit.Style.PRIMARY, true, mx, my,
                    () -> bedwarsNoticeContinued = true);
        }
        b.clipPop();
        return y + h + 10f * s * e;
    }

    private static final String[] FILTER_LABELS = { "All", "Enabled", "Favorites", "Bound" };

    private void drawModulesCatalog(UiDisplayList.Builder b, float x, float y, float w, float h,
                                    boolean compact, float s, float mx, float my) {
        // A search stays inside the open category (so searching "clutch" in Combat shows only Combat's, not the
        // Player and Bedwars ones too). A filter (Enabled/Favorites/Bound) still spans every category, since there
        // the whole point is to gather matching modules from anywhere.
        String query = searchField.value().trim();
        boolean searching = !query.isEmpty();
        if (!searching && moduleFilter == ModuleFilter.ALL) searchScoped = false;
        boolean crossCategory = moduleFilter != ModuleFilter.ALL && !searchScoped;
        GuiPrefs.CustomCategory folder = folderId == null ? null : guiPrefs().customCategory(folderId);
        if (folderId != null && folder == null) folderId = null;
        List<CatalogEntry> filtered = catalog(query, crossCategory);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (navIndex >= filtered.size()) navIndex = filtered.isEmpty() ? -1 : filtered.size() - 1;
        // Cards can be dragged into a new order while the list shows a whole category in its own order.
        catalogReorderable = !searching && moduleFilter == ModuleFilter.ALL && moduleSort == ModuleSort.DEFAULT
                && !crossCategory;
        List<Module> shownModules = new ArrayList<>(filtered.size());
        for (CatalogEntry e : filtered) shownModules.add(e.module());
        lastCatalogModules = shownModules;
        if (dragModule != null && (!catalogReorderable || !shownModules.contains(dragModule))) cancelCardDrag();
        if (renamingFolderId != null && (folder == null || !renamingFolderId.equals(folderId))) commitFolderRename();
        catalogDrawn = true;

        // List Header (.list-head): title, result count, scope chip on the left; sort on the right.
        float headY = y + 18f * s;
        String scopeName = folder != null ? folder.name() : category.name;
        String title = searching ? "Search" : moduleFilter != ModuleFilter.ALL ? moduleFilter.label : scopeName;
        boolean renaming = folder != null && folder.id().equals(renamingFolderId) && !searching
                && moduleFilter == ModuleFilter.ALL;
        float headMid = headY + 1f * s + 16f * s * GpuUiFontMetrics.CAP_HEIGHT * 0.5f;
        float afterTitle;
        if (renaming) {
            float fieldH = Math.round(28f * s), fieldW = Math.round(Math.min(240f * s, w * 0.45f));
            folderNameField.draw(b, kitHost, x + 14f * s, Math.round(headMid - fieldH * 0.5f), fieldW, fieldH, s);
            afterTitle = x + 14f * s + fieldW + 10f * s;
        } else {
            b.text(x + 20f * s, headY + 1f * s, 16f * s, titleColor, title, UiDrawCommand.TextWeight.SEMIBOLD);
            afterTitle = x + 26f * s + GpuUiFontMetrics.textWidth(title, 16f * s, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        if (searching || moduleFilter != ModuleFilter.ALL) {
            String count = filtered.size() == 1 ? "1 result" : filtered.size() + " results";
            textMid(b, afterTitle, headMid, 10f * s, muted2Color, count, UiDrawCommand.TextWeight.REGULAR);
            afterTitle += GpuUiFontMetrics.textWidth(count, 10f * s) + 10f * s;
            if (searchScoped) {
                // "in Combat  x": the search is narrowed to the open category; click to widen it again.
                String scope = "in " + scopeName + "  x";
                float chipH = Math.round(18f * s);
                float chipW = Math.round(GpuUiFontMetrics.textWidth(scope, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 16f * s);
                float chipY = Math.round(headMid - chipH * 0.5f);
                boolean chipHover = hovered(mx, my, afterTitle, chipY, chipW, chipH);
                b.rect(afterTitle, chipY, chipW, chipH, chipH * 0.5f * Math.min(1f, cornerScale),
                        withA(accentArgb(), chipHover ? 0x40 : 0x24));
                textCentered(b, afterTitle + chipW * 0.5f, chipY + chipH * 0.5f, 8.5f * s, accentArgb(), scope,
                        UiDrawCommand.TextWeight.SEMIBOLD);
                hit(afterTitle, chipY, chipW, chipH, () -> {
                    searchScoped = false;
                    navIndex = searchField.isEmpty() ? -1 : 0;
                });
            }
        }
        // Sort: one compact button that cycles Default -> A-Z -> Enabled first.
        String sortLabel = "Sort: " + moduleSort.label;
        float sortH = Math.round(22f * s);
        float sortW = UiKit.buttonWidth(sortLabel, s * 0.9f);
        float sortX = Math.round(x + w - 16f * s - sortW), sortY = Math.round(headMid - sortH * 0.5f);
        UiKit.button(b, kitHost, sortLabel, sortX, sortY, sortW, sortH, s * 0.9f, UiKit.Style.GHOST, true, mx, my, () -> {
            moduleSort = ModuleSort.values()[(moduleSort.ordinal() + 1) % ModuleSort.values().length];
            catalogCacheKey = "";
        });
        if (hovered(mx, my, sortX, sortY, sortW, sortH) && !searching && moduleFilter == ModuleFilter.ALL) {
            hoveredTooltipTitle = "Sort";
            hoveredTooltipDesc = moduleSort == ModuleSort.DEFAULT
                    ? "Your own order: press and drag a card to move it."
                    : "Switch back to Default to drag cards into your own order.";
        }
        if (folder != null && !searching && moduleFilter == ModuleFilter.ALL) {
            // Folder tools: rename in place, delete with a second click to confirm.
            boolean armed = deleteFolderArmed && nanoTime() - deleteFolderArmedAt < 3_000_000_000L;
            if (!armed) deleteFolderArmed = false;
            String del = armed ? "Click to confirm" : "Delete";
            float delW = UiKit.buttonWidth(del, s * 0.9f);
            float delX = Math.round(sortX - 8f * s - delW);
            String folderIdNow = folder.id();
            if (delX > afterTitle + 4f * s) {
                UiKit.button(b, kitHost, del, delX, sortY, delW, sortH, s * 0.9f,
                        armed ? UiKit.Style.DANGER : UiKit.Style.GHOST, true, mx, my, () -> {
                            if (deleteFolderArmed && nanoTime() - deleteFolderArmedAt < 3_000_000_000L) {
                                deleteFolder(folderIdNow);
                            } else {
                                deleteFolderArmed = true;
                                deleteFolderArmedAt = nanoTime();
                            }
                        });
                if (!renaming) {
                    float renW = UiKit.buttonWidth("Rename", s * 0.9f);
                    float renX = Math.round(delX - 6f * s - renW);
                    if (renX > afterTitle + 4f * s) {
                        UiKit.button(b, kitHost, "Rename", renX, sortY, renW, sortH, s * 0.9f, UiKit.Style.GHOST, true,
                                mx, my, () -> startFolderRename(folderIdNow));
                    }
                }
            }
        }
        if (showSeparators) b.rect(x + 16f * s, headY + 26f * s, w - 32f * s, s, 0, lineColor);

        // Search bar + filter segments. The filters sit beside the search field when there is room,
        // otherwise on their own row underneath it.
        float searchY = headY + 36f * s;
        float searchH = Math.round(36f * s);
        float searchX = x + 16f * s;
        float searchW = w - 32f * s;
        boolean searchFocus = searchField.focused(kitHost);
        float searchR = Math.min(searchH * 0.5f, cardRadius * s);
        if (lg) {
            glassPane(b, searchX, searchY, searchW, searchH, searchR, 1f, searchFocus ? 0x3a : 0x2c, false, 0);
        } else {
            b.rect(searchX, searchY, searchW, searchH, searchR, argb((searchFocus ? 0x38 : 0x2a) * panelOpacityK, 0x000000));
        }
        b.border(searchX, searchY, searchW, searchH, searchR, Math.max(1f, s * 0.8f),
                searchFocus ? argb(0x90, accentArgb()) : 0x1effffff);
        float searchIcon = Math.round(14f * s);
        ClassicIcons.draw(b, "search", Math.round(searchX + 12f * s), Math.round(searchY + (searchH - searchIcon) * 0.5f),
                searchIcon, searchFocus ? accentArgb() : mutedColor);
        searchField.draw(b, kitHost, searchX, searchY, searchW, searchH, s);
        if (searchField.isEmpty() && !searchFocus && searchW > 260f * s) {
            // Shortcut hint; typing anywhere in the module list also starts a search.
            String hint = "Ctrl+F";
            float hintW = GpuUiFontMetrics.textWidth(hint, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 12f * s;
            float hintH = Math.round(18f * s);
            float hintX = Math.round(searchX + searchW - hintW - 9f * s), hintY = Math.round(searchY + (searchH - hintH) * 0.5f);
            b.rect(hintX, hintY, hintW, hintH, hintH * 0.5f * Math.min(1f, cornerScale), controlFill(0x14));
            textCentered(b, hintX + hintW * 0.5f, hintY + hintH * 0.5f, 8.5f * s, muted2Color, hint,
                    UiDrawCommand.TextWeight.SEMIBOLD);
        }

        // Quick-clear button when text is present
        if (!searchField.isEmpty()) {
            float clrW = 20f * s, clrH = 20f * s;
            float clrX = searchX + searchW - clrW - 8f * s, clrY = searchY + (searchH - clrH) * 0.5f;
            boolean clrHover = hovered(mx, my, clrX, clrY, clrW, clrH);
            b.circle(clrX + clrW * 0.5f, clrY + clrH * 0.5f, 8f * s, clrHover ? controlFill(0x38) : controlFill(0x1a));
            textCentered(b, clrX + clrW * 0.5f, clrY + clrH * 0.5f, 9f * s, clrHover ? titleColor : mutedColor, "x",
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(clrX, clrY, clrW, clrH, () -> {
                searchField.clear();
                kitHost.focus(searchField);
            });
        }

        // Padded two-column module cards (.rows). Favorited modules float to the top of the list
        // (see the comparator in catalog(...)); there is no filter row anymore.
        float rowsTop = searchY + searchH + 12f * s;
        if (category == Category.BEDWARS && !crossCategory && folderId == null) {
            rowsTop = drawBedwarsNotice(b, x + 16f * s, rowsTop, w - 32f * s, s, mx, my);
        } else {
            toggleAnims.put("bedwars_notice", 0f);
        }
        float rowsBottom = y + h - 12f * s;
        float cardH = Math.round(66f * s);
        float rowGap = Math.round(10f * s);
        float columnGap = Math.round(12f * s);
        float gridPad = Math.round(14f * s);
        // As many columns as fit at a comfortable card width, so a narrow window (side panels open)
        // gets one wide column instead of two cramped ones.
        int cols = Math.max(1, (int) ((w - gridPad * 2f + columnGap) / (Math.round(300f * s) + columnGap)));
        float cardW = Math.max(0f, Math.round((w - gridPad * 2f - columnGap * (cols - 1)) / cols));
        int totalRows = (filtered.size() + cols - 1) / cols;
        float rowPitch = cardH + rowGap;
        float viewH = Math.max(0f, rowsBottom - rowsTop);
        lastCatalogCols = cols;
        lastCatalogRowPitch = rowPitch;
        lastCatalogViewH = viewH;
        // Only an actual category/search change resets the scroll offset and restarts the reveal
        // animation. Including sort/filter/scope here made a fast wheel occasionally coincide with a
        // state flip, which called catalogScroll.reset() mid-gesture (list snapped to top) and
        // re-staggered the reveal (rows briefly dimmed) -- the "menu bugs out when scrolling fast".
        String key = category + "|" + folderId + "|" + query;
        if (!key.equals(catalogKey)) { catalogKey = key; catalogSwitchAt = nanoTime(); catalogScroll.reset(); }
        // Headroom above the first row so a top card's hover lift has room to rise instead of being
        // clipped square against the search bar. Sized to the configured hover strength, and counted
        // into the scrollable height so the last row still reaches the bottom.
        float cardTopPad = Math.max(6f * s, hoverK * 2.5f * s + 2f * s);
        // Quick Settings: pinned settings above the cards, scrolling with them (hidden while searching/filtering).
        List<QuickPin> quick = !searching && moduleFilter == ModuleFilter.ALL ? quickPins() : List.of();
        float stripH = quickStripHeight(quick, cols, s);
        catalogScroll.update(frameDelta, viewH,
                Math.max(0f, stripH + totalRows * rowPitch - rowGap + cardTopPad), nanoTime());
        if (restoreCatalogScroll >= 0f) {
            // Reopened where it was left (the remembered scroll needs this frame's measured content height).
            catalogScroll.scrollTo(restoreCatalogScroll, true);
            restoreCatalogScroll = -1f;
        }
        if (dragModule != null) {
            // Dragging near the top or bottom edge scrolls the list.
            float edge = 28f * s, speed = 700f * s * frameDelta;
            if (dragPointerY < rowsTop + edge) catalogScroll.scrollTo(catalogScroll.target() - speed, false);
            else if (dragPointerY > rowsBottom - edge) catalogScroll.scrollTo(catalogScroll.target() + speed, false);
        }
        if (navScrollPending && navIndex >= 0) {
            // Keep the keyboard-selected card in view.
            navScrollPending = false;
            float top = stripH + (navIndex / cols) * rowPitch;
            float cur = catalogScroll.target();
            if (top < cur) catalogScroll.scrollTo(top, false);
            else if (top + cardH > cur + viewH) catalogScroll.scrollTo(top + cardH - viewH, false);
        }
        float scrollPx = catalogScroll.offset();
        float sinceSwitch = (nanoTime() - catalogSwitchAt) / 1.0e9f;

        b.clipPush(x + 10f * s, rowsTop, w - 20f * s, rowsBottom - rowsTop, 0);
        clipX = x + 10f * s; clipY = rowsTop; clipW = w - 20f * s; clipH = rowsBottom - rowsTop;
        clippingHits = true;

        if (filtered.isEmpty()) {
            String headline, detail;
            if (searching) {
                headline = "Nothing matches \"" + UiKit.fit(query, 160f * s, 12f * s) + "\"";
                detail = searchScoped ? "Only " + category.name + " is searched -- click the \"in " + category.name + "\" chip to search everything."
                        : "Search also finds setting names, categories and bound keys.";
            } else if (moduleFilter == ModuleFilter.FAVORITES) {
                headline = "No favorites yet";
                detail = "Hover a module card and click its star to pin it here.";
            } else if (moduleFilter == ModuleFilter.ENABLED) {
                headline = "Nothing is enabled";
                detail = "Click a module card to turn it on.";
            } else if (moduleFilter == ModuleFilter.BOUND) {
                headline = "No module has a keybind";
                detail = "Open a module's settings (right-click it) and set its key at the bottom.";
            } else if (folder != null) {
                headline = "This folder is empty";
                detail = "Right-click any module card and tick \"" + folder.name() + "\" to add it here.";
            } else {
                headline = "No modules found";
                detail = "";
            }
            UiKit.emptyState(b, kitHost, headline, UiKit.fit(detail, w - 60f * s, 10f * s), x + 10f * s,
                    rowsTop + cardTopPad + stripH - scrollPx + 34f * s, w - 20f * s, s);
        }

        float gridLeft = x + gridPad;
        float gridTop = rowsTop + cardTopPad + stripH - scrollPx;
        if (!quick.isEmpty()) {
            drawQuickStrip(b, quick, gridLeft, rowsTop + cardTopPad - scrollPx, w - gridPad * 2f, cols, cardW,
                    columnGap, rowGap, s, mx, my);
        }
        // Drop position of a dragged card, kept inside its favourites / others group.
        int dropAt = -1;
        float dropBarX = 0f, dropBarY = 0f, dropBarW = 0f, dropBarH = 0f;
        if (dragModule != null && catalogReorderable) {
            int n = shownModules.size();
            CatalogDrag.Drop drop = CatalogDrag.drop(dragPointerX, dragPointerY, gridLeft, gridTop, cardW, cardH,
                    columnGap, rowGap, cols, n);
            int favCount = 0;
            for (Module m : shownModules) if (m.isFavorite()) favCount++;
            int clamped = CatalogDrag.clampToGroup(drop.index(), dragModule.isFavorite(), favCount, n);
            int row = drop.row(), col = drop.column();
            if (clamped != drop.index()) {
                row = cols == 1 ? clamped : clamped / cols;
                col = cols == 1 ? 0 : clamped % cols;
                if (cols > 1 && col == 0 && clamped > 0 && clamped == n && n % cols == 0) { row--; col = cols; }
            }
            int from = shownModules.indexOf(dragModule);
            dropAt = clamped;
            dragDropIndex = clamped;
            if (clamped != from && clamped != from + 1) {
                float bar = Math.max(2f, Math.round(3f * s));
                if (cols == 1) {
                    dropBarX = gridLeft; dropBarW = cardW; dropBarH = bar;
                    dropBarY = gridTop + row * rowPitch - rowGap * 0.5f - bar * 0.5f;
                } else {
                    dropBarW = bar; dropBarH = cardH;
                    dropBarX = gridLeft + col * (cardW + columnGap) - columnGap * 0.5f - bar * 0.5f;
                    dropBarY = gridTop + row * rowPitch;
                }
            }
        } else {
            dragDropIndex = -1;
        }

        float titleSize = 12f * s, descSize = 9f * s, descPitch = 13f * s, titleGap = 8f * s;
        float titleCapH = titleSize * GpuUiFontMetrics.CAP_HEIGHT, descCapH = descSize * GpuUiFontMetrics.CAP_HEIGHT;
        float cardR = cardRadius * s;
        int firstRow = Math.max(0, (int) Math.floor((scrollPx - cardTopPad - stripH) / rowPitch));
        int firstCard = firstRow * cols;
        int end = Math.min(filtered.size(),
                Math.max(0, (int) Math.ceil((scrollPx + viewH - stripH) / rowPitch) + 1) * cols);
        for (int i = firstCard; i < end; i++) {
            CatalogEntry entry = filtered.get(i);
            Module m = entry.module();
            int gridColumn = i % cols;
            float ry = gridTop + (i / cols) * rowPitch;
            float rx = x + gridPad + gridColumn * (cardW + columnGap);
            float rw = cardW;

            boolean hover = hovered(mx, my, rx, ry, rw, cardH);
            boolean sel = selected == m;
            boolean on = m.isEnabled();
            boolean oneShot = m.isOneShot();
            boolean bound = isBound(m);

            // Hover tooltip capture
            if (hover && !safeText(m.getDescription()).isBlank()) {
                hoveredTooltipTitle = m.getName();
                hoveredTooltipDesc = m.getDescription();
            }

            // Glass tile: hover lifts it on a springy bounce, enabled modules glow in the accent
            // colour, and cards cascade in (slide + fade) when the category or search changes.
            float hv = spring("card_h_" + m.getName(), hover ? 1f : 0f, 340f, 22f);
            float onGlow = anim("card_o_" + m.getName(), on ? 1f : 0f, 12f);
            // The delay only ever grows down the visible list, so a card is never further from home
            // than the one below it: the slide-in can't push one card over its neighbour (the old
            // i % 12 stagger restarted at card 12, letting it rise while card 11 still hung low).
            int visibleIndex = Math.max(0, i - firstCard);
            float delay = Math.min(visibleIndex / cols, 8) * 0.035f + gridColumn * 0.012f;
            float reveal = Math.max(0f, Math.min(1f, (sinceSwitch - delay) / 0.32f));
            float revealOut = 1f - reveal;
            reveal = 1f - revealOut * revealOut * revealOut; // ease-out cubic: fast in, soft landing
            ry = Math.round(ry - hv * hoverK * 2.5f * s + (1f - reveal) * 12f * s);
            glassTile(b, rx, ry, rw, cardH, cardR, s, Math.max(0f, Math.min(1f, hv)), onGlow);
            if (sel) b.border(rx, ry, rw, cardH, cardR, Math.max(1.2f, 1.3f * s), argb(0xa0, selectionRing));
            if (navVisible && i == navIndex) {
                // Keyboard focus ring, offset outside the card so it reads apart from the selection ring.
                float o = Math.round(3f * s);
                b.border(rx - o, ry - o, rw + o * 2f, cardH + o * 2f, cardR + o, Math.max(1.5f, 1.6f * s),
                        argb(0xd0, selectionRing));
            }

            // No icon chip: every card in a category showed the same glyph, so it said nothing.
            // An accent bar on the left edge marks an enabled module instead; it grows from the centre.
            float textLeft = rx + 16f * s;
            if (onGlow > 0.01f) {
                float barH = (cardH - 28f * s) * onGlow;
                accentFill(b, rx + 6f * s, ry + cardH * 0.5f - barH * 0.5f, 3f * s, barH, 1.5f * s, onGlow, 0);
            }

            // Right-hand controls: the switch (or a one-shot's trigger key), and for a bound toggle
            // module a key chip beside the switch.
            float tgW = 34f * s, tgH = 18f * s;
            float tgX = rx + rw - tgW - 8f * s, tgY = ry + (cardH - tgH) * 0.5f;
            String chipLabel = bound && !oneShot ? keyLabel(m.getKeyCode()) : null;
            float chipW = chipLabel == null ? 0f : Math.round(Math.max(22f * s, Math.min(70f * s,
                    GpuUiFontMetrics.textWidth(chipLabel, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 12f * s)));
            float controlReserve = oneShot ? 92f * s : 54f * s + (chipLabel == null ? 0f : chipW + 6f * s);

            // Title, optional tags and up to two summary lines, laid out as one block whose visible ink
            // (title cap-top to last baseline) is centred in the card: equal padding above and below
            // whether the summary is zero, one or two lines.
            float tagRight = rx + rw - controlReserve + 4f * s;
            float maxDescW = Math.max(12f * s, rw - controlReserve - 16f * s);
            String note = entry.note();
            List<String> descLines = wrapText(safeText(m.getDescription()), maxDescW, descSize, note == null ? 2 : 1);
            int lineCount = descLines.size() + (note == null ? 0 : 1);
            float blockH = titleCapH + (lineCount == 0 ? 0f : titleGap + descCapH + (lineCount - 1) * descPitch);
            float blockTop = ry + (cardH - blockH) * 0.5f;
            float titleMid = blockTop + titleCapH * 0.5f;

            String moduleTitle = UiKit.fit(m.getName(), Math.max(0f, tagRight - textLeft), titleSize,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            if (searching) highlightMatch(b, m.getName(), moduleTitle, lowerQuery, textLeft, titleMid, titleSize,
                    UiDrawCommand.TextWeight.SEMIBOLD, s);
            textMid(b, textLeft, titleMid, titleSize, on ? titleColor : textColor,
                    moduleTitle, UiDrawCommand.TextWeight.SEMIBOLD);
            float tagX = textLeft + GpuUiFontMetrics.textWidth(moduleTitle, titleSize, UiDrawCommand.TextWeight.SEMIBOLD) + 7f * s;
            float tagH = Math.round(14f * s), tagY = Math.round(titleMid - tagH * 0.5f);
            if (oneShot) tagX = drawCardTag(b, "One-shot", tagX, tagY, tagH, tagRight, s, false);
            if (m.isBlatantFlagged()) tagX = drawCardTag(b, "Blatant", tagX, tagY, tagH, tagRight, s, true);
            // Results that span categories say which one each module lives in.
            if ((crossCategory || folder != null) && m.getCategory() != null) {
                float tagW = GpuUiFontMetrics.textWidth(m.getCategory().name, 8f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 12f * s;
                if (tagX + tagW <= tagRight) {
                    b.rect(tagX, tagY, tagW, tagH, tagH * 0.5f * Math.min(1f, cornerScale), controlFill(0x16));
                    textCentered(b, tagX + tagW * 0.5f, tagY + tagH * 0.5f, 8f * s, mutedColor, m.getCategory().name,
                            UiDrawCommand.TextWeight.SEMIBOLD);
                }
            }
            float descMid = blockTop + titleCapH + titleGap + descCapH * 0.5f;
            int li = 0;
            if (note != null) {
                String shownNote = UiKit.fit(note, maxDescW, descSize);
                if (entry.noteMatch() >= 0) highlightRange(b, note, shownNote, entry.noteMatch(),
                        entry.noteMatch() + lowerQuery.length(), textLeft, descMid, descSize, UiDrawCommand.TextWeight.REGULAR, s);
                textMid(b, textLeft, descMid, descSize, withA(accentArgb(), 0xe6), shownNote, UiDrawCommand.TextWeight.REGULAR);
                li = 1;
            }
            for (String line : descLines) {
                textMid(b, textLeft, descMid + li * descPitch, descSize, mutedColor, line, UiDrawCommand.TextWeight.REGULAR);
                li++;
            }

            // Left-click toggles the module (press and drag moves it); right-click opens its menu (settings,
            // favourite, folders). Trailing controls retain their own hit zones.
            hit(rx, ry, Math.max(0f, rw - controlReserve), cardH, new CardPress(m, () -> {
                navVisible = false;
                if (m.isOneShot() || m.isLocked()) selectModule(m);
                else m.toggle();
            }));
            // Right-click opens the module's settings; Shift+right-click keeps favourite/folder actions reachable.
            hitModuleCardRight(rx, ry, rw, cardH, () -> {
                if (shiftHeld()) openModuleMenu(m);
                else toggleModuleInspector(m);
            });

            if (!oneShot) {
                drawToggleSwitch(b, "mod_" + m.getName(), tgX, tgY, tgW, tgH, on, s);
                if (!m.isLocked()) hit(tgX - 4f * s, ry, tgW + 8f * s, cardH, m::toggle);
                if (chipLabel != null) {
                    float chipH = Math.round(18f * s);
                    float chipX = Math.round(tgX - 6f * s - chipW), chipY = Math.round(ry + (cardH - chipH) * 0.5f);
                    boolean chipHover = hovered(mx, my, chipX, chipY, chipW, chipH);
                    b.rect(chipX, chipY, chipW, chipH, chipH * 0.5f * Math.min(1f, cornerScale),
                            withA(accentArgb(), chipHover ? 0x3a : 0x22));
                    textCentered(b, chipX + chipW * 0.5f, chipY + chipH * 0.5f, 8.5f * s, chipHover ? titleColor : textColor,
                            UiKit.fit(chipLabel, chipW - 8f * s, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                            UiDrawCommand.TextWeight.SEMIBOLD);
                    if (chipHover) {
                        hoveredTooltipTitle = "Keybind: " + m.getKeyDisplayName();
                        hoveredTooltipDesc = "Click to rebind (Esc clears the capture).";
                    }
                    hit(chipX - 2f * s, ry, chipW + 4f * s, cardH, () -> beginModuleKeyCapture(m));
                }
            } else {
                boolean capturing = bindingModuleKey && bindingModuleTarget == m;
                String bindLabel = capturing ? "Press a key..." : keyLabel(m.getKeyCode());
                float bindW = Math.max(38f * s, Math.min(76f * s,
                        GpuUiFontMetrics.textWidth(bindLabel, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 14f * s));
                float bindX = Math.round(rx + rw - bindW - 8f * s);
                float bindH = Math.round(22f * s);
                float bindY = Math.round(ry + (cardH - bindH) * 0.5f);
                // (The unbound fill used to be 0x1b2027 -- alpha 0, so the pill was invisible.)
                b.rect(bindX, bindY, bindW, bindH, bindH * 0.5f * Math.min(1f, cornerScale),
                        bound || capturing ? (accentArgb() & 0x00ffffff) | 0x26000000 : controlFill(0x1a));
                textCentered(b, bindX + bindW * 0.5f, bindY + bindH * 0.5f, 9f * s, bound || capturing ? textColor : mutedColor,
                        UiKit.fit(bindLabel, bindW - 12f * s, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                hit(bindX - 4f * s, ry, bindW + 8f * s, cardH, () -> beginModuleKeyCapture(m));
            }

            // Favorite star: always shown once starred, revealed on hover otherwise.
            boolean fav = m.isFavorite();
            float starR = Math.round(5.5f * s);
            float starCx = rx + rw - 14f * s, starCy = ry + 11f * s;
            boolean starHover = hovered(mx, my, starCx - 9f * s, starCy - 8f * s, 18f * s, 16f * s);
            float starA = anim("card_star_" + m.getName(), fav || hover ? 1f : 0f, 16f);
            if (starA > 0.01f) {
                int starColor = fav ? accentArgb() : starHover ? textColor : mutedColor;
                drawStar(b, starCx, starCy, starR, scaleAlpha(starColor | 0xff000000, starA * (fav || starHover ? 1f : 0.7f)));
            }
            if (fav || hover) {
                hit(starCx - 9f * s, starCy - 8f * s, 18f * s, 16f * s, () -> {
                    m.setFavorite(!m.isFavorite());
                    catalogCacheKey = "";
                });
                if (starHover) {
                    hoveredTooltipTitle = fav ? "Unfavorite" : "Favorite";
                    hoveredTooltipDesc = fav ? "Remove from the Favorites filter." : "Pin to the Favorites filter.";
                }
            }
            if (reveal < 0.999f) b.rect(rx, ry, rw, cardH, cardR, argb(0xa0 * (1f - reveal), 0x0b0f16));
            // The card being dragged stays in place, faded, until it is dropped.
            if (m == dragModule) b.rect(rx, ry, rw, cardH, cardR, argb(0x90, 0x0b0f16));
        }
        if (dropAt >= 0 && dropBarW > 0f) {
            b.rect(dropBarX, dropBarY, dropBarW, dropBarH, Math.min(dropBarW, dropBarH) * 0.5f, accentArgb());
        }
        clippingHits = false;
        b.clipPop();
        catalogScroll.paintBar(b, x + w - Math.round(8f * s), rowsTop, viewH, s, textColor, nanoTime());
    }

    // ---- Quick Settings strip ----------------------------------------------------------------

    /** A pinned setting resolved to its live module and setting. */
    private record QuickPin(Module module, Setting setting) { }

    private static final float QUICK_HEADER_H = 26f;

    /** The pinned settings that exist right now (a pin whose module has not loaded is kept, just not shown). */
    private List<QuickPin> quickPins() {
        List<GuiPrefs.Pin> pins = guiPrefs().pins();
        if (pins.isEmpty()) return List.of();
        ModuleManager manager = moduleManager();
        if (manager == null) return List.of();
        Map<String, Module> byName = new HashMap<>();
        for (Module m : manager.getModules()) if (m != null) byName.putIfAbsent(m.getName(), m);
        List<QuickPin> out = new ArrayList<>();
        for (GuiPrefs.Pin pin : pins) {
            Module m = byName.get(pin.module());
            Setting st = m == null ? null : m.getSetting(pin.setting());
            if (st == null || !st.isVisible() || !pinnable(st)) continue;
            // A pinned sub-setting only shows while its parent condition is met, as in the module's own panel.
            ClassicSettingLayout.Row row = ClassicSettingLayout.row(m, st);
            if (row != null && !row.visible()) continue;
            if (m instanceof ClickGUIModule && !ClickGUIModule.shouldExposeSetting(st)) continue;
            out.add(new QuickPin(m, st));
        }
        return out;
    }

    private static boolean pinnable(Setting setting) {
        return setting != null && !(setting instanceof KeybindSetting) && !(setting instanceof ConfigKeybindListSetting);
    }

    /** Height (px) the strip takes above the module cards, including its gap below. */
    private float quickStripHeight(List<QuickPin> quick, int cols, float s) {
        if (quick.isEmpty()) return 0f;
        float h = Math.round(QUICK_HEADER_H * s);
        if (quickCollapsed) return h + Math.round(6f * s);
        float rowGap = Math.round(10f * s);
        for (int i = 0; i < quick.size(); i += cols) {
            int rowH = 0;
            for (int j = i; j < Math.min(quick.size(), i + cols); j++) rowH = Math.max(rowH, settingHeight(quick.get(j).setting()));
            h += Math.round(rowH * s) + rowGap;
        }
        return h + Math.round(8f * s);
    }

    private void drawQuickStrip(UiDisplayList.Builder b, List<QuickPin> quick, float x, float y, float w, int cols,
                                float cellW, float colGap, float rowGap, float s, float mx, float my) {
        float headH = Math.round(QUICK_HEADER_H * s);
        float headMid = y + headH * 0.5f - 2f * s;
        String label = "QUICK SETTINGS";
        textMid(b, x + 4f * s, headMid, 9f * s, mutedColor, label, UiDrawCommand.TextWeight.SEMIBOLD);
        float after = x + 4f * s + GpuUiFontMetrics.textWidth(label, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 8f * s;
        textMid(b, after, headMid, 9f * s, muted2Color, String.valueOf(quick.size()), UiDrawCommand.TextWeight.REGULAR);
        String fold = quickCollapsed ? "Show" : "Hide";
        float foldW = GpuUiFontMetrics.textWidth(fold, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 14f * s;
        float foldH = Math.round(18f * s);
        float foldX = Math.round(x + w - foldW), foldY = Math.round(headMid - foldH * 0.5f);
        boolean foldHover = hovered(mx, my, foldX, foldY, foldW, foldH);
        if (foldHover) b.rect(foldX, foldY, foldW, foldH, pillR(foldH), controlFill(0x1e));
        textCentered(b, foldX + foldW * 0.5f, foldY + foldH * 0.5f, 9f * s, foldHover ? textColor : mutedColor, fold,
                UiDrawCommand.TextWeight.SEMIBOLD);
        hit(foldX, foldY, foldW, foldH, () -> quickCollapsed = !quickCollapsed);
        if (hovered(mx, my, x, y, foldX - x, headH)) {
            hoveredTooltipTitle = "Quick Settings";
            hoveredTooltipDesc = "Settings you pinned, editable from any category. Right-click a setting row to pin or unpin it.";
        }
        if (quickCollapsed) return;
        float cy = y + headH;
        for (int i = 0; i < quick.size(); i += cols) {
            int rowUnits = 0;
            for (int j = i; j < Math.min(quick.size(), i + cols); j++) {
                rowUnits = Math.max(rowUnits, settingHeight(quick.get(j).setting()));
            }
            float rowH = Math.round(rowUnits * s);
            // Rows entirely outside the catalog viewport are skipped (their hits would be clipped away anyway).
            boolean visible = cy + rowH >= clipY && cy <= clipY + clipH;
            for (int j = i; visible && j < Math.min(quick.size(), i + cols); j++) {
                QuickPin pin = quick.get(j);
                float cx = x + (j - i) * (cellW + colGap);
                float cellH = Math.round(settingHeight(pin.setting()) * s);
                surfaceCard(b, cx, cy, cellW, cellH, cardRadius * s);
                rowLabelPrefix = pin.module().getName() + " · ";
                boolean seps = showSeparators;
                showSeparators = false;
                try {
                    drawSettingRow(b, pin.setting(), cx, cy, cellW, cellH, s, mx, my);
                } finally {
                    showSeparators = seps;
                    rowLabelPrefix = null;
                }
            }
            cy += rowH + rowGap;
        }
    }

    /** Small pill tag after a card title; returns the x after it (unchanged when it does not fit). */
    private float drawCardTag(UiDisplayList.Builder b, String label, float tagX, float tagY, float tagH,
                              float tagRight, float s, boolean outlined) {
        float tagW = GpuUiFontMetrics.textWidth(label, 8f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 12f * s;
        if (tagX + tagW > tagRight) return tagX;
        float r = tagH * 0.5f * Math.min(1f, cornerScale);
        b.rect(tagX, tagY, tagW, tagH, r, (accentArgb() & 0x00ffffff) | 0x1d000000);
        // Same accent palette as the other tags, outlined so Blatant reads as a flag rather than a warning.
        if (outlined) b.border(tagX, tagY, tagW, tagH, r, Math.max(1f, 0.8f * s), (accentArgb() & 0x00ffffff) | 0x80000000);
        textCentered(b, tagX + tagW * 0.5f, tagY + tagH * 0.5f, 8f * s, accentArgb(), label, UiDrawCommand.TextWeight.SEMIBOLD);
        return tagX + tagW + 5f * s;
    }

    /** Accent highlight behind the part of {@code shown} (a possibly ellipsised {@code full}) matching the query. */
    private void highlightMatch(UiDisplayList.Builder b, String full, String shown, String lowerQuery, float x,
                                float mid, float size, UiDrawCommand.TextWeight weight, float s) {
        if (lowerQuery.isEmpty()) return;
        int at = full.toLowerCase(Locale.ROOT).indexOf(lowerQuery);
        if (at >= 0) highlightRange(b, full, shown, at, at + lowerQuery.length(), x, mid, size, weight, s);
    }

    private void highlightRange(UiDisplayList.Builder b, String full, String shown, int from, int to, float x,
                                float mid, float size, UiDrawCommand.TextWeight weight, float s) {
        boolean ellipsised = !shown.equals(full) && shown.endsWith("...");
        int visible = Math.min(full.length(), ellipsised ? shown.length() - 3 : shown.length());
        if (from < 0 || from >= visible) return;
        int end = Math.min(to, visible);
        float hx = x + GpuUiFontMetrics.textWidth(full.substring(0, from), size, weight);
        float hw = GpuUiFontMetrics.textWidth(full.substring(from, end), size, weight);
        float hh = Math.round(size * 1.25f);
        b.rect(hx - 1f * s, Math.round(mid - hh * 0.5f), hw + 2f * s, hh, Math.max(1f, 3f * s), withA(accentArgb(), 0x48));
    }

    private static final float[] STAR_X = new float[10], STAR_Y = new float[10];
    static {
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            double r = (i % 2 == 0) ? 1.0 : 0.45;
            STAR_X[i] = (float) (Math.cos(a) * r);
            STAR_Y[i] = (float) (Math.sin(a) * r);
        }
    }

    /** Filled five-point star (scanline fill on whole pixel rows, like the logo). */
    private static void drawStar(UiDisplayList.Builder b, float cx, float cy, float r, int argb) {
        float top = cy - r, bottom = cy + r * 0.81f;
        int rows = Math.max(4, Math.round(bottom - top));
        float rowH = (bottom - top) / rows;
        float[] xs = new float[10];
        for (int row = 0; row < rows; row++) {
            float scan = top + (row + 0.5f) * rowH;
            int count = 0;
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10;
                float y0 = cy + STAR_Y[i] * r, y1 = cy + STAR_Y[j] * r;
                if ((y0 <= scan && scan < y1) || (y1 <= scan && scan < y0)) {
                    float x0 = cx + STAR_X[i] * r, x1 = cx + STAR_X[j] * r;
                    xs[count++] = x0 + (scan - y0) * (x1 - x0) / (y1 - y0);
                }
            }
            java.util.Arrays.sort(xs, 0, count);
            for (int i = 0; i + 1 < count; i += 2) {
                b.rect(xs[i], top + row * rowH, xs[i + 1] - xs[i], rowH * 1.02f, 0, argb);
            }
        }
    }

    private static boolean isBound(Module m) {
        int k = m.getKeyCode();
        return k != -1 && k != 0;
    }

    /** Every registered, non-hidden module across all categories (ClickGUI itself lives in the drawer). */
    private List<Module> allModules() {
        List<Module> out = new ArrayList<>();
        java.util.Set<Module> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Category c : Category.values()) {
            for (Module m : modulesForCategory(c)) {
                if (m instanceof ClickGUIModule || !seen.add(m)) continue;
                out.add(m);
            }
        }
        return out;
    }

    private boolean passesFilter(Module m) {
        return switch (moduleFilter) {
            case ALL -> true;
            case ENABLED -> m.isEnabled();
            case FAVORITES -> m.isFavorite();
            case BOUND -> isBound(m);
        };
    }

    /**
     * The catalog's entries for the current query/filter/sort, ranked: name prefix, name, category,
     * bound key, setting name, then description. Cached until the inputs or any module's
     * enabled/favorite/key state changes.
     */
    private List<CatalogEntry> catalog(String query, boolean crossCategory) {
        List<Module> base = crossCategory ? allModules()
                : folderId != null ? modulesForFolder(folderId) : modulesForCategory(category);
        long state = base.size();
        for (Module m : base) {
            state = state * 31 + (m.isEnabled() ? 1 : 0) + (m.isFavorite() ? 2 : 0) + 4L * m.getKeyCode();
        }
        // The layout revision covers a reorder or a folder edit (same modules and flags, new order).
        String key = query + '\u0001' + crossCategory + '\u0001' + category + '\u0001' + folderId + '\u0001'
                + guiPrefs().revision() + '\u0001' + moduleFilter + '\u0001' + moduleSort;
        if (key.equals(catalogCacheKey) && state == catalogCacheState) return catalogEntries;

        String q = query.toLowerCase(Locale.ROOT);
        List<CatalogEntry> out = new ArrayList<>();
        for (Module m : base) {
            if (m instanceof ClickGUIModule || !passesFilter(m)) continue;
            if (q.isEmpty()) {
                out.add(new CatalogEntry(m, 0, null, -1));
                continue;
            }
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith(q)) { out.add(new CatalogEntry(m, 0, null, -1)); continue; }
            if (name.contains(q)) { out.add(new CatalogEntry(m, 1, null, -1)); continue; }
            if (m.getCategory() != null && m.getCategory().name.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(new CatalogEntry(m, 2, null, -1));
                continue;
            }
            if (isBound(m) && (Module.getSingleKeyName(m.getKeyCode()).equalsIgnoreCase(q)
                    || m.getKeyDisplayName().equalsIgnoreCase(q))) {
                out.add(new CatalogEntry(m, 3, "Bound to " + m.getKeyDisplayName(), "Bound to ".length()));
                continue;
            }
            CatalogEntry viaSetting = null;
            for (Setting setting : m.getSettings()) {
                if (setting == null || setting instanceof KeybindSetting || setting.getName() == null) continue;
                int at = setting.getName().toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) {
                    viaSetting = new CatalogEntry(m, 4, "Setting: " + setting.getName(), "Setting: ".length() + at);
                    break;
                }
            }
            if (viaSetting != null) { out.add(viaSetting); continue; }
            if (safeText(m.getDescription()).toLowerCase(Locale.ROOT).contains(q)) out.add(new CatalogEntry(m, 5, null, -1));
        }
        // Favorited modules always float to the top (replaces the old Favorites filter chip), then the
        // selected sort decides the rest. The sort is stable, so within each group registration order holds.
        Comparator<CatalogEntry> favFirst = Comparator.comparingInt(e -> e.module().isFavorite() ? 0 : 1);
        Comparator<CatalogEntry> order = switch (moduleSort) {
            case DEFAULT -> favFirst.thenComparingInt(CatalogEntry::rank);
            case AZ -> favFirst.thenComparing(e -> e.module().getName(), String.CASE_INSENSITIVE_ORDER);
            case ENABLED_FIRST -> favFirst.thenComparingInt(e -> e.module().isEnabled() ? 0 : 1)
                    .thenComparingInt(CatalogEntry::rank);
        };
        out.sort(order); // stable: equal keys keep the registration order
        catalogEntries = List.copyOf(out);
        catalogCacheKey = key;
        catalogCacheState = state;
        return catalogEntries;
    }

    private void onSearchChanged() {
        catalogScroll.reset();
        catalogCacheKey = "";
        // Enter toggles the top result, so show the focus ring on it while typing.
        navIndex = searchField.isEmpty() ? -1 : 0;
        navVisible = !searchField.isEmpty();
        if (view != View.MODULES) view = View.MODULES;
    }

    /** Enter on the module list: toggles the keyboard-selected (or top) result; {@code open} shows its settings. */
    private void activateNav(boolean open) {
        List<CatalogEntry> list = catalogEntries;
        if (view != View.MODULES || list.isEmpty()) return;
        int i = navIndex >= 0 && navIndex < list.size() ? navIndex : 0;
        navIndex = i;
        navVisible = true;
        Module m = list.get(i).module();
        if (open || m.isOneShot() || m.isLocked()) selectModule(m);
        else m.toggle();
    }

    /** Arrow keys over the result grid. Returns true when the key was used. */
    private boolean moveNav(int key) {
        List<CatalogEntry> list = catalogEntries;
        int n = list.size();
        if (view != View.MODULES || n == 0) return false;
        int cols = Math.max(1, lastCatalogCols);
        if (navIndex < 0 || navIndex >= n || !navVisible) {
            navIndex = Math.max(0, Math.min(n - 1, navIndex));
            navVisible = true;
            navScrollPending = true;
            return true;
        }
        int col = navIndex % cols;
        switch (key) {
            case GLFW.GLFW_KEY_UP -> navIndex = Math.max(0, navIndex - cols);
            case GLFW.GLFW_KEY_DOWN -> navIndex = Math.min(n - 1, navIndex + cols);
            case GLFW.GLFW_KEY_LEFT -> {
                if (col > 0) navIndex--;
                else if (moduleInspectorTargetProgress > 0f) animateModuleInspectorTo(0f);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                // Last column (or a one-column list): Right opens the selected module's settings.
                if (col < cols - 1 && navIndex < n - 1) navIndex++;
                else activateNav(true);
            }
            default -> { return false; }
        }
        navScrollPending = true;
        return true;
    }

    private void selectModule(Module module) {
        selected = module;
        settingsScroll = 0;
        moduleInspectorScrollRemainder = 0;
        if (settingsFilterFor != module) {
            settingsFilter.setValue("");
            settingsFilterFor = module;
        }
        resetAllArmed = false;
        animateModuleInspectorTo(1f);
    }

    private void toggleModuleInspector(Module module) {
        if (selected == module && moduleInspectorTargetProgress > 0f) {
            animateModuleInspectorTo(0f);
            return;
        }
        selectModule(module);
    }

    private void animateModuleInspectorTo(float target) {
        target = clamp01(target);
        if (moduleInspectorTargetProgress == target) return;
        moduleInspectorTargetProgress = target;
        moduleInspectorAnimationStartedAt = nanoTime();
    }

    private void updateModuleInspectorAnimation(long now) {
        if (moduleInspectorAnimationStartedAt == 0L) return;
        // Same motion as the appearance drawer on the left: eased toward the target each frame, then smoothstepped.
        float raw = anim("module_inspector", moduleInspectorTargetProgress, 14f);
        moduleInspectorProgress = raw * raw * (3f - 2f * raw);
        if (raw == moduleInspectorTargetProgress) {
            moduleInspectorProgress = moduleInspectorTargetProgress;
            moduleInspectorAnimationStartedAt = 0L;
            if (moduleInspectorTargetProgress == 0f) selected = null;
        }
    }

    // =========================================================================
    // 3. RIGHT INSPECTOR (.set, .set-empty, .set-header, .set-card, .set-row)
    // =========================================================================

    private void drawModuleInspector(UiDisplayList.Builder b, float x, float y, float w, float h,
                                     boolean compact, float s, float mx, float my) {
        if (selected == null) {
            // Empty state (.set-empty)
            String hint = "Right-click a module to see its settings";
            float hintW = GpuUiFontMetrics.textWidth(hint, 11f * s);
            b.text(x + (w - hintW) * 0.5f, y + h * 0.5f - 5f * s, 11f * s, mutedColor, hint);
            return;
        }
        if (settingsFilterFor != selected) {
            settingsFilter.setValue("");
            settingsFilterFor = selected;
            resetAllArmed = false;
        }

        float pad = 24f * s;
        float headerY = y + 18f * s;

        // "Reset all": shown once anything differs from its default; the first click arms it.
        boolean anyModified = false;
        for (Setting setting : selected.getSettings()) {
            // The base Keybind setting is kept by Reset all (see its tooltip), so it never counts as modified here.
            if (setting != null && !(setting instanceof KeybindSetting) && setting.isModified()) { anyModified = true; break; }
        }
        boolean armed = resetAllArmed && nanoTime() - resetAllArmedAt < 3_000_000_000L;
        if (!armed) resetAllArmed = false;
        float resetW = 0f;
        if (anyModified) {
            String label = armed ? "Click to confirm" : "Reset all";
            float resetH = Math.round(22f * s);
            resetW = UiKit.buttonWidth(label, s * 0.9f);
            float resetX = Math.round(x + w - pad - resetW), resetY = Math.round(headerY + 1f * s);
            Module target = selected;
            UiKit.button(b, kitHost, label, resetX, resetY, resetW, resetH, s * 0.9f,
                    armed ? UiKit.Style.DANGER : UiKit.Style.SECONDARY, true, mx, my, () -> {
                        if (resetAllArmed && nanoTime() - resetAllArmedAt < 3_000_000_000L) {
                            for (Setting setting : target.getSettings()) {
                                if (setting != null && !(setting instanceof KeybindSetting)) setting.resetToDefault();
                            }
                            resetAllArmed = false;
                        } else {
                            resetAllArmed = true;
                            resetAllArmedAt = nanoTime();
                        }
                    });
            if (hovered(mx, my, resetX, resetY, resetW, resetH)) {
                hoveredTooltipTitle = "Reset all settings";
                hoveredTooltipDesc = "Puts every setting of " + selected.getName()
                        + " back to its default. Click twice to confirm. The keybind is kept.";
            }
        }

        b.text(x + pad, headerY + 2f * s, 16f * s, titleColor,
                UiKit.fit(selected.getName(), Math.max(0f, w - 2f * pad - (resetW > 0f ? resetW + 10f * s : 0f)), 16f * s,
                        UiDrawCommand.TextWeight.SEMIBOLD),
                UiDrawCommand.TextWeight.SEMIBOLD);

        float descMaxW = w - 2f * pad;
        List<String> descLines = wrapText(safeText(selected.getDescription()), descMaxW, 11f * s, 4);
        float descY = headerY + 24f * s;
        for (int li = 0; li < descLines.size(); li++) {
            b.text(x + pad, descY + li * 14f * s, 11f * s, mutedColor, descLines.get(li));
        }

        float divY = descLines.isEmpty() ? (headerY + 36f * s) : (descY + descLines.size() * 14f * s + 6f * s);
        if (showSeparators) b.rect(x + pad, divY, w - 2f * pad, s, 0, lineColor);

        float bodyTop = divY + 12f * s;
        float bodyBottom = y + h - 54f * s;

        // Settings grouped into the module's own sections, advanced ones last.
        List<SettingGroup> groups = settingGroups(selected);
        boolean noSettings = groups.isEmpty();
        // A filter box over the settings, for modules with more than a handful of rows.
        int settingCount = 0;
        for (SettingGroup group : groups) settingCount += group.settings().size();
        String filter = "";
        if (settingCount > 5) {
            float filterH = Math.round(28f * s);
            settingsFilter.draw(b, kitHost, x + pad, bodyTop, w - 2f * pad, filterH, s);
            bodyTop += filterH + 10f * s;
            filter = settingsFilter.value().trim().toLowerCase(Locale.ROOT);
        } else if (!settingsFilter.isEmpty()) {
            settingsFilter.setValue("");
        }
        boolean filtering = !filter.isEmpty();
        if (filtering) groups = filterGroups(groups, filter);

        float groupsHeight = 0f;
        for (SettingGroup group : groups) groupsHeight += groupHeight(group, filtering ? null : groupKey(selected, group));
        float settingsContentHeight = (selected.isOneShot() || filtering ? 0f : 58f)
                + (groups.isEmpty() ? 36f : groupsHeight);
        float settingsViewportHeight = Math.max(0f, (bodyBottom - bodyTop) / s);
        int maxSettingsScroll = (int) Math.ceil(Math.max(0f, settingsContentHeight - settingsViewportHeight));
        settingsScroll = Math.min(settingsScroll, maxSettingsScroll);
        settingsScrollShown = easeScroll(settingsScrollShown, settingsScroll);

        b.clipPush(x + pad, bodyTop, w - 2f * pad, bodyBottom - bodyTop, 0);
        clipX = x + pad; clipY = bodyTop; clipW = w - 2f * pad; clipH = bodyBottom - bodyTop;
        clippingHits = true;

        float curY = bodyTop - settingsScrollShown * s;
        float cardX = x + pad;
        float cardW = w - 2f * pad;

        // Master toggle card (hidden while filtering, so matches sit at the top)
        if (!selected.isOneShot() && !filtering) {
            float masterH = 46f * s;
            surfaceCard(b, cardX, curY, cardW, masterH, cardRadius * s);

            textMid(b, cardX + 16f * s, curY + masterH * 0.5f, 12.5f * s, textColor, "Enabled",
                    UiDrawCommand.TextWeight.SEMIBOLD);

            float tgW = 34f * s, tgH = 18f * s;
            float tgX = cardX + cardW - tgW - 14f * s, tgY = curY + (masterH - tgH) * 0.5f;
            drawToggleSwitch(b, "insp_" + selected.getName(), tgX, tgY, tgW, tgH, selected.isEnabled(), s);
            if (!selected.isLocked()) hit(tgX - 4f * s, curY, tgW + 8f * s, masterH, selected::toggle);

            curY += masterH + 12f * s;
        }

        if (noSettings) {
            b.text(cardX + 4f * s, curY + 16f * s, 11f * s, mutedColor, "This module has no additional settings.");
            curY += 36f * s;
        } else if (groups.isEmpty()) {
            b.text(cardX + 4f * s, curY + 16f * s, 11f * s, mutedColor,
                    UiKit.fit("No settings match \"" + settingsFilter.value().trim() + "\".", cardW - 8f * s, 11f * s));
            curY += 36f * s;
        } else {
            for (SettingGroup group : groups) {
                curY = drawSettingGroup(b, group.title(), group.settings(), cardX, curY, cardW, s, mx, my,
                        filtering ? null : groupKey(selected, group));
            }
        }

        clippingHits = false;
        b.clipPop();

        // Pinned Footer: Keybind control (.set-footer)
        float footY = y + h - 50f * s;
        if (showSeparators) b.rect(x + pad, footY, w - 2f * pad, s, 0, lineColor);
        String bindTitle = (selected.isOneShot() || selected.isKeyTriggered()) ? "TRIGGER KEY" : "Keybind";
        float footMid = footY + 18f * s;
        b.text(x + pad + 2f * s, footMid, 11.5f * s, textColor, bindTitle, UiDrawCommand.TextWeight.SEMIBOLD);

        String keyText = bindingModuleKey && bindingModuleTarget == selected ? "Press a key…" : selected.getKeyDisplayName();

        float keyBtnH = Math.round(26f * s);
        float clearW = GpuUiFontMetrics.textWidth("Clear", 10f * s) + 8f * s;
        float keyBtnW = Math.round(Math.min(150f * s,
                Math.max(56f * s, GpuUiFontMetrics.textWidth(keyText, 10.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 24f * s)));
        float keyBtnX = Math.round(x + w - pad - clearW - 8f * s - keyBtnW), keyBtnY = Math.round(footMid - keyBtnH * 0.5f);
        b.rect(keyBtnX, keyBtnY, keyBtnW, keyBtnH, pillR(keyBtnH),
                bindingModuleKey ? controlFill(0x48) : controlFill(0x1a));
        textCentered(b, keyBtnX + keyBtnW * 0.5f, footMid, 10.5f * s, bindingModuleKey ? accentArgb() : textColor,
                UiKit.fit(keyText, keyBtnW - 16f * s, 10.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                UiDrawCommand.TextWeight.SEMIBOLD);
        hit(keyBtnX, keyBtnY, keyBtnW, keyBtnH, () -> {
            beginModuleKeyCapture(selected);
        });

        // Toggle / Hold: a held key keeps the module on only while it is down. One-shot and trigger
        // binds already act on the press, so they have no mode.
        if (!selected.isOneShot() && !selected.isKeyTriggered() && selected.getKeyCode() != -1
                && !(selected instanceof ClickGUIModule)) { // the menu key has no Hold mode
            String modeLabel = selected.isHoldMode() ? "Hold" : "Toggle";
            float modeW = Math.round(GpuUiFontMetrics.textWidth("Toggle", 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s);
            float modeX = Math.round(keyBtnX - 8f * s - modeW);
            boolean modeHover = hovered(mx, my, modeX, keyBtnY, modeW, keyBtnH);
            b.rect(modeX, keyBtnY, modeW, keyBtnH, pillR(keyBtnH),
                    selected.isHoldMode() ? UiKit.withAlpha(accentArgb(), 0x30) : controlFill(modeHover ? 0x2a : 0x1a));
            if (selected.isHoldMode()) b.border(modeX, keyBtnY, modeW, keyBtnH, pillR(keyBtnH), 1f, UiKit.withAlpha(accentArgb(), 0x70));
            textCentered(b, modeX + modeW * 0.5f, footMid, 9.5f * s, selected.isHoldMode() ? accentArgb() : textColor, modeLabel,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(modeX, keyBtnY, modeW, keyBtnH, () -> selected.setHoldMode(!selected.isHoldMode()));
            if (modeHover) {
                hoveredTooltipTitle = "Key mode";
                hoveredTooltipDesc = selected.isHoldMode()
                        ? "Hold: the module is on only while the key is held. Click for Toggle."
                        : "Toggle: a press flips the module on or off. Click for Hold (on only while held).";
            }
        }

        // Clear keybind
        float clrX = x + w - pad - clearW;
        boolean clrHover = hovered(mx, my, clrX, keyBtnY, clearW, keyBtnH);
        textCentered(b, clrX + clearW * 0.5f, footMid, 10f * s, clrHover ? textColor : mutedColor, "Clear",
                UiDrawCommand.TextWeight.REGULAR);
        hit(clrX, keyBtnY, clearW, keyBtnH, () -> {
            selected.setKeyCode(-1);
            if (bindingModuleTarget == selected) cancelKeybindCapture();
        });
    }

    /** Capsule radius for a control of height {@code h}, scaled down by a low Global Curvature. */
    private float pillR(float h) {
        return h * 0.5f * Math.min(1f, cornerScale);
    }

    /** A raised card surface: its own small glass pane on Liquid Glass, the theme's card fill elsewhere. */
    private void surfaceCard(UiDisplayList.Builder b, float x, float y, float w, float h, float r) {
        if (lg) {
            glassPane(b, x, y, w, h, r, 1f, 0x2a, false, 0);
            if (cardFillCustom) b.rect(x, y, w, h, r, cardBg);
        } else {
            b.rect(x, y, w, h, r, cardBg);
        }
        if (cardBorderCustom) b.border(x, y, w, h, r, 1f, cardBorder);
    }

    private static String groupKey(Module module, SettingGroup group) {
        return group.title() == null ? null : module.getName() + "|" + group.title();
    }

    /** Groups reduced to the settings whose name (or group title) contains {@code filter}. */
    private static List<SettingGroup> filterGroups(List<SettingGroup> groups, String filter) {
        List<SettingGroup> out = new ArrayList<>();
        for (SettingGroup group : groups) {
            boolean titleMatch = group.title() != null && group.title().toLowerCase(Locale.ROOT).contains(filter);
            List<Setting> kept = new ArrayList<>();
            for (Setting setting : group.settings()) {
                if (titleMatch || setting.getName().toLowerCase(Locale.ROOT).contains(filter)
                        || safeText(setting.getDescription()).toLowerCase(Locale.ROOT).contains(filter)) {
                    kept.add(setting);
                }
            }
            if (kept.stream().anyMatch(st -> SUB_VISIBLE.getOrDefault(st, true))) {
                out.add(new SettingGroup(group.title(), kept));
            }
        }
        return out;
    }

    private void beginModuleKeyCapture(Module module) {
        cancelKeybindCapture();
        bindingModuleTarget = module;
        bindingModuleKey = module != null;
        if (module != null) module.setBinding(true);
    }

    private void beginSettingKeyCapture(KeybindSetting keybind) {
        cancelKeybindCapture();
        binding = keybind;
        bindingModuleTarget = keybind.getModule();
        if (bindingModuleTarget != null) bindingModuleTarget.setBinding(true);
    }

    private void beginConfigKeyCapture(String configName) {
        cancelKeybindCapture();
        bindingConfigName = configName;
    }

    private int settingHeight(Setting setting) {
        if (setting instanceof ModeSetting mode) {
            int total = mode.getModes().size();
            int cols = Math.max(1, total <= 4 ? total : (total <= 6 ? 3 : 4));
            int rows = Math.max(1, (total + cols - 1) / cols);
            // label band (MODE_GRID_TOP) + cells and gaps + tray inset + bottom padding; see drawSettingRow.
            return Math.round(MODE_GRID_TOP + rows * MODE_CELL_H + (rows - 1) * MODE_CELL_GAP + MODE_TRAY_PAD + 12f);
        }
        if (setting instanceof SliderSetting) return 46;
        return 40;
    }

    /**
     * Width a single-line row's trailing control occupies, measured from the row's right edge (its
     * 14px margin included). Mirrors the sizes drawSettingRow uses for each control; a slider counts
     * its value pill plus a minimum usable track.
     */
    private float controlReserve(Setting setting, float s) {
        if (setting instanceof BooleanSetting bool) {
            if (!bool.isBindable()) return 34f * s + 14f * s;
            String keyLabel = bindingBool == bool ? "Press a key…"
                    : (bool.getKeyCode() == -1 ? "Bind" : bool.getKeyDisplayName())
                      + (bool.getKeyCode() != -1 && bool.isKeyHoldMode() ? " · Hold" : "");
            float pillW = Math.min(110f * s, Math.max(46f * s,
                    GpuUiFontMetrics.textWidth(keyLabel, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s));
            return 34f * s + 14f * s + 8f * s + pillW;
        }
        if (setting instanceof SliderSetting slider) {
            float valW = Math.max(52f * s, GpuUiFontMetrics.textWidth(sliderValueText(slider), 10f * s,
                    UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s);
            return valW + 14f * s + 12f * s + 60f * s + 12f * s;
        }
        if (setting instanceof ColorSetting) return 22f * s + 14f * s + 62f * s;
        if (setting instanceof KeybindSetting keybind) {
            Module target = keybind.getModule();
            String label = binding == keybind ? "Press a key…" : target == null ? "None" : target.getKeyDisplayName();
            return Math.min(140f * s, Math.max(64f * s,
                    GpuUiFontMetrics.textWidth(label, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 24f * s)) + 14f * s;
        }
        if (setting instanceof StringSetting) return 90f * s + 14f * s;
        if (setting instanceof ConfigKeybindListSetting) {
            return GpuUiFontMetrics.textWidth("Open", 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 32f * s + 14f * s;
        }
        return 0f;
    }

    private static String sliderValueText(SliderSetting slider) {
        return String.format(Locale.ROOT, "%." + slider.getDecimalPlaces() + "f", slider.getValue())
                + sliderUnit(slider.getName(), slider.getMax());
    }

    /** Mode-row geometry (unscaled px): grid top below the row's label band, cell size and spacing, tray inset. */
    private static final float MODE_GRID_TOP = 33f, MODE_CELL_H = 26f, MODE_CELL_GAP = 5f, MODE_TRAY_PAD = 3f;
    /** Vertical centre of a mode row's label, inside the band above its grid. */
    private static final float MODE_LABEL_MID = 16f;

    private void drawSettingRow(UiDisplayList.Builder b, Setting setting, float rx, float ry,
                                float rw, float rh, float s, float mx, float my) {
        boolean hover = hovered(mx, my, rx, ry, rw, rh);
        if (showSeparators) b.rect(rx + 14f * s, ry + rh - Math.max(1f, s), rw - 28f * s, Math.max(1f, s), 0, lineSubtleColor);
        boolean modified = setting.isModified();
        // Single-line rows centre their label on the row; a mode row keeps it in the band above its grid.
        float labelMid = setting instanceof ModeSetting ? ry + MODE_LABEL_MID * s : ry + rh * 0.5f;
        if (hover) {
            b.rect(rx + 4f * s, ry + 2f * s, rw - 8f * s, rh - 4f * s, Math.max(0f, cardRadius - 4f) * s, hoverWash(0x10));
            String desc = safeText(setting.getDescription()).trim();
            String more = modified ? "Click the dot (or right-click) to reset it to its default."
                    : pinnable(setting) ? "Right-click to pin it to Quick Settings." : "";
            if (!more.isEmpty()) desc = desc.isEmpty() ? more : desc + " " + more;
            if (!desc.isEmpty()) {
                hoveredTooltipTitle = setting.getName();
                hoveredTooltipDesc = desc;
            }
        }
        // Right-click anywhere on the row: reset to default, pin to / unpin from Quick Settings.
        hitModuleCardRight(rx, ry, rw, rh, () -> openSettingMenu(setting));
        // A slider's unit is shown on its value, so a "(ms)" in the name would say it twice.
        String rowLabel = setting instanceof SliderSetting ? stripUnitSuffix(setting.getName()) : setting.getName();
        if (rowLabelPrefix != null) rowLabel = rowLabelPrefix + rowLabel;
        // Every single-line row has its control at a fixed spot on the same line. Fit the label (plus its
        // modified dot and Blatant tag) to the space actually left of that control, so no name can ever
        // run under a toggle, slider, button or field -- it is ellipsised instead.
        if (!(setting instanceof ModeSetting)) {
            float tags = (modified ? 9f * s : 0f) + (setting.isBlatant()
                    ? GpuUiFontMetrics.textWidth("Blatant", 8f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s : 0f);
            float labelMaxW = Math.max(0f, rw - 16f * s - controlReserve(setting, s) - 10f * s - tags);
            rowLabel = UiKit.fit(rowLabel, labelMaxW, 11.5f * s);
        }
        textMid(b, rx + 16f * s, labelMid, 11.5f * s, textColor, rowLabel, UiDrawCommand.TextWeight.REGULAR);
        float afterName = rx + 16f * s + GpuUiFontMetrics.textWidth(rowLabel, 11.5f * s) + 6f * s;
        float resetDotX = Float.NaN;
        if (modified) {
            // The changed-from-default dot doubles as a reset button: it grows into a ring on hover.
            resetDotX = afterName + 2f * s;
            boolean dotHover = hovered(mx, my, resetDotX - 7f * s, labelMid - 7f * s, 14f * s, 14f * s);
            if (dotHover) {
                b.circle(resetDotX, labelMid, 5.5f * s, withA(accentArgb(), 0x40));
                b.circle(resetDotX, labelMid, 3f * s, accentArgb());
                hoveredTooltipTitle = "Reset " + setting.getName();
                hoveredTooltipDesc = "Changed from its default. Click to put it back.";
            } else {
                b.circle(resetDotX, labelMid, 2f * s, accentArgb());
            }
            afterName += 9f * s;
        }
        if (setting.isBlatant()) {
            float tagW = GpuUiFontMetrics.textWidth("Blatant", 8f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 12f * s;
            float tagH = Math.round(14f * s), tagY = Math.round(labelMid - tagH * 0.5f);
            b.border(afterName, tagY, tagW, tagH, tagH * 0.5f, Math.max(1f, 0.8f * s), 0x99ff8a8a);
            textCentered(b, afterName + tagW * 0.5f, tagY + tagH * 0.5f, 8f * s, 0xffff9a9a, "Blatant",
                    UiDrawCommand.TextWeight.SEMIBOLD);
            afterName += tagW + 6f * s;
        }

        if (setting instanceof BooleanSetting bool) {
            float tgW = 34f * s, tgH = 18f * s;
            float tgX = rx + rw - tgW - 14f * s, tgY = ry + (rh - tgH) * 0.5f;
            drawToggleSwitch(b, "bool_" + setting.getName(), tgX, tgY, tgW, tgH, bool.isEnabled(), s);
            hit(rx, ry, rw, rh, bool::toggle);
            if (bool.isBindable()) {
                // Own keybind for this toggle: click to capture (Esc clears), right-click switches
                // between Toggle (press flips it) and Hold (on only while held).
                boolean capturing = bindingBool == bool;
                String keyLabel = capturing ? "Press a key…"
                        : (bool.getKeyCode() == -1 ? "Bind" : bool.getKeyDisplayName())
                          + (bool.getKeyCode() != -1 && bool.isKeyHoldMode() ? " · Hold" : "");
                float pillH = Math.round(20f * s);
                float pillW = Math.round(Math.min(110f * s, Math.max(46f * s,
                        GpuUiFontMetrics.textWidth(keyLabel, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s)));
                float pillX = Math.round(tgX - 8f * s - pillW), pillY = Math.round(ry + (rh - pillH) * 0.5f);
                boolean pillHover = hovered(mx, my, pillX, pillY, pillW, pillH);
                b.rect(pillX, pillY, pillW, pillH, pillR(pillH),
                        capturing ? controlFill(0x48) : controlFill(pillHover ? 0x2a : 0x1a));
                textCentered(b, pillX + pillW * 0.5f, pillY + pillH * 0.5f, 9f * s,
                        capturing ? accentArgb() : (bool.getKeyCode() == -1 ? mutedColor : textColor),
                        UiKit.fit(keyLabel, pillW - 10f * s, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                hit(pillX, pillY, pillW, pillH, () -> {
                    cancelKeybindCapture();
                    bindingBool = bool;
                });
                hitModuleCardRight(pillX, pillY, pillW, pillH, () -> bool.setKeyHoldMode(!bool.isKeyHoldMode()));
                if (pillHover) {
                    hoveredTooltipTitle = setting.getName() + " key";
                    hoveredTooltipDesc = "Click to bind a key or mouse button (Esc clears). Right-click: "
                            + (bool.isKeyHoldMode() ? "switch to Toggle." : "switch to Hold (on only while held).");
                }
            }
        } else if (setting instanceof SliderSetting slider) {
            String value = sliderValueText(slider);
            float valW = Math.round(Math.max(52f * s,
                    GpuUiFontMetrics.textWidth(value, 10f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s));
            float valH = Math.round(20f * s);
            float valX = Math.round(rx + rw - valW - 14f * s), valY = Math.round(ry + (rh - valH) * 0.5f);
            float minTrackLeft = afterName + 12f * s;
            float maxTrackRight = valX - 12f * s;
            float trackW = Math.max(40f * s, Math.min(180f * s, maxTrackRight - minTrackLeft));
            float trackX = Math.max(minTrackLeft, maxTrackRight - trackW);
            trackW = Math.max(1f * s, Math.min(trackW, maxTrackRight - trackX));
            if (draggingSlider == slider) {
                sliderX = trackX;
                sliderW = trackW;
            }
            // Whole-pixel track so the capsule ends and the knob share one centre line.
            float trackH = Math.max(2f, Math.round(5f * s)), trackY = Math.round(ry + (rh - trackH) * 0.5f);
            double span = slider.getMax() - slider.getMin();
            double progress = span == 0 ? 0 : (slider.getValue() - slider.getMin()) / span;
            progress = Math.max(0, Math.min(1, progress));
            float fillW = (float) (trackW * progress);

            // Track background with sunken look
            b.rect(trackX, trackY, trackW, trackH, trackH * 0.5f, controlFill(0x24));

            if (fillW > 0.5f) accentFill(b, trackX, trackY, Math.max(trackH, fillW), trackH, pillR(trackH), 1f, sliderFillColor);

            // Knob with hover aura & 3D shadow
            float knobCenterX = trackX + fillW;
            float knobCenterY = trackY + trackH * 0.5f;
            boolean sliderActive = draggingSlider == slider || hovered(mx, my, trackX - 6f * s, ry, trackW + 12f * s, rh);
            if (sliderActive) {
                b.circle(knobCenterX, knobCenterY, 9.5f * s, (accentArgb() & 0x00ffffff) | 0x26000000);
            }
            b.circle(knobCenterX, knobCenterY + 1f * s, 7.5f * s, 0x45000000);
            b.circle(knobCenterX, knobCenterY, sliderActive ? 7.5f * s : 6.5f * s, mixRgb(KNOB_WHITE, 0xff000000 | controlRgb, 0.12f));

            b.rect(valX, valY, valW, valH, valH * 0.5f, controlFill(0x24));
            textCentered(b, valX + valW * 0.5f, valY + valH * 0.5f, 10f * s, accentArgb(), value,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            final float sliderStart = trackX, sliderWidth = trackW;
            hit(trackX - 6f * s, ry, trackW + 12f * s, rh, () -> {
                draggingSlider = slider;
                sliderX = sliderStart;
                sliderW = sliderWidth;
                // A click on the track jumps the value right away; dragging then continues from there.
                if (sliderWidth > 0) {
                    double p = clamp01((float) ((pressX - sliderStart) / sliderWidth));
                    slider.setValue(slider.getMin() + p * (slider.getMax() - slider.getMin()));
                }
            });
        } else if (setting instanceof ModeSetting mode) {
            // Whole-pixel grid: every cell is the same integer box, so the active capsule lines up
            // exactly with its neighbours' labels and sits at an even inset inside the tray.
            float gridX = Math.round(rx + 14f * s + MODE_TRAY_PAD * s), gridY = Math.round(ry + MODE_GRID_TOP * s);
            int total = mode.getModes().size();
            int cols = Math.max(1, total <= 4 ? total : (total <= 6 ? 3 : 4));
            int rows = Math.max(1, (total + cols - 1) / cols);
            float cellGap = Math.round(MODE_CELL_GAP * s);
            float trayPad = Math.round(MODE_TRAY_PAD * s);
            float totalW = Math.round(rw - 28f * s) - trayPad * 2f;
            float cellH = Math.round(MODE_CELL_H * s);
            float trayW = totalW + trayPad * 2f;
            float trayH = rows * cellH + (rows - 1) * cellGap + trayPad * 2f;
            float trayX = gridX - trayPad;
            float trayY = gridY - trayPad;
            // Concentric capsules: each cell is a full pill and the tray's radius is the cell's plus
            // the inset, so the gap between them is the same all the way round the corners.
            float cellR = pillR(cellH);

            // Sunken glass tray container (.seg)
            b.rect(trayX, trayY, trayW, trayH, cellR + trayPad, 0x1c000000);

            // One row: each cell is sized to its own label and the spare width shared equally, so a long
            // option ("Ultra Legit") is not truncated just because its short neighbours got equal room.
            // Several rows keep equal columns so the grid lines up.
            float[] cellXs = new float[total + 1];
            if (rows == 1) {
                float[] need = new float[total];
                float needSum = 0f;
                for (int i = 0; i < total; i++) {
                    need[i] = GpuUiFontMetrics.textWidth(mode.getModes().get(i), 9.5f * s,
                            UiDrawCommand.TextWeight.SEMIBOLD) + 22f * s;
                    needSum += need[i];
                }
                float room = totalW - cellGap * (total - 1);
                float spare = (room - needSum) / total;
                float acc = 0f;
                for (int i = 0; i < total; i++) {
                    cellXs[i] = gridX + Math.round(acc);
                    acc += (spare >= 0f ? need[i] + spare : need[i] * room / needSum) + cellGap;
                }
                cellXs[total] = gridX + Math.round(acc);
            }
            for (int i = 0; i < total; i++) {
                String modeName = mode.getModes().get(i);
                int col = i % cols;
                float bx = rows == 1 ? cellXs[i] : gridX + Math.round(col * (totalW + cellGap) / cols);
                float bxEnd = rows == 1 ? (i == total - 1 ? gridX + totalW : cellXs[i + 1] - cellGap)
                        : gridX + Math.round((col + 1) * (totalW + cellGap) / cols) - cellGap;
                float cellW = bxEnd - bx;
                float by = gridY + (i / cols) * (cellH + cellGap);
                boolean active = modeName.equals(mode.getCurrentMode());
                boolean btnHover = hovered(mx, my, bx, by, cellW, cellH);

                if (active) {
                    accentFill(b, bx, by, cellW, cellH, cellR, 1f, 0);
                } else if (btnHover) {
                    b.rect(bx, by, cellW, cellH, cellR, hoverK > 0f ? controlFill(Math.round(0x1a * Math.min(1.5f, hoverK))) : 0);
                }

                int text = active ? 0xff101214 : (btnHover ? titleColor : mutedColor);
                String label = UiKit.fit(modeName, Math.max(0f, cellW - 12f * s), 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD);
                textCentered(b, bx + cellW * 0.5f, by + cellH * 0.5f, 9.5f * s, text, label,
                        UiDrawCommand.TextWeight.SEMIBOLD);
                hit(bx, by, cellW, cellH, () -> mode.setCurrentMode(modeName));
            }
        } else if (setting instanceof ColorSetting color) {
            float chip = Math.round(22f * s);
            float chipX = Math.round(rx + rw - chip - 14f * s), chipY = Math.round(ry + (rh - chip) * 0.5f);
            // Appearance colours that still follow the theme show the theme value actually in use.
            Integer themeValue = color.isModified() ? null : themeValueFor(color);
            int shownRgb = themeValue != null ? themeValue : color.getValue().getRGB();
            b.circle(chipX + chip * 0.5f, chipY + chip * 0.5f, chip * 0.5f, shownRgb);
            String hexLabel = color.isRainbow() ? "Rainbow" : themeValue != null ? "Theme" : hexOf(color.getBaseValue().getRGB(), false);
            textMid(b, chipX - 8f * s - GpuUiFontMetrics.textWidth(hexLabel, 9.5f * s), ry + rh * 0.5f, 9.5f * s,
                    mutedColor, hexLabel, UiDrawCommand.TextWeight.REGULAR);
            b.border(chipX, chipY, chip, chip, chip * 0.5f, Math.max(1f, Math.round(s)), 0x33ffffff);
            hit(rx, ry, rw, rh, () -> openColorPicker(color));
        } else if (setting instanceof KeybindSetting keybind) {
            Module target = keybind.getModule();
            boolean capturing = binding == keybind;
            boolean holdable = target != null && target.getKeyCode() != -1 && !target.isOneShot() && !target.isKeyTriggered()
                    && !(target instanceof ClickGUIModule);
            String label = capturing ? "Press a key…" : target == null ? "None"
                    : target.getKeyDisplayName() + (holdable && target.isHoldMode() ? " · Hold" : "");
            if (holdable) {
                // Right-click flips between Toggle and Hold (on only while the key is held).
                hitModuleCardRight(rx, ry, rw, rh, () -> target.setHoldMode(!target.isHoldMode()));
                if (hovered(mx, my, rx, ry, rw, rh)) {
                    hoveredTooltipTitle = "Keybind";
                    hoveredTooltipDesc = "Click to bind a key (Esc clears). Right-click: "
                            + (target.isHoldMode() ? "switch to Toggle." : "switch to Hold (on only while held).");
                }
            }
            float btnH = Math.round(24f * s);
            float btnW = Math.round(Math.min(140f * s, Math.max(64f * s,
                    GpuUiFontMetrics.textWidth(label, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 24f * s)));
            float btnX = Math.round(rx + rw - btnW - 14f * s), btnY = Math.round(ry + (rh - btnH) * 0.5f);
            b.rect(btnX, btnY, btnW, btnH, btnH * 0.5f,
                    capturing ? controlFill(0x48) : controlFill(0x1a));
            textCentered(b, btnX + btnW * 0.5f, btnY + btnH * 0.5f, 9.5f * s, capturing ? accentArgb() : textColor,
                    UiKit.fit(label, btnW - 16f * s, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(rx, ry, rw, rh, () -> beginSettingKeyCapture(keybind));
        } else if (setting instanceof StringSetting string) {
            float textH = Math.round(24f * s);
            float textW = Math.round(Math.max(0f, Math.min(160f * s, rx + rw - 14f * s - (afterName + 8f * s))));
            float textX = Math.round(rx + rw - textW - 14f * s), textY = Math.round(ry + (rh - textH) * 0.5f);
            b.rect(textX, textY, textW, textH, textH * 0.5f,
                    string.isEditing() ? controlFill(0x32) : controlFill(0x1a));
            textMid(b, textX + 11f * s, textY + textH * 0.5f, 10f * s,
                    string.isEditing() ? accentArgb() : textColor,
                    UiKit.fit(string.getValue().isEmpty() ? "Click to edit" : string.getValue(),
                            Math.max(0f, textW - 22f * s), 10f * s),
                    UiDrawCommand.TextWeight.REGULAR);
            hit(rx, ry, rw, rh, () -> beginEdit(string));
        } else if (setting instanceof ConfigKeybindListSetting) {
            float btnH = Math.round(24f * s);
            float btnW = Math.round(GpuUiFontMetrics.textWidth("Open", 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 32f * s);
            float btnX = Math.round(rx + rw - btnW - 14f * s), btnY = Math.round(ry + (rh - btnH) * 0.5f);
            b.rect(btnX, btnY, btnW, btnH, btnH * 0.5f, controlFill(0x1a));
            textCentered(b, btnX + btnW * 0.5f, btnY + btnH * 0.5f, 9.5f * s, textColor, "Open",
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(rx, ry, rw, rh, () -> {
                settingsDrawerOpen = true;
                settingsTab = SettingsTab.CONFIG_BINDS;
                refreshSavedConfigs();
            });
        }
        // Registered last so it wins over the row's own click (toggle, slider grab, editor).
        if (!Float.isNaN(resetDotX)) {
            hit(resetDotX - 7f * s, labelMid - 7f * s, 14f * s, 14f * s, setting::resetToDefault);
        }
    }

    /** Content of the settings drawer: a close control, a segmented tab control and the selected tab. */
    private void drawDrawerContent(UiDisplayList.Builder b, float x, float y, float w, float h, float s,
                                   float mx, float my) {
        float pad = 18f * s;
        float headY = y + 18f * s;
        b.text(x + pad, headY + 2f * s, 15f * s, titleColor, "Settings", UiDrawCommand.TextWeight.SEMIBOLD);

        // No close button: Escape or the "Settings" item in the top pill closes the drawer.

        // Segmented tab control
        // Capsule tray with capsule segments inset evenly inside it (concentric radii), on whole pixels.
        float tabsX = Math.round(x + pad), tabsY = Math.round(headY + 28f * s);
        float tabsW = Math.round(w - 2f * pad), tabsH = Math.round(30f * s);
        float tabInset = Math.max(2f, Math.round(3f * s));
        b.rect(tabsX, tabsY, tabsW, tabsH, tabsH * 0.5f, 0x38000000);
        b.border(tabsX, tabsY, tabsW, tabsH, tabsH * 0.5f, Math.max(1f, Math.round(s)), lineBorderColor);
        SettingsTab[] tabs = SettingsTab.values();
        float segInnerW = tabsW - tabInset * 2f;
        float segH = tabsH - tabInset * 2f;
        for (int i = 0; i < tabs.length; i++) {
            SettingsTab tab = tabs[i];
            float segX = tabsX + tabInset + Math.round(i * segInnerW / tabs.length);
            float segW = tabsX + tabInset + Math.round((i + 1) * segInnerW / tabs.length) - segX;
            float segY = tabsY + tabInset;
            boolean active = settingsTab == tab;
            boolean hover = hovered(mx, my, segX, tabsY, segW, tabsH);
            if (active) {
                b.rect(segX, segY, segW, segH, segH * 0.5f, 0x24ffffff);
                b.border(segX, segY, segW, segH, segH * 0.5f, Math.max(1f, Math.round(s)), 0x33ffffff);
            } else if (hover) {
                b.rect(segX, segY, segW, segH, segH * 0.5f, 0x0fffffff);
            }
            String label = tab == SettingsTab.APPEARANCE ? "Appearance"
                    : tab == SettingsTab.CLIENT_CONTROLS ? "Client" : "Binds";
            String fitted = UiKit.fit(label, Math.max(0f, segW - 12f * s), 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD);
            textCentered(b, segX + segW * 0.5f, segY + segH * 0.5f, 9.5f * s,
                    active ? accentArgb() : (hover ? textColor : mutedColor), fitted,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(segX, tabsY, segW, tabsH, () -> {
                settingsTab = tab;
                appearanceScroll = 0f;
                if (tab == SettingsTab.CONFIG_BINDS) refreshSavedConfigs();
            });
        }

        float cy = tabsY + tabsH + 14f * s;
        float cardX = x + pad, cardW = w - 2f * pad;

        if (settingsTab == SettingsTab.APPEARANCE) {
            drawAppearanceInspector(b, cardX, cy, cardW, y + h - 14f * s, s, mx, my);
        } else if (settingsTab == SettingsTab.CLIENT_CONTROLS) {
            // ScreenShare Proof card
            boolean ssOn = ClickGUIModule.isSsProof();
            // Same toggle as every other on/off setting (the old 96px "On/Off" button left the title
            // ~80px to live in). The subtitle wraps to a second line rather than being cut, and the
            // card's height is derived from its content, so nothing can spill past its bottom edge.
            float proofPad = 14f * s;
            float iconBox = Math.round(36f * s);
            float tgW = 34f * s, tgH = 18f * s;
            float proofTextX = cardX + proofPad + iconBox + 12f * s;
            float proofTextMaxW = Math.max(0f, cardX + cardW - proofPad - tgW - 12f * s - proofTextX);
            float titleSize = 12.5f * s, subSize = 9.5f * s, subPitch = 13f * s, subGap = 7f * s;
            String proofTitle = UiKit.fit("Screenshare proof", proofTextMaxW, titleSize, UiDrawCommand.TextWeight.SEMIBOLD);
            List<String> proofSub = UiKit.wrap("Hides the menu from OBS, Discord and screenshots",
                    proofTextMaxW, subSize, 2);
            float titleCapH = titleSize * GpuUiFontMetrics.CAP_HEIGHT, subCapH = subSize * GpuUiFontMetrics.CAP_HEIGHT;
            float proofBlockH = titleCapH + (proofSub.isEmpty() ? 0f
                    : subGap + subCapH + (proofSub.size() - 1) * subPitch);
            float proofCardH = Math.round(Math.max(iconBox + proofPad * 2f, proofBlockH + 34f * s));
            b.rect(cardX, cy, cardW, proofCardH, cardRadius * s, cardBg);

            float iconX = Math.round(cardX + proofPad), iconY = Math.round(cy + (proofCardH - iconBox) * 0.5f);
            b.rect(iconX, iconY, iconBox, iconBox, Math.max(0f, cardRadius - 2f) * s, ssOn ? accentArgb() : controlFill(0x0c));
            float glyph = Math.round(20f * s);
            ClassicIcons.draw(b, "shield", iconX + Math.round((iconBox - glyph) * 0.5f), iconY + Math.round((iconBox - glyph) * 0.5f),
                    glyph, ssOn ? 0xff101214 : mutedColor);

            float blockTop = cy + (proofCardH - proofBlockH) * 0.5f;
            textMid(b, proofTextX, blockTop + titleCapH * 0.5f, titleSize, textColor, proofTitle,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            float subMid = blockTop + titleCapH + subGap + subCapH * 0.5f;
            for (int li = 0; li < proofSub.size(); li++) {
                textMid(b, proofTextX, subMid + li * subPitch, subSize, mutedColor, proofSub.get(li),
                        UiDrawCommand.TextWeight.REGULAR);
            }

            drawToggleSwitch(b, "ss_proof", cardX + cardW - proofPad - tgW, cy + (proofCardH - tgH) * 0.5f,
                    tgW, tgH, ssOn, s);
            hit(cardX, cy, cardW, proofCardH, () -> {
                if (ClickGUIModule.ssProof != null) {
                    ClickGUIModule.ssProof.setEnabled(!ClickGUIModule.ssProof.isEnabled());
                }
            });

            cy += proofCardH + 12f * s;
            // The HUD editor already has its own button in the top bar, so it is not repeated here.
            drawClientSettings(b, cardX, cy, cardW, y + h - 14f * s, s, mx, my);
        } else {
            drawConfigKeybinds(b, cardX, cy, cardW, y + h - 14f * s, s);
        }
    }

    private void drawAppearanceInspector(UiDisplayList.Builder b, float cardX, float contentTop,
                                         float cardW, float contentBottom, float s, float mx, float my) {
        ClickGUIModule style = clickGuiModule();
        if (style == null) {
            b.text(cardX + 4f * s, contentTop + 18f * s, 11f * s, mutedColor, "ClickGUI style settings are unavailable.");
            return;
        }

        float viewportHeight = Math.max(0f, contentBottom - contentTop);
        b.clipPush(cardX, contentTop, cardW, viewportHeight, cardRadius * s);
        clipX = cardX; clipY = contentTop; clipW = cardW; clipH = viewportHeight;
        clippingHits = true;
        appearanceScrollShown = easeScroll(appearanceScrollShown, appearanceScroll);
        float cy = contentTop - appearanceScrollShown * s;

        b.text(cardX + 4f * s, cy, 10f * s, mutedColor, "Theme", UiDrawCommand.TextWeight.SEMIBOLD);
        cy += 18f * s;

        // Theme cards render the real surface material as their preview.
        List<String> themes = style.theme.getModes();
        int columns = 2;
        float gap = 8f * s;
        float tileW = (cardW - gap * (columns - 1)) / columns;
        float previewH = 44f * s, tileH = previewH + 26f * s;
        float gridTop = cy;
        for (int i = 0; i < themes.size(); i++) {
            String themeName = themes.get(i);
            float tx = cardX + (i % columns) * (tileW + gap);
            float ty = gridTop + (i / columns) * (tileH + gap);
            boolean selectedTheme = style.theme.is(themeName);
            boolean hover = hovered(mx, my, tx, ty, tileW, tileH);
            b.rect(tx, ty, tileW, tileH, cardRadius * s, cardBg);
            // The preview is clipped to its own rounded rect so the marbling never spills past it.
            float pvX = tx + 4f * s, pvY = ty + 4f * s, pvW = tileW - 8f * s;
            b.clipPush(pvX, pvY, pvW, previewH, Math.max(0f, cardRadius - 4f) * s);
            interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface tileSurface =
                    interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.byName(themeName);
            if (!tileSurface.isTheme()) {
                b.material(pvX, pvY, pvW, previewH, Math.max(0f, cardRadius - 4f) * s,
                        tileSurface.preview(UiThemeMaterial.APEXX.accent() & 0x00ffffff));
            } else if (UiThemeMaterial.LIQUID_GLASS.name().equalsIgnoreCase(themeName)) {
                int ga = UiThemeMaterial.LIQUID_GLASS.accent() & 0x00ffffff;
                b.gradient(pvX, pvY, pvW, previewH, Math.max(0f, cardRadius - 4f) * s, ga | 0x40000000, 0x22ffffff, ga | 0x18000000, 0x10ffffff);
            } else {
                b.material(pvX, pvY, pvW, previewH, Math.max(0f, cardRadius - 4f) * s, UiThemeMaterial.preview(themeName));
            }
            b.clipPop();
            b.border(pvX, pvY, pvW, previewH, Math.max(0f, cardRadius - 4f) * s, s, 0x1affffff);
            b.text(tx + 10f * s, ty + previewH + 10f * s, 10f * s,
                    selectedTheme ? accentArgb() : textColor, themeName, UiDrawCommand.TextWeight.SEMIBOLD);
            b.border(tx, ty, tileW, tileH, cardRadius * s, selectedTheme ? 1.5f * s : s,
                    selectedTheme ? accentArgb() : (hover ? 0x40ffffff : cardBorder));
            final String chosen = themeName;
            hit(tx, ty, tileW, tileH, () -> style.theme.setCurrentMode(chosen));
        }
        int themeRows = (themes.size() + columns - 1) / columns;
        cy = gridTop + themeRows * (tileH + gap) - gap + 14f * s;

        // Every ClickGUI appearance setting, grouped. Each group folds away from its header.
        if (ClickGUIModule.surfaceDetail != null && !ClickGUIModule.activeSurface().isTheme()) {
            cy = drawAppearanceSettingGroup(b, "Surface", List.of(
                    ClickGUIModule.surfaceDetail, ClickGUIModule.surfaceGrain, ClickGUIModule.surfaceMotion),
                    cardX, cy, cardW, s, mx, my);
        }
        cy = drawAppearanceSettingGroup(b, "Background Effects", List.of(ClickGUIModule.backgroundOverlay,
                ClickGUIModule.atmWater,
                ClickGUIModule.atmAurora, ClickGUIModule.auroraOpacity, ClickGUIModule.atmStarfield,
                ClickGUIModule.starfieldOpacity,
                ClickGUIModule.waterColours, ClickGUIModule.waterColour1, ClickGUIModule.waterColour2,
                ClickGUIModule.waterColour3, ClickGUIModule.waterOpacity, ClickGUIModule.waterFlowSpeed,
                ClickGUIModule.waterMouseRipple, ClickGUIModule.waterQuality, ClickGUIModule.waterColourMode,
                ClickGUIModule.waterCurl, ClickGUIModule.waterSplatRadius, ClickGUIModule.waterSplatForce,
                ClickGUIModule.waterDyeDissipation, ClickGUIModule.waterVelocityDissipation,
                ClickGUIModule.waterPressureIterations, ClickGUIModule.waterShading, ClickGUIModule.waterBloom,
                ClickGUIModule.waterRandomSplats), cardX, cy, cardW, s, mx, my);
        // Both blurs side by side, each saying which layer it blurs.
        cy = drawAppearanceSettingGroup(b, "Blur", java.util.Arrays.asList(ClickGUIModule.blurBackground,
                ClickGUIModule.blurStrength, ClickGUIModule.glassBlur), cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Glass", java.util.Arrays.asList(ClickGUIModule.glassOpacity,
                ClickGUIModule.panelOpacity, ClickGUIModule.outerStroke), cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Colors", java.util.Arrays.asList(ClickGUIModule.customHighlight,
                ClickGUIModule.highlightColor1, ClickGUIModule.highlightGradient, ClickGUIModule.highlightColor2,
                ClickGUIModule.gradientMenus, ClickGUIModule.panelTint, ClickGUIModule.cardFillColor,
                ClickGUIModule.cardBorderColor, ClickGUIModule.toggleOnColour, ClickGUIModule.toggleOffColour,
                ClickGUIModule.sliderFillColour, ClickGUIModule.selectionColour, ClickGUIModule.rimColour),
                cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Layout", java.util.Arrays.asList(ClickGUIModule.menuLayout,
                ClickGUIModule.menuScaleMode, ClickGUIModule.menuScale), cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Corners and Lines", java.util.Arrays.asList(ClickGUIModule.globalCurvature,
                ClickGUIModule.separator, ClickGUIModule.sidebarSeparator, ClickGUIModule.logoSeparator),
                cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Hover", java.util.Arrays.asList(ClickGUIModule.hover,
                ClickGUIModule.hoverStrength), cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Text", List.of(ClickGUIModule.fontFamily, ClickGUIModule.fontSize,
                ClickGUIModule.textColour, ClickGUIModule.mutedTextColour, ClickGUIModule.customHeaderColour,
                ClickGUIModule.customTextColor), cardX, cy, cardW, s, mx, my);
        cy = drawAppearanceSettingGroup(b, "Sounds", java.util.Arrays.asList(ClickGUIModule.uiSounds,
                ClickGUIModule.soundPack, ClickGUIModule.soundVolume, ClickGUIModule.soundPitchVariation,
                ClickGUIModule.soundHover, ClickGUIModule.soundClick, ClickGUIModule.soundToggle,
                ClickGUIModule.soundOpenClose, ClickGUIModule.soundSlider, ClickGUIModule.soundKeybind),
                cardX, cy, cardW, s, mx, my);

        float contentHeight = Math.max(0f, cy - (contentTop - appearanceScrollShown * s));
        float maxScroll = Math.max(0f, (contentHeight - viewportHeight) / s);
        appearanceScroll = Math.max(0f, Math.min(appearanceScroll, maxScroll));
        clippingHits = false;
        b.clipPop();
    }

    /**
     * Client-wide behaviour that applies to every module (the timing/execution switches), the
     * Screenshare proof fallback, the menu key, help and sign-out. Scrolls with the drawer.
     */
    private void drawClientSettings(UiDisplayList.Builder b, float cardX, float contentTop, float cardW,
                                    float contentBottom, float s, float mx, float my) {
        float viewportHeight = Math.max(0f, contentBottom - contentTop);
        b.clipPush(cardX, contentTop, cardW, viewportHeight, cardRadius * s);
        clipX = cardX; clipY = contentTop; clipW = cardW; clipH = viewportHeight;
        clippingHits = true;
        // Ease the shown offset toward the wheel target here too -- only the Appearance tab did this,
        // so the Client tab's content never moved however far you scrolled.
        appearanceScrollShown = easeScroll(appearanceScrollShown, appearanceScroll);
        float top = contentTop - appearanceScrollShown * s;
        float cy = top;

        List<Setting> behaviour = new ArrayList<>();
        for (Setting setting : new Setting[] { ClickGUIModule.executionMode,
                ClickGUIModule.gcdRotations, ClickGUIModule.subTickVariation }) {
            if (setting != null) behaviour.add(setting);
        }
        cy = drawSettingGroup(b, "Module behaviour", behaviour, cardX, cy, cardW, s, mx, my);

        if (ClickGUIModule.ssProofFailOpen != null) {
            cy = drawSettingGroup(b, "Screenshare proof", List.of(ClickGUIModule.ssProofFailOpen),
                    cardX, cy, cardW, s, mx, my);
        }

        // Only "GUI Key": the base module's "Keybind" row edits the same key and is hidden.
        ClickGUIModule style = clickGuiModule();
        List<Setting> menuKey = new ArrayList<>();
        if (style != null) {
            for (Setting setting : style.getSettings()) {
                if (setting instanceof KeybindSetting keybind && keybind.getModule() == style
                        && "GUI Key".equals(setting.getName())) menuKey.add(setting);
            }
        }
        cy = drawSettingGroup(b, "Menu", menuKey, cardX, cy, cardW, s, mx, my);

        // Help: replay the first-run tour, or open the devlog.
        cy += 4f * s;
        b.text(cardX + 4f * s, cy, 10f * s, mutedColor, "Help", UiDrawCommand.TextWeight.SEMIBOLD);
        cy += 18f * s;
        float helpH = Math.round(30f * s), helpGap = Math.round(8f * s);
        float helpW = Math.round((cardW - helpGap) * 0.5f);
        UiKit.button(b, kitHost, "Replay tour", cardX, cy, helpW, helpH, s, UiKit.Style.SECONDARY, true, mx, my,
                this::replayTour);
        UiKit.button(b, kitHost, WhatsNewPanel.hasUnread() ? "What's new  •" : "What's new", cardX + helpW + helpGap, cy,
                cardW - helpW - helpGap, helpH, s, UiKit.Style.SECONDARY, true, mx, my, whatsNew::open);
        cy += helpH + 12f * s;

        // Account: sign out clears the saved session (remembered sign-in included) and returns to the
        // sign-in prompt. Two clicks, like Self-Destruct, so a stray click cannot sign you out.
        b.text(cardX + 4f * s, cy, 10f * s, mutedColor, "Account", UiDrawCommand.TextWeight.SEMIBOLD);
        cy += 18f * s;
        boolean signOutArmed = signOutArmedAt != 0L && nanoTime() - signOutArmedAt < 3_500_000_000L;
        UiKit.button(b, kitHost, signOutArmed ? "Click again to sign out" : "Sign out", cardX, cy, cardW, helpH, s,
                UiKit.Style.SECONDARY, true, mx, my, () -> {
                    if (signOutArmedAt != 0L && nanoTime() - signOutArmedAt < 3_500_000_000L) {
                        signOutArmedAt = 0L;
                        interstellarTikiTiks.apexx.client.ApexxAuth.signOutAndPrompt("You signed out. Sign in to continue.");
                    } else {
                        signOutArmedAt = nanoTime();
                    }
                });
        cy += helpH + 12f * s;

        float maxScroll = Math.max(0f, ((cy - top) - viewportHeight) / s);
        appearanceScroll = Math.max(0f, Math.min(appearanceScroll, maxScroll));
        clippingHits = false;
        b.clipPop();
    }

    private void drawConfigKeybinds(UiDisplayList.Builder b, float x, float top, float w, float bottom, float s) {
        float cy = top;
        ConfigKeybindManager manager = ConfigKeybindManager.getInstance();
        // Configs are cloud-only, so list the player's cloud configs (refreshed in the background when the
        // menu or this tab opens). Before that list arrives, fall back to whatever is known locally.
        Set<String> names = new LinkedHashSet<>();
        Map<String, PrivateConfigManager.PrivateConfigInfo> cloud = cloudProfiles;
        for (String name : savedConfigs) {
            if (name == null || name.isBlank() || PrivateConfigManager.AUTOSAVE_SLUG.equals(name)) continue;
            boolean known = cloud.isEmpty()
                    ? !("default".equalsIgnoreCase(name) && CloudConfigs.signedIn()) // no cloud "default" exists yet
                    : cloud.containsKey(name.toLowerCase(Locale.ROOT));
            if (known) names.add(name);
        }
        for (ConfigKeybindManager.ConfigKeybind keybind : manager.getKeybinds()) {
            if (keybind.configName != null && !keybind.configName.isBlank()) names.add(keybind.configName);
        }

        b.text(x + 4f * s, cy, 10f * s, mutedColor, "Press a key to switch to a config", UiDrawCommand.TextWeight.SEMIBOLD);
        cy += 18f * s;
        if (names.isEmpty()) {
            b.text(x + 4f * s, cy + 4f * s, 9.5f * s, muted2Color,
                    UiKit.fit(CloudConfigs.signedIn() ? "No cloud configs yet. Save one on the Configs page first."
                            : "Sign in to bind your cloud configs.", w - 8f * s, 9.5f * s));
        }
        float rowH = 38f * s;
        // A status line, when shown, gets its own strip under the list instead of drawing over the last row.
        boolean hasStatus = !configBindStatusMessage.isBlank();
        float rowBottom = hasStatus ? bottom - 20f * s : bottom;
        b.clipPush(x, cy, w, Math.max(0f, rowBottom - cy), 0);
        clipX = x; clipY = cy; clipW = w; clipH = Math.max(0f, rowBottom - cy);
        clippingHits = true;
        hotkeyListX = x; hotkeyListY = cy; hotkeyListW = w; hotkeyListH = Math.max(0f, rowBottom - cy);
        float hotkeyContent = names.size() * (rowH + 3f * s);
        hotkeyScroll = Math.max(0f, Math.min(hotkeyScroll, Math.max(0f, hotkeyContent - hotkeyListH)));
        int i = 0;
        for (String name : names) {
            float ry = cy - hotkeyScroll + i++ * (rowH + 3f * s);
            ConfigKeybindManager.ConfigKeybind keybind = manager.get(name);
            String label = name.equals(bindingConfigName) ? "Press a key…"
                    : keybind == null ? "None" : keybind.getDisplayName();
            ry = Math.round(ry);
            float rowMid = ry + rowH * 0.5f;
            b.rect(x, ry, w, rowH, Math.max(0f, cardRadius - 2f) * s, cardBg);
            float keyH = Math.round(24f * s);
            float keyW = Math.round(Math.min(110f * s, Math.max(64f * s,
                    GpuUiFontMetrics.textWidth(label, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 24f * s)));
            float keyX = Math.round(x + w - keyW - 7f * s), keyY = Math.round(rowMid - keyH * 0.5f);
            float clearW = GpuUiFontMetrics.textWidth("Clear", 9.5f * s) + 10f * s;
            float clearX = keyX - 6f * s - clearW;
            float nameMaxW = Math.max(0f, (keybind != null ? clearX : keyX) - 10f * s - (x + 12f * s));
            textMid(b, x + 12f * s, rowMid, 10f * s, textColor,
                    UiKit.fit(name, nameMaxW, 10f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            b.rect(keyX, keyY, keyW, keyH, keyH * 0.5f,
                    name.equals(bindingConfigName) ? controlFill(0x48) : controlFill(0x1a));
            textCentered(b, keyX + keyW * 0.5f, rowMid, 9.5f * s,
                    name.equals(bindingConfigName) ? accentArgb() : textColor,
                    UiKit.fit(label, keyW - 16f * s, 9.5f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            hit(keyX, keyY, keyW, keyH, () -> beginConfigKeyCapture(name));
            if (keybind != null) {
                textCentered(b, clearX + clearW * 0.5f, rowMid, 9.5f * s, mutedColor, "Clear",
                        UiDrawCommand.TextWeight.REGULAR);
                hit(clearX, keyY, clearW, keyH, () -> {
                    manager.setKeybind(name, -1, -1);
                });
            }
        }
        clippingHits = false;
        b.clipPop();
        if (hasStatus) textMid(b, x + 4f * s, bottom - 8f * s, 8.5f * s, accentArgb(),
                UiKit.fit(configBindStatusMessage, w - 8f * s, 8.5f * s), UiDrawCommand.TextWeight.REGULAR);
    }

    private float drawAppearanceSettingGroup(UiDisplayList.Builder b, String title,
                                             List<? extends Setting> settings,
                                             float x, float y, float w, float s, float mx, float my) {
        ClickGUIModule style = clickGuiModule();
        List<Setting> exposed = new ArrayList<>();
        for (Setting setting : settings) {
            if (setting == null || !ClickGUIModule.shouldExposeSetting(setting)) continue;
            // Honour dependsOn (e.g. Hover Strength only while Hover is on).
            ClassicSettingLayout.Row row = style == null ? null : ClassicSettingLayout.row(style, setting);
            if (row != null && !row.visible()) continue;
            exposed.add(setting);
        }
        return drawSettingGroup(b, title, exposed, x, y, w, s, mx, my, "appearance|" + title);
    }

    private record SettingGroup(String title, List<Setting> settings) { }

    /**
     * A module's visible settings split into its own sections (in the order they first appear),
     * with settings marked advanced collected into a final "Advanced" group. The module's
     * built-in "Keybind" row is left out because the pinned footer already edits that key.
     */
    private static List<SettingGroup> settingGroups(Module module) {
        Map<String, List<Setting>> sections = new java.util.LinkedHashMap<>();
        List<Setting> advanced = new ArrayList<>();
        SUB_VISIBLE.clear();
        SUB_DEPTH.clear();
        // Dependent settings stay in the list while their condition is off: they are drawn folded shut
        // (height 0) and drop down, nested under their parent, once it is met.
        for (ClassicSettingLayout.Row row : ClassicSettingLayout.rows(module)) {
            Setting setting = row.setting();
            if (!setting.isVisible()) continue;
            if (setting instanceof KeybindSetting keybind && keybind.getModule() == module) continue;
            String dep = setting.getDependsOn();
            if (dep != null && !dep.isBlank()) SUB_VISIBLE.put(setting, row.visible());
            if (setting.isAdvanced()) { advanced.add(setting); continue; }
            String section = setting.getSection() == null ? "" : setting.getSection().trim();
            sections.computeIfAbsent(section, key -> new ArrayList<>()).add(setting);
        }
        List<SettingGroup> groups = new ArrayList<>();
        List<Setting> loose = sections.remove("");
        if (loose != null) groups.add(new SettingGroup(null, nestDependents(module, loose)));
        sections.forEach((title, list) -> groups.add(new SettingGroup(title, nestDependents(module, list))));
        if (!advanced.isEmpty()) groups.add(new SettingGroup("Advanced", nestDependents(module, advanced)));
        // A group whose every row is a folded-shut dependent would be an empty card.
        groups.removeIf(g -> g.settings().stream().noneMatch(st -> SUB_VISIBLE.getOrDefault(st, true)));
        return groups;
    }

    /** Dependent settings currently tracked for folding (true = condition met), and their nesting depth. */
    private static final Map<Setting, Boolean> SUB_VISIBLE = new java.util.IdentityHashMap<>();
    private static final Map<Setting, Integer> SUB_DEPTH = new java.util.IdentityHashMap<>();

    /** The setting named by the first condition of {@code setting}'s dependsOn, or null. */
    private static Setting dependencyParent(Module module, Setting setting) {
        String dep = setting.getDependsOn();
        if (dep == null || dep.isBlank()) return null;
        String first = dep.split(",", -1)[0].trim();
        if (first.startsWith("!")) first = first.substring(1).trim();
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(0, colon).trim();
        if (first.isEmpty()) return null;
        Setting parent = module.getSetting(first);
        return parent == setting ? null : parent;
    }

    /** Reorders a group so each dependent sits directly under its parent (recursively), recording depth. */
    private static List<Setting> nestDependents(Module module, List<Setting> list) {
        java.util.Set<Setting> members = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        members.addAll(list);
        Map<Setting, List<Setting>> children = new java.util.IdentityHashMap<>();
        List<Setting> roots = new ArrayList<>();
        for (Setting st : list) {
            Setting parent = dependencyParent(module, st);
            if (parent != null && members.contains(parent)) children.computeIfAbsent(parent, k -> new ArrayList<>()).add(st);
            else roots.add(st);
        }
        List<Setting> out = new ArrayList<>(list.size());
        java.util.Set<Setting> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Setting root : roots) emitNested(root, 0, children, out, seen);
        // Anything left (a dependency cycle) keeps its original position at the end.
        for (Setting st : list) if (seen.add(st)) out.add(st);
        return out;
    }

    private static void emitNested(Setting st, int depth, Map<Setting, List<Setting>> children,
                                   List<Setting> out, java.util.Set<Setting> seen) {
        if (!seen.add(st)) return;
        out.add(st);
        if (depth > 0) SUB_DEPTH.put(st, depth);
        for (Setting child : children.getOrDefault(st, List.of())) emitNested(child, depth + 1, children, out, seen);
    }

    private static final class Fold {
        float value;
        long frame = -1;
    }

    private final Map<Setting, Fold> folds = new java.util.IdentityHashMap<>();
    private long foldFrame;

    /** 0..1 open amount of a dependent setting row, eased once per frame toward its condition. */
    private float fold(Setting setting) {
        Boolean open = SUB_VISIBLE.get(setting);
        if (open == null) return 1f;
        float target = open ? 1f : 0f;
        Fold f = folds.get(setting);
        if (f == null) {
            f = new Fold();
            f.value = target;
            f.frame = foldFrame;
            folds.put(setting, f);
            return target;
        }
        if (f.frame != foldFrame) {
            f.frame = foldFrame;
            f.value += (target - f.value) * Math.min(1f, frameDelta * 14f);
            if (Math.abs(f.value - target) < 0.003f) f.value = target;
        }
        // Smoothstep so the drop-down eases at both ends.
        return f.value * f.value * (3f - 2f * f.value);
    }

    /** Unscaled height {@link #drawSettingGroup} uses for a group ({@code collapseKey} null = not collapsible). */
    private float groupHeight(SettingGroup group, String collapseKey) {
        float h = group.title() == null ? 0f : 18f;
        if (group.title() != null && collapseKey != null && COLLAPSED_GROUPS.contains(collapseKey)) return h + 6f;
        for (Setting setting : group.settings()) h += settingHeight(setting) * fold(setting);
        return h + 12f;
    }

    /** One titled card of setting rows; returns the y just below it. A null title draws no header. */
    private float drawSettingGroup(UiDisplayList.Builder b, String title, List<Setting> settings,
                                   float x, float y, float w, float s, float mx, float my) {
        return drawSettingGroup(b, title, settings, x, y, w, s, mx, my, null);
    }

    /**
     * As above; with a {@code collapseKey} the title becomes a header that folds the group away
     * (remembered for the session).
     */
    private float drawSettingGroup(UiDisplayList.Builder b, String title, List<Setting> settings,
                                   float x, float y, float w, float s, float mx, float my, String collapseKey) {
        if (settings.isEmpty()) return y;
        float cardY = y;
        boolean collapsible = title != null && collapseKey != null;
        boolean collapsed = collapsible && COLLAPSED_GROUPS.contains(collapseKey);
        if (title != null) {
            float size = 10f * s;
            float mid = y - GpuUiFontMetrics.topForCenter(0f, size);
            float tx = x + 4f * s;
            boolean headHover = collapsible && hovered(mx, my, x, y - 4f * s, w, 18f * s);
            int color = headHover ? textColor : mutedColor;
            if (collapsible) {
                float icon = Math.round(11f * s);
                UiIcons.draw(b, collapsed ? UiIcons.Icon.CHEVRON_RIGHT : UiIcons.Icon.CHEVRON_DOWN,
                        Math.round(tx - 2f * s), Math.round(mid - icon * 0.5f), icon, color);
                tx += icon + 2f * s;
                hit(x, y - 4f * s, w, 18f * s, () -> {
                    if (!COLLAPSED_GROUPS.remove(collapseKey)) COLLAPSED_GROUPS.add(collapseKey);
                });
            }
            textMid(b, tx, mid, size, color, title, UiDrawCommand.TextWeight.SEMIBOLD);
            if (collapsed) {
                int modified = 0;
                for (Setting setting : settings) if (setting.isModified()) modified++;
                String count = settings.size() + (settings.size() == 1 ? " setting" : " settings")
                        + (modified > 0 ? ", " + modified + " changed" : "");
                textMid(b, tx + GpuUiFontMetrics.textWidth(title, size, UiDrawCommand.TextWeight.SEMIBOLD) + 8f * s, mid,
                        9f * s, muted2Color, count, UiDrawCommand.TextWeight.REGULAR);
            }
            cardY = y + 18f * s;
        }
        if (collapsed) return cardY + 6f * s;
        float cardH = 0f;
        for (Setting setting : settings) cardH += settingHeight(setting) * s * fold(setting);
        surfaceCard(b, x, cardY, w, cardH, cardRadius * s);

        float rowY = cardY;
        for (int i = 0; i < settings.size(); i++) {
            Setting setting = settings.get(i);
            float open = fold(setting);
            float fullH = settingHeight(setting) * s;
            float rowH = fullH * open;
            if (open < 0.01f) continue;
            int depth = SUB_DEPTH.getOrDefault(setting, 0);
            float indent = Math.min(3, depth) * 16f * s;
            boolean partial = open < 0.999f;
            float sClipX = clipX, sClipY = clipY, sClipW = clipW, sClipH = clipH;
            boolean sClipping = clippingHits;
            if (partial) {
                // Drop-down: the row is laid out at full height and revealed from the top.
                b.clipPush(x, rowY, w, rowH, 0);
                float top = Math.max(rowY, sClipping ? sClipY : rowY);
                float bottom = Math.min(rowY + rowH, sClipping ? sClipY + sClipH : rowY + rowH);
                clipX = sClipping ? sClipX : x; clipW = sClipping ? sClipW : w;
                clipY = top; clipH = Math.max(0f, bottom - top);
                clippingHits = true;
            }
            if (depth > 0) {
                // Guide line marking a nested sub-setting under its parent.
                b.rect(x + indent - 4f * s, rowY + 6f * s, Math.max(1f, 1.5f * s), Math.max(0f, fullH - 12f * s),
                        1f, withA(accentArgb(), 0x66));
            }
            drawSettingRow(b, setting, x + indent, rowY, w - indent, fullH, s, mx, my);
            if (partial) {
                clipX = sClipX; clipY = sClipY; clipW = sClipW; clipH = sClipH; clippingHits = sClipping;
                b.clipPop();
            }
            rowY += rowH;
            if (i < settings.size() - 1 && showSeparators && open > 0.5f) {
                b.rect(x + 14f * s + indent, rowY - s, w - 28f * s - indent, s, 0, lineColor);
            }
        }
        return cardY + cardH + 12f * s;
    }

    // ---- Colour picker ----------------------------------------------------------------------------

    private static final int[] PRESET_COLOURS = {
            0xffffffff, 0xff26e0c6, 0xff4c84ff, 0xff7ed2ff, 0xffa86eff, 0xfff0486b, 0xffffb347, 0xff34d37d, 0xff101214 };

    private static String hexOf(int argb, boolean withAlpha) {
        return withAlpha ? String.format(Locale.ROOT, "#%08X", argb) : String.format(Locale.ROOT, "#%06X", argb & 0xffffff);
    }

    /** #RGB, #RRGGBB or #AARRGGBB (the # is optional); null when it is not a colour. */
    private static Integer parseHex(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        else if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() == 3) {
            StringBuilder wide = new StringBuilder(6);
            for (int i = 0; i < 3; i++) wide.append(hex.charAt(i)).append(hex.charAt(i));
            hex = wide.toString();
        }
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            long value = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int) (value | 0xff000000L) : (int) value;
        } catch (NumberFormatException notHex) {
            return null;
        }
    }

    /** The colour an untouched Appearance colour setting currently resolves to (it follows the theme). */
    private Integer themeValueFor(ColorSetting c) {
        if (c == null) return null;
        if (c == ClickGUIModule.textColour) return textColor;
        if (c == ClickGUIModule.mutedTextColour) return mutedColor;
        if (c == ClickGUIModule.toggleOnColour || c == ClickGUIModule.sliderFillColour) return accentArgb() | 0xff000000;
        if (c == ClickGUIModule.toggleOffColour) return toggleOffColor;
        if (c == ClickGUIModule.selectionColour) return 0xffffffff;
        if (c == ClickGUIModule.rimColour) return outerBorder;
        if (c == ClickGUIModule.cardFillColor) return cardBg;
        if (c == ClickGUIModule.cardBorderColor) return cardBorder;
        if (c == ClickGUIModule.panelTint) {
            return lg ? argb(0x44, UiGlass.DEFAULT_BODY_RGB)
                    : (mixRgb(0xff080b12, currentAccent, 0.16f) & 0x00ffffff) | 0x8c000000;
        }
        return null;
    }

    private void openColorPicker(ColorSetting setting) {
        if (colorPicker != null && colorPicker != setting) closeColorPicker();
        colorPicker = setting;
        Color base = setting.getBaseValue();
        pickerOpenedWith = base.getRGB();
        Integer theme = setting.isModified() ? null : themeValueFor(setting);
        setPickerHsv(theme != null ? theme : base.getRGB(), true);
        hexField.setValue(hexOf(theme != null ? theme : base.getRGB(), ((theme != null ? theme : base.getRGB()) >>> 24) != 0xff));
    }

    /** Commits (the value is already live) and closes the picker; a changed colour joins the recent list. */
    private void closeColorPicker() {
        ColorSetting closing = colorPicker;
        colorPicker = null;
        colorDrag = ColorDrag.NONE;
        if (kitFocus == hexField) clearTextFocus();
        if (closing != null && closing.getBaseValue() != null && closing.getBaseValue().getRGB() != pickerOpenedWith) {
            pushRecentColour(closing.getBaseValue().getRGB());
        }
    }

    private static void pushRecentColour(int argb) {
        RECENT_COLOURS.remove(argb);
        RECENT_COLOURS.addFirst(argb);
        while (RECENT_COLOURS.size() > 9) RECENT_COLOURS.removeLast();
    }

    private void setPickerHsv(int argb, boolean includeAlpha) {
        float[] hsv = Color.RGBtoHSB((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, null);
        colorHue = hsv[0];
        colorSaturation = hsv[1];
        colorValue = hsv[2];
        if (includeAlpha) colorAlpha = ((argb >>> 24) & 0xff) / 255f;
    }

    /** Sets the picked colour from an ARGB value (presets keep the current opacity). */
    private void pickColour(int argb, boolean includeAlpha) {
        if (colorPicker == null) return;
        setPickerHsv(argb, includeAlpha);
        updatePickedColor();
    }

    private void applyHexField() {
        if (colorPicker == null) return;
        Integer parsed = parseHex(hexField.value());
        if (parsed != null) pickColour(parsed, hexField.value().replace("#", "").trim().length() == 8);
    }

    private void applyHexFieldLive() {
        if (colorPicker == null) return;
        String raw = hexField.value().replace("#", "").trim();
        if (raw.length() == 6 || raw.length() == 8) applyHexField();
    }

    private void drawColorPicker(UiDisplayList.Builder b, float sw, float sh, float s, float mx, float my) {
        hits.clear();
        moduleCardRightHits.clear(); // the modal owns right-clicks too
        float baseW = 380f, baseH = 344f;
        float ms = Math.min(s, Math.min((sw - 32f) / baseW, (sh - 32f) / baseH));
        ms = Math.max(0.35f, ms);
        float w = Math.round(baseW * ms), h = Math.round(baseH * ms);
        float x = Math.round((sw - w) * 0.5f), y = Math.round((sh - h) * 0.5f);
        modalX = x; modalY = y; modalW = w; modalH = h;
        ColorSetting picking = colorPicker;

        // Clicking outside closes (the value is already applied); clicks on the panel itself do nothing.
        hit(0, 0, sw, sh, this::closeColorPicker);
        hit(x, y, w, h, () -> { });

        b.rect(0, 0, sw, sh, 0, 0x66000000);
        float r = 14f * ms * cornerScale;
        if (lg) glassPane(b, x, y, w, h, r, 1f, 0xd0, true, 0);
        else {
            panelShadow(b, x, y, w, h, r, ms, 1f);
            b.rect(x, y, w, h, r, 0xf0101214);
            if (showRims) b.border(x, y, w, h, r, Math.max(1f, ms), outerBorder);
        }

        b.text(x + 20f * ms, y + 18f * ms, 14f * ms, titleColor,
                UiKit.fit(picking.getName(), w - 40f * ms, 14f * ms, UiDrawCommand.TextWeight.SEMIBOLD),
                UiDrawCommand.TextWeight.SEMIBOLD);

        // Saturation / Value picker box
        svX = x + 20f * ms; svY = y + 46f * ms; svSize = 150f * ms;
        int pureHue = Color.HSBtoRGB(colorHue, 1f, 1f);
        b.gradient(svX, svY, svSize, svSize, 6f * ms, 0xffffffff, pureHue, pureHue, 0xffffffff);
        b.gradient(svX, svY, svSize, svSize, 6f * ms, 0x00000000, 0x00000000, 0xff000000, 0xff000000);
        float knobX = svX + colorSaturation * svSize, knobY = svY + (1f - colorValue) * svSize;
        b.circle(knobX, knobY, 6f * ms, 0x80000000);
        b.circle(knobX, knobY, 5f * ms, 0xffffffff);
        b.circle(knobX, knobY, 3.5f * ms, Color.HSBtoRGB(colorHue, colorSaturation, colorValue));
        hit(svX, svY, svSize, svSize, () -> colorDrag = ColorDrag.SV);

        // Hue slider bar
        hueX = svX + svSize + 16f * ms; hueY = svY; hueH = svSize;
        float hueW = 16f * ms;
        for (int step = 0; step < 6; step++) {
            float hy1 = hueY + (step / 6f) * hueH;
            float hy2 = hueH / 6f;
            int c1 = Color.HSBtoRGB(step / 6f, 1f, 1f);
            int c2 = Color.HSBtoRGB((step + 1) / 6f, 1f, 1f);
            b.gradient(hueX, hy1, hueW, hy2, 2f * ms, c1, c1, c2, c2);
        }
        b.rect(hueX - 2f * ms, hueY + colorHue * hueH - 2f * ms, hueW + 4f * ms, 4f * ms, 2f * ms, 0xffffffff);
        hit(hueX - 4f * ms, hueY, hueW + 8f * ms, hueH, () -> colorDrag = ColorDrag.HUE);

        // Right column: before/after preview, hex field, copy/paste, rainbow.
        float colX = hueX + hueW + 18f * ms, colW = x + w - 20f * ms - colX;
        Color base = picking.getBaseValue();
        float prevH = 34f * ms;
        float half = Math.round(colW * 0.5f);
        b.rect(colX, svY, half, prevH, 0, pickerOpenedWith);
        b.rect(colX + half, svY, colW - half, prevH, 0, picking.getValue().getRGB());
        b.border(colX, svY, colW, prevH, 6f * ms, Math.max(1f, ms), 0x33ffffff);
        textCentered(b, colX + half * 0.5f, svY + prevH + 8f * ms, 8f * ms, muted2Color, "Before", UiDrawCommand.TextWeight.REGULAR);
        textCentered(b, colX + half + (colW - half) * 0.5f, svY + prevH + 8f * ms, 8f * ms, muted2Color, "Now",
                UiDrawCommand.TextWeight.REGULAR);

        float hexY = svY + prevH + 18f * ms, hexH = Math.round(26f * ms);
        if (!hexField.focused(kitHost)) hexField.setValue(hexOf(base.getRGB(), base.getAlpha() != 255));
        hexField.draw(b, kitHost, colX, hexY, colW, hexH, ms);

        float btnY = hexY + hexH + 8f * ms, btnH = Math.round(22f * ms), btnW = Math.round((colW - 6f * ms) * 0.5f);
        UiKit.button(b, kitHost, "Copy", colX, btnY, btnW, btnH, ms * 0.9f, UiKit.Style.SECONDARY, true, mx, my,
                () -> kitHost.setClipboard(hexOf(picking.getBaseValue().getRGB(), picking.getBaseValue().getAlpha() != 255)));
        UiKit.button(b, kitHost, "Paste", colX + colW - btnW, btnY, btnW, btnH, ms * 0.9f, UiKit.Style.SECONDARY, true, mx, my,
                () -> {
                    String clip = kitHost.clipboard();
                    Integer parsed = parseHex(clip);
                    if (parsed != null) pickColour(parsed, clip.replace("#", "").trim().length() == 8);
                });

        float rbY = btnY + btnH + 12f * ms, rbH = Math.round(22f * ms);
        textMid(b, colX, rbY + rbH * 0.5f, 10.5f * ms, textColor, "Rainbow", UiDrawCommand.TextWeight.SEMIBOLD);
        float tgW = 30f * ms, tgH = 16f * ms;
        drawToggleSwitch(b, "picker_rainbow", colX + colW - tgW, rbY + (rbH - tgH) * 0.5f, tgW, tgH, picking.isRainbow(), ms);
        hit(colX, rbY, colW, rbH, () -> picking.setRainbow(!picking.isRainbow()));
        if (picking.isRainbow()) {
            float spY = rbY + rbH + 6f * ms;
            String speed = String.format(Locale.ROOT, "Speed %.1fx", picking.getRainbowSpeed());
            textMid(b, colX, spY + 6f * ms, 9f * ms, mutedColor, speed, UiDrawCommand.TextWeight.REGULAR);
            float trackY = spY + 16f * ms, trackH = Math.max(2f, Math.round(4f * ms));
            rainbowSpeedX = colX;
            rainbowSpeedW = colW;
            rainbowSpeedY = trackY;
            double p = (picking.getRainbowSpeed() - ColorSetting.MIN_RAINBOW_SPEED)
                    / (ColorSetting.MAX_RAINBOW_SPEED - ColorSetting.MIN_RAINBOW_SPEED);
            float fill = (float) (colW * Math.max(0, Math.min(1, p)));
            b.rect(colX, trackY, colW, trackH, trackH * 0.5f, controlFill(0x24));
            if (fill > 0.5f) accentFill(b, colX, trackY, Math.max(trackH, fill), trackH, trackH * 0.5f, 1f, sliderFillColor);
            b.circle(colX + fill, trackY + trackH * 0.5f, 5f * ms, KNOB_WHITE);
            hit(colX - 4f * ms, trackY - 8f * ms, colW + 8f * ms, trackH + 16f * ms, () -> colorDrag = ColorDrag.RAINBOW_SPEED);
        }

        // Opacity slider
        alphaX = x + 20f * ms; alphaY = svY + svSize + 16f * ms; alphaW = w - 40f * ms - 46f * ms;
        float alphaH = 12f * ms;
        int opaque = Color.HSBtoRGB(colorHue, colorSaturation, colorValue) | 0xff000000;
        b.gradient(alphaX, alphaY, alphaW, alphaH, 6f * ms, 0x33ffffff, opaque, opaque, 0x33ffffff);
        b.circle(alphaX + alphaW * colorAlpha, alphaY + alphaH * 0.5f, 6.5f * ms, 0x80000000);
        b.circle(alphaX + alphaW * colorAlpha, alphaY + alphaH * 0.5f, 6f * ms, 0xffffffff);
        hit(alphaX, alphaY - 4f * ms, alphaW, alphaH + 8f * ms, () -> colorDrag = ColorDrag.ALPHA);
        textMid(b, alphaX + alphaW + 10f * ms, alphaY + alphaH * 0.5f, 9.5f * ms, mutedColor,
                Math.round(colorAlpha * 100f) + "%", UiDrawCommand.TextWeight.SEMIBOLD);

        // Presets (theme accent first) and this session's recent colours.
        float rowX = x + 20f * ms, rowW = w - 40f * ms;
        float presetLabelY = alphaY + alphaH + 16f * ms;
        textMid(b, rowX, presetLabelY, 9f * ms, muted2Color, "PRESETS", UiDrawCommand.TextWeight.SEMIBOLD);
        float swR = 9f * ms, swPitch = 24f * ms, swY = presetLabelY + 18f * ms;
        drawSwatch(b, rowX + swR, swY, swR, accentArgb() | 0xff000000, mx, my, "Theme accent");
        for (int i = 0; i < PRESET_COLOURS.length && (i + 2) * swPitch <= rowW + swPitch; i++) {
            drawSwatch(b, rowX + swR + (i + 1) * swPitch, swY, swR, PRESET_COLOURS[i], mx, my, null);
        }
        float recentLabelY = swY + swR + 16f * ms;
        textMid(b, rowX, recentLabelY, 9f * ms, muted2Color, "RECENT", UiDrawCommand.TextWeight.SEMIBOLD);
        float recentY = recentLabelY + 18f * ms;
        if (RECENT_COLOURS.isEmpty()) {
            textMid(b, rowX, recentY, 9f * ms, muted2Color, "Colours you pick this session appear here.",
                    UiDrawCommand.TextWeight.REGULAR);
        } else {
            int i = 0;
            for (int colour : RECENT_COLOURS) {
                if ((i + 1) * swPitch > rowW + swPitch) break;
                drawSwatch(b, rowX + swR + i * swPitch, recentY, swR, colour, mx, my, null);
                i++;
            }
        }

        b.text(x + 20f * ms, y + h - 18f * ms, 9f * ms, muted2Color,
                UiKit.fit("Click outside or press Esc to close. Hex accepts #RGB, #RRGGBB and #AARRGGBB.", w - 40f * ms, 9f * ms));
    }

    /** One clickable colour dot in the picker; presets keep the current opacity unless they carry their own. */
    private void drawSwatch(UiDisplayList.Builder b, float cx, float cy, float r, int argb, float mx, float my, String tip) {
        boolean hover = hovered(mx, my, cx - r, cy - r, r * 2f, r * 2f);
        if (hover) b.circle(cx, cy, r + 2f, withA(textColor, 0x80));
        b.circle(cx, cy, r, argb | 0xff000000);
        b.border(cx - r, cy - r, r * 2f, r * 2f, r, 1f, 0x40ffffff);
        boolean current = colorPicker != null && (colorPicker.getBaseValue().getRGB() & 0xffffff) == (argb & 0xffffff);
        if (current) b.circle(cx, cy, Math.max(1.5f, r * 0.28f), (argb & 0xffffff) > 0x808080 ? 0xff101214 : 0xffffffff);
        if (hover) {
            hoveredTooltipTitle = tip != null ? tip : hexOf(argb, (argb >>> 24) != 0xff);
            hoveredTooltipDesc = "Click to use this colour.";
        }
        hit(cx - r, cy - r, r * 2f, r * 2f, () -> pickColour(argb, (argb >>> 24) != 0xff));
    }

    // =========================================================================
    // 5. INPUT & EVENT ROUTING
    // =========================================================================

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        UiTextTarget previous = kitFocus;
        kitFocus = null;
        StringSetting editedBefore = editingString;
        int editCountBefore = editBeginCount;
        boolean handled = false;
        // The click's sound follows what it changed: a switch on/off, a slider jump, or a plain click.
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.beginPress();
        try {
            handled = handleMouseClicked(click, doubled);
        } finally {
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.endPress(handled);
        }
        if (previous != null && kitFocus != previous) previous.onBlur();
        // A click that did not land on a text setting ends the edit (it used to stay open forever).
        if (editedBefore != null && editingString == editedBefore && editBeginCount == editCountBefore) {
            editingString.setEditing(false);
            editingString = null;
        }
        return handled;
    }

    public ConfigsView configsView() {
        return configsView;
    }

    public StudioTab macroView() {
        return macroView;
    }

    public StudioTab scriptView() {
        return scriptView;
    }

    public PeopleView peopleView() {
        return peopleView;
    }

    private boolean handleMouseClicked(Click click, boolean doubled) {
        float k = pixelsPerGuiUnit();
        double mx = click.x() * k, my = click.y() * k;
        pressX = mx;
        pressY = my;
        // The keyboard focus ring is for keyboard use; any click hides it again.
        navVisible = false;
        panning = false;
        flingVel = 0f; // a new touch stops a coasting list
        int button = click.button();
        if (isCapturingKeybind()) {
            // Mouse binds are stored as -(button + 100); Module.mouseButtonOf inverts that for the
            // keybind poll. (button - 100 decoded to an invalid negative button, so side/middle mouse
            // binds could be set but never fired.) Left click cancels the capture and keeps the old bind.
            if (button == GLFW.GLFW_MOUSE_BUTTON_1 || button < 0 || button > GLFW.GLFW_MOUSE_BUTTON_LAST) {
                cancelKeybindCapture();
            } else {
                captureKeybind(-(button + 100));
            }
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_2) {
            for (int i = moduleCardRightHits.size() - 1; i >= 0; i--) {
                Hit hit = moduleCardRightHits.get(i);
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            cancelCardDrag();
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.contains(mx, my)) {
                    if (hit.action() instanceof CardPress press && catalogReorderable && contextItems == null) {
                        // Toggle on release, so the same press can instead pick the card up and move it.
                        pendingCardPress = press;
                        pendingPressX = mx;
                        pendingPressY = my;
                        return true;
                    }
                    hit.action().run();
                    return true;
                }
            }
        }
        if (contextItems != null) {
            // Any other button outside the menu just closes it.
            closeContextMenu();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (panning) {
            flingVel = ResponsiveLayout.flingFor(panVel, nanoTime() - panLastNs);
            flingX = (float) pressX;
            flingY = (float) pressY;
            panning = false;
        }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_1) {
            if (dragModule != null) {
                float k = pixelsPerGuiUnit();
                dragPointerX = (float) (click.x() * k);
                dragPointerY = (float) (click.y() * k);
                commitCardDrag();
            } else if (pendingCardPress != null) {
                CardPress press = pendingCardPress;
                pendingCardPress = null;
                press.run();
            }
        }
        windowDrag = 0;
        cosmeticsView.mouseReleased();
        if (kitFocus != null) kitFocus.mouseReleased();
        draggingSlider = null;
        colorDrag = ColorDrag.NONE;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        float k = pixelsPerGuiUnit();
        double mx = click.x() * k, my = click.y() * k;
        if (pendingCardPress != null || dragModule != null) {
            if (dragModule == null && Math.hypot(mx - pendingPressX, my - pendingPressY) > 6.0 * Math.max(1f, lastFitS)) {
                // Moved far enough: the press becomes a drag (the toggle it would have been is dropped).
                Module m = pendingCardPress.module;
                pendingCardPress = null;
                if (catalogReorderable && lastCatalogModules.contains(m)) {
                    dragModule = m;
                    dragVisible = List.copyOf(lastCatalogModules);
                    dragDropIndex = -1;
                }
            }
            dragPointerX = (float) mx;
            dragPointerY = (float) my;
            return true;
        }
        if (windowDrag != 0) {
            dragWindow(mx, my);
            return true;
        }
        if (kitFocus != null && kitFocus.mouseDragged(mx, my)) return true;
        if (view == View.COSMETICS && cosmeticsView.mouseDragged(mx, my)) return true;
        if (draggingSlider instanceof SliderSetting slider && sliderW > 0) {
            double p = clamp01((float) ((mx - sliderX) / sliderW));
            double before = slider.getValue();
            slider.setValue(slider.getMin() + p * (slider.getMax() - slider.getMin()));
            // One tick per twentieth of the track, so a long drag clicks like a detent wheel.
            int notch = (int) Math.floor(p * 20.0);
            if (slider.getValue() != before && notch != sliderSoundNotch) {
                sliderSoundNotch = notch;
                interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.SLIDER);
            }
            return true;
        }
        if (colorDrag == ColorDrag.SV && svSize > 0) {
            colorSaturation = clamp01((float) ((mx - svX) / svSize));
            colorValue = clamp01(1f - (float) ((my - svY) / svSize));
            updatePickedColor();
            return true;
        }
        if (colorDrag == ColorDrag.HUE && hueH > 0) {
            colorHue = clamp01((float) ((my - hueY) / hueH));
            updatePickedColor();
            return true;
        }
        if (colorDrag == ColorDrag.RAINBOW_SPEED && rainbowSpeedW > 0 && colorPicker != null) {
            double p = clamp01((float) ((mx - rainbowSpeedX) / rainbowSpeedW));
            colorPicker.setRainbowSpeed(ColorSetting.MIN_RAINBOW_SPEED
                    + p * (ColorSetting.MAX_RAINBOW_SPEED - ColorSetting.MIN_RAINBOW_SPEED));
            return true;
        }
        if (colorDrag == ColorDrag.ALPHA && alphaW > 0) {
            colorAlpha = clamp01((float) ((mx - alphaX) / alphaW));
            updatePickedColor();
            return true;
        }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_1 && panDrag(mx, my)) return true;
        return super.mouseDragged(click, offsetX, offsetY);
    }

    /** Pixels of drag that equal one wheel notch where the drag started (mirrors mouseScrolled's routing). */
    private float panStepPx(double px, double py) {
        float notch = Math.max(1f, lastFitS);
        boolean inspector = view == View.MODULES
                ? inspectorRevealW > 0f && inside(px, py, inspectorRevealX, inspectorRevealY, inspectorRevealW, inspectorRevealH)
                : px >= (inspectorBoundaryX > 0 ? inspectorBoundaryX : width * pixelsPerGuiUnit() * 0.5f);
        return inspector ? 24f : 56f * notch;
    }

    /**
     * Drag-to-scroll: a left-button drag that no control claimed (not a slider, window edge or card pick-up)
     * past the slop scrolls whatever the wheel would scroll under the press point, and a fling coasts on release.
     * Touch emulated as a mouse and a real mouse both take this path.
     */
    private boolean panDrag(double mx, double my) {
        if (contextItems != null || colorPicker != null || modalOverlayOpen()) return false;
        long now = nanoTime();
        if (!panning) {
            if (Math.hypot(mx - pressX, my - pressY) <= ResponsiveLayout.DRAG_SLOP_PX * Math.max(1f, lastFitS)) return false;
            panning = true;
            panLastY = my;
            panVel = 0f;
            panLastNs = now;
            return true;
        }
        float dy = (float) (my - panLastY);
        panLastY = my;
        panVel = ResponsiveLayout.blendVelocity(panVel, dy, (now - panLastNs) / 1_000_000_000f);
        panLastNs = now;
        float k = pixelsPerGuiUnit();
        mouseScrolled(pressX / k, pressY / k, 0, dy / panStepPx(pressX, pressY));
        return true;
    }

    private void updatePickedColor() {
        if (colorPicker == null) return;
        int rgb = Color.HSBtoRGB(colorHue, colorSaturation, colorValue);
        int a = Math.round(colorAlpha * 255f);
        colorPicker.setValue(new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, a));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        float k = pixelsPerGuiUnit();
        mouseX *= k;
        mouseY *= k;
        long now = nanoTime();
        float notch = Math.max(1f, lastFitS);
        if (whatsNew.isOpen()) return whatsNew.scroll(mouseX, mouseY, verticalAmount, notch);
        if (tour.active()) return true; // modal: nothing underneath scrolls
        if (contextItems != null) {
            closeContextMenu(); // the menu would no longer sit on what it was opened for
            return true;
        }
        if (settingsDrawerOpen && drawerRegionW > 0f
                && inside(mouseX, mouseY, drawerRegionX, drawerRegionY, drawerRegionW, drawerRegionH)) {
            if (settingsTab == SettingsTab.CONFIG_BINDS) {
                // The Binds list keeps its own offset (in pixels); it was never wired to the wheel.
                hotkeyScroll = Math.max(0f, hotkeyScroll - (float) verticalAmount * 36f * notch);
            } else {
                appearanceScroll = Math.max(0f, appearanceScroll - (float) verticalAmount * 36f);
            }
            return true;
        }
        if (view == View.CONFIGS && configsView.scroll(mouseX, mouseY, verticalAmount, now)) return true;
        if (view == View.MACROS && macroView.scroll(mouseX, mouseY, verticalAmount, now)) return true;
        if (view == View.SCRIPTS && scriptView.scroll(mouseX, mouseY, verticalAmount, now)) return true;
        if (view == View.FRIENDS && peopleView.scroll(mouseX, mouseY, verticalAmount, now)) return true;
        if (view == View.COSMETICS && cosmeticsView.scroll(mouseX, mouseY, verticalAmount, now)) return true;
        float boundary = inspectorBoundaryX > 0 ? inspectorBoundaryX : (float) (width * k * 0.5);
        boolean moduleInspectorOnLeft = view == View.MODULES && inspectorRevealW > 0f
                && mouseX >= inspectorRevealX && mouseX <= inspectorRevealX + inspectorRevealW
                && mouseY >= inspectorRevealY && mouseY <= inspectorRevealY + inspectorRevealH;
        boolean scrollInspector = view == View.MODULES ? moduleInspectorOnLeft : mouseX >= boundary;
        if (scrollInspector) {
            if (moduleInspectorOnLeft) {
                moduleInspectorScrollRemainder += verticalAmount * 24.0;
                int delta = (int) moduleInspectorScrollRemainder;
                if (delta != 0) {
                    settingsScroll = Math.max(0, settingsScroll - delta);
                    moduleInspectorScrollRemainder -= delta;
                }
            } else {
                settingsScroll = Math.max(0, settingsScroll - (int) (verticalAmount * 24));
            }
        } else {
            if (view == View.MODULES) {
                catalogScroll.wheel(verticalAmount, 56f * notch, now);
            } else {
                moduleScrollRemainder += verticalAmount;
                int delta = (int) moduleScrollRemainder;
                if (delta != 0) {
                    moduleScroll = Math.max(0, moduleScroll - delta);
                    moduleScrollRemainder -= delta;
                }
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        // The tick loop also watches Escape and the GUI key as a fallback close path; recording that
        // the screen already received this press stops it closing the menu a second time.
        noteKeySeen(key);

        if (isCapturingKeybind()) {
            // The key's char event arrives after this; without swallowing it the bound key was also
            // typed into the module search ("press a key" bound it AND started a search).
            swallowCharsUntilMs = System.currentTimeMillis() + 250L;
            captureKeybind(key);
            return true;
        }

        // Modal overlays own the keyboard: Escape closes What's New / skips the tour, arrows/Enter page the tour.
        if (whatsNew.isOpen()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) whatsNew.close();
            return true;
        }
        if (tour.active()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) tour.finish();
            else if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) tour.next();
            else if (key == GLFW.GLFW_KEY_LEFT) tour.back();
            return true;
        }
        // Any other key dismisses an open right-click menu before doing its usual job.
        if (contextItems != null && key != GLFW.GLFW_KEY_ESCAPE) closeContextMenu();

        boolean typing = editingString != null || newConfigFocused || kitFocus != null;
        int mods = input.modifiers();
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // Escape backs out one layer at a time; only an empty stack closes the menu.
            if (contextItems != null) { closeContextMenu(); return true; }
            if (dragModule != null || pendingCardPress != null) { cancelCardDrag(); return true; }
            if (colorPicker != null) { closeColorPicker(); return true; }
            if (editingString != null) { editingString.setEditing(false); editingString = null; return true; }
            if (kitFocus != null) { clearTextFocus(); return true; }
            if (newConfigFocused) { newConfigFocused = false; return true; }
            if (settingsDrawerOpen) { settingsDrawerOpen = false; return true; }
            close();
            return true;
        }
        if (key == guiKey()) {
            // Shift is how capitals are typed: while a text field has focus the GUI key is just a key.
            if (typing) return true;
            if (colorPicker != null) { closeColorPicker(); return true; }
            close();
            return true;
        }

        // Ctrl+F: jump to the module search (selecting what is already typed) from anywhere but another field.
        if (ctrl && key == GLFW.GLFW_KEY_F && colorPicker == null && editingString == null
                && (kitFocus == null || kitFocus == searchField)) {
            focusSearch();
            searchField.selectAll();
            return true;
        }

        // Keyboard navigation over the module results, from the search field or with nothing focused.
        boolean onModules = view == View.MODULES && colorPicker == null && editingString == null;
        if (onModules && kitFocus == searchField) {
            switch (key) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                    moveNav(key);
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    activateNav(shift);
                    return true;
                }
                default -> { }
            }
        } else if (onModules && kitFocus == null && !newConfigFocused) {
            switch (key) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    if (moveNav(key)) return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (navVisible && navIndex >= 0) {
                        activateNav(shift);
                        return true;
                    }
                }
                default -> { }
            }
        }

        if (kitFocus != null && kitFocus.keyPressed(key, mods)) return true;

        if (editingString != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                editingString.setEditing(false);
                editingString = null;
                return true;
            }
            if (ctrl && key == GLFW.GLFW_KEY_V && client != null) {
                String clip = client.keyboard.getClipboard();
                if (clip != null && !clip.isEmpty()) {
                    editingString.setValue(editingString.getValue() + clip.replaceAll("[\\p{Cntrl}]", ""));
                }
                return true;
            }
        }

        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (newConfigFocused && !newConfigName.isEmpty()) {
                newConfigName = removeLastCodePoint(newConfigName);
                return true;
            }
            if (editingString != null && !editingString.getValue().isEmpty()) {
                editingString.setValue(removeLastCodePoint(editingString.getValue()));
                return true;
            }
        }

        return super.keyPressed(input);
    }

    /** Shows the module list and puts the caret in its search field. */
    private void focusSearch() {
        if (view != View.MODULES) view = View.MODULES;
        focusView = View.MODULES;
        kitHost.focus(searchField);
    }

    private int lastKeySeen = Integer.MIN_VALUE;
    private long lastKeySeenAt;

    protected final void noteKeySeen(int key) {
        lastKeySeen = key;
        lastKeySeenAt = System.currentTimeMillis();
    }

    /** True if this screen received a press of {@code key} within the last {@code windowMs}. */
    public boolean sawKeyRecently(int key, long windowMs) {
        return key == lastKeySeen && System.currentTimeMillis() - lastKeySeenAt <= windowMs;
    }

    private static int guiKey() {
        Module gui = interstellarTikiTiks.apexx.client.util.ModuleAccess.getModule("ClickGUI");
        int k = gui == null ? -1 : gui.getKeyCode();
        return (k == -1 || k == 0) ? GLFW.GLFW_KEY_RIGHT_SHIFT : k;
    }

    /** True while the next keyboard or mouse input is being assigned to a bind. */
    public boolean isCapturingKeybind() {
        return bindingModuleKey || binding != null || bindingBool != null || bindingConfigName != null
                || kitKeyCapture != null;
    }

    /** True if a keybind capture consumed {@code key} within the last {@code windowMs}. */
    public boolean capturedKeyRecently(int key, long windowMs) {
        return key == capturedKey && System.currentTimeMillis() - capturedKeyAtMs <= windowMs;
    }

    /** Records the key/button a capture consumed (see {@link #capturedKeyRecently}). */
    protected void noteCapturedKey(int code) {
        capturedKey = code;
        capturedKeyAtMs = System.currentTimeMillis();
        // Escape clears the bind, so it gets the plain click instead of the "bind set" confirmation.
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(code == GLFW.GLFW_KEY_ESCAPE
                ? interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.CLICK
                : interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.KEYBIND);
    }

    private void captureKeybind(int code) {
        noteCapturedKey(code);
        // Escape clears a bind instead of binding Escape itself.
        if (code == GLFW.GLFW_KEY_ESCAPE) code = -1;
        if (bindingBool != null) {
            bindingBool.setKeyCode(code);
            bindingBool = null;
            return;
        }
        if (kitKeyCapture != null) {
            java.util.function.IntConsumer capture = kitKeyCapture;
            kitKeyCapture = null;
            capture.accept(code == GLFW.GLFW_KEY_ESCAPE ? -1 : code);
            return;
        }
        if (bindingModuleKey) {
            if (bindingModuleTarget != null
                    && (!(bindingModuleTarget instanceof ClickGUIModule) || code != -1)) {
                bindingModuleTarget.setKeyCode(code);
            }
            if (bindingModuleTarget != null) {
                bindingModuleTarget.setBinding(false);
            }
            bindingModuleKey = false;
            bindingModuleTarget = null;
        } else if (binding != null) {
            Module target = binding.getModule();
            if (target != null) {
                // The menu key can never end up unbound: Escape (or a cleared capture) keeps the old key.
                if (!(target instanceof ClickGUIModule) || code != -1) target.setKeyCode(code);
                target.setBinding(false);
            }
            binding = null;
            bindingModuleTarget = null;
        } else if (bindingConfigName != null) {
            String configName = bindingConfigName;
            bindingConfigName = null;
            ConfigKeybindManager.getInstance().setKeybind(configName, code, -1);
        }
    }

    @Override
    public boolean charTyped(CharInput input) {
        int codepoint = input.codepoint();
        if (modalOverlayOpen()) return true; // nothing under the tour / What's New receives typing
        if (!Character.isValidCodePoint(codepoint) || Character.isISOControl(codepoint)) {
            return super.charTyped(input);
        }
        if (swallowCharsUntilMs != 0L) {
            boolean swallow = System.currentTimeMillis() <= swallowCharsUntilMs;
            swallowCharsUntilMs = 0L;
            if (swallow) return true;
        }
        if (isCapturingKeybind()) return true;
        String chars = new String(Character.toChars(codepoint));
        if (kitFocus != null) return kitFocus.charTyped(chars);
        if (editingString != null) {
            editingString.setValue(editingString.getValue() + chars);
            return true;
        }
        if (newConfigFocused) {
            newConfigName += chars;
            return true;
        }
        // Typing on the module list with nothing focused starts a search.
        if (view == View.MODULES && colorPicker == null && !isCapturingKeybind() && !Character.isWhitespace(codepoint)) {
            focusSearch();
            return searchField.charTyped(chars);
        }
        return super.charTyped(input);
    }

    private int editBeginCount;

    private void beginEdit(StringSetting value) {
        clearTextFocus();
        editBeginCount++;
        editingString = value;
        value.setEditing(true);
    }

    private View focusView = View.MODULES;

    /** Exactly one text target may have focus; every focus change goes through here first. */
    private void clearTextFocus() {
        if (kitFocus != null) {
            UiTextTarget old = kitFocus;
            kitFocus = null;
            old.onBlur();
        }
        if (editingString != null) editingString.setEditing(false);
        editingString = null;
        newConfigFocused = false;
    }

    private void hit(float x, float y, float w, float h, Runnable action) {
        if (clippingHits) {
            float right = Math.min(x + w, clipX + clipW), bottom = Math.min(y + h, clipY + clipH);
            x = Math.max(x, clipX); y = Math.max(y, clipY); w = right - x; h = bottom - y;
            if (w <= 0 || h <= 0) return;
        }
        Hit clipped = intersectHit(new Hit(x, y, w, h, action), inspectorRevealX, inspectorRevealY,
                inspectorRevealW, inspectorRevealH, inspectorRevealHitClip);
        if (clipped != null) hits.add(clipped);
    }

    private boolean shiftHeld() {
        try {
            long handle = client == null ? 0L : client.getWindow().getHandle();
            return handle != 0L && (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private void hitModuleCardRight(float x, float y, float w, float h, Runnable action) {
        if (clippingHits) {
            float right = Math.min(x + w, clipX + clipW), bottom = Math.min(y + h, clipY + clipH);
            x = Math.max(x, clipX); y = Math.max(y, clipY); w = right - x; h = bottom - y;
            if (w <= 0 || h <= 0) return;
        }
        Hit clipped = intersectHit(new Hit(x, y, w, h, action), inspectorRevealX, inspectorRevealY,
                inspectorRevealW, inspectorRevealH, inspectorRevealHitClip);
        if (clipped != null) moduleCardRightHits.add(clipped);
    }

    private static Hit intersectHit(Hit hit, float x, float y, float w, float h, boolean enabled) {
        if (!enabled) return hit;
        float left = Math.max(hit.x(), x), top = Math.max(hit.y(), y);
        float right = Math.min(hit.x() + hit.w(), x + w);
        float bottom = Math.min(hit.y() + hit.h(), y + h);
        if (right <= left || bottom <= top) return null;
        return new Hit(left, top, right - left, bottom - top, hit.action());
    }

    private void rebuildInputTree(float sw, float sh) {
        inputRoot.setBounds(new UiBounds(0, 0, sw, sh));
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            ActionElement element;
            if (i < inputNodes.size()) {
                element = inputNodes.get(i);
            } else {
                element = new ActionElement("click-" + i, hit.action());
                inputNodes.add(element);
                inputRoot.add(element);
            }
            element.setAction(hit.action());
            element.setBounds(new UiBounds(hit.x(), hit.y(), hit.w(), hit.h()));
            element.setEnabled(true);
        }
        while (inputNodes.size() > hits.size()) {
            ActionElement removed = inputNodes.remove(inputNodes.size() - 1);
            inputRoot.remove(removed);
        }
    }

    private static final class ActionElement extends UiElement {
        private Runnable action;
        private ActionElement(String id, Runnable action) {
            super(id);
            setFocusable(true);
            this.action = action;
        }
        private void setAction(Runnable action) {
            this.action = action;
        }
        @Override
        public boolean onPointerDown(float x, float y, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_1) return false;
            action.run();
            return true;
        }
    }

    private static boolean inside(double x, double y, double rx, double ry, double w, double h) {
        return x >= rx && y >= ry && x <= rx + w && y <= ry + h;
    }

    private int accentArgb() {
        return currentAccent;
    }

    private int controlFill(int alpha) {
        return ((alpha & 0xff) << 24) | (controlRgb & 0x00ffffff);
    }

    private static int brighten(int color, float amount) {
        return mixRgb(color, 0xffffffff, amount);
    }

    private static int darken(int color, float amount) {
        return mixRgb(color, 0xff000000, amount);
    }

    private static int mixRgb(int from, int to, float amount) {
        float t = clamp01(amount);
        int r = Math.round(((from >> 16) & 0xff) * (1f - t) + ((to >> 16) & 0xff) * t);
        int g = Math.round(((from >> 8) & 0xff) * (1f - t) + ((to >> 8) & 0xff) * t);
        int b = Math.round((from & 0xff) * (1f - t) + (to & 0xff) * t);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    /**
     * Pointer over the rect AND inside whatever is currently clipping it (a scroll viewport or a
     * half-revealed panel), matching what hit() accepts. Without the clip check, a card scrolled
     * out of view below the module grid still showed its tooltip and hover state.
     */
    private boolean hovered(double x, double y, float rx, float ry, float w, float h) {
        if (!inside(x, y, rx, ry, w, h)) return false;
        if (clippingHits && !inside(x, y, clipX, clipY, clipW, clipH)) return false;
        return !inspectorRevealHitClip
                || inside(x, y, inspectorRevealX, inspectorRevealY, inspectorRevealW, inspectorRevealH);
    }

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String removeLastCodePoint(String value) {
        return value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }

    private static List<String> wrapText(String text, float maxWidth, float fontSize, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxWidth <= 0f || maxLines <= 0) {
            return lines;
        }
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            String test = current.length() == 0 ? word : current + " " + word;
            if (GpuUiFontMetrics.textWidth(test, fontSize) <= maxWidth) {
                current.setLength(0);
                current.append(test);
            } else {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    if (lines.size() >= maxLines) break;
                }
                current.append(word);
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }
        return lines;
    }


    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    /**
     * Unit suffix for a slider's value, read from its name, so "120" reads as "120 ms" or "90°".
     * An explicit unit in parentheses wins, e.g. "Gap Variance (ms)".
     */
    private static String sliderUnit(String name, double max) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT);
        int open = n.lastIndexOf('('), close = n.lastIndexOf(')');
        if (open >= 0 && close > open) {
            String unit = n.substring(open + 1, close).trim();
            return switch (unit) {
                case "ms" -> " ms";
                case "ticks", "tick", "t" -> " t";
                case "%" -> "%";
                case "deg", "degrees", "°" -> "°";
                case "blocks", "block", "m" -> " m";
                case "cps" -> " cps";
                default -> "";
            };
        }
        if (n.contains("%") || n.contains("chance") || n.contains("percent") || n.contains("opacity")) return "%";
        if (n.contains("cps")) return " cps";
        if (n.contains("tick")) return " t";
        if (n.contains(" ms") || n.endsWith("ms")) return " ms";
        // Small-range delays are ticks or fractions in this codebase, so only label clear ms ranges.
        if ((n.contains("delay") || n.contains("cooldown") || n.contains("duration") || n.contains("latency")
                || n.contains("timeout")) && max > 40) return " ms";
        if (n.contains("delay") || n.contains("cooldown")) return "";
        if (n.contains("fov") || n.contains("angle") || n.contains("yaw") || n.contains("pitch")) return "°";
        if (n.contains("range") || n.contains("distance") || n.contains("reach") || n.contains("radius")
                || n.contains("height")) return " m";
        return "";
    }

    private static String stripUnitSuffix(String name) {
        if (name == null) return "";
        int open = name.lastIndexOf('('), close = name.lastIndexOf(')');
        if (open > 0 && close == name.length() - 1 && !sliderUnit(name, 0).isEmpty()) {
            return name.substring(0, open).trim();
        }
        return name;
    }

    private static String keyLabel(int code) {
        return code == -1 || code == 0 ? "None" : Module.getSingleKeyName(code);
    }

    // =========================================================================
    // KIT ADAPTERS: what the shared views need from this screen
    // =========================================================================

    private final class KitHost implements UiHost {
        @Override public void hit(float x, float y, float w, float h, Runnable action) {
            GpuClickGuiScreen.this.hit(x, y, w, h, action);
        }
        @Override public void hitSecondary(float x, float y, float w, float h, Runnable action) {
            hitModuleCardRight(x, y, w, h, action);
        }
        @Override public void beginHitClip(float x, float y, float w, float h) {
            clipX = x; clipY = y; clipW = w; clipH = h;
            clippingHits = true;
        }
        @Override public void endHitClip() { clippingHits = false; }
        @Override public boolean hovered(float mx, float my, float x, float y, float w, float h) {
            return GpuClickGuiScreen.this.hovered(mx, my, x, y, w, h);
        }
        @Override public int accent() { return accentArgb(); }
        @Override public int textColor() { return textColor; }
        @Override public int mutedColor() { return mutedColor; }
        @Override public int muted2Color() { return muted2Color; }
        @Override public int titleColor() { return titleColor; }
        @Override public float cornerScale() { return cornerScale; }
        @Override public int lineColor() { return lineColor; }
        @Override public int cardFill() { return cardBg; }
        @Override public int controlFill(int alpha) { return GpuClickGuiScreen.this.controlFill(alpha); }
        @Override public long now() { return nanoTime(); }
        @Override public float frameDelta() { return frameDelta; }
        @Override public float anim(String key, float target, float speed) { return GpuClickGuiScreen.this.anim(key, target, speed); }
        @Override public double pressX() { return pressX; }
        @Override public double pressY() { return pressY; }
        @Override public void focus(UiTextTarget target) {
            if (target == kitFocus) return;
            clearTextFocus();
            kitFocus = target;
            if (target != null) target.onFocus();
        }
        @Override public UiTextTarget focused() { return kitFocus; }
        @Override public void captureKey(java.util.function.IntConsumer onKey) {
            clearTextFocus();
            kitKeyCapture = onKey;
        }
        @Override public boolean capturingKey() { return kitKeyCapture != null; }
        @Override public String clipboard() {
            return client != null && client.keyboard != null ? client.keyboard.getClipboard() : "";
        }
        @Override public void setClipboard(String text) {
            if (client != null && client.keyboard != null) client.keyboard.setClipboard(text);
        }
    }

    /** Config actions for the Configs page; the cloud flows are the same ones the old page used. */
    private final class ConfigsBackend implements ConfigsView.Backend {
        @Override public List<String> savedConfigs() { return savedConfigs; }

        @Override public ConfigsView.Info info(String name) {
            PrivateConfigManager.PrivateConfigInfo cloud = cloudProfiles.get(name.toLowerCase(Locale.ROOT));
            return cloud == null ? null : new ConfigsView.Info(cloud.id, cloud.gameMode, cloud.active, cloud.isPublic, cloud.tags);
        }

        @Override public boolean signedIn() { return CloudConfigs.signedIn(); }

        @Override public void load(String name) {
            // Configs live only in the cloud (nothing is written to this PC), so there is
            // nothing to load until the user is signed in -- say so instead of failing silently.
            if (!CloudConfigs.signedIn()) {
                configStatusMessage = "Sign in to load cloud configs.";
                return;
            }
            profileLoadPending = true;
            configStatusMessage = "Loading \"" + name + "\" from cloud...";
            PrivateConfigManager.getInstance().loadConfig(name, true, loaded -> {
                profileLoadPending = false;
                if (!loaded) {
                    configStatusMessage = "Failed to load \"" + name + "\"";
                    return;
                }
                configStatusMessage = "Loaded \"" + name + "\"; cloud selection updates when this menu closes";
                lastCloudConfigStatusMessage = configStatusMessage;
                if (closing) {
                    lastCloudConfigStatusMessage = "Activating cloud profile \"" + name + "\"...";
                    PrivateConfigManager.getInstance().activateConfig(name,
                            () -> lastCloudConfigStatusMessage = "Active cloud profile: \"" + name + "\".",
                            error -> lastCloudConfigStatusMessage = "Could not activate cloud profile: " + error);
                } else {
                    pendingProfileActivationName = name;
                }
            }, error -> {
                profileLoadPending = false;
                configStatusMessage = "Failed to load \"" + name + "\": " + error;
            });
        }

        @Override public void save(String profileName, boolean makePublic, List<String> tags) {
            if (!CloudConfigs.signedIn()) {
                configStatusMessage = "Sign in to save configs -- they are stored in the cloud, never on this PC.";
                return;
            }
            boolean exists = cloudProfiles.containsKey(profileName.toLowerCase(Locale.ROOT));
            if (!exists && cloudProfiles.size() >= MAX_CLOUD_CONFIGS) {
                configStatusMessage = "Config limit reached (" + MAX_CLOUD_CONFIGS + "). Delete one first.";
                return;
            }
            pendingProfileSaveName = profileName;
            pendingSavePublic = makePublic;
            pendingSaveTags = tags == null ? List.of() : List.copyOf(tags);
            configStatusMessage = "Will save \"" + profileName + "\""
                    + (makePublic ? " (public)" : "") + (tags != null && !tags.isEmpty() ? " with filters" : "")
                    + " when this menu closes.";
        }

        @Override public void deleteCloud(String name) {
            if (!CloudConfigs.signedIn()) { configStatusMessage = "Sign in first."; return; }
            configStatusMessage = "Deleting \"" + name + "\"...";
            runCloudOp(() -> CloudConfigs.deleteNamed(name),
                    ok -> configStatusMessage = ok ? "Deleted \"" + name + "\"" : "Delete failed: " + cloudError());
        }

        @Override public void setPublic(String name, boolean makePublic) {
            if (!CloudConfigs.signedIn()) { configStatusMessage = "Sign in first."; return; }
            configStatusMessage = "Updating visibility...";
            runCloudOp(() -> CloudConfigs.setPublic(name, makePublic),
                    ok -> configStatusMessage = ok ? "\"" + name + "\" is now " + (makePublic ? "public" : "private")
                            : "Visibility change failed: " + cloudError());
        }

        @Override public void rename(String name, String newName) {
            if (!CloudConfigs.signedIn()) { configStatusMessage = "Sign in first."; return; }
            configStatusMessage = "Renaming...";
            runCloudOp(() -> {
                String id = CloudConfigs.idForName(name);
                return id != null && CloudConfigs.renameById(id, newName);
            }, ok -> configStatusMessage = ok ? "Renamed to \"" + newName + "\"" : "Rename failed: " + cloudError());
        }

        @Override public void setTags(String name, List<String> tags) {
            if (!CloudConfigs.signedIn()) { configStatusMessage = "Sign in first."; return; }
            runCloudOp(() -> {
                String id = CloudConfigs.idForName(name);
                return id != null && CloudConfigs.setTagsById(id, tags);
            }, ok -> { if (!ok) configStatusMessage = "Filter change failed: " + cloudError(); });
        }

        @Override public boolean premium() {
            return interstellarTikiTiks.apexx.client.util.PremiumGate.isPremium();
        }

        // Config Maker: always a NEW cloud config (never overwrites one with the same name).
        @Override public void saveGenerated(String name, String json, String gameMode, List<String> tags, boolean loadAfter,
                                            java.util.function.Consumer<Boolean> onDone) {
            String refusal = !CloudConfigs.signedIn() ? "Sign in to save configs -- they are stored in the cloud, never on this PC."
                    : cloudProfiles.containsKey(name.toLowerCase(Locale.ROOT)) ? "You already have a config named \"" + name + "\". Pick another name."
                    : cloudProfiles.size() >= MAX_CLOUD_CONFIGS ? "Config limit reached (" + MAX_CLOUD_CONFIGS + "). Delete one first."
                    : null;
            if (refusal != null) {
                configStatusMessage = refusal;
                onDone.accept(false);
                return;
            }
            configStatusMessage = "Saving \"" + name + "\"...";
            runCloudOp(() -> CloudConfigs.saveNamed(name, json, false, gameMode, "Made with Config Maker", tags) != null, ok -> {
                onDone.accept(ok);
                if (!ok) {
                    configStatusMessage = "Save failed: " + cloudError();
                    return;
                }
                configStatusMessage = "Saved \"" + name + "\" to your cloud configs.";
                if (loadAfter) load(name);
            });
        }

        @Override public boolean canDeleteLocal(String name) {
            return ConfigManager.isSaveLocalConfig() && !"default".equalsIgnoreCase(name);
        }

        @Override public void deleteLocal(String name) {
            try {
                if (!ConfigManager.getInstance().delete(name)) {
                    configStatusMessage = "Could not delete \"" + name + "\"";
                    return;
                }
                refreshSavedConfigs();
                configStatusMessage = "Deleted \"" + name + "\"";
            } catch (Exception ex) {
                configStatusMessage = "Delete failed: " + ex.getMessage();
            }
        }

        @Override public int cloudLimit() { return MAX_CLOUD_CONFIGS; }

        @Override public int cloudCount() { return cloudProfiles.size(); }

        @Override public String status() { return configStatusMessage; }

        @Override public void setStatus(String text) { configStatusMessage = text == null ? "" : text; }

        @Override public void closeMenu() { close(); }

        @Override public String loadedConfig() { return ConfigManager.getInstance().getLoadedConfigName(); }

        @Override public boolean unsavedChanges() { return ConfigManager.getInstance().hasUnsavedChanges(); }

        @Override public void overwrite() {
            if (!CloudConfigs.signedIn()) { configStatusMessage = "Sign in to save configs."; return; }
            if (profileLoadPending) { configStatusMessage = "Wait for the config to finish loading."; return; }
            String name = ConfigManager.getInstance().getLoadedConfigName();
            if (name == null) { configStatusMessage = "No config is loaded. Save as a new config instead."; return; }
            configStatusMessage = "Saving your changes to \"" + name + "\"...";
            PrivateConfigManager.getInstance().overwriteLoaded(saved -> {
                configStatusMessage = "Saved your changes to \"" + saved + "\".";
                refreshSavedConfigs();
            }, error -> configStatusMessage = "Overwrite failed: " + error);
        }
    }

    /** Friend / enemy access for the People page. */
    private final class PeopleBackend implements PeopleView.Backend {
        @Override public List<PeopleView.Person> saved() {
            List<PeopleView.Person> out = new ArrayList<>();
            for (Friend friend : FriendManager.getInstance().getFriends()) {
                out.add(new PeopleView.Person(friend.getName(), friend.getUuid(), true,
                        EnemyManager.getInstance().isEnemy(friend.getUuid())));
            }
            for (Enemy enemy : EnemyManager.getInstance().getEnemies()) {
                if (FriendManager.getInstance().isFriend(enemy.getUuid())) continue;
                out.add(new PeopleView.Person(enemy.getName(), enemy.getUuid(), false, true));
            }
            out.sort(Comparator.comparing(PeopleView.Person::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        }

        @Override public List<PeopleView.Person> nearby() {
            if (client == null || client.world == null) return List.of();
            List<PeopleView.Person> out = new ArrayList<>();
            for (PlayerEntity player : client.world.getPlayers()) {
                if (client.player != null && player.getUuid().equals(client.player.getUuid())) continue;
                out.add(new PeopleView.Person(player.getName().getString(), player.getUuid(),
                        FriendManager.getInstance().isFriend(player.getUuid()),
                        EnemyManager.getInstance().isEnemy(player.getUuid())));
            }
            out.sort(Comparator.comparing(PeopleView.Person::name, String.CASE_INSENSITIVE_ORDER));
            return out;
        }

        @Override public String toggleFriend(PeopleView.Person person) {
            if (FriendManager.getInstance().isFriend(person.uuid())) {
                FriendManager.getInstance().removeFriend(person.uuid());
                return "Removed friend " + person.name();
            }
            FriendManager.getInstance().addFriend(person.name(), person.uuid());
            return "Added friend " + person.name();
        }

        @Override public String toggleEnemy(PeopleView.Person person) {
            if (EnemyManager.getInstance().isEnemy(person.uuid())) {
                EnemyManager.getInstance().removeEnemy(person.uuid());
                return "Removed enemy " + person.name();
            }
            EnemyManager.getInstance().addEnemy(person.name(), person.uuid());
            return "Added enemy " + person.name();
        }

        @Override public String remove(PeopleView.Person person) {
            if (person.friend()) FriendManager.getInstance().removeFriend(person.uuid());
            if (person.enemy()) EnemyManager.getInstance().removeEnemy(person.uuid());
            return "Removed " + person.name();
        }
    }
}
