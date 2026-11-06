package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.PlayerInput;

/**
 * Tick-quantised sprint reset shared by SmartWTap (all reset styles) and TriggerBot's crit sprint
 * release. One instance per owning module; {@link #next} is called once per movement tick from that
 * module's {@code MovementInputModifier}.
 *
 * <p>Why ticks and not milliseconds: movement input is sampled once per {@code tickMovement}, and
 * the server only learns about sprint through the STOP/START command that {@code sendSprintingPacket}
 * sends at the end of a tick. A reset therefore has to keep the sprint off across at least one whole
 * movement tick (STOP is sent) and then let it start again (START is sent). A millisecond timer can
 * start and end between two movement ticks and do nothing at all.
 *
 * <p>Plan: {@code delay} untouched ticks, then {@code hold} reset ticks, then optionally one tick
 * with the sprint input pressed so a player who sprints by double-tapping forward (no sprint key)
 * resumes sprinting, like pressing the sprint key once. Vanilla still checks hunger, item use,
 * blindness and collisions before it starts the sprint.
 */
public final class SprintResetPlan {

    public enum Kind {
        /** Release forward: vanilla stops the sprint when there is no forward input. */
        W_TAP,
        /** Release forward and press back. */
        S_TAP,
        /**
         * Withhold the sprint input so vanilla does not restart the sprint the hit just cleared;
         * forward input is kept. If the sprint already restarted, releasing the sprint key would not
         * stop it (vanilla keeps sprinting while forward is held), so that tick falls back to W_TAP.
         */
        SPRINT_KEY
    }

    /** What one movement tick of the plan changes. */
    public record Step(boolean releaseForward, boolean pressBack, boolean releaseSprint, boolean pressSprint) {
        public static final Step NONE = new Step(false, false, false, false);

        public boolean isNone() {
            return !releaseForward && !pressBack && !releaseSprint && !pressSprint;
        }
    }

    private Kind kind = Kind.W_TAP;
    private int delayTicks;
    private int holdTicks;
    private boolean restoreSprint;
    private boolean active;

    public void start(Kind kind, int delayTicks, int holdTicks, boolean restoreSprint) {
        this.kind = kind;
        this.delayTicks = Math.max(0, delayTicks);
        this.holdTicks = Math.max(1, holdTicks);
        this.restoreSprint = restoreSprint;
        this.active = true;
    }

    public void cancel() {
        active = false;
        delayTicks = 0;
        holdTicks = 0;
        restoreSprint = false;
    }

    public boolean isActive() {
        return active;
    }

    /** True once the delay is over and reset ticks remain (not during the delay or the restore tick). */
    public boolean isHolding() {
        return active && delayTicks == 0 && holdTicks > 0;
    }

    public Kind kind() {
        return kind;
    }

    /** Consumes one movement tick of the plan. */
    public Step next(boolean clientSprinting) {
        if (!active) {
            return Step.NONE;
        }
        if (delayTicks > 0) {
            delayTicks--;
            return Step.NONE;
        }
        if (holdTicks > 0) {
            holdTicks--;
            return switch (kind) {
                case W_TAP -> new Step(true, false, false, false);
                case S_TAP -> new Step(true, true, false, false);
                case SPRINT_KEY -> clientSprinting
                        ? new Step(true, false, false, false)
                        : new Step(false, false, true, false);
            };
        }
        active = false;
        if (restoreSprint) {
            restoreSprint = false;
            return new Step(false, false, false, true);
        }
        return Step.NONE;
    }

    /** Applies {@code step} to vanilla's input for this tick. */
    public static PlayerInput apply(Step step, PlayerInput input) {
        if (step == null || step.isNone() || input == null) {
            return input;
        }
        boolean forward = input.forward() && !step.releaseForward();
        boolean backward = input.backward() || step.pressBack();
        // A restore only makes sense with forward input; vanilla's canStartSprinting checks it too.
        boolean sprint = (input.sprint() && !step.releaseSprint()) || (step.pressSprint() && forward);
        return new PlayerInput(forward, backward, input.left(), input.right(), input.jump(), input.sneak(), sprint);
    }
}
