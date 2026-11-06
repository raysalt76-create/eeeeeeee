package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Equal-width retained tab strip with pointer and arrow-key selection. */
public final class UiTabs extends UiElement implements UiWidget {
    private final List<String> labels;
    private final IntConsumer onSelect;
    private int selected;

    public UiTabs(String id, List<String> labels, int selected, IntConsumer onSelect) {
        super(id);
        this.labels = List.copyOf(labels);
        if (this.labels.isEmpty()) throw new IllegalArgumentException("Tabs require at least one label");
        this.onSelect = Objects.requireNonNull(onSelect, "onSelect");
        setFocusable(true);
        setSelected(selected, false);
    }

    public int selected() { return selected; }
    public void setSelected(int index) { setSelected(index, true); }
    private void setSelected(int index, boolean notify) {
        selected = Math.max(0, Math.min(labels.size() - 1, index));
        if (notify) onSelect.accept(selected);
    }

    @Override public void paint(UiDisplayList.Builder displayList) {
        UiBounds b = bounds();
        float tabWidth = b.width() / labels.size();
        for (int i = 0; i < labels.size(); i++) {
            float x = b.x() + i * tabWidth;
            boolean active = i == selected;
            displayList.rect(x, b.y(), tabWidth, b.height(), Math.min(6, b.height() * .5f),
                    active ? 0xff342d50 : 0xff202733);
            displayList.text(x + 8, b.y() + (b.height() - 10) * .5f, 9,
                    active ? 0xffe9edf5 : 0xff8d99aa, labels.get(i));
        }
    }

    @Override public boolean onPointerDown(float x, float y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_1 || bounds().width() <= 0) return false;
        int index = (int) ((x - bounds().x()) / bounds().width() * labels.size());
        setSelected(index);
        return true;
    }

    @Override public boolean onKeyPressed(int keyCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT) { setSelected(Math.floorMod(selected - 1, labels.size())); return true; }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) { setSelected((selected + 1) % labels.size()); return true; }
        return super.onKeyPressed(keyCode, modifiers);
    }
}
