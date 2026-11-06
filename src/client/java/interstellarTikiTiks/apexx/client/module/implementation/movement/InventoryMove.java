package interstellarTikiTiks.apexx.client.module.implementation.movement;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.hooks.TimerSpeedProvider;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.PlayerInput;
import org.lwjgl.glfw.GLFW;

/**
 * InventoryMove (Blatant) - keeps you walking, jumping and sprinting while a screen is open.
 * Ported from LiquidBounce's InventoryMove.
 *
 * <p>Vanilla stops all movement input the moment a screen opens, and the server still sees you
 * clicking slots while moving. Nothing here is hidden; it is exactly that.</p>
 *
 * <p>The keys are read from the keyboard directly and fed in as the movement input record, so the
 * real key bindings are not touched and nothing sticks when the screen closes.</p>
 */
public final class InventoryMove extends Module implements MovementInputModifier, TimerSpeedProvider {

    private static final String NORMAL = "Normal";
    private static final String INVENTORY_ONLY = "Inventory Only";

    private static final String SPRINT_KEEP = "Keep";
    private static final String SPRINT_ON = "Force On";
    private static final String SPRINT_OFF = "Force Off";

    private final ModeSetting behavior = new ModeSetting("Behavior", NORMAL, INVENTORY_ONLY);
    private final BooleanSetting jump = new BooleanSetting("Jump", true)
            .setDescription("Lets the jump key work while a screen is open, so you can keep jumping while sorting items.");
    private final BooleanSetting sneak = new BooleanSetting("Sneak", false)
            .setDescription("Lets the sneak key work while a screen is open. Off by default because sneaking in a menu is rarely wanted.");
    private final ModeSetting sprint = new ModeSetting("Sprint", SPRINT_KEEP, SPRINT_ON, SPRINT_OFF);
    private final SliderSetting timer = new SliderSetting("Screen Timer", 1.0, 0.1, 2.0, 2)
            .setDescription("Game speed multiplier applied only while a screen is open. 1.0 is normal speed; anything else changes your movement rate and is easy for servers to notice.");

    public InventoryMove() {
        super("InventoryMove", "Keeps you walking, jumping and sprinting while an inventory or container screen is open (vanilla stops all movement the moment a screen opens). Movement keys are read straight from the keyboard, ignored while you type in a text field, and nothing sticks when the screen closes. Servers see you clicking slots while moving, which vanilla can't do.", Category.BLATANT);
        behavior.setDescription("Normal: works in any inventory or container screen you are not typing in. Inventory Only: works only in your own inventory (and the creative one), leaving chests, furnaces and other containers alone.");
        sprint.setDescription("Keep: follows your sprint key. Force On: always sprints while moving in a screen. Force Off: never sprints while a screen is open.");
        addSetting(behavior);
        addSetting(jump);
        addSetting(sneak);
        addSetting(sprint);
        addSetting(timer);
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        Screen screen = client.currentScreen;
        if (screen == null || !handles(screen)) return input;
        boolean forward = down(client, client.options.forwardKey);
        boolean back = down(client, client.options.backKey);
        boolean left = down(client, client.options.leftKey);
        boolean right = down(client, client.options.rightKey);
        boolean jumping = jump.isEnabled() && down(client, client.options.jumpKey);
        boolean sneaking = sneak.isEnabled() && down(client, client.options.sneakKey);
        boolean sprinting;
        if (sprint.is(SPRINT_ON)) sprinting = forward;
        else if (sprint.is(SPRINT_OFF)) sprinting = false;
        else sprinting = down(client, client.options.sprintKey);
        return new PlayerInput(forward, back, left, right, jumping, sneaking, sprinting);
    }

    @Override
    public float getTimerSpeed() {
        Screen screen = mc.currentScreen;
        if (screen == null || !handles(screen)) return 1.0f;
        return (float) timer.getValue();
    }

    /**
     * Whether the open screen is one to move in: an inventory or container screen, and never while a
     * text box (the creative search, an anvil name) has the keyboard. Menus, chat and the client's own
     * GUI are never moved in.
     */
    private boolean handles(Screen screen) {
        if (!(screen instanceof HandledScreen<?>)) return false;
        if (screen.getFocused() instanceof TextFieldWidget) return false;
        if (behavior.is(INVENTORY_ONLY)) {
            return screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen;
        }
        return true;
    }

    /** Hardware state of a binding's key: the binding itself is released by vanilla when a screen opens. */
    private static boolean down(MinecraftClient client, KeyBinding binding) {
        InputUtil.Key key = ((KeyBindingAccessor) binding).getBoundKey();
        if (key == null || key.getCode() < 0 || client.getWindow() == null) return false;
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(client.getWindow(), key.getCode());
    }
}
