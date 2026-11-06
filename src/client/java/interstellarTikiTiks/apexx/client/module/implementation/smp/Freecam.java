package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.FreecamProvider;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Freecam (SMP) - flies the camera out of your body with the movement keys. Your body stays where it
 * is. The camera pose is placed by {@code CameraMixin} and the mouse turns the camera
 * ({@code FreecamTurnMixin}) instead of your body.
 */
public final class Freecam extends Module implements FreecamProvider, MovementInputModifier,
        IncomingPacketInterceptor {

    private final SliderSetting speed = new SliderSetting("Speed", 1.0, 0.1, 5.0, 1)
            .setDescription("Blocks per tick the camera moves at.");
    private final SliderSetting sprintMultiplier = new SliderSetting("Sprint Multiplier", 2.0, 1.0, 5.0, 1)
            .setDescription("Speed multiplier while the sprint key is held.");
    private final BooleanSetting followPitch = new BooleanSetting("Fly Where You Look", false)
            .setDescription("Forward and back follow the camera's vertical look as well, so it dives where you aim.");
    private final BooleanSetting freezeBody = new BooleanSetting("Freeze Body", true)
            .setDescription("Blanks your movement keys for the player so only the camera moves.");
    private final BooleanSetting cancelOnDamage = new BooleanSetting("Cancel On Damage", true)
            .setDescription("Turns off when you take damage.");
    private final BooleanSetting cancelOnTeleport = new BooleanSetting("Cancel On Teleport", true)
            .setDescription("Turns off when the server teleports you.");

    private final SliderSetting cancelTime = new SliderSetting("Cancel Time (s)", 0.0, 0.0, 120.0, 1)
            .setDescription("Turns Freecam off by itself after this many seconds. 0 keeps it on until you turn it off.");

    private Vec3d position = Vec3d.ZERO;
    private Vec3d previous = Vec3d.ZERO;
    private float yaw;
    private float pitch;
    private float previousYaw;
    private float previousPitch;
    private float lastHealth = -1.0f;
    private volatile boolean teleported;
    private int activeTicks;

    public Freecam() {
        super("Freecam", "Detaches the camera from your body so you can fly it around to scout, while your player stays put. Your real position and rotation are untouched, and it turns itself off on damage or teleport by default so you are never caught looking away.", Category.SMP);
        addSetting(speed);
        addSetting(sprintMultiplier);
        addSetting(followPitch);
        addSetting(freezeBody);
        addSetting(cancelOnDamage);
        addSetting(cancelOnTeleport);
        addSetting(cancelTime);
    }

    @Override
    public void onEnable() {
        teleported = false;
        activeTicks = 0;
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            position = previous = Vec3d.ZERO;
            lastHealth = -1.0f;
            return;
        }
        position = previous = player.getEyePos();
        yaw = previousYaw = player.getYaw();
        pitch = previousPitch = player.getPitch();
        lastHealth = player.getHealth();
    }

    /** The camera is gone with the world; nothing of it may carry into the next one. */
    @Override
    protected void onRuntimeStateReleased() {
        if (isEnabled()) setEnabled(false);
    }

    @Override
    public Vec3d getFreecamPosition(float tickDelta) {
        if (mc.player == null) return null;
        return previous.lerp(position, tickDelta);
    }

    @Override
    public float[] getFreecamRotation(float tickDelta) {
        if (mc.player == null) return null;
        return new float[]{
                MathHelper.lerp(tickDelta, previousYaw, yaw),
                MathHelper.lerp(tickDelta, previousPitch, pitch)};
    }

    @Override
    public boolean turnFreecam(double cursorDeltaX, double cursorDeltaY) {
        yaw += (float) (cursorDeltaX * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (cursorDeltaY * 0.15), -90.0f, 90.0f);
        return true;
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof PlayerPositionLookS2CPacket) teleported = true;
        return false;
    }

    /** While detached the body gets no movement input. */
    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (!freezeBody.isEnabled()) return input;
        return new PlayerInput(false, false, false, false, false, false, false);
    }

    @Override
    public void onTick() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            setEnabled(false);
            return;
        }
        float health = player.getHealth();
        boolean hurt = lastHealth >= 0.0f && health < lastHealth;
        lastHealth = health;
        if ((cancelOnDamage.isEnabled() && hurt) || (cancelOnTeleport.isEnabled() && teleported)) {
            teleported = false;
            setEnabled(false);
            return;
        }
        teleported = false;

        // Cancel Time is in seconds; the game runs 20 ticks a second.
        activeTicks++;
        double limit = cancelTime.getValue();
        if (limit > 0.0 && activeTicks >= Math.round(limit * 20.0)) {
            setEnabled(false);
            return;
        }

        previous = position;
        previousYaw = yaw;
        previousPitch = pitch;
        if (mc.currentScreen != null) return;

        double forward = axis(mc.options.forwardKey.isPressed(), mc.options.backKey.isPressed());
        double strafe = axis(mc.options.leftKey.isPressed(), mc.options.rightKey.isPressed());
        double vertical = axis(mc.options.jumpKey.isPressed(), mc.options.sneakKey.isPressed());
        double step = speed.getValue() * (mc.options.sprintKey.isPressed() ? sprintMultiplier.getValue() : 1.0);

        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = followPitch.isEnabled() ? pitch * MathHelper.RADIANS_PER_DEGREE : 0.0f;
        double flat = Math.cos(pitchRad);
        double x = -Math.sin(yawRad) * forward * flat + Math.cos(yawRad) * strafe;
        double z = Math.cos(yawRad) * forward * flat + Math.sin(yawRad) * strafe;
        double y = -Math.sin(pitchRad) * forward + vertical;

        Vec3d move = new Vec3d(x, y, z);
        if (move.lengthSquared() > 1.0) move = move.normalize();
        position = position.add(move.multiply(step));
    }

    private static double axis(boolean positive, boolean negative) {
        return positive == negative ? 0.0 : (positive ? 1.0 : -1.0);
    }
}
