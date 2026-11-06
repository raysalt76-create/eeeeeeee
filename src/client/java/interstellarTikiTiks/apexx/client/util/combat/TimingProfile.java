package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Client-wide timing profile: the single source of every humanized module delay.
 *
 * <p>Modules do not own delay sliders. Anything that waits between two steps (hotbar swap to
 * action, container click to click, place to place, open to close) asks this class how long to
 * wait. The user picks one mode in the ClickGUI ("Timing Profile") and every delay in the client
 * follows it.
 *
 * <ul>
 *   <li><b>Blatant</b> - 1-3 ms. Effectively back-to-back.</li>
 *   <li><b>Strict</b> - 10-15 ms.</li>
 *   <li><b>Legit</b> - 26-34 ms (about 30).</li>
 *   <li><b>Ultra Legit</b> - 36-40 ms.</li>
 * </ul>
 *
 * <p>Every window tops out at {@link #MAX_DELAY_MS}. A swap that is followed by an attribute-based
 * action (mace smash, stun slam, breach, lunge, density) only works when both land in the same
 * tick, so no profile may ever wait longer than 40 ms between them. Samples are drawn from a
 * skewed distribution inside the window (usually prompt, occasionally late) and are hard bounded:
 * no outlier injection can push a delay past the window.
 *
 * <p>This is timing only. Whether a delay is taken inside the current tick is still decided by
 * {@code SubTickSequencer}, which compresses any delay to the remaining tick budget.
 */
public final class TimingProfile {

    /** Longest delay any profile may produce, ms. Same-tick swap + action depends on this. */
    public static final double MAX_DELAY_MS = 40.0;

    // Shield stun's mechanic-specific gaps, requested independently of the swap pacing profile.
    public static final long SHIELD_STUN_FIRST_GAP_MS = 19L; // inside the 15-23 ms window
    public static final long SHIELD_STUN_FINAL_GAP_MS = 30L;

    public static final String BLATANT = "Blatant";
    public static final String STRICT = "Strict";
    public static final String LEGIT = "Legit";
    public static final String ULTRA_LEGIT = "Ultra Legit";

    public static final String DEFAULT = LEGIT;

    private static volatile double minMs = 26.0;
    private static volatile double maxMs = 34.0;

    private TimingProfile() {
    }

    /** Applies a mode by its display name. Unknown names fall back to {@link #DEFAULT}. */
    public static void set(String mode) {
        if (BLATANT.equalsIgnoreCase(mode)) {
            apply(1.0, 3.0);
        } else if (STRICT.equalsIgnoreCase(mode)) {
            apply(10.0, 15.0);
        } else if (ULTRA_LEGIT.equalsIgnoreCase(mode)) {
            apply(36.0, 40.0);
        } else {
            apply(26.0, 34.0);
        }
    }

    private static void apply(double lo, double hi) {
        minMs = lo;
        maxMs = Math.min(hi, MAX_DELAY_MS);
    }

    public static double minDelayMs() {
        return minMs;
    }

    public static double maxDelayMs() {
        return maxMs;
    }

    /** One humanized delay in ms, inside the active window. */
    public static long delayMs() {
        return HumanProfile.triangularMs(minMs, maxMs, 0.4);
    }

    /** One humanized delay in ms as a double, for the sub-tick scheduler. */
    public static double delayMsExact() {
        double lo = minMs;
        double hi = maxMs;
        if (hi - lo < 1.0e-9) {
            return lo;
        }
        return Math.max(lo, Math.min(hi,
                HumanProfile.triangularMs(lo * 1000.0, hi * 1000.0, 0.4) / 1000.0));
    }

    /**
     * The same delay for tick-quantised state machines. A sub-tick remainder is honoured on
     * average through probabilistic rounding instead of always being floored to zero.
     */
    public static int delayTicks() {
        double ms = delayMsExact();
        int whole = (int) (ms / 50.0);
        double frac = (ms - whole * 50.0) / 50.0;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < frac) {
            whole++;
        }
        return whole;
    }
}
