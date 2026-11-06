package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Viewport that clips child painting, translates child bounds, and clamps wheel scrolling. */
public final class UiScrollArea extends UiElement implements UiWidget {
    private final Map<UiElement, UiBounds> contentBounds = new IdentityHashMap<>();
    private float scrollOffset;
    private float contentHeight;
    private float radius;

    public UiScrollArea(String id, float radius) {
        super(id);
        this.radius = Math.max(0, radius);
    }

    public float scrollOffset() { return scrollOffset; }
    public float maxScrollOffset() { return Math.max(0, contentHeight - bounds().height()); }
    public void setRadius(float radius) { this.radius = Math.max(0, radius); }

    public void addContent(UiElement child, UiBounds contentBounds) {
        add(child);
        setContentBounds(child, contentBounds);
    }

    public void setContentBounds(UiElement child, UiBounds bounds) {
        if (child.parent() != this) throw new IllegalArgumentException("Content element must be a child of this viewport");
        contentBounds.put(child, bounds);
        applyOffset(child, bounds);
        contentHeight = Math.max(contentHeight, bounds.y() + bounds.height() - this.bounds().y());
        clampOffset();
    }

    public void setContentHeight(float height) {
        if (!Float.isFinite(height)) throw new IllegalArgumentException("Content height must be finite");
        contentHeight = Math.max(0, height);
        clampOffset();
        relayout();
    }

    public void setScrollOffset(float offset) {
        if (!Float.isFinite(offset)) return;
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(), offset));
        relayout();
    }

    public void scrollBy(float delta) {
        if (!Float.isFinite(delta)) return;
        setScrollOffset(scrollOffset + delta);
    }

    public void layoutVertical(float x, float contentY, float width, float gap, List<Float> heights) {
        if (heights.size() != children().size()) throw new IllegalArgumentException("One height is required per child");
        float top = contentY;
        for (int i = 0; i < heights.size(); i++) {
            UiElement child = children().get(i);
            float height = Math.max(0, heights.get(i));
            setContentBounds(child, new UiBounds(x, top, width, height));
            top += height + gap;
        }
        contentHeight = Math.max(0, top - gap - bounds().y());
        clampOffset();
        relayout();
    }

    @Override public boolean onScroll(float x, float y, double horizontal, double vertical) {
        if (vertical == 0 || maxScrollOffset() <= 0) return false;
        scrollBy((float) (-vertical * 24));
        return true;
    }

    @Override public void paint(UiDisplayList.Builder displayList) {
        UiBounds viewport = bounds();
        displayList.clipPush(viewport.x(), viewport.y(), viewport.width(), viewport.height(), radius);
        for (UiElement child : children()) {
            if (child.visible() && child instanceof UiWidget widget) widget.paint(displayList);
        }
        displayList.clipPop();
    }

    private void clampOffset() {
        scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset));
    }

    private void relayout() {
        for (UiElement child : children()) {
            UiBounds base = contentBounds.get(child);
            if (base != null) applyOffset(child, base);
        }
    }

    private void applyOffset(UiElement child, UiBounds base) {
        child.setBounds(new UiBounds(base.x(), base.y() - scrollOffset, base.width(), base.height()));
    }
}
