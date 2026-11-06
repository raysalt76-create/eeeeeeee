package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

/**
 * What a menu view needs from the screen hosting it: click regions, the theme palette, timing and
 * text focus. The click GUI implements it, and the offline preview reuses that implementation, so
 * views never touch Minecraft directly.
 */
public interface UiHost {
    /** Registers a click region (framebuffer pixels); later registrations win overlaps. */
    void hit(float x, float y, float w, float h, Runnable action);

    /** Registers a right-click region. */
    void hitSecondary(float x, float y, float w, float h, Runnable action);

    /** Clicks inside a scroll area must not reach controls that are scrolled out of view. */
    void beginHitClip(float x, float y, float w, float h);

    void endHitClip();

    boolean hovered(float mx, float my, float x, float y, float w, float h);

    /** Theme accent, 0xAARRGGBB. */
    int accent();

    int textColor();

    int mutedColor();

    int muted2Color();

    /** Titles and headings (the Custom Header Colour when set). */
    default int titleColor() {
        return textColor();
    }

    /** Hairline divider colour. */
    int lineColor();

    /** Fill of a raised card. */
    int cardFill();

    /** Neutral control fill with the given alpha (0-255). */
    int controlFill(int alpha);

    long now();

    float frameDelta();

    /**
     * Frame-rate independent ease of a per-key value toward {@code target} (same curve the click GUI uses for
     * its own toggles). Lets stateless kit controls animate. The default does not animate (returns the target);
     * the real host keeps the state and eases.
     */
    default float anim(String key, float target, float speed) {
        return target;
    }

    /** Pointer position at the most recent mouse-down, framebuffer pixels. */
    double pressX();

    double pressY();

    void focus(UiTextTarget target);

    UiTextTarget focused();

    /** Next key press or mouse button (as button - 100) is delivered to {@code onKey}; Escape delivers -1. */
    void captureKey(java.util.function.IntConsumer onKey);

    boolean capturingKey();

    String clipboard();

    void setClipboard(String text);

    /** Corner-radius multiplier from the menu's appearance settings (1 = default rounding). */
    default float cornerScale() {
        return 1f;
    }
}
