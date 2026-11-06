package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.ArrayList;
import java.util.List;

/** Routes input through the retained tree with focus and pointer capture. */
public final class UiInputRouter {
    private UiElement focused;
    private UiElement captured;
    private UiElement hovered;
    private float lastX, lastY;

    public UiElement focused() { return focused; }
    public UiElement captured() { return captured; }
    public UiElement hovered() { return hovered; }

    public void clearFocus() { focused = null; }
    public void setFocused(UiElement element) {
        focused = element != null && element.focusable() && isActive(element) ? element : null;
    }

    public boolean pointerDown(UiElement root, float x, float y, int button) {
        lastX = x;
        lastY = y;
        UiElement target = hit(root, x, y);
        hovered = target;
        if (target == null) {
            focused = null;
            captured = null;
            return false;
        }
        setFocused(nearestFocusable(target));
        boolean consumed = target.onPointerDown(x, y, button);
        captured = consumed ? target : null;
        return consumed;
    }

    public boolean pointerMove(UiElement root, float x, float y) {
        UiElement target = hit(root, x, y);
        hovered = target;
        UiElement receiver = captured != null && isActive(captured) ? captured : target;
        boolean consumed = receiver != null && receiver.onPointerMove(x, y, x - lastX, y - lastY);
        lastX = x;
        lastY = y;
        return consumed;
    }

    public boolean pointerUp(UiElement root, float x, float y, int button) {
        UiElement receiver = captured;
        captured = null;
        hovered = hit(root, x, y);
        return receiver != null && receiver.onPointerUp(x, y, button);
    }

    public boolean pointerCancel() {
        if (captured == null) return false;
        boolean consumed = captured.onPointerUp(lastX, lastY, -1);
        captured = null;
        return consumed;
    }

    public boolean scroll(UiElement root, float x, float y, double horizontal, double vertical) {
        for (UiElement target = hit(root, x, y); target != null; target = target.parent()) {
            if (target.onScroll(x, y, horizontal, vertical)) return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB && focused != null) {
            focusNext(focused.parent() == null ? focused : rootOf(focused),
                    (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        return focused != null && isActive(focused) && focused.onKeyPressed(keyCode, modifiers);
    }

    public void focusNext(UiElement root, boolean reverse) {
        List<UiElement> candidates = new ArrayList<>();
        collectFocusable(root, candidates);
        if (candidates.isEmpty()) { focused = null; return; }
        int current = candidates.indexOf(focused);
        int next = current < 0 ? (reverse ? candidates.size() - 1 : 0)
                : Math.floorMod(current + (reverse ? -1 : 1), candidates.size());
        focused = candidates.get(next);
    }

    private static UiElement nearestFocusable(UiElement element) {
        for (UiElement node = element; node != null; node = node.parent()) if (node.focusable()) return node;
        return null;
    }

    private static UiElement rootOf(UiElement element) {
        UiElement root = element;
        while (root.parent() != null) root = root.parent();
        return root;
    }

    private static void collectFocusable(UiElement element, List<UiElement> out) {
        if (element == null || !element.visible() || !element.enabled()) return;
        if (element.focusable()) out.add(element);
        for (UiElement child : element.children()) collectFocusable(child, out);
    }

    private static UiElement hit(UiElement element, float x, float y) {
        if (element == null || !element.visible() || !element.enabled() || !element.bounds().contains(x, y)) return null;
        var children = element.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            UiElement found = hit(children.get(i), x, y);
            if (found != null) return found;
        }
        return element;
    }

    private static boolean isActive(UiElement element) {
        for (UiElement node = element; node != null; node = node.parent()) {
            if (!node.visible() || !node.enabled()) return false;
        }
        return true;
    }
}
