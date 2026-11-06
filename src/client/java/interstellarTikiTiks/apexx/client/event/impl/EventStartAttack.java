package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class EventStartAttack implements Event {
    private boolean cancelled = false;

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public static class Pre extends EventStartAttack {
    }
}
