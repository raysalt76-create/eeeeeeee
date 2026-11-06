package interstellarTikiTiks.apexx.client.gui.kit;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.UiCodeEditor;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class UiCodeEditorTest {
    @Test
    void multilineReplacementCanBeUndoneAndRedone() {
        UiCodeEditor editor = new UiCodeEditor();
        editor.setText("module \"A\" {\n}\n");
        editor.keyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_SUPER);
        editor.insert("module \"B\" {\n  on tick { }\n}\n");
        assertEquals("module \"B\" {\n  on tick { }\n}\n", editor.text());
        editor.keyPressed(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER);
        assertEquals("module \"A\" {\n}\n", editor.text());
        editor.keyPressed(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
        assertEquals("module \"B\" {\n  on tick { }\n}\n", editor.text());
    }

    @Test
    void enterIndentsInsideABlockAndSaveWorksWithEitherPlatformModifier() {
        UiCodeEditor editor = new UiCodeEditor();
        int[] saves = {0};
        editor.onSave(() -> saves[0]++);
        editor.setText("on tick {");
        editor.keyPressed(GLFW.GLFW_KEY_END, 0);
        editor.keyPressed(GLFW.GLFW_KEY_ENTER, 0);
        editor.charTyped("use()");
        assertTrue(editor.text().contains("\n  use()"), editor.text());
        editor.keyPressed(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_CONTROL);
        editor.keyPressed(GLFW.GLFW_KEY_S, GLFW.GLFW_MOD_SUPER);
        assertEquals(2, saves[0]);
    }

    @Test
    void oversizedPasteLeavesTheSelectedSourceIntact() {
        UiCodeEditor editor = new UiCodeEditor();
        editor.setText("module \"Keep Me\" {}");
        String original = editor.text();
        int version = editor.version();
        editor.keyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL);
        editor.insert("x".repeat(60_001));
        assertEquals(original, editor.text());
        assertEquals(version, editor.version());
        editor.insert("\n".repeat(2001));
        assertEquals(original, editor.text());
    }

    @Test
    void selectingAnotherScriptClearsUndoHistory() {
        UiCodeEditor editor = new UiCodeEditor();
        editor.setText("first");
        editor.insert("edit");
        editor.setText("second");
        editor.keyPressed(GLFW.GLFW_KEY_Z, GLFW.GLFW_MOD_CONTROL);
        assertEquals("second", editor.text());
    }
}
