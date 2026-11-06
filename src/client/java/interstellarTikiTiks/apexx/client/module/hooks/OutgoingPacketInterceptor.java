package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.network.packet.Packet;

public interface OutgoingPacketInterceptor {

    boolean handleOutgoingPacket(Packet<?> packet);
}
