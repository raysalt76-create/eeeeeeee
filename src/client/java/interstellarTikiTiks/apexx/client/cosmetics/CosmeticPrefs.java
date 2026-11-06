package interstellarTikiTiks.apexx.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The player's cosmetics choices and options. Lives inside the cloud-synced {@code ClientPrefs} blob (under
 * {@link #KEY}), so it follows the account across config swaps and never touches the disk.
 */
public final class CosmeticPrefs {
    public static final String KEY = "cosmetics";

    public enum CapeMode {
        PHYSICS("Physics"), STATIC("Static"), VANILLA("Vanilla");
        public final String label;
        CapeMode(String label) { this.label = label; }
    }

    /** The instance owned by the cloud-synced ClientPrefs. */
    public static CosmeticPrefs get() {
        return interstellarTikiTiks.apexx.client.config.ClientPrefs.get().cosmetics();
    }

    private final Runnable onChange;
    private CosmeticSelection selection = CosmeticSelection.NONE;
    private CapeDesign customDesign = CapeDesign.defaultCustom();
    private CapeMode capeMode = CapeMode.PHYSICS;
    private boolean elytraUsesCape = true;
    private boolean shareMine;
    private boolean showOthers = true;
    private boolean autoSpin = true;
    private boolean published;
    private volatile int version;

    public CosmeticPrefs(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    // ------------------------------------------------------------------ accessors

    public synchronized CosmeticSelection selection() { return selection; }
    public synchronized CapeDesign customDesign() { return customDesign; }
    public synchronized CapeMode capeMode() { return capeMode; }
    public synchronized boolean elytraUsesCape() { return elytraUsesCape; }
    public synchronized boolean shareMine() { return shareMine; }
    public synchronized boolean showOthers() { return showOthers; }
    public synchronized boolean autoSpin() { return autoSpin; }
    public synchronized boolean published() { return published; }
    /** Bumped whenever the shared selection changes, so the sync knows to publish. */
    public int version() { return version; }

    public void setSelection(CosmeticSelection value) {
        CosmeticSelection v = value == null ? CosmeticSelection.NONE : value;
        synchronized (this) {
            if (v.equals(selection)) return;
            selection = v;
            version++;
        }
        onChange.run();
    }

    public void setCustomDesign(CapeDesign value) {
        if (value == null) return;
        synchronized (this) {
            if (value.equals(customDesign)) return;
            customDesign = value;
        }
        onChange.run();
    }

    public void setCapeMode(CapeMode mode) {
        synchronized (this) {
            if (mode == null || mode == capeMode) return;
            capeMode = mode;
        }
        onChange.run();
    }

    public void setElytraUsesCape(boolean on) {
        synchronized (this) {
            if (on == elytraUsesCape) return;
            elytraUsesCape = on;
        }
        onChange.run();
    }

    public void setShareMine(boolean on) {
        synchronized (this) {
            if (on == shareMine) return;
            shareMine = on;
            version++;
        }
        onChange.run();
    }

    public void setShowOthers(boolean on) {
        synchronized (this) {
            if (on == showOthers) return;
            showOthers = on;
        }
        onChange.run();
    }

    public void setAutoSpin(boolean on) {
        synchronized (this) {
            if (on == autoSpin) return;
            autoSpin = on;
        }
        onChange.run();
    }

    /** Recorded by the sync after the server confirms a publish (true) or a removal (false). */
    public void setPublished(boolean on) {
        synchronized (this) {
            if (on == published) return;
            published = on;
        }
        onChange.run();
    }

    // ------------------------------------------------------------------ blob

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("sel", selection.toJson());
        o.add("custom", customDesign.toJson());
        o.addProperty("capeMode", capeMode.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("elytraCape", elytraUsesCape);
        o.addProperty("share", shareMine);
        o.addProperty("showOthers", showOthers);
        o.addProperty("spin", autoSpin);
        o.addProperty("published", published);
        return o;
    }

    /** Restores from the user's own blob; missing keys keep the live values. Does not fire the change hook. */
    public void applyJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject o = element.getAsJsonObject();
        synchronized (this) {
            if (o.has("sel")) selection = CosmeticSelection.fromJson(o.get("sel"));
            CapeDesign custom = CapeDesign.fromJson(o.get("custom"));
            if (custom != null) customDesign = custom.withPreset(CapeDesign.CUSTOM);
            capeMode = Json.enumOf(CapeMode.class, Json.str(o, "capeMode", ""), capeMode);
            elytraUsesCape = Json.bool(o, "elytraCape", elytraUsesCape);
            shareMine = Json.bool(o, "share", shareMine);
            showOthers = Json.bool(o, "showOthers", showOthers);
            autoSpin = Json.bool(o, "spin", autoSpin);
            published = Json.bool(o, "published", published);
            version++;
        }
    }

    /**
     * The cosmetics block for a user who had the old Wings render module enabled: the same wings (type, colour,
     * scale, tint, animation) and, when its halo was on, the Nimbus hat. Used by the config migration.
     */
    public static JsonObject legacyWingsJson(JsonObject wingsSettings) {
        JsonObject s = wingsSettings == null ? new JsonObject() : wingsSettings;
        String type = Json.str(s, "Wings", "Angel");
        CosmeticSelection sel = CosmeticSelection.NONE;
        if ("Angel".equalsIgnoreCase(type) || "Dragon".equalsIgnoreCase(type)) {
            int custom = Json.integer(s, "Custom Color", 0x8CC8FF);
            sel = sel.withWings(new CosmeticSelection.Wings(type.toLowerCase(java.util.Locale.ROOT), Json.str(s, "Color", "Pearl White"),
                    custom, (float) Json.number(s, "Wing Scale", 1.0), (float) Json.number(s, "Tip Tint", 0.35),
                    Json.bool(s, "Wing Animation", true)));
        }
        if (Json.bool(s, "Halo (Nimbus)", true)) sel = sel.withHat(new CosmeticSelection.Hat("nimbus", CosmeticSelection.THEME_COLOR));
        JsonObject o = new JsonObject();
        o.add("sel", sel.toJson());
        return o;
    }
}
