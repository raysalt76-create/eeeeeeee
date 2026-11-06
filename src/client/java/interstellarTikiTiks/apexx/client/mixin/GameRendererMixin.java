package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;
import interstellarTikiTiks.apexx.client.module.hooks.HandRenderHooks;
import interstellarTikiTiks.apexx.client.module.hooks.HurtCamSuppression;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @org.spongepowered.asm.mixin.gen.Invoker("getProjectionMatrix")
    protected abstract Matrix4f apexx$getWorldProjection(float tickDelta);

    @Inject(
            method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    ordinal = 0
            )
    )
    private void apexx$compositeWorldVisuals(RenderTickCounter counter, CallbackInfo ci) {
        float tickDelta = counter.getTickProgress(true);
        interstellarTikiTiks.apexx.client.render.visuals.ShaderEspEngine.captureSceneDepth();
        interstellarTikiTiks.apexx.client.render.visuals.GpuWorldVisualEngine.composite(
                apexx$getWorldProjection(tickDelta), new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()));
    }

    @Inject(
            method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("RETURN")
    )
    private void apexx$postRenderWorld(RenderTickCounter counter, CallbackInfo ci) {
        java.util.List<interstellarTikiTiks.apexx.client.module.hooks.WorldRenderEndAware> hooks =
                ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.WorldRenderEndAware.class);
        if (hooks.isEmpty()) return;
        float tickDelta = counter.getTickProgress(true);
        if (hooks.size() > 1) {
            hooks = new java.util.ArrayList<>(hooks);
            hooks.sort(java.util.Comparator.comparingInt(
                    interstellarTikiTiks.apexx.client.module.hooks.WorldRenderEndAware::worldRenderEndOrder));
        }
        for (interstellarTikiTiks.apexx.client.module.hooks.WorldRenderEndAware hook : hooks) {
            try {
                hook.onWorldRenderEnd(tickDelta);
            } catch (Throwable failure) {
                // A broken effect must never take the render thread down with it.
            }
        }
    }

    @Inject(method = "renderHand", at = @At("HEAD"), require = 0)
    private void onRenderHandHead(CallbackInfo ci) {
        HandRenderHooks handRenderHooks = ModuleAccess.getEnabledHook(HandRenderHooks.class);
        if (handRenderHooks != null) {
            handRenderHooks.prepareRender();
        }
    }

    @Inject(method = "renderHand", at = @At("RETURN"), require = 0)
    private void onRenderHandReturn(CallbackInfo ci) {
        HandRenderHooks handRenderHooks = ModuleAccess.getEnabledHook(HandRenderHooks.class);
        if (handRenderHooks != null) {
            handRenderHooks.drawRender();
        }
    }

    @Inject(
            method = "tiltViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void apexx$suppressHurtCameraTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        // Any enabled provider may suppress it (No Hurt Cam, NoRender's Hurt Tilt toggle); the first
        // provider returning false must not shadow a later one that wants it hidden.
        for (HurtCamSuppression suppression : ModuleAccess.getEnabledHooks(HurtCamSuppression.class)) {
            if (ModuleCallbackGuard.call(suppression, "hurt cam", suppression::suppressHurtCam, false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "updateCrosshairTarget(F)V", at = @At("RETURN"))
    private void apexx$updateSilentCrosshairTarget(float tickProgress, CallbackInfo ci) {
        if (!RotationManager.isSilentRotationActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        float silentYaw = RotationManager.getFinalYaw();
        float silentPitch = RotationManager.getFinalPitch();
        if (!Float.isFinite(silentYaw) || !Float.isFinite(silentPitch)) return;

        double blockInteractionRange = mc.player.getBlockInteractionRange();
        double entityInteractionRange = mc.player.getEntityInteractionRange();
        double maxRange = Math.max(blockInteractionRange, entityInteractionRange);

        Vec3d cameraPos = mc.player.getCameraPosVec(tickProgress);
        float f = silentPitch * 0.017453292F;
        float g = -silentYaw * 0.017453292F;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        Vec3d rotationVec = new Vec3d(i * j, -k, h * j);
        Vec3d endVec = cameraPos.add(rotationVec.multiply(maxRange));

        net.minecraft.util.hit.BlockHitResult blockHit = mc.world.raycast(new net.minecraft.world.RaycastContext(
                cameraPos, endVec,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                mc.player
        ));

        double blockDistSq = maxRange * maxRange;
        if (blockHit != null && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
            blockDistSq = cameraPos.squaredDistanceTo(blockHit.getPos());
        }

        net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(cameraPos, endVec).expand(1.0);
        net.minecraft.util.hit.EntityHitResult entityHit = net.minecraft.entity.projectile.ProjectileUtil.raycast(
                mc.player,
                cameraPos,
                endVec,
                searchBox,
                entity -> !entity.isSpectator() && entity.canHit(),
                blockDistSq
        );

        if (entityHit != null) {
            double entityDistSq = cameraPos.squaredDistanceTo(entityHit.getPos());
            if (entityDistSq < blockDistSq && entityDistSq <= entityInteractionRange * entityInteractionRange) {
                mc.crosshairTarget = entityHit;
                mc.targetedEntity = entityHit.getEntity();
                return;
            }
        }

        if (blockHit != null && blockDistSq <= blockInteractionRange * blockInteractionRange) {
            mc.crosshairTarget = blockHit;
        }
    }

    /**
     * Once per real frame: resolves SS Proof policy state (capture-exclusion verification, fail-open,
     * notices) and keeps the overlay window started. The actual per-flush redirect of GUI draws into
     * the overlay framebuffer happens in {@code GuiRendererMixin} and {@code InGameHudMixin}, driven
     * by {@link OverlayCompositor#beginFrame()}/{@link OverlayCompositor#guiTarget}/
     * {@link OverlayCompositor#renderAndFlushHudEarly}, since the GUI render state can flush more than
     * once per real frame.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"), require = 0)
    private void apexx$ssProofFrameTick(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        OverlayCompositor.frameTick();
    }

    /** Once per real frame, after every flush of this frame has happened: present or hide the overlay. */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"), require = 0)
    private void apexx$ssProofFrameFinish(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        OverlayCompositor.finishFrame();
    }
}
