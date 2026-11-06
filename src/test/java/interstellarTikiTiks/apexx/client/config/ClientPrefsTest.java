package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientPrefsTest {

    @Test
    void normalizesServerAddresses() {
        assertEquals("play.foo.net", ClientPrefs.normalizeAddress(" Play.Foo.net:25565 "));
        assertEquals("play.foo.net", ClientPrefs.normalizeAddress("play.foo.net."));
        assertEquals("play.foo.net:25570", ClientPrefs.normalizeAddress("PLAY.foo.net:25570"));
        assertEquals("", ClientPrefs.normalizeAddress(null));
    }

    @Test
    void roundTripsThroughTheBlob() {
        ClientPrefs source = new ClientPrefs();
        JsonObject blob = JsonParser.parseString("{\"servers\":{\"Play.Foo.net:25565\":\"PvP\"},"
                + "\"onboardingSeen\":true,\"devlogSeen\":\"2026-10-01|Update\","
                + "\"hudLayouts\":{\"Main\":{\"FPS\":{\"x\":4,\"y\":8,\"enabled\":true,\"scale\":1.2}}}}").getAsJsonObject();
        source.applyFromBlob(blob);
        assertTrue(source.isOwned());
        assertEquals("PvP", source.serverConfigFor("play.foo.net"));
        assertTrue(source.isOnboardingSeen());
        assertEquals("2026-10-01|Update", source.getLastSeenDevlog());
        assertEquals(1, source.hudLayoutNames().size());

        ClientPrefs copy = new ClientPrefs();
        copy.applyFromBlob(source.toJson());
        assertEquals("PvP", copy.serverConfigFor("PLAY.FOO.NET"));
        assertEquals(1.2, copy.hudLayout("Main").getAsJsonObject("FPS").get("scale").getAsDouble(), 1e-9);
    }

    @Test
    void missingKeyKeepsLiveValuesButTakesOwnership() {
        ClientPrefs prefs = new ClientPrefs();
        assertFalse(prefs.isOwned());
        prefs.applyFromBlob(null);
        assertTrue(prefs.isOwned());
        assertFalse(prefs.isOnboardingSeen());
    }

    @Test
    void stripsPrefsFromForeignConfigs() {
        String json = "{\"modules\":{},\"clientPrefs\":{\"servers\":{\"a.net\":\"X\"}}}";
        String stripped = ClientPrefs.stripJson(json);
        assertFalse(stripped.contains("clientPrefs"));
        String untouched = "{\"modules\":{}}";
        assertSame(untouched, ClientPrefs.stripJson(untouched));
    }
}
