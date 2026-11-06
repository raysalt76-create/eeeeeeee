package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OldConfigSchemaMigrationTest {

    /** Shaped like a config saved by a client from before the 2026-10 settings rework (no schema stamp). */
    private static final String OLD_CONFIG = """
            {
              "theme": "Apexx",
              "modules": {
                "AutoMace": {"enabled": true, "settings": {"Lock-On Wait (ticks)": 2, "Use Timing Profile": true, "Shield Counter": "Stun Slam"}},
                "BowSpam": {"settings": {"Charge Ticks": 6, "Release Gap (ticks)": 2, "Press Gap (ticks)": 1}},
                "KnockbackDisplacement": {"settings": {"Cooldown (ticks)": 15}},
                "AutoElytra": {"settings": {"Extra Lead": 40}},
                "SpearAssist": {"settings": {"Through Walls": true, "Through Walls#key": 82, "Through Walls#hold": true}},
                "CartRefill": {"settings": {"Slot": 4, "Item": "TNT Minecart"}},
                "Some Removed Module": {"settings": {"Whatever": 1}}
              }
            }
            """;

    private static JsonObject settingsOf(JsonObject root, String module) {
        return root.getAsJsonObject("modules").getAsJsonObject(module).getAsJsonObject("settings");
    }

    @Test
    void oldConfigIsUpgradedAndStamped() {
        JsonObject root = JsonParser.parseString(OLD_CONFIG).getAsJsonObject();
        assertEquals(1, ConfigMigrations.schemaOf(root));

        ConfigMigrations.migrate(root);

        assertEquals(ConfigMigrations.CURRENT_SCHEMA, ConfigMigrations.schemaOf(root));
        JsonObject mace = settingsOf(root, "AutoMace");
        assertEquals(100.0, mace.get("Lock-On Wait (ms)").getAsDouble(), 1e-9, "ticks become ms");
        assertFalse(mace.has("Lock-On Wait (ticks)"));
        assertEquals("Stun Slam", mace.get("Shield Counter").getAsString(), "untouched settings survive");

        JsonObject bow = settingsOf(root, "BowSpam");
        assertEquals(300.0, bow.get("Charge Time (ms)").getAsDouble(), 1e-9);
        assertEquals(100.0, bow.get("Release Gap (ms)").getAsDouble(), 1e-9);
        assertTrue(bow.has("Press Gap (ticks)"), "settings with no successor are left for the loader to ignore");

        assertEquals(750.0, settingsOf(root, "KnockbackDisplacement").get("Cooldown (ms)").getAsDouble(), 1e-9);
        assertEquals(40.0, settingsOf(root, "AutoElytra").get("Extra Lead (ms)").getAsDouble(), 1e-9, "same unit, plain rename");

        JsonObject spear = settingsOf(root, "SpearAssist");
        assertTrue(spear.get("Wall Bypass").getAsBoolean());
        assertEquals(82, spear.get("Wall Bypass#key").getAsInt(), "a bindable's key follows the rename");
        assertTrue(spear.get("Wall Bypass#hold").getAsBoolean());
        assertFalse(spear.has("Through Walls#key"));

        JsonObject cart = settingsOf(root, "CartRefill");
        assertEquals("Slot 4", cart.get("Target Slot").getAsString());
        assertEquals("Auto", cart.get("Mode").getAsString(), "the old CartRefill always refilled by itself");
    }

    @Test
    void legacyClassicMenuMouseBindsAreReencoded() {
        // Old classic-menu encoding was button - 100 (mouse 4 = button 3 -> -97); readers decode -(button + 100).
        JsonObject root = JsonParser.parseString("{\"modules\":{"
                + "\"AutoMace\":{\"keyCode\":-97,\"keyCode2\":-104,\"settings\":{\"Silent Aim#key\":-96}},"
                + "\"ClickGUI\":{\"keyCode\":344}}}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        JsonObject mace = root.getAsJsonObject("modules").getAsJsonObject("AutoMace");
        assertEquals(-103, mace.get("keyCode").getAsInt(), "mouse button 3 in the current encoding");
        assertEquals(-104, mace.get("keyCode2").getAsInt(), "already current encoding is left alone");
        assertEquals(-104, mace.getAsJsonObject("settings").get("Silent Aim#key").getAsInt());
        assertEquals(344, root.getAsJsonObject("modules").getAsJsonObject("ClickGUI").get("keyCode").getAsInt());
    }

    @Test
    void currentSchemaIsNeverRewrittenTwice() {
        JsonObject root = JsonParser.parseString(
                "{\"configSchema\":2,\"modules\":{\"BowSpam\":{\"settings\":{\"Charge Ticks\":6}}}}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertTrue(settingsOf(root, "BowSpam").has("Charge Ticks"));
        assertFalse(settingsOf(root, "BowSpam").has("Charge Time (ms)"));
    }

    @Test
    void malformedConfigsNeverThrow() {
        for (String json : new String[] {"{}", "{\"modules\":5}", "{\"modules\":{\"AutoMace\":3}}",
                "{\"configSchema\":\"garbage\",\"modules\":{\"AutoMace\":{\"settings\":7}}}"}) {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            assertDoesNotThrow(() -> ConfigMigrations.migrate(root), json);
        }
        assertDoesNotThrow(() -> ConfigMigrations.migrate(null));
    }

    @Test
    void loaderClampsSkipsGarbageAndResetsVanishedModes() {
        ConfigManager manager = ConfigManager.getInstance();

        SliderSetting delay = new SliderSetting("Delay", 50, 0, 200, 0);
        delay.captureDefault();
        manager.loadSetting(JsonParser.parseString("{\"Delay\": 5000}").getAsJsonObject(), delay);
        assertEquals(200.0, delay.getValue(), 1e-9, "out-of-range values are clamped to the new bounds");
        manager.loadSetting(JsonParser.parseString("{\"Delay\": \"fast\"}").getAsJsonObject(), delay);
        assertEquals(200.0, delay.getValue(), 1e-9, "a non-number is skipped");

        ModeSetting mode = new ModeSetting("Mode", "Legit", "Strict");
        mode.captureDefault();
        manager.loadSetting(JsonParser.parseString("{\"Mode\": \"Strict\"}").getAsJsonObject(), mode);
        assertEquals("Strict", mode.getCurrentMode());
        manager.loadSetting(JsonParser.parseString("{\"Mode\": \"Blatant\"}").getAsJsonObject(), mode);
        assertEquals("Legit", mode.getCurrentMode(), "a removed choice falls back to the default");

        BooleanSetting flag = new BooleanSetting("Flag", true);
        flag.captureDefault();
        manager.loadSetting(JsonParser.parseString("{\"Other\": false}").getAsJsonObject(), flag);
        assertTrue(flag.isEnabled(), "a setting missing from an old config keeps its default");
    }
}
