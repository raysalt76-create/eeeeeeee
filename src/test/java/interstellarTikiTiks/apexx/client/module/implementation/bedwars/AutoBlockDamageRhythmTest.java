package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoBlockDamageRhythmTest {

    @Test
    void needsThreeGapsBeforePredicting() {
        AutoBlock.DamageRhythm r = new AutoBlock.DamageRhythm();
        r.record(1_000L);
        r.record(1_500L);
        r.record(2_000L);
        assertFalse(r.isStable());
        r.record(2_500L);
        assertTrue(r.isStable());
        assertEquals(3_000L, r.expectedNextHit());
    }

    @Test
    void averagesTheLastGaps() {
        AutoBlock.DamageRhythm r = new AutoBlock.DamageRhythm();
        r.record(0L + 1L);
        r.record(401L);
        r.record(901L);
        r.record(1_501L); // gaps 400, 500, 600 -> mean 500
        assertEquals(2_001L, r.expectedNextHit());
    }

    @Test
    void implausibleGapRestartsTheRhythm() {
        AutoBlock.DamageRhythm r = new AutoBlock.DamageRhythm();
        r.record(1_000L);
        r.record(1_500L);
        r.record(2_000L);
        r.record(2_500L);
        assertTrue(r.isStable());
        r.record(9_000L); // a long pause between fights
        assertFalse(r.isStable());
        r.record(9_100L); // too fast to be a melee rhythm
        assertFalse(r.isStable());
    }

    @Test
    void resetClearsEverything() {
        AutoBlock.DamageRhythm r = new AutoBlock.DamageRhythm();
        for (long t = 1_000L; t <= 3_000L; t += 500L) r.record(t);
        assertTrue(r.isStable());
        r.reset();
        assertFalse(r.isStable());
    }
}
