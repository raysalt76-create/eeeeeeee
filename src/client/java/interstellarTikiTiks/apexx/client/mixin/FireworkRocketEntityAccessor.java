package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read-only access used to identify the local player's still-live elytra rocket. */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {
    @Accessor("shooter")
    LivingEntity apexx$getShooter();

    @Invoker("wasShotByEntity")
    boolean apexx$wasShotByEntity();
}
