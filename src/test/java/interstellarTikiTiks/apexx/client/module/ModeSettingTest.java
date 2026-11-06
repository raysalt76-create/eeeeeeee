package interstellarTikiTiks.apexx.client.module;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ModeSettingTest {

    @Test
    public void testCycleNormalProgression() {
        ModeSetting setting = new ModeSetting("TestMode", "Mode1", "Mode2", "Mode3");

        assertEquals("Mode1", setting.getCurrentMode());

        setting.cycle();
        assertEquals("Mode2", setting.getCurrentMode());

        setting.cycle();
        assertEquals("Mode3", setting.getCurrentMode());
    }

    @Test
    public void testCycleWraparound() {
        ModeSetting setting = new ModeSetting("TestMode", "Mode1", "Mode2");

        assertEquals("Mode1", setting.getCurrentMode());

        setting.cycle();
        assertEquals("Mode2", setting.getCurrentMode());

        setting.cycle();
        assertEquals("Mode1", setting.getCurrentMode());
    }

    @Test
    public void testCycleCallsListener() {
        ModeSetting setting = new ModeSetting("TestMode", "Mode1", "Mode2");

        final boolean[] listenerCalled = {false};
        setting.onChange(() -> listenerCalled[0] = true);

        setting.cycle();

        assertTrue(listenerCalled[0], "Listener should be called when cycling modes");
    }

    @Test
    public void testCycleSingleMode() {
        ModeSetting setting = new ModeSetting("TestMode", "Mode1");

        assertEquals("Mode1", setting.getCurrentMode());

        setting.cycle();
        assertEquals("Mode1", setting.getCurrentMode());
    }

    @Test
    public void testCycleEmptyModes() {
        ModeSetting setting = new ModeSetting("TestMode");

        assertEquals("", setting.getCurrentMode());

        assertDoesNotThrow(() -> setting.cycle());
        assertEquals("", setting.getCurrentMode());
    }

    @Test
    public void testSetCurrentModeInvalid() {
        ModeSetting setting = new ModeSetting("TestMode", "Mode1", "Mode2");

        assertEquals("Mode1", setting.getCurrentMode());

        setting.setCurrentMode("UnknownMode");

        assertEquals("Mode1", setting.getCurrentMode(), "Should fallback to index 0 when unknown mode is set");
    }

    @Test
    public void testSetCurrentModeEmpty() {
        ModeSetting setting = new ModeSetting("TestMode");

        assertEquals("", setting.getCurrentMode());

        setting.setCurrentMode("UnknownMode");

        assertEquals("", setting.getCurrentMode());
    }
}
