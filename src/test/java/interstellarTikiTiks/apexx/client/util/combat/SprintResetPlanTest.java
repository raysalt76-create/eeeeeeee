package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.PlayerInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SprintResetPlanTest {

    private static final SprintResetPlan.Step RELEASE_FORWARD = new SprintResetPlan.Step(true, false, false, false);
    private static final SprintResetPlan.Step S_TAP = new SprintResetPlan.Step(true, true, false, false);
    private static final SprintResetPlan.Step RELEASE_SPRINT = new SprintResetPlan.Step(false, false, true, false);
    private static final SprintResetPlan.Step PRESS_SPRINT = new SprintResetPlan.Step(false, false, false, true);

    @Test
    void wTapRunsDelayThenHoldThenRestore() {
        SprintResetPlan plan = new SprintResetPlan();
        plan.start(SprintResetPlan.Kind.W_TAP, 1, 2, true);

        assertTrue(plan.isActive());
        assertFalse(plan.isHolding(), "delay ticks are not holding");
        assertEquals(SprintResetPlan.Step.NONE, plan.next(true));
        assertTrue(plan.isHolding());
        assertEquals(RELEASE_FORWARD, plan.next(true));
        assertEquals(RELEASE_FORWARD, plan.next(false));
        assertFalse(plan.isHolding());
        assertEquals(PRESS_SPRINT, plan.next(false));
        assertFalse(plan.isActive());
        assertEquals(SprintResetPlan.Step.NONE, plan.next(false));
    }

    @Test
    void holdIsAtLeastOneTick() {
        SprintResetPlan plan = new SprintResetPlan();
        plan.start(SprintResetPlan.Kind.S_TAP, 0, 0, false);
        assertEquals(S_TAP, plan.next(true));
        assertEquals(SprintResetPlan.Step.NONE, plan.next(true));
        assertFalse(plan.isActive());
    }

    @Test
    void sprintKeyResetFallsBackToForwardReleaseOnceSprintRestarted() {
        SprintResetPlan plan = new SprintResetPlan();
        plan.start(SprintResetPlan.Kind.SPRINT_KEY, 0, 1, false);
        assertEquals(RELEASE_SPRINT, plan.next(false));

        plan.start(SprintResetPlan.Kind.SPRINT_KEY, 0, 1, false);
        assertEquals(RELEASE_FORWARD, plan.next(true));
    }

    @Test
    void cancelStopsThePlan() {
        SprintResetPlan plan = new SprintResetPlan();
        plan.start(SprintResetPlan.Kind.W_TAP, 0, 3, true);
        plan.cancel();
        assertFalse(plan.isActive());
        assertEquals(SprintResetPlan.Step.NONE, plan.next(true));
    }

    @Test
    void applyEditsOnlyTheRequestedInputs() {
        PlayerInput held = new PlayerInput(true, false, true, false, false, false, true);

        PlayerInput wTap = SprintResetPlan.apply(RELEASE_FORWARD, held);
        assertFalse(wTap.forward());
        assertTrue(wTap.left());
        assertTrue(wTap.sprint());

        PlayerInput sTap = SprintResetPlan.apply(S_TAP, held);
        assertFalse(sTap.forward());
        assertTrue(sTap.backward());

        PlayerInput noSprint = SprintResetPlan.apply(RELEASE_SPRINT, held);
        assertTrue(noSprint.forward());
        assertFalse(noSprint.sprint());

        assertSame(held, SprintResetPlan.apply(SprintResetPlan.Step.NONE, held));
    }

    @Test
    void restoreSprintNeedsForwardInput() {
        PlayerInput forwardOnly = new PlayerInput(true, false, false, false, false, false, false);
        assertTrue(SprintResetPlan.apply(PRESS_SPRINT, forwardOnly).sprint());

        PlayerInput standing = new PlayerInput(false, false, false, false, false, false, false);
        assertFalse(SprintResetPlan.apply(PRESS_SPRINT, standing).sprint());
    }
}
