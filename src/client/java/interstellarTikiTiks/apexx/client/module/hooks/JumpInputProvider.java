package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.MinecraftClient;

/**
 * Hook interface for modules that need to force the jump key pressed during
 * {@code ClientPlayerEntity.tickMovement()}.
 *
 * <p>Queried each tick from {@code ClientPlayerEntityMixin}. Every enabled hook is
 * queried; if any returns {@code true}, the mixin sets {@code jumpKey.setPressed(true)} before
 * input processing and restores the physical keyboard state at RETURN.
 *
 * <p>No concrete module class from the implementation layer may be referenced
 * directly from the mixin — all communication must flow through this interface
 * via {@code ModuleAccess.getEnabledHooks(JumpInputProvider.class)}.
 */
public interface JumpInputProvider {
    /**
     * Returns {@code true} if this module wants the jump key forced pressed
     * for the current tick.
     *
     * @param mc the current {@link MinecraftClient} instance
     * @return {@code true} to force a jump this tick
     */
    boolean shouldJump(MinecraftClient mc);
}
