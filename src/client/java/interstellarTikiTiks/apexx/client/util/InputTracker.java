package interstellarTikiTiks.apexx.client.util;

import org.lwjgl.glfw.GLFW;
import java.util.BitSet;

public class InputTracker {
    private static final BitSet keys = new BitSet(GLFW.GLFW_KEY_LAST + 1);
    private static final BitSet mouseButtons = new BitSet(GLFW.GLFW_MOUSE_BUTTON_LAST + 1);
    private static final long[] keyPressOrder = new long[GLFW.GLFW_KEY_LAST + 1];
    private static final long[] mousePressOrder = new long[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
    private static long nextPressOrder;
    private static final ThreadLocal<Integer> syntheticDepth = ThreadLocal.withInitial(() -> 0);

    private static final ThreadLocal<Integer> feedbackDepth = ThreadLocal.withInitial(() -> 0);

    /** A callback describing an action already dispatched by vanilla must not execute it again. */
    public static boolean isFeedbackCallback() {
        return feedbackDepth.get() > 0;
    }

    public static void runFeedbackCallback(Runnable callback) {
        int depth = feedbackDepth.get();
        feedbackDepth.set(depth + 1);
        try {
            runSyntheticCallback(callback);
        } finally {
            if (depth == 0) feedbackDepth.remove();
            else feedbackDepth.set(depth);
        }
    }

    /** Callback routing must not turn a simulated input into a physical edge. */
    public static void runSyntheticCallback(Runnable callback) {
        int depth = syntheticDepth.get();
        syntheticDepth.set(depth + 1);
        try {
            callback.run();
        } finally {
            if (depth == 0) syntheticDepth.remove();
            else syntheticDepth.set(depth);
        }
    }

    public static synchronized long getKeyPressOrder(int key) {
        return key >= 0 && key < keyPressOrder.length ? keyPressOrder[key] : 0L;
    }

    public static synchronized long getMousePressOrder(int button) {
        return button >= 0 && button < mousePressOrder.length ? mousePressOrder[button] : 0L;
    }

    public static synchronized void setKeyState(int key, boolean pressed) {
        if (syntheticDepth.get() > 0) return;
        if (key >= 0 && key <= GLFW.GLFW_KEY_LAST) {
            if (pressed && !keys.get(key)) keyPressOrder[key] = ++nextPressOrder;
            keys.set(key, pressed);
        }
    }

    public static synchronized void setMouseButtonState(int button, boolean pressed) {
        if (syntheticDepth.get() > 0) return;
        if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            if (pressed && !mouseButtons.get(button)) mousePressOrder[button] = ++nextPressOrder;
            mouseButtons.set(button, pressed);
        }
    }

    public static synchronized boolean isKeyPressed(int key) {
        if (key >= 0 && key <= GLFW.GLFW_KEY_LAST) {
            return keys.get(key);
        }
        return false;
    }

    public static synchronized boolean isMouseButtonPressed(int button) {
        if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            return mouseButtons.get(button);
        }
        return false;
    }
}
