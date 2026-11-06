package interstellarTikiTiks.apexx.client.util.combat;

/**
 * When the server's answer to a charged-spear contact should reach this client: the time from the
 * movement packet that carried the contact state to the server's damage (or totem) packet for it.
 *
 * <p>Why the spear needs this. The server evaluates the charged spear once per server tick on the last
 * movement it processed, and a slot change it processes first ends the charge (STATICALLY VERIFIED,
 * 1.21.11 {@code ServerPlayNetworkHandler.onUpdateSelectedSlot} clears the main-hand active item). The
 * client cannot see which server tick handled which of its packets: jitter or a slow server tick can
 * deliver the contact movement and a swap sent ticks later in the same batch, and the spear then never
 * evaluates. So no client-side delay makes "spear first" certain; the server's own damage packet does.
 * This model does not decide the swap. It predicts when that packet is due, so the caller keeps the
 * charge alive exactly as long as a registration can still arrive and knows when to stop waiting.
 *
 * <p>Estimator: smoothed mean and mean deviation of the samples (Jacobson/Karels, gains 1/8 and 1/4),
 * seeded from the tab-list ping plus one server tick until the first real sample. Deadline = mean +
 * 4 deviations + one client tick to observe the packet at a tick head, clamped.
 *
 * <p>Pure arithmetic, unit-tested; not RUNTIME VERIFIED.
 */
public final class SpearRegistrationModel {

    public static final double TICK_MS = 50.0;
    static final double MIN_DEADLINE_MS = 150.0;
    static final double MAX_DEADLINE_MS = 1500.0;
    /** Floor of the deviation, so one lucky run of equal samples does not shrink the window to nothing. */
    static final double MIN_DEVIATION_MS = 15.0;
    /** A sample past this is not a registration of the predicted contact (a later contact, or a stall). */
    static final double MAX_SAMPLE_MS = 2000.0;

    private double meanMs;
    private double deviationMs;
    private int samples;

    public SpearRegistrationModel() {
        reset();
    }

    public void reset() {
        meanMs = 2.0 * TICK_MS;
        deviationMs = 2.0 * TICK_MS;
        samples = 0;
    }

    /**
     * Prior from the tab-list ping (a smoothed keep-alive round trip): the packet makes that round trip
     * plus the wait for the server tick that evaluates it. Ignored once a real sample exists.
     */
    public void seed(double pingMs) {
        if (samples > 0 || !(pingMs >= 0.0)) {
            return;
        }
        meanMs = pingMs + TICK_MS;
        deviationMs = Math.max(2.0 * MIN_DEVIATION_MS, pingMs / 4.0 + TICK_MS / 2.0);
    }

    /** One measured registration: movement packet carrying the contact state -> server confirmation arrival. */
    public boolean addSample(double ms) {
        if (!(ms >= 0.0) || ms > MAX_SAMPLE_MS) {
            return false;
        }
        if (samples == 0) {
            meanMs = ms;
            deviationMs = Math.max(MIN_DEVIATION_MS, ms / 2.0);
        } else {
            double err = ms - meanMs;
            meanMs += err / 8.0;
            deviationMs = Math.max(MIN_DEVIATION_MS, deviationMs + (Math.abs(err) - deviationMs) / 4.0);
        }
        samples++;
        return true;
    }

    public double expectedMs() {
        return meanMs;
    }

    public double deviationMs() {
        return deviationMs;
    }

    public int samples() {
        return samples;
    }

    /** Longest a predicted contact may wait for its confirmation before it counts as not registered. */
    public double deadlineMs() {
        double d = meanMs + 4.0 * deviationMs + TICK_MS;
        return Math.max(MIN_DEADLINE_MS, Math.min(MAX_DEADLINE_MS, d));
    }

    /** {@link #deadlineMs()} in client ticks, rounded up. */
    public int deadlineTicks() {
        return (int) Math.ceil(deadlineMs() / TICK_MS);
    }

    /** True while a contact predicted {@code sinceContactMs} ago may still be confirmed. */
    public boolean awaiting(double sinceContactMs) {
        return sinceContactMs >= 0.0 && sinceContactMs <= deadlineMs();
    }

    /**
     * Longest the use key is held against a physical release, in client ticks: the strike envelope
     * ({@code leadTicks}), the registration deadline, then the follow-up window. Capped at 2 s.
     */
    public int guardCapTicks(int leadTicks, int followUpTicks) {
        return Math.min(40, Math.max(0, leadTicks) + deadlineTicks() + Math.max(0, followUpTicks));
    }
}
