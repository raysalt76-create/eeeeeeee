package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpearRegistrationModelTest {

    @Test
    void pingSeedsTheWindowUntilARealSampleArrives() {
        SpearRegistrationModel m = new SpearRegistrationModel();
        m.seed(150.0);
        assertEquals(200.0, m.expectedMs(), 1e-9, "round trip plus one server tick");
        assertTrue(m.deadlineMs() > 200.0);
        m.addSample(120.0);
        m.seed(400.0);
        assertEquals(120.0, m.expectedMs(), 1e-9, "a measured sample replaces the ping prior for good");
    }

    @Test
    void higherPingWaitsLonger() {
        SpearRegistrationModel low = new SpearRegistrationModel();
        SpearRegistrationModel high = new SpearRegistrationModel();
        low.seed(30.0);
        high.seed(200.0);
        assertTrue(high.deadlineMs() > low.deadlineMs());
        assertTrue(high.awaiting(400.0), "a 200 ms connection still expects its confirmation at 400 ms");
    }

    @Test
    void steadySamplesTightenAndJitterWidensTheWindow() {
        SpearRegistrationModel steady = new SpearRegistrationModel();
        SpearRegistrationModel jittery = new SpearRegistrationModel();
        for (int i = 0; i < 40; i++) {
            steady.addSample(150.0);
            jittery.addSample(i % 2 == 0 ? 100.0 : 260.0);
        }
        assertEquals(150.0, steady.expectedMs(), 1.0);
        assertEquals(SpearRegistrationModel.MIN_DEVIATION_MS, steady.deviationMs(), 1.0);
        assertTrue(jittery.deadlineMs() > steady.deadlineMs() + 100.0);
        assertTrue(jittery.awaiting(300.0));
    }

    @Test
    void deadlineIsClampedAndBadSamplesAreRejected() {
        SpearRegistrationModel m = new SpearRegistrationModel();
        assertFalse(m.addSample(-5.0));
        assertFalse(m.addSample(Double.NaN));
        assertFalse(m.addSample(SpearRegistrationModel.MAX_SAMPLE_MS + 1.0));
        assertEquals(0, m.samples());
        m.addSample(0.0);
        assertTrue(m.deadlineMs() >= SpearRegistrationModel.MIN_DEADLINE_MS);
        m.addSample(1900.0);
        m.addSample(1900.0);
        assertTrue(m.deadlineMs() <= SpearRegistrationModel.MAX_DEADLINE_MS);
        assertFalse(m.awaiting(-1.0));
        assertFalse(m.awaiting(SpearRegistrationModel.MAX_DEADLINE_MS + 1.0));
    }

    @Test
    void guardCoversEnvelopeRegistrationAndFollowUpButIsCapped() {
        SpearRegistrationModel m = new SpearRegistrationModel();
        m.seed(100.0);
        assertEquals(4 + m.deadlineTicks() + 8, m.guardCapTicks(4, 8));
        m.addSample(1500.0);
        assertEquals(40, m.guardCapTicks(4, 8), "never more than 2 s");
    }
}
