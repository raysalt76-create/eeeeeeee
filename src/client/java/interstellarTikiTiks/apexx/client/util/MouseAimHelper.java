package interstellarTikiTiks.apexx.client.util;

import net.minecraft.client.MinecraftClient;

public final class MouseAimHelper {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static double pendingDX = 0.0;
    private static double pendingDY = 0.0;

    private static double aimRateDX      = 0.0;
    private static double aimRateDY      = 0.0;
    private static long   aimSetMs       = 0;
    private static long   aimLastPollMs  = 0;
    private static double aimAccDX       = 0.0;
    private static double aimAccDY       = 0.0;
    private static double cachedFrameFraction = 0.0;

    private MouseAimHelper() {}

    public static void addDelta(double dx, double dy) {
        pendingDX += dx;
        pendingDY += dy;
    }

    public static void setAimRate(double dx, double dy) {
        aimRateDX = dx;
        aimRateDY = dy;
        long now  = System.currentTimeMillis();
        aimSetMs  = now;
        if (aimLastPollMs == 0) aimLastPollMs = now;
    }

    public static void clearAimRate() {
        aimRateDX          = 0.0;
        aimRateDY          = 0.0;
        aimAccDX           = 0.0;
        aimAccDY           = 0.0;
        aimLastPollMs      = 0;
        cachedFrameFraction = 0.0;
    }

    public static double pollDX() {
        double result = pendingDX;
        pendingDX = 0.0;

        long now     = System.currentTimeMillis();
        long elapsed = aimLastPollMs > 0 ? Math.min(now - aimLastPollMs, 50L) : 0L;
        aimLastPollMs       = now;
        cachedFrameFraction  = elapsed / 50.0;

        if (aimRateDX != 0.0 && now - aimSetMs <= 100) {
            float gcd  = computeGcd();
            aimAccDX  += aimRateDX * cachedFrameFraction;
            long steps = Math.round(aimAccDX / gcd);
            aimAccDX  -= steps * gcd;
            result    += steps;
        }

        return result;
    }

    public static double pollDY() {
        double result = pendingDY;
        pendingDY = 0.0;

        long now = System.currentTimeMillis();
        if (aimRateDY != 0.0 && now - aimSetMs <= 100) {
            float gcd  = computeGcd();
            aimAccDY  += aimRateDY * cachedFrameFraction;
            long steps = Math.round(aimAccDY / gcd);
            aimAccDY  -= steps * gcd;
            result    += steps;
        }

        return result;
    }

    /**
     * The smallest rotation a real mouse can produce at the current sensitivity.
     *
     * <p>Vanilla scales raw cursor delta by {@code (sens*0.6+0.2)^3 * 8} and
     * {@code changeLookDirection} then multiplies by {@code 0.15}, so one pixel of
     * physical movement always turns the player by exactly
     * {@code (sens*0.6+0.2)^3 * 1.2} degrees. Every rotation reachable by hand is
     * therefore an integer multiple of this value -- which is what makes an
     * unquantized angle such a reliable tell.
     *
     * <p>Public because {@link RotationManager} quantizes against it; this is the one
     * definition of the constant, deliberately not duplicated.
     */
    public static double getGcdDouble() {
        if (mc.options == null) return 0.15;
        double sens = mc.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    public static float getGcd() {
        return (float) getGcdDouble();
    }

    private static float computeGcd() {
        return getGcd();
    }
}
