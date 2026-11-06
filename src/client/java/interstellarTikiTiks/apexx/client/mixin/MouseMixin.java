package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.MouseAimHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.network.ClientPlayerEntity;

@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void apexx$applyAimDelta(CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        if (client.player == null || client.currentScreen != null) return;
        
        if (interstellarTikiTiks.apexx.client.util.RotationManager.isMouseLocked()) {
            cursorDeltaX = 0.0;
            cursorDeltaY = 0.0;
        }

        // RotationManager is advanced once per render frame from MinecraftClientMixin. Keeping
        // that update out of the input callback prevents aim from stalling on frames where GLFW
        // does not deliver an updateMouse pass (no cursor delta, focus transitions, or input mods).

        if (interstellarTikiTiks.apexx.client.util.RotationManager.isMouseLocked()) {
            cursorDeltaX = 0.0;
            cursorDeltaY = 0.0;
            return;
        }

        double dx = MouseAimHelper.pollDX();
        double dy = MouseAimHelper.pollDY();
        if (dx != 0.0 || dy != 0.0) {
            cursorDeltaX += dx;
            cursorDeltaY += dy;
        }

        java.util.List<interstellarTikiTiks.apexx.client.module.hooks.MouseSensitivityModifier> modifiers = 
                interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.MouseSensitivityModifier.class);
        if (!modifiers.isEmpty()) {
            float combinedMult = 1.0f;
            for (interstellarTikiTiks.apexx.client.module.hooks.MouseSensitivityModifier modifier : modifiers) {
                float mult = interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard.call(modifier,
                        "mouse sensitivity", modifier::getSensitivityMultiplier, 1.0f);
                if (mult >= 0.0f && mult < combinedMult) {
                    combinedMult = mult;
                }
            }
            if (combinedMult < 0.999f) {
                cursorDeltaX *= combinedMult;
                cursorDeltaY *= combinedMult;
            }
        }

        java.util.List<interstellarTikiTiks.apexx.client.module.hooks.MouseDeltaShaper> shapers =
                interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.MouseDeltaShaper.class);
        if (!shapers.isEmpty() && (cursorDeltaX != 0.0 || cursorDeltaY != 0.0)) {
            // Convert to the degrees vanilla will turn by, so shapers work in camera angles.
            double s = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            boolean scoped = client.options.getPerspective().isFirstPerson() && client.player.isUsingSpyglass();
            double degPerUnit = s * s * s * (scoped ? 1.0 : 8.0) * 0.15;
            double yawSign = client.options.getInvertMouseX().getValue() ? -1.0 : 1.0;
            double pitchSign = client.options.getInvertMouseY().getValue() ? -1.0 : 1.0;
            if (degPerUnit > 1.0e-9) {
                double[] look = {cursorDeltaX * degPerUnit * yawSign, cursorDeltaY * degPerUnit * pitchSign};
                for (interstellarTikiTiks.apexx.client.module.hooks.MouseDeltaShaper shaper : shapers) {
                    interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard.run(shaper, "mouse delta shaper",
                            () -> shaper.shapeLookDelta(look));
                }
                if (Double.isFinite(look[0]) && Double.isFinite(look[1])) {
                    cursorDeltaX = look[0] / (degPerUnit * yawSign);
                    cursorDeltaY = look[1] / (degPerUnit * pitchSign);
                }
            }
        }
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void apexx$onMouseButton(long window, net.minecraft.client.input.MouseInput input, int action, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        // Feedback callbacks accompany an existing vanilla interaction. Do not let them
        // enqueue another world click or click an inventory screen at the cursor position.
        if (interstellarTikiTiks.apexx.client.util.InputTracker.isFeedbackCallback()) {
            ci.cancel();
            return;
        }
        int button = input.button();
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            interstellarTikiTiks.apexx.client.util.InputTracker.setMouseButtonState(button, true);
        } else if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
            interstellarTikiTiks.apexx.client.util.InputTracker.setMouseButtonState(button, false);
        }
    }

    @Redirect(
            method = "unlockCursor",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/util/InputUtil;setCursorParameters(Lnet/minecraft/client/util/Window;IDD)V")
    )
    private void apexx$redirectUnlockCursor(net.minecraft.client.util.Window window, int mode, double x, double y) {
        if (!Apexx.isTornDown() && client.currentScreen != null && client.currentScreen.getClass().getName().endsWith("GpuClickGuiScreen")
                && interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isSsProofReady()) {
            net.minecraft.client.util.InputUtil.setCursorParameters(window, org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN, x, y);
        } else {
            net.minecraft.client.util.InputUtil.setCursorParameters(window, mode, x, y);
        }
    }

    @Redirect(method = "updateMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void apexx$redirectPlayerLook(ClientPlayerEntity player, double d, double d2) {
        if (client.currentScreen != null) return;
        if (Apexx.isTornDown()) {
            player.changeLookDirection(d, d2);
            return;
        }
        interstellarTikiTiks.apexx.client.util.RotationManager.handleMouseLook(player, d, d2);
    }
}
