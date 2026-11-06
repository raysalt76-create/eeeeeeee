package interstellarTikiTiks.apexx.client.util;

import net.fabricmc.loader.api.FabricLoader;

public final class RenderBackendDetector {
    private static final String BACKEND_PROPERTY = "apexx.render.backend";
    private static final String BACKEND_ENV = "APEXX_RENDER_BACKEND";

    private static Boolean cachedVulkanBackend;

    private RenderBackendDetector() {
    }

    public static boolean isVulkanBackend() {
        if (cachedVulkanBackend != null) {
            return cachedVulkanBackend;
        }

        String explicitBackend = firstNonBlank(System.getProperty(BACKEND_PROPERTY), System.getenv(BACKEND_ENV));
        if (explicitBackend != null) {
            cachedVulkanBackend = "vulkan".equalsIgnoreCase(explicitBackend.trim());
            return cachedVulkanBackend;
        }

        if ("true".equalsIgnoreCase(System.getProperty("apexx.force.vulkan")) ||
                "true".equalsIgnoreCase(System.getenv("APEXX_FORCE_VULKAN"))) {
            cachedVulkanBackend = true;
            return true;
        }

        try {
            cachedVulkanBackend = FabricLoader.getInstance().isModLoaded("vulkanmod")
                    || isVulkanModClassPresent();
        } catch (Throwable ignored) {
            cachedVulkanBackend = isVulkanModClassPresent();
        }

        return cachedVulkanBackend;
    }

    static void resetCacheForTests() {
        cachedVulkanBackend = null;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static boolean isVulkanModClassPresent() {
        String[] candidates = new String[] {
                "net.vulkanmod.vulkan.VRenderSystem",
                "net.vulkanmod.config.Config",
                "net.vulkanmod.vulkan.Vulkan"
        };
        for (String name : candidates) {
            try {
                Class.forName(name, false, RenderBackendDetector.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
