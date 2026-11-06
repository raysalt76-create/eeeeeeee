package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

/** Something that can own keyboard focus: a text field or the code editor. */
public interface UiTextTarget {
    /** Characters typed (already filtered of control codes by the screen). */
    boolean charTyped(String chars);

    /** A key press with GLFW key code and modifier bits; return true when consumed. */
    boolean keyPressed(int key, int modifiers);

    /** Pointer dragged (framebuffer pixels) after a press this target started; return true when consumed. */
    default boolean mouseDragged(double mx, double my) { return false; }

    default void mouseReleased() { }

    default void onFocus() { }

    default void onBlur() { }
}
