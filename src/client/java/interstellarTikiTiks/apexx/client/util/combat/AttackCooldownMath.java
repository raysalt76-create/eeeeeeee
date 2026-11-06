package interstellarTikiTiks.apexx.client.util.combat;

/** Pure attack-cooldown display math (unit tested). */
public final class AttackCooldownMath {

    /** Community "half-swing" threshold: a hit above this is a usable pressure hit. */
    public static final float HALF_SWING = 0.848f;
    /** Treat anything this close to 1 as fully charged (float noise from the cooldown curve). */
    public static final float FULL_EPSILON = 0.999f;

    public static final int STAGE_CHARGING = 0;
    public static final int STAGE_HALF_SWING = 1;
    public static final int STAGE_FULL = 2;

    private AttackCooldownMath() {
    }

    /** Ring fill fraction in [0, 1]; NaN reads as empty. */
    public static float ringFraction(float progress) {
        if (Float.isNaN(progress) || progress <= 0.0f) return 0.0f;
        return progress >= 1.0f ? 1.0f : progress;
    }

    public static int stage(float progress) {
        float f = ringFraction(progress);
        if (f >= FULL_EPSILON) return STAGE_FULL;
        if (f >= HALF_SWING) return STAGE_HALF_SWING;
        return STAGE_CHARGING;
    }

    /** Number of ring segments to fill out of {@code totalSegments}, rounding to the nearest. */
    public static int filledSegments(float progress, int totalSegments) {
        if (totalSegments <= 0) return 0;
        return Math.min(totalSegments, Math.round(ringFraction(progress) * totalSegments));
    }

    /** Linear ARGB blend, t clamped to [0, 1]. */
    public static int lerpArgb(int from, int to, float t) {
        float k = t <= 0.0f ? 0.0f : (t >= 1.0f ? 1.0f : t);
        int a = lerpChannel(from >>> 24, to >>> 24, k);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, k);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, k);
        int b = lerpChannel(from & 0xFF, to & 0xFF, k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int a, int b, float t) {
        return Math.round(a + (b - a) * t) & 0xFF;
    }
}
