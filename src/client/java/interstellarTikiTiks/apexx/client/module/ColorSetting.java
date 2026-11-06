package interstellarTikiTiks.apexx.client.module;

import java.awt.Color;

public class ColorSetting extends Setting {
    /** Rainbow speed range (full hue cycles per ~10 s at 1.0). */
    public static final double MIN_RAINBOW_SPEED = 0.1, MAX_RAINBOW_SPEED = 5.0, DEFAULT_RAINBOW_SPEED = 1.0;

    private Color value;
    private boolean rainbow;
    private double rainbowSpeed = DEFAULT_RAINBOW_SPEED;

    // getValue() is called from render paths every frame; the animated colour is rebuilt at most
    // once per ~8 ms so a rainbow setting does not allocate a Color per call.
    private Color rainbowCache;
    private long rainbowCacheAt = Long.MIN_VALUE;
    private int rainbowCacheBase;

    public ColorSetting(String name, Color defaultValue) {
        super(name);
        this.value = defaultValue;
    }

    /** The colour consumers should draw with: the animated hue while Rainbow is on, else the stored colour. */
    public Color getValue() {
        if (!rainbow || value == null) return value;
        long now = System.nanoTime() / 8_000_000L;
        int base = value.getRGB();
        if (rainbowCache != null && now == rainbowCacheAt && base == rainbowCacheBase) return rainbowCache;
        float[] hsb = Color.RGBtoHSB(value.getRed(), value.getGreen(), value.getBlue(), null);
        double seconds = System.nanoTime() / 1_000_000_000.0;
        float hue = (float) ((hsb[0] + seconds * rainbowSpeed * 0.1) % 1.0);
        // A grey/white base would cycle invisibly, so give the rainbow a usable saturation and brightness.
        float sat = hsb[1] < 0.25f ? 0.75f : hsb[1];
        float bri = Math.max(0.55f, hsb[2]);
        int rgb = Color.HSBtoRGB(hue, sat, bri);
        rainbowCache = new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, value.getAlpha());
        rainbowCacheAt = now;
        rainbowCacheBase = base;
        return rainbowCache;
    }

    /** The stored (non-animated) colour: what the picker edits and what configs persist. */
    public Color getBaseValue() {
        return value;
    }

    public void setValue(Color value) {
        if (value == null ? this.value != null : !value.equals(this.value)) {
            this.value = value;
            callListener();
            return;
        }
        this.value = value;
    }

    public boolean isRainbow() {
        return rainbow;
    }

    public void setRainbow(boolean rainbow) {
        if (this.rainbow == rainbow) return;
        this.rainbow = rainbow;
        rainbowCache = null;
        callListener();
    }

    public double getRainbowSpeed() {
        return rainbowSpeed;
    }

    public void setRainbowSpeed(double speed) {
        double clamped = Double.isFinite(speed)
                ? Math.max(MIN_RAINBOW_SPEED, Math.min(MAX_RAINBOW_SPEED, speed)) : DEFAULT_RAINBOW_SPEED;
        if (clamped == rainbowSpeed) return;
        rainbowSpeed = clamped;
        callListener();
    }

    private Color defaultColor;

    @Override
    public void captureDefault() {
        defaultColor = value;
    }

    @Override
    public void resetToDefault() {
        setRainbow(false);
        setRainbowSpeed(DEFAULT_RAINBOW_SPEED);
        if (defaultColor != null) setValue(defaultColor);
    }

    @Override
    public boolean isModified() {
        return rainbow || (defaultColor != null && !defaultColor.equals(value));
    }
}
