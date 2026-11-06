package interstellarTikiTiks.apexx.client.module.implementation.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AutoShieldBreakStunTest {
    private static final long MS = 1_000_000L;

    @Test
    void disableClickIsFollowedByTwoDistinctTimedAxeClicks() {
        var sequence = new AutoShieldBreak.StunSequence();
        AtomicLong now = new AtomicLong(100 * MS);
        List<Long> hits = new ArrayList<>();
        sequence.start(now.get());

        assertTrue(sequence.advance(now::get, deadline -> {
            now.set(deadline);
            return true;
        }, () -> {
            hits.add(now.get());
            return true;
        }));
        assertEquals(List.of(119 * MS, 149 * MS), hits);
        assertTrue(sequence.advance(now::get, deadline -> fail("completed sequence must not wait"),
                () -> fail("completed sequence must not click again")));
    }

    @Test
    void crossingMovementBoundaryRetainsTheFinalClickWithoutCompressingItsDelay() {
        var sequence = new AutoShieldBreak.StunSequence();
        AtomicLong now = new AtomicLong(10 * MS);
        List<Long> hits = new ArrayList<>();
        sequence.start(now.get());

        assertFalse(sequence.advance(now::get, deadline -> {
            if (deadline > 45 * MS) return false;
            now.set(deadline);
            return true;
        }, () -> { hits.add(now.get()); return true; }));
        assertEquals(List.of(29 * MS), hits);

        now.set(50 * MS); // next tick head, still 9 ms before the final click is due
        assertTrue(sequence.advance(now::get, deadline -> {
            assertEquals(59 * MS, deadline);
            now.set(Math.max(now.get(), deadline));
            return true;
        }, () -> { hits.add(now.get()); return true; }));
        assertEquals(List.of(29 * MS, 59 * MS), hits);
    }

    @Test
    void missedFirstWindowCannotBecomeAnImmediateDoubleClick() {
        var sequence = new AutoShieldBreak.StunSequence();
        AtomicLong now = new AtomicLong(0);
        List<Long> hits = new ArrayList<>();
        sequence.start(0);
        assertFalse(sequence.advance(now::get, deadline -> false, () -> fail("window is closed")));

        now.set(50 * MS);
        assertTrue(sequence.advance(now::get, deadline -> {
            now.set(Math.max(now.get(), deadline));
            return true;
        }, () -> { hits.add(now.get()); return true; }));
        assertEquals(List.of(50 * MS, 80 * MS), hits);
    }

    @Test
    void rejectedClickDoesNotConsumeAFollowUpOrScheduleFromTheOldBreakTime() {
        var sequence = new AutoShieldBreak.StunSequence();
        AtomicLong now = new AtomicLong(19 * MS);
        sequence.start(0);
        assertFalse(sequence.advance(now::get, deadline -> true, () -> false));

        List<Long> hits = new ArrayList<>();
        now.set(70 * MS);
        assertTrue(sequence.advance(now::get, deadline -> {
            now.set(Math.max(now.get(), deadline));
            return true;
        }, () -> { hits.add(now.get()); return true; }));
        assertEquals(List.of(70 * MS, 100 * MS), hits);
    }

    @Test
    void secondGapStartsAfterActualDispatchRatherThanBeforeDispatchWork() {
        var sequence = new AutoShieldBreak.StunSequence();
        AtomicLong now = new AtomicLong();
        List<Long> deadlines = new ArrayList<>();
        sequence.start(0);
        assertTrue(sequence.advance(now::get, deadline -> {
            deadlines.add(deadline);
            now.set(deadline);
            return true;
        }, () -> { now.addAndGet(3 * MS); return true; }));
        assertEquals(List.of(19 * MS, 52 * MS), deadlines);
    }

    @Test
    void resetCancelsPendingClickAndNewRunUsesItsOwnBreakTimestamp() {
        var sequence = new AutoShieldBreak.StunSequence();
        sequence.start(0);
        sequence.clear();
        assertTrue(sequence.advance(() -> 0, deadline -> fail("cancelled"), () -> fail("cancelled")));
        sequence.start(1_000 * MS);
        AtomicLong now = new AtomicLong();
        List<Long> hits = new ArrayList<>();
        assertTrue(sequence.advance(now::get, deadline -> { now.set(deadline); return true; },
                () -> { hits.add(now.get()); return true; }));
        assertEquals(List.of(1_019 * MS, 1_049 * MS), hits);
    }

    @Test
    void cancellationInsideAttackDoesNotDispatchAnotherFollowUp() {
        var sequence = new AutoShieldBreak.StunSequence();
        sequence.start(0);
        AtomicInteger hits = new AtomicInteger();
        assertFalse(sequence.advance(() -> 19 * MS, deadline -> true, () -> {
            hits.incrementAndGet();
            sequence.clear();
            return true;
        }));
        assertEquals(1, hits.get());
        assertTrue(sequence.advance(() -> 0, deadline -> fail("cancelled"), () -> fail("cancelled")));
    }
}
