package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.entity.Entity;

/**
 * Observes entity status packets on the client thread, after vanilla resolved the entity and before it
 * handles the status. Only statuses 3 (death) and 35 (totem pop) are dispatched.
 */
public interface EntityStatusAware {
    void onEntityStatus(Entity entity, byte status);
}
