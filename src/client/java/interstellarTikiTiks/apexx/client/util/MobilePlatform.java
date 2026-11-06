package interstellarTikiTiks.apexx.client.util;

import java.io.File;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Detects whether the client is running under an Android launcher (PojavLauncher, Zalith, FCL and
 * similar) instead of a desktop JVM. Decided once per launch.
 *
 * <p>This class only touches the JDK so the pre-launch gate can call it before Minecraft's classes
 * exist. {@code -Dapexx.platform=mobile} or {@code =desktop} overrides detection; a forced
 * {@code mobile} is meant for testing the touch UI on a desktop and does not change the device
 * identity (see {@link #isAndroidRuntime()}).
 */
public final class MobilePlatform {
    public static final String OVERRIDE_PROPERTY = "apexx.platform";

    /** Result of one detection pass. {@code forced} is true when the override property decided it. */
    public record Decision(boolean mobile, boolean forced, String reason) {}

    private static volatile Decision decision;

    private MobilePlatform() {}

    public static boolean isMobile() {
        return decision().mobile();
    }

    /** Android launcher heuristics for device identity; UI overrides do not satisfy this check. */
    public static boolean isAndroidRuntime() {
        Decision d = decision();
        return d.mobile() && !d.forced();
    }

    /** Edition gate: launcher environment and JVM properties alone cannot authorize mobile builds. */
    public static boolean isAndroidSystemRuntime() {
        return isAndroidSystemRuntime(MobilePlatform::property, MobilePlatform::fileExists);
    }

    static boolean isAndroidSystemRuntime(Function<String, String> properties, Predicate<String> fileExists) {
        return lower(properties.apply("os.name")).contains("linux")
                && fileExists.test("/system/build.prop")
                && (fileExists.test("/system/bin/linker") || fileExists.test("/system/bin/linker64")
                    || fileExists.test("/apex/com.android.runtime/bin/linker")
                    || fileExists.test("/apex/com.android.runtime/bin/linker64"));
    }

    public static String reason() {
        return decision().reason();
    }

    private static Decision decision() {
        Decision current = decision;
        if (current == null) {
            current = detect(MobilePlatform::property, MobilePlatform::environment, MobilePlatform::fileExists);
            decision = current;
        }
        return current;
    }

    /** Pure detection over injected lookups so every branch can be tested without a real device. */
    public static Decision detect(Function<String, String> properties, Function<String, String> environment,
            Predicate<String> fileExists) {
        String override = lower(properties.apply(OVERRIDE_PROPERTY)).trim();
        if (override.equals("mobile")) return new Decision(true, true, "forced by -D" + OVERRIDE_PROPERTY + "=mobile");
        if (override.equals("desktop")) return new Decision(false, true, "forced by -D" + OVERRIDE_PROPERTY + "=desktop");

        if ("true".equalsIgnoreCase(properties.apply("apexx.force.vanilla"))) return mobile("apexx.force.vanilla property");
        for (String name : new String[] { "POJAVEXEC_EGL", "POJAV_RENDERER", "POJAV_NATIVEDIR", "ZALITH_RENDERER",
                "FCL_NATIVEDIR" }) {
            String value = environment.apply(name);
            if (value != null && !value.isBlank()) return mobile(name + " environment variable");
        }

        String vendor = lower(properties.apply("java.vendor") + " " + properties.apply("java.vm.vendor")
                + " " + properties.apply("java.runtime.name"));
        if (vendor.contains("android")) return mobile("Android JVM vendor");

        String vmName = lower(properties.apply("java.vm.name"));
        if (vmName.contains("dalvik") || vmName.equals("art") || vmName.startsWith("art ")) {
            return mobile("Android virtual machine");
        }

        String homes = lower(properties.apply("user.home") + " " + properties.apply("java.home")
                + " " + environment.apply("HOME"));
        if (homes.contains("/storage/emulated") || homes.contains("/data/user/") || homes.contains("/data/data/")
                || homes.contains("/system/framework")) {
            return mobile("Android storage path");
        }

        String os = lower(properties.apply("os.name"));
        String arch = lower(properties.apply("os.arch"));
        boolean armLinux = os.contains("linux") && (arch.equals("aarch64") || arch.startsWith("arm"));
        boolean androidMarker = properties.apply("android.os.Build.VERSION") != null
                || environment.apply("ANDROID_ROOT") != null || environment.apply("ANDROID_DATA") != null
                || fileExists.test("/system/build.prop");
        if (armLinux && androidMarker) return mobile("Linux ARM with Android markers");

        return new Decision(false, false, "desktop runtime");
    }

    private static Decision mobile(String reason) {
        return new Decision(true, false, reason);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String property(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException denied) {
            return null;
        }
    }

    private static String environment(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException denied) {
            return null;
        }
    }

    private static boolean fileExists(String path) {
        try {
            return new File(path).exists();
        } catch (SecurityException denied) {
            return false;
        }
    }
}
