package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Loaded-config tracking and the hidden working copy: named configs only change on Overwrite/Save. */
class WorkingCopyTest {
    private final ConfigManager manager = ConfigManager.getInstance();

    @AfterEach
    void clearLoaded() {
        String loaded = manager.getLoadedConfigName();
        if (loaded != null) manager.onConfigRenamed(loaded, null);
    }

    private static JsonObject blob() {
        JsonObject root = new JsonObject();
        JsonObject modules = new JsonObject();
        JsonObject sprint = new JsonObject();
        sprint.addProperty("enabled", true);
        sprint.addProperty("speed", 0.1f);
        modules.add("Sprint", sprint);
        root.add("modules", modules);
        root.addProperty("theme", "dark");
        return root;
    }

    @Test
    void comparableIgnoresAccountKeysAndNumberRepresentation() {
        JsonObject live = blob();
        live.add(UserContentBlob.MACROS, new JsonArray());
        live.add(ClientPrefs.KEY, new JsonObject());
        JsonObject marker = new JsonObject();
        marker.addProperty("from", "PvP");
        live.add(CloudConfigSync.WORKING_MARKER, marker);
        JsonObject parsed = JsonParser.parseString(blob().toString()).getAsJsonObject();

        JsonObject a = ConfigManager.comparable(live);
        JsonObject b = ConfigManager.comparable(parsed);
        assertEquals(a, b, "a live Float tree and the same blob parsed from text compare equal");
        assertFalse(a.has(UserContentBlob.MACROS));
        assertFalse(a.has(ClientPrefs.KEY));
        assertFalse(a.has(CloudConfigSync.WORKING_MARKER));
        assertNull(ConfigManager.comparable(null));
    }

    @Test
    void savedConfigIsCleanAndRestoredDirtyStateSurvives() {
        manager.markSavedAs("PvP", blob().toString());
        assertEquals("PvP", manager.getLoadedConfigName());
        assertFalse(manager.hasUnsavedChanges(), "right after a save nothing is unsaved");

        manager.restoreLoadedConfig("PvP", true);
        assertTrue(manager.hasUnsavedChanges(), "a working copy restored as dirty stays dirty until Overwrite");

        JsonObject marker = manager.workingMarker("cfg123");
        assertEquals("PvP", marker.get("from").getAsString());
        assertEquals("cfg123", marker.get("id").getAsString());
        assertTrue(marker.get("dirty").getAsBoolean());
    }

    @Test
    void renameAndDeleteFollowTheLoadedConfig() {
        manager.markSavedAs("Old", blob().toString());
        manager.onConfigRenamed("other", "x");
        assertEquals("Old", manager.getLoadedConfigName(), "renaming another config changes nothing");
        manager.onConfigRenamed("old", "New");
        assertEquals("New", manager.getLoadedConfigName());
        manager.onConfigRenamed("New", null);
        assertNull(manager.getLoadedConfigName());
        assertFalse(manager.hasUnsavedChanges());
        assertNull(manager.workingMarker("id"), "no marker without a loaded config");
    }

    @Test
    void theWorkingSlotIsNeverALoadedConfig() {
        manager.markSavedAs(CloudConfigs.WORKING_SLOT, blob().toString());
        assertNull(manager.getLoadedConfigName());
        manager.restoreLoadedConfig("  ", false);
        assertNull(manager.getLoadedConfigName());
    }

    @Test
    void workingMarkerIsAddedAndReadBack() {
        manager.restoreLoadedConfig("Mace", false);
        String marked = CloudConfigSync.withWorkingMarker(blob().toString());
        JsonObject root = JsonParser.parseString(marked).getAsJsonObject();
        JsonObject marker = CloudConfigSync.markerOf(root);
        assertNotNull(marker);
        assertEquals("Mace", marker.get("from").getAsString());
        assertTrue(root.has("modules"), "the config itself is untouched");
        assertNull(CloudConfigSync.markerOf(blob()));
    }

    @Test
    void switchingConfigsKeepsPrivateKeysFromTheWorkingCopy() {
        JsonObject named = blob();
        named.add(UserContentBlob.MACROS, new JsonArray()); // stale copy inside the named config
        JsonObject working = new JsonObject();
        JsonArray macros = new JsonArray();
        macros.add("m1");
        working.add(UserContentBlob.MACROS, macros);
        JsonObject prefs = new JsonObject();
        prefs.addProperty("tourDone", true);
        working.add(ClientPrefs.KEY, prefs);

        JsonObject merged = CloudConfigSync.mergePrivateKeys(named, working);
        assertEquals(macros, merged.get(UserContentBlob.MACROS));
        assertEquals(prefs, merged.get(ClientPrefs.KEY));
        assertEquals(named.get("modules"), merged.get("modules"));
        assertEquals(new JsonArray(), named.get(UserContentBlob.MACROS), "the input is not modified");
        assertEquals(named, CloudConfigSync.mergePrivateKeys(named, null));
    }
}
