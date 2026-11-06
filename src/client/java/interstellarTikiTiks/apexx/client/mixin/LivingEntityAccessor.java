package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for {@link LivingEntity} to allow external mutation of
 * {@code jumpingCooldown}, which is otherwise private.
 *
 * <p>Used by {@code PerfectWindcharge} to zero the cooldown so the forced
 * jump key press is immediately acted upon without waiting for the cooldown
 * to expire naturally.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Mutable
    @Accessor("jumpingCooldown")
    void apexx$setJumpingCooldown(int cooldown);

    @Accessor("jumpingCooldown")
    int apexx$getJumpingCooldown();

    @Accessor("lastHeadYaw")
    float apexx$getPrevHeadYaw();

    @Mutable
    @Accessor("lastHeadYaw")
    void apexx$setPrevHeadYaw(float yaw);

    @Accessor("lastBodyYaw")
    float apexx$getPrevBodyYaw();

    @Mutable
    @Accessor("lastBodyYaw")
    void apexx$setPrevBodyYaw(float yaw);

    @Mutable
    @Accessor("ticksSinceLastAttack")
    void apexx$setTicksSinceLastAttack(int ticks);
}
