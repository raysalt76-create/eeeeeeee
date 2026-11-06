package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.theme.ThemeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiConfigPersistenceTest {

    private ClickGUIModule clickGuiModule;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        clickGuiModule = new ClickGUIModule();
        configManager = ConfigManager.getInstance();
    }

    @Test
    void testThemeManagerModernThemeRegistration() {
        ThemeManager manager = ThemeManager.getInstance();
        for (String name : UiThemeMaterial.names()) {
            manager.setCurrentTheme(name);
            assertEquals(name, manager.getCurrentTheme().name, "ThemeManager should resolve modern theme " + name);
        }
    }

    @Test
    void testClickGUIModuleNormalization() {
        // Theme normalization
        assertEquals("Ruby", ClickGUIModule.normalizeThemeName("ruby"));
        assertEquals("Granite", ClickGUIModule.normalizeThemeName("black granite"));
        assertEquals("Granite", ClickGUIModule.normalizeThemeName("dark"));
        assertEquals("Purple", ClickGUIModule.normalizeThemeName("amethyst"));
        assertEquals("Apexx", ClickGUIModule.normalizeThemeName("non_existent_theme"));

    }

    @Test
    void testRootConfigApplication() {
        String json = """
            {
                "theme": "Ruby",
                "guiType": "Dropdown",
                "atmosphere": "Mist"
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        configManager.applyConfigFromJson(root);

        // Theme names are normalised to whatever the current theme catalogue calls them.
        String expected = ClickGUIModule.normalizeThemeName("Ruby");
        assertEquals(expected, ClickGUIModule.theme.getCurrentMode());
        assertEquals(expected, ThemeManager.getInstance().getCurrentTheme().name);
        assertTrue(clickGuiModule.getSettings().stream().noneMatch(setting -> "GUI Type".equals(setting.getName())));
        assertNotEquals("Off", ClickGUIModule.backgroundOverlay.getCurrentMode());
    }

    @Test
    void testRootConfigAliasesAndBooleans() {
        String json = """
            {
                "guiTheme": "Teal",
                "gui_type": "Islands",
                "mist": true
            }
            """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        configManager.applyConfigFromJson(root);

        assertEquals(ClickGUIModule.normalizeThemeName("Teal"), ClickGUIModule.theme.getCurrentMode());
        assertTrue(clickGuiModule.getSettings().stream().noneMatch(setting -> "GUI Type".equals(setting.getName())));
        assertEquals("On", ClickGUIModule.backgroundOverlay.getCurrentMode());

        // Mist as false -> None
        String jsonOff = """
            {
                "mist": false
            }
            """;
        configManager.applyConfigFromJson(JsonParser.parseString(jsonOff).getAsJsonObject());
        assertEquals("Off", ClickGUIModule.backgroundOverlay.getCurrentMode());
    }

    @Test
    void testModuleSettingsLoadAtmosphereAndAliases() {
        // Test ModeSetting Atmosphere with boolean "Mist": true
        JsonObject json1 = JsonParser.parseString("{\"Mist\": true}").getAsJsonObject();
        configManager.loadSetting(json1, ClickGUIModule.backgroundOverlay);
        assertEquals("On", ClickGUIModule.backgroundOverlay.getCurrentMode());

        // Test ModeSetting Atmosphere with boolean "Mist": false
        JsonObject json2 = JsonParser.parseString("{\"Mist\": false}").getAsJsonObject();
        configManager.loadSetting(json2, ClickGUIModule.backgroundOverlay);
        assertEquals("Off", ClickGUIModule.backgroundOverlay.getCurrentMode());

        // Test ModeSetting Atmosphere with string "Background Overlay": "Mist"
        JsonObject json3 = JsonParser.parseString("{\"Background Overlay\": \"Mist\"}").getAsJsonObject();
        configManager.loadSetting(json3, ClickGUIModule.backgroundOverlay);
        assertEquals("On", ClickGUIModule.backgroundOverlay.getCurrentMode());

        // Test ModeSetting Theme with string "theme": "catppuccin"
        JsonObject json4 = JsonParser.parseString("{\"theme\": \"catppuccin\"}").getAsJsonObject();
        configManager.loadSetting(json4, ClickGUIModule.theme);
        assertEquals("Catppuccin", ClickGUIModule.theme.getCurrentMode());

        // Test BooleanSetting Backdrop Blur (was Blur BG) with alias "blur"
        JsonObject json6 = JsonParser.parseString("{\"blur\": false}").getAsJsonObject();
        configManager.loadSetting(json6, ClickGUIModule.blurBackground);
        assertFalse(ClickGUIModule.blurBackground.isEnabled());

        JsonObject json7 = JsonParser.parseString("{\"blur_bg\": true}").getAsJsonObject();
        configManager.loadSetting(json7, ClickGUIModule.blurBackground);
        assertTrue(ClickGUIModule.blurBackground.isEnabled());
    }

}
