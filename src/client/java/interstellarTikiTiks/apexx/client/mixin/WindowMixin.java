package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                    shift = At.Shift.BEFORE
            ),
            require = 0
    )
    private void apexx$maybeHideWindow(CallbackInfo ci) {
        if (shouldHideWindow()) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        }
    }

    private static boolean shouldHideWindow() {
        if (Boolean.getBoolean("apexx.hide_window")) {
            return true;
        }

        try {
            return Files.exists(Path.of(".apexx-hide-window"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
