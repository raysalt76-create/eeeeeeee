package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.AmbienceSkyProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

@Mixin(SkyRendering.class)
public class SkyColorMixin {

    @Inject(
            method = "renderCelestialBodies",
            at = @At("HEAD")
    )
    private void apexx$renderAurora(MatrixStack matrices, float sunAngle, float moonAngle, float starAngle,
                                    net.minecraft.world.MoonPhase moonPhase, float alpha, float starBrightness,
                                    CallbackInfo ci) {
        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience != null) {
            ambience.renderAurora(matrices);
        }
    }

    @Inject(
            method = "updateRenderState(Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/state/SkyRenderState;)V",
            at = @At("RETURN")
    )
    private void apexx$overrideSkyColor(ClientWorld world, float tickDelta, Camera camera,
                                          SkyRenderState state, CallbackInfo ci) {
        

        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience == null || !ambience.isSkyOverrideEnabled()) return;
        Color c = ambience.getSkyOverrideColor();
        if (c == null) return;

        int packed = (0xFF << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
        state.skyColor = packed;

        state.sunriseAndSunsetColor = 0;
    }
}
