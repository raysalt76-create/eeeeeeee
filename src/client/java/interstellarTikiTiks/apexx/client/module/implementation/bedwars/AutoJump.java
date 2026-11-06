package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;

/**
 * AutoJump (edge jump): presses jump on the last tick you are still standing on a ledge, so running
 * off an edge becomes a full sprint-jump instead of a drop.
 *
 * <p>It only adds a jump press to that one movement tick; it never releases a jump you are holding.
 * The edge is found by moving your real hitbox by your current motion and checking real collision
 * shapes below it, and small steps down can be ignored with the minimum drop depth.
 */
public class AutoJump extends Module implements MovementInputModifier {

    private final BooleanSetting cancelSneaking = new BooleanSetting("Cancel When Sneaking", true)
            .setDescription("Never jumps while you are sneaking, so sneaking to an edge stays safe.");
    private final BooleanSetting onlySprinting = new BooleanSetting("Only When Sprinting", false)
            .setDescription("Only jumps off edges while you are sprinting.");
    private final SliderSetting lookahead = new SliderSetting("Edge Lookahead (ticks)", 1.0, 0.5, 2.0, 1)
            .setDescription("How far ahead (in ticks of your current motion) the edge is detected. 1.0 jumps on the last tick you are still on the block; higher jumps slightly earlier.");
    private final SliderSetting minDrop = new SliderSetting("Min Drop Depth", 1.0, 0.6, 5.0, 1)
            .setDescription("Only jumps if there is no floor within this many blocks below where you would step, so walking down a single step does not trigger it.");

    private final SliderSetting cooldown = new SliderSetting("Re-Jump Cooldown (ms)", 300, 0, 1500, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Minimum time between two edge jumps.");

    private long nextAllowedMs;

    public AutoJump() {
        super("AutoJump", "Jumps for you on the last tick you are standing on a ledge, so running off an edge becomes a full jump instead of a drop.", Category.BEDWARS);
        addSetting(cancelSneaking);
        addSetting(onlySprinting);
        addSetting(lookahead);
        addSetting(minDrop);
        addSetting(cooldown);
    }

    @Override
    public void onEnable() {
        nextAllowedMs = 0L;
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.currentScreen != null || input.jump()) return input;
        if (cancelSneaking.isEnabled() && input.sneak()) return input;
        if (onlySprinting.isEnabled() && !p.isSprinting()) return input;
        if (!p.isOnGround() || p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle()) return input;
        long now = System.currentTimeMillis();
        if (now < nextAllowedMs) return input;
        if (!BedwarsSupport.edgeAhead(client.world, p, lookahead.getValue(), minDrop.getValue())) return input;
        nextAllowedMs = now + (long) cooldown.sample();
        return new PlayerInput(input.forward(), input.backward(), input.left(), input.right(), true, input.sneak(), input.sprint());
    }
}
