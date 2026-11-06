package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.render.SafePipelines;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After vanilla has applied shader sources and precompiled its own pipelines (startup and every resource
 * reload), precompile Apexx's pipelines one by one. Apexx pipelines are not passed to
 * RenderPipelines.register, so a driver rejecting one of them can never abort the reload; see
 * {@link SafePipelines}. require = 0: without it pipelines still compile lazily on first use.
 */
@Mixin(ShaderLoader.class)
public class ShaderLoaderPrecompileMixin {

    @Inject(method = "apply(Lnet/minecraft/client/gl/ShaderLoader$Definitions;Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;)V",
            at = @At("RETURN"), require = 0)
    private void apexx$precompilePipelines(ShaderLoader.Definitions definitions, ResourceManager manager,
                                           Profiler profiler, CallbackInfo ci) {
        try {
            SafePipelines.precompileAll();
        } catch (RuntimeException | LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger("ApexxShaders").warn("Apexx pipeline precompile failed", failure);
        }
    }
}
