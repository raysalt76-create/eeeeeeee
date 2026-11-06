package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.NoRenderProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoRender weather: skips the rain/snow sheets and the rain splash particles and sounds. */
@Mixin(WeatherRendering.class)
public abstract class WeatherRenderingMixin {

    @Inject(method = "renderPrecipitation(Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/client/render/state/WeatherRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$hidePrecipitation(VertexConsumerProvider consumers, Vec3d cameraPos, WeatherRenderState state,
                                         CallbackInfo ci) {
        if (apexx$hideWeather()) ci.cancel();
    }

    @Inject(method = "addParticlesAndSound(Lnet/minecraft/client/world/ClientWorld;Lnet/minecraft/client/render/Camera;ILnet/minecraft/particle/ParticlesMode;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$hideRainSplashes(ClientWorld world, Camera camera, int ticks, ParticlesMode mode, int range,
                                        CallbackInfo ci) {
        if (apexx$hideWeather()) ci.cancel();
    }

    private static boolean apexx$hideWeather() {
        if (Apexx.isTornDown()) return false;
        for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "norender weather", provider::hideWeather, false)) return true;
        }
        return false;
    }
}
