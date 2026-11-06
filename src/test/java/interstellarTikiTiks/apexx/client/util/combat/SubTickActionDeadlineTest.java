package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SubTickActionDeadlineTest {
    @AfterEach
    void cleanup() {
        ExecutionMode.set(ExecutionMode.LEGIT);
        Thread.interrupted();
    }

    @Test
    void mechanicDeadlineIsNotCollapsedInEitherExecutionMode() {
        for (String mode : new String[] { ExecutionMode.LEGIT, ExecutionMode.DIRECT }) {
            ExecutionMode.set(mode);
            long deadline = System.nanoTime() + 8_000_000L;
            assertTrue(SubTickSequencer.awaitActionDeadline(deadline, () -> true, () -> 45.0), mode);
            assertTrue(System.nanoTime() >= deadline, mode + " must retain the requested separation");
        }
    }

    @Test
    void deadlineOutsideTheTickBudgetIsRefusedRatherThanCompressed() {
        assertFalse(SubTickSequencer.awaitActionDeadline(System.nanoTime() + 100_000_000L,
                () -> true, () -> 45.0));
        assertFalse(SubTickSequencer.awaitActionDeadline(System.nanoTime(), () -> true, () -> 0.0));
    }

    @Test
    void closedActionContextRejectsEvenAnOverdueDeadline() {
        assertFalse(SubTickSequencer.awaitActionDeadline(System.nanoTime(), () -> false, () -> 45.0));
    }

    @Test
    void contextIsRevalidatedAfterWaiting() {
        AtomicBoolean open = new AtomicBoolean(true);
        assertFalse(SubTickSequencer.awaitActionDeadline(System.nanoTime(),
                () -> open.getAndSet(false), () -> 45.0));
    }

    @Test
    void interruptionCancelsTheWaitWithoutConsumingTheDeadline() {
        Thread.currentThread().interrupt();
        assertFalse(SubTickSequencer.awaitActionDeadline(System.nanoTime() + 8_000_000L,
                () -> true, () -> 45.0));
    }
}
