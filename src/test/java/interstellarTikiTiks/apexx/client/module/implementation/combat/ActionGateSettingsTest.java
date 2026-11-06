package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.implementation.Sprint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sprint-reset / sprint-hit family exposes the liquid and air gates as plain toggles that default
 * off, so existing configs keep their behaviour. Modules that already had a ground-only toggle keep it
 * as their air gate instead of growing a duplicate.
 */
public class ActionGateSettingsTest {

    @Test
    public void gatesExistAndDefaultOff() {
        Map<Module, List<String>> expected = new LinkedHashMap<>();
        expected.put(new AutoJumpReset(), List.of("Require Target", "Disable In Liquids"));
        expected.put(new SmartWTap(), List.of("Disable In Liquids", "Grounded Only"));
        expected.put(new HitSelect(), List.of("Disable In Liquids", "Disable In Air"));
        expected.put(new SwordHit(), List.of("Disable In Liquids", "Disable In Air"));
        expected.put(new ShiftTap(), List.of("Disable In Liquids", "Only On Ground"));
        expected.put(new Sprint(), List.of("Disable In Liquids"));
        expected.put(new Strafe(), List.of("Disable In Liquids", "Disable In Air"));
        expected.put(new AntiMiss(), List.of("Disable In Liquids", "Disable In Air"));

        for (Map.Entry<Module, List<String>> entry : expected.entrySet()) {
            Module module = entry.getKey();
            for (String name : entry.getValue()) {
                Setting setting = module.getSetting(name);
                assertNotNull(setting, module.getName() + " is missing \"" + name + "\"");
                assertTrue(setting instanceof BooleanSetting, module.getName() + " \"" + name + "\" must be a toggle");
                assertFalse(((BooleanSetting) setting).isEnabled(), module.getName() + " \"" + name + "\" must default off");
            }
        }
    }

    @Test
    public void existingGroundTogglesAreNotDuplicated() {
        for (Module module : List.of(new SmartWTap(), new ShiftTap(), new Sprint(), new AutoJumpReset())) {
            assertNull(module.getSetting("Disable In Air"), module.getName() + " keeps its existing air gate");
        }
    }
}
