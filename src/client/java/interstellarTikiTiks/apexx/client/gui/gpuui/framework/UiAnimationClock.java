package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/** Monotonic UI time source; callers advance it once per frame. */
public final class UiAnimationClock {
    private double seconds;

    public double seconds() { return seconds; }
    public void advance(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0) throw new IllegalArgumentException("deltaSeconds must be finite and nonnegative");
        seconds += deltaSeconds;
    }

    public float tween(double startSeconds, double durationSeconds, float from, float to) {
        if (durationSeconds <= 0) return to;
        float t = (float) Math.max(0, Math.min(1, (seconds - startSeconds) / durationSeconds));
        t = t * t * (3 - 2 * t);
        return from + (to - from) * t;
    }
}
