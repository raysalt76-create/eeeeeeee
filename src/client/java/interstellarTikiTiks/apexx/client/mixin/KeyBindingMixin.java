package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exposes simulated holds to overlays querying KeyBinding.isPressed().
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {

    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void apexx$isSimulatedPressed(CallbackInfoReturnable<Boolean> cir) {
        if (SystemInputSimulator.isBindingClickSimPressed((KeyBinding) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
