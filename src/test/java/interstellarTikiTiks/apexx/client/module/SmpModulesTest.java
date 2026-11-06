package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.module.implementation.movement.InventoryMove;
import interstellarTikiTiks.apexx.client.module.implementation.smp.Flight;
import interstellarTikiTiks.apexx.client.module.implementation.smp.Freecam;
import interstellarTikiTiks.apexx.client.module.implementation.smp.Speed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SmpModulesTest {

    @Test
    public void smpModulesLiveInTheSmpCategory() {
        // Freecam stays in the SMP tab (AutoWalk moved to Movement; the ESPs moved to Render).
        Module freecam = new Freecam();
        assertEquals(Category.SMP, freecam.getCategory(), freecam.getName());
        assertFalse(freecam.isEnabled(), "Freecam must start off");
        assertEquals("SMP", Category.SMP.name);
    }

    @Test
    public void flightAndSpeedAreBlatantAndStartOff() {
        for (Module module : List.of(new Flight(), new Speed())) {
            assertEquals(Category.BLATANT, module.getCategory(), module.getName());
            assertFalse(module.isEnabled(), module.getName() + " must start off");
        }
    }

    @Test
    public void inventoryMoveIsBlatant() {
        assertEquals(Category.BLATANT, new InventoryMove().getCategory());
    }

    @Test
    public void flightIsTheVanillaModeOnly() {
        Flight flight = new Flight();
        for (String name : List.of("Horizontal Speed", "Vertical Speed", "Hover Drift", "Sprint Boost",
                "Anti Kick", "Disable On Setback")) {
            assertNotNull(flight.getSetting(name), name);
        }
        assertNull(flight.getSetting("Mode"), "no anticheat-specific modes are carried over");
    }

    @Test
    public void freecamIsJustTheDetachedCamera() {
        Freecam freecam = new Freecam();
        for (String name : List.of("Speed", "Sprint Multiplier", "Freeze Body", "Cancel On Damage", "Cancel Time (s)")) {
            assertNotNull(freecam.getSetting(name), name);
        }
        assertEquals(0.0, ((SliderSetting) freecam.getSetting("Cancel Time (s)")).getValue(), 1e-9,
                "the cancel time is off (0) by default");
        assertNull(freecam.getSetting("Navigation"));
        assertNull(freecam.getSetting("Camera Interaction"));
    }

    @Test
    public void noSmpModuleHasCombatMechanics() {
        // KillAura and MultiAura were removed: the SMP category carries no combat modules.
        for (String removed : List.of("KillAura", "MultiAura")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(
                    "interstellarTikiTiks.apexx.client.module.implementation.smp." + removed));
        }
    }
}
