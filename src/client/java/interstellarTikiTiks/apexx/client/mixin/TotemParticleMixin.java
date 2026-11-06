package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopVisualProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.particle.TotemParticle;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

/** Totem Pop Customizer: recolours totem particles after vanilla picked its random yellow/green. */
@Mixin(TotemParticle.class)
public abstract class TotemParticleMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/world/ClientWorld;DDDDDDLnet/minecraft/client/particle/SpriteProvider;)V",
            at = @At("TAIL"), require = 0)
    private void apexx$recolor(ClientWorld world, double x, double y, double z, double vx, double vy, double vz,
                               SpriteProvider sprites, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        try {
            TotemPopVisualProvider provider = ModuleAccess.getEnabledHook(TotemPopVisualProvider.class);
            if (provider == null) return;
            Color color = provider.totemParticleColor();
            if (color == null) return;
            // A small per-particle brightness spread keeps a single-colour burst from looking flat.
            float shade = 0.82f + (float) Math.random() * 0.18f;
            ((BillboardParticle) (Object) this).setColor(color.getRed() / 255f * shade,
                    color.getGreen() / 255f * shade, color.getBlue() / 255f * shade);
        } catch (Throwable ignored) {
        }
    }
}
