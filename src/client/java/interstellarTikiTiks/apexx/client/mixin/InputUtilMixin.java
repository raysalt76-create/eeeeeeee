package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exposes simulated holds to overlays polling InputUtil.isKeyPressed(...).
 */
@Mixin(InputUtil.class)
public abstract class InputUtilMixin {

    @Inject(method = "isKeyPressed(Lnet/minecraft/client/util/Window;I)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void apexx$isKeyPressedWindow(Window window, int code, CallbackInfoReturnable<Boolean> cir) {
        if (SystemInputSimulator.isKeyClickSimPressed(code)) {
            cir.setReturnValue(true);
        }
    }
}
