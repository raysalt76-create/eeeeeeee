package interstellarTikiTiks.apexx.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * What one player wears: a cape design, a hat and wings (each optional). This is exactly what is shared with
 * other Apexx users when the owner opts in, so it carries nothing but cosmetic data. Every read validates and
 * clamps, because remote selections come from other people.
 */
public record CosmeticSelection(CapeDesign cape, Hat hat, Wings wings) {
    public static final CosmeticSelection NONE = new CosmeticSelection(null, null, null);
    /** Upper bound on the serialized size, shared with the website's validation. */
    public static final int MAX_JSON_CHARS = 4000;
    /** Colour value meaning "use the theme accent". */
    public static final int THEME_COLOR = -1;

    public boolean isEmpty() {
        return cape == null && hat == null && wings == null;
    }

    public record Hat(String id, int color) {
        public Hat {
            id = CosmeticCatalog.hat(id) == null ? CosmeticCatalog.HATS.get(0).id() : id;
            color = color == THEME_COLOR ? THEME_COLOR : 0xFF000000 | (color & 0xFFFFFF);
        }
    }

    public record Wings(String type, String colorMode, int customColor, float scale, float tipTint, boolean animate) {
        public static final List<String> COLOR_MODES = List.of("Pearl White", "Theme Sync", "Obsidian", "Ember", "Custom");

        public Wings {
            type = "dragon".equals(type) ? "dragon" : "angel";
            colorMode = COLOR_MODES.contains(colorMode) ? colorMode : COLOR_MODES.get(0);
            customColor = 0xFF000000 | (customColor & 0xFFFFFF);
            scale = Float.isFinite(scale) ? Math.max(0.65f, Math.min(1.8f, scale)) : 1f;
            tipTint = Float.isFinite(tipTint) ? Math.max(0f, Math.min(1f, tipTint)) : 0.35f;
        }

        public static Wings of(String type) {
            return new Wings(type, "Pearl White", 0x8CC8FF, 1f, 0.35f, true);
        }

        public Wings withType(String t) { return new Wings(t, colorMode, customColor, scale, tipTint, animate); }
        public Wings withColorMode(String m) { return new Wings(type, m, customColor, scale, tipTint, animate); }
        public Wings withCustomColor(int c) { return new Wings(type, colorMode, c, scale, tipTint, animate); }
        public Wings withScale(float s) { return new Wings(type, colorMode, customColor, s, tipTint, animate); }
        public Wings withTip(float t) { return new Wings(type, colorMode, customColor, scale, t, animate); }
        public Wings withAnimate(boolean a) { return new Wings(type, colorMode, customColor, scale, tipTint, a); }
    }

    public CosmeticSelection withCape(CapeDesign c) { return new CosmeticSelection(c, hat, wings); }
    public CosmeticSelection withHat(Hat h) { return new CosmeticSelection(cape, h, wings); }
    public CosmeticSelection withWings(Wings w) { return new CosmeticSelection(cape, hat, w); }

    // ------------------------------------------------------------------ json

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        if (cape != null) o.add("cape", cape.toJson());
        if (hat != null) {
            JsonObject h = new JsonObject();
            h.addProperty("id", hat.id());
            h.addProperty("c", hat.color() == THEME_COLOR ? THEME_COLOR : hat.color() & 0xFFFFFF);
            o.add("hat", h);
        }
        if (wings != null) {
            JsonObject w = new JsonObject();
            w.addProperty("t", wings.type());
            w.addProperty("m", wings.colorMode());
            w.addProperty("c", wings.customColor() & 0xFFFFFF);
            w.addProperty("s", Math.round(wings.scale() * 100f) / 100f);
            w.addProperty("tip", Math.round(wings.tipTint() * 100f) / 100f);
            w.addProperty("a", wings.animate());
            o.add("wings", w);
        }
        return o;
    }

    /** Validating read; returns {@link #NONE} for anything unusable or oversized. */
    public static CosmeticSelection fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return NONE;
        if (element.toString().length() > MAX_JSON_CHARS) return NONE;
        JsonObject o = element.getAsJsonObject();
        CapeDesign cape = CapeDesign.fromJson(o.get("cape"));
        Hat hat = null;
        JsonObject h = Json.obj(o, "hat");
        if (h != null) {
            String id = Json.str(h, "id", "");
            if (CosmeticCatalog.hat(id) != null) hat = new Hat(id, Json.integer(h, "c", THEME_COLOR));
        }
        Wings wings = null;
        JsonObject w = Json.obj(o, "wings");
        if (w != null) {
            String type = Json.str(w, "t", "").toLowerCase(Locale.ROOT);
            if (type.equals("angel") || type.equals("dragon")) {
                wings = new Wings(type, Json.str(w, "m", "Pearl White"), Json.integer(w, "c", 0x8CC8FF),
                        (float) Json.number(w, "s", 1.0), (float) Json.number(w, "tip", 0.35), Json.bool(w, "a", true));
            }
        }
        return new CosmeticSelection(cape, hat, wings);
    }
}
