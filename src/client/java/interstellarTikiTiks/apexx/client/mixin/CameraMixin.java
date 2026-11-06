package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.FreecamProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * CameraMixin - Decoupled silent camera architecture.
 * Redirects the camera's first-person orientation to visual angles while
 * allowing the player entity (and head) to freely rotate toward aim targets.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    private boolean thirdPerson;

    /**
     * Freecam seam: after vanilla placed the camera, a detached camera is moved to the Freecam pose
     * and marked third person so the player's own model is drawn where the player stands.
     */
    @Inject(method = "update", at = @At("RETURN"))
    private void apexx$freecamPosition(World world, Entity focused, boolean thirdPersonView, boolean inverted,
                                       float tickDelta, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        FreecamProvider freecam = ModuleAccess.getEnabledHook(FreecamProvider.class);
        if (freecam == null) return;
        Vec3d position = freecam.getFreecamPosition(tickDelta);
        if (position == null) return;
        float[] rotation = freecam.getFreecamRotation(tickDelta);
        if (rotation != null) setRotation(rotation[0], rotation[1]);
        setPos(position);
        thirdPerson = true;
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw(F)F")
    )
    private float apexx$redirectCameraYaw(Entity entity, float tickDelta) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) {
            return entity.getYaw(tickDelta);
        }
        if (RotationManager.isSilentCameraActive(entity)) {
            return RotationManager.getVisualYaw();
        }
        float visible = RotationManager.visibleCameraYaw(entity, tickDelta);
        return Float.isNaN(visible) ? entity.getYaw(tickDelta) : visible;
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getPitch(F)F")
    )
    private float apexx$redirectCameraPitch(Entity entity, float tickDelta) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) {
            return entity.getPitch(tickDelta);
        }
        if (RotationManager.isSilentCameraActive(entity)) {
            return RotationManager.getVisualPitch();
        }
        float visible = RotationManager.visibleCameraPitch(entity, tickDelta);
        return Float.isNaN(visible) ? entity.getPitch(tickDelta) : visible;
    }
}
