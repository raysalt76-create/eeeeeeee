package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;

class InputTrackerTest {
    @Test
    void physicalPressesRetainTheirOrderWithinOnePoll() {
        int first = GLFW.GLFW_KEY_A;
        int second = GLFW.GLFW_KEY_D;
        InputTracker.setKeyState(first, false);
        InputTracker.setKeyState(second, false);
        try {
            InputTracker.setKeyState(second, true);
            InputTracker.setKeyState(first, true);
            assertTrue(InputTracker.getKeyPressOrder(first) > InputTracker.getKeyPressOrder(second));
            long order = InputTracker.getKeyPressOrder(first);
            InputTracker.setKeyState(first, true);
            assertEquals(order, InputTracker.getKeyPressOrder(first), "A repeat is not a fresh edge");
            InputTracker.setKeyState(second, false);
            InputTracker.setKeyState(second, true);
            assertTrue(InputTracker.getKeyPressOrder(second) > order);
        } finally {
            InputTracker.setKeyState(first, false);
            InputTracker.setKeyState(second, false);
        }
    }

    @Test
    void nestedSyntheticCallbacksDoNotChangePhysicalStateOrOrder() {
        int key = GLFW.GLFW_KEY_W;
        int mouse = GLFW.GLFW_MOUSE_BUTTON_4;
        InputTracker.setKeyState(key, false);
        InputTracker.setMouseButtonState(mouse, false);
        long order = InputTracker.getKeyPressOrder(key);
        InputTracker.runSyntheticCallback(() -> {
            InputTracker.setKeyState(key, true);
            InputTracker.runSyntheticCallback(() -> InputTracker.setMouseButtonState(mouse, true));
        });
        assertFalse(InputTracker.isKeyPressed(key));
        assertFalse(InputTracker.isMouseButtonPressed(mouse));
        assertEquals(order, InputTracker.getKeyPressOrder(key));
    }

    @Test
    void syntheticScopeIsReleasedWhenCallbackThrows() {
        int key = GLFW.GLFW_KEY_S;
        InputTracker.setKeyState(key, false);
        assertThrows(IllegalStateException.class, () -> InputTracker.runSyntheticCallback(() -> {
            throw new IllegalStateException("callback");
        }));
        try {
            InputTracker.setKeyState(key, true);
            assertTrue(InputTracker.isKeyPressed(key));
        } finally {
            InputTracker.setKeyState(key, false);
        }
        assertEquals(0L, InputTracker.getKeyPressOrder(-1));
        assertEquals(0L, InputTracker.getMousePressOrder(100));
    }
}
