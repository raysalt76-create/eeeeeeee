package interstellarTikiTiks.apexx.client.resource;

import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class RuntimeResourceBridge {
    private static final String NAMESPACE = "dynamic_fps";

    private RuntimeResourceBridge() {
    }

    public static Optional<Resource> interceptGetResource(Identifier id) {
        if (id == null || !NAMESPACE.equals(id.getNamespace())) {
            return null;
        }

        byte[] data = RuntimeResourceLoader.getResource(resolveAssetPath(id));
        if (data == null) {
            return null;
        }

        Resource resource = RuntimeMemoryResource.create(data);
        return resource == null ? null : Optional.of(resource);
    }

    public static Map<Identifier, Resource> interceptFindResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
        if (!RuntimeResourceLoader.ensureInitialized()) {
            return null;
        }

        Map<Identifier, Resource> resources = new LinkedHashMap<>();
        for (String entry : RuntimeResourceLoader.getResourcePaths()) {
            Identifier id = toFindResourceId(entry, startingPath);
            if (id == null) {
                continue;
            }

            if (allowedPathPredicate != null && !allowedPathPredicate.test(id)) {
                continue;
            }

            byte[] data = RuntimeResourceLoader.getResource(entry);
            if (data == null) {
                continue;
            }

            Resource resource = RuntimeMemoryResource.create(data);
            if (resource != null) {
                resources.put(id, resource);
            }
        }

        return resources.isEmpty() ? null : resources;
    }

    public static Map<Identifier, List<Resource>> interceptFindAllResources(String startingPath, Predicate<Identifier> allowedPathPredicate) {
        Map<Identifier, Resource> single = interceptFindResources(startingPath, allowedPathPredicate);
        if (single == null) {
            return null;
        }

        Map<Identifier, List<Resource>> resources = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : single.entrySet()) {
            resources.put(entry.getKey(), List.of(entry.getValue()));
        }
        return resources;
    }

    public static Set<String> interceptNamespaces(Set<String> namespaces) {
        if (!RuntimeResourceLoader.ensureInitialized()) {
            return namespaces;
        }

        if (RuntimeResourceLoader.hasNamespace(NAMESPACE)) {
            namespaces.add(NAMESPACE);
        }
        return namespaces;
    }

    private static String resolveAssetPath(Identifier id) {
        String directPath = "assets/" + id.getNamespace() + "/" + id.getPath();
        if (RuntimeResourceLoader.hasResource(directPath)) {
            return directPath;
        }

        if (RuntimeResourceLoader.hasResource("assets/" + id.getNamespace() + "/shaders/" + id.getPath())) {
            return "assets/" + id.getNamespace() + "/shaders/" + id.getPath();
        }

        return directPath;
    }

    private static Identifier toFindResourceId(String assetPath, String startingPath) {
        String prefix = "assets/" + NAMESPACE + "/" + startingPath + "/";
        if (!assetPath.startsWith(prefix)) {
            return null;
        }

        String relativePath = assetPath.substring(prefix.length());
        if (relativePath.isEmpty()) {
            return null;
        }

        return Identifier.of(NAMESPACE, startingPath + "/" + relativePath);
    }
}
