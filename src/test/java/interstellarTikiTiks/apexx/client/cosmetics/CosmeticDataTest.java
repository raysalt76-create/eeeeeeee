package interstellarTikiTiks.apexx.client.cosmetics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.config.ConfigMigrations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Selection JSON validation (it also comes from other users), prefs round trip, and the Wings migration. */
class CosmeticDataTest {

    @Test
    void selectionRoundTripsAndClamps() {
        CosmeticSelection sel = CosmeticSelection.NONE
                .withCape(CapeDesign.PRESETS.get(4).design().withText("ab1"))
                .withHat(new CosmeticSelection.Hat("crown", 0x12F5C542))
                .withWings(new CosmeticSelection.Wings("dragon", "Ember", 0x123456, 9f, -1f, false));
        CosmeticSelection back = CosmeticSelection.fromJson(JsonParser.parseString(sel.toJson().toString()));
        assertEquals(sel, back);
        assertEquals(1.8f, back.wings().scale());
        assertEquals(0f, back.wings().tipTint());
        assertEquals(0xFFF5C542, back.hat().color());
        assertTrue(sel.toJson().toString().length() < CosmeticSelection.MAX_JSON_CHARS);
    }

    @Test
    void hostileSelectionsDegradeSafely() {
        assertEquals(CosmeticSelection.NONE, CosmeticSelection.fromJson(JsonParser.parseString("[1,2]")));
        CosmeticSelection s = CosmeticSelection.fromJson(JsonParser.parseString(
                "{\"hat\":{\"id\":\"sombrero\"},\"wings\":{\"t\":\"bat\"},\"cape\":{\"id\":\"../x\",\"g\":\"plaid\",\"b1\":\"red\",\"t\":\"<b>\"}}"));
        assertNull(s.hat());
        assertNull(s.wings());
        assertEquals(CapeDesign.CUSTOM, s.cape().preset());
        assertEquals(CapeDesign.Gradient.NONE, s.cape().gradient());
        assertEquals("B", s.cape().text());
        String huge = "{\"cape\":{\"img\":\"" + "A".repeat(5000) + "\"}}";
        assertEquals(CosmeticSelection.NONE, CosmeticSelection.fromJson(JsonParser.parseString(huge)));
    }

    @Test
    void prefsRoundTripWithoutFiringTheChangeHook() {
        int[] changes = {0};
        CosmeticPrefs prefs = new CosmeticPrefs(() -> changes[0]++);
        prefs.setSelection(CosmeticSelection.NONE.withHat(new CosmeticSelection.Hat("halo", CosmeticSelection.THEME_COLOR)));
        prefs.setCapeMode(CosmeticPrefs.CapeMode.STATIC);
        prefs.setShareMine(true);
        assertEquals(3, changes[0]);
        CosmeticPrefs other = new CosmeticPrefs(() -> fail("restore must not count as a change"));
        other.applyJson(prefs.toJson());
        assertEquals(prefs.selection(), other.selection());
        assertEquals(CosmeticPrefs.CapeMode.STATIC, other.capeMode());
        assertTrue(other.shareMine());
        assertFalse(new CosmeticPrefs(null).shareMine(), "sharing is off by default");
    }

    @Test
    void enabledWingsModuleBecomesTheCosmeticsSelection() {
        JsonObject root = JsonParser.parseString("{\"configSchema\":4,\"modules\":{\"Wings\":{\"enabled\":true,\"settings\":"
                + "{\"Wings\":\"Dragon\",\"Color\":\"Ember\",\"Wing Scale\":1.3,\"Tip Tint\":0.5,\"Wing Animation\":false,\"Halo (Nimbus)\":true}}}}")
                .getAsJsonObject();
        ConfigMigrations.migrate(root);
        assertFalse(root.getAsJsonObject("modules").has("Wings"));
        CosmeticPrefs prefs = new CosmeticPrefs(null);
        prefs.applyJson(root.getAsJsonObject("clientPrefs").get(CosmeticPrefs.KEY));
        CosmeticSelection.Wings w = prefs.selection().wings();
        assertEquals("dragon", w.type());
        assertEquals("Ember", w.colorMode());
        assertEquals(1.3f, w.scale(), 1e-6);
        assertFalse(w.animate());
        assertEquals("nimbus", prefs.selection().hat().id());
    }

    @Test
    void disabledWingsModuleOrExistingCosmeticsAreLeftAlone() {
        JsonObject off = JsonParser.parseString("{\"configSchema\":4,\"modules\":{\"Wings\":{\"enabled\":false}}}").getAsJsonObject();
        ConfigMigrations.migrate(off);
        assertFalse(off.has("clientPrefs"));
        assertFalse(off.getAsJsonObject("modules").has("Wings"));

        JsonObject has = JsonParser.parseString("{\"configSchema\":4,\"clientPrefs\":{\"cosmetics\":{\"share\":true}},"
                + "\"modules\":{\"Wings\":{\"enabled\":true}}}").getAsJsonObject();
        ConfigMigrations.migrate(has);
        assertEquals("{\"share\":true}", has.getAsJsonObject("clientPrefs").get("cosmetics").toString());
    }
}
