package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Pure decision logic for AutoCrystal (no Minecraft types, unit-testable): damage-tick gating, the
 * hold/toggle activation rule, the pause-on-kill timer and the finish-then-off state.
 */
public final class CrystalLogic {

    private CrystalLogic() {
    }

    /**
     * Damage Tick: when enabled, a crystal is only broken while the target is outside its hurt window
     * ({@code hurtTime == 0}), so the blast actually deals damage. Disabled = always allowed.
     */
    public static boolean damageTickAllows(boolean damageTickEnabled, int targetHurtTime) {
        return !damageTickEnabled || targetHurtTime <= 0;
    }

    /** True for the Hold activation mode (case-insensitive); anything else is Toggle. */
    public static boolean isHold(String activationMode) {
        return "hold".equalsIgnoreCase(activationMode);
    }

    /**
     * Whether the module should be acting: Hold = only while the key is held, Toggle = while toggled on.
     */
    public static boolean active(boolean hold, boolean keyHeld, boolean toggledOn) {
        return hold ? keyHeld : toggledOn;
    }

    /** Target/self damage thresholds for a placement or a break. */
    public static boolean damageOk(float targetDamage, float selfDamage, float selfPool,
                                   float minTargetDamage, float maxSelfDamage) {
        return targetDamage >= minTargetDamage
                && selfDamage <= maxSelfDamage
                && selfDamage < selfPool - 0.5f;
    }

    /** Pause On Kill timer. {@code durationMs <= 0} pauses until {@link #reset()} (re-enable). */
    public static final class PauseGate {
        private long pausedUntil = 0L;
        private boolean paused = false;

        public void onKill(long nowMs, long durationMs) {
            paused = true;
            pausedUntil = durationMs <= 0L ? Long.MAX_VALUE : nowMs + durationMs;
        }

        public boolean isPaused(long nowMs) {
            if (!paused) return false;
            if (nowMs >= pausedUntil) {
                paused = false;
                return false;
            }
            return true;
        }

        public void reset() {
            paused = false;
            pausedUntil = 0L;
        }
    }

    /**
     * Always Break Last: a disable request is held off while the crystal that was just placed is still
     * standing, and the module only really turns off once it is broken or a timeout passes. A second
     * disable request while already finishing goes through immediately.
     */
    public static final class FinishGate {
        private boolean finishing = false;
        private long deadlineMs = 0L;

        /** @return true when the disable must be deferred (finish-then-off started). */
        public boolean requestDisable(boolean enabled, boolean crystalPending, long nowMs, long timeoutMs) {
            if (finishing) return false;
            if (enabled && crystalPending) {
                finishing = true;
                deadlineMs = nowMs + Math.max(0L, timeoutMs);
                return true;
            }
            return false;
        }

        public boolean isFinishing() {
            return finishing;
        }

        /** The module was enabled again before it finished turning off. */
        public void cancel() {
            finishing = false;
        }

        /** True when finishing and either nothing is pending any more or the timeout elapsed. */
        public boolean isDone(boolean crystalPending, long nowMs) {
            return finishing && (!crystalPending || nowMs >= deadlineMs);
        }

        public void reset() {
            finishing = false;
            deadlineMs = 0L;
        }
    }
}
