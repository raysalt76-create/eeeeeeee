package interstellarTikiTiks.apexx.client.module.implementation.studio.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MacroDefinitionTest {

    @Test
    void testStepReorderingAndDuplication() {
        MacroDefinition macro = new MacroDefinition();
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setItemId("minecraft:ender_pearl");
        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setItemId("minecraft:netherite_sword");
        MacroDefinition.Step s3 = new MacroDefinition.Step();
        s3.setItemId("minecraft:golden_apple");

        macro.getSteps().add(s1);
        macro.getSteps().add(s2);
        macro.getSteps().add(s3);

        assertEquals(3, macro.getStepCount());
        assertEquals("minecraft:ender_pearl", macro.getStep(0).getItemId());
        assertEquals("minecraft:netherite_sword", macro.getStep(1).getItemId());

        // Move step 1 up
        assertTrue(macro.moveStepUp(1));
        assertEquals("minecraft:netherite_sword", macro.getStep(0).getItemId());
        assertEquals("minecraft:ender_pearl", macro.getStep(1).getItemId());

        // Move step 0 down
        assertTrue(macro.moveStepDown(0));
        assertEquals("minecraft:ender_pearl", macro.getStep(0).getItemId());
        assertEquals("minecraft:netherite_sword", macro.getStep(1).getItemId());

        // Duplicate step 1
        MacroDefinition.Step dup = macro.duplicateStep(1);
        assertNotNull(dup);
        assertEquals(4, macro.getStepCount());
        assertEquals("minecraft:netherite_sword", macro.getStep(2).getItemId());

        // Remove step
        assertTrue(macro.removeStep(2));
        assertEquals(3, macro.getStepCount());
    }

    @Test
    void testTemplates() {
        assertFalse(MacroTemplates.getTemplates().isEmpty());

        for (MacroTemplates.TemplateEntry entry : MacroTemplates.getTemplates()) {
            assertNotNull(entry.name());
            assertNotNull(entry.category());
            assertNotNull(entry.description());
            assertNotNull(entry.factory());

            MacroDefinition created = entry.factory().get();
            assertNotNull(created);
            assertFalse(created.getSteps().isEmpty());
            assertNotNull(created.getId());
        }
    }
}
