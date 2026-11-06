package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.JumpInputProvider;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseSlowdownSuppression;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @org.spongepowered.asm.mixin.Shadow
    private float lastYawClient;
    @org.spongepowered.asm.mixin.Shadow
    private float lastPitchClient;

    private float apexx$savedYaw;
    private float apexx$savedPitch;
    private boolean apexx$sandwichActive;
    private float apexx$movementSavedYaw;
    private float apexx$movementSavedPitch;
    private boolean apexx$movementSandwichActive;

    /**
     * NoSlow seam: vanilla multiplies the movement input by 0.2 while an item is in use. An enabled
     * provider can turn that factor off by making this check read false.
     */
    @Redirect(method = "applyMovementSpeedFactors", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"), require = 0)
    private boolean apexx$itemUseSlowdown(ClientPlayerEntity self) {
        boolean using = self.isUsingItem();
        if (!using || interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return using;
        for (ItemUseSlowdownSuppression s : ModuleAccess.getEnabledHooks(ItemUseSlowdownSuppression.class)) {
            if (ModuleCallbackGuard.call(s, "item use slowdown", () -> s.suppressItemUseSlowdown(self), false)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private boolean apexx$wasSprintingBeforeSilent;
    @Unique
    private boolean apexx$hadPhysicalForwardMovement;
    @Unique
    private boolean apexx$wasSilentActiveLastTick;

    /** True when we forced jumpKey pressed this tick; cleared at RETURN. */
    @Unique
    private boolean apexx$jumpOverridden;

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void apexx$tickMovementHead(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        apexx$wasSprintingBeforeSilent = self.isSprinting();
        MinecraftClient mc = MinecraftClient.getInstance();
        apexx$hadPhysicalForwardMovement = (self.input != null && self.input.hasForwardMovement())
                || (mc != null && mc.options != null && mc.options.forwardKey.isPressed());

        // --- JumpInputProvider override ---
        apexx$jumpOverridden = false;
        if (mc != null && mc.options != null) {
            boolean forceJump = false;
            for (JumpInputProvider hook : ModuleAccess.getEnabledHooks(JumpInputProvider.class)) {
                boolean requested = ModuleCallbackGuard.call(hook, "jump input",
                        () -> hook.shouldJump(mc), false);
                forceJump = requested || forceJump;
            }
            if (forceJump) {
                if (!SystemInputSimulator.isSimulatedPressed(mc.options.jumpKey)) {
                    SystemInputSimulator.tapJump();
                }
                mc.options.jumpKey.setPressed(true);
                apexx$jumpOverridden = true;
            }
        }
    }

    /**
     * MovementInputModifier seam: runs once per movement tick after vanilla read the movement keys,
     * before the sprint start/stop checks and the move. Only the input record is replaced; the
     * keybindings are untouched.
     */
    @Inject(method = "tickMovement", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/input/Input;tick()V", shift = At.Shift.AFTER))
    private void apexx$afterInputTick(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (self.input == null || mc == null) {
            return;
        }
        PlayerInput original = self.input.playerInput;
        PlayerInput current = original;
        for (MovementInputModifier hook : ModuleAccess.getEnabledHooks(MovementInputModifier.class)) {
            PlayerInput before = current;
            PlayerInput result = ModuleCallbackGuard.call(hook, "movement input",
                    () -> hook.modifyMovementInput(mc, before), before);
            if (result != null) {
                current = result;
            }
        }
        if (current.equals(original)) {
            return;
        }
        self.input.playerInput = current;
        if (current.forward() != original.forward() || current.backward() != original.backward()
                || current.left() != original.left() || current.right() != original.right()) {
            // Same vector KeyboardInput.tick builds from the keys.
            ((InputAccessor) self.input).setMovementVector(new Vec2f(
                    apexx$axis(current.left(), current.right()),
                    apexx$axis(current.forward(), current.backward())).normalize());
        }
    }

    @Unique
    private static float apexx$axis(boolean positive, boolean negative) {
        return positive == negative ? 0.0f : (positive ? 1.0f : -1.0f);
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void apexx$tickMovementReturn(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;

        // --- Model B: Body-Relative ("Freecam Camera / Real Body Aim") ---
        // The player's body naturally faces finalYaw and sprints forward (movementForward = 1.0).
        // Movement stays within vanilla sprint physics.
        if (RotationManager.isSilentRotationActive() && apexx$wasSprintingBeforeSilent && apexx$hadPhysicalForwardMovement) {
            ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
            if (!self.isSprinting() && !self.isSneaking() && self.getHungerManager().getFoodLevel() > 6) {
                self.setSprinting(true);
            }
        }

        // --- Restore physical jump key state ---
        if (apexx$jumpOverridden) {
            apexx$jumpOverridden = false;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null && mc.getWindow() != null) {
                InputUtil.Key boundKey = ((KeyBindingAccessor) mc.options.jumpKey).getBoundKey();
                boolean physicallyPressed = boundKey != null
                        && boundKey.getCode() >= 0
                        && (InputUtil.isKeyPressed(mc.getWindow(), boundKey.getCode())
                        || SystemInputSimulator.isSimulatedPressed(mc.options.jumpKey));
                mc.options.jumpKey.setPressed(physicallyPressed);
            }
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void apexx$sendMovementHead(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (!RotationManager.isSilentRotationActive()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.setYaw(RotationManager.getFinalYaw());
            mc.player.setPitch(RotationManager.getFinalPitch());
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void apexx$sendMovementReturn(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        RotationManager.onClientTick();
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.onMovementPacketSent();
    }
}
