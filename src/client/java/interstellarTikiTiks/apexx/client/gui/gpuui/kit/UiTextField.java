package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntPredicate;

/**
 * Single-line text input for the GPU UI: caret, selection (Shift+arrows, Ctrl+A, drag-free), horizontal
 * scroll, clipboard (Ctrl+C / Ctrl+X / Ctrl+V), word jumps and deletes (Ctrl+arrows / Ctrl+Backspace),
 * and a filter for allowed characters.
 */
public final class UiTextField implements UiTextTarget {
    private String placeholder;
    private String value = "";
    private int caret;
    /** Selection anchor; equal to {@link #caret} (or -1) when nothing is selected. */
    private int anchor = -1;
    private float scrollX;
    private int maxLength = 64;
    private IntPredicate allowed = cp -> true;
    private Runnable onSubmit;
    private Runnable onChange;
    private Runnable onBlur;
    private UiHost lastHost;
    private long focusedAtNanos;
    // Presentation (design px, scaled by the draw call's s).
    private boolean bare;
    private float padLeft = 12f, padRight = 12f, fontSize = 11f;

    public UiTextField(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
    }

    public UiTextField maxLength(int max) { this.maxLength = Math.max(1, max); return this; }

    public UiTextField allow(IntPredicate codePointFilter) { this.allowed = codePointFilter; return this; }

    public UiTextField onSubmit(Runnable action) { this.onSubmit = action; return this; }

    public UiTextField onChange(Runnable action) { this.onChange = action; return this; }

    /** Runs when focus leaves the field (a good time to normalise what was typed). */
    public UiTextField onBlur(Runnable action) { this.onBlur = action; return this; }

    /** No background or border: the owner paints its own box (e.g. a search bar with an icon). */
    public UiTextField bare(boolean bare) { this.bare = bare; return this; }

    /** Inner text padding in design px. */
    public UiTextField padding(float left, float right) { this.padLeft = left; this.padRight = right; return this; }

    /** Font size in design px (default 11). */
    public UiTextField fontSize(float size) { this.fontSize = size; return this; }

    public UiTextField placeholder(String text) { this.placeholder = text == null ? "" : text; return this; }

    public String value() { return value; }

    public void setValue(String text) {
        value = text == null ? "" : text.substring(0, Math.min(text.length(), maxLength));
        caret = value.length();
        anchor = -1;
        scrollX = 0f;
    }

    /** Empties the field and reports the change. */
    public void clear() {
        if (value.isEmpty()) return;
        setValue("");
        changed();
    }

    public boolean isEmpty() { return value.isEmpty(); }

    public boolean focused(UiHost host) { return host.focused() == this; }

    public void selectAll() {
        anchor = 0;
        caret = value.length();
    }

    private boolean hasSelection() {
        return anchor >= 0 && anchor != caret;
    }

    private int selStart() { return hasSelection() ? Math.min(anchor, caret) : caret; }

    private int selEnd() { return hasSelection() ? Math.max(anchor, caret) : caret; }

    /** Draws the field and registers its click region. {@code s} is the menu scale. */
    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s) {
        lastHost = host;
        boolean focus = host.focused() == this;
        if (!bare) {
            // Same radius as the module search field and every card (12px), so inputs match the search bar.
            float radius = Math.min(h * 0.5f, Math.round(12f * s * host.cornerScale()));
            b.rect(x, y, w, h, radius, host.controlFill(focus ? 0x26 : 0x14));
            int accent = host.accent();
            b.border(x, y, w, h, radius, 1f, focus ? (accent & 0x00ffffff) | 0xa0000000 : 0x1effffff);
        }

        float size = fontSize * s;
        float padX = Math.round(padLeft * s);
        float inner = Math.max(1f, w - padX - Math.round(padRight * s));
        float ty = GpuUiFontMetrics.topInBox(y, h, size);
        caret = Math.max(0, Math.min(caret, value.length()));
        if (anchor > value.length()) anchor = -1;
        if (value.isEmpty()) {
            b.text(x + padX, ty, size, host.muted2Color(), UiKit.fit(placeholder, inner, size));
        } else {
            float caretX = GpuUiFontMetrics.textWidth(value.substring(0, caret), size);
            if (caretX - scrollX > inner - 2f) scrollX = caretX - inner + 2f;
            if (caretX - scrollX < 0f) scrollX = caretX;
            if (GpuUiFontMetrics.textWidth(value, size) <= inner) scrollX = 0f;
            b.clipPush(x + padX, y, inner, h, 0);
            if (focus && hasSelection()) {
                float sx = GpuUiFontMetrics.textWidth(value.substring(0, selStart()), size) - scrollX;
                float ex = GpuUiFontMetrics.textWidth(value.substring(0, selEnd()), size) - scrollX;
                float selH = Math.round(size * 1.35f);
                b.rect(x + padX + sx, Math.round(y + (h - selH) * 0.5f), Math.max(1f, ex - sx), selH,
                        Math.max(1f, 2f * s), (host.accent() & 0x00ffffff) | 0x55000000);
            }
            b.text(x + padX - scrollX, ty, size, host.textColor(), value);
            b.clipPop();
        }
        if (focus && !hasSelection() && ((host.now() - focusedAtNanos) / 530_000_000L) % 2 == 0) {
            float caretX = GpuUiFontMetrics.textWidth(value.substring(0, caret), size) - scrollX;
            float caretH = Math.round(size * 1.15f);
            b.rect(x + padX + Math.round(caretX), Math.round(y + (h - caretH) * 0.5f), 1f, caretH, 0, host.textColor());
        }
        host.hit(x, y, w, h, () -> {
            if (host.focused() != this) host.focus(this);
            caret = caretAt((float) host.pressX() - (x + padX) + scrollX, size);
            anchor = -1;
            focusedAtNanos = host.now();
        });
    }

    private int caretAt(float px, float size) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i <= value.length(); i++) {
            float d = Math.abs(GpuUiFontMetrics.textWidth(value.substring(0, i), size) - px);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    @Override
    public boolean charTyped(String chars) {
        StringBuilder add = new StringBuilder();
        chars.codePoints().filter(cp -> !Character.isISOControl(cp) && allowed.test(cp)).forEach(add::appendCodePoint);
        if (add.length() == 0) return true;
        insert(add.toString());
        return true;
    }

    private void insert(String text) {
        boolean removed = deleteSelection();
        int room = maxLength - value.length();
        if (room <= 0) {
            if (removed) changed();
            return;
        }
        if (text.length() > room) text = text.substring(0, room);
        value = value.substring(0, caret) + text + value.substring(caret);
        caret += text.length();
        anchor = -1;
        changed();
    }

    /** Removes the selected text (without reporting); true if anything was removed. */
    private boolean deleteSelection() {
        if (!hasSelection()) {
            anchor = -1;
            return false;
        }
        int from = selStart(), to = selEnd();
        value = value.substring(0, from) + value.substring(to);
        caret = from;
        anchor = -1;
        return true;
    }

    /** Moves the caret; with {@code extend} the selection grows from where it started. */
    private void moveCaret(int to, boolean extend) {
        if (extend) {
            if (anchor < 0) anchor = caret;
        } else {
            anchor = -1;
        }
        caret = Math.max(0, Math.min(value.length(), to));
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        boolean ctrl = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (deleteSelection()) {
                    changed();
                } else if (caret > 0) {
                    int cut = ctrl ? wordStart(caret) : caret - 1;
                    value = value.substring(0, cut) + value.substring(caret);
                    caret = cut;
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (deleteSelection()) {
                    changed();
                } else if (caret < value.length()) {
                    int end = ctrl ? wordEnd(caret) : caret + 1;
                    value = value.substring(0, caret) + value.substring(end);
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (!shift && hasSelection()) moveCaret(selStart(), false);
                else moveCaret(ctrl ? wordStart(caret) : caret - 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (!shift && hasSelection()) moveCaret(selEnd(), false);
                else moveCaret(ctrl ? wordEnd(caret) : caret + 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> { moveCaret(0, shift); return true; }
            case GLFW.GLFW_KEY_END -> { moveCaret(value.length(), shift); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (onSubmit != null) onSubmit.run();
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl && lastHost != null) {
                    String clip = lastHost.clipboard();
                    if (clip != null) charTyped(clip.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
                    return true;
                }
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl && lastHost != null) {
                    lastHost.setClipboard(hasSelection() ? value.substring(selStart(), selEnd()) : value);
                    return true;
                }
            }
            case GLFW.GLFW_KEY_X -> {
                if (ctrl && lastHost != null) {
                    if (hasSelection()) {
                        lastHost.setClipboard(value.substring(selStart(), selEnd()));
                        deleteSelection();
                        changed();
                    }
                    return true;
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) { selectAll(); return true; }
            }
            default -> { }
        }
        // Everything else a text field does not use (Tab, arrows up/down, shortcuts) is swallowed so it
        // can never trigger a menu shortcut while typing.
        return key != GLFW.GLFW_KEY_ESCAPE;
    }

    private int wordStart(int from) {
        int i = from;
        while (i > 0 && value.charAt(i - 1) == ' ') i--;
        while (i > 0 && value.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int wordEnd(int from) {
        int i = from, n = value.length();
        while (i < n && value.charAt(i) == ' ') i++;
        while (i < n && value.charAt(i) != ' ') i++;
        return i;
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    @Override
    public void onBlur() {
        anchor = -1;
        if (onBlur != null) onBlur.run();
    }

    @Override
    public void onFocus() {
        caret = value.length();
        anchor = -1;
        if (lastHost != null) focusedAtNanos = lastHost.now();
    }

    public static boolean isDigit(int cp) {
        return cp >= '0' && cp <= '9';
    }

    public static boolean isHexChar(int cp) {
        return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'f') || (cp >= 'A' && cp <= 'F') || cp == '#';
    }

    public static boolean isItemIdChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == ':' || cp == '_' || cp == '/' || cp == '.' || cp == '-';
    }

    /** Common character filters. */
    public static boolean isNameChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == ' ' || cp == '_' || cp == '-' || cp == '.' || cp == '#';
    }

}
