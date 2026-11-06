package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to EntityRenderManager to synchronize third-person rendering state
 * with silent rotations, providing buttery-smooth 3rd person and replay animations.
 */
@Mixin(EntityRenderManager.class)
public abstract class DispatchRendererMixin {

    @Unique
    private float apexx$savedYaw;
    @Unique
    private float apexx$savedPitch;
    @Unique
    private float apexx$savedPrevYaw;
    @Unique
    private float apexx$savedPrevPitch;
    @Unique
    private float apexx$savedHeadYaw;
    @Unique
    private float apexx$savedPrevHeadYaw;
    @Unique
    private float apexx$savedBodyYaw;
    @Unique
    private float apexx$savedPrevBodyYaw;
    @Unique
    private boolean apexx$swapped = false;

    @Inject(method = "getAndUpdateRenderState", at = @At("HEAD"))
    private void apexx$onGetAndUpdateRenderStateHead(Entity entity, float tickProgress, CallbackInfoReturnable<?> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity != mc.player || !(entity instanceof ClientPlayerEntity clientPlayer)) {
            return;
        }

        if (!RotationManager.isSilentRotationActive()) {
            return;
        }

        this.apexx$savedYaw = clientPlayer.getYaw();
        this.apexx$savedPitch = clientPlayer.getPitch();
        this.apexx$savedPrevYaw = clientPlayer.lastRenderYaw;
        this.apexx$savedPrevPitch = clientPlayer.lastRenderPitch;
        this.apexx$savedHeadYaw = clientPlayer.headYaw;
        this.apexx$savedPrevHeadYaw = ((LivingEntityAccessor) clientPlayer).apexx$getPrevHeadYaw();
        this.apexx$savedBodyYaw = clientPlayer.bodyYaw;
        this.apexx$savedPrevBodyYaw = ((LivingEntityAccessor) clientPlayer).apexx$getPrevBodyYaw();

        float targetYaw = RotationManager.getFinalYaw();
        float prevTargetYaw = RotationManager.getPrevFinalYaw();
        float targetPitch = RotationManager.getFinalPitch();
        float prevTargetPitch = RotationManager.getPrevFinalPitch();

        float lerpedYaw = MathHelper.lerpAngleDegrees(tickProgress, prevTargetYaw, targetYaw);
        float lerpedPitch = MathHelper.lerp(tickProgress, prevTargetPitch, targetPitch);

        clientPlayer.setYaw(lerpedYaw);
        clientPlayer.setPitch(lerpedPitch);
        clientPlayer.lastRenderYaw = lerpedYaw;
        clientPlayer.lastRenderPitch = lerpedPitch;
        clientPlayer.headYaw = lerpedYaw;
        ((LivingEntityAccessor) clientPlayer).apexx$setPrevHeadYaw(lerpedYaw);

        float diff = MathHelper.wrapDegrees(lerpedYaw - clientPlayer.bodyYaw);
        if (Math.abs(diff) > 50.0f) {
            float clampedBody = lerpedYaw - Math.copySign(50.0f, diff);
            clientPlayer.bodyYaw = clampedBody;
            ((LivingEntityAccessor) clientPlayer).apexx$setPrevBodyYaw(clampedBody);
        }

        this.apexx$swapped = true;
    }

    @Inject(method = "getAndUpdateRenderState", at = @At("RETURN"))
    private void apexx$onGetAndUpdateRenderStateReturn(Entity entity, float tickProgress, CallbackInfoReturnable<?> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity != mc.player || !(entity instanceof ClientPlayerEntity clientPlayer)) {
            return;
        }

        if (this.apexx$swapped) {
            clientPlayer.setYaw(this.apexx$savedYaw);
            clientPlayer.setPitch(this.apexx$savedPitch);
            clientPlayer.lastRenderYaw = this.apexx$savedPrevYaw;
            clientPlayer.lastRenderPitch = this.apexx$savedPrevPitch;
            clientPlayer.headYaw = this.apexx$savedHeadYaw;
            ((LivingEntityAccessor) clientPlayer).apexx$setPrevHeadYaw(this.apexx$savedPrevHeadYaw);
            clientPlayer.bodyYaw = this.apexx$savedBodyYaw;
            ((LivingEntityAccessor) clientPlayer).apexx$setPrevBodyYaw(this.apexx$savedPrevBodyYaw);
            this.apexx$swapped = false;
        }
    }
}
