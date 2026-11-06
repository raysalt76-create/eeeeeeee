package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;
import net.minecraft.network.packet.Packet;

public class PacketReceiveEvent implements Event {
    private final Packet<?> packet;
    
    public PacketReceiveEvent(Packet<?> packet) {
        this.packet = packet;
    }
    
    public Packet<?> getPacket() {
        return packet;
    }
}
