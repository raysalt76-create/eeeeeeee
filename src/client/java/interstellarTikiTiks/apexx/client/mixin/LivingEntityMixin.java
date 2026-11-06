package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.HeldItemAnimationProvider;
import interstellarTikiTiks.apexx.client.module.hooks.LocalJumpListener;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Shadow protected boolean handSwinging;
    @Shadow protected int handSwingTicks;

    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true)
    private void modifySwingDuration(CallbackInfoReturnable<Integer> info) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if ((Object) this != MinecraftClient.getInstance().player) {
            return;
        }
        HeldItemAnimationProvider animationProvider = apexx$swingDurationProvider();
        if (animationProvider != null) {
            int defaultDuration = 6;
            double speed = animationProvider.getSwingSpeed();
            if (speed <= 0.1) speed = 0.1;
            int newDuration = Math.max(1, (int) Math.round(defaultDuration / speed));
            info.setReturnValue(newDuration);
        }
    }

    @Inject(method = "swingHand(Lnet/minecraft/util/Hand;Z)V", at = @At("HEAD"))
    private void allowReSwing(Hand hand, boolean fromServer, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if ((Object) this == MinecraftClient.getInstance().player && apexx$swingDurationProvider() != null) {
            this.handSwinging = false;
            this.handSwingTicks = -1;
        }
    }

    /** First enabled provider that owns the swing duration; look-only providers (ViewModel) are skipped. */
    private static HeldItemAnimationProvider apexx$swingDurationProvider() {
        for (HeldItemAnimationProvider provider : ModuleAccess.getEnabledHooks(HeldItemAnimationProvider.class)) {
            if (provider.controlsSwingDuration()) return provider;
        }
        return null;
    }

    @Inject(method = "jump()V", at = @At("TAIL"))
    private void apexx$notifyLocalJump(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || (Object) this != mc.player) return;
        var player = mc.player;
        if (player.isTouchingWater() || player.isGliding() || player.isClimbing()) return;
        Vec3d position = new Vec3d(player.getX(), player.getY(), player.getZ());
        for (LocalJumpListener listener : ModuleAccess.getEnabledHooks(LocalJumpListener.class)) {
            ModuleCallbackGuard.call(listener, "local jump visual", () -> {
                listener.onLocalJump(position);
                return true;
            }, false);
        }
    }
}
