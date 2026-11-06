package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Scale floors, breakpoints and fling maths of the responsive menus, checked for the screens that matter. */
class ResponsiveLayoutTest {

    private static float bodyPx(float fbHeight) {
        return ResponsiveLayout.BODY_TEXT_UNITS * ResponsiveLayout.classicAutoScale(fbHeight, 1f);
    }

    /** Room (design units) the classic menu has across a framebuffer {@code fbW} x {@code fbH} wide, as buildFrame computes it. */
    private static float availUnits(float fbW, float fbH) {
        float base = ResponsiveLayout.classicAutoScale(fbH, 1f);
        float margin = Math.round(14f * base);
        return ResponsiveLayout.availableUnits(fbW - margin * 2f, base);
    }

    @Test
    void classicBodyTextIsReadableOnEveryDesktopHeight() {
        // 720p, 1080p, 1440p, 4K: the layout is in framebuffer pixels, so the Minecraft GUI scale (1-4) is irrelevant.
        for (float h : new float[] {600f, 720f, 900f, 1080f, 1440f, 2160f}) {
            assertTrue(bodyPx(h) >= ResponsiveLayout.MIN_BODY_TEXT_PX - 0.01f, "body text at " + h);
        }
        assertEquals(12f, bodyPx(720f), 0.01f);       // raised to the floor
        assertEquals(14.85f, bodyPx(1080f), 0.01f);   // unchanged
        assertEquals(19.8f, bodyPx(1440f), 0.01f);
        assertEquals(29.7f, bodyPx(2160f), 0.01f);
    }

    @Test
    void monitorCorrectionStacksButNeverBelowTheFloor() {
        // A 32" 4K monitor corrects down to 0.85; body text stays well over the floor.
        assertTrue(11f * ResponsiveLayout.classicAutoScale(2160f, 0.85f) > 25f);
        // The floor wins over an aggressive correction on a short screen.
        assertEquals(12f, 11f * ResponsiveLayout.classicAutoScale(600f, 0.85f), 0.01f);
    }

    @Test
    void railCollapsesOnNarrowScreensOnly() {
        assertFalse(ResponsiveLayout.compactRail(availUnits(1920f, 1080f)));   // 1393 units
        assertFalse(ResponsiveLayout.compactRail(availUnits(3440f, 1440f)));   // 21:9
        assertFalse(ResponsiveLayout.compactRail(availUnits(1280f, 720f)));    // 1147 units
        assertTrue(ResponsiveLayout.compactRail(availUnits(960f, 1080f)));     // half-screen: ~683 units
        assertTrue(ResponsiveLayout.compactRail(availUnits(1080f, 2400f)));    // phone portrait on the desktop menu
        assertTrue(ResponsiveLayout.compactRail(availUnits(800f, 600f)));
    }

    @Test
    void sidePanelsDockOnlyWhenTheyFit() {
        float inspector = 14f + 460f;
        assertTrue(ResponsiveLayout.dockSidePanel(availUnits(1920f, 1080f), 600f, inspector));
        assertTrue(ResponsiveLayout.dockSidePanel(availUnits(1280f, 720f), 600f, inspector));
        assertFalse(ResponsiveLayout.dockSidePanel(availUnits(1024f, 768f), 600f, inspector));
        assertFalse(ResponsiveLayout.dockSidePanel(availUnits(960f, 1080f), 440f, inspector));
    }

    @Test
    void orbitKeepsTextReadableWhileItFits() {
        // Fits the 1180x660 frame normally...
        assertEquals(1080f / 660f, ResponsiveLayout.orbitScale(1920f, 1080f, 1f), 0.01f);
        // ...a half-width window is lifted from 0.81 to the 12 px body floor, still inside the width cap.
        float half = ResponsiveLayout.orbitScale(960f, 1080f, 1f);
        assertEquals(12f / 13f, half, 0.01f);
        assertTrue(960f / half >= ResponsiveLayout.ORBIT_MIN_VIEW_W);
        // Never wider than the view needs.
        assertTrue(ResponsiveLayout.orbitScale(700f, 1000f, 1f) <= 700f / ResponsiveLayout.ORBIT_MIN_VIEW_W + 1e-4f);
    }

    @Test
    void mobileTargetsAreFingerSizedOnEveryPhone() {
        // 6 in phone 2400x1080 landscape, 1080x2400 portrait, a tablet and a tiny 800x480 screen.
        for (float fbHeight : new float[] {1080f, 2400f, 1600f, 480f, 720f}) {
            float unit = ResponsiveLayout.mobileUnitPx(fbHeight, 1f);
            assertTrue(unit * ResponsiveLayout.MOBILE_MIN_CONTROL_UNITS >= ResponsiveLayout.MIN_TOUCH_PX - 0.01f,
                    "control at " + fbHeight);
        }
        assertEquals(4f, ResponsiveLayout.mobileUnitPx(1080f, 1f), 1e-4f);
        assertEquals(6f, ResponsiveLayout.mobileUnitPx(2400f, 1f), 1e-4f);
        // The slider stacks on top, but the touch floor still holds at the smallest setting.
        assertTrue(ResponsiveLayout.mobileUnitPx(480f, 0.6f) * 28f >= 44f - 0.01f);
    }

    @Test
    void hudChromeLabelsAreReadableAndTouchButtonsFingerSized() {
        // 1080p at GUI scale 1, 2 and 4: viewport scale is 1.12 / 1.0ish / 0.62 (see GpuHudRenderer.viewportScale).
        for (float[] c : new float[][] {{1.12f, 1080f, 1f}, {1.0f, 1080f, 2f}, {0.62f, 1080f, 4f}, {0.62f, 2160f, 4f}}) {
            float s = ResponsiveLayout.hudChromeScale(c[0], c[1], c[2], false);
            assertTrue(ResponsiveLayout.HUD_LABEL_UNITS * s * c[2] >= ResponsiveLayout.HUD_MIN_LABEL_PX - 0.01f);
        }
        float touch = ResponsiveLayout.hudChromeScale(0.5f, 1080f, 2.5f, true);
        assertTrue(ResponsiveLayout.HUD_BUTTON_UNITS * touch * 2.5f >= ResponsiveLayout.MIN_TOUCH_PX - 0.01f);
    }

    @Test
    void flingOnlyFollowsAMovingFingerAndDecays() {
        float v = 0f;
        for (int i = 0; i < 10; i++) v = ResponsiveLayout.blendVelocity(v, 10f, 0.016f);   // ~625 px/s
        assertTrue(v > 300f && v < 700f);
        assertEquals(0f, ResponsiveLayout.flingFor(v, 500_000_000L));                      // finger had stopped
        assertEquals(v, ResponsiveLayout.flingFor(v, 20_000_000L), 1e-3f);
        assertEquals(ResponsiveLayout.FLING_MAX_PX_PER_S, ResponsiveLayout.flingFor(1e6f, 0L));
        assertEquals(-ResponsiveLayout.FLING_MAX_PX_PER_S, ResponsiveLayout.flingFor(-1e6f, 0L));
        float coast = 3000f;
        int frames = 0;
        while (coast != 0f && frames < 1000) { coast = ResponsiveLayout.decayFling(coast, 1f / 60f, 20f); frames++; }
        assertTrue(frames < 300, "fling stops within a few seconds, took " + frames);
        assertEquals(v, ResponsiveLayout.blendVelocity(v, 0f, 0f));                         // zero dt keeps the estimate
    }
}
