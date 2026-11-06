package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class MobilePlatformTest {
    private static final Predicate<String> NO_FILES = path -> false;

    @Test
    void editionGateRequiresAndroidSystemFilesEvenWithLauncherHints() {
        Map<String, String> hints = Map.of("os.name", "Linux", "apexx.platform", "mobile",
                "apexx.force.vanilla", "true", "java.vendor", "The Android Project");
        assertFalse(MobilePlatform.isAndroidSystemRuntime(hints::get, NO_FILES));
        assertFalse(MobilePlatform.isAndroidSystemRuntime(hints::get, "/system/build.prop"::equals));
        assertFalse(MobilePlatform.isAndroidSystemRuntime(hints::get, "/system/bin/linker64"::equals));
        for (String linker : new String[] { "/system/bin/linker", "/system/bin/linker64",
                "/apex/com.android.runtime/bin/linker", "/apex/com.android.runtime/bin/linker64" }) {
            Predicate<String> androidFiles = path -> path.equals("/system/build.prop") || path.equals(linker);
            assertTrue(MobilePlatform.isAndroidSystemRuntime(hints::get, androidFiles));
            assertFalse(MobilePlatform.isAndroidSystemRuntime(Map.of("os.name", "Mac OS X")::get, androidFiles));
            assertFalse(MobilePlatform.isAndroidSystemRuntime(Map.of("os.name", "Windows 11")::get, androidFiles));
        }
    }

    private static MobilePlatform.Decision detect(Map<String, String> props, Map<String, String> env, Predicate<String> files) {
        return MobilePlatform.detect(props::get, env::get, files);
    }

    @Test
    void ordinaryDesktopsAreNotMobile() {
        assertFalse(detect(Map.of("os.name", "Windows 11", "os.arch", "amd64", "user.home", "C:\\Users\\a"), Map.of(), NO_FILES).mobile());
        assertFalse(detect(Map.of("os.name", "Mac OS X", "os.arch", "aarch64", "user.home", "/Users/a"), Map.of(), NO_FILES).mobile());
        assertFalse(detect(Map.of("os.name", "Linux", "os.arch", "amd64", "user.home", "/home/a"), Map.of(), NO_FILES).mobile());
    }

    @Test
    void armLinuxAloneIsNotMobileButWithAndroidMarkersIs() {
        Map<String, String> props = Map.of("os.name", "Linux", "os.arch", "aarch64", "user.home", "/home/pi");
        assertFalse(detect(props, Map.of(), NO_FILES).mobile());
        assertTrue(detect(props, Map.of("ANDROID_ROOT", "/system"), NO_FILES).mobile());
        assertTrue(detect(props, Map.of(), "/system/build.prop"::equals).mobile());
    }

    @Test
    void launcherEnvironmentVariablesAreDetected() {
        Map<String, String> props = Map.of("os.name", "Linux", "os.arch", "aarch64");
        assertTrue(detect(props, Map.of("POJAV_RENDERER", "vulkan_zink"), NO_FILES).mobile());
        assertTrue(detect(props, Map.of("POJAVEXEC_EGL", "libEGL.so"), NO_FILES).mobile());
    }

    @Test
    void androidStoragePathsAreDetected() {
        assertTrue(detect(Map.of("user.home", "/storage/emulated/0/games/PojavLauncher"), Map.of(), NO_FILES).mobile());
        assertTrue(detect(Map.of("java.home", "/data/user/0/net.kdt.pojavlaunch/runtimes/jre-21"), Map.of(), NO_FILES).mobile());
    }

    @Test
    void androidVmIsDetected() {
        assertTrue(detect(Map.of("java.vm.name", "Dalvik"), Map.of(), NO_FILES).mobile());
        assertTrue(detect(Map.of("java.vendor", "The Android Project"), Map.of(), NO_FILES).mobile());
    }

    @Test
    void overrideWinsAndForcedMobileIsNotAnAndroidRuntime() {
        MobilePlatform.Decision forcedMobile = detect(Map.of("apexx.platform", "mobile", "os.name", "Windows 11"), Map.of(), NO_FILES);
        assertTrue(forcedMobile.mobile());
        assertTrue(forcedMobile.forced());

        MobilePlatform.Decision forcedDesktop = detect(Map.of("apexx.platform", "desktop"), Map.of("POJAV_RENDERER", "x"), NO_FILES);
        assertFalse(forcedDesktop.mobile());
        assertTrue(forcedDesktop.forced());

        MobilePlatform.Decision invalid = detect(Map.of("apexx.platform", "banana"), Map.of("POJAV_RENDERER", "x"), NO_FILES);
        assertTrue(invalid.mobile());
        assertFalse(invalid.forced());
    }
}
