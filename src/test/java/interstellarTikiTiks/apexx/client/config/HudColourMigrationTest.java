package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudColourMigrationTest {

    private static JsonObject hud(JsonObject root, String element) {
        return root.getAsJsonObject("hudElements").getAsJsonObject(element).getAsJsonObject("settings");
    }

    @Test
    void hudColourSwitchesBecomeColourModeAndOwnColours() {
        JsonObject root = JsonParser.parseString("""
                {"configSchema": 2, "hudElements": {
                  "HUD Style": {"settings": {"Follow GUI Theme": false, "Text Colour": -1}},
                  "CPS": {"settings": {"Theme Colours": false, "Left Color": -16087809}},
                  "Keystrokes": {"settings": {"Theme Colours": true, "Key On Color": -603290369}},
                  "Radar": {"settings": {"Scale": 1.0}}
                }}
                """).getAsJsonObject();

        ConfigMigrations.migrate(root);

        assertEquals(ConfigMigrations.CURRENT_SCHEMA, ConfigMigrations.schemaOf(root));
        JsonObject style = hud(root, "HUD Style");
        assertEquals("Custom", style.get("Colour Mode").getAsString());
        assertFalse(style.has("Follow GUI Theme"));
        assertEquals(-1, style.get("Text Colour").getAsInt(), "custom colours are kept");

        JsonObject cps = hud(root, "CPS");
        assertTrue(cps.get("Use Own Colours").getAsBoolean(), "own colours stay on for a widget that used them");
        assertFalse(cps.has("Theme Colours"));

        JsonObject keys = hud(root, "Keystrokes");
        assertFalse(keys.get("Use Own Colours").getAsBoolean(), "default colours + theme on: follows the HUD");

        assertFalse(hud(root, "Radar").has("Use Own Colours"), "untouched widgets keep the default");
    }

    @Test
    void customisedColourWithThemeSwitchOnStillKeepsOwnColours() {
        // The old Keystrokes turned its theme switch off on load whenever a colour was customised.
        JsonObject root = JsonParser.parseString("""
                {"configSchema": 2, "hudElements": {
                  "Keystrokes": {"settings": {"Theme Colours": true, "Key On Color": -65536}},
                  "HUD Style": {"settings": {"Follow GUI Theme": true}}
                }}
                """).getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertTrue(hud(root, "Keystrokes").get("Use Own Colours").getAsBoolean());
        assertEquals("Follow GUI Theme", hud(root, "HUD Style").get("Colour Mode").getAsString());
    }

    @Test
    void currentSchemaHudIsLeftAlone() {
        JsonObject root = JsonParser.parseString(
                "{\"configSchema\":3,\"hudElements\":{\"CPS\":{\"settings\":{\"Theme Colours\":false}}}}")
                .getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertTrue(hud(root, "CPS").has("Theme Colours"));
    }
}
