package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.DoubleConsumer;

/** Horizontal slider that owns pointer capture while its value is being changed. */
public final class UiSlider extends UiElement implements UiWidget {
    private final String label;
    private final double min;
    private final double max;
    private final DoubleConsumer onChange;
    private double value;
    private boolean dragging;

    public UiSlider(String id, String label, double min, double max, double value, DoubleConsumer onChange) {
        super(id);
        if (!Double.isFinite(min) || !Double.isFinite(max) || max < min) throw new IllegalArgumentException("Invalid slider range");
        this.label = Objects.requireNonNull(label, "label");
        this.min = min;
        this.max = max;
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        setFocusable(true);
        setValue(value, false);
    }

    public double value() { return value; }
    public void setValue(double value) { setValue(value, true); }
    private void setValue(double candidate, boolean notify) {
        if (!Double.isFinite(candidate)) return;
        value = Math.max(min, Math.min(max, candidate));
        if (notify) onChange.accept(value);
    }

    @Override public void paint(UiDisplayList.Builder displayList) {
        UiBounds b = bounds();
        displayList.text(b.x(), b.y(), 9, 0xffe9edf5, label + "  " + String.format("%.2f", value));
        float trackX = b.x(), trackY = b.y() + b.height() - 4, trackW = b.width();
        float p = max == min ? 0 : (float) ((value - min) / (max - min));
        displayList.rect(trackX, trackY, trackW, 3, 2, 0xff3b4655);
        displayList.rect(trackX, trackY, trackW * p, 3, 2, 0xff9c7cff);
    }

    @Override public boolean onPointerDown(float x, float y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_1) return false;
        dragging = true;
        update(x);
        return true;
    }

    @Override public boolean onPointerMove(float x, float y, float deltaX, float deltaY) {
        if (!dragging) return false;
        update(x);
        return true;
    }

    @Override public boolean onPointerUp(float x, float y, int button) {
        if (!dragging) return false;
        dragging = false;
        return true;
    }

    private void update(float x) {
        UiBounds b = bounds();
        double p = b.width() <= 0 ? 0 : Math.max(0, Math.min(1, (x - b.x()) / b.width()));
        setValue(min + p * (max - min));
    }
}
