package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;
import net.minecraft.entity.player.PlayerEntity;

public class EventSwingHand implements Event {
    private final PlayerEntity player;

    public EventSwingHand(PlayerEntity player) {
        this.player = player;
    }

    public PlayerEntity getPlayer() {
        return player;
    }
}
