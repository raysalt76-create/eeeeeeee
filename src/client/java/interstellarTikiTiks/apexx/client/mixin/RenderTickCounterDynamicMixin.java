package interstellarTikiTiks.apexx.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import interstellarTikiTiks.apexx.client.module.hooks.TimerSpeedProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTickCounter.Dynamic.class)
public abstract class RenderTickCounterDynamicMixin {

    @ModifyExpressionValue(
        method = "beginRenderTick(J)I",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F", remap = false)
    )
    private float apexx$modifyTimerSpeed(float tickTime) {
        for (TimerSpeedProvider provider : ModuleAccess.getEnabledHooks(TimerSpeedProvider.class)) {
            float speed = ModuleCallbackGuard.call(provider, "timer speed",
                    provider::getTimerSpeed, 1.0f);
            if (Float.isFinite(speed) && speed > 0.0f && Math.abs(speed - 1.0f) >= 0.001f) {
                // Providers are ordered by module registration; the first active override wins.
                return tickTime / speed;
            }
        }
        return tickTime;
    }
}
