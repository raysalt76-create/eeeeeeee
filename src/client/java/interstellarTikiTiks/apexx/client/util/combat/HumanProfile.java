package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared human-timing model for automated actions.
 *
 * <h2>Why this exists</h2>
 * Timing was previously scattered across four helpers with no shared model:
 * {@link HumanClick} (uniform jitter), {@link ReactionTiming} (good primitives, almost
 * no callers), {@code CombatRandomUtil} (one function) and {@code ModuleRandomDelay}
 * (biased {@code floorMod} sampling on a shared, non-thread-safe {@code Random}).
 * Meanwhile the single most developed timing model in the client sat unexported inside
 * AutoTotem. This class generalises that model so every module can reach it.
 *
 * <h2>What a detector actually looks at</h2>
 * The give-away is rarely any one delay -- it is the <em>shape of the distribution</em>
 * over many actions. Uniform jitter produces a rectangle; a real hand produces a
 * right-skewed bell with a long tail and a standard deviation that never collapses.
 * So the model layers, in order:
 *
 * <ol>
 *   <li><b>Log-normal core.</b> Motor delays are multiplicative, not additive -- being
 *       slow tends to be slow by a factor. exp(N(0,sigma)) against the mean gives the
 *       characteristic right skew that a uniform draw cannot.</li>
 *   <li><b>Bimodal fumble.</b> Humans occasionally mis-hit and recover. A per-action
 *       coin flip between a fast mean and a slow one reproduces the second hump that
 *       shows up in real click histograms.</li>
 *   <li><b>Tempo drift.</b> Nobody holds one pace. Re-rolling a small offset on the
 *       mean every few actions makes the centre wander the way attention does.</li>
 *   <li><b>Sigma floor.</b> The important one. Even with all of the above, a tight
 *       configuration can still produce a suspiciously consistent series. This keeps a
 *       rolling window of realised intervals and, whenever their standard deviation
 *       drops under a floor, deliberately injects an outlier. It is the only defence
 *       here that reacts to what actually happened rather than to what was configured.</li>
 * </ol>
 *
 * <h2>Reaction times</h2>
 * {@link #nextReactionMs()} samples an ex-Gaussian -- a Gaussian plus an exponential
 * tail -- which is the standard model for human reaction latency, because RT
 * distributions are reliably right-skewed and a plain Gaussian under-produces slow
 * responses. Rough anchors: simple visual RT sits near mu=200ms sigma=25ms tau=60ms;
 * choice reactions are slower and more skewed.
 *
 * <p>Instances are cheap and are meant to be held per module (or per distinct action
 * within a module) so their drift and interval history stay independent. Mutating
 * methods are synchronised because modules tick on the client thread while
 * {@code onMs} runs on a separate 1 ms timer thread.
 */
public final class HumanProfile {

    /** Rolling window of realised intervals used by the sigma floor. */
    private static final int INTERVAL_HISTORY = 12;

    /** Below this many samples the sigma estimate is too noisy to act on. */
    private static final int MIN_SAMPLES_FOR_SIGMA = 4;

    private final String name;

    // ── Core delay shape ────────────────────────────────────────────────────────
    private double fastMeanMs = 90.0;
    private double slowMeanMs = 170.0;
    private double fumbleChance = 0.12;
    private double logNormalSigma = 0.5;
    private double jitterMs = 12.0;
    private double minClampMs = 0.0;
    private double maxClampMs = 2000.0;

    // ── Tempo drift ─────────────────────────────────────────────────────────────
    private double driftMagnitudeMs = 50.0;
    private int driftMinActions = 3;
    private int driftMaxActions = 8;
    private double driftOffsetMs = 0.0;
    private int actionsSinceDrift = 0;

    // ── Sigma floor ─────────────────────────────────────────────────────────────
    private boolean varianceGuard = true;
    private double sigmaFloorMs = 35.0;
    private double injectMinMs = 150.0;
    private double injectMaxMs = 550.0;
    private long injectionCount = 0L;

    // ── Reaction (ex-Gaussian) ──────────────────────────────────────────────────
    private double reactionMeanMs = 200.0;
    private double reactionStdevMs = 25.0;
    private double reactionTauMs = 60.0;

    // ── Click rate ──────────────────────────────────────────────────────────────
    private double targetCps = 8.0;
    private double cpsDeviation = 1.2;
    private int burstMinLen = 3;
    private int burstMaxLen = 7;
    private double burstRestMinMs = 120.0;
    private double burstRestMaxMs = 320.0;
    private double burstChance = 0.35;
    private int clicksLeftInBurst = 0;
    private boolean burstActive = false;

    private final Deque<Long> recentIntervals = new ArrayDeque<>(INTERVAL_HISTORY);
    private long lastActionAtMs = 0L;

    public HumanProfile(String name) {
        this.name = name == null ? "unnamed" : name;
    }

    public String getName() {
        return name;
    }

    // ── Configuration (fluent; all optional, defaults are sane) ─────────────────

    /**
     * @param fastMeanMs   centre of the normal-response mode
     * @param slowMeanMs   centre of the fumble mode
     * @param fumbleChance probability in [0,1] of drawing the slow mode
     */
    public synchronized HumanProfile modes(double fastMeanMs, double slowMeanMs, double fumbleChance) {
        this.fastMeanMs = Math.max(0.0, fastMeanMs);
        this.slowMeanMs = Math.max(0.0, slowMeanMs);
        this.fumbleChance = clamp01(fumbleChance);
        return this;
    }

    /**
     * @param logNormalSigma spread of the multiplicative core; 0 removes the skew
     * @param jitterMs       additive Gaussian noise on top
     */
    public synchronized HumanProfile shape(double logNormalSigma, double jitterMs) {
        this.logNormalSigma = Math.max(0.0, logNormalSigma);
        this.jitterMs = Math.max(0.0, jitterMs);
        return this;
    }

    public synchronized HumanProfile clampMs(double minMs, double maxMs) {
        this.minClampMs = Math.max(0.0, Math.min(minMs, maxMs));
        this.maxClampMs = Math.max(this.minClampMs, Math.max(minMs, maxMs));
        return this;
    }

    public synchronized HumanProfile drift(double magnitudeMs, int minActions, int maxActions) {
        this.driftMagnitudeMs = Math.max(0.0, magnitudeMs);
        this.driftMinActions = Math.max(1, minActions);
        this.driftMaxActions = Math.max(this.driftMinActions, maxActions);
        return this;
    }

    /**
     * Configures the sigma floor. Set {@code enabled} false only to measure how
     * detectable the un-guarded distribution is.
     */
    public synchronized HumanProfile varianceGuard(boolean enabled, double sigmaFloorMs,
                                                   double injectMinMs, double injectMaxMs) {
        this.varianceGuard = enabled;
        this.sigmaFloorMs = Math.max(0.0, sigmaFloorMs);
        this.injectMinMs = Math.max(0.0, Math.min(injectMinMs, injectMaxMs));
        this.injectMaxMs = Math.max(this.injectMinMs, Math.max(injectMinMs, injectMaxMs));
        return this;
    }

    public synchronized HumanProfile reaction(double meanMs, double stdevMs, double tauMs) {
        this.reactionMeanMs = Math.max(0.0, meanMs);
        this.reactionStdevMs = Math.max(0.0, stdevMs);
        this.reactionTauMs = Math.max(0.0, tauMs);
        return this;
    }

    public synchronized HumanProfile cps(double targetCps, double deviation) {
        this.targetCps = Math.max(0.1, targetCps);
        this.cpsDeviation = Math.max(0.0, deviation);
        return this;
    }

    public synchronized HumanProfile burst(int minLen, int maxLen, double restMinMs,
                                           double restMaxMs, double chance) {
        this.burstMinLen = Math.max(1, minLen);
        this.burstMaxLen = Math.max(this.burstMinLen, maxLen);
        this.burstRestMinMs = Math.max(0.0, Math.min(restMinMs, restMaxMs));
        this.burstRestMaxMs = Math.max(this.burstRestMinMs, Math.max(restMinMs, restMaxMs));
        this.burstChance = clamp01(chance);
        return this;
    }

    // ── Sampling ────────────────────────────────────────────────────────────────

    /**
     * Full model: bimodal mean, tempo drift, log-normal core, additive jitter, clamp,
     * then a sigma-floor outlier if the recent series has gone too regular.
     *
     * <p><b>The configured mean behaves as a median.</b> Because the log-normal factor
     * has expectation {@code exp(sigma^2/2)} rather than 1, the realised average sits
     * above the configured value -- at the default sigma of 0.5 that is about +13%, so
     * a 100 ms setting yields a 100 ms median but a ~113 ms mean. That is inherent to
     * a right-skewed delay and is the intended shape; it is documented because the
     * discrepancy otherwise looks like a bug when a setting is measured.
     */
    public synchronized long nextDelayMs() {
        advanceDrift();

        boolean fumble = ThreadLocalRandom.current().nextDouble() < fumbleChance;
        double mean = (fumble ? slowMeanMs : fastMeanMs) + driftOffsetMs;
        if (mean < 0.0) {
            mean = 0.0;
        }

        double delay = mean * logNormalFactor(logNormalSigma);
        if (jitterMs > 0.0) {
            delay += ThreadLocalRandom.current().nextGaussian() * jitterMs;
        }

        double clamped = Math.max(minClampMs, Math.min(delay, maxClampMs));

        // Applied AFTER the clamp on purpose: the whole point is to escape the band
        // the clamp defines, because a hard clamp is itself a detectable signature.
        if (shouldInjectVariance()) {
            clamped += ReactionTiming.uniform(injectMinMs, injectMaxMs);
            injectionCount++;
        }
        return Math.max(0L, Math.round(clamped));
    }

    /** Ex-Gaussian reaction latency: Gaussian body plus an exponential right tail. */
    public synchronized long nextReactionMs() {
        return exGaussianMs(reactionMeanMs, reactionStdevMs, reactionTauMs);
    }

    /**
     * Gap until the next click, honouring the configured CPS and the burst/rest
     * structure. Sustained human clicking sits around 6-8 CPS; short bursts reach
     * 10-14 but cannot be held, which is exactly what the rest period models.
     */
    public synchronized long nextClickGapMs() {
        if (burstActive) {
            clicksLeftInBurst--;
            if (clicksLeftInBurst <= 0) {
                burstActive = false;
                return Math.round(ReactionTiming.uniform(burstRestMinMs, burstRestMaxMs));
            }
        } else if (burstChance > 0.0 && ThreadLocalRandom.current().nextDouble() < burstChance) {
            burstActive = true;
            clicksLeftInBurst = ThreadLocalRandom.current().nextInt(burstMinLen, burstMaxLen + 1);
        }

        double cps = targetCps;
        if (cpsDeviation > 0.0) {
            cps += ThreadLocalRandom.current().nextGaussian() * cpsDeviation;
        }
        cps = Math.max(0.5, cps);

        double gap = 1000.0 / cps;
        // Same multiplicative skew as the main model, at reduced spread: click gaps
        // are tighter than decision delays but still not symmetric.
        gap *= logNormalFactor(logNormalSigma * 0.35);
        return Math.max(1L, Math.round(gap));
    }

    /**
     * Records that an action actually fired. Feeds the interval history the sigma
     * floor reads, so it MUST be called on every realised action or the guard is
     * measuring nothing.
     */
    public synchronized void recordAction() {
        long now = System.currentTimeMillis();
        if (lastActionAtMs > 0L) {
            recentIntervals.addLast(now - lastActionAtMs);
            while (recentIntervals.size() > INTERVAL_HISTORY) {
                recentIntervals.pollFirst();
            }
        }
        lastActionAtMs = now;
    }

    /** Clears drift, burst and interval state. Call from a module's onDisable. */
    public synchronized void reset() {
        recentIntervals.clear();
        lastActionAtMs = 0L;
        driftOffsetMs = 0.0;
        actionsSinceDrift = 0;
        burstActive = false;
        clicksLeftInBurst = 0;
    }

    // ── Introspection: makes the guard auditable instead of a black box ─────────

    /** Standard deviation of the realised intervals, or -1 with too few samples. */
    public synchronized double observedSigmaMs() {
        int n = recentIntervals.size();
        if (n < MIN_SAMPLES_FOR_SIGMA) {
            return -1.0;
        }
        double mean = 0.0;
        for (long v : recentIntervals) {
            mean += v;
        }
        mean /= n;
        double variance = 0.0;
        for (long v : recentIntervals) {
            double d = v - mean;
            variance += d * d;
        }
        return Math.sqrt(variance / n);
    }

    public synchronized int sampleCount() {
        return recentIntervals.size();
    }

    /** How many times the sigma floor has had to intervene. */
    public synchronized long injectionCount() {
        return injectionCount;
    }

    // ── Internals ───────────────────────────────────────────────────────────────

    private void advanceDrift() {
        if (driftMagnitudeMs <= 0.0) {
            driftOffsetMs = 0.0;
            return;
        }
        actionsSinceDrift++;
        int threshold = driftMinActions
                + ThreadLocalRandom.current().nextInt(driftMaxActions - driftMinActions + 1);
        if (actionsSinceDrift >= threshold) {
            actionsSinceDrift = 0;
            driftOffsetMs = ReactionTiming.offset(driftMagnitudeMs);
        }
    }

    private boolean shouldInjectVariance() {
        if (!varianceGuard) {
            return false;
        }
        double sigma = observedSigmaMs();
        return sigma >= 0.0 && sigma < sigmaFloorMs;
    }

    // ── Stateless samplers (usable without an instance) ─────────────────────────

    /** exp(N(0, sigma)) -- multiplicative factor with a mean slightly above 1. */
    public static double logNormalFactor(double sigma) {
        if (sigma <= 1.0e-9) {
            return 1.0;
        }
        return Math.exp(ThreadLocalRandom.current().nextGaussian() * sigma);
    }

    /** Log-normally distributed delay centred on {@code meanMs}. */
    public static long logNormalMs(double meanMs, double sigma) {
        return Math.max(0L, Math.round(Math.max(0.0, meanMs) * logNormalFactor(sigma)));
    }

    /**
     * Ex-Gaussian: {@code N(mean, stdev) + Exp(tau)}. The exponential term supplies the
     * long right tail that real reaction-time data always shows and a pure Gaussian
     * never produces.
     */
    public static long exGaussianMs(double meanMs, double stdevMs, double tauMs) {
        double v = meanMs;
        if (stdevMs > 1.0e-9) {
            v += ThreadLocalRandom.current().nextGaussian() * stdevMs;
        }
        if (tauMs > 1.0e-9) {
            // Inverse-transform sample of Exp(1/tau). nextDouble() is [0,1), so the
            // 1 - u keeps the log argument off zero.
            v += -tauMs * Math.log(1.0 - ThreadLocalRandom.current().nextDouble());
        }
        return Math.max(0L, Math.round(v));
    }

    /**
     * Asymmetric triangular draw over [minMs, maxMs] peaking at {@code mode01} of the
     * way through. Good for "usually quick, occasionally slow" windows where a full
     * log-normal tail would be too long.
     */
    public static long triangularMs(double minMs, double maxMs, double mode01) {
        double lo = Math.min(minMs, maxMs);
        double hi = Math.max(minMs, maxMs);
        if (hi - lo < 1.0e-9) {
            return Math.max(0L, Math.round(lo));
        }
        double t = clamp01(mode01);
        double u = ThreadLocalRandom.current().nextDouble();
        double s = (u < t)
                ? Math.sqrt(u * t)
                : 1.0 - Math.sqrt((1.0 - u) * (1.0 - t));
        return Math.max(0L, Math.round(lo + s * (hi - lo)));
    }

    /**
     * Duration to hold a synthetic click down. Real presses vary by tens of
     * milliseconds; a constant hold is a per-click fingerprint on its own.
     */
    public static long clickHoldMs(double minMs, double maxMs) {
        return Math.max(1L, triangularMs(minMs, maxMs, 0.35));
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
