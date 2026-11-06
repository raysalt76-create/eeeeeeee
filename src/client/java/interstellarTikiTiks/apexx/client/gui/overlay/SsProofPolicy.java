package interstellarTikiTiks.apexx.client.gui.overlay;

import java.util.Locale;

/**
 * Pure (Minecraft-free) decision logic for screenshare-proof mode, so the fail-closed rules can be unit tested.
 *
 * <p>Invariant: while SS Proof is enabled, Apexx UI is only ever presented through the capture-excluded overlay
 * and only once that overlay has proven its exclusion ({@link State#READY}). In every other state the UI is
 * rendered into an offscreen sink that is never presented (never into the game framebuffer).
 */
public final class SsProofPolicy {
    private SsProofPolicy() {}

    /** How long the overlay may stay unverified before the user is told it is not coming up. */
    public static final long PENDING_TIMEOUT_MS = 8_000L;

    /** Consecutive failed exclusion verifications after which the opt-in "SS Proof Fail Open" setting disables SS Proof. */
    public static final int FAIL_OPEN_THRESHOLD = 3;

    /**
     * Opt-in recovery decision. Default behaviour (flag off) is fail-closed forever: the UI stays hidden and
     * SS Proof stays on. Only with the flag on does a streak of failed verifications turn SS Proof off.
     */
    public static boolean shouldFailOpen(boolean failOpenEnabled, int consecutiveFailures) {
        return failOpenEnabled && consecutiveFailures >= FAIL_OPEN_THRESHOLD;
    }

    /** Random neutral overlay window title (per launch), so the window is not enumerable by a fixed name. */
    public static String randomWindowTitle(java.util.Random rnd) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static String failOpenNoticeText() {
        return "Capture protection was turned off: exclusion could not be verified.";
    }

    public enum State {
        /** SS Proof disabled: Apexx UI draws normally. */
        OFF,
        /** Overlay alive and its capture exclusion verified by the overlay process itself: UI is presented. */
        READY,
        /** Overlay starting / restarting: UI is held back (drawn to the discard sink). */
        PENDING,
        /** Overlay can never be trusted on this system: UI stays hidden. */
        BLOCKED
    }

    public enum Reason {
        NONE,
        UNSUPPORTED_PLATFORM,
        AFFINITY_FAILED,
        START_TIMEOUT
    }

    /** SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) is Windows only; other platforms cannot be verified. */
    public static boolean isSupportedPlatform(String osName) {
        if (osName == null) return false;
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Overlay window-affinity verification state (self-contained; mirrors the old shared-memory codes). */
    public static final int AFFINITY_UNKNOWN = 0;
    public static final int AFFINITY_VERIFIED = 1;
    public static final int AFFINITY_FAILED = 2;

    public static State resolve(boolean enabled, boolean supportedPlatform, boolean processAlive, int affinityState) {
        if (!enabled) return State.OFF;
        if (!supportedPlatform) return State.BLOCKED;
        if (affinityState == AFFINITY_FAILED) return State.BLOCKED;
        if (processAlive && affinityState == AFFINITY_VERIFIED) return State.READY;
        return State.PENDING;
    }

    /** Why the UI is being held back, for the one-time notice. NONE when no notice is warranted. */
    public static Reason reason(State state, boolean supportedPlatform, int affinityState,
                                long pendingSinceMs, long nowMs) {
        if (state == State.BLOCKED) {
            return supportedPlatform ? Reason.AFFINITY_FAILED : Reason.UNSUPPORTED_PLATFORM;
        }
        if (state == State.PENDING && pendingSinceMs > 0L && nowMs - pendingSinceMs >= PENDING_TIMEOUT_MS) {
            return Reason.START_TIMEOUT;
        }
        return Reason.NONE;
    }

    /** True when Apexx drawing must not reach the game framebuffer (everything except OFF). */
    public static boolean routesToSink(State state) {
        return state != State.OFF;
    }

    /** True only when the overlay may be shown / fed frames. */
    public static boolean mayPresent(State state) {
        return state == State.READY;
    }

    /**
     * Whether a class belongs to Apexx's own screen set (ClickGUI, HUD editor, ...), by package prefix.
     * {@code overlayPackage} is this package; its parent (".gui") is the screen root.
     */
    public static boolean isApexxScreenClass(String className, String overlayPackage) {
        if (className == null || overlayPackage == null) return false;
        int cut = overlayPackage.lastIndexOf('.');
        if (cut <= 0) return false;
        return className.startsWith(overlayPackage.substring(0, cut + 1));
    }

    public static String noticeText(Reason reason) {
        if (reason == Reason.UNSUPPORTED_PLATFORM) {
            return "Screenshare Proof only works on Windows; it stays off on this system.";
        }
        if (reason == Reason.AFFINITY_FAILED) {
            return "Capture protection could not be verified; interface hidden.";
        }
        if (reason == Reason.START_TIMEOUT) {
            return "Capture protection is not starting; interface hidden.";
        }
        return "";
    }
}
