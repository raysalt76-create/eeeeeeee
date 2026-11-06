package me.sootysplash.box;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.awt.Color;
import java.util.Objects;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config") && !FabricLoader.getInstance().isModLoaded("cloth_config")) {
            return parent -> null;
        }
        return ClothConfigScreenHelper::createScreen;
    }

    private static class ClothConfigScreenHelper {
        private static Screen createScreen(Screen parent) {
            Config config = Config.getInstance();
            ConfigBuilder title = ConfigBuilder.create().setParentScreen(parent).setTitle(Text.literal("Config"));
            Objects.requireNonNull(config);
            ConfigBuilder builder = title.setSavingRunnable(config::save);
            ConfigEntryBuilder cfgent = builder.entryBuilder();

            ConfigCategory behavior = builder.getOrCreateCategory(Text.literal("Behavior"));
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Enabled"), config.enabled)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Modify hitbox rendering?"))
                    .setSaveConsumer(newValue -> config.enabled = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Render Eye Height"), config.renderEyeHeight)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Render the red line at the entity's eye height"))
                    .setSaveConsumer(newValue -> config.renderEyeHeight = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Render Look Direction"), config.renderLookDir)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Render the blue line indicating the direction the entity is facing"))
                    .setSaveConsumer(newValue -> config.renderLookDir = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Target HitBox Color"), config.changeTargetColor)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Target hitbox color on targets?"))
                    .setSaveConsumer(newValue -> config.changeTargetColor = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("HitBox Hurt"), config.hitBoxHurt)
                    .setDefaultValue(false)
                    .setTooltip(Text.literal("Hitbox hurt color when hurt?"))
                    .setSaveConsumer(newValue -> config.hitBoxHurt = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Hide Stuck Arrows"), config.hideArrow)
                    .setDefaultValue(false)
                    .setTooltip(Text.literal("Removes bee stingers and arrows visually from other players"))
                    .setSaveConsumer(newValue -> config.hideArrow = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Hide Fireworks"), config.hideFireworks)
                    .setDefaultValue(false)
                    .setTooltip(Text.literal("Skips rendering hitboxes for fireworks"))
                    .setSaveConsumer(newValue -> config.hideFireworks = newValue)
                    .build());
            behavior.addEntry(cfgent.startBooleanToggle(Text.literal("Line Look Direction"), config.lineLookDir)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Instead of the new arrow, draw the entity's look direction as a line"))
                    .setSaveConsumer(newValue -> config.lineLookDir = newValue)
                    .build());

            ConfigCategory colors = builder.getOrCreateCategory(Text.literal("Colors"));
            colors.addEntry(cfgent.startAlphaColorField(Text.literal("Base Color"), config.hitBoxColor)
                    .setDefaultValue(Color.WHITE.getRGB())
                    .setTooltip(Text.literal("The base hitbox's color"))
                    .setSaveConsumer(newValue -> config.hitBoxColor = newValue)
                    .build());
            colors.addEntry(cfgent.startAlphaColorField(Text.literal("Eye Color"), config.eyeColor)
                    .setDefaultValue(Color.RED.getRGB())
                    .setTooltip(Text.literal("The hitbox eye height color"))
                    .setSaveConsumer(newValue -> config.eyeColor = newValue)
                    .build());
            colors.addEntry(cfgent.startAlphaColorField(Text.literal("Look Direction Color"), config.lookColor)
                    .setDefaultValue(Color.BLUE.getRGB())
                    .setTooltip(Text.literal("The hitbox's look direction color"))
                    .setSaveConsumer(newValue -> config.lookColor = newValue)
                    .build());
            colors.addEntry(cfgent.startAlphaColorField(Text.literal("Target Color"), config.targetBoxColor)
                    .setDefaultValue(Color.RED.getRGB())
                    .setTooltip(Text.literal("The hitbox color when the entity is targeted"))
                    .setSaveConsumer(newValue -> config.targetBoxColor = newValue)
                    .build());
            colors.addEntry(cfgent.startAlphaColorField(Text.literal("Hurt Color"), config.hurtBoxColor)
                    .setDefaultValue(Color.MAGENTA.getRGB())
                    .setTooltip(Text.literal("The hitbox color when the entity is on hurt tick"))
                    .setSaveConsumer(newValue -> config.hurtBoxColor = newValue)
                    .build());

            ConfigCategory linesWidths = builder.getOrCreateCategory(Text.literal("Line Width"));
            linesWidths.addEntry(cfgent.startFloatField(Text.literal("Line Width 1"), config.line1)
                    .setMin(0.0f)
                    .setMax(25.0f)
                    .setDefaultValue(2.5f)
                    .setTooltip(Text.literal("The width of the hitbox lines"))
                    .setSaveConsumer(newValue -> config.line1 = newValue)
                    .build());
            linesWidths.addEntry(cfgent.startDoubleField(Text.literal("Distance for width 2"), config.distFor2)
                    .setMin(0.0d)
                    .setMax(256.0d)
                    .setDefaultValue(32.0d)
                    .setTooltip(Text.literal("The distance for Line Width 2 to be used"))
                    .setSaveConsumer(newValue -> config.distFor2 = newValue)
                    .build());
            linesWidths.addEntry(cfgent.startFloatField(Text.literal("Line Width 2"), config.line2)
                    .setMin(0.0f)
                    .setMax(25.0f)
                    .setDefaultValue(2.5f)
                    .setTooltip(Text.literal("The width of the hitbox lines beyond the set distance"))
                    .setSaveConsumer(newValue -> config.line2 = newValue)
                    .build());

            ConfigCategory outline = builder.getOrCreateCategory(Text.literal("Outline"));
            outline.addEntry(cfgent.startBooleanToggle(Text.literal("Outline Enabled"), config.outlineEnabled)
                    .setDefaultValue(false)
                    .setTooltip(Text.literal("Enable hitbox outlines"))
                    .setSaveConsumer(newValue -> config.outlineEnabled = newValue)
                    .build());
            outline.addEntry(cfgent.startAlphaColorField(Text.literal("Outline Color"), config.outlineColor)
                    .setDefaultValue(Color.BLACK.getRGB())
                    .setTooltip(Text.literal("The hitbox's outline color"))
                    .setSaveConsumer(newValue -> config.outlineColor = newValue)
                    .build());
            outline.addEntry(cfgent.startFloatField(Text.literal("Outline Size Multiplier"), config.outlineMultiplier)
                    .setDefaultValue(2.0f)
                    .setMin(Math.nextUp(1.0f))
                    .setMax(10.0f)
                    .setTooltip(Text.literal("How much the hitbox's line width will be multiplied by for the outline"))
                    .setSaveConsumer(newValue -> config.outlineMultiplier = newValue)
                    .build());

            return builder.build();
        }
    }
}
