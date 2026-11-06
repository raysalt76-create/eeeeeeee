package interstellarTikiTiks.apexx.client.gui.new_ui;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.config.ClientPrefs;
import interstellarTikiTiks.apexx.client.config.CloudConfigSync;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.hud.GpuHudRenderer;
import interstellarTikiTiks.apexx.client.hud.HUDElement;
import interstellarTikiTiks.apexx.client.hud.HUDManager;
import interstellarTikiTiks.apexx.client.hud.HudStyleElement;
import interstellarTikiTiks.apexx.client.hud.TargetHudElement;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen GPU editor for the same widgets used by the live HUD.
 *
 * <p>Left-drag moves (grid, centre and edge snapping), right-click toggles, Shift+right-click opens the
 * widget settings (scale, reset, full colour picker), arrow keys nudge the selected widget (Shift = 10),
 * the scroll wheel over a widget scales it. The header has HUD Style (HUD-wide theme settings), Reset
 * Layout (click twice) and Done.
 */
public final class HudEditorScreen extends Screen {
    private static final float GRID = 16f;
    private static final float SNAP = 8f;
    private static final float GUIDE_SNAP = 4f;

    private static final float ROW = 27f;
    private static final float PICKER_HEIGHT = 153f;
    private static final float SV_HEIGHT = 84f;
    private static final long RESET_ARM_MS = 3000L;
    private static final long SCALE_FLASH_MS = 900L;
    private static final double SCALE_STEP = 0.05;
    private static final int[] PRESETS = {
            0xFFFFFFFF, 0xFFFF5A5F, 0xFFFFA94D, 0xFFFFD43B, 0xFF51CF66,
            0xFF26E0C6, 0xFF4DABF7, 0xFFB197FC, 0xFFF783AC};
    private static final int[] HUE_STOPS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
    private static final String[] LEGEND_KEYS = {"DRAG", "R-CLICK", "SHIFT+R-CLICK", "SHIFT+CLICK", "ARROWS", "SHIFT", "SCROLL"};
    private static final String[] LEGEND_LABELS = {"move", "toggle", "settings", "multi-select", "nudge", "x10", "scale"};
    private static final int COMPACT_LEGEND = 3;
    private static final long HINT_MS = 2600L;
    private static final long DELETE_ARM_MS = 3000L;
    private static final int LAYOUT_NAME_MAX = 32;

    private enum PickPart { NONE, SV, HUE, ALPHA }

    /** Align / distribute actions on the selection (or, with one widget, against the screen). */
    private enum AlignOp {
        LEFT("ALIGN LEFT"), RIGHT("ALIGN RIGHT"), HCENTER("CENTER X"), VCENTER("CENTER Y"),
        TOP("ALIGN TOP"), BOTTOM("ALIGN BOTTOM"), SPREAD_H("SPREAD X"), SPREAD_V("SPREAD Y"),
        ALL("SELECT ALL"), NONE("CLEAR");
        final String label;
        AlignOp(String label) { this.label = label; }
    }

    // Multi-selection (Shift+click, or plain clicks while the Align panel is open), layouts and align panels.
    private final java.util.LinkedHashSet<HUDElement> multi = new java.util.LinkedHashSet<>();
    private boolean layoutsOpen;
    private boolean alignOpen;
    private int layoutsScroll;
    private String armedDeleteLayout;
    private long armedDeleteUntil;
    private boolean editingLayoutName;
    private final StringBuilder layoutName = new StringBuilder();
    private String hint;
    private long hintUntil;
    private boolean showTitle = true;
    private float layoutsX, layoutsW, alignX, alignW;

    // Theme-derived palette, refreshed every frame from the ClickGUI theme.
    private int accent = 0xFFDEE2E6;
    private int primary = 0xFFF1F3F4;
    private int secondary = 0xFF9CA1A6;
    private int muted = 0xFF666B70;
    private int border = 0x3Affffff;
    private int panelTop = 0xF51D2836;
    private int panelBottom = 0xF20E151F;
    private int backdrop = 0xD4070B12;
    private int gridTint = 0x00FFFFFF;
    private int toggleOff = 0xFF445163;
    private static final int DANGER = 0xFFFF6B6B;

    private final Screen parent;
    private HUDElement dragging;
    private HUDElement selected;
    private HUDElement settingsElement;
    private SliderSetting draggingSlider;
    private StringSetting editingString;
    private int settingsScrollOffset;
    private double sliderMinX;
    private double sliderMaxX;
    private boolean saved;
    /** 0 = header/footer docked, 1 = slid fully off-screen while a widget is being dragged. */
    private float chromeHide;
    private long lastFrameNanos;
    private float guideX = Float.NaN;
    private float guideY = Float.NaN;

    // Colour picker (one expanded colour row at a time).
    private ColorSetting expandedColor;
    private float pickHue, pickSat, pickBri;
    private PickPart pickDrag = PickPart.NONE;
    private boolean editingHex;
    private final StringBuilder hexBuffer = new StringBuilder(9);
    private float svX, svY, svW, svH, hueX, hueY, hueW, hueH, alphaX, alphaY, alphaW, alphaH;
    private float hexX, hexY, hexW, hexH, swX, swY, swSize, swGap;

    // Header geometry (screen px), recomputed every frame and for hit tests.
    private float hdrX, hdrY, hdrW, hdrH, btnY, btnH, styleX, styleW, resetX, resetW, doneX, doneW;
    private long resetArmedUntil;

    private HUDElement scaleFlash;
    private long scaleFlashUntil;
    private String hoverDescription;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD Editor"));
        this.parent = parent;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private void refreshTheme() {
        ClickGUIModule style = null;
        try {
            ModuleManager manager = Apexx.getInstance().getModuleManager();
            style = manager == null ? null : manager.getModule(ClickGUIModule.class);
        } catch (Throwable ignored) { }
        UiThemeMaterial.Theme theme = UiThemeMaterial.theme(style == null ? "" : style.theme.getCurrentMode());
        accent = style != null && style.customHighlight.isEnabled()
                ? 0xFF000000 | style.highlightColor1.getValue().getRGB()
                : theme.accent();
        primary = theme.text();
        secondary = theme.muted();
        muted = theme.muted2();
        int tint = theme.tint() & 0xFFFFFF;
        int base = theme.panelBase() & 0xFFFFFF;
        int deep = theme.deep() & 0xFFFFFF;
        border = (0x58 << 24) | mix(tint, theme.accent() & 0xFFFFFF, .35f);
        panelTop = (0xF4 << 24) | mix(base, tint, .10f);
        panelBottom = (0xF2 << 24) | mix(deep, base, .45f);
        backdrop = (0xCC << 24) | deep;
        gridTint = tint;
        toggleOff = theme.toggleOff() | 0xFF000000;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refreshTheme();
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(.1f, (now - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = now;
        float target = dragging != null ? 1f : 0f;
        chromeHide += (target - chromeHide) * (1f - (float) Math.exp(-16f * dt));
        if (Math.abs(target - chromeHide) < .002f) chromeHide = target;
        int sw = context.getScaledWindowWidth();
        int sh = context.getScaledWindowHeight();
        float scale = GpuHudRenderer.viewportScale(sw, sh);
        List<HUDElement> elements = HUDManager.getInstance().getElements();
        if (sw != lastSizeW || sh != lastSizeH) {
            // The window was resized: pull every widget back inside the new bounds. Header, panels and the open
            // popup are laid out from the size each frame, so they follow without help; a drag in progress carries on.
            if (lastSizeW != 0) for (HUDElement element : elements) clampToScreen(element);
            lastSizeW = sw;
            lastSizeH = sh;
        }
        UiDisplayList.Builder background = UiDisplayList.builder();

        background.rect(0, 0, sw, sh, 0, backdrop);
        drawGrid(background, sw, sh);
        GpuUiRenderer.render(context, background.build());

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.scale(scale, scale);
            GpuHudRenderer.renderElements(context, elements, Math.round(sw / scale), Math.round(sh / scale), delta, true);
        } finally {
            matrices.popMatrix();
        }
        if (client != null && client.player != null) HUDManager.renderArmorIcons(context, elements, scale);
        UiDisplayList.Builder b = UiDisplayList.builder();

        HUDElement hovered = elementAt(elements, mouseX, mouseY, sw, sh);
        if (hovered != null) {
            float x = GpuHudRenderer.editorX(hovered, sw, sh);
            float y = GpuHudRenderer.editorY(hovered, sw, sh);
            float w = GpuHudRenderer.editorWidth(hovered, sw, sh);
            float h = GpuHudRenderer.editorHeight(hovered, sw, sh);
            int edge = withAlpha(accent, 230);
            b.border(x - 2, y - 2, w + 4, h + 4, Math.min(18, h * .5f), 1.2f, edge);
        }
        if (selected != null && selected != hovered) {
            float x = GpuHudRenderer.editorX(selected, sw, sh);
            float y = GpuHudRenderer.editorY(selected, sw, sh);
            float w = GpuHudRenderer.editorWidth(selected, sw, sh);
            float h = GpuHudRenderer.editorHeight(selected, sw, sh);
            b.border(x - 1, y - 1, w + 2, h + 2, Math.min(18, h * .5f), 1f,
                    withAlpha(accent, 145));
        }
        if (multi.size() > 1) {
            for (HUDElement element : multi) {
                if (element == hovered) continue;
                float x = GpuHudRenderer.editorX(element, sw, sh);
                float y = GpuHudRenderer.editorY(element, sw, sh);
                float w = GpuHudRenderer.editorWidth(element, sw, sh);
                float h = GpuHudRenderer.editorHeight(element, sw, sh);
                b.rect(x - 1, y - 1, w + 2, h + 2, Math.min(18, h * .5f), withAlpha(accent, 0x1C));
                b.border(x - 2, y - 2, w + 4, h + 4, Math.min(18, h * .5f), 1.4f, withAlpha(accent, 0xD0));
            }
        }

        if (dragging != null) drawGuides(b, sw, sh);
        layoutHeader(sw, sh);
        if (chromeHide < .999f) {
            drawHeader(b, sw, sh, mouseX, mouseY);
            drawFooter(b, sw, sh, widgetCount(elements));
            if (layoutsOpen) drawLayoutsPanel(b, sw, sh, mouseX, mouseY);
            if (alignOpen) drawAlignPanel(b, sw, sh, mouseX, mouseY);
        }
        drawHint(b, sw, sh);
        hoverDescription = null;
        if (dragging == null) {
            if (hovered != null && settingsElement == null) drawElementLabel(b, hovered, mouseX, mouseY, sw, sh);
            if (settingsElement != null) drawSettingsPopup(b, settingsElement, sw, sh, mouseX, mouseY);
        }
        drawScaleFlash(b, sw, sh);
        if (hoverDescription != null && settingsElement != null) drawDescription(b, settingsElement, sw, sh);
        GpuUiRenderer.render(context, b.build());
    }

    private static int widgetCount(List<HUDElement> elements) {
        int count = 0;
        for (HUDElement element : elements) if (HUDManager.isWidget(element)) count++;
        return count;
    }

    private void drawGrid(UiDisplayList.Builder b, int sw, int sh) {
        float s = GpuHudRenderer.viewportScale(sw, sh);
        float step = GRID * s;
        boolean active = dragging != null;
        int minor = withAlpha(gridTint, active ? 0x1C : 0x0C);
        int major = withAlpha(gridTint, active ? 0x34 : 0x18);
        int col = 0;
        for (float x = 0; x <= sw; x += step, col++) {
            b.rect(Math.round(x), 0, 1, sh, 0, col % 4 == 0 ? major : minor);
        }
        int row = 0;
        for (float y = 0; y <= sh; y += step, row++) {
            b.rect(0, Math.round(y), sw, 1, 0, row % 4 == 0 ? major : minor);
        }
        int center = withAlpha(accent, active ? 0x66 : 0x2E);
        b.rect(sw * .5f, 0, 1, sh, 0, center);
        b.rect(0, sh * .5f, sw, 1, 0, center);
    }

    private void drawGuides(UiDisplayList.Builder b, int sw, int sh) {
        int col = withAlpha(accent, 0xB0);
        if (!Float.isNaN(guideX)) b.rect(Math.round(guideX), 0, 1, sh, 0, col);
        if (!Float.isNaN(guideY)) b.rect(0, Math.round(guideY), sw, 1, 0, col);
    }

    /** Snaps the dragged widget to the grid, screen centre and other widgets' edges (layout units). */
    private void snapDragged(HUDElement element, int layoutWidth, int layoutHeight, float scale) {
        guideX = Float.NaN;
        guideY = Float.NaN;
        float w = (float) element.getWidth() * (float) element.getScale();
        float h = (float) element.getHeight() * (float) element.getScale();
        double x = Math.round(element.getX() / SNAP) * SNAP;
        double y = Math.round(element.getY() / SNAP) * SNAP;

        double bestDx = GUIDE_SNAP + .001, bestDy = GUIDE_SNAP + .001;
        double[] xs = {layoutWidth * .5 - w * .5, 0, layoutWidth - w};
        double[] ys = {layoutHeight * .5 - h * .5, 0, layoutHeight - h};
        double[] gx = {layoutWidth * .5, 0, layoutWidth};
        double[] gy = {layoutHeight * .5, 0, layoutHeight};
        double snapX = x, snapY = y;
        double gX = Double.NaN, gY = Double.NaN;
        for (int i = 0; i < 3; i++) {
            double d = Math.abs(element.getX() - xs[i]);
            if (d < bestDx) { bestDx = d; snapX = xs[i]; gX = gx[i]; }
            d = Math.abs(element.getY() - ys[i]);
            if (d < bestDy) { bestDy = d; snapY = ys[i]; gY = gy[i]; }
        }
        for (HUDElement other : HUDManager.getInstance().getElements()) {
            if (other == element || other instanceof TargetHudElement || !HUDManager.isWidget(other)) continue;
            if (other.getX() < 0 || other.getWidth() <= 1) continue;
            double ox = other.getX(), oy = other.getY();
            double ow = other.getWidth() * other.getScale(), oh = other.getHeight() * other.getScale();
            double[] cx = {ox, ox + ow, ox + ow * .5};
            double[] mx = {element.getX(), element.getX() + w, element.getX() + w * .5};
            for (int i = 0; i < 3; i++) {
                double d = Math.abs(mx[i] - cx[i]);
                if (d < bestDx) { bestDx = d; snapX = element.getX() + (cx[i] - mx[i]); gX = cx[i]; }
            }
            double[] cy = {oy, oy + oh, oy + oh * .5};
            double[] my = {element.getY(), element.getY() + h, element.getY() + h * .5};
            for (int i = 0; i < 3; i++) {
                double d = Math.abs(my[i] - cy[i]);
                if (d < bestDy) { bestDy = d; snapY = element.getY() + (cy[i] - my[i]); gY = cy[i]; }
            }
        }
        element.setX(snapX);
        element.setY(snapY);
        if (!Double.isNaN(gX)) guideX = (float) gX * scale;
        if (!Double.isNaN(gY)) guideY = (float) gY * scale;
    }

    // ---------------------------------------------------------------- header / footer

    private int lastSizeW, lastSizeH;

    /**
     * Scale of the editor's own chrome (header, panels, popup, legend), in GUI units per design unit. The widgets keep
     * {@link GpuHudRenderer#viewportScale}; the chrome is raised so labels stay readable on a big or dense display
     * and, on a touch screen, buttons stay finger sized (see ResponsiveLayout.hudChromeScale).
     */
    private float chromeScale(int sw, int sh) {
        float ppg = 1f;
        if (client != null && client.getWindow() != null && client.getWindow().getScaledWidth() > 0) {
            ppg = (float) client.getWindow().getFramebufferWidth() / client.getWindow().getScaledWidth();
        }
        return interstellarTikiTiks.apexx.client.gui.gpuui.kit.ResponsiveLayout.hudChromeScale(
                GpuHudRenderer.viewportScale(sw, sh), sh * ppg, ppg,
                interstellarTikiTiks.apexx.client.util.PlatformPreset.touchUi());
    }

    private void layoutHeader(int sw, int sh) {
        float s = chromeScale(sw, sh);
        float titleW = GpuUiRenderer.textWidth("Hud Editor", 9.5f * s);
        btnH = 20f * s;
        hdrH = 30f * s;
        styleW = GpuUiRenderer.textWidth(COLOURS_LABEL, 7.5f * s) + 22f * s;
        layoutsW = GpuUiRenderer.textWidth("LAYOUTS", 7.5f * s) + 22f * s;
        alignW = GpuUiRenderer.textWidth("ALIGN", 7.5f * s) + 22f * s;
        resetW = Math.max(GpuUiRenderer.textWidth("RESET LAYOUT", 7.5f * s),
                GpuUiRenderer.textWidth("CLICK TO CONFIRM", 7.5f * s)) + 20f * s;
        doneW = 44f * s;
        float buttons = styleW + 6f * s + layoutsW + 6f * s + alignW + 6f * s + resetW + 6f * s + doneW + 6f * s;
        float full = 23f * s + titleW + 16f * s + buttons;
        // Narrow (mobile) screens drop the title before the buttons start overlapping it.
        showTitle = full <= sw - 24f * s;
        hdrW = Math.min(showTitle ? full : 14f * s + buttons, sw - 24f * s);
        hdrX = (sw - hdrW) * .5f;
        hdrY = 12f * s - chromeHide * (hdrH + 24f * s);
        btnY = hdrY + (hdrH - btnH) * .5f;
        doneX = hdrX + hdrW - doneW - 6f * s;
        resetX = doneX - 6f * s - resetW;
        alignX = resetX - 6f * s - alignW;
        layoutsX = alignX - 6f * s - layoutsW;
        styleX = layoutsX - 6f * s - styleW;
    }

    private boolean resetArmed() {
        return System.currentTimeMillis() < resetArmedUntil;
    }

    private void drawHeader(UiDisplayList.Builder b, int sw, int sh, int mouseX, int mouseY) {
        float s = chromeScale(sw, sh);
        b.shadow(hdrX, hdrY + s, hdrW, hdrH, hdrH * .5f, 14f * s, 1, 0x64000000);
        b.gradient(hdrX, hdrY, hdrW, hdrH, hdrH * .5f, panelTop, panelBottom, panelBottom, panelTop);
        b.border(hdrX, hdrY, hdrW, hdrH, hdrH * .5f, Math.max(.7f, s), border);
        if (showTitle) {
            b.circle(hdrX + 14f * s, hdrY + hdrH * .5f, 2.8f * s, accent);
            float textY = hdrY + (hdrH - 9.5f * s) * .5f - .5f;
            b.text(hdrX + 23f * s, textY, 9.5f * s, primary, "Hud Editor", UiDrawCommand.TextWeight.SEMIBOLD);
        }

        boolean styleOpen = settingsElement instanceof HudStyleElement;
        drawButton(b, styleX, btnY, styleW, btnH, COLOURS_LABEL, inRect(mouseX, mouseY, styleX, btnY, styleW, btnH) || styleOpen,
                accent, s);
        drawButton(b, layoutsX, btnY, layoutsW, btnH, "LAYOUTS",
                inRect(mouseX, mouseY, layoutsX, btnY, layoutsW, btnH) || layoutsOpen, accent, s);
        drawButton(b, alignX, btnY, alignW, btnH, "ALIGN",
                inRect(mouseX, mouseY, alignX, btnY, alignW, btnH) || alignOpen, accent, s);
        boolean armed = resetArmed();
        drawButton(b, resetX, btnY, resetW, btnH, armed ? "CLICK TO CONFIRM" : "RESET LAYOUT",
                inRect(mouseX, mouseY, resetX, btnY, resetW, btnH) || armed, armed ? DANGER : secondary, s);
        drawButton(b, doneX, btnY, doneW, btnH, "DONE", inRect(mouseX, mouseY, doneX, btnY, doneW, btnH), accent, s);
    }

    // ---------------------------------------------------------------- layouts panel (named presets)

    private float panelsTop(float s) {
        return hdrY + hdrH + 8f * s;
    }

    private int layoutRowsVisible(int count, int sh, float s) {
        float room = sh - panelsTop(s) - 10f * s - 28f * s - 38f * s;
        return Math.max(1, Math.min(Math.max(1, count), (int) (room / (22f * s))));
    }

    /**
     * Draws the layouts panel when {@code b} is non-null and returns the action under (mx, my): a no-op
     * inside the panel, or null outside it. One routine for both, so drawing and clicking cannot drift.
     */
    private Runnable layoutsPanel(UiDisplayList.Builder b, int sw, int sh, double mx, double my) {
        float s = chromeScale(sw, sh);
        List<String> names = ClientPrefs.get().hudLayoutNames();
        int rows = layoutRowsVisible(names.size(), sh, s);
        layoutsScroll = Math.max(0, Math.min(layoutsScroll, Math.max(0, names.size() - rows)));
        float w = Math.min(230f * s, sw - 20f * s);
        float x = Math.max(10f * s, Math.min(sw - w - 10f * s, layoutsX + layoutsW * .5f - w * .5f));
        float y = panelsTop(s);
        float h = 28f * s + rows * 22f * s + 38f * s;
        Runnable action = inRect(mx, my, x, y, w, h) ? () -> { } : null;
        if (b != null) {
            b.shadow(x, y, w, h, 12f * s, 12f * s, s, 0x72000000);
            b.gradient(x, y, w, h, 12f * s, panelTop, panelBottom, panelBottom, panelTop);
            b.border(x, y, w, h, 12f * s, Math.max(.7f, s), border);
            b.text(x + 12f * s, y + 10f * s, 8.5f * s, primary, "Saved layouts", UiDrawCommand.TextWeight.SEMIBOLD);
            String count = names.size() + "/" + ClientPrefs.MAX_HUD_LAYOUTS;
            b.text(x + w - 12f * s - GpuUiRenderer.textWidth(count, 7.5f * s), y + 11f * s, 7.5f * s, muted, count);
        }
        if (names.isEmpty() && b != null) {
            b.text(x + 12f * s, y + 33f * s, 8f * s, secondary, "No layouts yet. Save the current one below.");
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows && layoutsScroll + i < names.size(); i++) {
            String name = names.get(layoutsScroll + i);
            float ry = y + 28f * s + i * 22f * s;
            boolean armed = name.equals(armedDeleteLayout) && now < armedDeleteUntil;
            String del = armed ? "SURE?" : "DEL";
            float delW = Math.max(GpuUiRenderer.textWidth("SURE?", 7.5f * s), GpuUiRenderer.textWidth("DEL", 7.5f * s)) + 14f * s;
            float loadW = GpuUiRenderer.textWidth("LOAD", 7.5f * s) + 14f * s;
            float bh = 17f * s, by = ry + 2.5f * s;
            float delX = x + w - 10f * s - delW, loadX = delX - 5f * s - loadW;
            if (b != null) {
                if (i > 0) b.rect(x + 10f * s, ry, w - 20f * s, Math.max(.7f, s), 0, withAlpha(border, 0x30));
                b.text(x + 12f * s, ry + 7f * s, 8.5f * s, primary,
                        fit(name, loadX - x - 20f * s, 8.5f * s));
                drawButton(b, loadX, by, loadW, bh, "LOAD", inRect(mx, my, loadX, by, loadW, bh), accent, s);
                drawButton(b, delX, by, delW, bh, del, inRect(mx, my, delX, by, delW, bh) || armed,
                        armed ? DANGER : secondary, s);
            }
            if (inRect(mx, my, loadX, by, loadW, bh)) action = () -> applyLayoutPreset(name);
            else if (inRect(mx, my, delX, by, delW, bh)) action = () -> {
                if (armed) {
                    ClientPrefs.get().removeHudLayout(name);
                    armedDeleteLayout = null;
                    showHint("Deleted layout \"" + name + "\"");
                } else {
                    armedDeleteLayout = name;
                    armedDeleteUntil = System.currentTimeMillis() + DELETE_ARM_MS;
                }
            };
        }
        // Footer: optional name + Save (a blank name becomes "Layout N"; an existing name is updated).
        float fy = y + 28f * s + rows * 22f * s + 7f * s, fh = 22f * s;
        float saveW = GpuUiRenderer.textWidth("SAVE", 7.5f * s) + 18f * s;
        float fieldX = x + 10f * s, fieldW = w - 20f * s - saveW - 6f * s, saveX = fieldX + fieldW + 6f * s;
        if (b != null) {
            b.rect(fieldX, fy, fieldW, fh, 4f * s, editingLayoutName ? withAlpha(accent, 0x30) : withAlpha(muted, 0x30));
            b.border(fieldX, fy, fieldW, fh, 4f * s, Math.max(.7f, s),
                    editingLayoutName ? withAlpha(accent, 190) : withAlpha(muted, 0x80));
            String shown = layoutName.isEmpty() && !editingLayoutName ? "Name (optional)" : layoutName.toString();
            if (editingLayoutName && (System.currentTimeMillis() / 500L) % 2L == 0L) shown += "_";
            b.text(fieldX + 6f * s, fy + (fh - 8f * s) * .5f - .3f, 8f * s,
                    layoutName.isEmpty() && !editingLayoutName ? muted : primary, fit(shown, fieldW - 12f * s, 8f * s));
            drawButton(b, saveX, fy, saveW, fh, "SAVE", inRect(mx, my, saveX, fy, saveW, fh), accent, s);
        }
        if (inRect(mx, my, fieldX, fy, fieldW, fh)) action = () -> editingLayoutName = true;
        else if (inRect(mx, my, saveX, fy, saveW, fh)) action = this::saveLayoutPreset;
        return action;
    }

    private void saveLayoutPreset() {
        List<String> names = ClientPrefs.get().hudLayoutNames();
        String name = layoutName.toString().trim();
        if (name.isEmpty()) {
            int n = names.size() + 1;
            do name = "Layout " + n++; while (names.contains(name));
        }
        com.google.gson.JsonObject layout = new com.google.gson.JsonObject();
        for (HUDElement element : HUDManager.getInstance().getElements()) {
            if (!HUDManager.isWidget(element)) continue;
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("x", element.getX());
            entry.addProperty("y", element.getY());
            entry.addProperty("enabled", element.isEnabled());
            entry.addProperty("scale", element.getScale());
            layout.add(element.getName(), entry);
        }
        boolean existed = names.contains(name);
        if (!ClientPrefs.get().putHudLayout(name, layout)) {
            showHint("You have " + ClientPrefs.MAX_HUD_LAYOUTS + " layouts. Delete one first.");
            return;
        }
        layoutName.setLength(0);
        editingLayoutName = false;
        showHint((existed ? "Updated" : "Saved") + " layout \"" + name + "\"");
    }

    /** Positions, scale and on/off of every widget from a saved layout; widgets it does not list stay as they are. */
    private void applyLayoutPreset(String name) {
        com.google.gson.JsonObject layout = ClientPrefs.get().hudLayout(name);
        if (layout == null) return;
        closePopup();
        settingsElement = null;
        for (HUDElement element : HUDManager.getInstance().getElements()) {
            if (!HUDManager.isWidget(element)) continue;
            com.google.gson.JsonElement raw = layout.get(element.getName());
            // Layouts saved before a widget was renamed list it under its old name.
            String legacy = HUDManager.legacyName(element.getName());
            if ((raw == null || !raw.isJsonObject()) && legacy != null) raw = layout.get(legacy);
            if (raw == null || !raw.isJsonObject()) continue;
            com.google.gson.JsonObject entry = raw.getAsJsonObject();
            try { if (entry.has("scale")) element.setScale(entry.get("scale").getAsDouble()); } catch (RuntimeException ignored) { }
            try { if (entry.has("x")) element.setX(entry.get("x").getAsDouble()); } catch (RuntimeException ignored) { }
            try { if (entry.has("y")) element.setY(entry.get("y").getAsDouble()); } catch (RuntimeException ignored) { }
            try {
                if (entry.has("enabled")) {
                    boolean enabled = entry.get("enabled").getAsBoolean();
                    if (enabled != element.isEnabled()) element.setEnabled(enabled);
                }
            } catch (RuntimeException ignored) { }
            clampToScreen(element);
        }
        multi.clear();
        selected = null;
        showHint("Loaded layout \"" + name + "\"");
    }

    // ---------------------------------------------------------------- align panel

    private Runnable alignPanel(UiDisplayList.Builder b, int sw, int sh, double mx, double my) {
        float s = chromeScale(sw, sh);
        AlignOp[] ops = AlignOp.values();
        int rowsCount = (ops.length + 1) / 2;
        float w = Math.min(236f * s, sw - 20f * s);
        float x = Math.max(10f * s, Math.min(sw - w - 10f * s, alignX + alignW * .5f - w * .5f));
        float y = panelsTop(s);
        float h = 30f * s + rowsCount * 25f * s + 16f * s;
        Runnable action = inRect(mx, my, x, y, w, h) ? () -> { } : null;
        int count = alignTargets().size();
        if (b != null) {
            b.shadow(x, y, w, h, 12f * s, 12f * s, s, 0x72000000);
            b.gradient(x, y, w, h, 12f * s, panelTop, panelBottom, panelBottom, panelTop);
            b.border(x, y, w, h, 12f * s, Math.max(.7f, s), border);
            b.text(x + 12f * s, y + 10f * s, 8.5f * s, primary, "Align", UiDrawCommand.TextWeight.SEMIBOLD);
            String selectedText = count == 0 ? "nothing selected" : count == 1 ? "1 widget: to screen" : count + " widgets";
            b.text(x + w - 12f * s - GpuUiRenderer.textWidth(selectedText, 7.5f * s), y + 11f * s, 7.5f * s, muted, selectedText);
            b.text(x + 12f * s, y + h - 13f * s, 7f * s, muted,
                    fit("Click widgets to add or remove them from the selection.", w - 24f * s, 7f * s));
        }
        float cw = (w - 20f * s - 6f * s) / 2f, ch = 20f * s;
        for (int i = 0; i < ops.length; i++) {
            AlignOp op = ops[i];
            float cx = x + 10f * s + (i % 2) * (cw + 6f * s);
            float cy = y + 30f * s + (i / 2) * 25f * s;
            boolean hot = inRect(mx, my, cx, cy, cw, ch);
            if (b != null) drawButton(b, cx, cy, cw, ch, op.label, hot,
                    op == AlignOp.ALL || op == AlignOp.NONE ? secondary : accent, s);
            if (hot) action = () -> applyAlign(op);
        }
        return action;
    }

    /** Widgets the align actions move: the multi-selection, else the selected widget (TargetHUD excluded). */
    private List<HUDElement> alignTargets() {
        List<HUDElement> out = new java.util.ArrayList<>();
        if (!multi.isEmpty()) {
            for (HUDElement element : multi) if (!(element instanceof TargetHudElement)) out.add(element);
        } else if (selected != null && !(selected instanceof TargetHudElement) && HUDManager.isWidget(selected)) {
            out.add(selected);
        }
        return out;
    }

    private void applyAlign(AlignOp op) {
        if (op == AlignOp.ALL) {
            multi.clear();
            for (HUDElement element : HUDManager.getInstance().getElements()) {
                if (HUDManager.isWidget(element) && element.isEnabled() && !(element instanceof TargetHudElement)) multi.add(element);
            }
            selected = multi.isEmpty() ? null : multi.iterator().next();
            showHint(multi.size() + " widgets selected");
            return;
        }
        if (op == AlignOp.NONE) {
            multi.clear();
            selected = null;
            return;
        }
        List<HUDElement> targets = alignTargets();
        if (targets.isEmpty()) {
            showHint("Select a widget first (Shift+click for more)");
            return;
        }
        boolean spread = op == AlignOp.SPREAD_H || op == AlignOp.SPREAD_V;
        if (spread && targets.size() < 3) {
            showHint("Select 3 or more widgets to spread them evenly");
            return;
        }
        float scale = GpuHudRenderer.viewportScale(this.width, this.height);
        double layoutWidth = Math.round(this.width / scale), layoutHeight = Math.round(this.height / scale);
        for (HUDElement element : targets) unanchor(element, this.width, this.height);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (HUDElement element : targets) {
            minX = Math.min(minX, element.getX());
            minY = Math.min(minY, element.getY());
            maxX = Math.max(maxX, element.getX() + widthOf(element));
            maxY = Math.max(maxY, element.getY() + heightOf(element));
        }
        // One widget aligns to the screen; several align to their shared bounding box.
        boolean single = targets.size() == 1;
        double left = single ? 0 : minX, right = single ? layoutWidth : maxX;
        double top = single ? 0 : minY, bottom = single ? layoutHeight : maxY;
        switch (op) {
            case LEFT -> targets.forEach(e -> e.setX(left));
            case RIGHT -> targets.forEach(e -> e.setX(right - widthOf(e)));
            case HCENTER -> targets.forEach(e -> e.setX((left + right) * .5 - widthOf(e) * .5));
            case TOP -> targets.forEach(e -> e.setY(top));
            case BOTTOM -> targets.forEach(e -> e.setY(bottom - heightOf(e)));
            case VCENTER -> targets.forEach(e -> e.setY((top + bottom) * .5 - heightOf(e) * .5));
            case SPREAD_H -> spread(targets, true, minX, maxX);
            case SPREAD_V -> spread(targets, false, minY, maxY);
            default -> { }
        }
        for (HUDElement element : targets) clampToScreen(element);
        showHint(op.label.charAt(0) + op.label.substring(1).toLowerCase(Locale.ROOT)
                + (single ? " (to screen)" : " (" + targets.size() + " widgets)"));
    }

    /** Equal gaps between the widgets along one axis; equal centre spacing if they are too big to fit. */
    private static void spread(List<HUDElement> targets, boolean horizontal, double min, double max) {
        List<HUDElement> sorted = new java.util.ArrayList<>(targets);
        sorted.sort(java.util.Comparator.comparingDouble(e -> horizontal ? e.getX() : e.getY()));
        int n = sorted.size();
        double total = 0;
        for (HUDElement e : sorted) total += horizontal ? widthOf(e) : heightOf(e);
        double gap = (max - min - total) / (n - 1);
        if (gap >= 0) {
            double cursor = min;
            for (HUDElement e : sorted) {
                if (horizontal) e.setX(cursor); else e.setY(cursor);
                cursor += (horizontal ? widthOf(e) : heightOf(e)) + gap;
            }
        } else {
            HUDElement first = sorted.get(0), last = sorted.get(n - 1);
            double c0 = (horizontal ? first.getX() + widthOf(first) * .5 : first.getY() + heightOf(first) * .5);
            double c1 = (horizontal ? last.getX() + widthOf(last) * .5 : last.getY() + heightOf(last) * .5);
            for (int i = 0; i < n; i++) {
                HUDElement e = sorted.get(i);
                double c = c0 + (c1 - c0) * i / (n - 1);
                if (horizontal) e.setX(c - widthOf(e) * .5); else e.setY(c - heightOf(e) * .5);
            }
        }
    }

    private static double widthOf(HUDElement element) {
        return element.getWidth() * element.getScale();
    }

    private static double heightOf(HUDElement element) {
        return element.getHeight() * element.getScale();
    }

    private void showHint(String text) {
        hint = text;
        hintUntil = System.currentTimeMillis() + HINT_MS;
    }

    private void drawHint(UiDisplayList.Builder b, int sw, int sh) {
        if (hint == null || System.currentTimeMillis() > hintUntil) return;
        float s = chromeScale(sw, sh);
        float size = 8.5f * s;
        String text = fit(hint, sw - 60f * s, size);
        float w = GpuUiRenderer.textWidth(text, size) + 20f * s, h = 20f * s;
        float x = (sw - w) * .5f, y = sh - 26f * s - 12f * s - h - 8f * s;
        b.pill(x, y, w, h, withAlpha(accent, 0xD8));
        b.text(x + 10f * s, y + (h - size) * .5f - .3f, size, contrastOn(accent), text, UiDrawCommand.TextWeight.SEMIBOLD);
    }

    /** Trims {@code text} with an ellipsis to fit {@code maxW}. */
    private static String fit(String text, float maxW, float size) {
        if (text == null) return "";
        if (maxW <= 0) return "";
        if (GpuUiRenderer.textWidth(text, size) <= maxW) return text;
        String t = text;
        while (!t.isEmpty() && GpuUiRenderer.textWidth(t + "…", size) > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private void drawLayoutsPanel(UiDisplayList.Builder b, int sw, int sh, int mouseX, int mouseY) {
        layoutsPanel(b, sw, sh, mouseX, mouseY);
    }

    private void drawAlignPanel(UiDisplayList.Builder b, int sw, int sh, int mouseX, int mouseY) {
        alignPanel(b, sw, sh, mouseX, mouseY);
    }

    private void drawButton(UiDisplayList.Builder b, float x, float y, float w, float h, String label,
                            boolean hot, int tone, float s) {
        b.pill(x, y, w, h, withAlpha(tone, hot ? 0x48 : 0x22));
        b.border(x, y, w, h, h * .5f, Math.max(.6f, s), withAlpha(tone, hot ? 0xB0 : 0x70));
        float tw = GpuUiRenderer.textWidth(label, 7.5f * s);
        b.text(x + (w - tw) * .5f, y + (h - 7.5f * s) * .5f - .5f, 7.5f * s, primary, label,
                UiDrawCommand.TextWeight.SEMIBOLD);
    }

    /** Key-chip legend of every editor control; drops to the essentials on narrow screens. */
    private void drawFooter(UiDisplayList.Builder b, int sw, int sh, int count) {
        float s = chromeScale(sw, sh);
        float keySize = 6.8f * s, labelSize = 7.6f * s;
        int items = LEGEND_KEYS.length;
        float total = legendWidth(items, keySize, labelSize, s);
        if (total + 40f * s > sw - 24f * s) {
            items = COMPACT_LEGEND;
            total = legendWidth(items, keySize, labelSize, s);
        }
        float w = Math.min(sw - 24f * s, total + 40f * s);
        float x = (sw - w) * .5f;
        float h = 26f * s, y = sh - h - 12f * s + chromeHide * (h + 24f * s);
        b.pill(x, y, w, h, panelTop);
        b.border(x, y, w, h, h * .5f, Math.max(.7f, s), border);
        float cx = x + 13f * s;
        for (int i = 0; i < items; i++) {
            float chipW = GpuUiRenderer.textWidth(LEGEND_KEYS[i], keySize) + 10f * s, chipH = 14f * s;
            float chipY = y + (h - chipH) * .5f;
            b.rect(cx, chipY, chipW, chipH, 3f * s, withAlpha(accent, 0x22));
            b.border(cx, chipY, chipW, chipH, 3f * s, Math.max(.6f, s * .8f), withAlpha(accent, 0x66));
            b.text(cx + 5f * s, chipY + (chipH - keySize) * .5f - .3f, keySize, primary, LEGEND_KEYS[i],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            cx += chipW + 4f * s;
            b.text(cx, y + (h - labelSize) * .5f - .3f, labelSize, secondary, LEGEND_LABELS[i]);
            cx += GpuUiRenderer.textWidth(LEGEND_LABELS[i], labelSize) + 11f * s;
        }
        String total2 = Integer.toString(count);
        b.text(x + w - 13f * s - GpuUiRenderer.textWidth(total2, 8f * s), y + 8f * s, 8f * s, muted, total2,
                UiDrawCommand.TextWeight.SEMIBOLD);
    }

    private float legendWidth(int items, float keySize, float labelSize, float s) {
        float total = 0;
        for (int i = 0; i < items; i++) {
            total += GpuUiRenderer.textWidth(LEGEND_KEYS[i], keySize) + 10f * s + 4f * s
                    + GpuUiRenderer.textWidth(LEGEND_LABELS[i], labelSize) + 11f * s;
        }
        return total;
    }

    private void drawElementLabel(UiDisplayList.Builder b, HUDElement element,
                                  int mouseX, int mouseY, int sw, int sh) {
        float s = chromeScale(sw, sh);
        int percent = (int) Math.round(element.getScale() * 100);
        String label = element.getName() + (element.isEnabled() ? "  ·  ON" : "  ·  OFF")
                + (percent != 100 ? "  ·  " + percent + "%" : "");
        float w = GpuUiRenderer.textWidth(label, 8.5f * s) + 18f * s;
        float x = Math.min(sw - w - 8f * s, mouseX + 13f * s);
        float y = Math.min(sh - 32f * s, mouseY + 10f * s);
        b.pill(x, y, w, 21f * s, panelTop);
        b.border(x, y, w, 21f * s, 10.5f * s, Math.max(.7f, s), border);
        b.text(x + 9f * s, y + 6f * s, 8.5f * s, element.isEnabled() ? primary : secondary, label);
    }

    private void drawScaleFlash(UiDisplayList.Builder b, int sw, int sh) {
        if (scaleFlash == null || System.currentTimeMillis() > scaleFlashUntil) return;
        float s = chromeScale(sw, sh);
        String text = (int) Math.round(scaleFlash.getScale() * 100) + "%";
        float tw = GpuUiRenderer.textWidth(text, 8.5f * s);
        float w = tw + 16f * s, h = 18f * s;
        float ex = GpuHudRenderer.editorX(scaleFlash, sw, sh), ey = GpuHudRenderer.editorY(scaleFlash, sw, sh);
        float ew = GpuHudRenderer.editorWidth(scaleFlash, sw, sh);
        float x = Math.max(4f * s, Math.min(sw - w - 4f * s, ex + (ew - w) * .5f));
        float y = ey - h - 6f * s;
        if (y < 4f * s) y = ey + GpuHudRenderer.editorHeight(scaleFlash, sw, sh) + 6f * s;
        b.pill(x, y, w, h, withAlpha(accent, 0xD8));
        b.text(x + 8f * s, y + (h - 8.5f * s) * .5f - .3f, 8.5f * s, contrastOn(accent), text,
                UiDrawCommand.TextWeight.SEMIBOLD);
    }

    // ---------------------------------------------------------------- settings popup

    /** Header button that opens the HUD-wide colours (Follow GUI Theme / Custom) and glass settings. */
    private static final String COLOURS_LABEL = "COLOURS";

    /**
     * The rows the popup shows: settings whose visibility rule hides them (colour pickers while a widget
     * follows the HUD colours, Custom-only colours while following the theme) are left out.
     */
    private static List<Setting> shownSettings(HUDElement element) {
        List<Setting> all = element.getSettings();
        if (all == null || all.isEmpty()) return all;
        boolean allVisible = true;
        for (Setting setting : all) {
            if (!setting.isVisible()) {
                allVisible = false;
                break;
            }
        }
        if (allVisible) return all;
        List<Setting> shown = new java.util.ArrayList<>(all.size());
        for (Setting setting : all) if (setting.isVisible()) shown.add(setting);
        return shown;
    }

    private void drawSettingsPopup(UiDisplayList.Builder b, HUDElement element, int sw, int sh,
                                   int mouseX, int mouseY) {
        List<Setting> settings = shownSettings(element);
        if (settings == null || settings.isEmpty()) return;
        float[] bounds = popupBounds(element, settings, sw, sh);
        float x = bounds[0], y = bounds[1], w = bounds[2], h = bounds[3];
        float s = chromeScale(sw, sh);
        settingsScrollOffset = Math.max(0, Math.min(settingsScrollOffset,
                maxSettingsScrollOffset(settings, h, s)));
        b.shadow(x, y, w, h, 14f * s, 12f * s, s, 0x72000000);
        b.gradient(x, y, w, h, 14f * s, panelTop, panelBottom, panelBottom, panelTop);
        b.border(x, y, w, h, 14f * s, Math.max(.7f, s), border);
        String title = element instanceof HudStyleElement ? "HUD Colours" : element.getName();
        b.text(x + 14f * s, y + 12f * s, 9.5f * s, primary, title, UiDrawCommand.TextWeight.SEMIBOLD);

        float[] reset = popupResetBounds(x, y, w, s);
        boolean resetHot = inRect(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3]);
        boolean modified = element.isModifiedFromDefaults();
        int resetTone = modified ? accent : muted;
        b.pill(reset[0], reset[1], reset[2], reset[3], withAlpha(resetTone, resetHot ? 0x48 : 0x1C));
        b.border(reset[0], reset[1], reset[2], reset[3], reset[3] * .5f, Math.max(.6f, s * .8f),
                withAlpha(resetTone, resetHot ? 0xB0 : 0x60));
        b.text(reset[0] + 8f * s, reset[1] + (reset[3] - 7f * s) * .5f - .3f, 7f * s,
                modified ? primary : secondary, "RESET", UiDrawCommand.TextWeight.SEMIBOLD);

        int visibleEnd = visibleSettingsEnd(settings, h, s);
        if (settings.size() > visibleEnd - settingsScrollOffset) {
            String page = (settingsScrollOffset + 1) + "-" + visibleEnd + "/" + settings.size();
            float pageWidth = GpuUiRenderer.textWidth(page, 7f * s);
            b.text(reset[0] - pageWidth - 8f * s, y + 13f * s, 7f * s, secondary, page,
                    UiDrawCommand.TextWeight.SEMIBOLD);
        }
        float cy = y + 32f * s;
        for (int index = settingsScrollOffset; index < visibleEnd; index++) {
            Setting setting = settings.get(index);
            float rowHeight = settingRowHeight(setting) * s;
            if (mouseX >= x && mouseX <= x + w && mouseY >= cy && mouseY <= cy + Math.min(rowHeight, ROW * s)
                    && setting.getDescription() != null && !setting.getDescription().isBlank()) {
                hoverDescription = setting.getDescription();
            }
            b.rect(x + 10f * s, cy, w - 20f * s, Math.max(.7f, s), 0, withAlpha(border, 0x30));
            b.text(x + 13f * s, cy + 8f * s, 9f * s, primary, setting.getName());
            if (setting instanceof BooleanSetting bool) {
                drawSwitch(b, x + w - 49f * s, cy + 6f * s, bool.isEnabled(), s);
            } else if (setting instanceof ModeSetting mode) {
                String value = mode.getCurrentMode();
                float bw = Math.max(50f * s, GpuUiRenderer.textWidth(value, 8f * s) + 18f * s);
                float bx = x + w - bw - 11f * s;
                b.pill(bx, cy + 4f * s, bw, 19f * s, withAlpha(accent, 0x28));
                b.text(bx + (bw - GpuUiRenderer.textWidth(value, 8f * s)) * .5f, cy + 9f * s,
                        8f * s, accent, value, UiDrawCommand.TextWeight.SEMIBOLD);
            } else if (setting instanceof StringSetting text) {
                float bx = x + w - 119f * s;
                float by = cy + 5f * s;
                float bw = 106f * s, bh = 23f * s;
                boolean editing = editingString == text;
                b.rect(bx, by, bw, bh, 3f * s, editing ? withAlpha(accent, 0x30) : withAlpha(muted, 0x30));
                b.border(bx, by, bw, bh, 3f * s, Math.max(.7f, s),
                        editing ? withAlpha(accent, 190) : withAlpha(muted, 0x80));
                String value = text.getValue().isBlank() ? "TYPE LABEL" : text.getValue();
                value = value.length() > 15 ? value.substring(0, 14) + "…" : value;
                if (editing && (System.currentTimeMillis() / 500L) % 2L == 0L) value += "_";
                b.text(bx + 7f * s, by + 7f * s, 8f * s,
                        editing ? primary : secondary, value, UiDrawCommand.TextWeight.SEMIBOLD);
            } else if (setting instanceof SliderSetting slider) {
                String value = setting == element.getScaleSetting()
                        ? Math.round(slider.getValue() * 100) + "%" : slider.getValueAsString();
                float trackX = x + 13f * s, trackY = cy + 25f * s, trackW = w - 26f * s;
                double ratio = slider.getMax() <= slider.getMin() ? 0
                        : (slider.getValue() - slider.getMin()) / (slider.getMax() - slider.getMin());
                b.text(x + w - 13f * s - GpuUiRenderer.textWidth(value, 8f * s), cy + 8f * s,
                        8f * s, secondary, value);
                b.rect(trackX, trackY, trackW, 3f * s, 1.5f * s, withAlpha(muted, 0x80));
                b.rect(trackX, trackY, trackW * (float) ratio, 3f * s, 1.5f * s, accent);
                b.circle(trackX + trackW * (float) ratio, trackY + 1.5f * s, 4f * s, accent);
            } else if (setting instanceof ColorSetting color) {
                drawColorRow(b, color, x, cy, w, s);
            }
            cy += rowHeight;
        }
    }

    private float[] popupResetBounds(float x, float y, float w, float s) {
        float rw = GpuUiRenderer.textWidth("RESET", 7f * s) + 16f * s, rh = 15f * s;
        return new float[]{x + w - rw - 11f * s, y + 9f * s, rw, rh};
    }

    private void drawDescription(UiDisplayList.Builder b, HUDElement element, int sw, int sh) {
        List<Setting> settings = shownSettings(element);
        if (settings == null || settings.isEmpty()) return;
        float s = chromeScale(sw, sh);
        float[] popup = popupBounds(element, settings, sw, sh);
        float size = 7.6f * s, pad = 8f * s, lineH = 10.5f * s;
        float maxW = popup[2] - pad * 2;
        // Greedy word wrap, at most three lines.
        String[] words = hoverDescription.split(" ");
        String[] lines = new String[3];
        int lineCount = 0;
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (GpuUiRenderer.textWidth(candidate, size) > maxW && !line.isEmpty()) {
                if (lineCount == 2) { line.append("…"); break; }
                lines[lineCount++] = line.toString();
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty() && lineCount < 3) lines[lineCount++] = line.toString();
        float h = pad * 2 + lineCount * lineH - (lineH - size);
        float x = popup[0], y = popup[1] + popup[3] + 6f * s;
        if (y + h > sh - 6f * s) y = popup[1] - h - 6f * s;
        b.rect(x, y, popup[2], h, 8f * s, panelTop);
        b.border(x, y, popup[2], h, 8f * s, Math.max(.7f, s), border);
        for (int i = 0; i < lineCount; i++) {
            b.text(x + pad, y + pad + i * lineH, size, secondary, lines[i]);
        }
    }

    private void drawSwitch(UiDisplayList.Builder b, float x, float y, boolean enabled, float scale) {
        int fill = enabled ? accent : toggleOff;
        b.pill(x, y, 34f * scale, 15f * scale, fill);
        b.circle(enabled ? x + 26f * scale : x + 8f * scale, y + 7.5f * scale,
                5.2f * scale, primary);
    }

    // ---------------------------------------------------------------- colour picker

    private static int argbOf(Color color) {
        return color == null ? 0xFFFFFFFF : color.getRGB();
    }

    private static String hex(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        return alpha == 0xFF
                ? String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                : String.format(Locale.ROOT, "#%08X", argb);
    }

    private void drawColorRow(UiDisplayList.Builder b, ColorSetting color, float x, float cy, float w, float s) {
        int argb = argbOf(color.getValue());
        boolean expanded = expandedColor == color;
        float chipX = x + w - 36f * s, chipY = cy + 6f * s, chipW = 22f * s, chipH = 14f * s;
        drawChecker(b, chipX, chipY, chipW, chipH, 7f * s, 3.5f * s);
        b.pill(chipX, chipY, chipW, chipH, argb);
        b.border(chipX, chipY, chipW, chipH, 7f * s, Math.max(.7f, s), expanded ? withAlpha(accent, 0xD0) : border);
        String code = hex(argb);
        b.text(chipX - 6f * s - GpuUiRenderer.textWidth(code, 7.5f * s), cy + 9f * s, 7.5f * s,
                expanded ? accent : secondary, code, UiDrawCommand.TextWeight.SEMIBOLD);
        if (!expanded) return;

        layoutPicker(x, cy, w, s);
        // Saturation/brightness square: white -> hue horizontally, then a transparent -> black wash.
        int hueColor = 0xFF000000 | (Color.HSBtoRGB(pickHue, 1f, 1f) & 0xFFFFFF);
        b.gradient(svX, svY, svW, svH, 4f * s, 0xFFFFFFFF, hueColor, hueColor, 0xFFFFFFFF);
        b.gradient(svX, svY, svW, svH, 4f * s, 0x00000000, 0x00000000, 0xFF000000, 0xFF000000);
        b.border(svX, svY, svW, svH, 4f * s, Math.max(.6f, s * .8f), withAlpha(border, 0x80));
        float mx = svX + pickSat * svW, my = svY + (1f - pickBri) * svH;
        b.circle(mx, my, 4.6f * s, 0xFF000000);
        b.circle(mx, my, 3.8f * s, 0xFFFFFFFF);
        b.circle(mx, my, 2.8f * s, 0xFF000000 | (argb & 0xFFFFFF));

        // Vertical hue bar.
        float segment = hueH / (HUE_STOPS.length - 1);
        for (int i = 0; i < HUE_STOPS.length - 1; i++) {
            float sy = hueY + i * segment;
            b.gradient(hueX, sy, hueW, segment + .5f, 0, HUE_STOPS[i], HUE_STOPS[i], HUE_STOPS[i + 1], HUE_STOPS[i + 1]);
        }
        b.border(hueX, hueY, hueW, hueH, 2f * s, Math.max(.6f, s * .8f), withAlpha(border, 0x80));
        float hy = hueY + pickHue * hueH;
        b.rect(hueX - 2f * s, hy - 1.5f * s, hueW + 4f * s, 3f * s, 1.5f * s, 0xFFFFFFFF);
        b.border(hueX - 2f * s, hy - 1.5f * s, hueW + 4f * s, 3f * s, 1.5f * s, Math.max(.5f, s * .6f), 0xC0000000);

        // Alpha bar over a checkerboard.
        int alpha = (argb >>> 24) & 0xFF;
        drawChecker(b, alphaX, alphaY, alphaW, alphaH, alphaH * .5f, alphaH * .5f);
        b.gradient(alphaX, alphaY, alphaW, alphaH, alphaH * .5f, argb & 0x00FFFFFF, 0xFF000000 | argb,
                0xFF000000 | argb, argb & 0x00FFFFFF);
        b.border(alphaX, alphaY, alphaW, alphaH, alphaH * .5f, Math.max(.6f, s * .8f), withAlpha(border, 0x80));
        float ax = alphaX + alpha / 255f * alphaW;
        b.rect(ax - 1.5f * s, alphaY - 2f * s, 3f * s, alphaH + 4f * s, 1.5f * s, 0xFFFFFFFF);
        b.border(ax - 1.5f * s, alphaY - 2f * s, 3f * s, alphaH + 4f * s, 1.5f * s, Math.max(.5f, s * .6f), 0xC0000000);

        // Hex field (click to type, Ctrl+V pastes, Enter applies) + alpha readout.
        b.rect(hexX, hexY, hexW, hexH, 3f * s, editingHex ? withAlpha(accent, 0x30) : withAlpha(muted, 0x30));
        b.border(hexX, hexY, hexW, hexH, 3f * s, Math.max(.7f, s),
                editingHex ? withAlpha(accent, 190) : withAlpha(muted, 0x80));
        String field = editingHex ? hexBuffer + ((System.currentTimeMillis() / 500L) % 2L == 0L ? "_" : "") : code;
        b.text(hexX + 6f * s, hexY + (hexH - 8f * s) * .5f - .3f, 8f * s, editingHex ? primary : secondary, field,
                UiDrawCommand.TextWeight.SEMIBOLD);
        String alphaText = "A " + Math.round(alpha / 2.55f) + "%";
        b.text(hexX + hexW + 8f * s, hexY + (hexH - 7.5f * s) * .5f - .3f, 7.5f * s, muted, alphaText,
                UiDrawCommand.TextWeight.SEMIBOLD);
        String hint = editingHex ? "ENTER" : "CTRL+V";
        b.text(svX + svW + hueW + 6f * s - GpuUiRenderer.textWidth(hint, 6.8f * s) - 0f,
                hexY + (hexH - 6.8f * s) * .5f - .3f, 6.8f * s, muted, hint, UiDrawCommand.TextWeight.SEMIBOLD);

        // Preset swatches: theme accent first.
        for (int i = 0; i <= PRESETS.length; i++) {
            int swatch = i == 0 ? 0xFF000000 | accent : PRESETS[i - 1];
            float sx = swX + i * (swSize + swGap);
            boolean current = (swatch & 0xFFFFFF) == (argb & 0xFFFFFF);
            b.rect(sx, swY, swSize, swSize, swSize * .5f, swatch);
            b.border(sx, swY, swSize, swSize, swSize * .5f, current ? Math.max(1f, 1.4f * s) : Math.max(.6f, s * .7f),
                    current ? withAlpha(primary, 0xF0) : withAlpha(border, 0x90));
            if (i == 0) b.circle(sx + swSize * .5f, swY + swSize * .5f, 1.6f * s, contrastOn(swatch));
        }
    }

    private void drawChecker(UiDisplayList.Builder b, float x, float y, float w, float h, float radius, float cell) {
        b.rect(x, y, w, h, radius, 0xFF9A9A9A);
        int cols = (int) Math.ceil(w / cell), rows = (int) Math.ceil(h / cell);
        for (int r = 0; r < rows; r++) {
            for (int c = (r & 1); c < cols; c += 2) {
                float cx = x + c * cell, cy = y + r * cell;
                b.rect(cx, cy, Math.min(cell, x + w - cx), Math.min(cell, y + h - cy), 0, 0xFFD8D8D8);
            }
        }
    }

    /** Picker geometry for an expanded colour row whose top is {@code cy} (screen px). */
    private void layoutPicker(float x, float cy, float w, float s) {
        float px = x + 13f * s, py = cy + ROW * s, pw = w - 26f * s;
        hueW = 11f * s;
        svX = px;
        svY = py;
        svW = pw - hueW - 7f * s;
        svH = SV_HEIGHT * s;
        hueX = svX + svW + 7f * s;
        hueY = py;
        hueH = svH;
        alphaX = px;
        alphaY = py + svH + 8f * s;
        alphaW = pw;
        alphaH = 9f * s;
        hexX = px;
        hexY = alphaY + alphaH + 8f * s;
        hexW = 78f * s;
        hexH = 18f * s;
        int count = PRESETS.length + 1;
        swSize = 14f * s;
        swGap = Math.max(2f * s, (pw - count * swSize) / (count - 1));
        swX = px;
        swY = hexY + hexH + 6f * s;
    }

    private void expandColor(ColorSetting color) {
        finishHexEdit(false);
        if (expandedColor == color) {
            expandedColor = null;
            return;
        }
        expandedColor = color;
        syncPickerFrom(argbOf(color.getValue()), true);
    }

    private void syncPickerFrom(int argb, boolean resetHue) {
        float[] hsb = Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null);
        // Greys have no hue: keep the bar where the player left it.
        if (resetHue || hsb[1] > 0.001f) pickHue = hsb[0];
        pickSat = hsb[1];
        pickBri = hsb[2];
    }

    private boolean handlePickerClick(double mx, double my) {
        if (inRect(mx, my, svX, svY, svW, svH)) {
            pickDrag = PickPart.SV;
        } else if (inRect(mx, my, hueX - 2, hueY, hueW + 4, hueH)) {
            pickDrag = PickPart.HUE;
        } else if (inRect(mx, my, alphaX, alphaY - 3, alphaW, alphaH + 6)) {
            pickDrag = PickPart.ALPHA;
        } else if (inRect(mx, my, hexX, hexY, hexW, hexH)) {
            beginHexEdit();
            return true;
        } else {
            finishHexEdit(true);
            for (int i = 0; i <= PRESETS.length; i++) {
                float sx = swX + i * (swSize + swGap);
                if (inRect(mx, my, sx, swY, swSize, swSize)) {
                    int swatch = i == 0 ? 0xFF000000 | accent : PRESETS[i - 1];
                    int alpha = argbOf(expandedColor.getValue()) & 0xFF000000;
                    applyColor((swatch & 0xFFFFFF) | alpha);
                    syncPickerFrom(swatch, true);
                    return true;
                }
            }
            return true;
        }
        finishHexEdit(true);
        applyPick(mx, my);
        return true;
    }

    private void applyPick(double mx, double my) {
        if (expandedColor == null) return;
        int current = argbOf(expandedColor.getValue());
        int alpha = (current >>> 24) & 0xFF;
        switch (pickDrag) {
            case SV -> {
                pickSat = clamp01((float) ((mx - svX) / Math.max(1f, svW)));
                pickBri = 1f - clamp01((float) ((my - svY) / Math.max(1f, svH)));
            }
            case HUE -> pickHue = Math.min(0.9999f, clamp01((float) ((my - hueY) / Math.max(1f, hueH))));
            case ALPHA -> alpha = Math.round(clamp01((float) ((mx - alphaX) / Math.max(1f, alphaW))) * 255f);
            default -> { return; }
        }
        int rgb = pickDrag == PickPart.ALPHA ? current & 0xFFFFFF : Color.HSBtoRGB(pickHue, pickSat, pickBri) & 0xFFFFFF;
        applyColor((alpha << 24) | rgb);
    }

    private void applyColor(int argb) {
        if (expandedColor == null) return;
        if (argbOf(expandedColor.getValue()) != argb) expandedColor.setValue(new Color(argb, true));
    }

    private void beginHexEdit() {
        finishStringEdit();
        editingHex = true;
        hexBuffer.setLength(0);
        hexBuffer.append(hex(argbOf(expandedColor == null ? null : expandedColor.getValue())));
    }

    private void finishHexEdit(boolean apply) {
        if (!editingHex) return;
        editingHex = false;
        if (!apply || expandedColor == null) return;
        Integer parsed = parseHex(hexBuffer.toString(), argbOf(expandedColor.getValue()));
        if (parsed != null) {
            applyColor(parsed);
            syncPickerFrom(parsed, false);
        }
    }

    /** #RGB, #RRGGBB (keeps the current alpha) or #AARRGGBB, with or without '#'. */
    private static Integer parseHex(String raw, int currentArgb) {
        if (raw == null) return null;
        String h = raw.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        try {
            if (h.length() == 3) {
                int r = Integer.parseInt(h.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(h.substring(1, 2), 16) * 17;
                int bl = Integer.parseInt(h.substring(2, 3), 16) * 17;
                return (currentArgb & 0xFF000000) | (r << 16) | (g << 8) | bl;
            }
            if (h.length() == 6) return (currentArgb & 0xFF000000) | (int) (Long.parseLong(h, 16) & 0xFFFFFF);
            if (h.length() == 8) return (int) (Long.parseLong(h, 16) & 0xFFFFFFFFL);
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static boolean isHexChar(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private void pasteHex() {
        if (client == null || client.keyboard == null) return;
        String clip = client.keyboard.getClipboard();
        if (clip == null) return;
        hexBuffer.setLength(0);
        hexBuffer.append('#');
        String h = clip.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        for (int i = 0; i < h.length() && hexBuffer.length() < 9; i++) {
            char c = h.charAt(i);
            if (isHexChar(c)) hexBuffer.append(Character.toUpperCase(c));
        }
    }

    // ---------------------------------------------------------------- layout helpers

    private float[] popupBounds(HUDElement element, List<Setting> settings, int sw, int sh) {
        float s = chromeScale(sw, sh);
        float contentHeight = 38f * s;
        for (Setting setting : settings) contentHeight += settingRowHeight(setting) * s;
        float h = Math.min(contentHeight, Math.max(80f * s, sh - 20f * s));
        float w = 238f * s;
        if (element instanceof HudStyleElement) {
            // The style block has no widget to sit next to: hang it under the header's Style button.
            float x = Math.max(10f * s, Math.min(sw - w - 10f * s, styleX + styleW * .5f - w * .5f));
            float top = 12f * s + 30f * s + 8f * s;
            float y = Math.max(10f * s, Math.min(sh - h - 10f * s, top));
            return new float[]{x, y, w, h};
        }
        float ex = GpuHudRenderer.editorX(element, sw, sh);
        float ey = GpuHudRenderer.editorY(element, sw, sh);
        float x = ex + GpuHudRenderer.editorWidth(element, sw, sh) + 10f * s;
        if (x + w > sw - 10f * s) x = ex - w - 10f * s;
        x = Math.max(10f * s, Math.min(sw - w - 10f * s, x));
        float y = Math.max(10f * s, Math.min(sh - h - 10f * s, ey));
        return new float[]{x, y, w, h};
    }

    private int visibleSettingsEnd(List<Setting> settings, float popupHeight, float scale) {
        float available = Math.max(0f, popupHeight - 38f * scale);
        float used = 0f;
        int end = settingsScrollOffset;
        while (end < settings.size()) {
            float row = settingRowHeight(settings.get(end)) * scale;
            if (used + row > available + 0.01f && end > settingsScrollOffset) break;
            used += row;
            end++;
        }
        return end;
    }

    private int maxSettingsScrollOffset(List<Setting> settings, float popupHeight, float scale) {
        float available = Math.max(0f, popupHeight - 38f * scale);
        float used = 0f;
        int first = settings.size();
        while (first > 0) {
            float row = settingRowHeight(settings.get(first - 1)) * scale;
            if (used + row > available + 0.01f && first < settings.size()) break;
            used += row;
            first--;
        }
        return first;
    }

    private float settingRowHeight(Setting setting) {
        if (setting instanceof SliderSetting) return 39f;
        if (setting instanceof StringSetting) return 38f;
        if (setting instanceof ColorSetting && setting == expandedColor) return ROW + PICKER_HEIGHT;
        return ROW;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        int sw = this.width, sh = this.height;
        layoutHeader(sw, sh);
        boolean chromeVisible = chromeHide <= .01f;
        if (button == 0 && chromeVisible && inRect(mx, my, doneX, btnY, doneW, btnH)) {
            close();
            return true;
        }
        if (button == 0 && chromeVisible && inRect(mx, my, styleX, btnY, styleW, btnH)) {
            HUDElement style = HUDManager.getInstance().getStyle();
            closePanels();
            closePopup();
            if (settingsElement != style) {
                settingsElement = style;
                settingsScrollOffset = 0;
            } else {
                settingsElement = null;
            }
            return true;
        }
        if (button == 0 && chromeVisible && inRect(mx, my, resetX, btnY, resetW, btnH)) {
            if (resetArmed()) {
                resetArmedUntil = 0L;
                closePopup();
                HUDManager.getInstance().resetLayout();
            } else {
                resetArmedUntil = System.currentTimeMillis() + RESET_ARM_MS;
            }
            return true;
        }
        if (button == 0 && chromeVisible && inRect(mx, my, layoutsX, btnY, layoutsW, btnH)) {
            boolean open = !layoutsOpen;
            closePanels();
            closePopup();
            settingsElement = null;
            layoutsOpen = open;
            return true;
        }
        if (button == 0 && chromeVisible && inRect(mx, my, alignX, btnY, alignW, btnH)) {
            boolean open = !alignOpen;
            closePanels();
            closePopup();
            settingsElement = null;
            alignOpen = open;
            return true;
        }
        if (layoutsOpen) {
            Runnable action = chromeVisible ? layoutsPanel(null, sw, sh, mx, my) : null;
            if (action != null) {
                if (button == 0) {
                    // Any click in the panel ends name editing; clicking the field (re)starts it.
                    editingLayoutName = false;
                    action.run();
                }
                return true;
            }
            // A click outside closes the panel and is not passed on (no accidental drag).
            closePanels();
            return true;
        }
        if (alignOpen && chromeVisible) {
            Runnable action = alignPanel(null, sw, sh, mx, my);
            if (action != null) {
                if (button == 0) action.run();
                return true;
            }
        }
        if (settingsElement != null && handleSettingsClick(settingsElement, mx, my, button, sw, sh)) return true;
        if (settingsElement != null) {
            closePopup();
            settingsElement = null;
            settingsScrollOffset = 0;
        }

        List<HUDElement> elements = HUDManager.getInstance().getElements();
        HUDElement hit = elementAt(elements, mx, my, sw, sh);
        if (hit == null) {
            selected = null;
            multi.clear();
            alignOpen = false;
            return super.mouseClicked(click, doubleClick);
        }
        // Shift+click (or any click while the Align panel is open, for touch screens) adds/removes a widget
        // from the multi-selection instead of dragging it. TargetHUD sits on its own anchor and is never aligned.
        if (button == 0 && (alignOpen || shiftDown()) && !(hit instanceof TargetHudElement)) {
            if (multi.isEmpty() && selected != null && selected != hit
                    && !(selected instanceof TargetHudElement) && HUDManager.isWidget(selected)) {
                multi.add(selected);
            }
            if (!multi.remove(hit)) multi.add(hit);
            selected = multi.contains(hit) ? hit : (multi.isEmpty() ? null : multi.iterator().next());
            return true;
        }
        if (button == 0) multi.clear();
        selected = hit;
        if (button == 0) {
            unanchor(hit, sw, sh);
            dragging = hit;
            float scale = GpuHudRenderer.viewportScale(sw, sh);
            hit.startDragging(mx / scale, my / scale);
            return true;
        }
        if (button == 1) {
            if (shiftDown()) {
                closePopup();
                settingsElement = hit;
                settingsScrollOffset = 0;
            } else {
                hit.setEnabled(!hit.isEnabled());
            }
            return true;
        }
        return true;
    }

    /** A right-anchored widget (negative X) becomes absolutely positioned where it currently is. */
    private void unanchor(HUDElement element, int sw, int sh) {
        if (element.getX() < 0 && !(element instanceof TargetHudElement)) {
            float scale = GpuHudRenderer.viewportScale(sw, sh);
            element.setX(GpuHudRenderer.editorX(element, sw, sh) / scale);
            element.setY(GpuHudRenderer.editorY(element, sw, sh) / scale);
        }
    }

    private void closePopup() {
        finishStringEdit();
        finishHexEdit(true);
        expandedColor = null;
        pickDrag = PickPart.NONE;
        draggingSlider = null;
    }

    private boolean handleSettingsClick(HUDElement element, double mx, double my, int button, int sw, int sh) {
        List<Setting> settings = shownSettings(element);
        if (settings == null || settings.isEmpty()) return false;
        float[] popup = popupBounds(element, settings, sw, sh);
        if (mx < popup[0] || mx > popup[0] + popup[2] || my < popup[1] || my > popup[1] + popup[3]) return false;
        if (button != 0) return true;
        float scale = chromeScale(sw, sh);
        float[] reset = popupResetBounds(popup[0], popup[1], popup[2], scale);
        if (inRect(mx, my, reset[0], reset[1], reset[2], reset[3])) {
            closePopup();
            element.resetToDefaults();
            return true;
        }
        settingsScrollOffset = Math.max(0, Math.min(settingsScrollOffset,
                maxSettingsScrollOffset(settings, popup[3], scale)));
        float cy = popup[1] + 32f * scale;
        int visibleEnd = visibleSettingsEnd(settings, popup[3], scale);
        for (int index = settingsScrollOffset; index < visibleEnd; index++) {
            Setting setting = settings.get(index);
            float rowHeight = settingRowHeight(setting) * scale;
            if (my >= cy && my <= cy + rowHeight) {
                if (setting instanceof ColorSetting color) {
                    if (my <= cy + ROW * scale) {
                        expandColor(color);
                    } else if (expandedColor == color) {
                        layoutPicker(popup[0], cy, popup[2], scale);
                        handlePickerClick(mx, my);
                    }
                    return true;
                }
                finishHexEdit(true);
                if (setting instanceof BooleanSetting bool) bool.toggle();
                else if (setting instanceof ModeSetting mode) mode.cycle();
                else if (setting instanceof StringSetting text) beginStringEdit(text);
                else if (setting instanceof SliderSetting slider) {
                    draggingSlider = slider;
                    sliderMinX = popup[0] + 13f * scale;
                    sliderMaxX = popup[0] + popup[2] - 13f * scale;
                    updateSlider(mx);
                }
                return true;
            }
            cy += rowHeight;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return false;
        if (layoutsOpen && layoutsPanel(null, this.width, this.height, mouseX, mouseY) != null) {
            layoutsScroll += verticalAmount < 0 ? 1 : -1; // clamped to the list when the panel lays out
            if (layoutsScroll < 0) layoutsScroll = 0;
            return true;
        }
        if (settingsElement != null) {
            List<Setting> settings = shownSettings(settingsElement);
            if (settings != null && !settings.isEmpty()) {
                float scale = chromeScale(this.width, this.height);
                float[] popup = popupBounds(settingsElement, settings, this.width, this.height);
                if (mouseX >= popup[0] && mouseX <= popup[0] + popup[2]
                        && mouseY >= popup[1] && mouseY <= popup[1] + popup[3]) {
                    int maximum = maxSettingsScrollOffset(settings, popup[3], scale);
                    int step = verticalAmount < 0 ? 1 : -1;
                    settingsScrollOffset = Math.max(0, Math.min(maximum, settingsScrollOffset + step));
                    return true;
                }
            }
        }
        // Scroll over a widget scales it in 5% steps.
        HUDElement hovered = elementAt(HUDManager.getInstance().getElements(), mouseX, mouseY, this.width, this.height);
        if (hovered == null || dragging != null) return false;
        double next = Math.round((hovered.getScale() + (verticalAmount > 0 ? SCALE_STEP : -SCALE_STEP)) / SCALE_STEP)
                * SCALE_STEP;
        hovered.setScale(next);
        clampToScreen(hovered);
        selected = hovered;
        scaleFlash = hovered;
        scaleFlashUntil = System.currentTimeMillis() + SCALE_FLASH_MS;
        return true;
    }

    private void beginStringEdit(StringSetting setting) {
        finishStringEdit();
        editingString = setting;
        setting.setEditing(true);
    }

    private void finishStringEdit() {
        if (editingString != null) editingString.setEditing(false);
        editingString = null;
    }

    private void updateSlider(double mouseX) {
        if (draggingSlider == null) return;
        double range = Math.max(1, sliderMaxX - sliderMinX);
        double ratio = Math.max(0, Math.min(1, (mouseX - sliderMinX) / range));
        draggingSlider.setValue(draggingSlider.getMin() + ratio * (draggingSlider.getMax() - draggingSlider.getMin()));
        if (settingsElement != null && draggingSlider == settingsElement.getScaleSetting()) clampToScreen(settingsElement);
    }

    /** Keeps a widget fully on screen (layout units); TargetHUD's X/Y are offsets from its crosshair anchor. */
    private void clampToScreen(HUDElement element) {
        float scale = GpuHudRenderer.viewportScale(this.width, this.height);
        int layoutWidth = Math.round(this.width / scale), layoutHeight = Math.round(this.height / scale);
        if (element instanceof TargetHudElement target) {
            float actualX = target.getEditorX(layoutWidth);
            float actualY = target.getEditorY(layoutHeight);
            float maxX = Math.max(0, layoutWidth - target.getWidgetWidth() * (float) target.getScale());
            float maxY = Math.max(0, layoutHeight - target.getWidgetHeight() * (float) target.getScale());
            target.setX(target.getX() + Math.max(0, Math.min(maxX, actualX)) - actualX);
            target.setY(target.getY() + Math.max(0, Math.min(maxY, actualY)) - actualY);
        } else if (element.getX() >= 0) {
            float maxX = Math.max(0, layoutWidth - (float) element.getWidth() * (float) element.getScale());
            float maxY = Math.max(0, layoutHeight - (float) element.getHeight() * (float) element.getScale());
            element.setX(Math.max(0, Math.min(maxX, element.getX())));
            element.setY(Math.max(0, Math.min(maxY, element.getY())));
        }
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (pickDrag != PickPart.NONE) {
            applyPick(click.x(), click.y());
            return true;
        }
        if (draggingSlider != null) {
            updateSlider(click.x());
            return true;
        }
        if (dragging != null) {
            float scale = GpuHudRenderer.viewportScale(this.width, this.height);
            double layoutX = click.x() / scale, layoutY = click.y() / scale;
            dragging.mouseDragged(layoutX, layoutY);
            int layoutWidth = Math.round(this.width / scale), layoutHeight = Math.round(this.height / scale);
            clampToScreen(dragging);
            if (!(dragging instanceof TargetHudElement)) {
                snapDragged(dragging, layoutWidth, layoutHeight, scale);
                clampToScreen(dragging);
            }
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && dragging != null) {
            dragging.stopDragging();
            dragging = null;
            guideX = Float.NaN;
            guideY = Float.NaN;
        }
        if (click.button() == 0) {
            draggingSlider = null;
            pickDrag = PickPart.NONE;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        int mods = input.modifiers();
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (editingLayoutName) {
            if (key == GLFW.GLFW_KEY_ESCAPE) editingLayoutName = false;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) saveLayoutPreset();
            else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!layoutName.isEmpty()) layoutName.setLength(layoutName.length() - 1);
            } else if (ctrl && key == GLFW.GLFW_KEY_V && client != null && client.keyboard != null) {
                String clip = client.keyboard.getClipboard();
                if (clip != null) clip.codePoints().forEach(this::appendLayoutNameChar);
            } else if (ctrl && key == GLFW.GLFW_KEY_A) layoutName.setLength(0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && (layoutsOpen || alignOpen)) {
            closePanels();
            return true;
        }
        if (editingHex) {
            if (key == GLFW.GLFW_KEY_ESCAPE) finishHexEdit(false);
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) finishHexEdit(true);
            else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!hexBuffer.isEmpty()) hexBuffer.setLength(hexBuffer.length() - 1);
            } else if (ctrl && key == GLFW.GLFW_KEY_V) pasteHex();
            else if (ctrl && key == GLFW.GLFW_KEY_A) hexBuffer.setLength(0);
            else if (ctrl && key == GLFW.GLFW_KEY_C && client != null && client.keyboard != null) {
                client.keyboard.setClipboard(hexBuffer.toString());
            }
            return true;
        }
        if (editingString != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                finishStringEdit();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                String value = editingString.getValue();
                if (!value.isEmpty()) editingString.setValue(removeLastCodePoint(value));
                return true;
            }
        }
        if (expandedColor != null && ctrl && key == GLFW.GLFW_KEY_C && client != null && client.keyboard != null) {
            client.keyboard.setClipboard(hex(argbOf(expandedColor.getValue())));
            return true;
        }
        if (expandedColor != null && ctrl && key == GLFW.GLFW_KEY_V) {
            beginHexEdit();
            pasteHex();
            finishHexEdit(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (expandedColor != null) {
                expandedColor = null;
            } else if (settingsElement != null) {
                closePopup();
                settingsElement = null;
                settingsScrollOffset = 0;
            } else {
                close();
            }
            return true;
        }
        if (editingString == null && selected != null && dragging == null) {
            int step = shift ? 10 : 1;
            int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
            if (dx != 0 || dy != 0) {
                if (multi.size() > 1) {
                    for (HUDElement element : multi) nudge(element, dx, dy);
                } else {
                    nudge(selected, dx, dy);
                }
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private void closePanels() {
        layoutsOpen = false;
        alignOpen = false;
        editingLayoutName = false;
        armedDeleteLayout = null;
    }

    private void appendLayoutNameChar(int codepoint) {
        if (layoutName.length() >= LAYOUT_NAME_MAX) return;
        if (Character.isLetterOrDigit(codepoint) || codepoint == ' ' || codepoint == '-' || codepoint == '_'
                || codepoint == '.') {
            if (codepoint == ' ' && layoutName.isEmpty()) return;
            layoutName.appendCodePoint(codepoint);
        }
    }

    /** Moves a widget by whole layout pixels (arrow keys), keeping it on screen. */
    private void nudge(HUDElement element, int dx, int dy) {
        unanchor(element, this.width, this.height);
        element.setX(element.getX() + dx);
        element.setY(element.getY() + dy);
        clampToScreen(element);
    }

    @Override
    public boolean charTyped(CharInput input) {
        int codepoint = input.codepoint();
        if (editingLayoutName) {
            appendLayoutNameChar(codepoint);
            return true;
        }
        if (editingHex) {
            if (codepoint == '#' && hexBuffer.isEmpty()) hexBuffer.append('#');
            else if (isHexChar(codepoint) && hexBuffer.length() < 9) hexBuffer.append(Character.toUpperCase((char) codepoint));
            return true;
        }
        if (editingString == null) return super.charTyped(input);
        if (!Character.isValidCodePoint(codepoint) || Character.isISOControl(codepoint)) return true;
        String value = editingString.getValue();
        if (value.codePointCount(0, value.length()) >= 32) return true;
        editingString.setValue(value + new String(Character.toChars(codepoint)));
        return true;
    }

    private String removeLastCodePoint(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }

    @Override
    public void close() {
        closePopup();
        if (client != null) client.setScreen(parent);
        else saveLayout();
    }

    @Override
    public void removed() {
        closePopup();
        saveLayout();
    }

    private void saveLayout() {
        if (saved) return;
        saved = true;
        try {
            ConfigManager manager = ConfigManager.getInstance();
            String active = manager.getLastLoadedConfigName();
            if (active == null || active.isBlank()) active = "default";
            manager.save(active);
            String json = manager.exportCurrentConfigJsonForCloud();
            if (json != null) CloudConfigSync.saveIfChangedNow(json);
        } catch (Throwable ignored) {
            // The editor remains usable if config synchronization is unavailable.
        }
    }

    @Override public boolean shouldPause() { return false; }

    private HUDElement elementAt(List<HUDElement> elements, double mx, double my, int sw, int sh) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            HUDElement element = elements.get(i);
            if (!HUDManager.isWidget(element)) continue;
            float x = GpuHudRenderer.editorX(element, sw, sh);
            float y = GpuHudRenderer.editorY(element, sw, sh);
            float w = GpuHudRenderer.editorWidth(element, sw, sh);
            float h = GpuHudRenderer.editorHeight(element, sw, sh);
            if (w <= 0 || h <= 0) continue;
            if (mx >= x && mx <= x + w && my >= y && my <= y + h) return element;
        }
        return null;
    }

    private static boolean inRect(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int contrastOn(int fill) {
        int r = (fill >> 16) & 0xFF, g = (fill >> 8) & 0xFF, b = fill & 0xFF;
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f > 0.62f ? 0xFF101215 : 0xFFFFFFFF;
    }

    private boolean shiftDown() {
        if (client == null) return false;
        // Touch launchers have no physical Shift and some GLFW shims do not implement glfwGetKey.
        if (interstellarTikiTiks.apexx.client.util.MobilePlatform.isMobile()) return false;
        try {
            long window = client.getWindow().getHandle();
            return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
