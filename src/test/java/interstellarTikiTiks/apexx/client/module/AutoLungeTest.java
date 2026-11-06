package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.module.implementation.movement.AutoLunge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoLungeTest {

    @Test
    public void testAutoLungeIsOneShotAndExposesRepeatSetting() {
        AutoLunge lunge = new AutoLunge();

        assertTrue(lunge.isOneShot(), "AutoLunge must be a one-shot trigger module");
        assertTrue(lunge.getSettings().stream()
                .anyMatch(setting -> setting.getName().equals("Hold to Repeat")));
        assertTrue(lunge.getSettings().stream()
                .anyMatch(setting -> setting.getName().equals("Repeat Speed (lunges/s)")));
        assertFalse(lunge.isOneShotRepeat(), "Hold to Repeat defaults to off");
    }

    @Test
    public void testAutoLungeSettingsDefaults() {
        AutoLunge lunge = new AutoLunge();

        // AutoLunge is legit-only; the instant (blatant) behaviour lives in the separate InstantLunge module.
        org.junit.jupiter.api.Assertions.assertNull(lunge.getSetting("Mode"));
        interstellarTikiTiks.apexx.client.module.implementation.blatant.InstantLunge instant =
                new interstellarTikiTiks.apexx.client.module.implementation.blatant.InstantLunge();
        org.junit.jupiter.api.Assertions.assertEquals(Category.BLATANT, instant.getCategory());
        assertFalse(((BooleanSetting) instant.getSetting("LMB Lunge")).isEnabled(),
                "InstantLunge must not fire from a plain left click by default");

        // The packet-only "Silent Mode" is gone: it sent raw slot packets, which the compatibility rules forbid.
        org.junit.jupiter.api.Assertions.assertNull(lunge.getSetting("Silent Mode"));

        Setting lmbSetting = lunge.getSetting("LMB Lunge");
        assertTrue(lmbSetting instanceof BooleanSetting);
        assertTrue(((BooleanSetting) lmbSetting).isEnabled());

        Setting swapBackSetting = lunge.getSetting("Swap Back");
        assertTrue(swapBackSetting instanceof BooleanSetting);
        assertTrue(((BooleanSetting) swapBackSetting).isEnabled());

        assertTrue(AutoLunge.isNonWeapon(null), "Null stack is non-weapon");
    }

    @Test
    public void testAimAssistAndStopLungeSettingsDefaultOff() {
        AutoLunge lunge = new AutoLunge();

        for (String name : new String[] {"Aim Assist", "Silent Aim", "Stop Lunge"}) {
            Setting setting = lunge.getSetting(name);
            assertTrue(setting instanceof BooleanSetting, name + " must exist as a toggle");
            assertFalse(((BooleanSetting) setting).isEnabled(), name + " defaults to off");
        }
        for (String name : new String[] {"Aim Range", "Aim FOV", "Aim Speed", "Stop Min Distance", "Stop Max Distance"}) {
            assertTrue(lunge.getSetting(name) instanceof SliderSetting, name + " must exist as a slider");
        }
    }

    @Test
    public void testSubSettingsAreHiddenUntilTheirToggleIsOn() {
        AutoLunge lunge = new AutoLunge();

        assertFalse(lunge.getSetting("Aim Range").isVisible());
        assertFalse(lunge.getSetting("Silent Aim").isVisible());
        assertFalse(lunge.getSetting("Stop Min Distance").isVisible());

        ((BooleanSetting) lunge.getSetting("Aim Assist")).setEnabled(true);
        ((BooleanSetting) lunge.getSetting("Stop Lunge")).setEnabled(true);

        assertTrue(lunge.getSetting("Aim Range").isVisible());
        assertTrue(lunge.getSetting("Aim FOV").isVisible());
        assertTrue(lunge.getSetting("Silent Aim").isVisible());
        assertTrue(lunge.getSetting("Stop Min Distance").isVisible());
        assertTrue(lunge.getSetting("Stop Max Distance").isVisible());
    }

    @Test
    public void testStopBandIsClosedAndOrderIndependent() {
        assertTrue(AutoLunge.inStopBand(2.0, 1.0, 3.0));
        assertTrue(AutoLunge.inStopBand(1.0, 1.0, 3.0));
        assertTrue(AutoLunge.inStopBand(3.0, 1.0, 3.0));
        assertTrue(AutoLunge.inStopBand(2.0, 3.0, 1.0), "the two values may be set in either order");
        assertFalse(AutoLunge.inStopBand(3.01, 1.0, 3.0));
        assertFalse(AutoLunge.inStopBand(0.99, 1.0, 3.0));
        assertFalse(AutoLunge.inStopBand(Double.POSITIVE_INFINITY, 0.0, 12.0), "no opponent is never inside the band");
    }

    @Test
    public void testStopBandUsesHorizontalDistanceOnly() {
        // 3 blocks across and 40 blocks up is still 3 blocks apart horizontally.
        assertTrue(AutoLunge.horizontalDistance(0.0, 0.0, 3.0, 0.0) == 3.0);
        assertTrue(AutoLunge.horizontalDistance(1.0, 1.0, 4.0, 5.0) == 5.0);
    }

    @Test
    public void testAimConeUsesFullWidth() {
        assertTrue(AutoLunge.withinCone(44.9, 90.0));
        assertTrue(AutoLunge.withinCone(45.0, 90.0));
        assertFalse(AutoLunge.withinCone(45.1, 90.0));
        assertTrue(AutoLunge.withinCone(180.0, 360.0));
    }
}
