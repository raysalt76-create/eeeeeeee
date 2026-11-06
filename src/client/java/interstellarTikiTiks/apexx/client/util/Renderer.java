package interstellarTikiTiks.apexx.client.util;

public class Renderer {
    private static final String RENDERER_MODE_PROPERTY = "apexx.renderer";
    private static final String RENDERER_MODE_ENV = "APEXX_RENDERER";
    private static final String MODE_AUTO = "auto";
    private static final String MODE_VANILLA = "vanilla";
    private static final String MODE_NANOVG = "nanovg";

    private static IApexxRenderer instance;

    private static Boolean cachedIsAndroid = null;

    public static IApexxRenderer get() {
        if (instance == null) {
            setupRenderer();
        }
        return instance;
    }

    /**
     * Currently always selects the vanilla renderer. The {@code apexx.renderer} / {@code APEXX_RENDERER}
     * mode constants and the NanoVG creation path below are retained but not consulted here, so those
     * settings have no effect until selection is wired back in and verified per platform.
     */
    private static void setupRenderer() {
        instance = createVanillaRenderer();
    }

    static boolean isAndroidOrPojav() {
        if (cachedIsAndroid != null) {
            return cachedIsAndroid;
        }

        // MobilePlatform is the single detector (Pojav/Zalith/FCL variables, ART, storage paths, the
        // -Dapexx.platform=mobile override); the checks below are kept as an OR for anything it misses.
        if (MobilePlatform.isMobile()) {
            return (cachedIsAndroid = true);
        }

        String jvmArg = System.getProperty("apexx.force.vanilla");
        if ("true".equalsIgnoreCase(jvmArg)) {

            return (cachedIsAndroid = true);
        }

        if (System.getenv("POJAVEXEC_EGL") != null || System.getenv("POJAV_RENDERER") != null) {

            return (cachedIsAndroid = true);
        }

        String vendor = System.getProperty("java.vendor");
        if (vendor != null && vendor.toLowerCase().contains("android")) {

            return (cachedIsAndroid = true);
        }

        String vmName = System.getProperty("java.vm.name");
        if (vmName != null && vmName.toLowerCase().contains("dalvik")) {

            return (cachedIsAndroid = true);
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && (userHome.contains("/storage/emulated") || userHome.contains("/data/user")
                || userHome.contains("/data/data"))) {

            return (cachedIsAndroid = true);
        }

        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");

        boolean isLinux = os != null && os.toLowerCase().contains("linux");
        boolean isArm = arch != null && (arch.equals("aarch64") || arch.startsWith("arm"));

        if (isLinux && isArm) {

            String androidBuild = System.getProperty("android.os.Build.VERSION");
            if (androidBuild != null) {

                return (cachedIsAndroid = true);
            }

            return (cachedIsAndroid = false);
        }

        return (cachedIsAndroid = false);
    }

    public static boolean isVulkanBackendActive() {
        return RenderBackendDetector.isVulkanBackend();
    }

    public static void forceVanillaRenderer() {
        try {
            if (instance != null) {
                instance.cleanup();
            }
        } catch (Throwable ignored) {
        }

        instance = createVanillaRenderer();
    }

    public static boolean isNanoVGActive() {
        return instance instanceof NanoVGRendererImpl;
    }

    public static boolean canUseNanoVG() {
        return isNanoVGSupportedOnCurrentBackend();
    }

    private static IApexxRenderer tryCreateNanoVGRenderer() {
        try {
            NanoVGRendererImpl renderer = new NanoVGRendererImpl();
            renderer.init();
            if (!renderer.isInitialized()) {
                return null;
            }
            return renderer;
        } catch (Throwable t) {
            return null;
        }
    }

    private static IApexxRenderer createVanillaRenderer() {
        IApexxRenderer renderer = new VanillaRendererImpl();
        renderer.init();
        return renderer;
    }

    private static String resolveRendererMode() {
        String explicitMode = firstNonBlank(System.getProperty(RENDERER_MODE_PROPERTY), System.getenv(RENDERER_MODE_ENV));
        if (explicitMode == null) {
            return MODE_AUTO;
        }

        String normalized = explicitMode.trim().toLowerCase();
        if (MODE_AUTO.equals(normalized) || MODE_VANILLA.equals(normalized) || MODE_NANOVG.equals(normalized)) {
            return normalized;
        }

        logRenderer("Unknown renderer mode '" + explicitMode + "', defaulting to auto.");
        return MODE_AUTO;
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

    private static void logRenderer(String message) {

    }

    private static boolean isNanoVGSupportedOnCurrentBackend() {
        if (RenderBackendDetector.isVulkanBackend() || isAndroidOrPojav()) {
            return false;
        }

        try {
            return NanoVGRendererImpl.isSupportedOnCurrentBackend();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
