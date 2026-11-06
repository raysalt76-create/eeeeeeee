package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.PlayerInput;

/**
 * Hook for modules that change the player's movement input for exact ticks (W-tap, S-tap, sprint
 * reset, crit sprint release).
 *
 * <p>{@code ClientPlayerEntityMixin} calls every enabled hook exactly once per
 * {@code ClientPlayerEntity.tickMovement}, right after vanilla read the movement keys and before
 * the sprint start/stop logic and the move run. Each hook receives the input built so far and
 * returns the input to use (return {@code input} unchanged for no change). The movement keys and
 * keybindings are not touched, so physical key state and overlays are unaffected.
 *
 * <p>Because the call happens once per movement tick, a module may consume one tick of a plan here.
 */
public interface MovementInputModifier {
    PlayerInput modifyMovementInput(MinecraftClient mc, PlayerInput input);
}
