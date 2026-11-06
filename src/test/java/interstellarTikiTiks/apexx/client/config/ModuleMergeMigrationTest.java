package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Schema 3 -> 4: duplicate modules folded into one survivor keep their users' settings, state and binds. */
class ModuleMergeMigrationTest {

    private static JsonObject migrate(String modulesJson) {
        JsonObject root = JsonParser.parseString("{\"configSchema\":3,\"modules\":" + modulesJson + "}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertEquals(ConfigMigrations.CURRENT_SCHEMA, ConfigMigrations.schemaOf(root));
        return root.getAsJsonObject("modules");
    }

    private static JsonObject module(JsonObject modules, String name) {
        JsonObject module = modules.getAsJsonObject(name);
        assertNotNull(module, name + " block");
        return module;
    }

    private static JsonObject settings(JsonObject modules, String name) {
        return module(modules, name).getAsJsonObject("settings");
    }

    @Test
    void sprintResetBecomesSmartWTapSprintKey() {
        JsonObject modules = migrate("{\"SprintReset\":{\"enabled\":true,\"keyCode\":82,\"settings\":{\"Reset Delay (ms)\":20}},"
                + "\"SmartWTap\":{\"enabled\":false,\"keyCode\":-1,\"settings\":{\"Start Lag (ms)\":30,\"Reset Style\":\"Release\"}}}");
        assertFalse(modules.has("SprintReset"));
        JsonObject wtap = module(modules, "SmartWTap");
        assertTrue(wtap.get("enabled").getAsBoolean());
        assertEquals(82, wtap.get("keyCode").getAsInt(), "the bind moves to a survivor without one");
        assertEquals("Sprint Key", settings(modules, "SmartWTap").get("Reset Style").getAsString());
        assertEquals(20.0, settings(modules, "SmartWTap").get("Start Lag (ms)").getAsDouble(), 1e-9);
    }

    @Test
    void unusedSprintResetLeavesSmartWTapAlone() {
        JsonObject modules = migrate("{\"SprintReset\":{\"enabled\":false,\"settings\":{\"Reset Delay (ms)\":20}},"
                + "\"SmartWTap\":{\"enabled\":true,\"settings\":{\"Reset Style\":\"Mixed\"}}}");
        assertFalse(modules.has("SprintReset"));
        assertEquals("Mixed", settings(modules, "SmartWTap").get("Reset Style").getAsString());
        assertFalse(settings(modules, "SmartWTap").has("Start Lag (ms)"));
    }

    @Test
    void noHurtCamTurnsOnNoRenderHurtTiltOnly() {
        JsonObject modules = migrate("{\"No Hurt Cam\":{\"enabled\":true,\"settings\":{}}}");
        JsonObject noRender = settings(modules, "NoRender");
        assertTrue(module(modules, "NoRender").get("enabled").getAsBoolean());
        assertTrue(noRender.get("Hurt Tilt").getAsBoolean());
        assertFalse(noRender.get("Fire").getAsBoolean(), "a NoRender that was off must not start hiding fire");
    }

    @Test
    void noHurtCamKeepsAnEnabledNoRendersOverlays() {
        JsonObject modules = migrate("{\"No Hurt Cam\":{\"enabled\":true},"
                + "\"NoRender\":{\"enabled\":true,\"settings\":{\"Fire\":true,\"Hurt Tilt\":false}}}");
        assertTrue(settings(modules, "NoRender").get("Hurt Tilt").getAsBoolean());
        assertTrue(settings(modules, "NoRender").get("Fire").getAsBoolean());
    }

    @Test
    void swingSpeedBecomesViewModelSwingDuration() {
        JsonObject modules = migrate("{\"Swing Speed\":{\"enabled\":true,\"settings\":{\"Speed\":10}},"
                + "\"ViewModel\":{\"enabled\":false,\"settings\":{\"Scale\":1.0}}}");
        assertTrue(module(modules, "ViewModel").get("enabled").getAsBoolean());
        assertEquals(10.0, settings(modules, "ViewModel").get("Swing Duration (ticks)").getAsDouble(), 1e-9);
        assertEquals(1.0, settings(modules, "ViewModel").get("Scale").getAsDouble(), 1e-9);
    }

    @Test
    void disabledSwingSpeedDoesNotDriveAnEnabledViewModel() {
        JsonObject modules = migrate("{\"Swing Speed\":{\"enabled\":false,\"settings\":{\"Speed\":10}},"
                + "\"ViewModel\":{\"enabled\":true,\"settings\":{}}}");
        assertFalse(settings(modules, "ViewModel").has("Swing Duration (ticks)"));
    }

    @Test
    void bedBowAimFoldsIntoBowAimbotWithRenames() {
        JsonObject modules = migrate("{\"Bed Bow Aim\":{\"enabled\":true,\"keyCode\":66,\"settings\":{\"Max Distance\":60,"
                + "\"Aim Speed\":8,\"Humanization\":50,\"Line Of Sight\":true,\"Aim Point\":\"Head\",\"Tridents\":true}},"
                + "\"BowAimbot\":{\"enabled\":false,\"keyCode\":-1,\"settings\":{\"Range\":40,\"Aim At\":\"Auto\"}}}");
        JsonObject bow = settings(modules, "BowAimbot");
        assertTrue(module(modules, "BowAimbot").get("enabled").getAsBoolean());
        assertEquals(66, module(modules, "BowAimbot").get("keyCode").getAsInt());
        assertEquals(60.0, bow.get("Range").getAsDouble(), 1e-9, "the module the user ran wins");
        assertEquals(8.0, bow.get("Rotation Speed").getAsDouble(), 1e-9);
        assertEquals(0.5, bow.get("Aim Randomness").getAsDouble(), 1e-9);
        assertTrue(bow.get("Visible Only").getAsBoolean());
        assertEquals("Head", bow.get("Aim At").getAsString());
        assertTrue(bow.get("Tridents").getAsBoolean());
        assertFalse(modules.has("Bed Bow Aim"));
    }

    @Test
    void survivorInUseKeepsItsOwnValuesAndBind() {
        JsonObject modules = migrate("{\"Bed Bow Aim\":{\"enabled\":false,\"keyCode\":66,\"settings\":{\"Max Distance\":60,\"Prediction\":true}},"
                + "\"BowAimbot\":{\"enabled\":true,\"keyCode\":70,\"settings\":{\"Range\":40}}}");
        assertEquals(40.0, settings(modules, "BowAimbot").get("Range").getAsDouble(), 1e-9);
        assertTrue(settings(modules, "BowAimbot").get("Prediction").getAsBoolean(), "new settings still arrive");
        assertEquals(70, module(modules, "BowAimbot").get("keyCode").getAsInt());
    }

    @Test
    void bedClutchFoldsIntoClutchConvertingMsToTicks() {
        JsonObject modules = migrate("{\"Bed Clutch\":{\"enabled\":true,\"settings\":{\"Fall Distance\":5,\"Reach\":4.5,"
                + "\"Knockback Window (ms)\":1000,\"Re-Clutch Cooldown (ms)\":500,\"Aim Motion\":\"Wind\",\"Water Fallback\":true}},"
                + "\"Clutch\":{\"enabled\":false,\"settings\":{\"Min Fall Distance\":3}}}");
        JsonObject clutch = settings(modules, "Clutch");
        assertTrue(module(modules, "Clutch").get("enabled").getAsBoolean());
        assertEquals(5.0, clutch.get("Min Fall Distance").getAsDouble(), 1e-9);
        assertEquals(4.5, clutch.get("Max Reach").getAsDouble(), 1e-9);
        assertEquals(20.0, clutch.get("Knockback Window (ticks)").getAsDouble(), 1e-9);
        assertEquals(10.0, clutch.get("Re-Clutch Cooldown (ticks)").getAsDouble(), 1e-9);
        assertEquals("Wind", clutch.get("Rotation Mode").getAsString());
        // Schema 6 turned the water fallback into the Fall Method choice.
        assertFalse(clutch.has("Water Fallback"));
        assertEquals("Blocks + MLG", clutch.get("Fall Method").getAsString());
    }

    @Test
    void autoExtinguishTurnsOnClutchMlgFireTrigger() {
        JsonObject modules = migrate("{\"AutoExtinguish\":{\"enabled\":true,\"settings\":{\"Reaction Delay (ms)\":150,"
                + "\"Pickup Delay (ms)\":90,\"Cooldown (ms)\":700,\"Pick Up Water\":false,\"Silent Aim\":true}},"
                + "\"ClutchMLG\":{\"enabled\":true,\"settings\":{\"Trigger on Fire\":false,\"Auto Retract\":true}}}");
        // Schema 4 folds AutoExtinguish into ClutchMLG; schema 6 then folds ClutchMLG into Clutch as its MLG method.
        assertFalse(modules.has("ClutchMLG"));
        JsonObject mlg = settings(modules, "Clutch");
        assertTrue(module(modules, "Clutch").get("enabled").getAsBoolean());
        assertEquals("MLG", mlg.get("Fall Method").getAsString());
        assertTrue(mlg.get("Trigger on Fire").getAsBoolean());
        assertEquals(150.0, mlg.get("Fire Reaction Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(90.0, mlg.get("Fire Pickup Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(700.0, mlg.get("Fire Cooldown (ms)").getAsDouble(), 1e-9);
        assertTrue(mlg.get("Auto Retract").getAsBoolean(), "the enabled survivor's own value is kept");
        assertTrue(mlg.get("Fire Silent Aim").getAsBoolean());
    }

    @Test
    void autoAlienWcBecomesAutoActivation() {
        JsonObject modules = migrate("{\"AutoAlienWC\":{\"enabled\":true,\"settings\":{\"Run Cooldown (ms)\":900}},"
                + "\"AlienHighCharge\":{\"enabled\":false,\"keyCode\":71,\"settings\":{\"Aim Speed\":5}}}");
        JsonObject alien = settings(modules, "AlienHighCharge");
        assertTrue(module(modules, "AlienHighCharge").get("enabled").getAsBoolean());
        assertEquals("Auto", alien.get("Activation").getAsString());
        assertEquals(900.0, alien.get("Run Cooldown (ms)").getAsDouble(), 1e-9);
        assertEquals(71, module(modules, "AlienHighCharge").get("keyCode").getAsInt());
    }

    @Test
    void legacyAlienWcKeyStillReachesTheSurvivor() {
        JsonObject root = JsonParser.parseString("{\"modules\":{\"AlienWC\":{\"enabled\":false,\"settings\":{\"Aim Speed\":5}},"
                + "\"AutoAlienWC\":{\"enabled\":true,\"settings\":{}}}}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        JsonObject alien = root.getAsJsonObject("modules").getAsJsonObject("AlienHighCharge");
        assertTrue(alien.get("enabled").getAsBoolean());
        assertEquals("Auto", alien.getAsJsonObject("settings").get("Activation").getAsString());
        assertEquals(5.0, alien.getAsJsonObject("settings").get("Aim Speed").getAsDouble(), 1e-9);
    }

    @Test
    void hoverPotBecomesPotRefillHoverMode() {
        JsonObject modules = migrate("{\"HoverPot\":{\"enabled\":true,\"settings\":{\"Refill Slot 1\":true,\"Refill Slot 2\":false,"
                + "\"Refill Delay (ms)\":40,\"Close Delay (ms)\":100,\"Auto Close\":true,\"Auto Close Filled Slots\":5}},"
                + "\"Pot Refill\":{\"enabled\":false,\"settings\":{\"Refill Mode\":\"Continuous\",\"Refill Delay (ms)\":200}}}");
        assertFalse(modules.has("Pot Refill"), "schema 6 drops the space from the display name");
        JsonObject pot = settings(modules, "PotRefill");
        assertTrue(module(modules, "PotRefill").get("enabled").getAsBoolean());
        assertEquals("Hover", pot.get("Refill Mode").getAsString());
        assertTrue(pot.get("Hover Slot 1").getAsBoolean());
        assertFalse(pot.get("Hover Slot 2").getAsBoolean());
        assertEquals(40.0, pot.get("Hover Refill Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(200.0, pot.get("Refill Delay (ms)").getAsDouble(), 1e-9, "Pot Refill's own delay is not clobbered");
        assertTrue(pot.get("Hover Auto Close").getAsBoolean());
        assertEquals(5.0, pot.get("Auto Close Filled Slots").getAsDouble(), 1e-9);
        assertFalse(modules.has("HoverPot"));
    }

    @Test
    void hoverPotLeavesAnEnabledPotRefillsMode() {
        JsonObject modules = migrate("{\"HoverPot\":{\"enabled\":true,\"settings\":{}},"
                + "\"Pot Refill\":{\"enabled\":true,\"settings\":{\"Refill Mode\":\"One-Shot\"}}}");
        assertEquals("One-Shot", settings(modules, "PotRefill").get("Refill Mode").getAsString());
    }

    @Test
    void autoGHeadBecomesAutoEatGoldenHeads() {
        JsonObject modules = migrate("{\"AutoGHead\":{\"enabled\":true,\"keyCode\":72,\"settings\":{\"Health Threshold\":7,"
                + "\"Cooldown (ms)\":2000,\"Reaction Delay (ms)\":60,\"Swap Back (ms)\":90,\"Name Contains\":\"head\"}},"
                + "\"AutoEat\":{\"enabled\":false,\"settings\":{\"Health Threshold\":12,\"Reaction Delay (ms)\":100}}}");
        JsonObject eat = settings(modules, "AutoEat");
        assertTrue(module(modules, "AutoEat").get("enabled").getAsBoolean());
        assertEquals(72, module(modules, "AutoEat").get("keyCode").getAsInt());
        assertTrue(eat.get("Golden Heads").getAsBoolean());
        assertEquals(7.0, eat.get("Golden Head Health").getAsDouble(), 1e-9);
        assertEquals(2000.0, eat.get("Head Cooldown (ms)").getAsDouble(), 1e-9);
        assertEquals(60.0, eat.get("Head Reaction Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(90.0, eat.get("Head Swap Back Delay (ms)").getAsDouble(), 1e-9);
        assertEquals("head", eat.get("Name Contains").getAsString());
        assertEquals(12.0, eat.get("Health Threshold").getAsDouble(), 1e-9, "AutoEat's own threshold survives");
        assertEquals(100.0, eat.get("Reaction Delay (ms)").getAsDouble(), 1e-9);
    }

    @Test
    void hoverTotemBecomesAutoTotemHoverAssist() {
        JsonObject modules = migrate("{\"HoverTotem\":{\"enabled\":true,\"keyCode\":74,\"settings\":{\"Only If Empty Offhand\":false,"
                + "\"Auto Close Inventory\":true,\"Hover Delay (ms)\":60,\"Swap Delay (ms)\":140,\"Close Delay (ms)\":90}},"
                + "\"AutoTotem\":{\"enabled\":false,\"keyCode\":-1,\"settings\":{\"Close Delay (ms)\":150,\"Hover Assist\":false}}}");
        JsonObject totem = settings(modules, "AutoTotem");
        assertTrue(module(modules, "AutoTotem").get("enabled").getAsBoolean());
        assertEquals(74, module(modules, "AutoTotem").get("keyCode").getAsInt());
        assertTrue(totem.get("Hover Assist").getAsBoolean());
        assertFalse(totem.get("Hover Only If No Totem").getAsBoolean());
        assertTrue(totem.get("Hover Auto Close").getAsBoolean());
        assertEquals(60.0, totem.get("Hover Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(140.0, totem.get("Hover Swap Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(90.0, totem.get("Hover Close Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(150.0, totem.get("Close Delay (ms)").getAsDouble(), 1e-9, "AutoTotem's own close delay survives");
        assertFalse(modules.has("HoverTotem"));
    }

    @Test
    void chompzaStaysItsOwnModule() {
        JsonObject modules = migrate("{\"Chompza Insta-Catch\":{\"enabled\":false,\"keyCode\":73,\"settings\":{}},"
                + "\"Pearl Catch\":{\"enabled\":false,\"settings\":{}}}");
        assertTrue(modules.has("ChompzaInstaCatch"), "renamed in schema 6, never folded into PearlCatch");
        assertFalse(modules.has("Chompza Insta-Catch"));
        assertFalse(settings(modules, "PearlCatch").has("Throw Angle"));
    }

    @Test
    void currentSchemaConfigIsLeftAlone() {
        JsonObject root = JsonParser.parseString("{\"configSchema\":" + ConfigMigrations.CURRENT_SCHEMA
                + ",\"modules\":{\"HoverPot\":{\"enabled\":true}}}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertTrue(root.getAsJsonObject("modules").has("HoverPot"));
    }
}
