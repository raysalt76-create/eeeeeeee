package interstellarTikiTiks.apexx.client.gui.mobile;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.config.CloudConfigSync;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.ClassicSettingLayout;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.ResponsiveLayout;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Touch layout of the Apexx menu for Android launchers. It is a separate screen from the desktop GPU
 * menu, not a scaled copy: every control is a large target, nothing needs hover or right-click, and it
 * is drawn with plain {@link DrawContext} fills and Minecraft's text renderer only (see {@link MobileUi}).
 *
 * <p>Modules and settings come from the same {@link Module} objects and
 * {@link ClassicSettingLayout} rows the desktop menu uses, so values, visibility rules and saved configs
 * are identical. Launchers deliver touches as a mouse: a press, drags while held, and a release. A tap
 * is therefore decided on release, and a drag that starts on a row scrolls instead of toggling it, so a
 * swipe can never flip a module by accident.
 */
public final class MobileClickGuiScreen extends Screen {
    private static final int ACCENT_FALLBACK = 0xFFB98CFF;
    private static final int TOUCH_SLOP = 6;

    private enum Tab {
        COMBAT("Combat", Category.COMBAT), MOVEMENT("Movement", Category.MOVEMENT),
        AUTOMATIONS("Automations", Category.AUTOMATIONS),
        RENDER("Render", Category.RENDER), PLAYER("Player", Category.PLAYER),
        BLATANT("Blatant", Category.BLATANT), SMP("SMP", Category.SMP), BEDWARS("Bedwars", Category.BEDWARS),
        CLIENT("Client", Category.CLIENT), CONFIGS("Configs", null), COSMETICS("Cosmetics", null);

        final String label;
        final Category category;

        Tab(String label, Category category) {
            this.label = label;
            this.category = category;
        }
    }

    /** Full-screen pages reached from the Client tab (not modules). */
    private enum Page { NONE, PEOPLE, SELF_DESTRUCT }

    private enum PeopleFilter { ALL, FRIENDS, ENEMIES }

    private record Person(String name, java.util.UUID uuid, boolean friend, boolean enemy) { }

    private interface SliderDrag {
        void set(double mouseX);
    }

    private record Zone(int x, int y, int w, int h, boolean inContent, Runnable tap, SliderDrag slider) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final Screen parent;
    private final List<Zone> zones = new ArrayList<>();

    private Tab tab = Tab.COMBAT;
    private Module detail;
    private float scroll;
    private int contentHeight;
    private int viewTop;
    private int viewBottom;
    private float k = 1f;

    private String search = "";
    private boolean searchFocused;
    private StringSetting editingString;
    private Module capturingKey;
    private Setting openColor;
    private boolean showAdvanced;
    private List<String> configNames = List.of();
    private long configNamesAt;
    private String configStatus = "";
    private boolean configLoadPending;

    private Page page = Page.NONE;
    private PeopleFilter peopleFilter = PeopleFilter.ALL;
    private String peopleStatus = "";
    /** 0 = not capturing, 1 = friend key, 2 = enemy key. */
    private int capturingPeopleKey;
    // Two-step arm on Self-Destruct, as on desktop: the second tap within 3.5 s runs the wipe.
    private boolean sdArmed;
    private long sdArmedAt;
    private boolean selfDestructing;

    private Zone pressZone;
    private SliderDrag activeSlider;
    private double pressY;
    private boolean scrolling;
    // Fling after a drag-scroll: GUI px per second in the direction the content moves (positive = scrolls down).
    private float scrollVel, flingVel;
    private long lastMoveNs, lastRenderNs;

    public MobileClickGuiScreen() {
        this(null);
    }

    public MobileClickGuiScreen(Screen parent) {
        super(Text.literal("Apexx"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Escape/back steps out of the current page first, see keyPressed.
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

    private boolean openSoundPlayed;

    @Override
    protected void init() {
        super.init();
        if (!openSoundPlayed) {
            openSoundPlayed = true;
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.OPEN);
        }
    }

    @Override
    public void close() {
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.CLOSE);
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        endEditing();
        capturingKey = null;
        // Self-destruct must not write the config (or push it to the cloud) on its way out.
        if (!configLoadPending && !selfDestructing) {
            ConfigManager manager = ConfigManager.getInstance();
            manager.save(manager.getLastLoadedConfigName());
            String json = manager.exportCurrentConfigJsonForCloud();
            if (json != null) CloudConfigSync.saveIfChangedNow(json);
        }
        super.removed();
    }

    @Override
    protected void applyBlur(DrawContext context) {
        // No blur pass: it needs offscreen targets that Android launchers' GL layers may not give.
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, MobileUi.BG);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        zones.clear();
        TextRenderer tr = textRenderer;
        // GUI units per design unit: a fixed physical size from the framebuffer height, whatever GUI scale the launcher
        // picked (ResponsiveLayout.mobileUnitPx keeps the smallest control a 44 px finger target).
        float perGui = client != null && client.getWindow() != null && client.getWindow().getScaledWidth() > 0
                ? (float) client.getWindow().getFramebufferWidth() / client.getWindow().getScaledWidth() : 1f;
        float fbHeight = height * perGui;
        float menuScale = ClickGUIModule.menuScale == null ? 1f
                : (float) Math.max(0.6, Math.min(2.0, ClickGUIModule.menuScale.getValue() / 100.0));
        k = ResponsiveLayout.mobileUnitPx(fbHeight, menuScale) / Math.max(0.01f, perGui);
        long nowNs = System.nanoTime();
        float dt = lastRenderNs == 0 ? 0f : Math.min(0.1f, (nowNs - lastRenderNs) / 1_000_000_000f);
        lastRenderNs = nowNs;
        if (flingVel != 0f && !scrolling) {
            int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
            float before = scroll;
            scroll = Math.max(0, Math.min(maxScroll, scroll + flingVel * dt));
            // Hitting either end stops the coast.
            flingVel = scroll == before || scroll == 0 || scroll == maxScroll
                    ? 0f : ResponsiveLayout.decayFling(flingVel, dt, 20f);
        }
        int accent = accent();

        int headerH = (int) (38 * k);
        int tabsH = detail == null && page == Page.NONE ? (int) (32 * k) : 0;
        viewTop = headerH + tabsH;
        viewBottom = height;

        drawContent(c, tr, accent);
        // Header and tabs are drawn after the content so rows scrolling underneath are covered.
        c.fill(0, 0, width, viewTop, MobileUi.BG | 0xFF000000);
        drawHeader(c, tr, accent, headerH);
        if (tabsH > 0) drawTabs(c, tr, accent, headerH, tabsH);
        c.fill(0, viewTop, width, viewTop + 1, 0x22FFFFFF);
    }

    /**
     * Side margin of the content column: the usual 8 units, widened on very wide screens so rows stop at
     * {@link ResponsiveLayout#MOBILE_MAX_CONTENT_UNITS} design units and the column stays centred.
     */
    private int contentPad() {
        float pad = 8 * k;
        float extra = (width - 2 * pad - ResponsiveLayout.MOBILE_MAX_CONTENT_UNITS * k) / 2f;
        return (int) (pad + Math.max(0f, extra));
    }

    private int accent() {
        try {
            Color color = ClickGUIModule.getModuleEnabledDot();
            if (color != null) return 0xFF000000 | (color.getRGB() & 0x00FFFFFF);
        } catch (Throwable ignored) {
            // Theme not ready yet: use the default accent.
        }
        return ACCENT_FALLBACK;
    }

    private void drawHeader(DrawContext c, TextRenderer tr, int accent, int h) {
        int pad = (int) (8 * k);
        int btnH = (int) (28 * k);
        int btnY = (h - btnH) / 2;

        int closeW = (int) (66 * k);
        button(c, tr, width - pad - closeW, btnY, closeW, btnH, "Close", MobileUi.PANEL_HI, MobileUi.TEXT, this::close, false);
        int rightEdge = width - pad - closeW - pad;

        int leftEdge = pad;
        String pageTitle = detail != null ? detail.getName()
                : page == Page.PEOPLE ? "Friends & Enemies"
                : page == Page.SELF_DESTRUCT ? "Self-Destruct" : null;
        if (pageTitle != null) {
            int backW = (int) (66 * k);
            button(c, tr, pad, btnY, backW, btnH, "< Back", MobileUi.PANEL_HI, MobileUi.TEXT, this::goBack, false);
            leftEdge = pad + backW + pad;
            MobileUi.textMiddle(c, tr, MobileUi.fit(tr, pageTitle, rightEdge - leftEdge, 1.3f * k), leftEdge, 0, h, 1.3f * k, MobileUi.TEXT);
            return;
        }

        String logo = "APEXX";
        MobileUi.textMiddle(c, tr, logo, pad, 0, h, 1.3f * k, accent);
        leftEdge = pad + (int) MobileUi.width(tr, logo, 1.3f * k) + pad;
        if (tab == Tab.CONFIGS || tab == Tab.COSMETICS) return;

        int boxW = rightEdge - leftEdge;
        if (boxW < 60 * k) return;
        MobileUi.roundRect(c, leftEdge, btnY, boxW, btnH, (int) (6 * k), searchFocused ? MobileUi.PANEL_HI : MobileUi.PANEL);
        boolean empty = search.isEmpty();
        String shown = empty && !searchFocused ? "Search modules" : search + (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        MobileUi.textMiddle(c, tr, MobileUi.fit(tr, shown, boxW - 14 * k, 1.0f * k), leftEdge + 8 * k, btnY, btnH, 1.0f * k,
                empty && !searchFocused ? MobileUi.MUTED : MobileUi.TEXT);
        zone(leftEdge, btnY, boxW, btnH, false, () -> {
            searchFocused = true;
            endEditing();
        });
    }

    private void drawTabs(DrawContext c, TextRenderer tr, int accent, int y, int h) {
        // Cosmetics is Premium only: free sessions get no tab for it at all.
        boolean cosmeticsTab = interstellarTikiTiks.apexx.client.util.PremiumGate.cosmetics();
        Tab[] tabs = cosmeticsTab ? Tab.values()
                : java.util.Arrays.stream(Tab.values()).filter(t -> t != Tab.COSMETICS).toArray(Tab[]::new);
        int w = width / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int x = i * w;
            boolean selected = t == tab && (search.isEmpty() || t == Tab.CONFIGS || t == Tab.COSMETICS);
            // Two more tabs (SMP, Bedwars) on a narrow phone can leave w < 8k; never let the scale go non-positive.
            float scale = Math.max(0.2f, Math.min(1.0f * k, (w - 8 * k) / Math.max(1f, tr.getWidth(t.label))));
            MobileUi.textCentered(c, tr, t.label, x + w / 2f, y, h - 3 * k, scale, selected ? MobileUi.TEXT : MobileUi.MUTED);
            if (selected) c.fill(x + (int) (6 * k), y + h - (int) (3 * k), x + w - (int) (6 * k), y + h, accent);
            zone(x, y, w, h, false, () -> selectTab(t));
        }
    }

    private void drawContent(DrawContext c, TextRenderer tr, int accent) {
        int[] cursor = { viewTop + (int) (8 * k) };
        c.enableScissor(0, viewTop, width, viewBottom);
        float startScroll = scroll;
        int originY = cursor[0];
        cursor[0] = originY - (int) startScroll;

        if (tab == Tab.COSMETICS && !interstellarTikiTiks.apexx.client.util.PremiumGate.cosmetics()) tab = Tab.COMBAT;
        if (detail != null) drawDetail(c, tr, accent, cursor);
        else if (page == Page.PEOPLE) drawPeople(c, tr, accent, cursor);
        else if (page == Page.SELF_DESTRUCT) drawSelfDestruct(c, tr, accent, cursor);
        else if (tab == Tab.CONFIGS && search.isEmpty()) drawConfigs(c, tr, accent, cursor);
        else if (tab == Tab.COSMETICS) drawCosmetics(c, tr, accent, cursor);
        else drawModuleList(c, tr, accent, cursor);

        c.disableScissor();
        contentHeight = cursor[0] + (int) startScroll - originY + (int) (16 * k);
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private boolean visible(int y, int h) {
        return y + h > viewTop && y < viewBottom;
    }

    // -- module list

    private List<Module> modulesForView() {
        var manager = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
        if (manager == null) return List.of();
        List<Module> result = new ArrayList<>();
        String query = search.trim().toLowerCase(Locale.ROOT);
        for (Module m : manager.getModules()) {
            if (isExcluded(m)) continue;
            if (!query.isEmpty()) {
                if (m.getName().toLowerCase(Locale.ROOT).contains(query)) result.add(m);
            } else if (tab.category == (settingsOnly(m) ? Category.CLIENT : m.getCategory())) {
                result.add(m);
            }
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        // The order the player arranged on desktop (drag to reorder) carries over; the rest stay A-Z.
        if (query.isEmpty() && tab.category != null) {
            return new ArrayList<>(interstellarTikiTiks.apexx.client.config.GuiPrefs.applyOrder(result, Module::getName,
                    interstellarTikiTiks.apexx.client.config.ClientPrefs.get().gui().order(tab.category.name())));
        }
        return result;
    }

    /** Hidden utilities stay off the list; self-destruct is never offered here. */
    private static boolean isExcluded(Module m) {
        String name = m.getName();
        if (name.equals("SelfDestruct") || name.equals("ConfigBind")) return true;
        return m.isHidden();
    }

    private static boolean anyPayloadModuleLoaded() {
        var manager = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
        if (manager == null) return false;
        for (Module m : manager.getModules()) if (!settingsOnly(m)) return true;
        return false;
    }

    /** ClickGUI holds settings only; it has nothing to switch on or off. */
    private static boolean settingsOnly(Module m) {
        return m.isHidden() || m.getName().equals("ClickGUI");
    }

    private void drawModuleList(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        if (tab == Tab.CLIENT && search.isEmpty()) {
            // Client-level pages that are not modules: People and Self-Destruct (desktop has them as tabs).
            int pad = contentPad();
            int rowH = (int) (50 * k);
            int gap = (int) (6 * k);
            drawNavRow(c, tr, pad, cursor[0], width - pad * 2, rowH, "Friends & Enemies",
                    "Add or remove friends and enemies", MobileUi.TEXT, () -> openPage(Page.PEOPLE));
            cursor[0] += rowH + gap;
            drawNavRow(c, tr, pad, cursor[0], width - pad * 2, rowH, "Self-Destruct",
                    "Wipe the client from this device", MobileUi.DANGER, () -> openPage(Page.SELF_DESTRUCT));
            cursor[0] += rowH + gap;
        }
        List<Module> modules = modulesForView();
        if (modules.isEmpty()) {
            if (search.isEmpty() && tab != Tab.CLIENT && !anyPayloadModuleLoaded()) {
                // Signed in, but the module payload did not register: say so, with the recorded reason.
                MobileUi.textCentered(c, tr, "Signed in, but no modules loaded", width / 2f, cursor[0], 30 * k, 1.1f * k, MobileUi.DANGER);
                cursor[0] += (int) (30 * k);
                for (String line : wrap(tr, interstellarTikiTiks.apexx.client.auth.PayloadLoader.shortStatus(), width - 32 * k, 0.9f * k)) {
                    MobileUi.textCentered(c, tr, line, width / 2f, cursor[0], 12 * k, 0.9f * k, MobileUi.MUTED);
                    cursor[0] += (int) (12 * k);
                }
                cursor[0] += (int) (10 * k);
            } else {
                MobileUi.textCentered(c, tr, search.isEmpty() ? "Nothing in this tab" : "No modules match", width / 2f, cursor[0], 40 * k, 1.1f * k, MobileUi.MUTED);
                cursor[0] += (int) (44 * k);
            }
            return;
        }
        int pad = contentPad();
        int rowH = (int) (50 * k);
        int gap = (int) (6 * k);
        for (Module m : modules) {
            int y = cursor[0];
            if (visible(y, rowH)) drawModuleRow(c, tr, accent, m, pad, y, width - pad * 2, rowH);
            cursor[0] += rowH + gap;
        }
    }

    private void drawModuleRow(DrawContext c, TextRenderer tr, int accent, Module m, int x, int y, int w, int h) {
        boolean only = settingsOnly(m);
        boolean on = m.isEnabled() && !only;
        MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), on ? MobileUi.PANEL_HI : MobileUi.PANEL);
        if (on) MobileUi.roundRect(c, x, y + (int) (8 * k), (int) (4 * k), h - (int) (16 * k), (int) (2 * k), accent);

        int toggleW = only ? 0 : (int) (56 * k);
        int toggleH = (int) (30 * k);
        int chevronW = (int) (30 * k);
        int textX = x + (int) (14 * k);
        int textMax = w - (textX - x) - toggleW - chevronW - (int) (14 * k);

        String name = m.getName();
        float nameScale = 1.2f * k;
        boolean blatant = m.isBlatantFlagged();
        MobileUi.text(c, tr, MobileUi.fit(tr, name, textMax, nameScale), textX, y + 7 * k, nameScale, on ? MobileUi.TEXT : 0xFFD3D6E2);
        if (blatant) {
            float tagScale = 0.75f * k;
            float nameW = MobileUi.width(tr, MobileUi.fit(tr, name, textMax, nameScale), nameScale);
            MobileUi.text(c, tr, "BLATANT", textX + nameW + 6 * k, y + 9 * k, tagScale, MobileUi.DANGER);
        }
        String desc = m.getDescription();
        if (desc != null && !desc.isBlank()) {
            MobileUi.text(c, tr, MobileUi.fit(tr, desc, textMax, 0.85f * k), textX, y + 7 * k + 12.5f * k, 0.85f * k, MobileUi.MUTED);
        }

        MobileUi.textCentered(c, tr, ">", x + w - (toggleW + chevronW / 2f + 8 * k), y, h, 1.4f * k, MobileUi.MUTED);
        zone(x, y, w, h, true, () -> openDetail(m));
        if (!only) {
            int tx = x + w - toggleW - (int) (10 * k);
            int ty = y + (h - toggleH) / 2;
            MobileUi.toggle(c, tx, ty, toggleW, toggleH, on, accent);
            // The target is the whole right-hand end of the row, well past the visible pill.
            zone(tx - (int) (14 * k), y, toggleW + (int) (24 * k), h, true, m::toggle);
        }
    }

    // -- module page

    private void drawDetail(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        Module m = detail;
        int pad = contentPad();
        int w = width - pad * 2;

        // Header card: description and the enable switch.
        List<String> descLines = wrap(tr, m.getDescription(), w - 24 * k, 0.95f * k);
        boolean only = settingsOnly(m);
        int headH = (int) ((only ? 14 : 52) * k) + (int) (descLines.size() * 12 * k);
        int y = cursor[0];
        if (visible(y, headH)) {
            MobileUi.roundRect(c, pad, y, w, headH, (int) (8 * k), MobileUi.PANEL);
            int ty = y + (int) (8 * k);
            if (!only) {
                MobileUi.textMiddle(c, tr, "Enabled", pad + 14 * k, y + 4 * k, 36 * k, 1.2f * k, MobileUi.TEXT);
                int tw = (int) (64 * k), th = (int) (34 * k);
                int tx = pad + w - tw - (int) (12 * k);
                MobileUi.toggle(c, tx, y + (int) (9 * k), tw, th, m.isEnabled(), accent);
                zone(pad, y, w, (int) (52 * k), true, m::toggle);
                ty = y + (int) (52 * k);
            }
            for (String line : descLines) {
                MobileUi.text(c, tr, line, pad + 14 * k, ty, 0.95f * k, MobileUi.MUTED);
                ty += (int) (12 * k);
            }
        }
        cursor[0] += headH + (int) (8 * k);

        List<ClassicSettingLayout.Row> normal = new ArrayList<>();
        List<ClassicSettingLayout.Row> advanced = new ArrayList<>();
        for (ClassicSettingLayout.Row row : ClassicSettingLayout.visibleRows(m)) {
            if (!exposed(m, row)) continue;
            (row.setting().isAdvanced() ? advanced : normal).add(row);
        }

        drawGrouped(c, tr, accent, normal, cursor);
        if (!advanced.isEmpty()) {
            int hy = cursor[0];
            int hh = (int) (36 * k);
            if (visible(hy, hh)) {
                MobileUi.roundRect(c, pad, hy, w, hh, (int) (8 * k), MobileUi.PANEL);
                MobileUi.textMiddle(c, tr, "Advanced settings", pad + 14 * k, hy, hh, 1.1f * k, MobileUi.TEXT);
                MobileUi.textMiddle(c, tr, showAdvanced ? "Hide" : "Show", pad + w - 14 * k - MobileUi.width(tr, showAdvanced ? "Hide" : "Show", 1.0f * k), hy, hh, 1.0f * k, accent);
                zone(pad, hy, w, hh, true, () -> showAdvanced = !showAdvanced);
            }
            cursor[0] += hh + (int) (6 * k);
            if (showAdvanced) drawGrouped(c, tr, accent, advanced, cursor);
        }
    }

    /**
     * Same exposure rules as the desktop menu: dependsOn/visibility are already applied by
     * {@link ClassicSettingLayout#visibleRows}; ClickGUI additionally hides settings its own rules say are
     * irrelevant (e.g. highlight colours while Custom Highlight is off), and the self-destruct options live on
     * the Self-Destruct page instead of as loose toggles.
     */
    private static boolean exposed(Module m, ClassicSettingLayout.Row row) {
        if (row.kind() == ClassicSettingLayout.Kind.ACTION) return false;
        if (m instanceof ClickGUIModule) {
            if (!ClickGUIModule.shouldExposeSetting(row.setting())) return false;
            if (selfDestructOptions().contains(row.setting())) return false;
        }
        return true;
    }

    /**
     * Draws rows split into their sections like desktop: settings without a section form their own untitled
     * group first, then each section in the order it first appears; registration order is kept inside a group.
     * (A header used to be drawn only when the section changed, so unsectioned rows fell under the previous one.)
     */
    private void drawGrouped(DrawContext c, TextRenderer tr, int accent, List<ClassicSettingLayout.Row> rows, int[] cursor) {
        java.util.Map<String, List<ClassicSettingLayout.Row>> groups = new java.util.LinkedHashMap<>();
        groups.put("", new ArrayList<>());
        for (ClassicSettingLayout.Row row : rows) {
            String section = row.section() == null ? "" : row.section().trim();
            groups.computeIfAbsent(section, key -> new ArrayList<>()).add(row);
        }
        int pad = contentPad();
        for (var group : groups.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            if (!group.getKey().isEmpty()) sectionHeader(c, tr, accent, group.getKey(), pad, cursor);
            for (ClassicSettingLayout.Row row : group.getValue()) drawSettingRow(c, tr, accent, row, cursor);
        }
    }

    private void sectionHeader(DrawContext c, TextRenderer tr, int accent, String title, int pad, int[] cursor) {
        int sy = cursor[0];
        if (visible(sy, (int) (22 * k))) MobileUi.text(c, tr, title.toUpperCase(Locale.ROOT), pad + 4 * k, sy + 8 * k, 0.9f * k, accent);
        cursor[0] += (int) (24 * k);
    }

    private void drawSettingRow(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int[] cursor) {
        int pad = contentPad();
        int w = width - pad * 2;
        switch (row.kind()) {
            case BOOLEAN -> drawBoolean(c, tr, accent, row, pad, w, cursor);
            case SLIDER -> drawSlider(c, tr, accent, row, pad, w, cursor);
            case MODE -> drawMode(c, tr, accent, row, pad, w, cursor);
            case STRING -> drawString(c, tr, accent, row, pad, w, cursor);
            case COLOR -> drawColor(c, tr, accent, row, pad, w, cursor);
            case KEYBIND -> drawKeybind(c, tr, accent, row, pad, w, cursor);
            default -> { }
        }
    }

    private int descHeight(ClassicSettingLayout.Row row) {
        return row.hasDescription() ? (int) (13 * k) : 0;
    }

    private void drawDescription(DrawContext c, TextRenderer tr, ClassicSettingLayout.Row row, int x, float y, int w) {
        if (row.hasDescription()) MobileUi.text(c, tr, MobileUi.fit(tr, row.description(), w - 28 * k, 0.8f * k), x + 14 * k, y, 0.8f * k, MobileUi.MUTED);
    }

    private void drawBoolean(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        BooleanSetting setting = (BooleanSetting) row.setting();
        int h = (int) (44 * k) + descHeight(row);
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.text(c, tr, MobileUi.fit(tr, row.name(), w - 90 * k, 1.1f * k), x + 14 * k, y + 8 * k, 1.1f * k, MobileUi.TEXT);
            drawDescription(c, tr, row, x, y + 8 * k + 13 * k, w);
            int tw = (int) (52 * k), th = (int) (28 * k);
            MobileUi.toggle(c, x + w - tw - (int) (12 * k), y + (h - th) / 2, tw, th, setting.isEnabled(), accent);
            zone(x, y, w, h, true, setting::toggle);
        }
        cursor[0] += h + (int) (6 * k);
    }

    private void drawSlider(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        SliderSetting setting = (SliderSetting) row.setting();
        int h = (int) (70 * k) + descHeight(row);
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            int stepSize = (int) (30 * k);
            int stepY = y + (int) (6 * k);
            int plusX = x + w - (int) (12 * k) - stepSize;
            String value = setting.getValueAsString();
            float valueW = Math.max(MobileUi.width(tr, value, 1.1f * k), 36 * k);
            int valueX = (int) (plusX - 8 * k - valueW);
            int minusX = (int) (valueX - 8 * k - stepSize);

            MobileUi.text(c, tr, MobileUi.fit(tr, row.name(), minusX - x - 20 * k, 1.1f * k), x + 14 * k, stepY + (stepSize - 8 * 1.1f * k) / 2f, 1.1f * k, MobileUi.TEXT);
            button(c, tr, minusX, stepY, stepSize, stepSize, "-", MobileUi.PANEL_HI, MobileUi.TEXT, () -> step(setting, -1), true);
            MobileUi.textCentered(c, tr, value, valueX + valueW / 2f, stepY, stepSize, 1.1f * k, accent);
            button(c, tr, plusX, stepY, stepSize, stepSize, "+", MobileUi.PANEL_HI, MobileUi.TEXT, () -> step(setting, 1), true);
            drawDescription(c, tr, row, x, stepY + stepSize + 2 * k, w);

            int trackX = x + (int) (18 * k);
            int trackW = w - (int) (36 * k);
            int trackH = (int) (8 * k);
            int trackY = y + h - (int) (20 * k) - trackH / 2;
            double range = setting.getMax() - setting.getMin();
            float frac = range <= 0 ? 0f : (float) ((setting.getPreciseValue() - setting.getMin()) / range);
            frac = Math.max(0f, Math.min(1f, frac));
            MobileUi.roundRect(c, trackX, trackY, trackW, trackH, trackH / 2, MobileUi.OFF);
            MobileUi.roundRect(c, trackX, trackY, Math.max(trackH, (int) (trackW * frac)), trackH, trackH / 2, accent);
            int knob = (int) (20 * k);
            MobileUi.roundRect(c, trackX + (int) (trackW * frac) - knob / 2, trackY + trackH / 2 - knob / 2, knob, knob, knob / 2, 0xFFFFFFFF);
            final int fx = trackX, fw = trackW;
            addZone(new Zone(x, trackY - (int) (14 * k), w, (int) (36 * k), true, null, mx -> {
                double p = Math.max(0.0, Math.min(1.0, (mx - fx) / (double) fw));
                setting.setValue(setting.getMin() + p * range);
            }));
        }
        cursor[0] += h + (int) (6 * k);
    }

    private static void step(SliderSetting setting, int direction) {
        double base = Math.pow(10, -setting.getDecimalPlaces());
        double range = setting.getMax() - setting.getMin();
        double step = Math.max(base, Math.round(range / 40.0 / base) * base);
        setting.setValue(setting.getPreciseValue() + direction * step);
    }

    private void drawMode(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        ModeSetting setting = (ModeSetting) row.setting();
        float chipScale = 1.0f * k;
        int chipH = (int) (30 * k);
        int chipGap = (int) (6 * k);
        int innerX = x + (int) (12 * k);
        int innerW = w - (int) (24 * k);

        List<int[]> chips = new ArrayList<>(); // x, y(relative), w
        int cx = 0, cy = 0;
        for (String choice : setting.getModes()) {
            int cw = (int) (MobileUi.width(tr, choice, chipScale) + 22 * k);
            cw = Math.min(cw, innerW);
            if (cx > 0 && cx + cw > innerW) {
                cx = 0;
                cy += chipH + chipGap;
            }
            chips.add(new int[] { cx, cy, cw });
            cx += cw + chipGap;
        }
        int chipsH = setting.getModes().isEmpty() ? 0 : cy + chipH;
        int head = (int) (30 * k) + descHeight(row);
        int h = head + chipsH + (int) (10 * k);

        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.text(c, tr, MobileUi.fit(tr, row.name(), w - 28 * k, 1.1f * k), x + 14 * k, y + 8 * k, 1.1f * k, MobileUi.TEXT);
            drawDescription(c, tr, row, x, y + 8 * k + 13 * k, w);
            List<String> modes = setting.getModes();
            for (int i = 0; i < modes.size(); i++) {
                int[] chip = chips.get(i);
                String choice = modes.get(i);
                boolean selected = choice.equals(setting.getCurrentMode());
                int chipX = innerX + chip[0];
                int chipY = y + head + chip[1];
                MobileUi.roundRect(c, chipX, chipY, chip[2], chipH, (int) (6 * k), selected ? accent : MobileUi.PANEL_HI);
                MobileUi.textCentered(c, tr, MobileUi.fit(tr, choice, chip[2] - 8 * k, chipScale), chipX + chip[2] / 2f, chipY, chipH, chipScale,
                        selected ? 0xFF14101E : MobileUi.TEXT);
                zone(chipX, chipY, chip[2], chipH, true, () -> setting.setCurrentMode(choice));
            }
        }
        cursor[0] += h + (int) (6 * k);
    }

    private void drawString(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        StringSetting setting = (StringSetting) row.setting();
        boolean editing = editingString == setting;
        int boxH = (int) (28 * k);
        int h = (int) (36 * k) + boxH + descHeight(row) + (editing ? (int) (14 * k) : 0);
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.text(c, tr, MobileUi.fit(tr, row.name(), w - 28 * k, 1.1f * k), x + 14 * k, y + 8 * k, 1.1f * k, MobileUi.TEXT);
            drawDescription(c, tr, row, x, y + 8 * k + 13 * k, w);
            int bx = x + (int) (12 * k);
            int by = y + (int) (30 * k) + descHeight(row);
            int bw = w - (int) (24 * k);
            MobileUi.roundRect(c, bx, by, bw, boxH, (int) (6 * k), editing ? accent : MobileUi.PANEL_HI);
            MobileUi.roundRect(c, bx + 1, by + 1, bw - 2, boxH - 2, (int) (6 * k), MobileUi.PANEL_HI);
            String shown = setting.getValue() + (editing && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
            MobileUi.textMiddle(c, tr, MobileUi.fit(tr, shown, bw - 14 * k, 1.0f * k), bx + 8 * k, by, boxH, 1.0f * k, MobileUi.TEXT);
            if (editing) MobileUi.text(c, tr, "Type with the on-screen keyboard. Enter or tap away to finish.", bx, by + boxH + 3 * k, 0.8f * k, MobileUi.MUTED);
            zone(x, y, w, h, true, () -> beginEditing(setting));
        }
        cursor[0] += h + (int) (6 * k);
    }

    private void drawColor(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        ColorSetting setting = (ColorSetting) row.setting();
        boolean open = openColor == setting;
        int head = (int) (44 * k) + descHeight(row);
        int pickerH = open ? (int) (4 * 34 * k + 8 * k) : 0;
        int h = head + pickerH;
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.text(c, tr, MobileUi.fit(tr, row.name(), w - 120 * k, 1.1f * k), x + 14 * k, y + 8 * k, 1.1f * k, MobileUi.TEXT);
            drawDescription(c, tr, row, x, y + 8 * k + 13 * k, w);
            Color color = setting.getValue();
            int sw = (int) (44 * k), sh = (int) (24 * k);
            int sx = x + w - sw - (int) (12 * k);
            int sy = y + (int) (10 * k);
            MobileUi.roundRect(c, sx - 1, sy - 1, sw + 2, sh + 2, (int) (6 * k), 0x55FFFFFF);
            MobileUi.roundRect(c, sx, sy, sw, sh, (int) (5 * k), 0xFF000000 | (color.getRGB() & 0xFFFFFF));
            zone(x, y, w, head, true, () -> openColor = open ? null : setting);
            if (open) {
                String[] names = { "R", "G", "B", "A" };
                int[] channel = { color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() };
                int[] tint = { 0xFFE5534B, 0xFF4BD07A, 0xFF5B8CFF, 0xFFB0B4C6 };
                for (int i = 0; i < 4; i++) {
                    final int index = i;
                    int rowY = y + head + (int) (i * 34 * k);
                    MobileUi.text(c, tr, names[i], x + 14 * k, rowY + 8 * k, 1.0f * k, MobileUi.MUTED);
                    MobileUi.text(c, tr, String.valueOf(channel[i]), x + w - 14 * k - MobileUi.width(tr, String.valueOf(channel[i]), 0.95f * k), rowY + 8 * k, 0.95f * k, MobileUi.TEXT);
                    int trackX = x + (int) (36 * k);
                    int trackW = w - (int) (36 * k) - (int) (52 * k);
                    int trackH = (int) (8 * k);
                    int trackY = rowY + (int) (14 * k);
                    MobileUi.roundRect(c, trackX, trackY, trackW, trackH, trackH / 2, MobileUi.OFF);
                    float frac = channel[i] / 255f;
                    MobileUi.roundRect(c, trackX, trackY, Math.max(trackH, (int) (trackW * frac)), trackH, trackH / 2, tint[i]);
                    int knob = (int) (18 * k);
                    MobileUi.roundRect(c, trackX + (int) (trackW * frac) - knob / 2, trackY + trackH / 2 - knob / 2, knob, knob, knob / 2, 0xFFFFFFFF);
                    final int fx = trackX, fw = trackW;
                    addZone(new Zone(x + (int) (30 * k), rowY + (int) (4 * k), w - (int) (60 * k), (int) (30 * k), true, null, mx -> {
                        int v = (int) Math.round(Math.max(0.0, Math.min(1.0, (mx - fx) / (double) fw)) * 255.0);
                        Color cur = setting.getValue();
                        int[] ch = { cur.getRed(), cur.getGreen(), cur.getBlue(), cur.getAlpha() };
                        ch[index] = v;
                        setting.setValue(new Color(ch[0], ch[1], ch[2], ch[3]));
                    }));
                }
            }
        }
        cursor[0] += h + (int) (6 * k);
    }

    private void drawKeybind(DrawContext c, TextRenderer tr, int accent, ClassicSettingLayout.Row row, int x, int w, int[] cursor) {
        KeybindSetting setting = (KeybindSetting) row.setting();
        Module target = setting.getModule();
        if (target == null) return;
        boolean capturing = capturingKey == target;
        int h = (int) (44 * k);
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.textMiddle(c, tr, "Keybind", x + 14 * k, y, h, 1.1f * k, MobileUi.TEXT);
            String label = capturing ? "Press a key..." : target.getKeyDisplayName();
            int bw = (int) Math.max(96 * k, MobileUi.width(tr, label, 1.0f * k) + 24 * k);
            int bh = (int) (30 * k);
            int bx = x + w - bw - (int) (12 * k);
            int by = y + (h - bh) / 2;
            MobileUi.roundRect(c, bx, by, bw, bh, (int) (6 * k), capturing ? accent : MobileUi.PANEL_HI);
            MobileUi.textCentered(c, tr, label, bx + bw / 2f, by, bh, 1.0f * k, capturing ? 0xFF14101E : MobileUi.TEXT);
            zone(bx, by, bw, bh, true, () -> capturingKey = capturing ? null : target);
            if (target.getKeyCode() != -1 && !capturing) {
                int clearW = (int) (30 * k);
                int clearX = bx - clearW - (int) (6 * k);
                button(c, tr, clearX, by, clearW, bh, "x", MobileUi.PANEL_HI, MobileUi.MUTED, () -> target.setKeyCode(-1), true);
                // Toggle / Hold: a held key keeps the module on only while it is down (not for one-shot or trigger binds).
                if (!target.isOneShot() && !target.isKeyTriggered()) {
                    String modeLabel = target.isHoldMode() ? "Hold" : "Toggle";
                    int modeW = (int) Math.max(56 * k, MobileUi.width(tr, "Toggle", 0.95f * k) + 18 * k);
                    button(c, tr, clearX - modeW - (int) (6 * k), by, modeW, bh, modeLabel,
                            target.isHoldMode() ? accent : MobileUi.PANEL_HI, target.isHoldMode() ? 0xFF14101E : MobileUi.TEXT,
                            () -> target.setHoldMode(!target.isHoldMode()), true);
                }
            }
        }
        cursor[0] += h + (int) (6 * k);
    }

    // -- configs

    private void drawConfigs(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        ConfigManager manager = ConfigManager.getInstance();
        long now = System.currentTimeMillis();
        if (now - configNamesAt > 2000) {
            configNames = manager.getAvailableConfigs();
            configNamesAt = now;
        }
        int pad = contentPad();
        int w = width - pad * 2;
        String active = manager.getLastLoadedConfigName();

        int y = cursor[0];
        int hh = (int) (40 * k);
        if (visible(y, hh)) {
            MobileUi.roundRect(c, pad, y, w, hh, (int) (8 * k), accent);
            MobileUi.textCentered(c, tr, "Save current settings", width / 2f, y, hh, 1.15f * k, 0xFF14101E);
            zone(pad, y, w, hh, true, () -> {
                manager.save(manager.getLastLoadedConfigName());
                configStatus = "Saved to \"" + manager.getLastLoadedConfigName() + "\"";
            });
        }
        cursor[0] += hh + (int) (6 * k);
        if (!configStatus.isEmpty()) {
            MobileUi.text(c, tr, configStatus, pad + 4 * k, cursor[0], 0.95f * k, MobileUi.MUTED);
            cursor[0] += (int) (16 * k);
        }

        int rowH = (int) (44 * k);
        for (String name : configNames) {
            int ry = cursor[0];
            if (visible(ry, rowH)) {
                boolean isActive = name.equalsIgnoreCase(active);
                MobileUi.roundRect(c, pad, ry, w, rowH, (int) (8 * k), isActive ? MobileUi.PANEL_HI : MobileUi.PANEL);
                MobileUi.textMiddle(c, tr, MobileUi.fit(tr, name, w - 120 * k, 1.15f * k), pad + 14 * k, ry, rowH, 1.15f * k, MobileUi.TEXT);
                String tag = isActive ? "Active" : "Load";
                MobileUi.textMiddle(c, tr, tag, pad + w - 14 * k - MobileUi.width(tr, tag, 1.0f * k), ry, rowH, 1.0f * k, isActive ? accent : MobileUi.MUTED);
                if (!isActive) {
                    zone(pad, ry, w, rowH, true, () -> {
                        configLoadPending = true;
                        configStatus = "Loading \"" + name + "\"...";
                        manager.load(name, result -> {
                            configLoadPending = false;
                            configStatus = "Loaded \"" + name + "\"";
                            configNamesAt = 0;
                        });
                    });
                }
            }
            cursor[0] += rowH + (int) (6 * k);
        }
    }

    // -- client pages (People, Self-Destruct)

    private void drawNavRow(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String name, String desc,
                            int nameColor, Runnable open) {
        if (!visible(y, h)) return;
        MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
        int textX = x + (int) (14 * k);
        int textMax = w - (int) (60 * k);
        MobileUi.text(c, tr, MobileUi.fit(tr, name, textMax, 1.2f * k), textX, y + 7 * k, 1.2f * k, nameColor);
        MobileUi.text(c, tr, MobileUi.fit(tr, desc, textMax, 0.85f * k), textX, y + 7 * k + 12.5f * k, 0.85f * k, MobileUi.MUTED);
        MobileUi.textCentered(c, tr, ">", x + w - 22 * k, y, h, 1.4f * k, MobileUi.MUTED);
        zone(x, y, w, h, true, open);
    }

    private static Module clickGuiModule() {
        var manager = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
        if (manager == null) return null;
        for (Module m : manager.getModules()) if (m instanceof ClickGUIModule) return m;
        return null;
    }

    /**
     * The self-destruct options that currently exist, looked up by name on the ClickGUI module so the page
     * follows whatever option set the desktop menu ships (they are ClickGUI settings surfaced only here and by
     * the desktop Self-Destruct tab). Settings registered in that order keep it; any missing name is skipped.
     */
    private static final String[] SELF_DESTRUCT_OPTION_NAMES = {
            "Ocean Safe", "Wipe Config", "Delete Jar", "Replace Jar", "Decoy URL" };

    private static List<Setting> selfDestructOptions() {
        Module gui = clickGuiModule();
        List<Setting> out = new ArrayList<>();
        if (gui == null) return out;
        for (String name : SELF_DESTRUCT_OPTION_NAMES) {
            Setting s = gui.getSetting(name);
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private void drawSelfDestruct(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        int pad = contentPad();
        int w = width - pad * 2;
        for (String line : wrap(tr, "Wipes the client from this device and shuts it down. This cannot be undone.", w - 16 * k, 0.95f * k)) {
            if (visible(cursor[0], (int) (12 * k))) MobileUi.text(c, tr, line, pad + 4 * k, cursor[0], 0.95f * k, MobileUi.MUTED);
            cursor[0] += (int) (12 * k);
        }
        cursor[0] += (int) (6 * k);

        Module gui = clickGuiModule();
        List<Setting> options = selfDestructOptions();
        if (!options.isEmpty()) sectionHeader(c, tr, accent, "Options", pad, cursor);
        for (Setting s : options) {
            ClassicSettingLayout.Row row = gui == null ? null : ClassicSettingLayout.row(gui, s);
            if (row == null || !row.visible()) continue;
            drawSettingRow(c, tr, accent, row, cursor);
        }

        cursor[0] += (int) (6 * k);
        int bh = (int) (52 * k);
        int y = cursor[0];
        boolean armed = sdArmed && System.nanoTime() - sdArmedAt < 3_500_000_000L;
        if (!armed) sdArmed = false;
        if (visible(y, bh)) {
            MobileUi.roundRect(c, pad, y, w, bh, (int) (8 * k), armed ? MobileUi.DANGER : MobileUi.withAlpha(MobileUi.DANGER, 0x40));
            MobileUi.textCentered(c, tr, armed ? "Tap again to self-destruct" : "Self-Destruct Now", width / 2f, y, bh, 1.2f * k,
                    armed ? 0xFF14101E : 0xFFFF8A8A);
            zone(pad, y, w, bh, true, () -> {
                if (sdArmed && System.nanoTime() - sdArmedAt < 3_500_000_000L) {
                    sdArmed = false;
                    triggerSelfDestruct();
                } else {
                    sdArmed = true;
                    sdArmedAt = System.nanoTime();
                }
            });
        }
        cursor[0] += bh + (int) (6 * k);
    }

    /** Same as the desktop Self-Destruct tab: runs with the tab's chosen options (SelfDestruct owns the logic). */
    private void triggerSelfDestruct() {
        selfDestructing = true;
        if (client != null) client.setScreen(null);
        interstellarTikiTiks.apexx.client.module.implementation.SelfDestruct.runConfigured();
    }

    private List<Person> savedPeople() {
        var friends = interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance();
        var enemies = interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance();
        List<Person> out = new ArrayList<>();
        for (var f : friends.getFriends()) out.add(new Person(f.getName(), f.getUuid(), true, enemies.isEnemy(f.getUuid())));
        for (var e : enemies.getEnemies()) {
            if (friends.isFriend(e.getUuid())) continue;
            out.add(new Person(e.getName(), e.getUuid(), false, true));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private List<Person> nearbyPeople() {
        if (client == null || client.world == null) return List.of();
        var friends = interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance();
        var enemies = interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance();
        List<Person> out = new ArrayList<>();
        for (var player : client.world.getPlayers()) {
            if (client.player != null && player.getUuid().equals(client.player.getUuid())) continue;
            out.add(new Person(player.getName().getString(), player.getUuid(),
                    friends.isFriend(player.getUuid()), enemies.isEnemy(player.getUuid())));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private void toggleFriend(Person p) {
        var friends = interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance();
        if (friends.isFriend(p.uuid())) {
            friends.removeFriend(p.uuid());
            peopleStatus = "Removed friend " + p.name();
        } else {
            friends.addFriend(p.name(), p.uuid());
            peopleStatus = "Added friend " + p.name();
        }
    }

    private void toggleEnemy(Person p) {
        var enemies = interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance();
        if (enemies.isEnemy(p.uuid())) {
            enemies.removeEnemy(p.uuid());
            peopleStatus = "Removed enemy " + p.name();
        } else {
            enemies.addEnemy(p.name(), p.uuid());
            peopleStatus = "Added enemy " + p.name();
        }
    }

    private void removePerson(Person p) {
        if (p.friend()) interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().removeFriend(p.uuid());
        if (p.enemy()) interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().removeEnemy(p.uuid());
        peopleStatus = "Removed " + p.name();
    }

    private void drawPeople(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        int pad = contentPad();
        int w = width - pad * 2;
        int gap = (int) (6 * k);

        // Bind keys: the key friends / marks as enemy the player under the crosshair.
        var friendKeys = interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance();
        var enemyKeys = interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance();
        peopleKeyRow(c, tr, accent, pad, w, cursor, "Friend key", 1, friendKeys.getKeyCode(), friendKeys.getKeyDisplayName(),
                () -> friendKeys.setKeyCode(-1));
        peopleKeyRow(c, tr, accent, pad, w, cursor, "Enemy key", 2, enemyKeys.getKeyCode(), enemyKeys.getKeyDisplayName(),
                () -> enemyKeys.setKeyCode(-1));

        // Filter: All / Friends / Enemies.
        int segH = (int) (32 * k);
        int y = cursor[0];
        PeopleFilter[] filters = PeopleFilter.values();
        String[] labels = { "All", "Friends", "Enemies" };
        int segW = (w - gap * (filters.length - 1)) / filters.length;
        if (visible(y, segH)) {
            for (int i = 0; i < filters.length; i++) {
                PeopleFilter f = filters[i];
                int sx = pad + i * (segW + gap);
                boolean sel = peopleFilter == f;
                MobileUi.roundRect(c, sx, y, segW, segH, (int) (6 * k), sel ? accent : MobileUi.PANEL_HI);
                MobileUi.textCentered(c, tr, labels[i], sx + segW / 2f, y, segH, 1.0f * k, sel ? 0xFF14101E : MobileUi.TEXT);
                zone(sx, y, segW, segH, true, () -> peopleFilter = f);
            }
        }
        cursor[0] += segH + gap;

        if (!peopleStatus.isEmpty()) {
            if (visible(cursor[0], (int) (14 * k))) MobileUi.text(c, tr, MobileUi.fit(tr, peopleStatus, w, 0.95f * k), pad + 4 * k, cursor[0] + 2 * k, 0.95f * k, MobileUi.MUTED);
            cursor[0] += (int) (16 * k);
        }

        sectionHeader(c, tr, accent, "Saved", pad, cursor);
        List<Person> saved = new ArrayList<>();
        for (Person p : savedPeople()) {
            if (peopleFilter == PeopleFilter.FRIENDS && !p.friend()) continue;
            if (peopleFilter == PeopleFilter.ENEMIES && !p.enemy()) continue;
            saved.add(p);
        }
        if (saved.isEmpty()) {
            String empty = peopleFilter == PeopleFilter.FRIENDS ? "No friends yet" : peopleFilter == PeopleFilter.ENEMIES ? "No enemies yet" : "Nobody saved yet";
            if (visible(cursor[0], (int) (30 * k))) MobileUi.textCentered(c, tr, empty, width / 2f, cursor[0], 30 * k, 1.0f * k, MobileUi.MUTED);
            cursor[0] += (int) (32 * k);
        }
        for (Person p : saved) personRow(c, tr, accent, pad, w, cursor, p, true);

        sectionHeader(c, tr, accent, "Nearby players", pad, cursor);
        List<Person> nearby = nearbyPeople();
        if (nearby.isEmpty()) {
            if (visible(cursor[0], (int) (30 * k))) MobileUi.textCentered(c, tr, client != null && client.world != null ? "No other players nearby" : "Join a world to see players",
                    width / 2f, cursor[0], 30 * k, 1.0f * k, MobileUi.MUTED);
            cursor[0] += (int) (32 * k);
        }
        for (Person p : nearby) personRow(c, tr, accent, pad, w, cursor, p, false);
    }

    private void peopleKeyRow(DrawContext c, TextRenderer tr, int accent, int x, int w, int[] cursor, String label, int which,
                              int code, String keyName, Runnable clear) {
        int h = (int) (44 * k);
        int y = cursor[0];
        if (visible(y, h)) {
            boolean capturing = capturingPeopleKey == which;
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            MobileUi.textMiddle(c, tr, label, x + 14 * k, y, h, 1.1f * k, MobileUi.TEXT);
            String shown = capturing ? "Press a key..." : keyName;
            int bw = (int) Math.max(96 * k, MobileUi.width(tr, shown, 1.0f * k) + 24 * k);
            int bh = (int) (30 * k);
            int bx = x + w - bw - (int) (12 * k);
            int by = y + (h - bh) / 2;
            MobileUi.roundRect(c, bx, by, bw, bh, (int) (6 * k), capturing ? accent : MobileUi.PANEL_HI);
            MobileUi.textCentered(c, tr, shown, bx + bw / 2f, by, bh, 1.0f * k, capturing ? 0xFF14101E : MobileUi.TEXT);
            zone(bx, by, bw, bh, true, () -> capturingPeopleKey = capturing ? 0 : which);
            if (code != -1 && !capturing) {
                int clearW = (int) (30 * k);
                button(c, tr, bx - clearW - (int) (6 * k), by, clearW, bh, "x", MobileUi.PANEL_HI, MobileUi.MUTED, clear, true);
            }
        }
        cursor[0] += h + (int) (6 * k);
    }

    private void personRow(DrawContext c, TextRenderer tr, int accent, int x, int w, int[] cursor, Person p, boolean savedList) {
        int h = (int) (46 * k);
        int y = cursor[0];
        if (visible(y, h)) {
            MobileUi.roundRect(c, x, y, w, h, (int) (8 * k), MobileUi.PANEL);
            int bh = (int) (30 * k);
            int by = y + (h - bh) / 2;
            int right = x + w - (int) (10 * k);
            float bs = 1.0f * k;
            if (savedList) {
                int rw = (int) (MobileUi.width(tr, "Remove", bs) + 24 * k);
                right -= rw;
                MobileUi.roundRect(c, right, by, rw, bh, (int) (6 * k), MobileUi.withAlpha(MobileUi.DANGER, 0x40));
                MobileUi.textCentered(c, tr, "Remove", right + rw / 2f, by, bh, bs, 0xFFFF8A8A);
                zone(right, by, rw, bh, true, () -> removePerson(p));
            } else {
                String fl = p.friend() ? "Unfriend" : "Friend";
                String el = p.enemy() ? "Not enemy" : "Enemy";
                int fw = (int) (MobileUi.width(tr, fl, bs) + 20 * k);
                int ew = (int) (MobileUi.width(tr, el, bs) + 20 * k);
                right -= fw;
                MobileUi.roundRect(c, right, by, fw, bh, (int) (6 * k), p.friend() ? accent : MobileUi.PANEL_HI);
                MobileUi.textCentered(c, tr, fl, right + fw / 2f, by, bh, bs, p.friend() ? 0xFF14101E : MobileUi.TEXT);
                zone(right, by, fw, bh, true, () -> toggleFriend(p));
                right -= ew + (int) (6 * k);
                MobileUi.roundRect(c, right, by, ew, bh, (int) (6 * k), p.enemy() ? MobileUi.DANGER : MobileUi.PANEL_HI);
                MobileUi.textCentered(c, tr, el, right + ew / 2f, by, bh, bs, p.enemy() ? 0xFF14101E : MobileUi.TEXT);
                zone(right, by, ew, bh, true, () -> toggleEnemy(p));
            }
            // Name plus Friend / Enemy tags.
            int textX = x + (int) (14 * k);
            int textMax = right - textX - (int) (8 * k);
            float tagScale = 0.75f * k;
            String tags = (p.friend() ? "FRIEND " : "") + (p.enemy() ? "ENEMY" : "");
            float tagW = savedList && !tags.isBlank() ? MobileUi.width(tr, tags.trim(), tagScale) + 6 * k : 0;
            String name = MobileUi.fit(tr, p.name(), textMax - tagW, 1.1f * k);
            MobileUi.textMiddle(c, tr, name, textX, y, h, 1.1f * k, MobileUi.TEXT);
            if (tagW > 0) {
                float tx = textX + MobileUi.width(tr, name, 1.1f * k) + 6 * k;
                if (p.friend()) {
                    MobileUi.textMiddle(c, tr, "FRIEND", tx, y, h, tagScale, accent);
                    tx += MobileUi.width(tr, "FRIEND ", tagScale);
                }
                if (p.enemy()) MobileUi.textMiddle(c, tr, "ENEMY", tx, y, h, tagScale, MobileUi.DANGER);
            }
        }
        cursor[0] += h + (int) (6 * k);
    }

    // -- cosmetics (premium; a simple tap-to-wear list, the full page lives in the desktop menu)

    private final interstellarTikiTiks.apexx.client.cosmetics.CosmeticsBackend cosmetics = new interstellarTikiTiks.apexx.client.cosmetics.CosmeticsBackend();

    private void drawCosmetics(DrawContext c, TextRenderer tr, int accent, int[] cursor) {
        int pad = contentPad();
        int w = width - pad * 2;
        if (!cosmetics.unlocked()) return; // Premium only; the tab is hidden for free sessions
        var prefs = cosmetics.prefs();
        var sel = prefs.selection();
        cosmeticHeader(c, tr, "Capes", pad, cursor);
        cosmeticRow(c, tr, accent, pad, w, cursor, "None", sel.cape() == null, () -> prefs.setSelection(prefs.selection().withCape(null)));
        var custom = prefs.customDesign().withPreset(interstellarTikiTiks.apexx.client.cosmetics.CapeDesign.CUSTOM);
        cosmeticRow(c, tr, accent, pad, w, cursor, "Your Cape", sel.cape() != null && "custom".equals(sel.cape().preset()),
                () -> prefs.setSelection(prefs.selection().withCape(custom)));
        for (var p : interstellarTikiTiks.apexx.client.cosmetics.CapeDesign.PRESETS) {
            var design = "theme".equals(p.id()) ? interstellarTikiTiks.apexx.client.cosmetics.CapeDesign.themed(cosmetics.accentRgb()) : p.design();
            cosmeticRow(c, tr, accent, pad, w, cursor, p.name(), sel.cape() != null && p.id().equals(sel.cape().preset()),
                    () -> prefs.setSelection(prefs.selection().withCape(design)));
        }
        cosmeticHeader(c, tr, "Hats", pad, cursor);
        cosmeticRow(c, tr, accent, pad, w, cursor, "None", sel.hat() == null, () -> prefs.setSelection(prefs.selection().withHat(null)));
        for (var item : interstellarTikiTiks.apexx.client.cosmetics.CosmeticCatalog.HATS) {
            cosmeticRow(c, tr, accent, pad, w, cursor, item.name(), sel.hat() != null && item.id().equals(sel.hat().id()),
                    () -> prefs.setSelection(prefs.selection().withHat(new interstellarTikiTiks.apexx.client.cosmetics.CosmeticSelection.Hat(item.id(), item.defaultColor()))));
        }
        cosmeticHeader(c, tr, "Wings", pad, cursor);
        cosmeticRow(c, tr, accent, pad, w, cursor, "None", sel.wings() == null, () -> prefs.setSelection(prefs.selection().withWings(null)));
        for (var item : interstellarTikiTiks.apexx.client.cosmetics.CosmeticCatalog.WINGS) {
            cosmeticRow(c, tr, accent, pad, w, cursor, item.name(), sel.wings() != null && item.id().equals(sel.wings().type()),
                    () -> prefs.setSelection(prefs.selection().withWings(interstellarTikiTiks.apexx.client.cosmetics.CosmeticSelection.Wings.of(item.id()))));
        }
    }

    private void cosmeticHeader(DrawContext c, TextRenderer tr, String label, int pad, int[] cursor) {
        cursor[0] += (int) (6 * k);
        int hh = (int) (18 * k);
        if (visible(cursor[0], hh)) MobileUi.textMiddle(c, tr, label, pad + 4 * k, cursor[0], hh, 1.0f * k, MobileUi.MUTED);
        cursor[0] += hh;
    }

    private void cosmeticRow(DrawContext c, TextRenderer tr, int accent, int pad, int w, int[] cursor, String name, boolean worn, Runnable wear) {
        int rowH = (int) (40 * k);
        int ry = cursor[0];
        if (visible(ry, rowH)) {
            MobileUi.roundRect(c, pad, ry, w, rowH, (int) (8 * k), worn ? MobileUi.PANEL_HI : MobileUi.PANEL);
            MobileUi.textMiddle(c, tr, MobileUi.fit(tr, name, w - 120 * k, 1.1f * k), pad + 14 * k, ry, rowH, 1.1f * k, MobileUi.TEXT);
            String tag = worn ? "Wearing" : "Wear";
            MobileUi.textMiddle(c, tr, tag, pad + w - 14 * k - MobileUi.width(tr, tag, 1.0f * k), ry, rowH, 1.0f * k, worn ? accent : MobileUi.MUTED);
            if (!worn) zone(pad, ry, w, rowH, true, wear);
        }
        cursor[0] += rowH + (int) (6 * k);
    }

    // ------------------------------------------------------------------ small widgets / zones

    private void button(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String label, int fill, int textColor, Runnable tap, boolean inContent) {
        MobileUi.roundRect(c, x, y, w, h, (int) (6 * k), fill);
        MobileUi.textCentered(c, tr, label, x + w / 2f, y, h, 1.1f * k, textColor);
        zone(x, y, w, h, inContent, tap);
    }

    private void zone(int x, int y, int w, int h, boolean inContent, Runnable tap) {
        addZone(new Zone(x, y, w, h, inContent, tap, null));
    }

    private void addZone(Zone zone) {
        zones.add(zone);
    }

    private List<String> wrap(TextRenderer tr, String text, float maxWidth, float scale) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && MobileUi.width(tr, candidate, scale) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    // ------------------------------------------------------------------ navigation

    private void selectTab(Tab target) {
        if (target == Tab.COSMETICS && !interstellarTikiTiks.apexx.client.util.PremiumGate.cosmetics()) return;
        tab = target;
        search = "";
        searchFocused = false;
        scroll = 0;
        endEditing();
        capturingKey = null;
        configNamesAt = 0;
        page = Page.NONE;
        capturingPeopleKey = 0;
        sdArmed = false;
    }

    private void openPage(Page target) {
        page = target;
        scroll = 0;
        searchFocused = false;
        endEditing();
        capturingKey = null;
        capturingPeopleKey = 0;
        sdArmed = false;
        peopleStatus = "";
    }

    private void openDetail(Module module) {
        detail = module;
        scroll = 0;
        showAdvanced = false;
        openColor = null;
        searchFocused = false;
        endEditing();
        capturingKey = null;
    }

    private void goBack() {
        if (detail == null) page = Page.NONE;
        capturingPeopleKey = 0;
        sdArmed = false;
        detail = null;
        scroll = 0;
        openColor = null;
        endEditing();
        capturingKey = null;
    }

    private void beginEditing(StringSetting setting) {
        if (editingString != null && editingString != setting) editingString.setEditing(false);
        editingString = setting;
        setting.setEditing(true);
        searchFocused = false;
    }

    private void endEditing() {
        if (editingString != null) editingString.setEditing(false);
        editingString = null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_1) return true;
        double mx = click.x(), my = click.y();
        pressZone = null;
        activeSlider = null;
        scrolling = false;
        flingVel = 0f; // touching the list stops a coast
        scrollVel = 0f;
        pressY = my;
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (!z.contains(mx, my)) continue;
            if (z.inContent() && (my < viewTop || my >= viewBottom)) continue;
            if (z.slider() != null) {
                activeSlider = z.slider();
                activeSlider.set(mx);
                return true;
            }
            pressZone = z;
            break;
        }
        // Tapping empty space finishes any text entry.
        if (pressZone == null) {
            searchFocused = false;
            endEditing();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (activeSlider != null) {
            activeSlider.set(click.x());
            return true;
        }
        // Slop grows with the screen so a finger's jitter on a dense display never turns a tap into a scroll.
        if (!scrolling && Math.abs(click.y() - pressY) > Math.max(TOUCH_SLOP, 2.5f * k)) {
            scrolling = true;
            pressZone = null; // the gesture became a scroll, so it must not also be a tap
            lastMoveNs = System.nanoTime();
        }
        if (scrolling) {
            long nowNs = System.nanoTime();
            float perGui = client != null && client.getWindow() != null && client.getWindow().getScaledWidth() > 0
                    ? (float) client.getWindow().getFramebufferWidth() / client.getWindow().getScaledWidth() : 1f;
            // Velocity is tracked in framebuffer pixels so the fling limits mean the same on every GUI scale.
            scrollVel = ResponsiveLayout.blendVelocity(scrollVel, -(float) offsetY * perGui, (nowNs - lastMoveNs) / 1_000_000_000f);
            lastMoveNs = nowNs;
            scroll = Math.max(0, scroll - (float) offsetY);
            int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
            scroll = Math.min(scroll, maxScroll);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        Zone down = pressZone;
        boolean wasSlider = activeSlider != null;
        pressZone = null;
        activeSlider = null;
        boolean wasScrolling = scrolling;
        scrolling = false;
        if (wasScrolling) {
            float perGui = client != null && client.getWindow() != null && client.getWindow().getScaledWidth() > 0
                    ? (float) client.getWindow().getFramebufferWidth() / client.getWindow().getScaledWidth() : 1f;
            flingVel = ResponsiveLayout.flingFor(scrollVel, System.nanoTime() - lastMoveNs) / Math.max(0.01f, perGui);
        }
        if (!wasSlider && !wasScrolling && down != null && down.contains(click.x(), click.y()) && down.tap() != null) {
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.beginPress();
            try {
                down.tap().run();
            } finally {
                interstellarTikiTiks.apexx.client.gui.sound.UiSounds.endPress(true);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scroll = Math.max(0, Math.min(maxScroll, scroll - (float) verticalAmount * 36f * k));
        return true;
    }

    private int lastKeySeen = Integer.MIN_VALUE;
    private long lastKeySeenAt;

    /** True while a key press is already this screen's business (bind capture, text input, or just received). */
    public boolean ownsKey(int key) {
        return capturingPeopleKey != 0 || capturingKey != null || editingString != null || searchFocused
                || (key == lastKeySeen && System.currentTimeMillis() - lastKeySeenAt <= 300L);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        lastKeySeen = key;
        lastKeySeenAt = System.currentTimeMillis();
        if (capturingPeopleKey != 0) {
            int code = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? -1 : key;
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                if (capturingPeopleKey == 1) interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance().setKeyCode(code);
                else interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance().setKeyCode(code);
            }
            capturingPeopleKey = 0;
            return true;
        }
        if (capturingKey != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                // cancel
            } else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                capturingKey.setKeyCode(-1);
            } else {
                capturingKey.setKeyCode(key);
            }
            capturingKey.setBinding(false);
            capturingKey = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (editingString != null || searchFocused) {
                endEditing();
                searchFocused = false;
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (editingString != null) {
                String v = editingString.getValue();
                if (!v.isEmpty()) editingString.setValue(v.substring(0, v.offsetByCodePoints(v.length(), -1)));
                return true;
            }
            if (searchFocused && !search.isEmpty()) {
                search = search.substring(0, search.offsetByCodePoints(search.length(), -1));
                scroll = 0;
                return true;
            }
        }
        // The menu key itself is handled by the tick-edge toggle in MinecraftClientMixin; handling it here
        // as well would close the screen and let that edge reopen it.
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (editingString != null || searchFocused) {
                endEditing();
                searchFocused = false;
            } else if (openColor != null) {
                openColor = null;
            } else if (detail != null || page != Page.NONE) {
                goBack();
            } else {
                close();
            }
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        int cp = input.codepoint();
        if (!Character.isValidCodePoint(cp) || Character.isISOControl(cp)) return super.charTyped(input);
        String typed = new String(Character.toChars(cp));
        if (editingString != null) {
            editingString.setValue(editingString.getValue() + typed);
            return true;
        }
        if (searchFocused) {
            search += typed;
            scroll = 0;
            return true;
        }
        return super.charTyped(input);
    }
}
