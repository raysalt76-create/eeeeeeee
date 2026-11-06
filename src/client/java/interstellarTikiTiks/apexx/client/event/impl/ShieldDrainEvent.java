package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class ShieldDrainEvent implements Event {
    private boolean active = false;

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
