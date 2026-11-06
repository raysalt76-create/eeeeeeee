package interstellarTikiTiks.apexx.client.resource;

import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class ApexxResourceManagerWrapper implements ResourceManager {
    private final ResourceManager delegate;

    private ApexxResourceManagerWrapper(ResourceManager delegate) {
        this.delegate = delegate;
    }

    public static ResourceManager wrap(ResourceManager original) {
        if (original instanceof ApexxResourceManagerWrapper) {
            return original;
        }
        return new ApexxResourceManagerWrapper(original);
    }

    @Override
    public Optional<Resource> getResource(Identifier id) {
        Optional<Resource> runtime = RuntimeResourceBridge.interceptGetResource(id);
        if (runtime != null) {
            return runtime;
        }
        return delegate.getResource(id);
    }

    @Override
    public Map<Identifier, Resource> findResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
        Map<Identifier, Resource> delegateResult = delegate.findResources(startingPath, allowedPathPredicate);
        Map<Identifier, Resource> runtimeResult = RuntimeResourceBridge.interceptFindResources(startingPath, allowedPathPredicate);
        if (runtimeResult == null || runtimeResult.isEmpty()) {
            return delegateResult;
        }
        Map<Identifier, Resource> merged = new LinkedHashMap<>(delegateResult);
        merged.putAll(runtimeResult);
        return merged;
    }

    @Override
    public Map<Identifier, List<Resource>> findAllResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
        Map<Identifier, List<Resource>> delegateResult = delegate.findAllResources(startingPath, allowedPathPredicate);
        Map<Identifier, List<Resource>> runtimeResult = RuntimeResourceBridge.interceptFindAllResources(startingPath, allowedPathPredicate);
        if (runtimeResult == null || runtimeResult.isEmpty()) {
            return delegateResult;
        }
        Map<Identifier, List<Resource>> merged = new LinkedHashMap<>(delegateResult);
        merged.putAll(runtimeResult);
        return merged;
    }

    @Override
    public Set<String> getAllNamespaces() {
        return RuntimeResourceBridge.interceptNamespaces(new LinkedHashSet<>(delegate.getAllNamespaces()));
    }

    @Override
    public List<Resource> getAllResources(Identifier id) {
        return delegate.getAllResources(id);
    }

    @Override
    public Stream<ResourcePack> streamResourcePacks() {
        return delegate.streamResourcePacks();
    }
}
