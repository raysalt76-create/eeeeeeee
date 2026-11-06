package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimingProfileTest {
    private static final int SAMPLES = 5_000;

    @AfterEach
    void restoreDefault() {
        TimingProfile.set(TimingProfile.DEFAULT);
    }

    @Test
    void exactDelayStaysInsideEveryProfileWindow() {
        double[][] windows = { { 1, 3 }, { 10, 15 }, { 26, 34 }, { 36, 40 } };
        String[] modes = { TimingProfile.BLATANT, TimingProfile.STRICT, TimingProfile.LEGIT, TimingProfile.ULTRA_LEGIT };
        for (int i = 0; i < modes.length; i++) {
            TimingProfile.set(modes[i]);
            for (int n = 0; n < SAMPLES; n++) {
                double exact = TimingProfile.delayMsExact();
                assertTrue(exact >= windows[i][0] - 1.0e-9 && exact <= windows[i][1] + 1.0e-9,
                        modes[i] + " exact delay out of window: " + exact);
            }
        }
    }

    @Test
    void wholeMillisecondDelayStaysInsideTheWindow() {
        TimingProfile.set(TimingProfile.STRICT);
        for (int n = 0; n < SAMPLES; n++) {
            long delay = TimingProfile.delayMs();
            assertTrue(delay >= 10 && delay <= 15, "Strict delay out of window: " + delay);
        }
    }

    @Test
    void tickConversionNeverExceedsOneTickBecauseNoProfileExceedsFortyMs() {
        for (String mode : new String[] { TimingProfile.LEGIT, TimingProfile.ULTRA_LEGIT }) {
            TimingProfile.set(mode);
            for (int n = 0; n < SAMPLES; n++) {
                int ticks = TimingProfile.delayTicks();
                assertTrue(ticks >= 0 && ticks <= 1, mode + " produced " + ticks + " ticks");
            }
        }
    }

    @Test
    void neverExceedsTheSameTickCap() {
        for (String mode : new String[] { TimingProfile.BLATANT, TimingProfile.STRICT,
                TimingProfile.LEGIT, TimingProfile.ULTRA_LEGIT, "unknown" }) {
            TimingProfile.set(mode);
            assertTrue(TimingProfile.maxDelayMs() <= TimingProfile.MAX_DELAY_MS);
        }
    }
}
