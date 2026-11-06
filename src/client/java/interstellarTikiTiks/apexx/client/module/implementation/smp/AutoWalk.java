package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;

/**
 * AutoWalk (SMP) - walks forward for you. Ported from LiquidBounce's AutoWalk, with sprint, an
 * auto-jump over one-block steps, and the usual ways to stop.
 *
 * <p>It adds "forward" to the movement input record, so your real key bindings are left alone and
 * nothing sticks when it is turned off.</p>
 */
public final class AutoWalk extends Module implements MovementInputModifier {

    private final BooleanSetting sprint = new BooleanSetting("Sprint", true)
            .setDescription("Sprints while auto-walking. Like vanilla, sprinting still needs more than 3 hunger bars.");
    private final BooleanSetting autoJump = new BooleanSetting("Auto Jump", false)
            .setDescription("Jumps automatically when you walk into a one-block step, so travel doesn't stall on uneven ground.");
    private final BooleanSetting pauseInScreens = new BooleanSetting("Pause In Screens", true)
            .setDescription("Stops walking while a menu, chat or inventory is open, and resumes when it closes.");
    private final BooleanSetting stopOnBack = new BooleanSetting("Disable On Back", true)
            .setDescription("Turns AutoWalk off as soon as you press the back key, giving you an instant manual stop.");
    private final BooleanSetting stopOnDamage = new BooleanSetting("Disable On Damage", false)
            .setDescription("Turns AutoWalk off the moment you take damage, so you don't walk on into danger.");

    private float lastHealth = -1.0f;

    public AutoWalk() {
        super("AutoWalk", "Holds forward for you so long trips need no input: walks (and optionally sprints and steps up blocks) in the direction you face until you stop it. Uses the normal movement input, so nothing sticks when it turns off.", Category.MOVEMENT);
        addSetting(sprint);
        addSetting(autoJump);
        addSetting(pauseInScreens);
        addSetting(stopOnBack);
        addSetting(stopOnDamage);
    }

    @Override
    public void onEnable() {
        lastHealth = mc.player != null ? mc.player.getHealth() : -1.0f;
    }

    @Override
    public void onTick() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        if (stopOnBack.isEnabled() && mc.currentScreen == null && mc.options.backKey.isPressed()) {
            setEnabled(false);
            return;
        }
        float health = player.getHealth();
        if (stopOnDamage.isEnabled() && lastHealth >= 0.0f && health < lastHealth) {
            setEnabled(false);
            return;
        }
        lastHealth = health;
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        ClientPlayerEntity player = client.player;
        if (player == null || (pauseInScreens.isEnabled() && client.currentScreen != null)) return input;
        boolean jump = input.jump() || (autoJump.isEnabled() && player.horizontalCollision && player.isOnGround());
        boolean sprinting = input.sprint() || sprint.isEnabled();
        return new PlayerInput(true, input.backward(), input.left(), input.right(), jump, input.sneak(), sprinting);
    }
}
