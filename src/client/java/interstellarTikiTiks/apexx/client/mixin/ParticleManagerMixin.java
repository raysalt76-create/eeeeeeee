package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.NoRenderProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoRender particle filter. This is a hot path (every particle spawn), so it avoids the callback
 * guard's lock; a plain try/catch keeps a faulty provider from breaking vanilla particles.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    // Vanilla callers already handle a null result: createParticle returns null for unregistered types.
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$filterEffect(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz,
                                    CallbackInfoReturnable<Particle> cir) {
        if (effect == null || Apexx.isTornDown()) return;
        try {
            for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
                if (provider.blockParticle(effect)) {
                    cir.setReturnValue(null);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$filterParticle(Particle particle, CallbackInfo ci) {
        if (particle == null || Apexx.isTornDown()) return;
        try {
            for (NoRenderProvider provider : ModuleAccess.getEnabledHooks(NoRenderProvider.class)) {
                if (provider.blockParticle(particle)) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
