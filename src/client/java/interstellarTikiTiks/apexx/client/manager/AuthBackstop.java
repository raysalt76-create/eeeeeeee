package interstellarTikiTiks.apexx.client.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps modules off while the session is not authorised, and gives them back when it is again.
 *
 * <p>The backstop is level-triggered: every tick without a live session, every enabled module is switched
 * off, so a revoked session can never keep running. What it did not do was undo that. A session that lapsed
 * for a minute (the computer slept, the service could not be reached, a refresh was repaired) left every
 * module off for the rest of the run, and the player had to find and re-enable each one.
 *
 * <p>Two changes: a short grace period before anything is switched off, so a one-tick glitch costs nothing,
 * and a record of the modules the backstop itself switched off, which are re-enabled as soon as the session
 * is live again. A module the player had switched off is never in that record.
 *
 * @param <M> the module type; operations are passed in so the policy can be tested without a game
 */
final class AuthBackstop<M> {
    static final long GRACE_NANOS = TimeUnit.SECONDS.toNanos(20);

    private boolean counting;
    private long unauthorizedSinceNanos;
    private final List<M> forcedOff = new ArrayList<>();

    void onTick(boolean authorized, long nowNanos, Iterable<M> modules,
                Predicate<M> isEnabled, Consumer<M> disable, Consumer<M> enable) {
        if (authorized) {
            counting = false;
            if (!forcedOff.isEmpty()) {
                List<M> restore = new ArrayList<>(forcedOff);
                forcedOff.clear();
                for (M module : restore) {
                    try {
                        if (!isEnabled.test(module)) {
                            enable.accept(module);
                            // setEnabled can decline without throwing; keep it on the list and try next tick
                            // instead of forgetting a module the player never switched off.
                            if (!isEnabled.test(module)) forcedOff.add(module);
                        }
                    } catch (RuntimeException ignored) {
                        // One module that will not come back must not keep the others off.
                        forcedOff.add(module);
                    }
                }
            }
            return;
        }
        if (!counting) {
            counting = true;
            unauthorizedSinceNanos = nowNanos;
        }
        if (nowNanos - unauthorizedSinceNanos < GRACE_NANOS) return;
        for (M module : modules) {
            try {
                if (isEnabled.test(module)) {
                    forcedOff.add(module);
                    disable.accept(module);
                }
            } catch (RuntimeException ignored) {
                // Keep switching the rest off.
            }
        }
    }

    int forcedOffCount() {
        return forcedOff.size();
    }
}
