package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFormatTest {
    @TempDir Path home;
    private String oldHome;

    @BeforeEach
    void setUp() {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldHome);
    }

    @Test
    void parsesABareConfigAndTheServiceWrapper() {
        assertNotNull(ConfigFormat.parse("{\"modules\":{}}"));
        ConfigFormat.Parsed wrapped = ConfigFormat.parse("{\"id\":\"x\",\"name\":\"Mace Duels\",\"data\":{\"modules\":{\"A\":{}}}}");
        assertNotNull(wrapped);
        assertEquals("Mace Duels", wrapped.name());
        assertTrue(wrapped.config().getAsJsonObject("modules").has("A"));
    }

    @Test
    void rejectsTextThatIsNotAConfig() {
        assertNull(ConfigFormat.parse(null));
        assertNull(ConfigFormat.parse("  "));
        assertNull(ConfigFormat.parse("hello"));
        assertNull(ConfigFormat.parse("[1,2,3]"));
        assertNull(ConfigFormat.parse("{\"settings\":{}}"));
    }

    @Test
    void aNewerSchemaIsStillAcceptedAndFlagged() {
        ConfigFormat.Parsed parsed = ConfigFormat.parse("{\"meta\":{\"schema\":99,\"edition\":\"free\"},\"modules\":{}}");
        assertNotNull(parsed);
        assertTrue(parsed.newerSchema());
        assertEquals("free", parsed.edition());
    }

    @Test
    void sharingRemovesPrivateKeysAndOptionallyUserContent() {
        JsonObject root = JsonParser.parseString("{\"modules\":{},\"macros\":[{\"n\":1}],\"scripts\":[{\"n\":2}],"
                + "\"configKeybinds\":[{\"configName\":\"a\",\"key1\":1}],\"webSyncCode\":\"X\",\"theme\":\"dark\"}").getAsJsonObject();
        JsonObject shared = ConfigFormat.forSharing(root, false);
        assertFalse(shared.has("macros"));
        assertFalse(shared.has("scripts"));
        assertFalse(shared.has("configKeybinds"));
        assertFalse(shared.has("webSyncCode"));
        assertEquals("dark", shared.get("theme").getAsString());
        assertTrue(ConfigFormat.forSharing(root, true).has("macros"), "own uploads keep the user's macros");
        assertTrue(root.has("macros"), "the source object is not modified");
    }

    @Test
    void stampWritesTheEnvelopeAndOnlyTimestampsWhenAsked() {
        JsonObject root = new JsonObject();
        root.add("modules", new JsonObject());
        ConfigFormat.stamp(root, "pvp", false);
        JsonObject meta = root.getAsJsonObject("meta");
        assertEquals("apexx-config", meta.get("format").getAsString());
        assertEquals(ConfigFormat.SCHEMA, meta.get("schema").getAsInt());
        assertEquals(ConfigFormat.EDITION, meta.get("edition").getAsString());
        assertFalse(meta.has("savedAt"));
        ConfigFormat.stamp(root, "pvp", true);
        assertTrue(root.getAsJsonObject("meta").has("savedAt"));
    }

    @Test
    void contentHashIgnoresMeta() {
        String a = "{\"modules\":{\"A\":{\"enabled\":true}},\"meta\":{\"savedAt\":\"1\"}}";
        String b = "{\"modules\":{\"A\":{\"enabled\":true}},\"meta\":{\"savedAt\":\"2\"}}";
        String c = "{\"modules\":{\"A\":{\"enabled\":false}},\"meta\":{\"savedAt\":\"1\"}}";
        assertEquals(ConfigLinks.contentHash(a), ConfigLinks.contentHash(b));
        assertNotEquals(ConfigLinks.contentHash(a), ConfigLinks.contentHash(c));
    }

    @Test
    void linksRoundTripRenameAndRemove() {
        assertNull(ConfigLinks.get("pvp"));
        ConfigLinks.put("pvp", new ConfigLinks.Link("cloud1", "PvP", 42));
        assertEquals("cloud1", ConfigLinks.get("PVP").cloudId());
        assertEquals("pvp", ConfigLinks.localFor("cloud1"));
        ConfigLinks.rename("pvp", "duel");
        assertNull(ConfigLinks.get("pvp"));
        assertEquals("duel", ConfigLinks.localFor("cloud1"));
        ConfigLinks.remove("duel");
        assertNull(ConfigLinks.localFor("cloud1"));
    }
}
