package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.List;

/** Small absolute-coordinate layout helpers for retained child trees. */
public final class UiLayout {
    private UiLayout() { }

    public static void vertical(UiElement parent, float x, float y, float width, float gap, List<Float> heights) {
        if (heights.size() != parent.children().size()) throw new IllegalArgumentException("One height is required per child");
        float top = y;
        for (int i = 0; i < heights.size(); i++) {
            float height = Math.max(0, heights.get(i));
            parent.children().get(i).setBounds(new UiBounds(x, top, Math.max(0, width), height));
            top += height + gap;
        }
    }

    public static void horizontal(UiElement parent, float x, float y, float height, float gap, List<Float> widths) {
        if (widths.size() != parent.children().size()) throw new IllegalArgumentException("One width is required per child");
        float left = x;
        for (int i = 0; i < widths.size(); i++) {
            float width = Math.max(0, widths.get(i));
            parent.children().get(i).setBounds(new UiBounds(left, y, width, Math.max(0, height)));
            left += width + gap;
        }
    }
}
