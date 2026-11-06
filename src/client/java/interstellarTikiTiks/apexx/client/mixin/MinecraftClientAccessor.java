package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

    @Accessor("itemUseCooldown")
    int getItemUseCooldown();

    @Mutable
    @Accessor("itemUseCooldown")
    void setItemUseCooldown(int cooldown);

    // MinecraftClient.attackCooldown, used by the client attack-timing helpers.
    @Accessor("attackCooldown")
    int getAttackCooldown();

    @Mutable
    @Accessor("attackCooldown")
    void setAttackCooldown(int cooldown);

    @Invoker("doAttack")
    boolean attack();

    @Invoker("doItemUse")
    void useItem();

}
