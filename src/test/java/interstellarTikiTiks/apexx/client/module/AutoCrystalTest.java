package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.module.implementation.combat.AutoAnchor;
import interstellarTikiTiks.apexx.client.module.implementation.combat.AutoCrystal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

public class AutoCrystalTest {

    @Test
    public void testAutoAnchorHoldModeIsFalse() {
        AutoAnchor anchor = new AutoAnchor();
        assertFalse(anchor.isHoldMode(), "AutoAnchor should not default to hold mode");

        try (var auth = mockStatic(ApexxAuth.class)) {
            auth.when(ApexxAuth::isAuthenticated).thenReturn(true);
            anchor.setEnabled(true);
            assertTrue(anchor.isEnabled(), "AutoAnchor should be enableable when authenticated");
        }
    }

    @Test
    public void testAutoCrystalSettingsAndDefaults() {
        AutoCrystal crystal = new AutoCrystal();
        assertEquals(Category.COMBAT, crystal.getCategory());
        assertEquals(SubCategory.CRYSTAL, crystal.getSubCategory());

        Setting headbob = crystal.getSetting("Headbob");
        assertNotNull(headbob, "Headbob setting must exist");
        assertTrue(headbob instanceof BooleanSetting);
        assertTrue(((BooleanSetting) headbob).isEnabled(), "Headbob should default to true");

        Setting silentAim = crystal.getSetting("Silent Aim");
        assertNotNull(silentAim, "Silent Aim setting must exist");
        assertTrue(silentAim instanceof BooleanSetting);
        assertFalse(((BooleanSetting) silentAim).isEnabled(), "Silent Aim should default to false");

        Setting aimSpeed = crystal.getSetting("Aim Speed");
        assertNotNull(aimSpeed, "Aim Speed setting must exist");
        assertTrue(aimSpeed instanceof SliderSetting);
        SliderSetting speedSlider = (SliderSetting) aimSpeed;
        assertEquals(28.0, speedSlider.getValue(), 1e-4);
        assertEquals(10.0, speedSlider.getMin(), 1e-4);
        assertEquals(72.0, speedSlider.getMax(), 1e-4);

        Setting hand = crystal.getSetting("Hand");
        assertNotNull(hand, "Hand setting must exist");
        assertTrue(hand instanceof ModeSetting);
        assertEquals("Righty", ((ModeSetting) hand).getCurrentMode());
        ((ModeSetting) hand).setCurrentMode("Lefty");
        assertTrue(((ModeSetting) hand).is("Lefty"));

        Setting movement = crystal.getSetting("Head Movement");
        assertNotNull(movement, "Head Movement setting must exist");
        assertTrue(movement instanceof SliderSetting);
        assertTrue(((SliderSetting) movement).getValue() <= 25.0, "default sits at the low end");
        assertEquals(0.0, ((SliderSetting) movement).getMin(), 1e-4);
        assertEquals(100.0, ((SliderSetting) movement).getMax(), 1e-4);

        Setting variation = crystal.getSetting("Height Variation");
        assertNotNull(variation, "Height Variation setting must exist");
        assertTrue(variation instanceof BooleanSetting);
        assertTrue(((BooleanSetting) variation).isEnabled(), "Height Variation defaults on so bob depth varies");

        Setting switchBack = crystal.getSetting("Swap Back");
        assertNotNull(switchBack, "Swap Back setting must exist");
        assertTrue(switchBack instanceof BooleanSetting);
        assertFalse(((BooleanSetting) switchBack).isEnabled(), "Swap Back should default to false");

        assertEquals(AutoCrystal.BobState.IDLE, crystal.getBobState(), "Initial bob state should be IDLE");
        assertNull(crystal.getBobBase(), "Initial bob base should be null");
        assertFalse(crystal.isReturning());
    }
}
