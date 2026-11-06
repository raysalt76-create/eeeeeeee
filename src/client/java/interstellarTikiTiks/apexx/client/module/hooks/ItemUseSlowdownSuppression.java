package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Hook for modules that remove the movement slowdown vanilla applies while the player uses an item
 * (eating, drinking, blocking, drawing a bow). {@code ClientPlayerEntityMixin} asks every enabled
 * provider once per movement tick; any provider returning true skips the slowdown for that tick.
 */
public interface ItemUseSlowdownSuppression {
    boolean suppressItemUseSlowdown(ClientPlayerEntity player);
}
