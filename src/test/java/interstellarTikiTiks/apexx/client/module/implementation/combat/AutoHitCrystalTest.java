package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.SwapAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoHitCrystalTest {
    @Test
    void everyEarlierSettingIsStillThereAlongsideTheAuraSettings() {
        AutoHitCrystal module = new AutoHitCrystal();
        String[] names = {"Auto Place", "Require Sword Hit", "Hit Window (ms)", "Sword", "Axe", "Pickaxe", "Mace",
                "Totem", "Any Item", "Custom Items", "Headbob", "Hand", "Head Movement", "Height Variation",
                "Silent Aim", "Aim Speed", "Crystal Speed", "Pearl Follow", "Follow Delay (ms)", "Follow Distance",
                "Follow Confidence", "Switch Back", "Place Delay (ms)", "Break Delay (ms)", "Switch Back Delay (ms)",
                "Mode", "Target Mode", "Target Range", "Max Angle", "Min Efficiency (%)", "Max Self Damage",
                "Anti Suicide", "Predict Movement", "Optimization", "Rapid Min Efficiency (%)", "Activation Delay (ms)"};
        for (String name : names) assertNotNull(module.getSetting(name), name);
        // Manual is the default: saved configs keep the proven combo unless the user picks Auto.
        assertEquals("Manual", ((ModeSetting) module.getSetting("Mode")).getCurrentMode());
        assertEquals("None", ((ModeSetting) module.getSetting("Optimization")).getCurrentMode());
        assertTrue(module.getSetting("Optimization").isBlatant());
        assertTrue(option(module, "Anti Suicide").isEnabled());
        SliderSetting activation = (SliderSetting) module.getSetting("Activation Delay (ms)");
        assertEquals(0.0, activation.getMin());
        assertTrue(activation.getValue() > 0.0);
    }

    @Test
    void auraSettingsHideInManualMode() {
        AutoHitCrystal module = new AutoHitCrystal();
        assertFalse(module.getSetting("Target Mode").isVisible());
        ((ModeSetting) module.getSetting("Mode")).setCurrentMode("Auto");
        assertTrue(module.getSetting("Target Mode").isVisible());
        assertFalse(module.getSetting("Rapid Min Efficiency (%)").isVisible());
        ((ModeSetting) module.getSetting("Optimization")).setCurrentMode("Rapid Fire");
        assertTrue(module.getSetting("Rapid Min Efficiency (%)").isVisible());
        ((ModeSetting) module.getSetting("Mode")).setCurrentMode("Manual");
        assertFalse(module.getSetting("Target Mode").isVisible());
        assertFalse(module.getSetting("Activation Delay (ms)").isVisible());
        assertFalse(module.getSetting("Rapid Min Efficiency (%)").isVisible());
    }

    @Test
    void samePlanMeansSameKindBaseAndCrystal() {
        BlockPos a = new BlockPos(1, 2, 3);
        HitCrystalAura.Plan place = new HitCrystalAura.Plan(HitCrystalAura.Kind.PLACE, a, null, 8.0, 1.0, 0.6);
        HitCrystalAura.Plan samePlace = new HitCrystalAura.Plan(HitCrystalAura.Kind.PLACE, new BlockPos(1, 2, 3), null, 5.0, 2.0, 0.4);
        HitCrystalAura.Plan elsewhere = new HitCrystalAura.Plan(HitCrystalAura.Kind.PLACE, new BlockPos(1, 2, 4), null, 8.0, 1.0, 0.6);
        HitCrystalAura.Plan obsidian = new HitCrystalAura.Plan(HitCrystalAura.Kind.OBSIDIAN, a, null, 8.0, 1.0, 0.6);
        assertTrue(HitCrystalAura.sameAction(place, samePlace));
        assertFalse(HitCrystalAura.sameAction(place, elsewhere));
        assertFalse(HitCrystalAura.sameAction(place, obsidian));
        assertFalse(HitCrystalAura.sameAction(place, null));
        assertTrue(HitCrystalAura.sameAction(null, null));
    }

    @Test
    void efficiencyBoundFallsWithDistanceAndEndsAtTheBlastRadius() {
        assertEquals(1.0, HitCrystalAura.upperBound(0.0), 1.0e-9);
        assertTrue(HitCrystalAura.upperBound(2.0) > HitCrystalAura.upperBound(6.0));
        assertEquals(0.0, HitCrystalAura.upperBound(12.0));
        assertEquals(0.0, HitCrystalAura.upperBound(20.0));
    }

    @Test
    void autoPlaceAndSilentAimDefaultOffAndExposeAimControlsWhenEnabled() {
        AutoHitCrystal module = new AutoHitCrystal();
        ((ModeSetting) module.getSetting("Mode")).setCurrentMode("Manual");
        assertFalse(option(module, "Auto Place").isEnabled());
        assertFalse(option(module, "Silent Aim").isEnabled());
        option(module, "Headbob").setEnabled(false);
        assertFalse(module.getSetting("Aim Speed").isVisible());
        option(module, "Auto Place").setEnabled(true);
        assertTrue(module.getSetting("Aim Speed").isVisible());
        assertTrue(module.getSetting("Silent Aim").isVisible());
        assertTrue(module.getSetting("Hit Window (ms)").isVisible());
    }


    @Test
    void disconnectCancelsArmedAndActiveWorkAndCleanupIsIdempotent() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        try (var clients = mockStatic(MinecraftClient.class);
             var rotations = mockStatic(RotationManager.class);
             var sequences = mockStatic(SubTickSequencer.class)) {
            clients.when(MinecraftClient::getInstance).thenReturn(client);
            AutoHitCrystal module = new AutoHitCrystal();
            SwapAction swap = mock(SwapAction.class);
            set(module, "swap", swap);
            set(module, "autoPending", true);
            set(module, "automatic", true);
            set(module, "aiming", true);
            setPhase(module, "WAIT_OBSIDIAN");
            module.onTick();
            module.onTick();
            assertEquals("IDLE", get(module, "phase").toString());
            assertFalse((boolean) get(module, "autoPending"));
            assertFalse((boolean) get(module, "automatic"));
            assertEquals(Long.MIN_VALUE, get(module, "swordHitAt"));
            sequences.verify(() -> SubTickSequencer.cancelOwner(module), atLeastOnce());
            rotations.verify(() -> RotationManager.clearTarget(module));
            verify(swap, never()).act(anyInt(), any());
        }
    }

    @Test
    void disablingWhileOnlyArmedConsumesTheTriggerWithoutAWorldTick() throws Exception {
        try (var clients = mockStatic(MinecraftClient.class);
             var sequences = mockStatic(SubTickSequencer.class)) {
            clients.when(MinecraftClient::getInstance).thenReturn(mock(MinecraftClient.class));
            AutoHitCrystal module = new AutoHitCrystal();
            set(module, "autoPending", true);
            module.onDisable();
            module.onDisable();
            assertFalse((boolean) get(module, "autoPending"));
            assertFalse((boolean) get(module, "automatic"));
            assertEquals("IDLE", get(module, "phase").toString());
            sequences.verify(() -> SubTickSequencer.cancelOwner(module), times(2));
        }
    }

    private static BooleanSetting option(AutoHitCrystal module, String name) {
        return (BooleanSetting) module.getSetting(name);
    }

    private static Object get(AutoHitCrystal module, String name) throws Exception {
        Field field = AutoHitCrystal.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(module);
    }

    private static void set(AutoHitCrystal module, String name, Object value) throws Exception {
        Field field = AutoHitCrystal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(module, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setPhase(AutoHitCrystal module, String name) throws Exception {
        Field field = AutoHitCrystal.class.getDeclaredField("phase");
        field.setAccessible(true);
        field.set(module, Enum.valueOf((Class) field.getType(), name));
    }
}
