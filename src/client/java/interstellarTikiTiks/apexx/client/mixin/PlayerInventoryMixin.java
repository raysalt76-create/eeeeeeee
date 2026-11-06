package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes local hotbar changes without changing slot selection or synchronization. */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow private int selectedSlot;
    @Unique private int apexx$previousSelectedSlot;

    @Inject(method = "setSelectedSlot", at = @At("HEAD"))
    private void apexx$beforeSelection(int slot, CallbackInfo ci) {
        apexx$previousSelectedSlot = selectedSlot;
    }

    @Inject(method = "setSelectedSlot", at = @At("TAIL"))
    private void apexx$showSelection(int slot, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (apexx$previousSelectedSlot == selectedSlot) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.isOnThread() && mc.player != null
                && mc.player.getInventory() == (Object) this) {
            SystemInputSimulator.showHotbarSelection(slot);
        }
    }
}
