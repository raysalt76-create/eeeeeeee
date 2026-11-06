package interstellarTikiTiks.apexx.client.util.render;

/**
 * Pure math shared by the trail, ring and outline visuals: easing, fades and packed ARGB colour
 * helpers. No Minecraft types, so it is unit tested directly.
 */
public final class TrailMath {
    private TrailMath() {
    }

    public static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 == edge0) return x < edge0 ? 0.0f : 1.0f;
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    public static float easeOutCubic(float t) {
        float c = clamp01(t);
        float inv = 1.0f - c;
        return 1.0f - inv * inv * inv;
    }

    /** Fraction of a lifetime used, 0 at birth and 1 at (or past) the end. Non-positive life counts as expired. */
    public static float lifeFraction(long ageMs, long lifeMs) {
        if (lifeMs <= 0L) return 1.0f;
        if (ageMs <= 0L) return 0.0f;
        return clamp01((float) ageMs / (float) lifeMs);
    }

    /** Smooth 1 -> 0 fade over a lifetime: full at birth, 0 at the end, no visible pop at either end. */
    public static float fade(long ageMs, long lifeMs) {
        return 1.0f - smoothstep(0.0f, 1.0f, lifeFraction(ageMs, lifeMs));
    }

    /** Radius of an expanding ring at {@code progress} (0..1): fast start, gentle stop at {@code maxRadius}. */
    public static float ringRadius(float progress, float maxRadius) {
        return maxRadius * easeOutCubic(progress);
    }

    /** 0..1 sine pulse with {@code speed} cycles per second. */
    public static float pulse(double timeSeconds, double speed) {
        return (float) (0.5 + 0.5 * Math.sin(timeSeconds * speed * Math.PI * 2.0));
    }

    /**
     * Half width in blocks of a camera-facing line that should look about {@code thickness} pixels wide at
     * {@code distance} blocks, independent of distance.
     */
    public static float screenConstantHalfWidth(float thickness, double distance) {
        return (float) (Math.max(0.05, thickness) * 0.00065 * Math.max(0.05, distance));
    }

    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** Replaces the alpha of {@code argb} with {@code alpha} (0..1), keeping RGB. */
    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(clamp01(alpha) * 255.0f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Multiplies the existing alpha of {@code argb} by {@code factor} (0..1). */
    public static int scaleAlpha(int argb, float factor) {
        int a = Math.round(alpha(argb) * clamp01(factor));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Per-channel linear blend of two packed ARGB colours, {@code t} clamped to 0..1. */
    public static int lerpArgb(int from, int to, float t) {
        float k = clamp01(t);
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, k);
        int r = lerpChannel((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, k);
        int g = lerpChannel((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, k);
        int b = lerpChannel(from & 0xFF, to & 0xFF, k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    /** Opaque ARGB from hue (any real, wrapped to 0..1), saturation and value (0..1). */
    public static int hsv(float hue, float saturation, float value) {
        float h = hue - (float) Math.floor(hue);
        float s = clamp01(saturation);
        float v = clamp01(value);
        float scaled = h * 6.0f;
        int sector = (int) scaled % 6;
        float f = scaled - (float) Math.floor(scaled);
        float p = v * (1.0f - s);
        float q = v * (1.0f - f * s);
        float t = v * (1.0f - (1.0f - f) * s);
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255.0f) << 16) | (Math.round(g * 255.0f) << 8) | Math.round(b * 255.0f);
    }

    /** Brightens an RGB colour toward white by {@code amount} (0..1), keeping alpha. */
    public static int brighten(int argb, float amount) {
        return lerpArgb(argb, (argb & 0xFF000000) | 0x00FFFFFF, amount);
    }
}
