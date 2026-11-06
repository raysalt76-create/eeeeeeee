package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class TriggerBotReadyEvent implements Event {
    private boolean cancelled = false;
    private final net.minecraft.entity.Entity target;

    public TriggerBotReadyEvent(net.minecraft.entity.Entity target) {
        this.target = target;
    }

    public net.minecraft.entity.Entity getTarget() {
        return target;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
