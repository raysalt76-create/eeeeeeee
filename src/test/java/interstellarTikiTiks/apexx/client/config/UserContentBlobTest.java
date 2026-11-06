package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserContentBlobTest {
    private static JsonObject base() {
        JsonObject root = new JsonObject();
        root.add("modules", new JsonObject());
        return root;
    }

    private static JsonArray macros(int n) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < n; i++) {
            JsonObject m = new JsonObject();
            m.addProperty("id", "id" + i);
            m.addProperty("name", "Macro " + i);
            a.add(m);
        }
        return a;
    }

    private static JsonArray scripts(int n, int sourceChars) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < n; i++) {
            JsonObject s = new JsonObject();
            s.addProperty("name", "s" + i);
            s.addProperty("enabled", true);
            s.addProperty("source", "x".repeat(sourceChars));
            a.add(s);
        }
        return a;
    }

    @Test
    void roundTripKeepsMacrosScriptsAndModules() {
        JsonObject root = base();
        UserContentBlob.attach(root, macros(2), scripts(1, 10), UserContentBlob.MAX_BLOB_BYTES);
        JsonObject back = JsonParser.parseString(root.toString()).getAsJsonObject();
        assertTrue(back.has("modules"));
        assertEquals(2, back.getAsJsonArray("macros").size());
        assertEquals("s0", back.getAsJsonArray("scripts").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(UserContentBlob.hasContent(back));
    }

    @Test
    void nullLeavesExistingKeysAlone() {
        JsonObject root = base();
        root.add("macros", macros(1));
        UserContentBlob.attach(root, null, null, UserContentBlob.MAX_BLOB_BYTES);
        assertEquals(1, root.getAsJsonArray("macros").size());
    }

    @Test
    void oversizeDropsScriptsBeforeMacros() {
        JsonObject root = base();
        UserContentBlob.attach(root, macros(3), scripts(4, 1000), 2000);
        assertFalse(root.has("scripts"));
        assertEquals(3, root.getAsJsonArray("macros").size());
        assertTrue(UserContentBlob.byteSize(root) <= 2000);
    }

    @Test
    void stillOversizeDropsMacrosToo() {
        JsonObject root = base();
        UserContentBlob.attach(root, macros(50), scripts(1, 10), 100);
        assertFalse(root.has("scripts"));
        assertFalse(root.has("macros"));
        assertTrue(root.has("modules"));
    }

    @Test
    void foreignMacrosAndScriptsAreStripped() {
        JsonObject root = base();
        root.add("macros", macros(1));
        root.add("scripts", scripts(1, 5));
        String stripped = UserContentBlob.stripJson(root.toString());
        JsonObject out = JsonParser.parseString(stripped).getAsJsonObject();
        assertFalse(out.has("macros"));
        assertFalse(out.has("scripts"));
        assertTrue(out.has("modules"));
        assertFalse(UserContentBlob.hasContent(stripped));
    }

    @Test
    void emptyArraysAreNotContent() {
        JsonObject root = base();
        root.add("macros", new JsonArray());
        root.add("scripts", new JsonArray());
        assertFalse(UserContentBlob.hasContent(root));
        assertFalse(UserContentBlob.hasContent("not json"));
    }
}
