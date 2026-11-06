package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.mixin.MouseCallbackAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.util.Window;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeystrokesClickTest {
    private static final class Clicker extends Module implements ClickSimulating {
        Clicker() { super("Clicker", Category.COMBAT); }
    }

    private static final class Watcher extends Module {
        Watcher() { super("Watcher", Category.COMBAT); }
    }

    private static BooleanSetting clickSim(Module module) {
        return (BooleanSetting) module.getSettings().stream()
                .filter(setting -> setting.getName().equals("Click Sim")).findFirst().orElseThrow();
    }

    @Test
    void onlyTheRunningModulesOwnToggleEnablesCallbacksInEitherExecutionMode() {
        Clicker on = new Clicker();
        Clicker off = new Clicker();
        clickSim(on).setEnabled(true);
        try {
            for (String mode : new String[] { ExecutionMode.LEGIT, ExecutionMode.DIRECT }) {
                ExecutionMode.set(mode);
                assertFalse(ClickSimController.isEnabled(), mode + ": no module running, no callbacks");
                ModuleExecutionContext.run(on, () -> assertTrue(ClickSimController.isEnabled()));
                ModuleExecutionContext.run(off, () -> assertFalse(ClickSimController.isEnabled(),
                        mode + ": another module's toggle must not enable this one"));
                assertFalse(ClickSimController.isEnabledFor(new Object()));
            }
        } finally {
            ExecutionMode.set(ExecutionMode.LEGIT);
        }
    }

    @Test
    void clickSimIsPerModuleOffByDefaultAndOnlyOnClickingModules() {
        Clicker module = new Clicker();
        assertTrue(new Watcher().getSettings().stream().noneMatch(s -> s.getName().equals("Click Sim")));
        BooleanSetting option = clickSim(module);
        assertFalse(module.isClickSimEnabled());
        option.setEnabled(true);
        assertTrue(module.isClickSimEnabled());
        assertTrue(ClickSimController.isEnabledFor(module));
        option.resetToDefault();
        assertFalse(ClickSimController.isEnabledFor(module));
    }

    @Test
    void normalReleaseWithoutAGlfwPressDoesNotEmitAGlfwCallback() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        Mouse mouse = mock(Mouse.class, withSettings().extraInterfaces(MouseCallbackAccessor.class));
        Window window = mock(Window.class);
        when(window.getHandle()).thenReturn(123L);
        when(client.getWindow()).thenReturn(window);
        var mouseField = MinecraftClient.class.getDeclaredField("mouse");
        mouseField.setAccessible(true);
        mouseField.set(client, mouse);
        var release = SystemInputSimulator.class.getDeclaredMethod("dispatchRelease", int.class);
        release.setAccessible(true);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            release.invoke(null, -1024);
            release.invoke(null, -1025);
            verifyNoInteractions(mouse);
        }
    }

    @Test
    void leftAndRightFeedbackCallbacksRemainIsolatedFromPhysicalInputEvenOnFailure() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        Mouse mouse = mock(Mouse.class, withSettings().extraInterfaces(MouseCallbackAccessor.class));
        Window window = mock(Window.class);
        when(window.getHandle()).thenReturn(123L);
        when(client.getWindow()).thenReturn(window);
        var mouseField = MinecraftClient.class.getDeclaredField("mouse");
        mouseField.setAccessible(true);
        mouseField.set(client, mouse);
        var callback = (MouseCallbackAccessor) mouse;
        int left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        int right = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        InputTracker.setMouseButtonState(left, false);
        InputTracker.setMouseButtonState(right, true);
        long physicalOrder = InputTracker.getMousePressOrder(right);
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            doAnswer(invocation -> {
                assertTrue(InputTracker.isFeedbackCallback(), "Vanilla mouse handlers must recognize display-only events");
                MouseInput input = invocation.getArgument(1);
                int action = invocation.getArgument(2);
                InputTracker.setMouseButtonState(input.button(), action == GLFW.GLFW_PRESS);
                if (input.button() == right) throw new IllegalStateException("another mod rejects feedback");
                return null;
            }).when(callback).invokeOnMouseButton(eq(123L), any(MouseInput.class), anyInt());
            SystemInputSimulator.dispatchFeedbackMouse(left, GLFW.GLFW_PRESS);
            SystemInputSimulator.dispatchFeedbackMouse(left, GLFW.GLFW_RELEASE);
            assertDoesNotThrow(() -> SystemInputSimulator.dispatchFeedbackMouse(right, GLFW.GLFW_RELEASE));
            assertFalse(InputTracker.isFeedbackCallback());
            assertFalse(InputTracker.isMouseButtonPressed(left));
            assertTrue(InputTracker.isMouseButtonPressed(right));
            assertEquals(physicalOrder, InputTracker.getMousePressOrder(right));
            verify(callback, times(3)).invokeOnMouseButton(eq(123L), any(MouseInput.class), anyInt());
        } finally {
            InputTracker.setMouseButtonState(right, false);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void displayHoldCannotPreventAnActionableMouseButtonFromReleasing() throws Exception {
        MinecraftClient client = mock(MinecraftClient.class);
        Mouse mouse = mock(Mouse.class, withSettings().extraInterfaces(MouseCallbackAccessor.class));
        Window window = mock(Window.class);
        when(window.getHandle()).thenReturn(123L);
        when(client.getWindow()).thenReturn(window);
        var mouseField = MinecraftClient.class.getDeclaredField("mouse");
        mouseField.setAccessible(true);
        mouseField.set(client, mouse);
        var poolField = SystemInputSimulator.class.getDeclaredField("hotbarPulses");
        poolField.setAccessible(true);
        var pool = (java.util.Map<Integer, Object>) poolField.get(null);
        Class<?> pulseType = Class.forName(SystemInputSimulator.class.getName() + "$HotbarPulse");
        var constructor = pulseType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var release = SystemInputSimulator.class.getDeclaredMethod("dispatchRelease", int.class);
        release.setAccessible(true);
        int leftId = -1024;
        try (var minecraft = mockStatic(MinecraftClient.class)) {
            minecraft.when(MinecraftClient::getInstance).thenReturn(client);
            var callbackField = SystemInputSimulator.class.getDeclaredField("callbackPressedInputs");
            callbackField.setAccessible(true);
            ((java.util.Set<Integer>) callbackField.get(null)).add(leftId);
            pool.put(leftId, constructor.newInstance(new Object(), null, null, Long.MAX_VALUE, true, true, 0));
            release.invoke(null, leftId);
            verify((MouseCallbackAccessor) mouse).invokeOnMouseButton(eq(123L), any(MouseInput.class), eq(GLFW.GLFW_RELEASE));
        } finally {
            pool.remove(leftId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void displayHoldsAreVisibleToOverlaysButNotToVanillaTickOrModuleActionGates() throws Exception {
        var field = SystemInputSimulator.class.getDeclaredField("hotbarPulses");
        field.setAccessible(true);
        var pool = (java.util.Map<Integer, Object>) field.get(null);
        Class<?> pulseType = Class.forName(SystemInputSimulator.class.getName() + "$HotbarPulse");
        var constructor = pulseType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        int leftId = -1024;
        try {
            pool.put(leftId, constructor.newInstance(new Object(), null, null, Long.MAX_VALUE, true, true, 0));
            SystemInputSimulator.endClientTick();
            assertTrue(SystemInputSimulator.isMouseButtonSimulatedPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT));
            SystemInputSimulator.beginClientTick();
            assertFalse(SystemInputSimulator.isMouseButtonSimulatedPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                    "Display feedback must not repeat attacks or keep using an item during vanilla input handling");
            SystemInputSimulator.endClientTick();
            ModuleExecutionContext.run(new Object(), () ->
                    assertFalse(SystemInputSimulator.isMouseButtonSimulatedPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                            "Render-frame module gates must not mistake feedback for actionable held input"));
        } finally {
            SystemInputSimulator.endClientTick();
            pool.remove(leftId);
        }
    }

    @Test
    void nestedFeedbackScopesRecoverAfterAnException() {
        assertFalse(InputTracker.isFeedbackCallback());
        InputTracker.runFeedbackCallback(() -> {
            assertTrue(InputTracker.isFeedbackCallback());
            assertThrows(IllegalStateException.class, () -> InputTracker.runFeedbackCallback(() -> {
                throw new IllegalStateException("callback");
            }));
            assertTrue(InputTracker.isFeedbackCallback());
        });
        assertFalse(InputTracker.isFeedbackCallback());
    }
}
