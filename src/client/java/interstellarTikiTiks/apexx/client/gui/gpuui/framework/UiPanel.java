package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/** Composable rounded panel. Child widgets paint in insertion order. */
public final class UiPanel extends UiElement implements UiWidget {
    private float radius;
    private int color;

    public UiPanel(String id, int color, float radius) {
        super(id);
        this.color = color;
        this.radius = Math.max(0, radius);
    }

    public void setColor(int color) { this.color = color; }
    public void setRadius(float radius) { this.radius = Math.max(0, radius); }

    @Override public void paint(UiDisplayList.Builder displayList) {
        UiBounds bounds = bounds();
        displayList.rect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius, color);
        for (UiElement child : children()) {
            if (child.visible() && child.enabled() && child instanceof UiWidget widget) widget.paint(displayList);
        }
    }
}
