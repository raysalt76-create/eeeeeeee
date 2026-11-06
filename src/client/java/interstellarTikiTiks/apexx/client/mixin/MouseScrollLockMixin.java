package interstellarTikiTiks.apexx.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.HotbarScrollBlocker;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.Mouse;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hotbar Scroll Lock seam. Wraps only the selected-slot change of the in-game mouse-wheel branch of
 * {@code Mouse.onMouseScroll}: screen scrolling, the spectator menu and flight-speed scrolling are
 * other branches, and number keys / module swaps call {@code setSelectedSlot} elsewhere.
 */
@Mixin(Mouse.class)
public abstract class MouseScrollLockMixin {

    @WrapOperation(method = "onMouseScroll", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerInventory;setSelectedSlot(I)V"))
    private void apexx$lockHotbarScroll(PlayerInventory inventory, int slot, Operation<Void> original) {
        if (!Apexx.isTornDown()) {
            for (HotbarScrollBlocker blocker : ModuleAccess.getEnabledHooks(HotbarScrollBlocker.class)) {
                if (ModuleCallbackGuard.call(blocker, "hotbar scroll lock", blocker::blockHotbarScroll, false)) {
                    return;
                }
            }
        }
        original.call(inventory, slot);
    }
}
