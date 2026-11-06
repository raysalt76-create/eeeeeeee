package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;

/**
 * Auto Header: under a low ceiling, re-jumps the moment you land so you keep the head-hitter speed
 * boost while running or bridging.
 *
 * <p>It works only through the jump input of each movement tick, exactly like tapping space: jump is
 * let go while you are in the air (which also clears vanilla's held-jump cooldown) and pressed again
 * on the tick you land, after the module's re-jump delay. The ceiling check uses your whole hitbox
 * against real collision shapes, so slabs, trapdoors and ceilings over the hitbox edge all count.
 */
public class AutoHeader extends Module implements MovementInputModifier {

    private final BooleanSetting cancelSneaking = new BooleanSetting("Cancel If Sneaking", true)
            .setDescription("Does nothing while you are sneaking.");
    private final BooleanSetting onlyHoldingJump = new BooleanSetting("Only When Holding Jump", true)
            .setDescription("Only works while you physically hold the jump key. Off: jumps under any low ceiling by itself.");
    private final BooleanSetting onlyMoving = new BooleanSetting("Only When Moving", true)
            .setDescription("Only works while a movement key is held, so standing under a ceiling does not bounce you.");
    private final SliderSetting ceilingHeight = new SliderSetting("Max Ceiling Height", 2.0, 2.0, 3.0, 1)
            .setDescription("Highest ceiling (in blocks above your feet) that counts as a head-hitter. 2.0 is the classic two-block gap.");

    private final SliderSetting rejumpDelay = new SliderSetting("Re-Jump Delay (ms)", 60, 0, 500, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("Wait after landing before jump is pressed again. 0 jumps on the very tick you land.");

    private boolean wasOnGround;
    private long landedAtMs;
    private long waitMs;

    public AutoHeader() {
        super("AutoHeader", "Under a low ceiling, re-taps jump the moment you land to keep the head-hitter speed boost while running or bridging.", Category.BEDWARS);
        addSetting(cancelSneaking);
        addSetting(onlyHoldingJump);
        addSetting(onlyMoving);
        addSetting(ceilingHeight);
        addSetting(rejumpDelay);
    }

    @Override
    public void onEnable() {
        wasOnGround = false;
        landedAtMs = 0L;
        waitMs = 0L;
    }

    @Override
    protected void onRuntimeStateReleased() {
        wasOnGround = false;
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.currentScreen != null) return input;
        if (cancelSneaking.isEnabled() && input.sneak()) return input;
        if (p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle() || p.getAbilities().flying) return input;
        if (onlyHoldingJump.isEnabled() && !SystemInputSimulator.isPhysicallyPressed(client.options.jumpKey)) return input;
        if (onlyMoving.isEnabled() && !(input.forward() || input.backward() || input.left() || input.right())) return input;

        boolean onGround = p.isOnGround();
        long now = System.currentTimeMillis();
        if (onGround && !wasOnGround) {
            landedAtMs = now;
            waitMs = (long) rejumpDelay.sample();
        }
        wasOnGround = onGround;

        double clearance = Math.max(0.05, ceilingHeight.getValue() - p.getHeight() + 0.02);
        if (!BedwarsSupport.ceilingAbove(client.world, p, clearance)) return input;

        boolean jump = onGround && now - landedAtMs >= waitMs;
        if (jump == input.jump()) return input;
        return new PlayerInput(input.forward(), input.backward(), input.left(), input.right(), jump, input.sneak(), input.sprint());
    }
}
