package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.option.KeyBinding;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Single owner of "may a window click be sent on this client tick".
 *
 * <p>Every server-side inventory-click analysis of the silent-refill family keys off the same tell:
 * a window click arriving while the server still believes the player is moving or sprinting. The
 * reference implementation Grim registers as {@code MultiActionsC} ("Clicked in inventory while
 * moving", not experimental, so on by default) flags a {@code CLICK_WINDOW} when the end-of-tick
 * input packet reports {@code forward || backward || left || right || jump} or the player is
 * sprinting. Intave's {@code OnMoveCheck} and the delay analyzers watch the same window.
 *
 * <p>A vanilla client can never trip that: with a container open there is no movement input to
 * report. A silent swap breaks it, because it clicks while the player is mid-stride. Releasing keys
 * for the same tick is not enough either — the input the server has is the one from the last
 * end-of-tick input packet, and the sprint flag clears through a client command, so both have to
 * actually reach the server <em>before</em> the click does.
 *
 * <p>So a swap is issued like this:
 * <ol>
 *   <li>{@link #arm(Object)} at the start of a tick releases the movement and sprint keys and starts a
 *       quiet sequence.</li>
 *   <li>Each following tick re-asserts the release and counts a quiet tick only while the client
 *       reports no movement input and no sprinting, so a key pressed again by the player simply
 *       restarts the count.</li>
 *   <li>From {@link #REQUIRED_QUIET_TICKS} quiet ticks on, {@link #ready()} is true and the caller
 *       may send its click from the tick-tail event — after the zero-input packet for that tick is
 *       already queued.</li>
 *   <li>{@link #noteClick()} ends the sequence, and the keys are given back on the next tick, only
 *       for the keys the player is still physically holding.</li>
 * </ol>
 *
 * <p>It also enforces one window click per tick, so a retry can never share a tick with the click
 * it is retrying.
 */
public final class InventoryClickGate {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * Quiet ticks required before a click is allowed. Two ticks cover both server-side tells: the
     * end-tick input packet of the release tick, and the sprint command the client sends once its
     * own movement tick sees no forward input.
     */
    public static final int REQUIRED_QUIET_TICKS = 2;

    /** Keys the gate may take away for a quiet tick. */
    private static final int KEY_FORWARD = 0;
    private static final int KEY_BACK = 1;
    private static final int KEY_LEFT = 2;
    private static final int KEY_RIGHT = 3;
    private static final int KEY_JUMP = 4;
    private static final int KEY_SPRINT = 5;

    private static final Set<Object> OWNERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean armed;
    private static int quietTicks;
    /** Player age of the last quiet evaluation: arm() and tick() can both run in one tick, which counts once. */
    private static int evalAge = -1;
    private static int armedAge = -1;
    private static int lastSeenAge = -1;
    private static int clickedAge = -1;
    private static boolean holding;
    private static final boolean[] heldBefore = new boolean[6];

    private InventoryClickGate() {
    }

    // ------------------------------------------------------------------ caller API

    /**
     * Starts (or continues) a quiet sequence for {@code owner}: the movement and sprint keys are
     * released for this tick. Safe to call every tick while a swap is pending. Several modules can
     * hold the same sequence; it ends when the last one disarms or any click goes out.
     */
    public static void arm(Object owner) {
        if (mc == null || mc.options == null || owner == null) {
            return;
        }
        if (!armed) {
            armed = true;
            quietTicks = 0;
            evalAge = -1;
            armedAge = playerAge();
        }
        OWNERS.add(owner);
        releaseKeys();
        evaluateQuiet();
    }

    /**
     * True when a window click may be sent right now: the sequence has been quiet long enough and
     * nothing has clicked this tick yet.
     */
    public static boolean ready() {
        if (!armed || quietTicks < REQUIRED_QUIET_TICKS) {
            return false;
        }
        return !clickedThisTick();
    }

    /** True once a window click has gone out on this tick; one click per tick is the limit. */
    public static boolean clickedThisTick() {
        return clickedAge == playerAge();
    }

    /** Records that a window click just went out, ending the quiet sequence for every owner. */
    public static void noteClick() {
        clickedAge = playerAge();
        armed = false;
        quietTicks = 0;
        OWNERS.clear();
    }

    /**
     * Withdraws {@code owner} from the sequence (state reset, module disabled). The sequence is
     * abandoned, and the keys given back next tick, only once no other owner still waits on it.
     */
    public static void disarm(Object owner) {
        OWNERS.remove(owner);
        if (!OWNERS.isEmpty()) {
            return;
        }
        armed = false;
        quietTicks = 0;
        // The per-tick drive may never run again (module disabled), so hand the keys back now: only the
        // ones the player is still physically holding.
        restoreKeys();
    }

    /** True while the gate is holding the player's input down for a quiet tick. */
    public static boolean isHoldingInput() {
        return holding;
    }

    // ------------------------------------------------------------------ per-tick drive

    /**
     * Called once per client tick, at the head of the tick. Re-asserts the release while a sequence
     * is running and hands the keys back once one is finished.
     */
    public static void tick() {
        int age = playerAge();
        if (age == lastSeenAge) {
            return;
        }
        lastSeenAge = age;
        if (armed) {
            releaseKeys();
            evaluateQuiet();
            return;
        }
        restoreKeys();
    }

    /** True when the client itself reports no movement input and no sprinting. */
    public static boolean clientQuiet() {
        if (mc == null || mc.player == null || mc.options == null) {
            return false;
        }
        if (mc.player.isSprinting()) {
            return false;
        }
        return !anyMovementKeyDown();
    }

    private static void evaluateQuiet() {
        int age = playerAge();
        if (age == evalAge) {
            return;
        }
        evalAge = age;
        if (clientQuiet()) {
            quietTicks++;
        } else {
            quietTicks = 0;
        }
    }

    // ------------------------------------------------------------------ key handling

    private static void releaseKeys() {
        if (!holding) {
            holding = true;
            for (int index = 0; index < heldBefore.length; index++) {
                heldBefore[index] = key(index) != null && key(index).isPressed();
            }
        }
        setPressed(KEY_FORWARD, false);
        setPressed(KEY_BACK, false);
        setPressed(KEY_LEFT, false);
        setPressed(KEY_RIGHT, false);
        setPressed(KEY_JUMP, false);
        setPressed(KEY_SPRINT, false);
    }

    /** Gives back only the keys the gate took away and the player is still holding. */
    private static void restoreKeys() {
        if (!holding) {
            return;
        }
        holding = false;
        for (int index = 0; index < heldBefore.length; index++) {
            if (!heldBefore[index]) {
                continue;
            }
            KeyBinding binding = key(index);
            heldBefore[index] = false;
            if (binding != null && isPhysicallyDown(binding)) {
                binding.setPressed(true);
            }
        }
    }

    private static void setPressed(int index, boolean pressed) {
        KeyBinding binding = key(index);
        if (binding != null) {
            binding.setPressed(pressed);
        }
    }

    private static boolean anyMovementKeyDown() {
        for (int index = KEY_FORWARD; index <= KEY_JUMP; index++) {
            KeyBinding binding = key(index);
            if (binding != null && binding.isPressed()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPhysicallyDown(KeyBinding binding) {
        if (!(binding instanceof KeyBindingAccessor accessor) || accessor.getBoundKey() == null) {
            return false;
        }
        return mc.getWindow() != null
                && GLFW.glfwGetKey(mc.getWindow().getHandle(), accessor.getBoundKey().getCode()) == GLFW.GLFW_PRESS;
    }

    private static KeyBinding key(int index) {
        if (mc == null || mc.options == null) {
            return null;
        }
        return switch (index) {
            case KEY_FORWARD -> mc.options.forwardKey;
            case KEY_BACK -> mc.options.backKey;
            case KEY_LEFT -> mc.options.leftKey;
            case KEY_RIGHT -> mc.options.rightKey;
            case KEY_JUMP -> mc.options.jumpKey;
            case KEY_SPRINT -> mc.options.sprintKey;
            default -> null;
        };
    }

    private static int playerAge() {
        return mc == null || mc.player == null ? -1 : mc.player.age;
    }
}
