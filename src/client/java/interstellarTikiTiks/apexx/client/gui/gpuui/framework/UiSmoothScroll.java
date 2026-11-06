package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/**
 * Pixel-accurate scroll state for the GPU UI: wheel input moves a target, the drawn offset eases
 * toward it (frame-rate independent), both are clamped to the content, and a thin overlay scrollbar
 * can be painted that brightens while the list is moving. The drawn offset is whole pixels so text
 * and rects never shimmer between positions.
 */
public final class UiSmoothScroll {
    private static final float FOLLOW_RATE = 18f;
    private static final long BAR_VISIBLE_NANOS = 900_000_000L;

    private float target, pos, max, view, content;
    private long lastActivityNanos = Long.MIN_VALUE / 2;

    /** Current offset in pixels (whole pixels). */
    public float offset() { return Math.round(pos); }
    public float target() { return target; }
    public float max() { return max; }
    public boolean scrollable() { return max > 0.5f; }

    public void reset() { target = 0f; pos = 0f; }

    /** One wheel notch: {@code amount} is GLFW's vertical amount (positive = up), {@code stepPx} the pixels per notch. */
    public void wheel(double amount, float stepPx, long nowNanos) {
        if (amount == 0 || !Float.isFinite((float) amount)) return;
        target = clamp(target - (float) amount * stepPx, 0f, Math.max(max, 0f));
        lastActivityNanos = nowNanos;
    }

    /** Jump the target (and optionally the drawn offset) so an item at {@code y} is visible. */
    public void scrollTo(float y, boolean immediate) {
        target = clamp(y, 0f, Math.max(max, 0f));
        if (immediate) pos = target;
    }

    /** Advance the ease and re-clamp against the current viewport/content sizes. Call once per frame. */
    public void update(float deltaSeconds, float viewHeight, float contentHeight, long nowNanos) {
        view = Math.max(0f, viewHeight);
        content = Math.max(0f, contentHeight);
        max = Math.max(0f, content - view);
        target = clamp(target, 0f, max);
        float follow = 1f - (float) Math.exp(-Math.max(0f, deltaSeconds) * FOLLOW_RATE);
        pos += (target - pos) * follow;
        if (Math.abs(target - pos) < 0.3f) pos = target;
        pos = clamp(pos, 0f, max);
        if (pos != target) lastActivityNanos = nowNanos;
    }

    /** Thin overlay bar hugging the right edge of the viewport at (x, y, h); fades out when idle. */
    public void paintBar(UiDisplayList.Builder b, float x, float y, float h, float s, int rgb, long nowNanos) {
        if (max <= 0.5f || h <= 0f) return;
        float since = (nowNanos - lastActivityNanos) / (float) BAR_VISIBLE_NANOS;
        float visibility = clamp(1.2f - since, 0.28f, 1f);
        float trackW = Math.max(2f, Math.round(3f * s));
        float thumbH = Math.max(28f * s, h * view / Math.max(content, 1f));
        float thumbY = y + (h - thumbH) * (max <= 0 ? 0f : pos / max);
        int alpha = Math.round(0x90 * visibility);
        b.rect(x, Math.round(thumbY), trackW, Math.round(thumbH), trackW * 0.5f, (alpha << 24) | (rgb & 0x00ffffff));
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
