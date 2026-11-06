package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/** Focusable button with matching pointer and keyboard activation. */
public final class UiButton extends UiElement implements UiWidget {
    private String label;
    private final Runnable action;
    private int color;
    private int hoverColor;
    private int textColor;
    private float radius;
    private float textInset;
    private float textSize = 9;
    private boolean hovered;

    public UiButton(String id, String label, Runnable action, int color, int hoverColor,
                    int textColor, float radius, float textInset) {
        super(id);
        setFocusable(true);
        this.label = Objects.requireNonNull(label, "label");
        this.action = Objects.requireNonNull(action, "action");
        this.color = color;
        this.hoverColor = hoverColor;
        this.textColor = textColor;
        this.radius = Math.max(0, radius);
        this.textInset = Math.max(0, textInset);
    }

    public void setLabel(String label) { this.label = Objects.requireNonNull(label, "label"); }
    public void setHovered(boolean hovered) { this.hovered = hovered; }
    public void setTextSize(float size) { textSize = Math.max(1, size); }
    public void setColors(int color, int hoverColor, int textColor) {
        this.color = color;
        this.hoverColor = hoverColor;
        this.textColor = textColor;
    }

    @Override public void paint(UiDisplayList.Builder displayList) {
        UiBounds b = bounds();
        displayList.rect(b.x(), b.y(), b.width(), b.height(), radius, hovered ? hoverColor : color);
        displayList.text(b.x() + textInset, b.y() + (b.height() - textSize) * .5f, textSize, textColor, label);
    }

    @Override public boolean onPointerDown(float x, float y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_1 || !enabled()) return false;
        action.run();
        return true;
    }

    @Override public boolean onPointerUp(float x, float y, int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_1;
    }
}
