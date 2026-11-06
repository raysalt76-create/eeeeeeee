package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Schema 5 -> 6: the 2026-10-21 clean-up keeps users' values across the renames, merges and widget moves. */
class ConfigMigrationsV6Test {

    private static JsonObject migrate(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("configSchema", 5);
        ConfigMigrations.migrate(root);
        assertEquals(ConfigMigrations.CURRENT_SCHEMA, ConfigMigrations.schemaOf(root));
        return root;
    }

    private static JsonObject modules(JsonObject root) {
        return root.getAsJsonObject("modules");
    }

    private static JsonObject settings(JsonObject root, String module) {
        JsonObject block = modules(root).getAsJsonObject(module);
        assertNotNull(block, module + " block");
        return block.getAsJsonObject("settings");
    }

    @Test
    void displayNamesLoseTheirSpaces() {
        JsonObject root = migrate("{\"modules\":{\"Pearl Catch\":{\"enabled\":true,\"keyCode\":80,\"settings\":{\"Throw Gap (ms)\":12}},"
                + "\"SpearMaceDtap\":{\"enabled\":false,\"settings\":{}}}}");
        assertFalse(modules(root).has("Pearl Catch"));
        assertTrue(modules(root).getAsJsonObject("PearlCatch").get("enabled").getAsBoolean());
        assertEquals(80, modules(root).getAsJsonObject("PearlCatch").get("keyCode").getAsInt());
        assertEquals(12.0, settings(root, "PearlCatch").get("Throw Gap (ms)").getAsDouble(), 1e-9);
        assertTrue(modules(root).has("SpearMaceDoubleTap"));
    }

    @Test
    void swapBackNamesAndUnitsUnify() {
        JsonObject root = migrate("{\"modules\":{"
                + "\"AutoShieldBreak\":{\"settings\":{\"Return To Weapon\":true,\"Swap-Back Delay (ms)\":44,\"Swap-Back Delay (ms) StDev\":5}},"
                + "\"SwordHit\":{\"settings\":{\"Swap Back Ticks\":3,\"Swap Back Ticks StDev\":1}},"
                + "\"WebEscape\":{\"settings\":{\"Switch Back\":false,\"Pickup Delay\":120,\"Pickup Delay StDev\":10}},"
                + "\"NoBreakDelay\":{\"settings\":{\"Extra Delay (ms)\":40,\"Extra Delay (ms) StDev\":8}}}}");
        JsonObject shield = settings(root, "AutoShieldBreak");
        assertTrue(shield.get("Swap Back").getAsBoolean());
        assertEquals(44.0, shield.get("Swap Back Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(5.0, shield.get("Swap Back Delay StDev (ms)").getAsDouble(), 1e-9);
        assertFalse(shield.has("Swap-Back Delay (ms) StDev"));
        JsonObject sword = settings(root, "SwordHit");
        assertEquals(150.0, sword.get("Swap Back Delay (ms)").getAsDouble(), 1e-9, "ticks become ms");
        assertEquals(50.0, sword.get("Swap Back Delay StDev (ms)").getAsDouble(), 1e-9);
        JsonObject web = settings(root, "WebEscape");
        assertFalse(web.get("Swap Back").getAsBoolean());
        assertEquals(120.0, web.get("Pickup Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(10.0, web.get("Pickup Delay StDev (ms)").getAsDouble(), 1e-9);
        JsonObject nbd = settings(root, "NoBreakDelay");
        assertEquals(8.0, nbd.get("Extra Delay StDev (ms)").getAsDouble(), 1e-9, "generic StDev relabel");
        assertFalse(nbd.has("Extra Delay (ms) StDev"));
    }

    @Test
    void humanizationKeystrokesBecomesPerModuleClickSim() {
        JsonObject root = migrate("{\"modules\":{\"Humanization\":{\"enabled\":true,\"settings\":{\"Keystrokes\":true}},"
                + "\"TriggerBot\":{\"enabled\":true,\"settings\":{}},\"AutoMace\":{\"settings\":{\"Click Sim\":false}}}}");
        assertFalse(modules(root).has("Humanization"));
        assertTrue(settings(root, "TriggerBot").get("Click Sim").getAsBoolean());
        assertFalse(settings(root, "AutoMace").get("Click Sim").getAsBoolean(), "an explicit per-module value wins");
    }

    @Test
    void autoMaceSpearReachMovesToItsModule() {
        JsonObject root = migrate("{\"modules\":{\"AutoMace\":{\"settings\":{\"AutoSpear Reach\":true,"
                + "\"Spear Swap Reaction (ms)\":95,\"Spear Swap Reaction (ms) StDev\":20}}}}");
        JsonObject mace = settings(root, "AutoMace");
        assertFalse(mace.has("AutoSpear Reach"));
        assertFalse(mace.has("Spear Swap Reaction (ms)"));
        assertTrue(modules(root).getAsJsonObject("AutoSpearReach").get("enabled").getAsBoolean());
        assertEquals(95.0, settings(root, "AutoSpearReach").get("Swap Reaction (ms)").getAsDouble(), 1e-9);
        assertEquals(20.0, settings(root, "AutoSpearReach").get("Swap Reaction StDev (ms)").getAsDouble(), 1e-9);
    }

    @Test
    void clickGuiTintBlurAndSpellingsCarryOver() {
        JsonObject root = migrate("{\"modules\":{\"ClickGUI\":{\"settings\":{\"Custom BG Color\":true,"
                + "\"Background Color\":" + 0xFF102030 + ",\"Background Opacity\":50,\"Blur BG\":true,\"Blur Strength\":7,"
                + "\"Glass Blur\":3,\"Text Colour\":123,\"Text Colour#rainbow\":true,\"Mist\":\"None\",\"Water Colour Mode\":\"My Colours\","
                + "\"Display Name\":\"x\",\"Best-Effort Log Blank\":true}},"
                + "\"SelfDestruct\":{\"settings\":{\"Ocean Safe\":true,\"Wipe Config\":false}}}}");
        JsonObject gui = settings(root, "ClickGUI");
        assertEquals((0x80 << 24) | 0x102030, gui.get("Panel Tint").getAsInt(), "colour at half the opacity");
        assertFalse(gui.has("Custom BG Color"));
        assertFalse(gui.has("Background Color"));
        assertTrue(gui.get("Backdrop Blur").getAsBoolean());
        assertEquals(7.0, gui.get("Backdrop Blur Strength").getAsDouble(), 1e-9);
        assertEquals(3.0, gui.get("Panel Blur").getAsDouble(), 1e-9);
        assertEquals(123, gui.get("Text Color").getAsInt());
        assertTrue(gui.get("Text Color#rainbow").getAsBoolean());
        assertEquals("Off", gui.get("Mist").getAsString());
        assertEquals("My Colors", gui.get("Water Color Mode").getAsString());
        assertFalse(gui.has("Display Name"));
        assertFalse(gui.has("Best-Effort Log Blank"));
        JsonObject sd = settings(root, "SelfDestruct");
        assertFalse(sd.has("Ocean Safe"));
        assertFalse(sd.has("Wipe Config"));
    }

    @Test
    void untouchedPanelTintStaysWhenCustomBackgroundWasOff() {
        JsonObject root = migrate("{\"modules\":{\"ClickGUI\":{\"settings\":{\"Custom BG Color\":false,"
                + "\"Background Color\":" + 0xFF102030 + ",\"Panel Tint\":77}}}}");
        assertEquals(77, settings(root, "ClickGUI").get("Panel Tint").getAsInt());
    }

    @Test
    void chamsAndTwoDEspFoldIntoShaderEsp() {
        JsonObject root = migrate("{\"modules\":{"
                + "\"ShaderESP\":{\"enabled\":false,\"settings\":{\"ESP Style\":\"Shader + 2DFull\",\"Mobs\":true,\"Items\":true,\"Item Labels\":false}},"
                + "\"Chams\":{\"enabled\":true,\"keyCode\":67,\"settings\":{\"Players\":true,\"Mobs\":false,\"Color Mode\":\"Custom\","
                + "\"Occluded Color\":" + 0xFF00FF00 + ",\"Glowing\":true,\"Glow Size\":2}},"
                + "\"2D ESP\":{\"enabled\":false,\"settings\":{\"Style\":\"Corners\",\"Line Width\":2,\"Fill Opacity\":0.2,\"Players\":false}}}}");
        assertFalse(modules(root).has("Chams"));
        assertFalse(modules(root).has("2D ESP"));
        JsonObject block = modules(root).getAsJsonObject("ShaderESP");
        assertTrue(block.get("enabled").getAsBoolean(), "Chams was on");
        assertEquals(67, block.get("keyCode").getAsInt(), "Chams' bind moves to the survivor");
        JsonObject s = block.getAsJsonObject("settings");
        assertFalse(s.has("ESP Style"));
        assertTrue(s.get("Shader").getAsBoolean());
        assertEquals("Full", s.get("2D Boxes").getAsString());
        assertFalse(s.get("3D Boxes").getAsBoolean());
        assertTrue(s.get("Hostile").getAsBoolean(), "ShaderESP's own Mobs=true wins over Chams' false");
        assertTrue(s.get("Chams").getAsBoolean());
        assertTrue(s.get("Players").getAsBoolean());
        assertEquals("Custom", s.get("Mode").getAsString());
        assertEquals(0xFF00FF00, s.get("Custom Color").getAsInt());
        assertTrue(s.get("Chams Glowing").getAsBoolean());
        assertEquals(2.0, s.get("Chams Glow Size").getAsDouble(), 1e-9);
        assertEquals(2.0, s.get("2D Line Width").getAsDouble(), 1e-9, "a disabled module's looks still carry");
        assertTrue(s.get("2D Box Fill").getAsBoolean());
        assertFalse(s.has("Items"));
        assertFalse(modules(root).has("ItemESP"), "ShaderESP was off, so its item boxes don't turn ItemESP on");
    }

    @Test
    void mobEspAloneTurnsShaderEspIntoThreeDBoxes() {
        JsonObject root = migrate("{\"modules\":{\"MobESP\":{\"enabled\":true,\"settings\":{\"Style\":\"Outline\","
                + "\"Fill Opacity\":0.3,\"Hostile\":true,\"Passive\":false,\"Tracers\":true}}}}");
        JsonObject s = settings(root, "ShaderESP");
        assertTrue(modules(root).getAsJsonObject("ShaderESP").get("enabled").getAsBoolean());
        assertTrue(s.get("3D Boxes").getAsBoolean());
        assertFalse(s.get("Shader").getAsBoolean());
        assertEquals("Off", s.get("2D Boxes").getAsString());
        assertEquals(0.0, s.get("3D Fill Alpha").getAsDouble(), 1e-9);
        assertTrue(s.get("Tracers").getAsBoolean());
        assertFalse(s.get("Passive").getAsBoolean());
    }

    @Test
    void itemGlowFoldsIntoItemEsp() {
        JsonObject root = migrate("{\"modules\":{\"Item Glow\":{\"enabled\":true,\"settings\":{\"Filter\":\"All\",\"Color\":\"Per Category\","
                + "\"Netherite\":false,\"Range\":40,\"Netherite Color\":5}},"
                + "\"ItemESP\":{\"enabled\":false,\"settings\":{\"Color\":9,\"Range\":60}}}}");
        assertFalse(modules(root).has("Item Glow"));
        JsonObject s = settings(root, "ItemESP");
        assertTrue(modules(root).getAsJsonObject("ItemESP").get("enabled").getAsBoolean());
        assertEquals("All", s.get("Show").getAsString());
        assertEquals("Per Category", s.get("Color").getAsString());
        assertEquals(9, s.get("Custom Color").getAsInt(), "ItemESP's old single colour");
        assertEquals(60.0, s.get("Range").getAsDouble(), 1e-9, "ItemESP's own range wins");
        assertFalse(s.get("Netherite").getAsBoolean());
        assertEquals(5, s.get("Netherite Color").getAsInt());
        assertFalse(s.get("Box").getAsBoolean(), "a glow-only user keeps a glow-only look");
    }

    @Test
    void clutchMlgFoldsIntoClutch() {
        JsonObject root = migrate("{\"modules\":{\"Clutch\":{\"enabled\":true,\"settings\":{\"Water Fallback\":false,\"Pick Up Water\":true,"
                + "\"Water Pickup Delay (ms)\":80,\"Swap-Back Delay (ms)\":30}},"
                + "\"ClutchMLG\":{\"enabled\":true,\"settings\":{\"Rotation Mode\":\"Silent\",\"Min Fall Distance\":4,\"Switch Back\":true,"
                + "\"Silent Aim\":true,\"Swap Delay (ms)\":15,\"Use Timing Profile\":true,\"Cooldown (ms)\":900}}}}");
        assertFalse(modules(root).has("ClutchMLG"));
        JsonObject s = settings(root, "Clutch");
        assertEquals("Blocks + MLG", s.get("Fall Method").getAsString(), "both were on");
        assertTrue(s.get("Auto Retract").getAsBoolean());
        assertEquals(80.0, s.get("Retract Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(30.0, s.get("Swap Back Delay (ms)").getAsDouble(), 1e-9);
        assertTrue(s.get("Silent Rotations").getAsBoolean());
        assertEquals(4.0, s.get("MLG Min Fall Distance").getAsDouble(), 1e-9);
        assertTrue(s.get("Swap Back").getAsBoolean());
        assertTrue(s.get("Fire Silent Aim").getAsBoolean());
        assertEquals(15.0, s.get("MLG Swap Delay (ms)").getAsDouble(), 1e-9);
        assertEquals(900.0, s.get("MLG Cooldown (ms)").getAsDouble(), 1e-9);
        assertFalse(s.has("Use Timing Profile"));
        assertFalse(s.has("Water Fallback"));
    }

    @Test
    void trajectoryThemeBecomesSharedColor() {
        JsonObject root = migrate("{\"modules\":{\"Trajectory\":{\"settings\":{\"Theme\":\"Chroma\",\"Custom Primary\":1,\"Custom Secondary\":2}}}}");
        JsonObject s = settings(root, "Trajectory");
        assertEquals("Rainbow", s.get("Color").getAsString());
        assertEquals(1, s.get("Custom Color").getAsInt());
        assertEquals(2, s.get("Gradient End").getAsInt());
        assertFalse(s.has("Theme"));
    }

    @Test
    void hudWidgetsRenameSplitAndAbsorbTheirModules() {
        JsonObject root = migrate("{\"modules\":{\"TargetHud\":{\"enabled\":true},"
                + "\"ArrayList\":{\"enabled\":false,\"settings\":{\"Font Size\":14,\"Color Mode\":\"Custom\",\"Custom Color\":3,\"BG Color\":4,\"Sort\":\"Length\"}},"
                + "\"Notifications\":{\"settings\":{\"Corner Radius\":6,\"Duration (ms)\":2000}}},"
                + "\"hudElements\":{\"TargetHUD\":{\"x\":5,\"y\":6,\"enabled\":false,\"settings\":{}},"
                + "\"Stats\":{\"x\":0,\"y\":0,\"enabled\":true,\"settings\":{\"FPS\":true,\"Ping\":false,\"Server\":true}},"
                + "\"FPS\":{\"x\":1,\"y\":1,\"enabled\":false,\"settings\":{\"Font Size\":12}},"
                + "\"Server Info\":{\"x\":1,\"y\":1,\"enabled\":true,\"settings\":{\"Session Time\":true}},"
                + "\"Session Stats\":{\"x\":2,\"y\":2,\"enabled\":false,\"settings\":{}},"
                + "\"Keybinded Modules\":{\"x\":3,\"y\":3,\"enabled\":true,\"settings\":{}},"
                + "\"HUD Style\":{\"x\":0,\"y\":0,\"enabled\":true,\"settings\":{\"Override Accent\":true,\"Accent Override\":9,\"Glass Opacity\":1.5}}}}");
        JsonObject hud = root.getAsJsonObject("hudElements");
        assertFalse(hud.has("TargetHUD"));
        JsonObject target = hud.getAsJsonObject("Target HUD");
        assertEquals(5, target.get("x").getAsInt(), "position survives the rename");
        assertTrue(target.get("enabled").getAsBoolean(), "the module was the on/off switch");
        assertFalse(modules(root).has("TargetHud"));
        assertFalse(hud.has("Stats"));
        assertTrue(hud.getAsJsonObject("FPS").get("enabled").getAsBoolean());
        assertEquals(12.0, hud.getAsJsonObject("FPS").getAsJsonObject("settings").get("Text Size").getAsDouble(), 1e-9);
        assertFalse(hud.getAsJsonObject("Server Info").getAsJsonObject("settings").has("Session Time"));
        assertTrue(hud.getAsJsonObject("Session Stats").get("enabled").getAsBoolean());
        assertTrue(hud.has("Bound Modules"));
        JsonObject list = hud.getAsJsonObject("Array List");
        assertFalse(list.get("enabled").getAsBoolean());
        JsonObject ls = list.getAsJsonObject("settings");
        assertEquals(14.0, ls.get("Text Size").getAsDouble(), 1e-9);
        assertTrue(ls.get("Use Own Colours").getAsBoolean());
        assertEquals(3, ls.get("Accent Colour").getAsInt());
        assertEquals(4, ls.get("Background Colour").getAsInt());
        assertEquals("Length", ls.get("Sort").getAsString());
        assertFalse(modules(root).has("ArrayList"));
        JsonObject style = hud.getAsJsonObject("HUD Style").getAsJsonObject("settings");
        assertTrue(style.get("Custom Accent").getAsBoolean());
        assertEquals(9, style.get("Accent Color").getAsInt());
        assertEquals(150, style.get("Glass Opacity").getAsInt(), "multiplier becomes percent");
        assertFalse(settings(root, "Notifications").has("Corner Radius"));
        assertEquals(2000.0, settings(root, "Notifications").get("Duration (ms)").getAsDouble(), 1e-9);
    }

    @Test
    void guiPrefsFollowTheRenames() {
        JsonObject root = migrate("{\"modules\":{},\"clientPrefs\":{\"gui\":{"
                + "\"order\":{\"combat\":[\"Pearl Catch\",\"AutoMace\",\"Chams\",\"Humanization\"]},"
                + "\"folders\":[{\"id\":\"f\",\"name\":\"Pearls\",\"modules\":[\"Pearl Catch\",\"Pearl Disrupter\"]}],"
                + "\"pins\":[{\"m\":\"ClickGUI\",\"s\":\"Blur BG\"},{\"m\":\"Pot Refill\",\"s\":\"Refill Mode\"},{\"m\":\"TargetHud\",\"s\":\"x\"}]}}}");
        JsonObject gui = root.getAsJsonObject("clientPrefs").getAsJsonObject("gui");
        assertEquals("[\"PearlCatch\",\"AutoMace\",\"ShaderESP\"]", gui.getAsJsonObject("order").get("combat").toString());
        assertEquals("[\"PearlCatch\",\"PearlDisrupter\"]",
                gui.getAsJsonArray("folders").get(0).getAsJsonObject().get("modules").toString());
        assertEquals("[{\"m\":\"ClickGUI\",\"s\":\"Backdrop Blur\"},{\"m\":\"PotRefill\",\"s\":\"Refill Mode\"}]",
                gui.get("pins").toString());
    }

    @Test
    void currentSchemaIsLeftAlone() {
        JsonObject root = JsonParser.parseString("{\"configSchema\":" + ConfigMigrations.CURRENT_SCHEMA
                + ",\"modules\":{\"Pearl Catch\":{\"enabled\":true}}}").getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertTrue(root.getAsJsonObject("modules").has("Pearl Catch"));
    }
}
