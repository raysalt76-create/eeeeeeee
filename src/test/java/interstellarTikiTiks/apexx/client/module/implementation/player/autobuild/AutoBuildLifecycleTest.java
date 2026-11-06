package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.AutoBuild;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import net.minecraft.client.MinecraftClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoBuildLifecycleTest {
    @Test
    void defaultsToPreviewWithVisibleAimAndOwnJitteredDelays() {
        AutoBuild module = new AutoBuild();
        assertFalse(module.isEnabled());
        for (String name : new String[]{"Auto Place", "Silent Aim", "Clear Obstructions", "Scaffold", "Tower Up"}) {
            BooleanSetting setting = (BooleanSetting) module.getSettings().stream()
                    .filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
            assertFalse(setting.isEnabled(), name);
        }
        for (String name : new String[]{"Place Delay (ms)", "Swap Delay (ms)", "Break Delay (ms)",
                "Swap Back Delay (ms)", "Tower Delay (ms)"}) {
            interstellarTikiTiks.apexx.client.module.SliderSetting delay =
                    (interstellarTikiTiks.apexx.client.module.SliderSetting) module.getSettings().stream()
                            .filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
            assertEquals("Delays", delay.getSection(), name);
            assertEquals(0.0, delay.getMin(), name + " must allow 0");
            assertTrue(delay.getValue() > 0.0, name + " must not default to 0");
            assertNotNull(delay.getJitter(), name + " needs a StDev companion");
            assertTrue(module.getSettings().contains(delay.getJitter()), name + " StDev must be registered");
        }
        assertTrue(module.getSettings().stream().noneMatch(s -> s.getName().contains("Timing Profile")));
    }

    @Test
    void cleanupWithoutAWorldIsImmediateAndIdempotent() throws Exception {
        MinecraftClient mc = mock(MinecraftClient.class);
        try (var clients = mockStatic(MinecraftClient.class);
             var slots = mockStatic(SlotSync.class);
             var rotations = mockStatic(RotationManager.class);
             var sequencer = mockStatic(SubTickSequencer.class)) {
            clients.when(MinecraftClient::getInstance).thenReturn(mc);
            AutoBuild module = new AutoBuild();
            set(module, "originalSlot", 1);
            set(module, "expectedSlot", 4);
            module.releaseRuntimeState();
            module.releaseRuntimeState();
            slots.verify(() -> SlotSync.restore(mc, 1), never());
            rotations.verify(() -> RotationManager.stop(module), times(2));
            sequencer.verify(() -> SubTickSequencer.cancelOwner(module), atLeast(2));
            Field original = AutoBuild.class.getDeclaredField("originalSlot");
            original.setAccessible(true);
            assertEquals(-1, original.getInt(module));
        }
    }

    private static void set(AutoBuild module, String name, Object value) throws Exception {
        Field f = AutoBuild.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(module, value);
    }
}
