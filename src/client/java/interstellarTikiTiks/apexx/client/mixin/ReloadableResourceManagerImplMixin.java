package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.resource.RuntimeResourceBridge;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@Mixin(ReloadableResourceManagerImpl.class)
public abstract class ReloadableResourceManagerImplMixin {
    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void apexx$interceptGetResource(Identifier id, CallbackInfoReturnable<Optional<Resource>> cir) {
        Optional<Resource> resource = RuntimeResourceBridge.interceptGetResource(id);
        if (resource != null) {
            cir.setReturnValue(resource);
        }
    }

    @Inject(method = "findResources", at = @At("RETURN"), cancellable = true)
    private void apexx$mergeFindResources(String startingPath, Predicate<Identifier> allowedPathPredicate, CallbackInfoReturnable<Map<Identifier, Resource>> cir) {
        Map<Identifier, Resource> injected = RuntimeResourceBridge.interceptFindResources(startingPath, allowedPathPredicate);
        if (injected == null || injected.isEmpty()) {
            return;
        }

        Map<Identifier, Resource> merged = new LinkedHashMap<>(cir.getReturnValue());
        merged.putAll(injected);
        cir.setReturnValue(merged);
    }

    @Inject(method = "findAllResources", at = @At("RETURN"), cancellable = true)
    private void apexx$mergeFindAllResources(String startingPath, Predicate<Identifier> allowedPathPredicate, CallbackInfoReturnable<Map<Identifier, List<Resource>>> cir) {
        Map<Identifier, List<Resource>> injected = RuntimeResourceBridge.interceptFindAllResources(startingPath, allowedPathPredicate);
        if (injected == null || injected.isEmpty()) {
            return;
        }

        Map<Identifier, List<Resource>> merged = new LinkedHashMap<>(cir.getReturnValue());
        merged.putAll(injected);
        cir.setReturnValue(merged);
    }

    @Inject(method = "getAllNamespaces", at = @At("RETURN"), cancellable = true)
    private void apexx$mergeNamespaces(CallbackInfoReturnable<Set<String>> cir) {
        Set<String> namespaces = new java.util.LinkedHashSet<>(cir.getReturnValue());
        cir.setReturnValue(RuntimeResourceBridge.interceptNamespaces(namespaces));
    }
}
