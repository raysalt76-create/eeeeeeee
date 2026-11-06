package interstellarTikiTiks.apexx.client.gui;

import interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy;
import interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.Reason;
import interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsProofPolicyTest {
    private static final int UNK = SsProofPolicy.AFFINITY_UNKNOWN;
    private static final int OK = SsProofPolicy.AFFINITY_VERIFIED;
    private static final int BAD = SsProofPolicy.AFFINITY_FAILED;

    @Test
    void disabledIsAlwaysOff() {
        assertEquals(State.OFF, SsProofPolicy.resolve(false, true, true, OK));
        assertEquals(State.OFF, SsProofPolicy.resolve(false, false, false, BAD));
        assertFalse(SsProofPolicy.routesToSink(State.OFF));
    }

    @Test
    void onlyAVerifiedLiveOverlayMayPresent() {
        assertEquals(State.READY, SsProofPolicy.resolve(true, true, true, OK));
        assertEquals(State.PENDING, SsProofPolicy.resolve(true, true, true, UNK));
        assertEquals(State.PENDING, SsProofPolicy.resolve(true, true, false, OK));
        assertEquals(State.PENDING, SsProofPolicy.resolve(true, true, false, UNK));
        assertTrue(SsProofPolicy.mayPresent(State.READY));
        assertFalse(SsProofPolicy.mayPresent(State.PENDING));
        assertFalse(SsProofPolicy.mayPresent(State.BLOCKED));
        assertFalse(SsProofPolicy.mayPresent(State.OFF));
    }

    @Test
    void failuresNeverRouteToTheGameFramebuffer() {
        assertEquals(State.BLOCKED, SsProofPolicy.resolve(true, true, true, BAD));
        assertEquals(State.BLOCKED, SsProofPolicy.resolve(true, false, true, OK));
        for (State s : new State[]{State.READY, State.PENDING, State.BLOCKED}) {
            assertTrue(SsProofPolicy.routesToSink(s), s.name());
        }
    }

    @Test
    void platformSupportIsWindowsOnly() {
        assertTrue(SsProofPolicy.isSupportedPlatform("Windows 11"));
        assertFalse(SsProofPolicy.isSupportedPlatform("Linux"));
        assertFalse(SsProofPolicy.isSupportedPlatform("Mac OS X"));
        assertFalse(SsProofPolicy.isSupportedPlatform("Darwin"));
        assertFalse(SsProofPolicy.isSupportedPlatform(null));
    }

    @Test
    void noticeReasons() {
        assertEquals(Reason.UNSUPPORTED_PLATFORM, SsProofPolicy.reason(State.BLOCKED, false, UNK, 0, 0));
        assertEquals(Reason.AFFINITY_FAILED, SsProofPolicy.reason(State.BLOCKED, true, BAD, 0, 0));
        assertEquals(Reason.NONE, SsProofPolicy.reason(State.PENDING, true, UNK, 1000, 2000));
        assertEquals(Reason.START_TIMEOUT,
                SsProofPolicy.reason(State.PENDING, true, UNK, 1000, 1000 + SsProofPolicy.PENDING_TIMEOUT_MS));
        assertEquals(Reason.NONE, SsProofPolicy.reason(State.READY, true, OK, 0, 99999));
        assertEquals(Reason.NONE, SsProofPolicy.reason(State.OFF, true, UNK, 0, 99999));
        assertFalse(SsProofPolicy.noticeText(Reason.START_TIMEOUT).isEmpty());
        assertEquals("", SsProofPolicy.noticeText(Reason.NONE));
    }

    @Test
    void apexxScreensAreRecognisedByPackage() {
        String overlayPkg = "interstellarTikiTiks.apexx.client.gui.overlay";
        assertTrue(SsProofPolicy.isApexxScreenClass(
                "interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen", overlayPkg));
        assertTrue(SsProofPolicy.isApexxScreenClass(
                "interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen", overlayPkg));
        assertFalse(SsProofPolicy.isApexxScreenClass("net.minecraft.client.gui.screen.ChatScreen", overlayPkg));
        assertFalse(SsProofPolicy.isApexxScreenClass(null, overlayPkg));
        assertFalse(SsProofPolicy.isApexxScreenClass("x", "nodots"));
    }

    @Test
    void failOpenIsOptInAndNeedsThreeFailures() {
        assertFalse(SsProofPolicy.shouldFailOpen(false, 99));
        assertFalse(SsProofPolicy.shouldFailOpen(true, SsProofPolicy.FAIL_OPEN_THRESHOLD - 1));
        assertTrue(SsProofPolicy.shouldFailOpen(true, SsProofPolicy.FAIL_OPEN_THRESHOLD));
    }

    @Test
    void windowTitleIsRandomAndNeutral() {
        String a = SsProofPolicy.randomWindowTitle(new java.util.Random(1));
        assertEquals(12, a.length());
        assertFalse(a.toLowerCase().contains("apexx"));
        assertFalse(a.equals(SsProofPolicy.randomWindowTitle(new java.util.Random(2))));
    }
}
