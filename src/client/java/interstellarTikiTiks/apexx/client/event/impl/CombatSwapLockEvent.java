package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class CombatSwapLockEvent implements Event {
    private final Object requester;
    private boolean itemSwapLocked = false;
    private boolean autoAttackLocked = false;

    public CombatSwapLockEvent() {
        this(null);
    }

    /** {@code requester} is the module asking; a module's own lock never blocks its own action. */
    public CombatSwapLockEvent(Object requester) {
        this.requester = requester;
    }

    public boolean isRequestedBy(Object module) {
        return requester != null && requester == module;
    }

    public boolean isItemSwapLocked() {
        return itemSwapLocked;
    }

    public void setItemSwapLocked(boolean itemSwapLocked) {
        this.itemSwapLocked = itemSwapLocked;
    }

    public boolean isAutoAttackLocked() {
        return autoAttackLocked;
    }

    public void setAutoAttackLocked(boolean autoAttackLocked) {
        this.autoAttackLocked = autoAttackLocked;
    }
}
