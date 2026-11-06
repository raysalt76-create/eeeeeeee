package interstellarTikiTiks.apexx.client.render.visuals;

/** Per-frame ShaderESP composite parameters, filled by the module and consumed by {@link ShaderEspEngine}. */
public final class ShaderEspParams {
    public static final int FILL_SOLID = 0;
    public static final int FILL_GRADIENT = 1;
    public static final int FILL_SMOKE = 2;

    public boolean throughWalls = true;

    public boolean fill = true;
    public float fillAlpha = 0.41f;
    public int fillStyle = FILL_SOLID;
    public float fillBrightness = 1.0f;

    public boolean outline = true;
    public float outlineWidth = 2.0f;
    public float outlineAlpha = 0.92f;
    public float outlineIntensity = 1.15f;

    public boolean glow = true;
    public float glowRadius = 8.0f;
    public float glowAlpha = 0.72f;
    public float glowIntensity = 1.25f;

    public float smokeScale = 2.6f;
    public float smokeSpeed = 0.55f;
    public int smokeOctaves = 4;
    public float smokeContrast = 1.2f;

    /** Gradient partner color, 0xRRGGBB. */
    public int secondaryRgb = 0x9B5FFF;
    public float gradientAngleRadians = 0.9f;

    /** Relation colors that stay solid, 0xRRGGBB. */
    public int friendRgb = 0x4BFF91;
    public int enemyRgb = 0xFF485C;

    boolean drawsAnything() {
        return fill || outline || glow;
    }
}
