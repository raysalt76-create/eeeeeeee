package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;

/**
 * Speed (SMP) - moves you faster on the ground or with a hop. LiquidBounce's Speed is a list of
 * anticheat-specific modes; this keeps the two generic ideas of it (a plain strafe hop and a ground
 * boost) with the tunables of its Custom mode (strafe strength, jump height).
 *
 * <p>For servers with no movement checks.</p>
 */
public final class Speed extends Module {

    private static final String HOP = "Hop";
    private static final String GROUND = "Ground";

    private final ModeSetting mode = new ModeSetting("Mode", HOP, GROUND)
            .setDescription("Hop: bunny-hops while moving and steers each hop toward the keys you hold, fastest overall. Ground: keeps you on the floor and only raises ground speed, which is smoother in tight spaces.");
    private final SliderSetting speed = new SliderSetting("Speed", 0.42, 0.1, 2.0, 2)
            .setDescription("Blocks per tick along the ground. Plain sprinting is about 0.28; a vanilla hop peaks near 0.36.");
    private final SliderSetting strafeStrength = new SliderSetting("Strafe Strength", 1.0, 0.1, 1.0, 2)
            .setDescription("How much of your motion is turned toward the keys you hold each tick. 1 is full control in the air; lower keeps more of the old direction.");
    private final BooleanSetting autoJump = new BooleanSetting("Auto Jump", true)
            .setDescription("Hops by itself while a movement key is held, keeping the speed up.");
    private final SliderSetting jumpHeight = new SliderSetting("Jump Height", 0.42, 0.2, 1.0, 2)
            .setDescription("Vertical speed of each hop. 0.42 is a vanilla jump.");
    private final BooleanSetting potionScaling = new BooleanSetting("Potion Scaling", true)
            .setDescription("Scales the speed with a Speed or Slowness effect the way vanilla scales walking.");
    private final BooleanSetting stopWhenIdle = new BooleanSetting("Stop When Idle", false)
            .setDescription("Cuts your ground speed to zero when no movement key is held, instead of sliding.");
    private final BooleanSetting pauseInLiquid = new BooleanSetting("Pause In Liquid", true)
            .setDescription("Leaves your motion alone in water and lava.");
    private final BooleanSetting pauseWhileSneaking = new BooleanSetting("Pause While Sneaking", true)
            .setDescription("Leaves your motion alone while sneaking.");

    public Speed() {
        super("Speed", "Sets your ground and air speed directly for much faster travel, with optional automatic strafe hops. Hop mode bunny-hops to keep speed; Ground mode stays on the floor. Pauses in liquids and while sneaking by default. Blatant: any server with movement checks will flag it, so only use it on servers without them.", Category.BLATANT);
        addSetting(mode);
        addSetting(speed);
        addSetting(strafeStrength);
        addSetting(autoJump);
        autoJump.setVisibility(() -> mode.is(HOP));
        addSetting(jumpHeight);
        jumpHeight.setVisibility(() -> mode.is(HOP) && autoJump.isEnabled());
        addSetting(potionScaling);
        addSetting(stopWhenIdle);
        addSetting(pauseInLiquid);
        addSetting(pauseWhileSneaking);
    }

    /** Runs right before the player's own tick, so the velocity set here is the one the move uses. */
    @Override
    public void onTickPost() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.currentScreen != null || player.isSpectator()
                || player.isGliding() || player.isClimbing() || player.getAbilities().flying) {
            return;
        }
        if (pauseInLiquid.isEnabled() && (player.isTouchingWater() || player.isInLava())) return;
        if (pauseWhileSneaking.isEnabled() && player.isSneaking()) return;

        double forward = axis(mc.options.forwardKey.isPressed(), mc.options.backKey.isPressed());
        double strafe = axis(mc.options.leftKey.isPressed(), mc.options.rightKey.isPressed());
        Vec3d velocity = player.getVelocity();
        boolean moving = forward != 0.0 || strafe != 0.0;

        if (!moving) {
            if (stopWhenIdle.isEnabled() && player.isOnGround()) {
                player.setVelocity(0.0, velocity.y, 0.0);
            }
            return;
        }

        boolean hop = mode.is(HOP);
        // Ground mode only boosts while grounded; in the air vanilla's own control stays.
        if (!hop && !player.isOnGround()) return;

        double target = speed.getValue() * (potionScaling.isEnabled() ? potionFactor(player) : 1.0);
        double length = Math.sqrt(forward * forward + strafe * strafe);
        forward /= length;
        strafe /= length;
        double yaw = Math.toRadians(player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double wantX = (strafe * cos - forward * sin) * target;
        double wantZ = (forward * cos + strafe * sin) * target;

        double k = strafeStrength.getValue();
        double x = velocity.x + (wantX - velocity.x) * k;
        double z = velocity.z + (wantZ - velocity.z) * k;
        double y = velocity.y;
        if (hop && autoJump.isEnabled() && player.isOnGround()) {
            y = jumpHeight.getValue();
        }
        player.setVelocity(x, y, z);
    }

    private static double potionFactor(ClientPlayerEntity player) {
        double factor = 1.0;
        StatusEffectInstance haste = player.getStatusEffect(StatusEffects.SPEED);
        if (haste != null) factor *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
        StatusEffectInstance slow = player.getStatusEffect(StatusEffects.SLOWNESS);
        if (slow != null) factor *= Math.max(0.0, 1.0 - 0.15 * (slow.getAmplifier() + 1));
        return factor;
    }

    private static double axis(boolean positive, boolean negative) {
        return positive == negative ? 0.0 : (positive ? 1.0 : -1.0);
    }
}
