package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/** Immutable screen-space rectangle in GUI-scaled coordinates. */
public record UiBounds(float x, float y, float width, float height) {
    public UiBounds {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("Bounds must be finite");
        }
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    public boolean contains(float px, float py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }
}
