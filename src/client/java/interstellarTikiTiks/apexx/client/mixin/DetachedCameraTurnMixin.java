package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.FreecamProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam mouse seam: while the camera is detached the mouse turns the camera, not the player.
 */
@Mixin(Entity.class)
public abstract class DetachedCameraTurnMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void apexx$freecamTurn(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || (Object) this != mc.player) return;
        FreecamProvider freecam = ModuleAccess.getEnabledHook(FreecamProvider.class);
        if (freecam != null && freecam.turnFreecam(cursorDeltaX, cursorDeltaY)) {
            ci.cancel();
        }
    }
}
