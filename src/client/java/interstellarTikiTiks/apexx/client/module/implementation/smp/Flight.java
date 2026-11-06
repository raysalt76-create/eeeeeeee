package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * Flight (SMP) - vanilla-style fly: horizontal speed from the movement keys, vertical from jump and
 * sneak, hover or drift when neither is held. Ported from LiquidBounce's Fly "Vanilla" mode; every
 * anticheat-specific mode of that module is left out on purpose.
 *
 * <p>For servers with no movement checks. The only server-side rule it works around is vanilla's own
 * "floating too long" kick: with Anti Kick on, it dips a little for one tick on an interval.</p>
 */
public final class Flight extends Module implements IncomingPacketInterceptor {

    private final SliderSetting horizontalSpeed = new SliderSetting("Horizontal Speed", 0.5, 0.1, 5.0, 2)
            .setDescription("Blocks per tick along the ground while a movement key is held (0.5 is ten blocks a second).");
    private final SliderSetting verticalSpeed = new SliderSetting("Vertical Speed", 0.45, 0.1, 5.0, 2)
            .setDescription("Blocks per tick up on jump and down on sneak.");
    private final SliderSetting hover = new SliderSetting("Hover Drift", 0.0, -0.5, 0.5, 2)
            .setDescription("Vertical blocks per tick while neither jump nor sneak is held. 0 hovers; below 0 sinks slowly.");
    private final BooleanSetting sprintBoost = new BooleanSetting("Sprint Boost", true)
            .setDescription("Flies faster while you hold the sprint key, using Sprint Multiplier.");
    private final SliderSetting sprintMultiplier = new SliderSetting("Sprint Multiplier", 1.6, 1.0, 4.0, 1)
            .setDescription("How much faster you fly while sprint is held (1.6 = 60% faster).")
            .setDependsOn("Sprint Boost");
    private final BooleanSetting antiKick = new BooleanSetting("Anti Kick", true)
            .setDescription("Dips for a single tick now and then so vanilla's \"flying is not enabled\" kick does not trigger.");
    private final SliderSetting antiKickInterval = new SliderSetting("Anti Kick Interval", 30.0, 10.0, 70.0, 0)
            .setDescription("Ticks between anti-kick dips. Vanilla kicks after 80 ticks of floating, so this stays below 70.")
            .setDependsOn("Anti Kick");
    private final BooleanSetting stopOnDisable = new BooleanSetting("Stop On Disable", true)
            .setDescription("Zeroes your motion when turned off so you do not keep flying off.");
    private final BooleanSetting disableOnSetback = new BooleanSetting("Disable On Setback", false)
            .setDescription("Turns off when the server teleports you back.");

    private int ticks;
    private boolean dipping;
    private volatile boolean setbackSeen;

    public Flight() {
        super("Flight", "Creative-style flight in survival: move freely in any direction with jump to rise and sneak to sink, hovering in place when idle. Sets your velocity directly and dips briefly now and then to avoid vanilla's idle-flying kick. Blatant: any server with movement checks will flag it.", Category.BLATANT);
        addSetting(horizontalSpeed);
        addSetting(verticalSpeed);
        addSetting(hover);
        addSetting(sprintBoost);
        addSetting(sprintMultiplier);
        sprintMultiplier.setVisibility(sprintBoost::isEnabled);
        addSetting(antiKick);
        addSetting(antiKickInterval);
        antiKickInterval.setVisibility(antiKick::isEnabled);
        addSetting(stopOnDisable);
        addSetting(disableOnSetback);
    }

    @Override
    public void onEnable() {
        ticks = 0;
        dipping = false;
        setbackSeen = false;
    }

    @Override
    public void onDisable() {
        ClientPlayerEntity player = mc.player;
        if (player != null && stopOnDisable.isEnabled()) {
            player.setVelocity(Vec3d.ZERO);
        }
    }

    /** A server teleport (setback) shows as a position packet; it is only noted, never cancelled. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof PlayerPositionLookS2CPacket) setbackSeen = true;
        return false;
    }

    /** Runs right before the player's own tick, so the velocity set here is the one the move uses. */
    @Override
    public void onTickPost() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.currentScreen != null || player.isSpectator()
                || player.isGliding()) {
            return;
        }
        if (setbackSeen && disableOnSetback.isEnabled()) {
            setbackSeen = false;
            setEnabled(false);
            return;
        }
        setbackSeen = false;

        boolean boost = sprintBoost.isEnabled() && mc.options.sprintKey.isPressed();
        double factor = boost ? sprintMultiplier.getValue() : 1.0;
        double horizontal = horizontalSpeed.getValue() * factor;
        double vertical = verticalSpeed.getValue() * factor;

        double forward = axis(mc.options.forwardKey.isPressed(), mc.options.backKey.isPressed());
        double strafe = axis(mc.options.leftKey.isPressed(), mc.options.rightKey.isPressed());
        double x = 0.0;
        double z = 0.0;
        if (forward != 0.0 || strafe != 0.0) {
            double length = Math.sqrt(forward * forward + strafe * strafe);
            forward /= length;
            strafe /= length;
            double yaw = Math.toRadians(player.getYaw());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            x = (strafe * cos - forward * sin) * horizontal;
            z = (forward * cos + strafe * sin) * horizontal;
        }

        boolean up = mc.options.jumpKey.isPressed();
        boolean down = mc.options.sneakKey.isPressed();
        double y;
        if (up && !down) y = vertical;
        else if (down && !up) y = -vertical;
        else y = hover.getValue();

        ticks++;
        if (dipping) {
            // The dip is one tick long; the tick after it is flown normally again.
            dipping = false;
        } else if (antiKick.isEnabled() && ticks >= (int) antiKickInterval.getValue()) {
            ticks = 0;
            dipping = true;
            y = Math.min(y, -0.04);
        }
        player.setVelocity(x, y, z);
    }

    private static double axis(boolean positive, boolean negative) {
        return positive == negative ? 0.0 : (positive ? 1.0 : -1.0);
    }
}
