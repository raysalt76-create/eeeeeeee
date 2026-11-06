package interstellarTikiTiks.apexx.client.gui.gpuui;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.ClassicIcons;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.ResponsiveLayout;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.gui.new_ui.ColorPickerScreen;
import interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
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
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Orbit": an alternative menu layout with no single window. Categories sit on a rotating dial at the left
 * edge, the modules of the chosen category are loose glass tiles in a staggered field, and the settings of one
 * module float out as a separate stack of cards joined to its tile by a dotted tether. Every piece sits at its
 * own parallax depth. It subclasses the classic screen so every place that recognises the menu (cursor
 * handling, stream-proof overlay, movement while open) treats it the same way.
 */
@Environment(EnvType.CLIENT)
public class OrbitGpuClickGuiScreen extends GpuClickGuiScreen {
    private static final Category[] DIAL = {
            Category.COMBAT, Category.MOVEMENT, Category.AUTOMATIONS, Category.RENDER, Category.PLAYER,
            Category.BLATANT, Category.SMP, Category.BEDWARS
    };

    // Geometry in layout units; one unit is `s` framebuffer pixels.
    private static final float DIAL_R = 560f, DIAL_X = 112f;
    private static final float TOP = 84f, BOTTOM_PAD = 80f, SIDE = 40f;
    private static final float FIELD_LEFT = 288f;
    private static final float TILE_W = 184f, TILE_H = 92f, TILE_GAP = 20f;
    private static final float SAT_W = 388f;
    private static final long CLOSE_NS = 160_000_000L;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(double px, double py) { return px >= x && py >= y && px <= x + w && py <= y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> rightHits = new ArrayList<>();
    private final Map<String, Float> anims = new HashMap<>();

    // Navigation and interaction state.
    private Category category = Category.COMBAT;
    private float dialPos;
    private Module selected;
    private float fieldScroll, fieldScrollAnim, satScroll, satScrollAnim;
    private String search = "";
    private boolean searchFocused, enabledOnly, closing, swallowSlash;
    private Setting draggingSlider;
    private float sliderPx, sliderWpx;
    private StringSetting editingString;
    private KeybindSetting bindingSetting;
    private Module bindingModule;

    // Frame state.
    private long openedAt, closedAt, lastFrame;
    private float ppg = 1f, dt, s = 1f, sw, sh, viewW, viewH, nx, ny;
    // Drag-to-scroll on empty space (touch emulated as mouse, or a mouse) with fling; design units per second.
    private boolean panArmed, panning, panSat;
    private double panY;
    private float panVel, flingVel;
    private long panNs;
    private int mx, my;
    private boolean ready;
    private float clipX, clipY, clipW, clipH;
    private boolean clipping;
    private String tipTitle, tipDesc;

    // Palette, refreshed every frame from the selected theme.
    private int accent = 0xffc5b89b, text = 0xfff1f3f4, muted = 0xff9ca1a6;
    private int toggleOff = 0xff2a2c2f, rim = 0x38ffffff, glassTint = 0x8c0a0d14;
    private float glassBlur = 12f;

    // Per-frame category stats.
    private final int[] catOn = new int[DIAL.length], catAll = new int[DIAL.length];

    public OrbitGpuClickGuiScreen() {
        super();
        openedAt = nanoTime();
        lastFrame = openedAt;
    }

    // =========================================================================
    // Screen lifecycle
    // =========================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (closing && nanoTime() - closedAt > CLOSE_NS) {
            if (client != null) client.setScreen(returnScreen);
            return;
        }
        float k = pixelsPerGuiUnit();
        ppg = k;
        float px = mouseX * k, py = mouseY * k;
        if (client != null && client.mouse != null && client.getWindow() != null && client.getWindow().getWidth() > 0) {
            double toGui = (double) client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            px = (float) (client.mouse.getX() * toGui * k);
            py = (float) (client.mouse.getY() * toGui * k);
        }
        UiDisplayList frame = orbitFrame(Math.round(px), Math.round(py), width * k, height * k);
        if (frame != null) {
            GpuUiRenderer.renderPixels(context, frame, k);
            orbitHoverSound(Math.round(px), Math.round(py), width * k * height * k);
        }
    }

    private int orbitHoverKey;
    private float orbitHoverX = Float.NaN, orbitHoverY = Float.NaN;
    private int orbitSliderNotch = Integer.MIN_VALUE;

    /** A quiet tick when the pointer moves onto a new clickable target (not when content scrolls under it). */
    private void orbitHoverSound(float mx, float my, float screenArea) {
        int key = 0;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!hit.contains(mx, my)) continue;
            if (hit.w() * hit.h() > screenArea * 0.25f) break;
            key = (Math.round(hit.x()) * 31 + Math.round(hit.y())) * 31 + Math.round(hit.w()) * 7 + Math.round(hit.h()) | 1;
            break;
        }
        boolean moved = mx != orbitHoverX || my != orbitHoverY;
        orbitHoverX = mx;
        orbitHoverY = my;
        if (key == orbitHoverKey) return;
        orbitHoverKey = key;
        if (key != 0 && moved && !closing) {
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.HOVER);
        }
    }

    @Override
    public void close() {
        if (closing) return;
        stopEditing();
        cancelCapture();
        closing = true;
        closedAt = nanoTime();
        // The base screen's closing flag drives the save + cloud sync in removed(); it also plays the close sound.
        super.close();
    }

    @Override
    public void removed() {
        stopEditing();
        cancelCapture();
        super.removed();
    }

    // =========================================================================
    // Frame
    // =========================================================================

    protected final UiDisplayList orbitFrame(int mouseX, int mouseY, float screenW, float screenH) {
        long now = nanoTime();
        dt = (float) Math.min(.1, Math.max(0, (now - lastFrame) / 1_000_000_000.0));
        lastFrame = now;
        mx = mouseX;
        my = mouseY;
        sw = screenW;
        sh = screenH;
        // Fit the 1180x660 frame, but never let body text fall under its readable size while the width allows.
        s = ResponsiveLayout.orbitScale(sw, sh, 1f);
        if (flingVel != 0f) {
            // Coast after a drag-scroll (design units, like the wheel).
            float step = flingVel * dt / s;
            if (panSat) satScroll = Math.max(0f, satScroll - step);
            else fieldScroll = Math.max(0f, fieldScroll - step);
            flingVel = ResponsiveLayout.decayFling(flingVel, dt, 30f);
        }
        viewW = sw / s;
        viewH = sh / s;
        nx = clamp((mx / Math.max(1f, sw) - .5f) * 2f, -1f, 1f);
        ny = clamp((my / Math.max(1f, sh) - .5f) * 2f, -1f, 1f);
        hits.clear();
        rightHits.clear();
        clipping = false;
        tipTitle = null;
        tipDesc = null;
        ready = true;
        palette();

        float t = Math.max(0f, (now - openedAt) / 1_000_000_000f);
        float closeFade = closing ? 1f - clamp((now - closedAt) / (float) CLOSE_NS, 0f, 1f) : 1f;

        var b = UiDisplayList.builder();
        Canvas c = new Canvas(b);
        ClickGUIModule style = clickGuiModule();

        // Scene: dim, optional blur, soft glows and a parallax lattice. In SS Proof these are queued with the menu
        // and land in the hidden overlay; the blur samples a copy of the game frame, so recorders see the plain
        // game (GpuClickGuiScreen.applyBlur skips the one vanilla pass that would reach the capturable frame).
        float bgFade = Math.min(1f, t * 3f) * closeFade;
        boolean captureSafe = true;
        // No world (title screen): vanilla's panorama is the backdrop and the empty game frame would blur to black.
        boolean hasScene = client == null || client.world != null;
        if (captureSafe && hasScene && style != null && ClickGUIModule.blurBackground != null && ClickGUIModule.blurBackground.isEnabled()) {
            float blur = Math.min(8f, (float) ClickGUIModule.blurStrength.getValue() * 0.4f) * bgFade;
            if (blur > 0.3f) b.blur(0, 0, sw, sh, 0, blur);
        }
        if (captureSafe) {
            b.gradient(0, 0, sw, sh, 0, argb(0x40 * bgFade, 0), argb(0x40 * bgFade, 0), argb(0x86 * bgFade, 0), argb(0x86 * bgFade, 0));
        }
        drawBackdrop(c, t, bgFade);

        refreshStats();
        syncSelection();

        dialPos += (indexOf(category) - dialPos) * Math.min(1f, dt * 9f);
        if (Math.abs(dialPos - indexOf(category)) < 0.002f) dialPos = indexOf(category);
        fieldScrollAnim += (fieldScroll - fieldScrollAnim) * Math.min(1f, dt * 14f);
        satScrollAnim += (satScroll - satScrollAnim) * Math.min(1f, dt * 14f);

        float satP = ease("sat", selected != null && !closing ? 1f : 0f, 11f);
        float satSpace = satP * (SAT_W + 24f);
        float fieldRight = viewW - SIDE - satSpace;

        List<Module> shown = visibleModules();

        drawDial(c, t, closeFade);
        float[] tetherAt = drawField(c, t, closeFade, shown, fieldRight);
        if (satP > 0.01f && selected != null) {
            drawTether(c, tetherAt, satP * closeFade, now);
            drawSatellite(c, satP, closeFade);
        }
        drawTopBar(c, t, closeFade, fieldRight);
        drawBottomBar(c, t, closeFade);

        if (tipDesc != null && !tipDesc.isBlank()) drawTooltip(c, closeFade);

        if (interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isSsProofActive()
                && client != null && client.getWindow() != null) {
            ClassicIcons.drawCursor(b, mx, my, client.getWindow().getScaleFactor());
        }
        return b.build();
    }

    private void palette() {
        ClickGUIModule style = clickGuiModule();
        String themeName = style == null || ClickGUIModule.theme == null ? "" : ClickGUIModule.theme.getCurrentMode();
        UiThemeMaterial.Theme theme = UiThemeMaterial.theme(themeName);
        accent = 0xff000000 | (style != null && ClickGUIModule.customHighlight != null && ClickGUIModule.customHighlight.isEnabled()
                ? ClickGUIModule.highlightColor1.getValue().getRGB() : theme.accent());
        text = theme.text();
        muted = theme.muted();
        toggleOff = theme.toggleOff();
        rim = theme.outer();
        glassBlur = style != null && ClickGUIModule.blurBackground != null && ClickGUIModule.blurBackground.isEnabled()
                ? Math.max(6f, (float) ClickGUIModule.blurStrength.getValue()) : 12f;
        glassTint = (mix(0xff080b12, accent, .10f) & 0x00ffffff) | 0x8c000000;
        // Panel Tint (when the user picked one) replaces the default body, as in the classic menu.
        if (style != null) glassTint = ClickGUIModule.colourOr(ClickGUIModule.panelTint, glassTint);
    }

    private void refreshStats() {
        for (int i = 0; i < DIAL.length; i++) {
            List<Module> list = modulesForCategory(DIAL[i]);
            int on = 0;
            for (Module m : list) if (m.isEnabled()) on++;
            catOn[i] = on;
            catAll[i] = list.size();
        }
    }

    /** The satellite follows its module: a module that left the visible catalog closes it. */
    private void syncSelection() {
        if (selected != null && selected != clickGuiModule() && selected.isHidden()) selected = null;
    }

    private List<Module> visibleModules() {
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        if (q.isEmpty()) {
            for (Module m : modulesForCategory(category)) if (!enabledOnly || m.isEnabled()) out.add(m);
        } else {
            // Searches every module, not just the selected dial category.
            for (Category cat : DIAL) {
                for (Module m : modulesForCategory(cat)) {
                    if (enabledOnly && !m.isEnabled()) continue;
                    String desc = m.getDescription() == null ? "" : m.getDescription().toLowerCase(Locale.ROOT);
                    if (m.getName().toLowerCase(Locale.ROOT).contains(q) || desc.contains(q)) out.add(m);
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Backdrop
    // =========================================================================

    private void drawBackdrop(Canvas c, float t, float fade) {
        c.alpha = fade;
        // Two slow glows give the dark scene somewhere for the glass to refract.
        float drift = (float) Math.sin(t * .35f);
        c.layer(18f, 0f);
        c.shadow(-120f + drift * 14f, viewH * .5f - 220f, 420f, 440f, 210f, 160f, 0f, argb(0x30, accent));
        c.shadow(viewW - 520f - drift * 16f, viewH - 340f, 520f, 300f, 150f, 150f, 0f, argb(0x1c, mix(accent, 0xff5b7aa6, .5f)));

        c.layer(26f, 0f);
        float pitch = 46f;
        for (float gy = pitch * .5f - 10f; gy < viewH + pitch; gy += pitch) {
            for (float gx = pitch * .5f - 10f; gx < viewW + pitch; gx += pitch) {
                float dx = (mx - (gx * s + c.ox)) / s, dy = (my - (gy * s + c.oy)) / s;
                float prox = clamp(1f - (float) Math.sqrt(dx * dx + dy * dy) / 200f, 0f, 1f);
                prox *= prox;
                float r = 1.1f + 1.9f * prox;
                int col = prox > .02f ? mix(0x30ffffff, accent, prox * .85f) : 0x22ffffff;
                col = (Math.min(255, 0x1a + Math.round(0xb0 * prox)) << 24) | (col & 0x00ffffff);
                c.circle(gx, gy, r, col);
            }
        }
        c.alpha = 1f;
    }

    // =========================================================================
    // Category dial
    // =========================================================================

    private void drawDial(Canvas c, float t, float closeFade) {
        // A fixed arc centred on the screen's mid-height: every category stays in reach, the chosen one is
        // pushed out toward the field, and a selector ring glides along the arc between choices.
        float cy = (TOP + viewH - BOTTOM_PAD) * .5f + 4f, cx = DIAL_X - DIAL_R;
        float mid = (DIAL.length - 1) * .5f;
        // Orb spacing shrinks on short screens so the whole arc stays between the top chips and the status row.
        float spacing = clamp((viewH - TOP - BOTTOM_PAD - 80f) / (DIAL.length - 1), 54f, 92f);
        float step = spacing / DIAL_R;
        c.layer(5f, 0f);
        c.alpha = closeFade * reveal(t, .08f);
        float span = mid * step + .16f;
        for (int k = -34; k <= 34; k++) {
            float ang = k / 34f * span;
            float fall = 1f - Math.abs(ang) / (span + .05f);
            c.circle(cx + DIAL_R * (float) Math.cos(ang), cy + DIAL_R * (float) Math.sin(ang), 1.4f,
                    argb(0x30 + 0x28 * fall, 0xffffff));
        }
        // Selector ring (position follows the animated dial index).
        float rang = (dialPos - mid) * step;
        float rx = cx + DIAL_R * (float) Math.cos(rang) + 20f, ry = cy + DIAL_R * (float) Math.sin(rang);
        c.shadow(rx - 30f, ry - 30f, 60f, 60f, 30f, 36f, 4f, argb(0x48, accent));
        c.border(rx - 33f, ry - 33f, 66f, 66f, 33f, Math.max(1.5f, hair()), argb(0xd8, accent));

        for (int i = 0; i < DIAL.length; i++) {
            Category cat = DIAL[i];
            float ang = (i - mid) * step;
            boolean active = cat == category;
            float on = ease("orbon_" + i, active ? 1f : 0f, 12f);
            float r = 19f + 8f * on;
            float ox = cx + DIAL_R * (float) Math.cos(ang) + 20f * on, oy = cy + DIAL_R * (float) Math.sin(ang);
            c.alpha = closeFade * reveal(t, .1f + i * .035f);
            boolean hov = c.hov(ox - r, oy - r, r * 2, r * 2);
            float hv = ease("orb_" + i, hov ? 1f : 0f, 16f);

            glassDisc(c, ox, oy, r + hv * 1.5f);
            if (on > .01f) c.circle(ox, oy, r, argb(0x2c * on, accent));
            float is = r * 1.0f;
            c.icon(ClassicIcons.forCategory(cat.name()), ox - is * .5f, oy - is * .5f, is, mix(muted, text, Math.max(on, hv)));

            if (catOn[i] > 0) {
                float bx = ox + r * .72f, by = oy - r * .72f;
                c.circle(bx, by, 8f, accent);
                String n = Integer.toString(Math.min(99, catOn[i]));
                float nw = c.tw(n, 8.5f, UiDrawCommand.TextWeight.SEMIBOLD);
                c.text(bx - nw * .5f, by - 4.6f, 8.5f, 0xff0d0f12, n, UiDrawCommand.TextWeight.SEMIBOLD);
            }

            if (active) {
                String name = cat.name;
                String cnt = catOn[i] + "/" + catAll[i];
                float nw = c.tw(name, 13.5f, UiDrawCommand.TextWeight.SEMIBOLD), cw = c.tw(cnt, 9.5f, UiDrawCommand.TextWeight.REGULAR);
                float pw = 16f + nw + 10f + cw + 14f, ph = 34f, px = ox + r + 16f, py = oy - ph * .5f;
                panel(c, px, py, pw, ph, ph * .5f, .5f);
                c.textMid(px + 16f, py, ph, 13.5f, text, name, UiDrawCommand.TextWeight.SEMIBOLD);
                c.textMid(px + 16f + nw + 10f, py, ph, 9.5f, muted, cnt, UiDrawCommand.TextWeight.REGULAR);
            } else if (hov) {
                float nw = c.tw(cat.name, 10.5f, UiDrawCommand.TextWeight.SEMIBOLD);
                float pw = nw + 24f, ph = 26f, px = ox + r + 12f, py = oy - ph * .5f;
                panel(c, px, py, pw, ph, ph * .5f, .3f);
                c.textMid(px + 12f, py, ph, 10.5f, text, cat.name, UiDrawCommand.TextWeight.SEMIBOLD);
            }
            c.hit(ox - r - 4f, oy - r - 4f, r * 2 + 8f, r * 2 + 8f, () -> setCategory(cat));
        }
        c.alpha = 1f;
    }

    private void setCategory(Category cat) {
        if (cat == category) return;
        category = cat;
        fieldScroll = 0f;
        fieldScrollAnim = 0f;
        if (selected != clickGuiModule()) selected = null;
        search = "";
        searchFocused = false;
    }

    private static int indexOf(Category cat) {
        for (int i = 0; i < DIAL.length; i++) if (DIAL[i] == cat) return i;
        return 0;
    }

    // =========================================================================
    // Module field
    // =========================================================================

    /** Draws the tiles and returns where the selected module's tether starts (x, y, visibility), or null. */
    private float[] drawField(Canvas c, float t, float closeFade, List<Module> shown, float fieldRight) {
        float left = FIELD_LEFT, top = TOP + 4f, bottom = viewH - BOTTOM_PAD;
        float fieldW = Math.max(TILE_W, fieldRight - left);
        int cols = Math.max(1, (int) Math.floor((fieldW + TILE_GAP) / (TILE_W + TILE_GAP)));
        float pitchX = TILE_W + TILE_GAP, pitchY = TILE_H + TILE_GAP;
        int rows = (shown.size() + cols - 1) / cols;
        float stagger = cols > 1 ? pitchY * .5f : 0f;
        float contentH = rows * pitchY + stagger + 8f;

        // Heading line above the field.
        c.layer(7f, 0f);
        c.alpha = closeFade * reveal(t, .12f);
        boolean searching = !search.isBlank();
        String heading = searching ? "RESULTS  ·  " + shown.size()
                : category.name.toUpperCase(Locale.ROOT) + "  ·  " + catOn[indexOf(category)] + " OF " + catAll[indexOf(category)] + " ON";
        c.text(left + 4f, top + 2f, 9.5f, muted, heading, UiDrawCommand.TextWeight.SEMIBOLD);

        float viewTop = top + 22f, viewHeight = Math.max(40f, bottom - viewTop);
        float maxScroll = Math.max(0f, contentH - viewHeight);
        fieldScroll = clamp(fieldScroll, 0f, maxScroll);
        fieldScrollAnim = clamp(fieldScrollAnim, 0f, maxScroll);

        c.beginClip(left - 14f, viewTop - 10f, fieldW + 28f, viewHeight + 16f);
        float[] tether = null;
        if (shown.isEmpty()) {
            c.text(left + 4f, viewTop + 26f, 12f, muted, searching ? "Nothing matches that search." : "No modules here.");
        }
        for (int i = 0; i < shown.size(); i++) {
            Module m = shown.get(i);
            int col = i % cols, row = i / cols;
            float tx = left + col * pitchX;
            float ty = viewTop + row * pitchY + ((col & 1) == 1 ? stagger : 0f) - fieldScrollAnim;
            if (ty > bottom + 10f || ty + TILE_H < viewTop - 14f) continue;
            float depth = (col & 1) == 1 ? 9f : 5f;
            float appear = reveal(t, .16f + Math.min(i, 14) * .03f);
            c.layer(depth, (1f - appear) * 22f);
            c.alpha = closeFade * appear;
            drawTile(c, m, tx, ty);
            if (m == selected) {
                float vis = clamp((ty + TILE_H * .5f - viewTop) / 30f, 0f, 1f) * clamp((bottom - ty) / 30f, 0f, 1f);
                tether = new float[]{tx + TILE_W, ty + TILE_H * .5f, vis};
            }
        }
        c.endClip();
        c.alpha = 1f;

        if (maxScroll > 1f) {
            // Slim scroll thumb floating beside the field.
            c.layer(7f, 0f);
            c.alpha = closeFade * .8f;
            float trackH = viewHeight - 8f, thumbH = Math.max(28f, trackH * viewHeight / contentH);
            float thumbY = viewTop + 4f + (trackH - thumbH) * (fieldScrollAnim / maxScroll);
            c.rect(left + fieldW + 8f, viewTop + 4f, 3f, trackH, 1.5f, argb(0x12, 0xffffff));
            c.rect(left + fieldW + 8f, thumbY, 3f, thumbH, 1.5f, argb(0x70, accent));
            c.alpha = 1f;
        }
        return tether;
    }

    private void drawTile(Canvas c, Module m, float x, float y) {
        boolean hov = c.hov(x, y, TILE_W, TILE_H);
        boolean on = m.isEnabled();
        boolean sel = m == selected;
        float hv = ease("tile_" + m.getName(), hov ? 1f : 0f, 16f);
        float en = ease("en_" + m.getName(), on ? 1f : 0f, 12f);
        float ty = y - hv * 4f;
        float r = 16f;

        panel(c, x, ty, TILE_W, TILE_H, r, .55f + hv * .45f);
        if (en > .01f) {
            c.gradient(x, ty, TILE_W, TILE_H, r, argb(0x34 * en, accent), argb(0x10 * en, accent), 0x00000000, argb(0x08 * en, accent));
            c.border(x, ty, TILE_W, TILE_H, r, hair(), argb(0x7a * en, accent));
        }
        if (sel) c.border(x - 2.5f, ty - 2.5f, TILE_W + 5f, TILE_H + 5f, r + 2.5f, Math.max(1.5f, hair()), argb(0xd8, accent));

        // Status lamp.
        float lx = x + 20f, ly = ty + 24f;
        if (en > .01f) c.shadow(lx - 5f, ly - 5f, 10f, 10f, 5f, 16f, 2f, argb(0x90 * en, accent));
        c.circle(lx, ly, 6f, mix(argb(0x30, 0xffffff), accent, en));
        c.circle(lx, ly, 3.2f, mix(argb(0x70, 0), 0xff0d0f12, en));

        float nameMax = TILE_W - 56f - 24f;
        c.text(x + 36f, ty + 16f, 13f, on ? text : mix(text, muted, .45f), fit(c, m.getName(), 13f, nameMax),
                UiDrawCommand.TextWeight.SEMIBOLD);
        String desc = m.getDescription() == null ? "" : m.getDescription();
        c.text(x + 18f, ty + 40f, 9.5f, muted, fit(c, desc, 9.5f, TILE_W - 36f));

        // Chips along the bottom edge.
        float chipX = x + 18f, chipY = ty + TILE_H - 30f;
        if (m.isOneShot() || m.isKeyTriggered()) chipX += chip(c, chipX, chipY, "KEY", muted) + 5f;
        if (m.isBlatantFlagged()) chipX += chip(c, chipX, chipY, "BLATANT", 0xffff8f95) + 5f;
        if (m.getKeyCode() > 0) {
            String key = m.getKeyDisplayName();
            if (key != null && !key.isBlank() && chipX + c.tw(key, 8.5f, UiDrawCommand.TextWeight.SEMIBOLD) + 12f < x + TILE_W - 48f) {
                chip(c, chipX, chipY, key, accent);
            }
        }

        // Energy line while on.
        if (en > .01f) {
            c.rect(x + 18f, ty + TILE_H - 8f, (TILE_W - 36f) * en, 2.5f, 1.25f, argb(0xd0, accent));
        }

        // Gear: opens the satellite without toggling.
        float gx = x + TILE_W - 40f, gy = ty + TILE_H - 40f, gs = 28f;
        boolean gearHov = c.hov(gx, gy, gs, gs);
        c.circle(gx + gs * .5f, gy + gs * .5f, gs * .5f, gearHov || sel ? argb(0x22, 0xffffff) : argb(0x0c, 0xffffff));
        c.icon("gear", gx + 6.5f, gy + 6.5f, 15f, sel || gearHov ? text : muted);

        if (hov && !gearHov) {
            tipTitle = m.getName();
            tipDesc = desc;
        }
        final boolean oneShot = m.isOneShot();
        c.hit(x, ty, TILE_W, TILE_H, () -> {
            if (oneShot) toggleSatellite(m);
            else if (!m.isLocked()) m.toggle();
        });
        c.rhit(x, ty, TILE_W, TILE_H, () -> toggleSatellite(m));
        c.hit(gx, gy, gs, gs, () -> toggleSatellite(m));
    }

    private float chip(Canvas c, float x, float y, String label, int color) {
        float w = c.tw(label, 8.5f, UiDrawCommand.TextWeight.SEMIBOLD) + 12f, h = 16f;
        c.rect(x, y, w, h, 5f, argb(0x1e, color & 0xffffff));
        c.textMid(x + 6f, y, h, 8.5f, color, label, UiDrawCommand.TextWeight.SEMIBOLD);
        return w;
    }

    private void toggleSatellite(Module m) {
        if (selected == m) {
            selected = null;
            return;
        }
        selected = m;
        satScroll = 0f;
        satScrollAnim = 0f;
        stopEditing();
        cancelCapture();
    }

    // =========================================================================
    // Tether
    // =========================================================================

    private void drawTether(Canvas c, float[] from, float p, long now) {
        if (from == null) return;
        float sx = viewW - SIDE - SAT_W + (1f - p) * 60f;
        float sy = TOP + 44f;
        float x0 = from[0] + 6f, y0 = from[1];
        float vis = from[2] * p;
        if (vis <= .02f) return;
        c.layer(8f, 0f);
        c.alpha = vis;
        float pulse = (now / 1_000_000_000f * .55f) % 1f;
        int n = 28;
        float bend = Math.max(40f, (sx - x0) * .45f);
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n, v = 1f - u;
            float px = v * v * v * x0 + 3 * v * v * u * (x0 + bend) + 3 * v * u * u * (sx - bend) + u * u * u * sx;
            float py = v * v * v * y0 + 3 * v * v * u * y0 + 3 * v * u * u * sy + u * u * u * sy;
            float near = Math.max(0f, 1f - Math.abs(u - pulse) / .09f);
            float r = 1.5f + near * 1.8f;
            c.circle(px, py, r, argb(0x50 + 0xa0 * near, accent));
        }
        c.circle(x0, y0, 4.5f, accent);
        c.border(x0 - 8f, y0 - 8f, 16f, 16f, 8f, hair(), argb(0x80, accent));
        c.circle(sx, sy, 3.5f, accent);
        c.alpha = 1f;
    }

    // =========================================================================
    // Settings satellite
    // =========================================================================

    private record Section(String name, List<Setting> rows) { }

    private List<Section> sectionsFor(Module m) {
        boolean client = m == clickGuiModule();
        LinkedHashMap<String, List<Setting>> map = new LinkedHashMap<>();
        List<Setting> advanced = new ArrayList<>();
        for (ClassicSettingLayout.Row row : ClassicSettingLayout.visibleRows(m)) {
            Setting st = row.setting();
            if (row.kind() == ClassicSettingLayout.Kind.ACTION) continue;
            if (!client && st instanceof KeybindSetting) continue;
            // The Sign Out switch is not a plain toggle here (one click would sign out), and the ClickGUI's own
            // visibility rules apply to every other row.
            if (client && (st == ClickGUIModule.signOut || !ClickGUIModule.shouldExposeSetting(st))) continue;
            if (st.isAdvanced()) {
                advanced.add(st);
                continue;
            }
            String sec = row.section() == null || row.section().isBlank() ? "Settings" : row.section();
            map.computeIfAbsent(sec, k -> new ArrayList<>()).add(st);
        }
        List<Section> out = new ArrayList<>();
        for (var e : map.entrySet()) out.add(new Section(e.getKey(), e.getValue()));
        if (!advanced.isEmpty()) out.add(new Section("Advanced", advanced));
        return out;
    }

    private void drawSatellite(Canvas c, float p, float closeFade) {
        Module m = selected;
        boolean client = m == clickGuiModule();
        float x = viewW - SIDE - SAT_W + (1f - p) * 60f;
        float top = TOP;
        float bottom = viewH - BOTTOM_PAD;
        c.layer(12f, 0f);
        c.alpha = p * closeFade;

        // Header card.
        float hh = client ? 112f : 150f;
        panel(c, x, top, SAT_W, hh, 18f, 1f);
        c.text(x + 22f, top + 18f, 9f, muted, (client ? "CLIENT" : m.getCategory().name.toUpperCase(Locale.ROOT)),
                UiDrawCommand.TextWeight.SEMIBOLD);
        c.text(x + 22f, top + 34f, 20f, text, client ? "Appearance & menu" : fit(c, m.getName(), 20f, SAT_W - 100f),
                UiDrawCommand.TextWeight.SEMIBOLD);
        List<String> lines = wrap(c, m.getDescription() == null ? "" : m.getDescription(), 10f, SAT_W - 44f, 2);
        for (int i = 0; i < lines.size(); i++) c.text(x + 22f, top + 64f + i * 14f, 10f, muted, lines.get(i));

        float closeS = 28f, cxp = x + SAT_W - closeS - 16f, cyp = top + 14f;
        boolean closeHov = c.hov(cxp, cyp, closeS, closeS);
        c.circle(cxp + closeS * .5f, cyp + closeS * .5f, closeS * .5f, argb(closeHov ? 0x22 : 0x0e, 0xffffff));
        c.icon("close", cxp + 8f, cyp + 8f, 12f, closeHov ? text : muted);
        c.hit(cxp, cyp, closeS, closeS, () -> selected = null);

        if (!client && !m.isOneShot()) {
            float sy = top + hh - 48f, sh2 = 34f, sx = x + 18f, swd = SAT_W - 36f;
            boolean hov = c.hov(sx, sy, swd, sh2);
            float en = ease("master", m.isEnabled() ? 1f : 0f, 12f);
            c.rect(sx, sy, swd, sh2, sh2 * .5f, mix(argb(hov ? 0x1c : 0x12, 0xffffff), argb(0x34, accent), en));
            c.border(sx, sy, swd, sh2, sh2 * .5f, hair(), mix(argb(0x1c, 0xffffff), argb(0xa0, accent), en));
            c.textMid(sx + 18f, sy, sh2, 11.5f, text, m.isEnabled() ? "Enabled" : "Disabled", UiDrawCommand.TextWeight.SEMIBOLD);
            toggleSwitch(c, "msw_" + m.getName(), sx + swd - 52f, sy + (sh2 - 20f) * .5f, m.isEnabled());
            if (!m.isLocked()) c.hit(sx, sy, swd, sh2, m::toggle);
        }

        // Scrolling stack of section cards.
        float footH = client ? 0f : 56f;
        float bodyTop = top + hh + 14f;
        float bodyBottom = bottom - (client ? 0f : footH + 14f);
        float viewHeight = Math.max(40f, bodyBottom - bodyTop);
        List<Section> sections = sectionsFor(m);
        float inner = SAT_W;
        float contentH = 0f;
        for (Section sec : sections) contentH += sectionHeight(c, sec, inner) + 14f;
        float maxScroll = Math.max(0f, contentH - viewHeight - 14f);
        satScroll = clamp(satScroll, 0f, maxScroll);
        satScrollAnim = clamp(satScrollAnim, 0f, maxScroll);

        c.beginClip(x - 20f, bodyTop - 6f, SAT_W + 40f, viewHeight + 12f);
        float cy = bodyTop - satScrollAnim;
        if (sections.isEmpty()) {
            panel(c, x, cy, SAT_W, 56f, 16f, .6f);
            c.textMid(x + 20f, cy, 56f, 11f, muted, "This module has no extra settings.", UiDrawCommand.TextWeight.REGULAR);
        }
        for (Section sec : sections) {
            float h = sectionHeight(c, sec, inner);
            if (cy + h > bodyTop - 20f && cy < bodyBottom + 20f) drawSection(c, sec, x, cy, inner, h);
            cy += h + 14f;
        }
        c.endClip();

        if (!client) {
            float fy = bottom - footH;
            panel(c, x, fy, SAT_W, footH, 18f, .8f);
            String title = (m.isOneShot() || m.isKeyTriggered()) ? "Trigger key" : "Keybind";
            c.textMid(x + 22f, fy, footH, 11.5f, text, title, UiDrawCommand.TextWeight.SEMIBOLD);
            boolean capturing = bindingModule == m;
            String label = capturing ? "Press a key" : m.getKeyCode() == -1 || m.getKeyCode() == 0 ? "Not set" : m.getKeyDisplayName();
            float kw = Math.max(72f, c.tw(label, 10f, UiDrawCommand.TextWeight.SEMIBOLD) + 28f), kh = 28f;
            float clr = 28f;
            float kx = x + SAT_W - 20f - clr - 8f - kw, ky = fy + (footH - kh) * .5f;
            keyCap(c, kx, ky, kw, kh, label, capturing);
            c.hit(kx, ky, kw, kh, () -> {
                cancelCapture();
                bindingModule = m;
                m.setBinding(true);
            });
            float cx2 = x + SAT_W - 20f - clr;
            boolean clrHov = c.hov(cx2, ky, clr, kh);
            c.circle(cx2 + clr * .5f, ky + kh * .5f, clr * .5f, argb(clrHov ? 0x22 : 0x0e, 0xffffff));
            c.icon("close", cx2 + 8f, ky + 8f, 12f, clrHov ? text : muted);
            c.hit(cx2, ky, clr, kh, () -> {
                m.setKeyCode(-1);
                if (bindingModule == m) cancelCapture();
            });
        }
        c.alpha = 1f;
    }

    private float sectionHeight(Canvas c, Section sec, float w) {
        float h = 38f;
        for (Setting st : sec.rows()) h += rowHeight(c, st, w - 40f);
        return h + 6f;
    }

    private void drawSection(Canvas c, Section sec, float x, float y, float w, float h) {
        panel(c, x, y, w, h, 16f, .6f);
        c.text(x + 22f, y + 16f, 9f, muted, sec.name().toUpperCase(Locale.ROOT), UiDrawCommand.TextWeight.SEMIBOLD);
        float ry = y + 38f;
        List<Setting> rows = sec.rows();
        for (int i = 0; i < rows.size(); i++) {
            Setting st = rows.get(i);
            float rh = rowHeight(c, st, w - 40f);
            drawRow(c, st, x + 20f, ry, w - 40f, rh);
            ry += rh;
            if (i < rows.size() - 1) c.rect(x + 22f, ry - .5f, w - 44f, .75f, 0f, argb(0x14, 0xffffff));
        }
    }

    private float rowHeight(Canvas c, Setting st, float w) {
        if (st instanceof SliderSetting) return 54f;
        if (st instanceof StringSetting) return 66f;
        if (st instanceof ModeSetting mode) return 36f + modeRows(c, mode, w) * 32f - 6f + 12f;
        return 40f;
    }

    private int modeRows(Canvas c, ModeSetting mode, float w) {
        int rows = 1;
        float cur = 0f;
        for (String name : mode.getModes()) {
            float cw = chipWidth(c, name);
            if (cur > 0f && cur + cw > w) {
                rows++;
                cur = 0f;
            }
            cur += cw + 6f;
        }
        return rows;
    }

    private float chipWidth(Canvas c, String name) {
        return Math.min(150f, c.tw(trim(name, 18), 10f, UiDrawCommand.TextWeight.SEMIBOLD) + 24f);
    }

    private void drawRow(Canvas c, Setting st, float x, float y, float w, float h) {
        boolean hov = c.hov(x, y, w, h);
        if (hov && st.getDescription() != null && !st.getDescription().isBlank()) {
            tipTitle = st.getName();
            tipDesc = st.getDescription();
        }
        float labelW = c.tw(st.getName(), 11.5f, UiDrawCommand.TextWeight.REGULAR);
        if (st instanceof BooleanSetting bool) {
            c.textMid(x + 2f, y, h, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            if (st.isBlatant()) c.text(x + 2f + labelW + 8f, y + h * .5f - 4.2f, 8f, 0xffff8f95, "BLATANT", UiDrawCommand.TextWeight.SEMIBOLD);
            toggleSwitch(c, "bool_" + System.identityHashCode(bool), x + w - 40f, y + (h - 20f) * .5f, bool.isEnabled());
            c.hit(x, y, w, h, bool::toggle);
        } else if (st instanceof SliderSetting slider) {
            String value = String.format(Locale.ROOT, "%." + slider.getDecimalPlaces() + "f", slider.getValue());
            c.text(x + 2f, y + 10f, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            if (st.isBlatant()) c.text(x + 2f + labelW + 8f, y + 12f, 8f, 0xffff8f95, "BLATANT", UiDrawCommand.TextWeight.SEMIBOLD);
            float vw = c.tw(value, 10.5f, UiDrawCommand.TextWeight.SEMIBOLD);
            c.text(x + w - vw - 2f, y + 10.6f, 10.5f, accent, value, UiDrawCommand.TextWeight.SEMIBOLD);
            float tx = x + 2f, tw = w - 4f, ty = y + h - 18f, th = 5f;
            double span = slider.getMax() - slider.getMin();
            float prog = span == 0 ? 0f : clamp((float) ((slider.getValue() - slider.getMin()) / span), 0f, 1f);
            c.rect(tx, ty, tw, th, th * .5f, argb(0x1c, 0xffffff));
            if (prog > 0f) c.rect(tx, ty, Math.max(th, tw * prog), th, th * .5f, argb(0xff, accent));
            boolean active = draggingSlider == slider || c.hov(tx - 6f, ty - 8f, tw + 12f, 22f);
            float kr = active ? 7.5f : 6.5f;
            float kx = tx + tw * prog;
            c.shadow(kx - kr, ty + th * .5f - kr + 1f, kr * 2, kr * 2, kr, 5f, 0f, 0x66000000);
            c.circle(kx, ty + th * .5f, kr, 0xfff7f8fa);
            if (draggingSlider == slider) {
                sliderPx = c.px(tx);
                sliderWpx = tw * s;
            }
            final float pressPx = c.px(tx); // measured on this row's own parallax layer, not the last one drawn
            c.hit(tx - 8f, y + 18f, tw + 16f, h - 18f, () -> {
                draggingSlider = slider;
                sliderPx = pressPx;
                sliderWpx = tw * s;
                applySlider(slider, mx);
            });
        } else if (st instanceof ModeSetting mode) {
            c.text(x + 2f, y + 10f, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            float cx = 0f, cy = y + 32f;
            for (String name : mode.getModes()) {
                float cw = chipWidth(c, name);
                if (cx > 0f && cx + cw > w) {
                    cx = 0f;
                    cy += 32f;
                }
                boolean active = name.equals(mode.getCurrentMode());
                boolean ch = c.hov(x + cx, cy, cw, 26f);
                float act = ease("mode_" + System.identityHashCode(mode) + name, active ? 1f : 0f, 14f);
                c.rect(x + cx, cy, cw, 26f, 13f, mix(argb(ch ? 0x16 : 0x0b, 0xffffff), argb(0x34, accent), act));
                c.border(x + cx, cy, cw, 26f, 13f, hair(), mix(argb(0x16, 0xffffff), argb(0xc0, accent), act));
                String label = trim(name, 18);
                float lw = c.tw(label, 10f, UiDrawCommand.TextWeight.SEMIBOLD);
                c.textMid(x + cx + (cw - lw) * .5f, cy, 26f, 10f, active || ch ? text : muted, label, UiDrawCommand.TextWeight.SEMIBOLD);
                c.hit(x + cx, cy, cw, 26f, () -> mode.setCurrentMode(name));
                cx += cw + 6f;
            }
        } else if (st instanceof ColorSetting color) {
            c.textMid(x + 2f, y, h, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            int rgb = color.getValue().getRGB();
            String hex = String.format(Locale.ROOT, "#%06X", rgb & 0xffffff);
            float hw = c.tw(hex, 10f, UiDrawCommand.TextWeight.REGULAR);
            c.circle(x + w - 15f, y + h * .5f, 12.5f, argb(0x34, 0xffffff));
            c.circle(x + w - 15f, y + h * .5f, 11.5f, rgb | 0xff000000);
            c.textMid(x + w - 36f - hw, y, h, 10f, muted, hex, UiDrawCommand.TextWeight.REGULAR);
            c.hit(x, y, w, h, () -> {
                if (client != null) client.setScreen(new ColorPickerScreen(this, color, v -> color.setValue(v)));
            });
        } else if (st instanceof StringSetting string) {
            c.text(x + 2f, y + 10f, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            float fx = x + 2f, fy = y + 32f, fw = w - 4f, fh = 28f;
            boolean editing = string.isEditing();
            c.rect(fx, fy, fw, fh, 9f, argb(editing ? 0x18 : 0x0c, 0xffffff));
            c.border(fx, fy, fw, fh, 9f, hair(), editing ? argb(0xb0, accent) : argb(0x1c, 0xffffff));
            String shown = string.getValue().isEmpty() ? "Click to edit" : string.getValue();
            if (editing && (nanoTime() / 500_000_000L) % 2 == 0) shown += "|";
            c.textMid(fx + 11f, fy, fh, 10f, string.getValue().isEmpty() && !editing ? muted : text, fit(c, shown, 10f, fw - 22f),
                    UiDrawCommand.TextWeight.REGULAR);
            c.hit(fx, fy, fw, fh, () -> beginEdit(string));
        } else if (st instanceof KeybindSetting keybind) {
            c.textMid(x + 2f, y, h, 11.5f, text, st.getName(), UiDrawCommand.TextWeight.REGULAR);
            Module target = keybind.getModule();
            boolean capturing = bindingSetting == keybind;
            String label = capturing ? "Press a key" : target == null || target.getKeyCode() == -1 || target.getKeyCode() == 0 ? "Not set" : target.getKeyDisplayName();
            float kw = Math.max(72f, c.tw(label, 10f, UiDrawCommand.TextWeight.SEMIBOLD) + 28f), kh = 28f;
            keyCap(c, x + w - kw, y + (h - kh) * .5f, kw, kh, label, capturing);
            c.hit(x, y, w, h, () -> {
                cancelCapture();
                bindingSetting = keybind;
                if (keybind.getModule() != null) keybind.getModule().setBinding(true);
            });
        }
    }

    private void keyCap(Canvas c, float x, float y, float w, float h, String label, boolean active) {
        boolean hov = c.hov(x, y, w, h);
        c.rect(x, y, w, h, 9f, argb(active ? 0x24 : hov ? 0x18 : 0x0e, 0xffffff));
        c.border(x, y, w, h, 9f, hair(), active ? argb(0xc0, accent) : argb(hov ? 0x2c : 0x1c, 0xffffff));
        float lw = c.tw(label, 10f, UiDrawCommand.TextWeight.SEMIBOLD);
        c.textMid(x + (w - lw) * .5f, y, h, 10f, active ? accent : text, label, UiDrawCommand.TextWeight.SEMIBOLD);
    }

    private void toggleSwitch(Canvas c, String id, float x, float y, boolean on) {
        float a = ease(id, on ? 1f : 0f, 18f);
        float w = 34f, h = 19f;
        c.rect(x, y, w, h, h * .5f, mix(mix(argb(0x1c, 0xffffff), toggleOff | 0xff000000, .55f), accent, a));
        c.border(x, y, w, h, h * .5f, hair(), mix(argb(0x1c, 0xffffff), argb(0x40, 0xffffff), a));
        float knob = h - 5f, kx = x + 2.5f + (w - knob - 5f) * a, ky = y + 2.5f;
        c.shadow(kx, ky + 1f, knob, knob, knob * .5f, 3f, 0f, 0x55000000);
        float luma = (((accent >> 16) & 0xff) * .299f + ((accent >> 8) & 0xff) * .587f + (accent & 0xff) * .114f) / 255f;
        c.circle(kx + knob * .5f, ky + knob * .5f, knob * .5f, mix(0xfff7f8fa, luma > .62f ? 0xff14171b : 0xfff7f8fa, a));
    }

    // =========================================================================
    // Top and bottom floating chips
    // =========================================================================

    private record Util(String icon, String label, Runnable action, boolean active) { }

    private void drawTopBar(Canvas c, float t, float closeFade, float fieldRight) {
        // Brand.
        c.layer(3f, 0f);
        float ap = reveal(t, 0f);
        c.alpha = closeFade * ap;
        float bx = SIDE, by = 22f, bh = 40f;
        float nameW = c.tw("Apexx", 14f, UiDrawCommand.TextWeight.SEMIBOLD);
        float bw = 14f + 22f + 10f + nameW + 12f + c.tw("ORBIT", 8.5f, UiDrawCommand.TextWeight.SEMIBOLD) + 16f;
        panel(c, bx, by, bw, bh, bh * .5f, .6f);
        c.icon("logo", bx + 14f, by + 9f, 22f, text);
        c.textMid(bx + 14f + 22f + 10f, by, bh, 14f, text, "Apexx", UiDrawCommand.TextWeight.SEMIBOLD);
        c.textMid(bx + 14f + 22f + 10f + nameW + 12f, by, bh, 8.5f, muted, "ORBIT", UiDrawCommand.TextWeight.SEMIBOLD);

        // Search.
        c.alpha = closeFade * reveal(t, .04f);
        float sx = FIELD_LEFT, sw2 = 380f, sh2 = 40f;
        boolean hov = c.hov(sx, by, sw2, sh2);
        float focus = ease("search", searchFocused ? 1f : 0f, 16f);
        panel(c, sx, by, sw2, sh2, sh2 * .5f, .6f);
        if (focus > .01f) c.border(sx, by, sw2, sh2, sh2 * .5f, Math.max(1.5f, hair()), argb(0xc0 * focus, accent));
        c.icon("search", sx + 16f, by + 12f, 16f, mix(muted, text, Math.max(focus, hov ? .6f : 0f)));
        String shown = search.isEmpty() ? (searchFocused ? "" : "Search every module") : search;
        if (searchFocused && (nanoTime() / 500_000_000L) % 2 == 0) shown += "|";
        c.textMid(sx + 42f, by, sh2, 11.5f, search.isEmpty() && !searchFocused ? muted : text, fit(c, shown, 11.5f, sw2 - 90f),
                UiDrawCommand.TextWeight.REGULAR);
        // Registered before the clear button so the (later, topmost) button hit wins over the field.
        c.hit(sx, by, sw2, sh2, () -> {
            stopEditing();
            searchFocused = true;
        });
        if (search.isEmpty()) {
            float kw = 22f;
            c.rect(sx + sw2 - kw - 14f, by + 11f, kw, 18f, 6f, argb(0x14, 0xffffff));
            float sl = c.tw("/", 10f, UiDrawCommand.TextWeight.SEMIBOLD);
            c.textMid(sx + sw2 - kw - 14f + (kw - sl) * .5f, by + 11f, 18f, 10f, muted, "/", UiDrawCommand.TextWeight.SEMIBOLD);
        } else {
            float cs = 22f, cx = sx + sw2 - cs - 12f;
            boolean ch = c.hov(cx, by + 9f, cs, cs);
            c.circle(cx + cs * .5f, by + 9f + cs * .5f, cs * .5f, argb(ch ? 0x22 : 0x10, 0xffffff));
            c.icon("close", cx + 6f, by + 15f, 10f, ch ? text : muted);
            c.hit(cx, by + 9f, cs, cs, () -> {
                search = "";
                fieldScroll = 0f;
            });
        }

        // Utility buttons, right aligned.
        // Macros and Scripts are Premium only: free sessions get no button for them at all.
        boolean studio = interstellarTikiTiks.apexx.client.util.PremiumGate.studio();
        List<Util> utils = new java.util.ArrayList<>();
        utils.add(new Util("gear", "Appearance & menu", this::toggleAppearance, selected != null && selected == clickGuiModule()));
        utils.add(new Util("configs", "Configs", () -> openClassic("CONFIGS"), false));
        if (studio) {
            utils.add(new Util("macros", "Macros", () -> openClassic("MACROS"), false));
            utils.add(new Util("info", "Scripts", () -> openClassic("SCRIPTS"), false));
        }
        utils.addAll(List.of(
                new Util("friends", "People", () -> openClassic("FRIENDS"), false),
                new Util("crosshair", "HUD editor", () -> {
                    // HUD elements draw from the live player, so the editor needs a world (not the title screen).
                    if (client != null && client.world == null) {
                        interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.toast("HUD Editor", "Join a world to edit the HUD.");
                    } else if (client != null) client.setScreen(new HudEditorScreen(this));
                }, false),
                new Util("shield", interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.isSsProof()
                        ? "Screenshare proof: on" : "Screenshare proof: off", () -> {
                    var proof = interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.ssProof;
                    if (proof != null) proof.setEnabled(!proof.isEnabled());
                }, interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.isSsProof()),
                new Util("alert", "Self-Destruct", () -> openClassic("SELF_DESTRUCT"), false),
                new Util("logo", "Classic menu", () -> openClassic(null), false)));
        float d = 40f, gap = 10f;
        float ux = viewW - SIDE - (utils.size() * d + (utils.size() - 1) * gap);
        for (int i = 0; i < utils.size(); i++) {
            Util u = utils.get(i);
            float bxu = ux + i * (d + gap);
            c.layer(3f + (i & 1) * 2f, 0f);
            c.alpha = closeFade * reveal(t, .06f + i * .03f);
            boolean uh = c.hov(bxu, by, d, d);
            float act = ease("util_" + i, u.active() || uh ? 1f : 0f, 16f);
            if (u.active()) c.shadow(bxu, by, d, d, d * .5f, 24f, 2f, argb(0x50, accent));
            glassDisc(c, bxu + d * .5f, by + d * .5f, d * .5f);
            if (u.active()) c.border(bxu - 2.5f, by - 2.5f, d + 5f, d + 5f, d * .5f + 2.5f, hair(), argb(0xc0, accent));
            c.icon(u.icon(), bxu + 11f, by + 11f, 18f, mix(muted, u.active() ? accent : text, act));
            c.hit(bxu, by, d, d, u.action());
            if (uh) {
                float lw = c.tw(u.label(), 10f, UiDrawCommand.TextWeight.SEMIBOLD) + 22f;
                float lx = Math.min(bxu + d * .5f - lw * .5f, viewW - SIDE - lw + 6f), ly = by + d + 8f;
                panel(c, lx, ly, lw, 24f, 12f, .3f);
                c.textMid(lx + 11f, ly, 24f, 10f, text, u.label(), UiDrawCommand.TextWeight.SEMIBOLD);
            }
        }
        c.alpha = 1f;
    }

    private void drawBottomBar(Canvas c, float t, float closeFade) {
        c.layer(4f, 0f);
        float h = 34f, y = viewH - 24f - h;
        int on = 0, all = 0;
        for (int i = 0; i < DIAL.length; i++) {
            on += catOn[i];
            all += catAll[i];
        }
        c.alpha = closeFade * reveal(t, .28f);
        String a = on + " active";
        String b2 = " of " + all + " modules";
        float aw = c.tw(a, 10.5f, UiDrawCommand.TextWeight.SEMIBOLD), bw = c.tw(b2, 10.5f, UiDrawCommand.TextWeight.REGULAR);
        float w1 = 18f + 10f + aw + bw + 18f;
        panel(c, SIDE, y, w1, h, h * .5f, .5f);
        c.circle(SIDE + 20f, y + h * .5f, 3.5f, accent);
        c.textMid(SIDE + 34f, y, h, 10.5f, text, a, UiDrawCommand.TextWeight.SEMIBOLD);
        c.textMid(SIDE + 34f + aw, y, h, 10.5f, muted, b2, UiDrawCommand.TextWeight.REGULAR);

        c.alpha = closeFade * reveal(t, .31f);
        String hint = "Right-click a tile for its settings   ·   scroll the dial to change category   ·   / to search";
        float hw = c.tw(hint, 9.5f, UiDrawCommand.TextWeight.REGULAR) + 36f;
        float hx = SIDE + w1 + 14f;
        String toggleLabel = "Enabled only";
        float tlw = c.tw(toggleLabel, 10.5f, UiDrawCommand.TextWeight.SEMIBOLD);
        float w3 = 18f + tlw + 14f + 34f + 16f, x3 = viewW - SIDE - w3;
        if (hx + hw + 14f < x3) {
            panel(c, hx, y, hw, h, h * .5f, .4f);
            c.textMid(hx + 18f, y, h, 9.5f, muted, hint, UiDrawCommand.TextWeight.REGULAR);
        }
        c.alpha = closeFade * reveal(t, .34f);
        panel(c, x3, y, w3, h, h * .5f, .5f);
        c.textMid(x3 + 18f, y, h, 10.5f, text, toggleLabel, UiDrawCommand.TextWeight.SEMIBOLD);
        toggleSwitch(c, "enabled_only", x3 + 18f + tlw + 14f, y + (h - 20f) * .5f, enabledOnly);
        c.hit(x3, y, w3, h, () -> {
            enabledOnly = !enabledOnly;
            fieldScroll = 0f;
        });
        c.alpha = 1f;
    }

    private void drawTooltip(Canvas c, float closeFade) {
        c.layer(0f, 0f);
        c.alpha = closeFade;
        float size = 9.5f, pad = 12f, maxW = 250f;
        boolean title = tipTitle != null && !tipTitle.isBlank();
        List<String> lines = wrap(c, tipDesc, size, maxW - pad * 2f, 4);
        if (lines.isEmpty()) return;
        float w = title ? c.tw(tipTitle, 10f, UiDrawCommand.TextWeight.SEMIBOLD) : 0f;
        for (String line : lines) w = Math.max(w, c.tw(line, size, UiDrawCommand.TextWeight.REGULAR));
        w = Math.min(maxW, w + pad * 2f);
        float h = pad * 2f - 2f + (title ? 16f : 0f) + lines.size() * 13f;
        float x = mx / s + 18f, y = my / s + 20f;
        if (x + w > viewW - 12f) x = mx / s - w - 14f;
        if (y + h > viewH - 12f) y = my / s - h - 14f;
        panel(c, x, y, w, h, 12f, .4f);
        float ty = y + pad - 1f;
        if (title) {
            c.text(x + pad, ty, 10f, text, tipTitle, UiDrawCommand.TextWeight.SEMIBOLD);
            ty += 16f;
        }
        for (String line : lines) {
            c.text(x + pad, ty, size, muted, line);
            ty += 13f;
        }
        c.alpha = 1f;
    }

    private void toggleAppearance() {
        Module style = clickGuiModule();
        if (style == null) return;
        if (selected == style) {
            selected = null;
        } else {
            selected = style;
            satScroll = 0f;
            satScrollAnim = 0f;
        }
    }

    private void openClassic(String viewName) {
        if (client == null) return;
        GpuClickGuiScreen classic = new GpuClickGuiScreen().withReturnScreen(returnScreen);
        if (viewName != null) classic.showView(viewName);
        client.setScreen(classic);
    }

    // =========================================================================
    // Panels
    // =========================================================================

    /** A floating glass slab. {@code depth} scales how far it appears to hover above the scene. */
    private void panel(Canvas c, float x, float y, float w, float h, float r, float depth) {
        c.shadow(x, y + 12f * depth, w, h, r, 34f * depth + 6f, 0f, argb(0x62, 0));
        c.shadow(x, y + 2f, w, h, r, 8f, 0f, argb(0x40, 0));
        c.glass(x, y, w, h, r, glassBlur, glassTint);
        c.gradient(x, y, w, h, r, argb(0x16, 0xffffff), argb(0x08, 0xffffff), 0x00ffffff, argb(0x03, 0xffffff));
        c.border(x, y, w, h, r, hair(), rim);
        if (w > r * 2f + 4f) c.rect(x + r, y + .75f, w - r * 2f, .75f, 0f, argb(0x22, 0xffffff));
    }

    private void glassDisc(Canvas c, float cx, float cy, float r) {
        c.shadow(cx - r, cy - r + 5f, r * 2, r * 2, r, 20f, 0f, argb(0x58, 0));
        c.glass(cx - r, cy - r, r * 2, r * 2, r, glassBlur, glassTint);
        c.gradient(cx - r, cy - r, r * 2, r * 2, r, argb(0x1c, 0xffffff), argb(0x08, 0xffffff), 0x00ffffff, argb(0x04, 0xffffff));
        c.border(cx - r, cy - r, r * 2, r * 2, r, hair(), rim);
    }

    // =========================================================================
    // Canvas: unit space + parallax layer + alpha, converting to framebuffer pixels
    // =========================================================================

    private final class Canvas {
        final UiDisplayList.Builder b;
        float ox, oy, alpha = 1f;

        Canvas(UiDisplayList.Builder b) {
            this.b = b;
        }

        /** Selects a depth layer: larger depth moves further against the pointer. */
        void layer(float depth, float riseUnits) {
            ox = -nx * depth * s;
            oy = -ny * depth * .7f * s + riseUnits * s;
        }

        float px(float u) { return u * s + ox; }

        int col(int argb) {
            int a = Math.round(((argb >>> 24) & 0xff) * alpha);
            return (a << 24) | (argb & 0x00ffffff);
        }

        void rect(float x, float y, float w, float h, float r, int color) {
            b.rect(x * s + ox, y * s + oy, w * s, h * s, r * s, col(color));
        }

        void circle(float cx, float cy, float r, int color) {
            b.circle(cx * s + ox, cy * s + oy, r * s, col(color));
        }

        void gradient(float x, float y, float w, float h, float r, int tl, int tr, int br, int bl) {
            b.gradient(x * s + ox, y * s + oy, w * s, h * s, r * s, col(tl), col(tr), col(br), col(bl));
        }

        void border(float x, float y, float w, float h, float r, float thicknessPx, int color) {
            b.border(x * s + ox, y * s + oy, w * s, h * s, r * s, thicknessPx, col(color));
        }

        void shadow(float x, float y, float w, float h, float r, float soft, float spread, int color) {
            b.shadow(x * s + ox, y * s + oy, w * s, h * s, r * s, soft * s, spread * s, col(color));
        }

        void glass(float x, float y, float w, float h, float r, float blur, int tint) {
            b.glass(x * s + ox, y * s + oy, w * s, h * s, r * s, blur, col(tint));
        }

        void text(float x, float y, float size, int color, String str) {
            text(x, y, size, color, str, UiDrawCommand.TextWeight.REGULAR);
        }

        void text(float x, float y, float size, int color, String str, UiDrawCommand.TextWeight weight) {
            b.text(x * s + ox, y * s + oy, size * s, col(color), str, weight);
        }

        /** Text vertically centred in a box. */
        void textMid(float x, float y, float h, float size, int color, String str, UiDrawCommand.TextWeight weight) {
            float top = GpuUiFontMetrics.topInBox(y * s + oy, h * s, size * s);
            b.text(x * s + ox, top, size * s, col(color), str, weight);
        }

        float tw(String str, float size, UiDrawCommand.TextWeight weight) {
            return GpuUiRenderer.textWidth(str, size * s, weight) / s;
        }

        void icon(String name, float x, float y, float size, int color) {
            ClassicIcons.draw(b, name, x * s + ox, y * s + oy, size * s, col(color));
        }

        void beginClip(float x, float y, float w, float h) {
            b.clipPush(x * s + ox, y * s + oy, w * s, h * s, 0);
            clipX = x * s + ox;
            clipY = y * s + oy;
            clipW = w * s;
            clipH = h * s;
            clipping = true;
        }

        void endClip() {
            b.clipPop();
            clipping = false;
        }

        boolean hov(float x, float y, float w, float h) {
            if (!(ready && alpha > .2f)) return false;
            double px = x * s + ox, py = y * s + oy;
            if (clipping && (mx < clipX || my < clipY || mx > clipX + clipW || my > clipY + clipH)) return false;
            return mx >= px && my >= py && mx <= px + w * s && my <= py + h * s;
        }

        void hit(float x, float y, float w, float h, Runnable action) {
            addHit(hits, x * s + ox, y * s + oy, w * s, h * s, action);
        }

        void rhit(float x, float y, float w, float h, Runnable action) {
            addHit(rightHits, x * s + ox, y * s + oy, w * s, h * s, action);
        }

        private void addHit(List<Hit> into, float x, float y, float w, float h, Runnable action) {
            if (alpha < .25f) return;
            if (clipping) {
                float right = Math.min(x + w, clipX + clipW), bottom = Math.min(y + h, clipY + clipH);
                x = Math.max(x, clipX);
                y = Math.max(y, clipY);
                w = right - x;
                h = bottom - y;
                if (w <= 0 || h <= 0) return;
            }
            into.add(new Hit(x, y, w, h, action));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float hair() {
        return Math.max(1f, Math.round(s * .75f));
    }

    private float reveal(float t, float delay) {
        float p = clamp((t - delay) / .42f, 0f, 1f);
        return 1f - (1f - p) * (1f - p) * (1f - p);
    }

    private float ease(String key, float target, float speed) {
        float cur = anims.getOrDefault(key, target);
        cur += (target - cur) * Math.min(1f, dt * speed);
        if (Math.abs(cur - target) < .003f) cur = target;
        anims.put(key, cur);
        return cur;
    }

    private String fit(Canvas c, String str, float size, float maxW) {
        if (str == null || str.isEmpty()) return "";
        if (c.tw(str, size, UiDrawCommand.TextWeight.REGULAR) <= maxW) return str;
        int end = str.length();
        while (end > 1 && c.tw(str.substring(0, end) + "…", size, UiDrawCommand.TextWeight.REGULAR) > maxW) end--;
        return str.substring(0, Math.max(1, end)).stripTrailing() + "…";
    }

    private List<String> wrap(Canvas c, String str, float size, float maxW, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (str == null || str.isBlank()) return lines;
        String[] words = str.trim().split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String trial = cur.length() == 0 ? words[i] : cur + " " + words[i];
            if (c.tw(trial, size, UiDrawCommand.TextWeight.REGULAR) <= maxW || cur.length() == 0) {
                cur = new StringBuilder(trial);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(words[i]);
                if (lines.size() == maxLines) break;
            }
        }
        if (lines.size() < maxLines && cur.length() > 0) lines.add(cur.toString());
        boolean cut = lines.size() == maxLines && String.join(" ", lines).length() < str.trim().length() - 1;
        if (cut) lines.set(maxLines - 1, fit(c, lines.get(maxLines - 1) + " …", size, maxW));
        return lines;
    }

    private static String trim(String str, int max) {
        return str.length() <= max ? str : str.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int argb(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | (rgb & 0x00ffffff);
    }

    private static int mix(int from, int to, float t) {
        t = clamp(t, 0f, 1f);
        int a = Math.round(((from >>> 24) & 0xff) * (1f - t) + ((to >>> 24) & 0xff) * t);
        int r = Math.round(((from >> 16) & 0xff) * (1f - t) + ((to >> 16) & 0xff) * t);
        int g = Math.round(((from >> 8) & 0xff) * (1f - t) + ((to >> 8) & 0xff) * t);
        int bl = Math.round((from & 0xff) * (1f - t) + (to & 0xff) * t);
        return a << 24 | r << 16 | g << 8 | bl;
    }

    // =========================================================================
    // Input
    // =========================================================================

    private boolean capturing() {
        return bindingModule != null || bindingSetting != null;
    }

    private void cancelCapture() {
        if (bindingModule != null) bindingModule.setBinding(false);
        if (bindingSetting != null && bindingSetting.getModule() != null) bindingSetting.getModule().setBinding(false);
        bindingModule = null;
        bindingSetting = null;
    }

    private void applyCapture(int code) {
        Module target = bindingModule != null ? bindingModule : bindingSetting != null ? bindingSetting.getModule() : null;
        // The menu key can never be cleared, or the menu could not be opened again.
        boolean menuKey = target instanceof interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
        if (target != null && !(menuKey && code == -1)) target.setKeyCode(code);
        cancelCapture();
    }

    @Override
    public boolean isCapturingKeybind() {
        return capturing() || super.isCapturingKeybind();
    }

    private long orbitSwallowCharsUntilMs;

    private static int menuKey() {
        Module gui = interstellarTikiTiks.apexx.client.util.ModuleAccess.getModule("ClickGUI");
        int k = gui == null ? -1 : gui.getKeyCode();
        return (k == -1 || k == 0) ? GLFW.GLFW_KEY_RIGHT_SHIFT : k;
    }

    private void stopEditing() {
        if (editingString != null) editingString.setEditing(false);
        editingString = null;
        searchFocused = false;
    }

    private void beginEdit(StringSetting value) {
        stopEditing();
        editingString = value;
        value.setEditing(true);
    }

    private void applySlider(SliderSetting slider, double pointerX) {
        if (sliderWpx <= 0f) return;
        double p = clamp((float) ((pointerX - sliderPx) / sliderWpx), 0f, 1f);
        double before = slider.getValue();
        slider.setValue(slider.getMin() + p * (slider.getMax() - slider.getMin()));
        int notch = (int) Math.floor(p * 20.0);
        if (slider.getValue() != before && notch != orbitSliderNotch) {
            orbitSliderNotch = notch;
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.play(interstellarTikiTiks.apexx.client.gui.sound.UiSounds.Event.SLIDER);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double px = click.x() * ppg, py = click.y() * ppg;
        int button = click.button();
        if (capturing()) {
            if (button >= 2 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
                noteCapturedKey(-(button + 100));
                applyCapture(-(button + 100));
            }
            else cancelCapture();
            return true;
        }
        boolean wasTyping = searchFocused || editingString != null;
        panArmed = false;
        panning = false;
        flingVel = 0f; // a new touch stops a coasting list
        if (button == GLFW.GLFW_MOUSE_BUTTON_2) {
            for (int i = rightHits.size() - 1; i >= 0; i--) {
                if (rightHits.get(i).contains(px, py)) {
                    pressWithSound(rightHits.get(i).action());
                    return true;
                }
            }
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            if (wasTyping) stopEditing();
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(px, py)) {
                    pressWithSound(hits.get(i).action());
                    return true;
                }
            }
            // Nothing claimed the press: a drag from here scrolls the field or the satellite under it.
            double ux = px / s;
            if (ux >= FIELD_LEFT - 30f) {
                panArmed = true;
                panY = py;
                panSat = selected != null && ux > viewW - SIDE - SAT_W - 12f;
            }
        }
        return true;
    }

    /** Runs a click's action inside the sound bracket, so it sounds like what it changed. */
    private static void pressWithSound(Runnable action) {
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.beginPress();
        try {
            action.run();
        } finally {
            interstellarTikiTiks.apexx.client.gui.sound.UiSounds.endPress(true);
        }
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingSlider = null;
        if (panning) flingVel = ResponsiveLayout.flingFor(panVel, System.nanoTime() - panNs);
        panArmed = false;
        panning = false;
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingSlider instanceof SliderSetting slider) {
            applySlider(slider, click.x() * ppg);
            return true;
        }
        if (panArmed) {
            double py = click.y() * ppg;
            long now = System.nanoTime();
            if (!panning) {
                if (Math.abs(py - panY) <= ResponsiveLayout.DRAG_SLOP_PX * Math.max(1f, s)) return true;
                panning = true;
                panVel = 0f;
                panNs = now;
                panY = py;
                return true;
            }
            float dy = (float) (py - panY);
            panY = py;
            panVel = ResponsiveLayout.blendVelocity(panVel, dy / s, (now - panNs) / 1_000_000_000f);
            panNs = now;
            if (panSat) satScroll = Math.max(0f, satScroll - dy / s);
            else fieldScroll = Math.max(0f, fieldScroll - dy / s);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double guiX, double guiY, double horizontalAmount, double verticalAmount) {
        double px = guiX * ppg, py = guiY * ppg;
        double ux = px / s;
        if (ux < FIELD_LEFT - 30f) {
            int dir = verticalAmount > 0 ? -1 : 1;
            int idx = Math.max(0, Math.min(DIAL.length - 1, indexOf(category) + dir));
            setCategory(DIAL[idx]);
            return true;
        }
        if (selected != null && ux > viewW - SIDE - SAT_W - 12f) {
            satScroll = Math.max(0f, satScroll - (float) verticalAmount * 46f);
        } else {
            fieldScroll = Math.max(0f, fieldScroll - (float) verticalAmount * 62f);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        noteKeySeen(key);
        if (capturing()) {
            noteCapturedKey(key);
            orbitSwallowCharsUntilMs = System.currentTimeMillis() + 250L;
            if (key == GLFW.GLFW_KEY_ESCAPE) cancelCapture();
            else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) applyCapture(-1);
            else applyCapture(key);
            return true;
        }
        // A letter menu key is just a letter while a text field has focus.
        if (key == GLFW.GLFW_KEY_ESCAPE || (key == menuKey() && editingString == null && !searchFocused)) {
            if (editingString != null) {
                stopEditing();
                return true;
            }
            if (searchFocused) {
                searchFocused = false;
                return true;
            }
            close();
            return true;
        }
        boolean typing = searchFocused || editingString != null;
        if (!typing && (key == GLFW.GLFW_KEY_SLASH || (key == GLFW.GLFW_KEY_F && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0))) {
            searchFocused = true;
            swallowSlash = key == GLFW.GLFW_KEY_SLASH; // Ctrl+F produces no character event to swallow
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (editingString != null && !editingString.getValue().isEmpty()) {
                editingString.setValue(removeLast(editingString.getValue()));
                return true;
            }
            if (searchFocused && !search.isEmpty()) {
                search = removeLast(search);
                fieldScroll = 0f;
                return true;
            }
        }
        if (searchFocused && key == GLFW.GLFW_KEY_ENTER) {
            searchFocused = false;
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        int cp = input.codepoint();
        if (!Character.isValidCodePoint(cp) || Character.isISOControl(cp)) return false;
        if (orbitSwallowCharsUntilMs != 0L) {
            boolean swallow = System.currentTimeMillis() <= orbitSwallowCharsUntilMs;
            orbitSwallowCharsUntilMs = 0L;
            if (swallow) return true;
        }
        if (capturing()) return true;
        String chars = new String(Character.toChars(cp));
        if (swallowSlash) {
            swallowSlash = false;
            if (chars.equals("/") || chars.equalsIgnoreCase("f")) return true;
        }
        if (editingString != null) {
            editingString.setValue(editingString.getValue() + chars);
            return true;
        }
        if (searchFocused) {
            search += chars;
            fieldScroll = 0f;
            fieldScrollAnim = 0f;
            return true;
        }
        return false;
    }

    private static String removeLast(String value) {
        return value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }
}
