package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;

/**
 * AntiTranslation — Prevents servers from forcibly translating or rotating client camera pitch and yaw.
 * 
 * <p>Protects against DonutSMP-style relative rotation desyncs and server translation exploits.
 * Ported from Nitro Orchard reference architecture (dev.orchard.module.player.AntiTranslation).
 */
public class AntiTranslation extends Module implements IncomingPacketInterceptor {

    private final BooleanSetting blockRelativeYaw = new BooleanSetting("Preserve Look Yaw", true)
            .setDescription("Ignores the horizontal look change in a server teleport, so your left/right facing stays where you had it.");
    private final BooleanSetting blockRelativePitch = new BooleanSetting("Preserve Look Pitch", true)
            .setDescription("Ignores the vertical look change in a server teleport, so your up/down facing stays where you had it.");

    public AntiTranslation() {
        // BLATANT: sends TeleportConfirm for a server teleport it deliberately does NOT apply, so your
        // next move packet reports the old position. A guaranteed, self-inflicted desync.
        super("AntiTranslation", "Keeps your camera where it was when the server teleports or rotates you, by confirming the teleport without applying it. This deliberately leaves your position out of sync with the server and is flagged by any server that checks.", Category.BLATANT);
        addSetting(blockRelativeYaw);
        addSetting(blockRelativePitch);
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (!isEnabled() || mc.player == null || mc.getNetworkHandler() == null) {
            return false;
        }

        if (packet instanceof PlayerPositionLookS2CPacket posLook) {
            java.util.Set<PositionFlag> rel = posLook.relatives();

            // A genuine server teleport / anti-cheat correction carries an ABSOLUTE position
            // component (X, Y or Z not flagged relative). Fighting that fully desyncs us, so we
            // stand down: let the packet through untouched and apply the correction. We only ever
            // suppress a pure relative-rotation twist where the position is left alone (relative).
            boolean absolutePosition = !rel.contains(PositionFlag.X)
                    || !rel.contains(PositionFlag.Y)
                    || !rel.contains(PositionFlag.Z);
            if (absolutePosition) {
                return false; // real teleport/correction — stand down and apply it
            }

            boolean hasRelYaw = blockRelativeYaw.isEnabled() && rel.contains(PositionFlag.Y_ROT);
            boolean hasRelPitch = blockRelativePitch.isEnabled() && rel.contains(PositionFlag.X_ROT);

            if (hasRelYaw || hasRelPitch) {
                // Acknowledge the teleport to server without applying the forced camera twist
                mc.getNetworkHandler().sendPacket(new TeleportConfirmC2SPacket(posLook.teleportId()));
                return true; // Drop packet to prevent client-side camera snap
            }
        }
        return false;
    }
}
