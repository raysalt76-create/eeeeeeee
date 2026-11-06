package interstellarTikiTiks.apexx.client.render.visuals;

import java.awt.Color;
import net.minecraft.util.math.Vec3d;

/** Per-frame, immutable-at-submit inputs for the world compositor. */
public final class WorldSceneVisuals {
    float fogStart;
    float fogEnd = 128.0f;
    float fogDensity;
    float fogR, fogG, fogB;
    float globeStrength;
    float groundStrength;
    float groundScale = 1.0f;
    Vec3d hitPosition;
    float hitRadius;
    float hitStrength;
    float hitAge;
    float hitR = 0.45f, hitG = 0.9f, hitB = 1.0f;

    /** Upper bound on terrain-projected landing markers evaluated in one compositor pass. */
    public static final int MAX_LANDING_MARKERS = 4;
    int markerCount;
    float markerStyle;
    final float[] markerPositionRadius = new float[MAX_LANDING_MARKERS * 4];
    final float[] markerNormalIntensity = new float[MAX_LANDING_MARKERS * 4];
    final float[] markerPrimary = new float[MAX_LANDING_MARKERS * 4];
    final float[] markerSecondary = new float[MAX_LANDING_MARKERS * 4];

    public void setDepthFog(Color color, float start, float end, float density) {
        if (color == null) return;
        fogR = color.getRed() / 255f; fogG = color.getGreen() / 255f; fogB = color.getBlue() / 255f;
        fogStart = finiteClamp(start, 0, 4096); fogEnd = finiteClamp(end, fogStart + 0.01f, 4096);
        fogDensity = finiteClamp(density, 0, 1);
    }

    public void setFloatingGlobes(float strength) { globeStrength = finiteClamp(strength, 0, 1); }
    public void setGroundFilm(float strength, float blockScale) {
        groundStrength = finiteClamp(strength, 0, 1); groundScale = finiteClamp(blockScale, 0.25f, 8);
    }
    public void setHitRipple(Vec3d position, float radius, float strength, float ageSeconds, Color color) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return;
        hitPosition = position;
        hitRadius = finiteClamp(radius, 0.05f, 8);
        hitStrength = finiteClamp(strength, 0, 1);
        hitAge = finiteClamp(ageSeconds, 0, 4);
        if (color != null) { hitR = color.getRed() / 255f; hitG = color.getGreen() / 255f; hitB = color.getBlue() / 255f; }
    }

    /**
     * Adds a decal that is projected onto the captured scene depth around {@code position}, in the plane
     * perpendicular to {@code normal}, so it wraps over the real terrain instead of floating on a quad.
     *
     * @param style 0 = Halo, 1 = Reticle, 2 = Minimal (shared by every marker in the frame)
     * @return false when the marker was rejected (full or non-finite input)
     */
    public boolean addLandingMarker(Vec3d position, Vec3d normal, float radius, float intensity,
                                    Color primary, Color secondary, int style) {
        if (markerCount >= MAX_LANDING_MARKERS || position == null || primary == null) return false;
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return false;
        Vec3d n = normal == null || normal.lengthSquared() < 1.0e-6 ? new Vec3d(0, 1, 0) : normal.normalize();
        Color second = secondary == null ? primary : secondary;
        int o = markerCount * 4;
        markerPositionRadius[o] = (float) position.x;
        markerPositionRadius[o + 1] = (float) position.y;
        markerPositionRadius[o + 2] = (float) position.z;
        markerPositionRadius[o + 3] = finiteClamp(radius, 0.1f, 6);
        markerNormalIntensity[o] = (float) n.x;
        markerNormalIntensity[o + 1] = (float) n.y;
        markerNormalIntensity[o + 2] = (float) n.z;
        markerNormalIntensity[o + 3] = finiteClamp(intensity, 0, 2);
        markerPrimary[o] = primary.getRed() / 255f;
        markerPrimary[o + 1] = primary.getGreen() / 255f;
        markerPrimary[o + 2] = primary.getBlue() / 255f;
        markerSecondary[o] = second.getRed() / 255f;
        markerSecondary[o + 1] = second.getGreen() / 255f;
        markerSecondary[o + 2] = second.getBlue() / 255f;
        markerStyle = Math.max(0, Math.min(2, style));
        markerCount++;
        return true;
    }

    private static float finiteClamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    boolean enabled(int operation) {
        return switch (operation) {
            case 0 -> fogDensity > 0;
            case 1 -> globeStrength > 0;
            case 2 -> groundStrength > 0;
            case 3 -> hitStrength > 0 && hitPosition != null;
            case 4 -> markerCount > 0;
            default -> false;
        };
    }

    boolean hasEffects() {
        for (int operation = 0; operation < OPERATIONS; operation++) if (enabled(operation)) return true;
        return false;
    }

    /** Number of ordered compositor operations: fog, globes, ground film, hit ripple, landing markers. */
    static final int OPERATIONS = 5;
}
