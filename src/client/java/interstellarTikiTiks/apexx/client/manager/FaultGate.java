package interstellarTikiTiks.apexx.client.manager;

/**
 * Decides what to do when one callback of one module throws.
 *
 * <p>The earlier rule counted consecutive failures and, after three, parked the module for the rest of the
 * session. That lets a transient condition (a world transition, a menu, a lost race with the render thread)
 * switch a module off with no way back, and it counted a module that fails only because no world is loaded
 * the same as one that is broken. Here a fault is judged by when it happens:
 *
 * <ul>
 *   <li>Outside a world it is expected: the callback is paused briefly and nothing is counted.</li>
 *   <li>Inside a world, faults are counted in a ten second window. The first two only pause the callback for
 *       a quarter of a second. The third trips the gate: the callback is suspended for 2 s, then 10 s, 30 s and
 *       120 s on each later trip, and the module keeps running every other callback meanwhile.</li>
 *   <li>Only a gate that keeps tripping (five trips without two quiet minutes between them) disables the module,
 *       and joining a world resets every gate, so nothing stays off for good because of an earlier world.</li>
 * </ul>
 *
 * <p>One gate belongs to one callback of one module. It is touched by whichever thread dispatches that callback,
 * so its methods are synchronized; the cost is a single uncontended lock per call.
 */
final class FaultGate {
    enum Action { CONTINUE, PAUSE, SUSPEND, DISABLE }

    static final long WINDOW_MS = 10_000L;
    static final int FAULTS_PER_TRIP = 3;
    static final long SHORT_PAUSE_MS = 250L;
    static final long OUT_OF_WORLD_PAUSE_MS = 1_000L;
    static final long[] BACKOFF_MS = {2_000L, 10_000L, 30_000L, 120_000L};
    static final int TRIPS_TO_DISABLE = 5;
    static final long QUIET_RESET_MS = 120_000L;

    private final long[] recent = new long[FAULTS_PER_TRIP];
    private int recentCount;
    private long suspendedUntil;
    private int trips;
    private long lastFaultAt;
    private long totalFaults;
    private String lastError = "";

    synchronized boolean allowed(long now) {
        return now >= suspendedUntil;
    }

    synchronized void onSuccess(long now) {
        if (trips > 0 && now - lastFaultAt > QUIET_RESET_MS) trips = 0;
    }

    synchronized Action onFault(long now, boolean inWorld, String error) {
        totalFaults++;
        lastFaultAt = now;
        lastError = error == null ? "" : error;
        if (!inWorld) {
            suspendedUntil = now + OUT_OF_WORLD_PAUSE_MS;
            return Action.PAUSE;
        }
        if (recentCount == recent.length) {
            System.arraycopy(recent, 1, recent, 0, recent.length - 1);
            recentCount--;
        }
        recent[recentCount++] = now;
        int inWindow = 0;
        for (int i = 0; i < recentCount; i++) if (now - recent[i] <= WINDOW_MS) inWindow++;
        if (inWindow < FAULTS_PER_TRIP) {
            suspendedUntil = now + SHORT_PAUSE_MS;
            return Action.PAUSE;
        }
        recentCount = 0;
        trips++;
        if (trips >= TRIPS_TO_DISABLE) return Action.DISABLE;
        suspendedUntil = now + BACKOFF_MS[Math.min(trips - 1, BACKOFF_MS.length - 1)];
        return Action.SUSPEND;
    }

    synchronized void reset() {
        recentCount = 0;
        suspendedUntil = 0L;
        trips = 0;
    }

    synchronized boolean faulting(long now) {
        return now < suspendedUntil || recentCount > 0 || trips > 0;
    }

    synchronized String describe(long now) {
        return "faults=" + totalFaults + ", trips=" + trips
                + (now < suspendedUntil ? ", suspended " + ((suspendedUntil - now + 999L) / 1000L) + "s more" : "")
                + (lastError.isEmpty() ? "" : ", last: " + lastError);
    }
}
