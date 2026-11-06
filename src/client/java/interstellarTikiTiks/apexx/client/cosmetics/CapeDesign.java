package interstellarTikiTiks.apexx.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * A cape as data: colours, a pattern, initials, an emblem and a border, or an imported picture
 * ({@link CapeImageCodec}). Small enough to live in the cloud prefs and to be shared with other Apexx
 * users; {@link CapeArt} turns it into a texture at runtime, so no image file ever exists.
 */
public record CapeDesign(String preset, int base1, int base2, Gradient gradient, Pattern pattern, int patternColor,
                         String text, int textColor, Emblem emblem, int emblemColor, Border border, int borderColor,
                         String image) {

    public static final int MAX_TEXT = 4;
    /** Hard cap on an encoded imported picture, shared with the website's validation. */
    public static final int MAX_IMAGE_CHARS = 3200;

    /** Syntactic check of an encoded picture (base64url charset, length); untrusted input is filtered with it. */
    public static boolean isImageWellFormed(String data) {
        if (data == null || data.isEmpty() || data.length() > MAX_IMAGE_CHARS) return false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }
    public static final String CUSTOM = "custom";

    public enum Gradient { NONE, VERTICAL, HORIZONTAL, DIAGONAL, RADIAL }

    public enum Pattern { NONE, STRIPES, CHEVRON, DOTS, CHECKER, STARS, WAVES, CIRCUIT, DIAMONDS, SCALES }

    public enum Emblem { NONE, APEXX, STAR, HEART, BOLT, CROWN, MOON, FLAME, DIAMOND }

    public enum Border { NONE, THIN, THICK, GLOW }

    public CapeDesign {
        preset = cleanId(preset);
        base1 = rgb(base1);
        base2 = rgb(base2);
        patternColor = rgb(patternColor);
        textColor = rgb(textColor);
        emblemColor = rgb(emblemColor);
        borderColor = rgb(borderColor);
        gradient = gradient == null ? Gradient.NONE : gradient;
        pattern = pattern == null ? Pattern.NONE : pattern;
        emblem = emblem == null ? Emblem.NONE : emblem;
        border = border == null ? Border.NONE : border;
        text = cleanText(text);
        image = isImageWellFormed(image) ? image : null;
    }

    /** Built-in cape: an id the UI can highlight plus the design itself. */
    public record Preset(String id, String name, CapeDesign design) { }

    public static final List<Preset> PRESETS = List.of(
            preset("midnight", "Apexx Midnight", 0x0B0F1A, 0x1C2B52, Gradient.VERTICAL, Pattern.NONE, 0, Emblem.APEXX, 0x5AA9FF, Border.THIN, 0x5AA9FF),
            preset("theme", "Theme Sync", 0x101622, 0x3A6BD8, Gradient.VERTICAL, Pattern.NONE, 0, Emblem.APEXX, 0xFFFFFF, Border.GLOW, 0x3A6BD8),
            preset("aurora", "Aurora", 0x0B1E2D, 0x1FD1A5, Gradient.DIAGONAL, Pattern.WAVES, 0x7CF5FF, Emblem.NONE, 0, Border.NONE, 0),
            preset("ember", "Ember", 0x2A0A05, 0xFF5A1F, Gradient.VERTICAL, Pattern.NONE, 0, Emblem.FLAME, 0xFFC24A, Border.THIN, 0xFF8A3D),
            preset("royal", "Royal", 0x2B0F4C, 0x6A2CC2, Gradient.VERTICAL, Pattern.DIAMONDS, 0x8E52E0, Emblem.CROWN, 0xF5C542, Border.THICK, 0xF5C542),
            preset("frost", "Frost", 0xDDEFFF, 0x7FB8FF, Gradient.VERTICAL, Pattern.STARS, 0xFFFFFF, Emblem.STAR, 0xFFFFFF, Border.THIN, 0xFFFFFF),
            preset("circuit", "Circuit", 0x05140C, 0x0B2E1A, Gradient.VERTICAL, Pattern.CIRCUIT, 0x2BFF88, Emblem.BOLT, 0x2BFF88, Border.THIN, 0x2BFF88),
            preset("sakura", "Sakura", 0xFFD6E8, 0xFF8FB8, Gradient.RADIAL, Pattern.DOTS, 0xFFFFFF, Emblem.HEART, 0xFF4F8B, Border.NONE, 0),
            preset("mono", "Monochrome", 0x111111, 0x2A2A2A, Gradient.VERTICAL, Pattern.STRIPES, 0x3A3A3A, Emblem.APEXX, 0xFFFFFF, Border.THIN, 0xFFFFFF),
            preset("galaxy", "Galaxy", 0x090314, 0x2A0E4A, Gradient.RADIAL, Pattern.STARS, 0xFFFFFF, Emblem.MOON, 0xE8E1FF, Border.NONE, 0),
            preset("racing", "Racing", 0x101010, 0x101010, Gradient.NONE, Pattern.CHECKER, 0xF0F0F0, Emblem.NONE, 0, Border.THICK, 0xE53935),
            preset("ocean", "Ocean", 0x03224C, 0x1B9CE5, Gradient.VERTICAL, Pattern.WAVES, 0x9BE7FF, Emblem.NONE, 0, Border.NONE, 0),
            preset("dragon", "Dragon Scale", 0x0E2A12, 0x2F7D32, Gradient.DIAGONAL, Pattern.SCALES, 0x7BD67F, Emblem.NONE, 0, Border.THIN, 0xC8E6C9),
            preset("gold", "Gilded", 0x3D2A05, 0xC9971C, Gradient.RADIAL, Pattern.CHEVRON, 0xF5D36A, Emblem.DIAMOND, 0xFFF4C2, Border.THICK, 0xF5D36A));

    private static Preset preset(String id, String name, int b1, int b2, Gradient g, Pattern p, int pc, Emblem e, int ec,
                                 Border bd, int bc) {
        return new Preset(id, name, new CapeDesign(id, b1, b2, g, p, pc, "", 0xFFFFFF, e, ec, bd, bc, null));
    }

    /** Default starting point of the cape maker. */
    public static CapeDesign defaultCustom() {
        return new CapeDesign(CUSTOM, 0x14182A, 0x3A6BD8, Gradient.VERTICAL, Pattern.NONE, 0xFFFFFF, "",
                0xFFFFFF, Emblem.APEXX, 0xFFFFFF, Border.THIN, 0xFFFFFF, null);
    }

    public static Preset presetById(String id) {
        for (Preset p : PRESETS) if (p.id().equals(id)) return p;
        return null;
    }

    /** The Theme Sync preset recoloured with the given accent (RGB). */
    public static CapeDesign themed(int accent) {
        int a = rgb(accent);
        return new CapeDesign("theme", mix(0x0A0D14, a, 0.18f), a, Gradient.VERTICAL, Pattern.NONE, 0, "", 0xFFFFFF,
                Emblem.APEXX, 0xFFFFFF, Border.GLOW, mix(a, 0xFFFFFF, 0.25f), null);
    }

    public CapeDesign withPreset(String id) {
        return new CapeDesign(id, base1, base2, gradient, pattern, patternColor, text, textColor, emblem, emblemColor, border, borderColor, image);
    }

    public CapeDesign withImage(String encoded) {
        return new CapeDesign(preset, base1, base2, gradient, pattern, patternColor, text, textColor, emblem, emblemColor, border, borderColor, encoded);
    }

    public CapeDesign withColors(int b1, int b2, int pc, int tc, int ec, int bc) {
        return new CapeDesign(preset, b1, b2, gradient, pattern, pc, text, tc, emblem, ec, border, bc, image);
    }

    public CapeDesign withShape(Gradient g, Pattern p, Emblem e, Border b) {
        return new CapeDesign(preset, base1, base2, g, p, patternColor, text, textColor, e, emblemColor, b, borderColor, image);
    }

    public CapeDesign withText(String t) {
        return new CapeDesign(preset, base1, base2, gradient, pattern, patternColor, t, textColor, emblem, emblemColor, border, borderColor, image);
    }

    // ------------------------------------------------------------------ json

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", preset);
        o.addProperty("b1", base1 & 0xFFFFFF);
        o.addProperty("b2", base2 & 0xFFFFFF);
        o.addProperty("g", gradient.name().toLowerCase(Locale.ROOT));
        o.addProperty("p", pattern.name().toLowerCase(Locale.ROOT));
        o.addProperty("pc", patternColor & 0xFFFFFF);
        if (!text.isEmpty()) o.addProperty("t", text);
        o.addProperty("tc", textColor & 0xFFFFFF);
        o.addProperty("e", emblem.name().toLowerCase(Locale.ROOT));
        o.addProperty("ec", emblemColor & 0xFFFFFF);
        o.addProperty("bd", border.name().toLowerCase(Locale.ROOT));
        o.addProperty("bc", borderColor & 0xFFFFFF);
        if (image != null) o.addProperty("img", image);
        return o;
    }

    /** Lenient, validating read: unknown values fall back to defaults; anything that is not an object gives null. */
    public static CapeDesign fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        return new CapeDesign(Json.str(o, "id", CUSTOM), Json.integer(o, "b1", 0x14182A), Json.integer(o, "b2", 0x3A6BD8),
                Json.enumOf(Gradient.class, Json.str(o, "g", ""), Gradient.NONE),
                Json.enumOf(Pattern.class, Json.str(o, "p", ""), Pattern.NONE), Json.integer(o, "pc", 0xFFFFFF),
                Json.str(o, "t", ""), Json.integer(o, "tc", 0xFFFFFF),
                Json.enumOf(Emblem.class, Json.str(o, "e", ""), Emblem.NONE), Json.integer(o, "ec", 0xFFFFFF),
                Json.enumOf(Border.class, Json.str(o, "bd", ""), Border.NONE), Json.integer(o, "bc", 0xFFFFFF),
                Json.str(o, "img", null));
    }

    /** Stable key for texture caching. */
    public String cacheKey() {
        return toJson().toString();
    }

    // ------------------------------------------------------------------ helpers

    static int rgb(int color) {
        return 0xFF000000 | (color & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Upper-case letters and digits only, at most {@link #MAX_TEXT}. */
    public static String cleanText(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(MAX_TEXT);
        for (int i = 0; i < raw.length() && sb.length() < MAX_TEXT; i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private static String cleanId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 24 || !raw.matches("[a-z0-9_]+")) return CUSTOM;
        return raw;
    }
}
