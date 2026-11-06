package interstellarTikiTiks.apexx.client.module.implementation.studio.macro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class MacroTemplates {

    public record TemplateEntry(
            String id,
            String name,
            String category,
            String description,
            String triggerBadge,
            String flowSummary,
            Supplier<MacroDefinition> factory
    ) {}

    private static final List<TemplateEntry> TEMPLATES = new ArrayList<>();

    static {
        TEMPLATES.add(new TemplateEntry(
                "pearl_sword",
                "Pearl & Sword Catch",
                "Combat",
                "Throws an Ender Pearl and instantly switches to Netherite Sword to engage.",
                "Keybind (Press)",
                "Ender Pearl (Use) -> Netherite Sword (Attack)",
                MacroTemplates::createPearlAndSword
        ));

        TEMPLATES.add(new TemplateEntry(
                "shield_breaker",
                "Auto Shield Breaker",
                "Combat",
                "Automatically triggers on blocking enemies: axe hit to disable shield, followed by sword strike.",
                "Target Shield",
                "Netherite Axe (Attack) -> Netherite Sword (Attack)",
                MacroTemplates::createShieldBreaker
        ));

        TEMPLATES.add(new TemplateEntry(
                "anchor_glowstone",
                "Anchor & Glowstone Charge",
                "Anchor PvP",
                "When aiming at a Respawn Anchor: charges it with Glowstone and swaps to Totem of Undying.",
                "Look At Block",
                "Glowstone (Use) -> Totem (Use)",
                MacroTemplates::createAnchorGlowstone
        ));

        TEMPLATES.add(new TemplateEntry(
                "fast_gapple",
                "Quick Gapple Munch",
                "Survival",
                "Swaps to Enchanted Golden Apple and holds Use until consumed.",
                "Keybind (Hold)",
                "Enchanted Golden Apple (Use 1650ms)",
                MacroTemplates::createFastGapple
        ));

        TEMPLATES.add(new TemplateEntry(
                "cobweb_trap",
                "Cobweb Feet Trap",
                "PvP Utility",
                "Instantly selects Cobweb and places it at your target location to immobilize enemies.",
                "Keybind (Press)",
                "Cobweb (Use)",
                MacroTemplates::createCobwebTrap
        ));

        TEMPLATES.add(new TemplateEntry(
                "wind_charge_burst",
                "Wind Charge Leap",
                "Movement",
                "Quickly casts a Wind Charge at the ground for an instant tactical burst launch.",
                "Keybind (Press)",
                "Wind Charge (Use)",
                MacroTemplates::createWindChargeLeap
        ));

        TEMPLATES.add(new TemplateEntry(
                "crystal_pop",
                "Crystal Place & Pop",
                "Crystal PvP",
                "When looking at Obsidian: places an End Crystal and follows up with an attack to detonate.",
                "Look At Block",
                "End Crystal (Use) -> Netherite Sword (Attack)",
                MacroTemplates::createCrystalPop
        ));

        TEMPLATES.add(new TemplateEntry(
                "mace_slam",
                "Mace Slam Finisher",
                "Combat",
                "Swaps to Mace to land a high-damage smash attack, then immediately restores your sword.",
                "Keybind (Press)",
                "Mace (Attack) -> Netherite Sword (Attack)",
                MacroTemplates::createMaceSlam
        ));
    }

    private MacroTemplates() {}

    public static List<TemplateEntry> getTemplates() {
        return Collections.unmodifiableList(TEMPLATES);
    }

    public static MacroDefinition createPearlAndSword() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Pearl & Sword");
        macro.setTriggerMode(MacroDefinition.TriggerMode.KEYBIND);
        macro.setActivationMode(MacroDefinition.ActivationMode.PRESS);
        macro.setCooldownMs(200);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:ender_pearl");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(20);
        macro.getSteps().add(s1);

        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s2.setItemId("minecraft:netherite_sword");
        s2.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s2.setPreDelayMs(20);
        s2.setPostDelayMs(20);
        macro.getSteps().add(s2);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createShieldBreaker() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Shield Breaker");
        macro.setTriggerMode(MacroDefinition.TriggerMode.TARGET_SHIELD);
        macro.setShieldHoldMs(80);
        macro.setCooldownMs(350);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:netherite_axe");
        s1.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(20);
        macro.getSteps().add(s1);

        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s2.setItemId("minecraft:netherite_sword");
        s2.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s2.setPreDelayMs(30);
        s2.setPostDelayMs(20);
        macro.getSteps().add(s2);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createAnchorGlowstone() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Anchor & Glowstone");
        macro.setTriggerMode(MacroDefinition.TriggerMode.LOOK_AT_BLOCK);
        macro.setBlockFilterId("minecraft:respawn_anchor");
        macro.setCooldownMs(250);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:glowstone");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(25);
        macro.getSteps().add(s1);

        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s2.setItemId("minecraft:totem_of_undying");
        s2.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s2.setPreDelayMs(20);
        s2.setPostDelayMs(20);
        macro.getSteps().add(s2);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createFastGapple() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Fast Gapple");
        macro.setTriggerMode(MacroDefinition.TriggerMode.KEYBIND);
        macro.setActivationMode(MacroDefinition.ActivationMode.HOLD);
        macro.setCooldownMs(100);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:enchanted_golden_apple");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(1650);
        macro.getSteps().add(s1);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createCobwebTrap() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Cobweb Trap");
        macro.setTriggerMode(MacroDefinition.TriggerMode.KEYBIND);
        macro.setActivationMode(MacroDefinition.ActivationMode.PRESS);
        macro.setCooldownMs(150);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:cobweb");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(25);
        macro.getSteps().add(s1);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createWindChargeLeap() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Wind Charge Leap");
        macro.setTriggerMode(MacroDefinition.TriggerMode.KEYBIND);
        macro.setActivationMode(MacroDefinition.ActivationMode.PRESS);
        macro.setCooldownMs(200);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:wind_charge");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(25);
        macro.getSteps().add(s1);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createCrystalPop() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Crystal Pop");
        macro.setTriggerMode(MacroDefinition.TriggerMode.LOOK_AT_BLOCK);
        macro.setBlockFilterId("minecraft:obsidian");
        macro.setCooldownMs(200);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:end_crystal");
        s1.setActionMode(MacroDefinition.ActionMode.USE_ITEM);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(20);
        macro.getSteps().add(s1);

        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s2.setItemId("minecraft:netherite_sword");
        s2.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s2.setPreDelayMs(25);
        s2.setPostDelayMs(20);
        macro.getSteps().add(s2);

        macro.sanitize();
        return macro;
    }

    public static MacroDefinition createMaceSlam() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName("Mace Slam Finisher");
        macro.setTriggerMode(MacroDefinition.TriggerMode.KEYBIND);
        macro.setActivationMode(MacroDefinition.ActivationMode.PRESS);
        macro.setCooldownMs(300);
        macro.setRestoreOriginalSlot(true);
        macro.getSteps().clear();

        MacroDefinition.Step s1 = new MacroDefinition.Step();
        s1.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s1.setItemId("minecraft:mace");
        s1.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s1.setPreDelayMs(0);
        s1.setPostDelayMs(25);
        macro.getSteps().add(s1);

        MacroDefinition.Step s2 = new MacroDefinition.Step();
        s2.setTargetMode(MacroDefinition.TargetMode.ITEM);
        s2.setItemId("minecraft:netherite_sword");
        s2.setActionMode(MacroDefinition.ActionMode.ATTACK);
        s2.setPreDelayMs(30);
        s2.setPostDelayMs(20);
        macro.getSteps().add(s2);

        macro.sanitize();
        return macro;
    }
}
