package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.InputTracker;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void apexx$onKey(long window, int action, net.minecraft.client.input.KeyInput input, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        int key = input.key();
        if (action == GLFW.GLFW_PRESS) {
            InputTracker.setKeyState(key, true);
        } else if (action == GLFW.GLFW_RELEASE) {
            InputTracker.setKeyState(key, false);
        }
    }
}
