package interstellarTikiTiks.apexx.client.config;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GuiPrefsTest {

    @Test
    void appliesSavedOrderAndKeepsTheRestInPlace() {
        List<String> items = List.of("A", "B", "C", "D");
        List<String> out = GuiPrefs.applyOrder(items, Function.identity(), List.of("C", "Gone", "A"));
        assertEquals(List.of("C", "A", "B", "D"), out);
        assertSame(items, GuiPrefs.applyOrder(items, Function.identity(), List.of()));
    }

    @Test
    void movesToAnInsertionIndex() {
        List<String> names = List.of("A", "B", "C", "D");
        assertEquals(List.of("B", "C", "A", "D"), GuiPrefs.move(names, "A", 3));
        assertEquals(List.of("D", "A", "B", "C"), GuiPrefs.move(names, "D", 0));
        assertEquals(List.of("A", "B", "D", "C"), GuiPrefs.move(names, "C", 4));
        assertEquals(names, GuiPrefs.move(names, "B", 1));
        assertEquals(names, GuiPrefs.move(names, "B", 2));
        assertEquals(names, GuiPrefs.move(names, "Missing", 0));
    }

    @Test
    void mergeKeepsNamesThatWereNotVisible() {
        assertEquals(List.of("C", "A", "B", "Unloaded"),
                GuiPrefs.mergeVisible(List.of("A", "Unloaded", "B"), List.of("C", "A", "B")));
    }

    @Test
    void foldersPinsAndViewRoundTrip() {
        AtomicInteger changes = new AtomicInteger();
        GuiPrefs prefs = new GuiPrefs(changes::incrementAndGet);
        String id = prefs.createCategory("  My   PvP  ");
        assertNotNull(id);
        assertEquals("My PvP", prefs.customCategory(id).name());
        assertTrue(prefs.toggleMember(id, "AutoMace"));
        assertTrue(prefs.toggleMember(id, "TriggerBot"));
        assertFalse(prefs.toggleMember(id, "TriggerBot"));
        assertTrue(prefs.togglePin("AutoMace", "Cooldown"));
        prefs.setOrder("COMBAT", List.of("B", "A"));
        prefs.rememberView("MODULES", "folder:" + id, 120f);
        int before = changes.get();
        assertTrue(before >= 5);

        GuiPrefs copy = new GuiPrefs(null);
        copy.applyJson(prefs.toJson());
        assertEquals(List.of("AutoMace"), copy.customCategory(id).modules());
        assertTrue(copy.isPinned("AutoMace", "Cooldown"));
        assertEquals(List.of("B", "A"), copy.order("COMBAT"));
        assertEquals("folder:" + id, copy.lastCategory());
        assertEquals(120f, copy.lastScroll(), 1e-6);
        assertEquals("MODULES", copy.lastView());
        // rememberView is carried by the next save, not a change notification of its own.
        prefs.rememberView("CONFIGS", "COMBAT", 0f);
        assertEquals(before, changes.get());
    }

    @Test
    void renameKeepsOldNameWhenBlankAndDeleteRemoves() {
        GuiPrefs prefs = new GuiPrefs(null);
        String id = prefs.createCategory("Main");
        assertTrue(prefs.renameCategory(id, "   "));
        assertEquals("Main", prefs.customCategory(id).name());
        assertTrue(prefs.renameCategory(id, "Crystal stuff that has a really long name"));
        assertEquals(GuiPrefs.MAX_NAME, prefs.customCategory(id).name().length());
        prefs.deleteCategory(id);
        assertNull(prefs.customCategory(id));
        assertTrue(prefs.customCategories().isEmpty());
    }

    @Test
    void limitsAreEnforced() {
        GuiPrefs prefs = new GuiPrefs(null);
        for (int i = 0; i < GuiPrefs.MAX_CUSTOM_CATEGORIES; i++) assertNotNull(prefs.createCategory(null));
        assertFalse(prefs.canCreateCategory());
        assertNull(prefs.createCategory("one too many"));
        for (int i = 0; i < GuiPrefs.MAX_PINS; i++) assertTrue(prefs.togglePin("M", "S" + i));
        assertFalse(prefs.togglePin("M", "extra"));
        assertFalse(prefs.isPinned("M", "extra"));
    }

    @Test
    void malformedJsonIsIgnored() {
        GuiPrefs prefs = new GuiPrefs(null);
        prefs.applyJson(com.google.gson.JsonParser.parseString(
                "{\"order\":[1,2],\"folders\":{\"x\":1},\"pins\":[{\"m\":5},\"x\"],\"scroll\":\"NaN\"}"));
        assertTrue(prefs.customCategories().isEmpty());
        assertTrue(prefs.pins().isEmpty());
        assertEquals(0f, prefs.lastScroll());
    }

    @Test
    void booleanDefaultCoversItsOwnKey() {
        BooleanSetting setting = new BooleanSetting("Thing", false).setBindable(true);
        setting.captureDefault();
        assertFalse(setting.isModified());
        setting.setKeyCode(70);
        assertTrue(setting.isModified());
        setting.setKeyHoldMode(true);
        setting.setEnabled(true);
        setting.resetToDefault();
        assertFalse(setting.isEnabled());
        assertEquals(-1, setting.getKeyCode());
        assertFalse(setting.isKeyHoldMode());
        assertFalse(setting.isModified());
    }
}
