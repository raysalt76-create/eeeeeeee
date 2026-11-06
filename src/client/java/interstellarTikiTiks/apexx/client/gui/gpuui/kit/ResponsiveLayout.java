package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

/**
 * The menus' scale floors, layout breakpoints and fling maths in one place, free of Minecraft so the numbers can
 * be checked offline for every screen size.
 *
 * <p>Sizes come in two kinds. <b>Design units</b> are the numbers the layout code is written in (a classic-menu
 * card is 66 units tall); one unit is drawn as {@code s} framebuffer pixels. <b>Breakpoints</b> are taken in design
 * units of the room available at the base scale, i.e. "how much of the design would fit without shrinking it":
 * below a breakpoint the layout reflows (rail to icons, panels over the window) instead of scaling its text down.
 */
public final class ResponsiveLayout {
    private ResponsiveLayout() { }

    // ---- Classic (desktop) menu ---------------------------------------------------------------------------

    /** Body text of the classic menu in design units (setting names, card titles are 11-12.5). */
    public static final float BODY_TEXT_UNITS = 11f;
    /** The classic menu's smallest routinely read text (chips, hints), design units. */
    public static final float SMALL_TEXT_UNITS = 8.5f;
    /** Readable floor for body text on a PC monitor, framebuffer pixels. */
    public static final float MIN_BODY_TEXT_PX = 12f;
    /** Smallest finger target, framebuffer pixels (about 7 mm on a phone). */
    public static final float MIN_TOUCH_PX = 44f;

    /** Below this many available design units of width the category rail collapses to icons. */
    public static final float COMPACT_RAIL_BREAKPOINT = 760f;
    /** Below this window width (design units) the header drops its word mark and gives the pill the room. */
    public static final float HEADER_COMPACT_BREAKPOINT = 700f;
    public static final float RAIL_W = 150f, COMPACT_RAIL_W = 56f;
    /** Minimum window width with the icon rail: one comfortable card column plus the rail. */
    public static final float COMPACT_MIN_WINDOW_W = 440f;
    /** Very wide or tall screens: the window stops growing here and stays centred. */
    public static final float MAX_WINDOW_W = 1400f, MAX_WINDOW_H = 1000f;

    /**
     * Automatic part of the classic menu scale: the window-height rule (same share of the screen at 720p, 1080p,
     * 1440p and 4K), times the monitor-size correction, never letting body text under its readable floor. The
     * Menu Scale slider multiplies this afterwards, so the player can still go smaller on purpose.
     */
    public static float classicAutoScale(float framebufferHeight, float monitorCorrection) {
        float base = Math.max(0.7f, Math.min(3.2f, framebufferHeight / 800f)) * monitorCorrection;
        return Math.max(base, MIN_BODY_TEXT_PX / BODY_TEXT_UNITS);
    }

    /** Design units of width that fit on a screen {@code availablePx} wide at scale {@code s}. */
    public static float availableUnits(float availablePx, float s) {
        return availablePx / Math.max(0.05f, s);
    }

    /** Icon-only rail for narrow or portrait screens. */
    public static boolean compactRail(float availableUnits) {
        return availableUnits < COMPACT_RAIL_BREAKPOINT;
    }

    /**
     * A side panel (module inspector, settings drawer) docks beside the window only when it fits next to the
     * window's minimum width at full scale; otherwise it slides over the window's content instead.
     */
    public static boolean dockSidePanel(float availableUnits, float minWindowW, float panelExtent) {
        return availableUnits >= minWindowW + panelExtent;
    }

    // ---- Orbit layout -------------------------------------------------------------------------------------

    public static final float ORBIT_REF_W = 1180f, ORBIT_REF_H = 660f;
    /** Dial, one tile column, the settings satellite and both side margins must fit across. */
    public static final float ORBIT_MIN_VIEW_W = 940f;
    public static final float ORBIT_BODY_UNITS = 13f;

    /**
     * Orbit's unit size: fit the reference frame, times the Menu Scale / monitor factor, floored so body text keeps
     * its readable size whenever the height allows, and capped so the dial, field and satellite always fit across.
     */
    public static float orbitScale(float screenW, float screenH, float factor) {
        float fit = Math.min(screenW / ORBIT_REF_W, screenH / ORBIT_REF_H);
        float floor = Math.min(MIN_BODY_TEXT_PX / ORBIT_BODY_UNITS, screenH / ORBIT_REF_H);
        float s = Math.max(fit, floor) * Math.max(0.05f, factor);
        return Math.max(0.05f, Math.min(s, screenW / ORBIT_MIN_VIEW_W));
    }

    // ---- Touch menu ---------------------------------------------------------------------------------------

    /** Smallest touch control in the mobile menu (switch, step button), design units. */
    public static final float MOBILE_MIN_CONTROL_UNITS = 28f;
    public static final float MOBILE_MIN_UNIT_PX = 1.6f, MOBILE_MAX_UNIT_PX = 6f;
    /** Rows stop growing at this width (design units); wider screens centre the column. */
    public static final float MOBILE_MAX_CONTENT_UNITS = 520f;

    /**
     * Framebuffer pixels per mobile design unit. Taken from the physical height of the screen, so rows are the same
     * real size whatever Minecraft GUI scale the launcher picked (a 1080 px tall phone gives 4 px a unit: 200 px
     * rows). The Menu Scale slider stacks on top; the floor keeps the smallest control at a 44 px finger target.
     */
    public static float mobileUnitPx(float framebufferHeight, float menuScale) {
        float unit = Math.max(MOBILE_MIN_UNIT_PX, Math.min(MOBILE_MAX_UNIT_PX, framebufferHeight / 270f));
        unit *= Math.max(0.05f, menuScale);
        return Math.max(unit, MIN_TOUCH_PX / MOBILE_MIN_CONTROL_UNITS);
    }

    // ---- HUD editor chrome --------------------------------------------------------------------------------

    /** The editor's label size and button height, in its own units (which the GUI scale multiplies). */
    public static final float HUD_LABEL_UNITS = 7.5f, HUD_BUTTON_UNITS = 20f;
    public static final float HUD_MIN_LABEL_PX = 13f;

    /**
     * Scale of the HUD editor's own chrome (header, panels, popups, legend). It starts from the HUD's viewport scale
     * and is raised so a label is never under {@link #HUD_MIN_LABEL_PX} framebuffer pixels, matches the classic
     * menu's small text on big monitors, and on a touch screen keeps a button at least 44 px tall. The widgets
     * themselves keep the HUD's scale.
     */
    public static float hudChromeScale(float viewportScale, float framebufferHeight, float pixelsPerGuiUnit,
                                       boolean touch) {
        float px = Math.max(0.01f, pixelsPerGuiUnit);
        float s = Math.max(viewportScale, HUD_MIN_LABEL_PX / (HUD_LABEL_UNITS * px));
        float menuSmallPx = SMALL_TEXT_UNITS * Math.max(0.7f, Math.min(3.2f, framebufferHeight / 800f));
        s = Math.max(s, menuSmallPx / (HUD_LABEL_UNITS * px));
        if (touch) s = Math.max(s, MIN_TOUCH_PX / (HUD_BUTTON_UNITS * px));
        return s;
    }

    // ---- Drag-to-scroll -----------------------------------------------------------------------------------

    /** Pointer travel before a press becomes a scroll (so a tap never scrolls), framebuffer pixels at scale 1. */
    public static final float DRAG_SLOP_PX = 6f;
    /** A fling only starts if the finger was still moving within this many ns of the release. */
    public static final long FLING_RECENT_NS = 120_000_000L;
    public static final float FLING_MAX_PX_PER_S = 6000f;
    private static final float FLING_DECAY_PER_S = 3.5f;

    /** New velocity estimate from the latest move: an EMA so a jittery finger does not whip the list. */
    public static float blendVelocity(float previous, float deltaPx, float dtSeconds) {
        if (!(dtSeconds > 0f)) return previous;
        float sample = deltaPx / Math.max(dtSeconds, 0.001f);
        return previous * 0.6f + sample * 0.4f;
    }

    /** Fling velocity for a release: the tracked velocity, clamped, or 0 when the finger had already stopped. */
    public static float flingFor(float velocity, long sinceLastMoveNs) {
        if (sinceLastMoveNs > FLING_RECENT_NS) return 0f;
        return Math.max(-FLING_MAX_PX_PER_S, Math.min(FLING_MAX_PX_PER_S, velocity));
    }

    /** Velocity after {@code dtSeconds} of coasting; snaps to 0 once slower than {@code stopBelow}. */
    public static float decayFling(float velocity, float dtSeconds, float stopBelow) {
        float v = velocity * (float) Math.exp(-Math.max(0f, dtSeconds) * FLING_DECAY_PER_S);
        return Math.abs(v) < stopBelow ? 0f : v;
    }
}
