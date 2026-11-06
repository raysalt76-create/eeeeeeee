package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Human click-interval model shared by the Bedwars clickers. One interval is drawn per click (never per
 * frame, which would skew the rate towards the fastest draw), shaped by a pattern, slowed by burst
 * fatigue, and occasionally stretched by a hesitation spike.
 */
final class ClickTiming {

    static final String GAUSSIAN = "Gaussian";
    static final String BUTTERFLY = "Butterfly";
    static final String JITTER = "Jitter";

    /** Shortest interval ever produced, ms (a hard physical floor for a finger). */
    private static final double MIN_INTERVAL_MS = 25.0;
    /** Fatigue never slows the rate below this fraction of the drawn rate. */
    private static final double FATIGUE_FLOOR = 0.70;

    private long burstStartMs = -1L;
    private boolean butterflyShort;

    void reset() {
        burstStartMs = -1L;
        butterflyShort = false;
    }

    /**
     * The wait before the next click, ms.
     *
     * @param minCps       lower end of the CPS band
     * @param maxCps       upper end of the CPS band
     * @param pattern      {@link #GAUSSIAN}, {@link #BUTTERFLY} or {@link #JITTER}
     * @param fatiguePct   rate lost per second of continuous clicking, percent
     * @param spikeChance  chance (0..1) that this interval gets a hesitation spike
     * @param spikeMs      size of a spike, ms (already jittered by the caller)
     */
    long nextIntervalMs(long nowMs, double minCps, double maxCps, String pattern,
                        double fatiguePct, double spikeChance, double spikeMs) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double lo = Math.max(0.5, Math.min(minCps, maxCps));
        double hi = Math.max(lo, Math.max(minCps, maxCps));
        if (burstStartMs < 0L) burstStartMs = nowMs;

        double cps;
        if (JITTER.equals(pattern)) {
            // Uniform band plus a log-normal wobble: occasional fast and slow outliers, like a jitter clicker's hand.
            cps = lo + r.nextDouble() * (hi - lo);
            cps *= Math.exp(r.nextGaussian() * 0.12);
        } else {
            double mid = (lo + hi) * 0.5;
            double sd = Math.max(0.15, (hi - lo) / 4.0);
            cps = mid + r.nextGaussian() * sd;
        }
        cps = Math.max(lo * 0.85, Math.min(hi * 1.10, cps));

        double burstSeconds = (nowMs - burstStartMs) / 1000.0;
        if (burstSeconds > 1.0 && fatiguePct > 0.0) {
            double factor = Math.max(FATIGUE_FLOOR, 1.0 - (burstSeconds - 1.0) * fatiguePct / 100.0);
            cps *= factor;
        }

        double interval = 1000.0 / Math.max(0.5, cps);
        if (BUTTERFLY.equals(pattern)) {
            // Butterfly clicking comes in pairs: a short gap between the two fingers, then a longer one.
            butterflyShort = !butterflyShort;
            interval *= butterflyShort ? 0.45 + r.nextDouble() * 0.15 : 1.40 + r.nextDouble() * 0.15;
        }
        if (spikeChance > 0.0 && r.nextDouble() < spikeChance) {
            interval += Math.max(0.0, spikeMs) * (0.6 + r.nextDouble() * 0.8);
        }
        return Math.round(Math.max(MIN_INTERVAL_MS, interval));
    }

    static boolean roll(double chance01) {
        return chance01 > 0.0 && ThreadLocalRandom.current().nextDouble() < chance01;
    }
}
