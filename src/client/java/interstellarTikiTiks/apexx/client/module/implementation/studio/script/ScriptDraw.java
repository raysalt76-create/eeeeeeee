package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;

import java.util.Locale;

/** Pure helpers behind the script HUD-drawing functions in {@link HostApi}. */
public final class ScriptDraw {

    private ScriptDraw() {}

    /** Longest string drawText draws; longer text is cut. */
    public static final int MAX_TEXT = 256;
    /** Coordinates and sizes are clamped to this range (scaled GUI pixels). */
    public static final int MAX_COORD = 16_384;

    /**
     * Script colour: a number (0xRRGGBB is drawn opaque, 0xAARRGGBB as given - the same numbers a
     * {@code setting color} reads as) or a hex string ("#RRGGBB", "#AARRGGBB", "#RGB", "0x..."). Anything
     * unreadable is white.
     */
    public static int parseColor(Value v) {
        if (v == null) return 0xFFFFFFFF;
        if (v.type == Value.Type.STRING) {
            String s = v.asString().trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) s = s.substring(1);
            else if (s.startsWith("0x")) s = s.substring(2);
            if (s.length() == 3) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 3; i++) sb.append(s.charAt(i)).append(s.charAt(i));
                s = sb.toString();
            }
            if (s.length() != 6 && s.length() != 8) return 0xFFFFFFFF;
            try {
                long parsed = Long.parseLong(s, 16);
                return s.length() == 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
            } catch (NumberFormatException e) {
                return 0xFFFFFFFF;
            }
        }
        double d = v.asNumber();
        if (!Double.isFinite(d)) return 0xFFFFFFFF;
        long bits = (long) d & 0xFFFFFFFFL;
        // Without an alpha byte the colour would be fully transparent (and text is skipped at alpha 0).
        if (bits <= 0xFFFFFFL) bits |= 0xFF000000L;
        return (int) bits;
    }

    /** Clamps a script coordinate/size to a sane integer range (NaN -> 0). */
    public static int coord(double d) {
        if (!Double.isFinite(d)) return 0;
        return (int) Math.max(-MAX_COORD, Math.min(MAX_COORD, Math.round(d)));
    }

    /** Text for drawText/textWidth: control characters removed, length capped. */
    public static String text(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), MAX_TEXT));
        for (int i = 0; i < s.length() && sb.length() < MAX_TEXT; i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Whether a HUD pass may draw script visuals: always when SS Proof is off; with SS Proof on, only while the
     * pass is redirected into the overlay (no Apexx screen: the early HUD flush; an Apexx screen open: the
     * redirected GUI flush). Same rule the other HUD/render visuals follow.
     */
    static boolean mayDrawThisPass() {
        try {
            if (!OverlayCompositor.isSsProofOn()) return true;
            return OverlayCompositor.shouldRedirectHud() || OverlayCompositor.shouldRedirect();
        } catch (Throwable t) {
            return false;
        }
    }
}
