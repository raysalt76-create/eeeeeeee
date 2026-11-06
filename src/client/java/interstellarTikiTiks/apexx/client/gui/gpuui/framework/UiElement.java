package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Retained tree node. Bounds are absolute GUI-scaled coordinates. */
public class UiElement {
    private final String id;
    private final List<UiElement> children = new ArrayList<>();
    private UiElement parent;
    private UiBounds bounds = new UiBounds(0, 0, 0, 0);
    private boolean visible = true;
    private boolean enabled = true;
    private boolean focusable;

    public UiElement(String id) { this.id = Objects.requireNonNull(id, "id"); }
    public String id() { return id; }
    public UiElement parent() { return parent; }
    public List<UiElement> children() { return Collections.unmodifiableList(children); }
    public UiBounds bounds() { return bounds; }
    public void setBounds(UiBounds bounds) { this.bounds = Objects.requireNonNull(bounds, "bounds"); }
    public boolean visible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean focusable() { return focusable; }
    public void setFocusable(boolean focusable) { this.focusable = focusable; }

    public void add(UiElement child) {
        Objects.requireNonNull(child, "child");
        if (child == this || isDescendantOf(child)) throw new IllegalArgumentException("Adding child would create a cycle");
        if (child.parent != null) child.parent.remove(child);
        child.parent = this;
        children.add(child);
    }

    public boolean remove(UiElement child) {
        if (!children.remove(child)) return false;
        child.parent = null;
        return true;
    }

    private boolean isDescendantOf(UiElement candidate) {
        for (UiElement node = this; node != null; node = node.parent) if (node == candidate) return true;
        return false;
    }

    /** Override to handle a primary-button press. Return true when consumed. */
    public boolean onPointerDown(float x, float y, int button) { return false; }
    /** Override to handle pointer motion while this element owns capture. */
    public boolean onPointerMove(float x, float y, float deltaX, float deltaY) { return false; }
    /** Override to handle a primary-button release. Return true when consumed. */
    public boolean onPointerUp(float x, float y, int button) { return false; }
    /** Override to handle a wheel event over this element. */
    public boolean onScroll(float x, float y, double horizontal, double vertical) { return false; }
    /** Override to handle a key press on the focused element. Return true when consumed. */
    public boolean onKeyPressed(int keyCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            UiBounds b = bounds;
            return onPointerDown(b.x() + b.width() * .5f, b.y() + b.height() * .5f, 0);
        }
        return false;
    }
}
