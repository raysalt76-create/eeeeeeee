package interstellarTikiTiks.apexx.client.gui.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiSoundsTest {

    @Test
    void everyPackSynthesisesEveryEventAsShortAudibleClips() {
        for (String pack : new String[] { "Creamy", "Clacky", "Soft" }) {
            for (UiSounds.Event event : UiSounds.Event.values()) {
                short[] pcm = UiSounds.synth(pack, event);
                assertNotNull(pcm, pack + " " + event);
                // Under a quarter second at 44.1 kHz: UI sounds must stay short.
                assertTrue(pcm.length > 0 && pcm.length <= 44100 / 4, pack + " " + event + " length " + pcm.length);
                int peak = 0;
                for (short s : pcm) peak = Math.max(peak, Math.abs((int) s));
                assertTrue(peak > 1000, pack + " " + event + " is silent");
                assertTrue(peak < 32767 * 0.6, pack + " " + event + " is too loud");
                // Ends faded out, so the clip never pops when it stops.
                assertTrue(Math.abs((int) pcm[pcm.length - 1]) < 200, pack + " " + event + " does not fade out");
            }
        }
    }
}
