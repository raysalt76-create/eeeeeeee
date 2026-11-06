package interstellarTikiTiks.apexx.client.util;

import java.awt.Color;

public final class ColorUtils {

    private ColorUtils() {}

    public static int clampChannel(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public static Color lerpColor(Color a, Color b, double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return new Color(
                clampChannel((int) (a.getRed()   + (b.getRed()   - a.getRed())   * c)),
                clampChannel((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * c)),
                clampChannel((int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * c)),
                clampChannel((int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * c)));
    }

    public static Color brighten(Color c, double amount) {
        if (amount <= 0.001) return c;
        int r = clampChannel((int) (c.getRed()   + (255 - c.getRed())   * amount));
        int g = clampChannel((int) (c.getGreen() + (255 - c.getGreen()) * amount));
        int b = clampChannel((int) (c.getBlue()  + (255 - c.getBlue())  * amount));
        return new Color(r, g, b, c.getAlpha());
    }
}
