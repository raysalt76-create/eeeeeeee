package interstellarTikiTiks.apexx.client.util;

/**
 * The one place that says what differs between a desktop launch and an Android launch.
 *
 * <p>Both editions ship the same classes. Which behaviour runs is decided here, once per launch, from
 * {@link MobilePlatform}: callers ask for a capability by name instead of testing "is mobile" themselves, so
 * the desktop path stays the default and the Android preset is a short, reviewable list. Only the JDK is touched,
 * so it is safe to call before Minecraft's classes exist.
 */
public final class PlatformPreset {
    private PlatformPreset() {}

    /** Touch menu and plain (non-GPU-UI) sign-in/loading screens instead of the desktop GPU UI. */
    public static boolean touchUi() {
        return MobilePlatform.isMobile();
    }

    /** Offscreen render targets (backdrop blur, glass) are only trusted on a desktop GL stack. */
    public static boolean offscreenEffects() {
        return !MobilePlatform.isMobile();
    }

    /** Rounded clip masks need an offscreen target; mobile falls back to rectangular scissors. */
    public static boolean roundedClipMasks() {
        return !MobilePlatform.isMobile();
    }

    /** MSDF atlases need java.awt fonts and the msdfgen native, which an Android JRE does not reliably have. */
    public static boolean msdfFonts() {
        return !MobilePlatform.isMobile();
    }

    /** The screen-share-proof overlay is a separate OS window process; an Android launcher has none. */
    public static boolean overlayWindow() {
        return !MobilePlatform.isMobile();
    }

    /** Short description for logs and diagnostics. */
    public static String describe() {
        return MobilePlatform.isMobile() ? "mobile preset (" + MobilePlatform.reason() + ")" : "desktop preset";
    }
}
