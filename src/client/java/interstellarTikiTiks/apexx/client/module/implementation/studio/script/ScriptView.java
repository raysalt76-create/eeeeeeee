package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.gui.gpuui.kit.*;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.HostApi;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptDebugger;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager.LocalScript;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager.Problem;

import java.util.List;
import java.util.Locale;

/**
 * The script IDE: your scripts on the left (or the API reference), a code editor in the middle with live
 * problem checking, and one-click save-and-apply. Scripts are the {@code module "Name" { ... }} language the
 * client already runs; saving hot-reloads the module.
 */
public final class ScriptView implements interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.Panel {
    private enum Side { SCRIPTS, API }

    private Side side = Side.SCRIPTS;
    private List<LocalScript> items = List.of();
    private boolean loaded;
    private String current;
    private String savedSource = "";
    private boolean enabled;
    private Problem problem;
    private int checkedVersion = -1, seenVersion = -1;
    private long editedAt;
    private String status = "";
    private boolean statusError;
    private long statusAt = Long.MIN_VALUE;
    private boolean deleteArmed;
    private long deleteArmedAt;

    private final UiTextField nameField = new UiTextField("Script name").maxLength(32).allow(UiTextField::isNameChar);
    private final UiTextField apiSearch = new UiTextField("Search the API").maxLength(30);
    private final UiCodeEditor editor = new UiCodeEditor();
    private final UiSmoothScroll listScroll = new UiSmoothScroll();
    private final UiSmoothScroll apiScroll = new UiSmoothScroll();
    private final float[] sideRect = new float[4];
    private float lastScale = 1f;
    private final java.util.Map<String, Boolean> brokenCache = new java.util.HashMap<>();

    // Snapshot debugger panel.
    private boolean debugOpen;
    /** Sequence number of the snapshot being viewed; -1 follows the newest. */
    private long selectedSeq = -1;
    private final UiSmoothScroll hitScroll = new UiSmoothScroll();
    private final UiSmoothScroll varScroll = new UiSmoothScroll();
    private final float[] hitRect = new float[4];
    private final float[] varRect = new float[4];

    public ScriptView() {
        editor.onSave(this::save).functions(ScriptView::knownFunction)
                .breakpoints(line -> ScriptDebugger.hasBreakpoint(debugScript(), line),
                        line -> ScriptDebugger.toggleBreakpoint(debugScript(), line));
        nameField.onSubmit(this::rename).onBlur(this::rename);
    }

    private static boolean knownFunction(String name) {
        try {
            return HostApi.isHostFunction(name);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public void refresh() {
        items = ScriptManager.getInstance().localScripts();
        loaded = true;
    }

    /** Preview/testing hook: show these scripts without touching the manager. */
    public void preview(List<LocalScript> scripts, int select, boolean showApi) {
        items = List.copyOf(scripts);
        loaded = true;
        LocalScript target = scripts.get(select);
        current = target.label();
        enabled = target.enabled();
        savedSource = target.source();
        nameField.setValue(current);
        editor.setText(target.source());
        side = showApi ? Side.API : Side.SCRIPTS;
    }

    /** Called when the menu closes so edits are never lost. */
    public void flush() {
        if (dirty()) save();
    }

    private boolean dirty() {
        return current != null && !editor.text().equals(savedSource);
    }

    private void say(String text, boolean error) {
        status = text;
        statusError = error;
        statusAt = Long.MIN_VALUE;
    }

    // ---- actions ----------------------------------------------------------------------------------

    private void select(LocalScript script) {
        if (script.label().equals(current)) return;
        if (dirty()) save();
        current = script.label();
        enabled = script.enabled();
        savedSource = script.source();
        nameField.setValue(current);
        editor.setText(script.source());
        problem = null;
        checkedVersion = -1;
        deleteArmed = false;
        selectedSeq = -1;
        hitScroll.reset();
        varScroll.reset();
    }

    /** The debugger key (live module name) of the open script, or null. */
    private String debugScript() {
        if (current == null) return null;
        try {
            return ScriptManager.getInstance().moduleNameFor(current);
        } catch (Throwable unavailable) {
            return current;
        }
    }

    private void newScript() {
        int n = items.size() + 1;
        String label;
        do {
            label = "Script " + n++;
        } while (exists(label));
        ScriptManager.getInstance().saveLocal(label, ScriptReference.starter(label), false);
        refresh();
        for (LocalScript s : items) if (s.label().equals(label)) select(s);
        side = Side.SCRIPTS;
        say("New script created. It stays off until you enable it.", false);
    }

    private void newFromTemplate(String name, String source) {
        if (dirty()) save();
        String label = name;
        int n = 2;
        while (exists(label)) label = name + " " + n++;
        ScriptManager.getInstance().saveLocal(label, source, false);
        refresh();
        for (LocalScript s : items) if (s.label().equals(label)) select(s);
        side = Side.SCRIPTS;
        say("Template \"" + name + "\" added. It stays off until you enable it.", false);
    }

    private boolean exists(String label) {
        for (LocalScript s : items) if (s.label().equalsIgnoreCase(label)) return true;
        return false;
    }

    private void save() {
        if (current == null) return;
        // The live module may have been toggled by its keybind or the module list since the last save.
        Boolean live = ScriptManager.getInstance().liveEnabledOrNull(current);
        if (live != null) enabled = live;
        saveNow();
    }

    private void saveNow() {
        if (current == null) return;
        String text = editor.text();
        ScriptManager.getInstance().saveLocal(current, text, enabled);
        savedSource = text;
        refresh();
        Problem p = ScriptManager.check(text);
        if (p == null) say("Saved and applied.", false);
        else say("Saved, but it will not run until fixed: " + p.message(), true);
    }

    private void toggleEnabled() {
        if (current == null) return;
        Boolean live = ScriptManager.getInstance().liveEnabledOrNull(current);
        if (live != null) enabled = live;
        enabled = !enabled;
        saveNow();
        say(enabled ? "Script enabled." : "Script disabled.", false);
    }

    private void delete() {
        if (current == null) return;
        deleteArmed = false;
        String module = debugScript();
        ScriptDebugger.clearBreakpoints(module);
        ScriptDebugger.clearSnapshots(module);
        ScriptManager.getInstance().deleteLocal(current);
        current = null;
        savedSource = "";
        editor.setText("");
        refresh();
        say("Script deleted.", false);
    }

    private void rename() {
        if (current == null) return;
        String wanted = nameField.value().trim();
        if (wanted.isEmpty() || wanted.equals(current)) {
            nameField.setValue(current);
            return;
        }
        if (exists(wanted) && !wanted.equalsIgnoreCase(current)) {
            say("A script called \"" + wanted + "\" already exists.", true);
            nameField.setValue(current);
            return;
        }
        String old = current;
        Boolean live = ScriptManager.getInstance().liveEnabledOrNull(old);
        if (live != null) enabled = live;
        current = wanted;
        ScriptManager.getInstance().renameLocal(old, wanted, editor.text(), enabled);
        savedSource = editor.text();
        refresh();
        say("Renamed.", false);
    }

    // ---- input ------------------------------------------------------------------------------------

    public boolean scroll(double mx, double my, double amount, long now) {
        if (debugOpen && current != null) {
            if (inside(hitRect, mx, my)) { hitScroll.wheel(amount, 40f * lastScale, now); return true; }
            if (inside(varRect, mx, my)) { varScroll.wheel(amount, 40f * lastScale, now); return true; }
        }
        if (editor.scroll(mx, my, amount, now)) return true;
        if (mx >= sideRect[0] && mx <= sideRect[0] + sideRect[2] && my >= sideRect[1] && my <= sideRect[1] + sideRect[3]) {
            (side == Side.SCRIPTS ? listScroll : apiScroll).wheel(amount, 56f * lastScale, now);
            return true;
        }
        return false;
    }

    // ---- drawing ----------------------------------------------------------------------------------

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                     float mx, float my) {
        lastScale = s;
        if (!loaded) refresh();
        long now = host.now();
        if (statusAt == Long.MIN_VALUE) statusAt = now;
        if (editor.version() != seenVersion) {
            seenVersion = editor.version();
            editedAt = now;
        }
        if (current != null && checkedVersion != editor.version() && now - editedAt > 350_000_000L) {
            problem = ScriptManager.check(editor.text());
            checkedVersion = editor.version();
            editor.setErrorLine(problem == null ? -1 : problem.line());
        }

        float padX = Math.round(22f * s);
        UiKit.title(b, host, "Scripts", x + padX, y + Math.round(20f * s), s);
        b.text(x + padX + GpuUiFontMetrics.textWidth("Scripts", 16f * s) + Math.round(12f * s), y + Math.round(24f * s),
                9.5f * s, host.muted2Color(), "Write your own modules. Save to hot-reload them.");
        UiKit.divider(b, host, x + padX, y + Math.round(56f * s), w - padX * 2f);

        float top = y + Math.round(72f * s), bottom = y + h - Math.round(16f * s);
        float sideW = Math.round(Math.min(236f * s, (w - padX * 2f) * 0.3f));
        float gap = Math.round(16f * s);
        float lx = x + padX, cx = lx + sideW + gap, cw = x + w - padX - cx;
        drawSide(b, host, lx, top, sideW, bottom - top, s, mx, my);
        if (current == null) {
            UiKit.card(b, host, cx, top, cw, bottom - top, s);
            UiKit.emptyState(b, host, "Pick a script or create one",
                    "Scripts add a module to the menu. Press New script for a working starter.",
                    cx, top + (bottom - top) * 0.5f - Math.round(34f * s), cw, s);
            float bw = UiKit.buttonWidth("New script", s);
            UiKit.button(b, host, "New script", cx + (cw - bw) * 0.5f, top + (bottom - top) * 0.5f, bw, Math.round(32f * s), s,
                    Style.PRIMARY, true, mx, my, this::newScript);
        } else {
            drawEditor(b, host, cx, top, cw, bottom - top, s, mx, my);
        }
    }

    private void drawSide(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                          float mx, float my) {
        UiKit.segmented(b, host, new String[]{"Scripts", "API"}, side.ordinal(), x, y, w, Math.round(30f * s), s, mx, my,
                i -> side = Side.values()[i]);
        float top = y + Math.round(42f * s), height = h - Math.round(42f * s);
        sideRect[0] = x; sideRect[1] = top; sideRect[2] = w; sideRect[3] = height;
        if (side == Side.SCRIPTS) drawScriptList(b, host, x, top, w, height, s, mx, my);
        else drawApi(b, host, x, top, w, height, s, mx, my);
    }

    private void drawScriptList(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                                float mx, float my) {
        UiKit.button(b, host, "+ New script", x, y, w, Math.round(32f * s), s, Style.PRIMARY, true, mx, my, this::newScript);
        float listTop = y + Math.round(44f * s), listH = h - Math.round(44f * s);
        float rowH = Math.round(46f * s), gap = Math.round(6f * s), pitch = rowH + gap;
        listScroll.update(host.frameDelta(), listH, Math.max(0f, items.size() * pitch - gap), host.now());
        float scroll = listScroll.offset();
        if (items.isEmpty()) {
            UiKit.emptyState(b, host, "No scripts yet", "Your scripts will appear here.", x, listTop + Math.round(20f * s), w, s);
        }
        b.clipPush(x, listTop, w, listH, 0);
        host.beginHitClip(x, listTop, w, listH);
        for (int i = 0; i < items.size(); i++) {
            float ry = listTop + i * pitch - scroll;
            if (ry + rowH < listTop || ry > listTop + listH) continue;
            LocalScript script = items.get(i);
            boolean sel = script.label().equals(current);
            boolean hover = host.hovered(mx, my, x, ry, w, rowH);
            float r = Math.round(10f * s);
            UiKit.card(b, host, x, ry, w, rowH, s);
            if (hover && !sel) b.rect(x, ry, w, rowH, r, host.controlFill(0x0c));
            if (sel) {
                b.rect(x, ry, w, rowH, r, UiKit.withAlpha(host.accent(), 0x14));
                b.border(x, ry, w, rowH, r, 1f, UiKit.withAlpha(host.accent(), 0xa0));
            }
            boolean broken = script.label().equals(current) ? problem != null
                    : brokenCache.computeIfAbsent(script.source(), src -> ScriptManager.check(src) != null);
            boolean live = ScriptManager.getInstance().isLiveEnabled(script.label());
            int dot = broken ? UiKit.DANGER : live ? UiKit.SUCCESS : host.controlFill(0x48);
            float tx = x + Math.round(16f * s);
            b.text(tx, ry + Math.round(10f * s), 11.5f * s, host.textColor(), UiKit.fit(script.label(), w - 50f * s, 11.5f * s),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            b.text(tx, ry + Math.round(27f * s), 9f * s, host.muted2Color(),
                    broken ? "Has a problem" : live ? "Running" : "Off");
            b.circle(x + w - Math.round(16f * s), ry + rowH * 0.5f, Math.round(3.5f * s), dot);
            host.hit(x, ry, w, rowH, () -> select(script));
        }
        host.endHitClip();
        b.clipPop();
        listScroll.paintBar(b, x + w + Math.round(2f * s), listTop, listH, s, host.textColor(), host.now());
    }

    private void drawApi(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                         float mx, float my) {
        apiSearch.draw(b, host, x, y, w, Math.round(32f * s), s);
        float listTop = y + Math.round(42f * s), listH = h - Math.round(42f * s);
        String q = apiSearch.value().trim().toLowerCase(Locale.ROOT);
        float rowH = Math.round(44f * s), headH = Math.round(24f * s);
        // First pass: total height for the scroll range.
        float total = 0f;
        String lastGroup = "";
        List<ScriptReference.Entry> shown = new java.util.ArrayList<>();
        for (ScriptReference.Entry e : ScriptReference.entries()) {
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q) && !e.doc().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
            if (!e.group().equals(lastGroup)) { total += headH; lastGroup = e.group(); }
            total += rowH;
        }
        apiScroll.update(host.frameDelta(), listH, total, host.now());
        float scroll = apiScroll.offset();
        b.clipPush(x, listTop, w, listH, 0);
        host.beginHitClip(x, listTop, w, listH);
        float cy = listTop - scroll;
        lastGroup = "";
        for (ScriptReference.Entry e : shown) {
            if (!e.group().equals(lastGroup)) {
                lastGroup = e.group();
                if (cy + headH >= listTop && cy <= listTop + listH) UiKit.label(b, host, e.group(), x + Math.round(4f * s), cy + Math.round(8f * s), s);
                cy += headH;
            }
            if (cy + rowH >= listTop && cy <= listTop + listH) {
                boolean hover = host.hovered(mx, my, x, cy, w, rowH);
                float r = Math.round(8f * s);
                if (hover) b.rect(x, cy, w, rowH - Math.round(4f * s), r, host.controlFill(0x14));
                b.text(x + Math.round(8f * s), cy + Math.round(6f * s), 10.5f * s, e.hostFunction() ? 0xff8fc7ff : host.accent(),
                        UiKit.fit(e.signature(), w - 16f * s, 10.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
                b.text(x + Math.round(8f * s), cy + Math.round(23f * s), 9f * s, host.muted2Color(),
                        UiKit.fit(e.doc(), w - 16f * s, 9f * s));
                String insert = e.insert();
                boolean template = e.group().equals("Templates");
                String templateName = e.name();
                host.hit(x, cy, w, rowH - Math.round(4f * s), () -> {
                    if (template) newFromTemplate(templateName, insert);
                    else if (current != null && !insert.isEmpty()) { editor.insert(insert); host.focus(editor); }
                });
            }
            cy += rowH;
        }
        host.endHitClip();
        b.clipPop();
        apiScroll.paintBar(b, x + w + Math.round(2f * s), listTop, listH, s, host.textColor(), host.now());
        if (current == null) {
            b.text(x, y + h - Math.round(12f * s), 9f * s, host.muted2Color(), "Open a script to insert, or pick a Template.");
        }
    }

    private void drawEditor(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                            float mx, float my) {
        // toolbar
        float barH = Math.round(34f * s);
        float nameW = Math.round(Math.min(220f * s, w * 0.34f));
        nameField.draw(b, host, x, y, nameW, barH, s);
        float tx = x + nameW + Math.round(14f * s);
        b.text(tx, GpuUiFontMetrics.topInBox(y, barH, 10f * s), 10f * s, host.mutedColor(), enabled ? "On" : "Off", UiDrawCommand.TextWeight.SEMIBOLD);
        UiKit.toggle(b, host, tx + Math.round(26f * s), y + (barH - Math.round(18f * s)) * 0.5f, s, enabled, this::toggleEnabled);

        float saveW = UiKit.buttonWidth(dirty() ? "Save and apply" : "Save", s);
        float right = x + w;
        UiKit.button(b, host, dirty() ? "Save and apply" : "Save", right - saveW, y, saveW, barH, s, Style.PRIMARY, true, mx, my, this::save);
        right -= saveW + Math.round(8f * s);
        boolean armed = deleteArmed && host.now() - deleteArmedAt < 3_500_000_000L;
        String delLabel = armed ? "Confirm delete" : "Delete";
        float delW = UiKit.buttonWidth("Confirm delete", s);
        UiKit.button(b, host, delLabel, right - delW, y, delW, barH, s, Style.DANGER, true, mx, my, () -> {
            if (armed) delete();
            else { deleteArmed = true; deleteArmedAt = host.now(); }
        });
        right -= delW + Math.round(8f * s);
        String dbgLabel = debugOpen ? "Hide debug" : "Debug";
        float dbgW = UiKit.buttonWidth("Hide debug", s);
        UiKit.button(b, host, dbgLabel, right - dbgW, y, dbgW, barH, s, debugOpen ? Style.SECONDARY : Style.GHOST, true, mx, my,
                () -> debugOpen = !debugOpen);

        float probH = Math.round(44f * s);
        float edY = y + barH + Math.round(12f * s);
        float edH = h - barH - Math.round(12f * s) - probH - Math.round(10f * s);
        float edW = w;
        if (debugOpen) {
            float gap = Math.round(12f * s);
            float panelW = Math.round(Math.max(220f * s, Math.min(320f * s, w * 0.36f)));
            edW = w - panelW - gap;
            drawDebug(b, host, x + edW + gap, edY, panelW, edH, s, mx, my);
        } else {
            editor.setDebugLine(-1);
            hitRect[2] = varRect[2] = 0f;
        }
        editor.draw(b, host, x, edY, edW, edH, s);
        drawProblems(b, host, x, edY + edH + Math.round(10f * s), w, probH, s);
    }

    private static boolean inside(float[] r, double mx, double my) {
        return r[2] > 0f && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    // ---- snapshot debugger ---------------------------------------------------------------------------

    private void drawDebug(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                           float mx, float my) {
        UiKit.card(b, host, x, y, w, h, s);
        String module = debugScript();
        List<ScriptDebugger.Snapshot> snaps = ScriptDebugger.snapshots(module);
        int idx = snaps.size() - 1;
        if (selectedSeq >= 0) {
            int found = -1;
            for (int i = 0; i < snaps.size(); i++) if (snaps.get(i).seq() == selectedSeq) { found = i; break; }
            if (found < 0) selectedSeq = -1; else idx = found;
        }
        ScriptDebugger.Snapshot sel = idx >= 0 ? snaps.get(idx) : null;
        editor.setDebugLine(sel == null ? -1 : sel.line());

        float p = Math.round(12f * s);
        float cx = x + p, cw = w - p * 2f;
        float cy = y + p;
        UiKit.label(b, host, "Debugger", cx, cy + Math.round(4f * s), s);
        boolean on = ScriptDebugger.isEnabled();
        UiKit.toggle(b, host, cx + cw - Math.round(34f * s), cy, s, on, () -> ScriptDebugger.setEnabled(!ScriptDebugger.isEnabled()));
        b.text(cx + cw - Math.round(34f * s) - Math.round(26f * s), cy + Math.round(4f * s), 9.5f * s, host.mutedColor(),
                on ? "On" : "Off", UiDrawCommand.TextWeight.SEMIBOLD);
        cy += Math.round(28f * s);

        // Prev / Next / Clear
        float bh = Math.round(26f * s), bg = Math.round(6f * s);
        float bw = (cw - bg * 2f) / 3f;
        final int at = idx;
        boolean canPrev = sel != null && at > 0, canNext = sel != null && at < snaps.size() - 1;
        UiKit.button(b, host, "Prev", cx, cy, bw, bh, s, Style.SECONDARY, canPrev, mx, my, () -> stepTo(snaps, at - 1));
        UiKit.button(b, host, "Next", cx + bw + bg, cy, bw, bh, s, Style.SECONDARY, canNext, mx, my, () -> stepTo(snaps, at + 1));
        UiKit.button(b, host, "Clear", cx + (bw + bg) * 2f, cy, bw, bh, s, Style.GHOST, !snaps.isEmpty(), mx, my, () -> {
            ScriptDebugger.clearSnapshots(module);
            selectedSeq = -1;
        });
        cy += bh + Math.round(10f * s);

        int bps = ScriptDebugger.breakpoints(module).size();
        String status;
        if (!on) status = "Off. Turn it on to record snapshots.";
        else if (bps == 0) status = "Click a line number to add a breakpoint.";
        else if (sel == null) status = bps + " breakpoint" + (bps == 1 ? "" : "s") + ". Waiting for one to run.";
        else status = "Hit " + (idx + 1) + " of " + snaps.size() + (selectedSeq < 0 ? " (newest)" : "")
                + "  |  " + bps + " breakpoint" + (bps == 1 ? "" : "s");
        b.text(cx, cy, 9f * s, host.muted2Color(), UiKit.fit(status, cw, 9f * s));
        cy += Math.round(18f * s);

        // Hit list
        float listH = Math.round(Math.max(60f * s, (y + h - p - cy) * 0.34f));
        float rowH = Math.round(20f * s);
        hitRect[0] = cx; hitRect[1] = cy; hitRect[2] = cw; hitRect[3] = listH;
        hitScroll.update(host.frameDelta(), listH, snaps.size() * rowH, host.now());
        float off = hitScroll.offset();
        long nowMs = System.currentTimeMillis();
        b.clipPush(cx, cy, cw, listH, 0);
        host.beginHitClip(cx, cy, cw, listH);
        // Newest first.
        for (int k = 0; k < snaps.size(); k++) {
            int i = snaps.size() - 1 - k;
            float ry = cy + k * rowH - off;
            if (ry + rowH < cy || ry > cy + listH) continue;
            ScriptDebugger.Snapshot snap = snaps.get(i);
            boolean isSel = i == idx;
            if (isSel) b.rect(cx, ry, cw, rowH, Math.round(6f * s), UiKit.withAlpha(host.accent(), 0x22));
            else if (host.hovered(mx, my, cx, ry, cw, rowH)) b.rect(cx, ry, cw, rowH, Math.round(6f * s), host.controlFill(0x10));
            String row = "line " + snap.line() + "  " + snap.event() + "  " + age(nowMs - snap.timeMs());
            b.text(cx + Math.round(6f * s), ry + (rowH - 9.5f * s) * 0.5f, 9.5f * s, isSel ? host.textColor() : host.mutedColor(),
                    UiKit.fit(row, cw - 12f * s, 9.5f * s));
            final long seq = snap.seq();
            final int line = snap.line();
            host.hit(cx, ry, cw, rowH, () -> {
                selectedSeq = seq;
                editor.goToLine(line);
            });
        }
        host.endHitClip();
        b.clipPop();
        cy += listH + Math.round(10f * s);
        UiKit.divider(b, host, cx, cy - Math.round(5f * s), cw);

        // Variables of the selected snapshot
        float varH = y + h - p - cy;
        varRect[0] = cx; varRect[1] = cy; varRect[2] = cw; varRect[3] = Math.max(0f, varH);
        if (sel == null || varH <= 0f) {
            varScroll.update(host.frameDelta(), Math.max(1f, varH), 0f, host.now());
            return;
        }
        float vRow = Math.round(17f * s), head = Math.round(20f * s);
        String[] titles = {"Locals", "Module vars", "Settings"};
        List<List<ScriptDebugger.Var>> groups = List.of(sel.locals(), sel.vars(), sel.settings());
        float total = 0f;
        for (List<ScriptDebugger.Var> g : groups) total += head + Math.max(1, g.size()) * vRow;
        varScroll.update(host.frameDelta(), varH, total, host.now());
        float vy = cy - varScroll.offset();
        b.clipPush(cx, cy, cw, varH, 0);
        float nameW = Math.round(cw * 0.4f);
        for (int gi = 0; gi < groups.size(); gi++) {
            List<ScriptDebugger.Var> g = groups.get(gi);
            if (vy + head >= cy && vy <= cy + varH) UiKit.label(b, host, titles[gi], cx, vy + Math.round(5f * s), s);
            vy += head;
            if (g.isEmpty()) {
                if (vy + vRow >= cy && vy <= cy + varH) b.text(cx + Math.round(6f * s), vy, 9f * s, host.muted2Color(), "none");
                vy += vRow;
                continue;
            }
            for (ScriptDebugger.Var v : g) {
                if (vy + vRow >= cy && vy <= cy + varH) {
                    b.text(cx + Math.round(6f * s), vy, 9.5f * s, host.mutedColor(), UiKit.fit(v.name(), nameW - 10f * s, 9.5f * s));
                    b.text(cx + nameW, vy, 9.5f * s, host.textColor(), UiKit.fit(v.value(), cw - nameW, 9.5f * s));
                }
                vy += vRow;
            }
        }
        b.clipPop();
        varScroll.paintBar(b, cx + cw + Math.round(4f * s), cy, varH, s, host.textColor(), host.now());
    }

    private void stepTo(List<ScriptDebugger.Snapshot> snaps, int index) {
        if (index < 0 || index >= snaps.size()) return;
        ScriptDebugger.Snapshot snap = snaps.get(index);
        selectedSeq = snap.seq();
        editor.goToLine(snap.line());
    }

    private static String age(long ms) {
        if (ms < 1000) return ms + " ms ago";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1f s ago", ms / 1000.0);
        return (ms / 60_000) + " min ago";
    }

    private void drawProblems(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s) {
        UiKit.card(b, host, x, y, w, h, s);
        float p = Math.round(16f * s);
        Problem runtime = problem == null && current != null && !dirty()
                ? ScriptManager.getInstance().runtimeErrorFor(current) : null;
        if (runtime != null) {
            b.circle(x + p, y + h * 0.5f, Math.round(4f * s), 0xffffb454);
            b.text(x + p + Math.round(14f * s), GpuUiFontMetrics.topInBox(y, h, 10.5f * s), 10.5f * s, 0xffffc27a,
                    UiKit.fit("While running: " + runtime.message(), w - p * 2f - 14f * s, 10.5f * s),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            if (runtime.line() > 0) {
                int line = runtime.line();
                host.hit(x, y, w, h, () -> editor.goToLine(line));
            }
            return;
        }
        boolean ok = problem == null;
        b.circle(x + p, y + h * 0.5f, Math.round(4f * s), ok ? UiKit.SUCCESS : UiKit.DANGER);
        String text;
        int color;
        boolean fresh = !status.isEmpty() && host.now() - statusAt < 5_000_000_000L;
        if (fresh) {
            text = status;
            color = statusError ? UiKit.DANGER : host.accent();
        } else if (ok) {
            text = dirty() ? "No problems. You have unsaved changes (Ctrl+S)." : "No problems.";
            color = host.mutedColor();
        } else {
            text = problem.message();
            color = 0xffff9a9a;
        }
        b.text(x + p + Math.round(14f * s), GpuUiFontMetrics.topInBox(y, h, 10.5f * s), 10.5f * s, color,
                UiKit.fit(text, w - p * 2f - 14f * s, 10.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
        if (!ok && problem.line() > 0) {
            int line = problem.line();
            host.hit(x, y, w, h, () -> editor.goToLine(line));
        }
    }
}
