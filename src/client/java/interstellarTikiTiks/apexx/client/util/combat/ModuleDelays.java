package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Module delays. Every module exposes its own delay sliders (in ms) and uses the value directly; there is no
 * client-wide delay and no "Use Timing Profile" toggle.
 */
public final class ModuleDelays {

    /** Upper bound for any configurable delay, ms. */
    public static final double MAX_CONFIGURED_MS = 1000.0;

    private ModuleDelays() {
    }

    /**
     * A configured ms delay as whole ticks for tick-quantised state machines. The sub-tick remainder
     * is kept on average by probabilistic rounding (30 ms -> 1 tick 60% of the time, else 0), so a
     * small ms value still has an effect instead of always flooring to zero.
     */
    public static int msToTicks(double configuredMs) {
        double ms = clampMs(configuredMs);
        int whole = (int) (ms / 50.0);
        double frac = (ms - whole * 50.0) / 50.0;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < frac) whole++;
        return whole;
    }

    /** A configured delay, clamped to [0, {@link #MAX_CONFIGURED_MS}]; NaN becomes 0. */
    public static long clampMs(double configuredMs) {
        if (!Double.isFinite(configuredMs) || configuredMs <= 0.0) return 0L;
        return Math.round(Math.min(configuredMs, MAX_CONFIGURED_MS));
    }
}
