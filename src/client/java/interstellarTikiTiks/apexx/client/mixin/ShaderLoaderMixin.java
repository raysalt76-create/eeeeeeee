package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.resource.ApexxResourceManagerWrapper;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ShaderLoader.class)
public class ShaderLoaderMixin {

    @ModifyVariable(method = "prepare", at = @At("HEAD"), argsOnly = true, index = 1)
    private ResourceManager apexx$wrapResourceManagerForPrepare(ResourceManager original) {
        return ApexxResourceManagerWrapper.wrap(original);
    }

}
