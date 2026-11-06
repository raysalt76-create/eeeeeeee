package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.AmbienceSkyProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.Color;
import java.nio.ByteBuffer;

@Mixin(FogRenderer.class)
public class BackgroundRendererMixin {

    @ModifyArg(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"
            ),
            index = 2
    )
    private Vector4f apexx$modifyFogColor(Vector4f original) {
        

        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience == null) return original;
        Color c = ambience.getFogOverrideColor();
        if (c == null) return original;
        return new Vector4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1.0f);
    }

    @Inject(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void apexx$overrideClearColor(Camera camera, int renderDistance,
                                             RenderTickCounter counter, float viewDistance,
                                             ClientWorld world,
                                             CallbackInfoReturnable<Vector4f> cir) {
        

        AmbienceSkyProvider ambience = ModuleAccess.getEnabledHook(AmbienceSkyProvider.class);
        if (ambience == null) return;
        Color c = ambience.getFogOverrideColor();
        if (c == null) return;
        cir.setReturnValue(new Vector4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1.0f));
    }
}
