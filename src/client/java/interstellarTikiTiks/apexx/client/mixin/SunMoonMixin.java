package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.AmbienceSkyProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.SkyRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRendering.class)
public class SunMoonMixin {

    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
    private void apexx$hideSun(CallbackInfo ci) {
        

        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience != null && ambience.isMoonSunHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
    private void apexx$hideMoon(CallbackInfo ci) {
        

        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience != null && ambience.isMoonSunHidden()) {
            ci.cancel();
        }
    }
}
