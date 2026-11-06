package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.network.packet.Packet;

public interface IncomingPacketInterceptor {

    /**
     * Called when an incoming packet is received.
     * @param packet The packet received.
     * @return true if the packet should be cancelled (intercepted), false to let it process.
     */
    boolean handleIncomingPacket(Packet<?> packet);
}
