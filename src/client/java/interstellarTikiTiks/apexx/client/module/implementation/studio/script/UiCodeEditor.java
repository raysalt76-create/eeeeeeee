package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.gui.gpuui.kit.*;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Multi-line code editor for the script IDE: caret and selection, undo/redo, clipboard, auto-indent,
 * comment toggling, line numbers, syntax colouring and an error marker. It draws with the menu's own
 * font, so columns are measured rather than assumed fixed-width.
 */
public final class UiCodeEditor implements UiTextTarget {
    private static final int MAX_LINES = 2000;
    private static final int MAX_CHARS = 60_000;
    private static final int UNDO_LIMIT = 120;
    private static final Set<String> KEYWORDS = Set.of("module", "category", "description", "setting", "var", "let",
            "on", "fn", "after", "slider", "toggle", "mode", "range", "step", "of", "if", "else", "while", "for", "in",
            "break", "continue", "return", "and", "or", "not", "true", "false", "null",
            "type", "key", "jitter", "bindable", "color", "oneshot", "trigger");
    private static final Set<String> EVENTS = interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptEventCatalog.all().keySet();

    private record Run(String text, int kind) { }

    private record Snapshot(List<String> lines, int line, int col) { }

    private static final int K_TEXT = 0, K_KEYWORD = 1, K_FUNCTION = 2, K_STRING = 3, K_NUMBER = 4, K_COMMENT = 5, K_PUNCT = 6;

    private final ArrayList<String> lines = new ArrayList<>(List.of(""));
    private int line, col;
    private int anchorLine = -1, anchorCol;
    private final UiSmoothScroll scrollY = new UiSmoothScroll();
    private float scrollX;
    private int version;
    private boolean caretMoved = true;
    private long caretBlinkAt;
    private boolean dragging;
    private long lastTypeNanos;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Runnable onSave;
    private Predicate<String> isFunction = name -> false;
    private int errorLine = -1;
    /** 0-based line the debugger's selected snapshot was taken on, or -1. */
    private int debugLine = -1;
    /** 1-based breakpoint lookup and toggle; null = no breakpoint gutter. */
    private java.util.function.IntPredicate breakpointAt;
    private java.util.function.IntConsumer toggleBreakpoint;

    private UiHost lastHost;
    private float vx, vy, vw, vh, textX, lineH, fontSize;
    private final Map<String, List<Run>> runCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Run>> eldest) { return size() > 400; }
    };

    public UiCodeEditor onSave(Runnable action) { this.onSave = action; return this; }

    public UiCodeEditor functions(Predicate<String> known) { this.isFunction = known; return this; }

    /** Enables gutter breakpoints: clicking a line number calls {@code toggle} with the 1-based line. */
    public UiCodeEditor breakpoints(java.util.function.IntPredicate has, java.util.function.IntConsumer toggle) {
        this.breakpointAt = has;
        this.toggleBreakpoint = toggle;
        return this;
    }

    /** 1-based line to mark as the debugger's current snapshot, or -1. */
    public void setDebugLine(int oneBased) { this.debugLine = oneBased - 1; }

    public int version() { return version; }

    public String text() { return String.join("\n", lines); }

    public void setText(String text) {
        lines.clear();
        String normalised = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "  ");
        if (normalised.length() > MAX_CHARS) normalised = normalised.substring(0, MAX_CHARS);
        for (String l : normalised.split("\n", -1)) {
            if (lines.size() >= MAX_LINES) break;
            lines.add(l);
        }
        if (lines.isEmpty()) lines.add("");
        line = 0;
        col = 0;
        anchorLine = -1;
        undo.clear();
        redo.clear();
        scrollY.reset();
        scrollX = 0f;
        errorLine = -1;
        version++;
        caretMoved = true;
    }

    /** 1-based line to mark as having a problem, or -1. */
    public void setErrorLine(int oneBased) { this.errorLine = oneBased - 1; }

    public int lineCount() { return lines.size(); }

    public void goToLine(int oneBased) {
        line = Math.max(0, Math.min(lines.size() - 1, oneBased - 1));
        col = Math.min(col, lines.get(line).length());
        anchorLine = -1;
        caretMoved = true;
    }

    /** Insert text at the caret (used by the API reference). */
    public void insert(String text) {
        pushUndo(true);
        replaceSelectionWith(text);
    }

    // ------------------------------------------------------------------------------------------ drawing

    public boolean scroll(double mx, double my, double amount, long now) {
        if (mx < vx || mx > vx + vw + 60f || my < vy || my > vy + vh) return false;
        scrollY.wheel(amount, lineH * 3f, now);
        return true;
    }

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s) {
        lastHost = host;
        float r = Math.round(10f * s);
        b.rect(x, y, w, h, r, UiKit.argb(0x55, 0x000000));
        b.border(x, y, w, h, r, 1f, 0x1effffff);

        fontSize = 11.5f * s;
        lineH = Math.round(18f * s);
        float pad = Math.round(8f * s);
        float gutterW = Math.round(GpuUiFontMetrics.textWidth(Integer.toString(Math.max(10, lines.size())), fontSize) + 28f * s);
        vx = x + gutterW;
        vy = y + pad;
        vw = w - gutterW - pad;
        vh = h - pad * 2f;
        textX = vx + Math.round(10f * s);

        b.rect(x + 1f, y + 1f, gutterW - 1f, h - 2f, r, UiKit.argb(0x22, 0x000000));
        b.rect(x + gutterW, y + 1f, 1f, h - 2f, 0, 0x14ffffff);

        scrollY.update(host.frameDelta(), vh, lines.size() * lineH + lineH * 2f, host.now());
        if (caretMoved) {
            ensureCaretVisible();
            caretMoved = false;
        }
        float scroll = scrollY.offset();
        int first = Math.max(0, (int) Math.floor(scroll / lineH));
        int last = Math.min(lines.size() - 1, (int) Math.ceil((scroll + vh) / lineH));

        int[] sel = selection();
        int accent = host.accent();
        boolean focus = host.focused() == this;

        b.clipPush(x + 1f, vy, w - 2f, vh, 0);
        for (int i = first; i <= last; i++) {
            float ly = vy + i * lineH - scroll;
            if (i == line && focus) b.rect(vx, ly, vw, lineH, 0, UiKit.argb(0x0c, 0xffffff));
            if (i == debugLine) b.rect(vx, ly, vw, lineH, 0, UiKit.withAlpha(accent, 0x26));
            if (i == errorLine) {
                b.rect(vx, ly, vw, lineH, 0, UiKit.withAlpha(UiKit.DANGER, 0x1c));
                b.rect(x + 1f, ly, Math.round(3f * s), lineH, 0, UiKit.DANGER);
            }
            // selection highlight
            if (sel != null && i >= sel[0] && i <= sel[2]) {
                String text = lines.get(i);
                int c0 = i == sel[0] ? sel[1] : 0, c1 = i == sel[2] ? sel[3] : text.length();
                float x0 = textX - scrollX + width(text.substring(0, Math.min(c0, text.length())));
                float x1 = textX - scrollX + width(text.substring(0, Math.min(c1, text.length())))
                        + (i < sel[2] ? fontSize * 0.35f : 0f);
                if (x1 > x0) b.rect(x0, ly, x1 - x0, lineH, 0, UiKit.withAlpha(accent, 0x40));
            }
            // text
            float cx = textX - scrollX;
            for (Run run : runs(lines.get(i))) {
                float rw = width(run.text());
                if (cx + rw >= vx && cx <= vx + vw && !run.text().isBlank()) {
                    b.text(cx, ly + (lineH - fontSize) * 0.5f, fontSize, colour(host, run.kind()), run.text());
                }
                cx += rw;
            }
        }
        // caret
        if (focus && ((host.now() - caretBlinkAt) / 530_000_000L) % 2 == 0) {
            float cy = vy + line * lineH - scroll;
            if (cy + lineH >= vy && cy <= vy + vh) {
                float cx = textX - scrollX + width(lines.get(line).substring(0, Math.min(col, lines.get(line).length())));
                b.rect(Math.round(cx), cy + Math.round(2f * s), 1f, lineH - Math.round(4f * s), 0, host.textColor());
            }
        }
        b.clipPop();

        // line numbers (own clip so they never draw over the text)
        b.clipPush(x + 1f, vy, gutterW - 1f, vh, 0);
        for (int i = first; i <= last; i++) {
            float ly = vy + i * lineH - scroll;
            String n = Integer.toString(i + 1);
            if (breakpointAt != null && breakpointAt.test(i + 1)) {
                b.circle(x + Math.round(9f * s), ly + lineH * 0.5f, Math.round(3.5f * s), 0xffff5c5c);
            }
            int numColour = i == errorLine ? UiKit.DANGER : i == debugLine ? accent : i == line ? host.mutedColor() : host.muted2Color();
            b.text(x + gutterW - Math.round(12f * s) - GpuUiFontMetrics.textWidth(n, fontSize * 0.9f),
                    ly + (lineH - fontSize * 0.9f) * 0.5f, fontSize * 0.9f, numColour, n);
        }
        b.clipPop();
        scrollY.paintBar(b, x + w - Math.round(7f * s), vy, vh, s, host.textColor(), host.now());

        // pointer
        host.hit(x, y, w, h, () -> {
            if (host.focused() != this) host.focus(this);
            int[] at = posAt((float) host.pressX(), (float) host.pressY());
            line = at[0];
            col = at[1];
            anchorLine = line;
            anchorCol = col;
            dragging = true;
            caretBlinkAt = host.now();
        });
        // Gutter: toggles a breakpoint. Registered after the text hit so it wins over it.
        if (toggleBreakpoint != null) {
            host.hit(x, vy, gutterW, vh, () -> {
                int ln = (int) Math.floor(((float) host.pressY() - vy + scrollY.offset()) / Math.max(1f, lineH));
                if (ln >= 0 && ln < lines.size()) toggleBreakpoint.accept(ln + 1);
            });
        }
    }

    private int colour(UiHost host, int kind) {
        return switch (kind) {
            case K_KEYWORD -> host.accent();
            case K_FUNCTION -> 0xff8fc7ff;
            case K_STRING -> 0xffe9c27b;
            case K_NUMBER -> 0xff7ee0c3;
            case K_COMMENT -> host.muted2Color();
            case K_PUNCT -> host.mutedColor();
            default -> host.textColor();
        };
    }

    private float width(String text) {
        return GpuUiFontMetrics.textWidth(text, fontSize);
    }

    private void ensureCaretVisible() {
        float top = line * lineH;
        float target = scrollY.target();
        if (top < target) scrollY.scrollTo(top, false);
        else if (top + lineH > target + vh) scrollY.scrollTo(top + lineH - vh, false);
        float cx = width(lines.get(line).substring(0, Math.min(col, lines.get(line).length())));
        float viewW = Math.max(40f, vw - 24f);
        if (cx - scrollX > viewW) scrollX = cx - viewW;
        if (cx - scrollX < 0f) scrollX = Math.max(0f, cx - 20f);
    }

    /** Maps a pointer position (framebuffer pixels) to a (line, column). */
    private int[] posAt(float px, float py) {
        int ln = Math.max(0, Math.min(lines.size() - 1, (int) Math.floor((py - vy + scrollY.offset()) / lineH)));
        String text = lines.get(ln);
        float target = px - textX + scrollX;
        float acc = 0f;
        int c = 0;
        while (c < text.length()) {
            float cw = width(text.substring(c, c + 1));
            if (acc + cw * 0.5f >= target) break;
            acc += cw;
            c++;
        }
        return new int[]{ln, c};
    }

    // ------------------------------------------------------------------------------------------ highlighting

    private List<Run> runs(String text) {
        return runCache.computeIfAbsent(text, this::tokenize);
    }

    private List<Run> tokenize(String text) {
        List<Run> out = new ArrayList<>();
        int i = 0, n = text.length();
        boolean afterOn = false;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '#') {
                out.add(new Run(text.substring(i), K_COMMENT));
                break;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '"') j += text.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(n, j + 1);
                out.add(new Run(text.substring(i, j), K_STRING));
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isDigit(text.charAt(j)) || (text.charAt(j) == '.' && j + 1 < n && Character.isDigit(text.charAt(j + 1))))) j++;
                out.add(new Run(text.substring(i, j), K_NUMBER));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
                String word = text.substring(i, j);
                int k = j;
                while (k < n && text.charAt(k) == ' ') k++;
                boolean call = k < n && text.charAt(k) == '(';
                int kind;
                if (KEYWORDS.contains(word)) kind = K_KEYWORD;
                else if (afterOn && EVENTS.contains(word)) kind = K_KEYWORD;
                else if (call && isFunction.test(word)) kind = K_FUNCTION;
                else kind = K_TEXT;
                afterOn = word.equals("on");
                out.add(new Run(word, kind));
                i = j;
                continue;
            }
            int j = i;
            while (j < n) {
                char d = text.charAt(j);
                if (Character.isLetterOrDigit(d) || d == '_' || d == '"' || d == '#') break;
                j++;
            }
            if (j == i) j = i + 1;
            String punct = text.substring(i, j);
            out.add(new Run(punct, punct.isBlank() ? K_TEXT : K_PUNCT));
            i = j;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ editing

    private int[] selection() {
        if (anchorLine < 0) return null;
        // Defensive clamp: an edit that shortened a line must never leave the anchor past its end.
        if (anchorLine >= lines.size()) { anchorLine = -1; return null; }
        anchorCol = Math.max(0, Math.min(anchorCol, lines.get(anchorLine).length()));
        if (line >= 0 && line < lines.size()) col = Math.max(0, Math.min(col, lines.get(line).length()));
        if (anchorLine == line && anchorCol == col) return null;
        boolean forward = anchorLine < line || (anchorLine == line && anchorCol <= col);
        return forward ? new int[]{anchorLine, anchorCol, line, col} : new int[]{line, col, anchorLine, anchorCol};
    }

    private String selectedText() {
        int[] sel = selection();
        if (sel == null) return "";
        if (sel[0] == sel[2]) return lines.get(sel[0]).substring(sel[1], sel[3]);
        StringBuilder sb = new StringBuilder(lines.get(sel[0]).substring(sel[1]));
        for (int i = sel[0] + 1; i < sel[2]; i++) sb.append('\n').append(lines.get(i));
        sb.append('\n').append(lines.get(sel[2]), 0, sel[3]);
        return sb.toString();
    }

    private void pushUndo(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastTypeNanos < 700_000_000L && !undo.isEmpty()) {
            lastTypeNanos = now;
            return;
        }
        lastTypeNanos = now;
        undo.push(new Snapshot(new ArrayList<>(lines), line, col));
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
    }

    private void replaceSelectionWith(String text) {
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "  ");
        int[] sel = selection();
        int removedChars = 0, removedLines = 0;
        if (sel != null) {
            removedLines = sel[2] - sel[0];
            for (int i = sel[0]; i <= sel[2]; i++) {
                int start = i == sel[0] ? sel[1] : 0;
                int end = i == sel[2] ? sel[3] : lines.get(i).length();
                removedChars += end - start + (i < sel[2] ? 1 : 0);
            }
        }
        String[] parts = text.split("\n", -1);
        // Reject a paste before modifying the selection, so limits cannot destroy the draft.
        if (totalChars() - removedChars + text.length() > MAX_CHARS
                || lines.size() - removedLines + parts.length - 1 > MAX_LINES) return;
        if (sel != null) {
            String head = lines.get(sel[0]).substring(0, sel[1]);
            String tail = lines.get(sel[2]).substring(sel[3]);
            for (int i = sel[2]; i > sel[0]; i--) lines.remove(i);
            lines.set(sel[0], head + tail);
            line = sel[0];
            col = sel[1];
            anchorLine = -1;
        }
        if (text.isEmpty()) { touched(); return; }
        String current = lines.get(line);
        String head = current.substring(0, col), tail = current.substring(col);
        if (parts.length == 1) {
            lines.set(line, head + parts[0] + tail);
            col += parts[0].length();
        } else {
            lines.set(line, head + parts[0]);
            for (int i = 1; i < parts.length; i++) {
                lines.add(line + i, i == parts.length - 1 ? parts[i] + tail : parts[i]);
            }
            line += parts.length - 1;
            col = parts[parts.length - 1].length();
        }
        touched();
    }

    private int totalChars() {
        int n = 0;
        for (String l : lines) n += l.length() + 1;
        return n;
    }

    private void touched() {
        version++;
        caretMoved = true;
        if (lastHost != null) caretBlinkAt = lastHost.now();
    }

    @Override
    public boolean charTyped(String chars) {
        StringBuilder add = new StringBuilder();
        chars.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(add::appendCodePoint);
        if (add.length() == 0) return true;
        pushUndo(false);
        String typed = add.toString();
        // Typing a closing brace on an otherwise-empty line steps it back one indent level.
        if (typed.equals("}") && selection() == null && lines.get(line).substring(0, col).isBlank()
                && lines.get(line).startsWith("  ") && col >= 2) {
            String l = lines.get(line);
            lines.set(line, l.substring(2));
            col -= 2;
        }
        replaceSelectionWith(typed);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_S -> { if (onSave != null) onSave.run(); return true; }
                case GLFW.GLFW_KEY_A -> { anchorLine = 0; anchorCol = 0; line = lines.size() - 1; col = lines.get(line).length(); caretMoved = true; return true; }
                case GLFW.GLFW_KEY_C -> { copy(); return true; }
                case GLFW.GLFW_KEY_X -> { copy(); if (selection() != null) { pushUndo(true); replaceSelectionWith(""); } return true; }
                case GLFW.GLFW_KEY_V -> { paste(); return true; }
                case GLFW.GLFW_KEY_Z -> { if (shift) redo(); else undo(); return true; }
                case GLFW.GLFW_KEY_Y -> { redo(); return true; }
                case GLFW.GLFW_KEY_SLASH -> { toggleComment(); return true; }
                default -> { }
            }
        }
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> move(shift, ctrl ? wordLeft() : prevPos(), true);
            case GLFW.GLFW_KEY_RIGHT -> move(shift, ctrl ? wordRight() : nextPos(), false);
            case GLFW.GLFW_KEY_UP -> move(shift, new int[]{Math.max(0, line - 1), col}, null);
            case GLFW.GLFW_KEY_DOWN -> move(shift, new int[]{Math.min(lines.size() - 1, line + 1), col}, null);
            case GLFW.GLFW_KEY_HOME -> move(shift, new int[]{ctrl ? 0 : line, 0}, null);
            case GLFW.GLFW_KEY_END -> move(shift, ctrl ? new int[]{lines.size() - 1, lines.get(lines.size() - 1).length()}
                    : new int[]{line, lines.get(line).length()}, null);
            case GLFW.GLFW_KEY_PAGE_UP -> move(shift, new int[]{Math.max(0, line - pageLines()), col}, null);
            case GLFW.GLFW_KEY_PAGE_DOWN -> move(shift, new int[]{Math.min(lines.size() - 1, line + pageLines()), col}, null);
            case GLFW.GLFW_KEY_BACKSPACE -> backspace(ctrl);
            case GLFW.GLFW_KEY_DELETE -> delete(ctrl);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> newline();
            case GLFW.GLFW_KEY_TAB -> tab(shift);
            default -> { return key != GLFW.GLFW_KEY_ESCAPE; }
        }
        return true;
    }

    private int pageLines() { return Math.max(1, (int) (vh / Math.max(1f, lineH)) - 1); }

    private int[] prevPos() {
        if (col > 0) return new int[]{line, col - 1};
        if (line > 0) return new int[]{line - 1, lines.get(line - 1).length()};
        return new int[]{0, 0};
    }

    private int[] nextPos() {
        if (col < lines.get(line).length()) return new int[]{line, col + 1};
        if (line < lines.size() - 1) return new int[]{line + 1, 0};
        return new int[]{line, col};
    }

    private int[] wordLeft() {
        if (col == 0) return prevPos();
        String t = lines.get(line);
        int c = col;
        while (c > 0 && !Character.isLetterOrDigit(t.charAt(c - 1))) c--;
        while (c > 0 && Character.isLetterOrDigit(t.charAt(c - 1))) c--;
        return new int[]{line, c};
    }

    private int[] wordRight() {
        String t = lines.get(line);
        if (col >= t.length()) return nextPos();
        int c = col;
        while (c < t.length() && !Character.isLetterOrDigit(t.charAt(c))) c++;
        while (c < t.length() && Character.isLetterOrDigit(t.charAt(c))) c++;
        return new int[]{line, c};
    }

    /** Moves the caret; {@code leftRight} is TRUE/FALSE for horizontal keys so a plain press collapses a selection. */
    private void move(boolean extend, int[] to, Boolean leftRight) {
        int[] sel = selection();
        if (!extend && sel != null && leftRight != null) {
            if (leftRight) { line = sel[0]; col = sel[1]; } else { line = sel[2]; col = sel[3]; }
            anchorLine = -1;
        } else {
            if (extend && anchorLine < 0) { anchorLine = line; anchorCol = col; }
            if (!extend) anchorLine = -1;
            line = to[0];
            col = Math.min(to[1], lines.get(line).length());
        }
        touchedCaret();
    }

    private void touchedCaret() {
        caretMoved = true;
        if (lastHost != null) caretBlinkAt = lastHost.now();
    }

    private void backspace(boolean word) {
        pushUndo(false);
        if (selection() != null) { replaceSelectionWith(""); return; }
        if (col == 0 && line == 0) return;
        if (col == 0) {
            int prevLen = lines.get(line - 1).length();
            lines.set(line - 1, lines.get(line - 1) + lines.get(line));
            lines.remove(line);
            line--;
            col = prevLen;
        } else {
            String t = lines.get(line);
            int cut = word ? wordLeft()[1] : col - 1;
            // In leading whitespace, backspace removes a whole indent step.
            if (!word && t.substring(0, col).isBlank() && col >= 2 && col % 2 == 0) cut = col - 2;
            lines.set(line, t.substring(0, cut) + t.substring(col));
            col = cut;
        }
        touched();
    }

    private void delete(boolean word) {
        pushUndo(false);
        if (selection() != null) { replaceSelectionWith(""); return; }
        String t = lines.get(line);
        if (col < t.length()) {
            int end = word ? wordRight()[1] : col + 1;
            lines.set(line, t.substring(0, col) + t.substring(end));
        } else if (line < lines.size() - 1) {
            lines.set(line, t + lines.get(line + 1));
            lines.remove(line + 1);
        } else {
            return;
        }
        touched();
    }

    private void newline() {
        pushUndo(true);
        String current = lines.get(line);
        String before = current.substring(0, Math.min(col, current.length()));
        int indent = 0;
        while (indent < before.length() && before.charAt(indent) == ' ') indent++;
        String trimmed = before.stripTrailing();
        if (trimmed.endsWith("{")) indent += 2;
        replaceSelectionWith("\n" + " ".repeat(indent));
    }

    private void tab(boolean outdent) {
        int[] sel = selection();
        pushUndo(true);
        if (sel != null && sel[0] != sel[2]) {
            for (int i = sel[0]; i <= sel[2]; i++) {
                String t = lines.get(i);
                if (outdent) {
                    int strip = t.startsWith("  ") ? 2 : t.startsWith(" ") ? 1 : 0;
                    lines.set(i, t.substring(strip));
                    if (i == line) col = Math.max(0, col - strip);
                    if (i == anchorLine) anchorCol = Math.max(0, anchorCol - strip);
                } else {
                    lines.set(i, "  " + t);
                    if (i == line) col += 2;
                    if (i == anchorLine) anchorCol += 2;
                }
            }
            touched();
        } else if (outdent) {
            String t = lines.get(line);
            int strip = t.startsWith("  ") ? 2 : t.startsWith(" ") ? 1 : 0;
            lines.set(line, t.substring(strip));
            col = Math.max(0, col - strip);
            touched();
        } else {
            replaceSelectionWith("  ");
        }
    }

    private void toggleComment() {
        int[] sel = selection();
        int from = sel == null ? line : sel[0], to = sel == null ? line : sel[2];
        boolean allCommented = true;
        for (int i = from; i <= to; i++) {
            String t = lines.get(i).stripLeading();
            if (!t.isEmpty() && !t.startsWith("#")) { allCommented = false; break; }
        }
        pushUndo(true);
        for (int i = from; i <= to; i++) {
            String t = lines.get(i);
            int indent = t.length() - t.stripLeading().length();
            int delta = 0;
            if (allCommented) {
                String body = t.substring(indent);
                int before = body.length();
                if (body.startsWith("# ")) body = body.substring(2); else if (body.startsWith("#")) body = body.substring(1);
                lines.set(i, t.substring(0, indent) + body);
                delta = body.length() - before;
            } else if (!t.isBlank()) {
                lines.set(i, t.substring(0, indent) + "# " + t.substring(indent));
                delta = 2;
            }
            if (delta != 0) {
                if (i == line && col >= indent) col = Math.max(indent, col + delta);
                if (i == anchorLine && anchorCol >= indent) anchorCol = Math.max(indent, anchorCol + delta);
            }
        }
        col = Math.max(0, Math.min(col, lines.get(line).length()));
        if (anchorLine >= 0) {
            if (anchorLine >= lines.size()) anchorLine = -1;
            else anchorCol = Math.max(0, Math.min(anchorCol, lines.get(anchorLine).length()));
        }
        touched();
    }

    private void copy() {
        String text = selectedText();
        if (lastHost != null && !text.isEmpty()) lastHost.setClipboard(text);
    }

    private void paste() {
        if (lastHost == null) return;
        String clip = lastHost.clipboard();
        if (clip == null || clip.isEmpty()) return;
        pushUndo(true);
        replaceSelectionWith(clip);
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(new Snapshot(new ArrayList<>(lines), line, col));
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(new Snapshot(new ArrayList<>(lines), line, col));
        restore(redo.pop());
    }

    private void restore(Snapshot snap) {
        lines.clear();
        lines.addAll(snap.lines());
        line = Math.min(snap.line(), lines.size() - 1);
        col = Math.min(snap.col(), lines.get(line).length());
        anchorLine = -1;
        touched();
    }

    // ------------------------------------------------------------------------------------------ pointer drag

    @Override
    public boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        // Dragging past the top or bottom edge scrolls so a long selection can be made.
        if (my < vy) scrollY.scrollTo(scrollY.target() - lineH, false);
        else if (my > vy + vh) scrollY.scrollTo(scrollY.target() + lineH, false);
        int[] at = posAt((float) mx, (float) my);
        line = at[0];
        col = at[1];
        caretMoved = false;
        return true;
    }

    @Override
    public void mouseReleased() {
        dragging = false;
    }

    @Override
    public void onBlur() {
        dragging = false;
    }
}
