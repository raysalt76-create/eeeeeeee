package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Versioned rewrites applied to a config's JSON before it is loaded, so configs saved by older client
 * versions keep their values when settings are renamed or change units. Each config is stamped with
 * {@link #SCHEMA_KEY}; a config without the stamp is schema 1. Unknown or removed settings need no rule:
 * the loader simply never asks for them, and a missing setting keeps its default.
 */
public final class ConfigMigrations {

    public static final String SCHEMA_KEY = "configSchema";
    public static final int CURRENT_SCHEMA = 6;

    /** Suffix keys stored next to a setting's own value that must follow it through a rename. */
    private static final List<String> COMPANION_SUFFIXES = List.of("#key", "#hold", "#rainbow", "#rainbowSpeed");

    /** One setting rewrite inside one module: old name to new name, value multiplied by {@code scale}. */
    private record Rename(String module, String from, String to, double scale) {
    }

    /** Schema 1 -> 2: renames and unit changes from the 2026-10 settings rework. */
    private static final List<Rename> V2_RENAMES = List.of(
            new Rename("AutoMace", "Lock-On Wait (ticks)", "Lock-On Wait (ms)", 50.0),
            new Rename("AutoElytra", "Extra Lead", "Extra Lead (ms)", 1.0),
            new Rename("ChompzaInstaCatch", "Click Delay", "Click Delay (ms)", 1.0),
            new Rename("BowSpam", "Charge Ticks", "Charge Time (ms)", 50.0),
            new Rename("BowSpam", "Release Gap (ticks)", "Release Gap (ms)", 50.0),
            new Rename("KnockbackDisplacement", "Cooldown (ticks)", "Cooldown (ms)", 50.0),
            new Rename("NoBreakDelay", "Max Delay", "Extra Delay (ms)", 50.0),
            new Rename("SpearAssist", "Through Walls", "Wall Bypass", 1.0));

    private ConfigMigrations() {
    }

    /** The schema a config was saved with; 1 when it predates the stamp or the stamp is unreadable. */
    public static int schemaOf(JsonObject root) {
        if (root == null) return CURRENT_SCHEMA;
        JsonElement stamp = root.get(SCHEMA_KEY);
        if (stamp == null || !stamp.isJsonPrimitive()) return 1;
        try {
            return Math.max(1, stamp.getAsInt());
        } catch (RuntimeException malformed) {
            return 1;
        }
    }

    /** Upgrades {@code root} in place to {@link #CURRENT_SCHEMA}. Never throws; a config it can't read is left as is. */
    public static void migrate(JsonObject root) {
        if (root == null) return;
        try {
            int schema = schemaOf(root);
            if (schema >= CURRENT_SCHEMA) return;
            JsonElement modulesElement = root.get("modules");
            if (modulesElement != null && modulesElement.isJsonObject()) {
                JsonObject modules = modulesElement.getAsJsonObject();
                if (schema < 2) migrateToV2(modules);
                if (schema < 4) migrateToV4(modules);
                if (schema < 5) migrateWingsToCosmetics(root, modules);
            }
            JsonElement hudElement = root.get("hudElements");
            if (schema < 3 && hudElement != null && hudElement.isJsonObject()) {
                migrateHudToV3(hudElement.getAsJsonObject());
            }
            // Schema 6 touches modules, HUD widgets and GUI prefs together (modules became widgets).
            if (schema < 6) ConfigMigrationsV6.migrate(root);
            root.addProperty(SCHEMA_KEY, CURRENT_SCHEMA);
        } catch (RuntimeException malformed) {
            // A half-migrated config still loads through the normal name matching.
        }
    }

    /** Stamps a freshly built config with the current schema. */
    public static void stamp(JsonObject root) {
        if (root != null) root.addProperty(SCHEMA_KEY, CURRENT_SCHEMA);
    }

    /** Module-level bind fields (current names and the older obfuscated aliases ConfigManager also reads). */
    private static final List<String> MODULE_BIND_KEYS = List.of("keyCode", "keyCode2", "lIIllIlI32", "lIlIIlIl32");

    /**
     * The classic menu used to save a mouse bind as {@code button - 100} (-99..-93 for buttons 1..7) while every
     * reader decodes {@code -(button + 100)}, so those binds never fired. Values in -99..-93 can only come from that
     * old encoding (the current one is always <= -100), so they convert unambiguously.
     */
    private static int fixLegacyMouseBind(int code) {
        return code >= -99 && code <= -93 ? -(code + 200) : code;
    }

    private static void fixBind(JsonObject holder, String key) {
        JsonElement value = holder.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return;
        int code = value.getAsInt();
        int fixed = fixLegacyMouseBind(code);
        if (fixed != code) holder.addProperty(key, fixed);
    }

    private static void migrateMouseBinds(JsonObject modules) {
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject module = entry.getValue().getAsJsonObject();
            for (String key : MODULE_BIND_KEYS) fixBind(module, key);
            JsonElement settings = module.get("settings");
            if (settings == null || !settings.isJsonObject()) continue;
            JsonObject settingsObject = settings.getAsJsonObject();
            for (String name : List.copyOf(settingsObject.keySet())) {
                if (name.endsWith("#key")) fixBind(settingsObject, name);
            }
        }
    }

    /** Module display-name renames (old key -> new key); the whole saved block moves across. */
    private static final Map<String, String> MODULE_RENAMES = Map.ofEntries(Map.entry("AlienWC", "AlienHighCharge"));

    private static void renameModules(JsonObject modules) {
        for (Map.Entry<String, String> rename : MODULE_RENAMES.entrySet()) {
            if (modules.has(rename.getKey()) && !modules.has(rename.getValue())) {
                modules.add(rename.getValue(), modules.remove(rename.getKey()));
            }
        }
    }

    /** Modules folded into another (old key -> surviving key): its settings move across, never overwriting. */
    private static final Map<String, String> MODULE_MERGES = Map.ofEntries(Map.entry("Alien Windcharge", "AlienHighCharge"));

    private static void mergeModules(JsonObject modules) {
        for (Map.Entry<String, String> merge : MODULE_MERGES.entrySet()) {
            String from = merge.getKey();
            String to = merge.getValue();
            if (!modules.has(from) || !modules.get(from).isJsonObject()) continue;
            JsonObject src = modules.remove(from).getAsJsonObject();
            if (!modules.has(to) || !modules.get(to).isJsonObject()) {
                modules.add(to, src);
                continue;
            }
            JsonObject dst = modules.get(to).getAsJsonObject();
            JsonElement srcSettings = src.get("settings");
            if (srcSettings == null || !srcSettings.isJsonObject()) continue;
            JsonObject dstSettings;
            if (dst.has("settings") && dst.get("settings").isJsonObject()) {
                dstSettings = dst.getAsJsonObject("settings");
            } else {
                dstSettings = new JsonObject();
                dst.add("settings", dstSettings);
            }
            for (Map.Entry<String, JsonElement> e : srcSettings.getAsJsonObject().entrySet()) {
                if (!dstSettings.has(e.getKey())) dstSettings.add(e.getKey(), e.getValue());
            }
        }
    }

    private static void migrateToV2(JsonObject modules) {
        renameModules(modules);
        mergeModules(modules);
        migrateMouseBinds(modules);
        for (Rename rename : V2_RENAMES) {
            JsonObject settings = moduleSettings(modules, rename.module());
            if (settings != null) renameSetting(settings, rename.from(), rename.to(), rename.scale());
        }
        // CartRefill was rebuilt: the old module always refilled a fixed hotbar slot by itself.
        JsonObject cart = moduleSettings(modules, "CartRefill");
        if (cart != null && cart.has("Slot") && !cart.has("Target Slot")) {
            try {
                int slot = (int) Math.round(cart.get("Slot").getAsDouble());
                if (slot >= 1 && slot <= 9) cart.addProperty("Target Slot", "Slot " + slot);
            } catch (RuntimeException ignored) {
            }
            if (!cart.has("Mode")) cart.addProperty("Mode", "Auto");
            cart.remove("Slot");
        }
    }

    /** One module folded into another by the 2026-10 duplicate-module merge (schema 3 -> 4). */
    private record Fold(String from, String to, List<Rename> renames, boolean settingsOnlyWhenActive) {
    }

    /** A rename inside the absorbed module's own block, applied before its settings move across. */
    private static Rename r(String from, String to) {
        return new Rename("", from, to, 1.0);
    }

    private static Rename r(String from, String to, double scale) {
        return new Rename("", from, to, scale);
    }

    private static List<Rename> hoverPotRenames() {
        List<Rename> list = new ArrayList<>();
        list.add(r("Auto Close", "Hover Auto Close"));
        for (int i = 1; i <= 9; i++) list.add(r("Refill Slot " + i, "Hover Slot " + i));
        list.add(r("Refill Delay (ms)", "Hover Refill Delay (ms)"));
        list.add(r("Close Delay (ms)", "Hover Close Delay (ms)"));
        return List.copyOf(list);
    }

    /**
     * Schema 3 -> 4: duplicate modules merged into one survivor. The absorbed block's clashing settings are renamed
     * first, then its settings move across. When the absorbed module was in use (enabled or bound to a key) and the
     * survivor was not, the absorbed values win; otherwise only settings the survivor lacks are added. Folds marked
     * {@code settingsOnlyWhenActive} carry nothing from an unused absorbed module, because its values would start
     * changing what the survivor does.
     */
    private static final List<Fold> V4_FOLDS = List.of(
            new Fold("SprintReset", "SmartWTap", List.of(r("Reset Delay (ms)", "Start Lag (ms)")), true),
            new Fold("No Hurt Cam", "NoRender", List.of(), true),
            new Fold("Swing Speed", "ViewModel", List.of(r("Speed", "Swing Duration (ticks)")), true),
            new Fold("Bed Bow Aim", "BowAimbot", List.of(
                    r("Max Distance", "Range"),
                    r("Aim Speed", "Rotation Speed"),
                    r("Humanization", "Aim Randomness", 0.01),
                    r("Line Of Sight", "Visible Only"),
                    r("Aim Point", "Aim At")), false),
            new Fold("Bed Clutch", "Clutch", List.of(
                    r("On Long Fall", "On Lethal Fall"),
                    r("Fall Distance", "Min Fall Distance"),
                    r("Reach", "Max Reach"),
                    r("Silent Rotation", "Silent Rotations"),
                    r("Aim Motion", "Rotation Mode"),
                    r("Knockback Window (ms)", "Knockback Window (ticks)", 0.02),
                    r("Re-Clutch Cooldown (ms)", "Re-Clutch Cooldown (ticks)", 0.02)), false),
            new Fold("AutoExtinguish", "ClutchMLG", List.of(
                    r("Reaction Delay (ms)", "Fire Reaction Delay (ms)"),
                    r("Pickup Delay (ms)", "Fire Pickup Delay (ms)"),
                    r("Cooldown (ms)", "Fire Cooldown (ms)"),
                    r("Pick Up Water", "Auto Retract")), true),
            new Fold("AutoAlienWC", "AlienHighCharge", List.of(), false),
            new Fold("HoverPot", "PotRefill", hoverPotRenames(), false),
            new Fold("AutoGHead", "AutoEat", List.of(
                    r("Health Threshold", "Golden Head Health"),
                    r("Cooldown (ms)", "Head Cooldown (ms)"),
                    r("Reaction Delay (ms)", "Head Reaction Delay (ms)"),
                    r("Swap Back (ms)", "Head Swap Back Delay (ms)")), false),
            new Fold("HoverTotem", "AutoTotem", List.of(
                    r("Only If Empty Offhand", "Hover Only If No Totem"),
                    r("Auto Close Inventory", "Hover Auto Close"),
                    r("Swap Delay (ms)", "Hover Swap Delay (ms)"),
                    r("Close Delay (ms)", "Hover Close Delay (ms)")), false));

    /** Current field name first, then the older obfuscated alias ConfigManager also reads. */
    private static final String[] ENABLED_KEYS = {"enabled", "IlIllIlI32"};
    private static final String[] KEY_KEYS = {"keyCode", "lIIllIlI32"};
    private static final String[] KEY2_KEYS = {"keyCode2", "lIlIIlIl32"};

    private static void migrateToV4(JsonObject modules) {
        for (Fold fold : V4_FOLDS) fold(modules, fold);
    }

    private static void fold(JsonObject modules, Fold fold) {
        String srcKey = moduleKey(modules, fold.from());
        if (srcKey == null || !modules.get(srcKey).isJsonObject()) return;
        String dstKey = moduleKey(modules, fold.to());
        if (srcKey.equals(dstKey)) return;
        JsonObject src = modules.remove(srcKey).getAsJsonObject();
        JsonObject dst;
        if (dstKey != null && modules.get(dstKey).isJsonObject()) {
            dst = modules.get(dstKey).getAsJsonObject();
        } else {
            if (dstKey != null) modules.remove(dstKey);
            dst = new JsonObject();
            dst.addProperty("enabled", false);
            modules.add(fold.to(), dst);
        }
        JsonObject srcSettings = settingsOf(src);
        JsonObject dstSettings = settingsOf(dst);
        for (Rename rename : fold.renames()) renameSetting(srcSettings, rename.from(), rename.to(), rename.scale());

        boolean srcEnabled = readFlag(src, ENABLED_KEYS);
        boolean srcBound = isBound(readInt(src, KEY_KEYS));
        boolean srcActive = srcEnabled || srcBound;
        boolean dstEnabled = readFlag(dst, ENABLED_KEYS);
        boolean dstBound = isBound(readInt(dst, KEY_KEYS));
        boolean dstActive = dstEnabled || dstBound;

        if (srcActive || !fold.settingsOnlyWhenActive()) {
            boolean srcWins = srcActive && !dstActive;
            for (Map.Entry<String, JsonElement> e : srcSettings.entrySet()) {
                if (srcWins || !dstSettings.has(e.getKey())) dstSettings.add(e.getKey(), e.getValue());
            }
        }
        // A key bound to the absorbed module moves to the survivor when the survivor has none.
        if (srcBound && !dstBound) {
            setInt(dst, KEY_KEYS, readInt(src, KEY_KEYS));
            setInt(dst, KEY2_KEYS, readInt(src, KEY2_KEYS));
            JsonElement hold = src.get("holdMode");
            if (hold != null) dst.add("holdMode", hold);
        }
        if (srcEnabled) setFlag(dst, ENABLED_KEYS, true);
        // The survivor takes over the absorbed module's role when that module was the one in use.
        if (srcEnabled || (srcBound && !dstActive)) applyRole(fold.from(), dstSettings, dstEnabled);
    }

    /** The survivor-side switches that make it behave like the absorbed module did. */
    private static void applyRole(String from, JsonObject dstSettings, boolean dstWasEnabled) {
        switch (from) {
            case "SprintReset" -> dstSettings.addProperty("Reset Style", "Sprint Key");
            case "No Hurt Cam" -> {
                dstSettings.addProperty("Hurt Tilt", true);
                // NoRender was off: its default-on overlays must not start hiding things the user saw before.
                if (!dstWasEnabled) {
                    dstSettings.addProperty("Fire", false);
                    dstSettings.addProperty("Pumpkin Blur", false);
                    dstSettings.addProperty("In Wall", false);
                }
            }
            case "AutoExtinguish" -> dstSettings.addProperty("Trigger on Fire", true);
            case "AutoAlienWC" -> dstSettings.addProperty("Activation", "Auto");
            case "HoverPot" -> {
                // Both were on: Pot Refill keeps its own mode; the hover settings are still merged in.
                if (!dstWasEnabled) dstSettings.addProperty("Refill Mode", "Hover");
            }
            case "AutoGHead" -> dstSettings.addProperty("Golden Heads", true);
            case "HoverTotem" -> dstSettings.addProperty("Hover Assist", true);
            default -> {
            }
        }
    }

    /**
     * Schema 4 -> 5: the Wings render module became a cosmetic. Its saved block is removed; when it was enabled, its
     * wings (and its halo, as the Nimbus hat) become the cosmetics selection in the user's prefs. Prefs are only ever
     * applied from the user's own blob, so a named config or an import carrying Wings changes nothing there, and a
     * user who already has cosmetics prefs keeps them.
     */
    private static void migrateWingsToCosmetics(JsonObject root, JsonObject modules) {
        String key = moduleKey(modules, "Wings");
        if (key == null) return;
        JsonElement removed = modules.remove(key);
        if (removed == null || !removed.isJsonObject()) return;
        JsonObject wings = removed.getAsJsonObject();
        if (!readFlag(wings, ENABLED_KEYS)) return;
        JsonObject prefs;
        JsonElement existing = root.get(ClientPrefs.KEY);
        if (existing != null && existing.isJsonObject()) {
            prefs = existing.getAsJsonObject();
        } else if (existing == null) {
            prefs = new JsonObject();
            root.add(ClientPrefs.KEY, prefs);
        } else {
            return;
        }
        String cosmeticsKey = interstellarTikiTiks.apexx.client.cosmetics.CosmeticPrefs.KEY;
        if (prefs.has(cosmeticsKey)) return;
        prefs.add(cosmeticsKey, interstellarTikiTiks.apexx.client.cosmetics.CosmeticPrefs.legacyWingsJson(settingsOf(wings)));
    }

    private static boolean isBound(int code) {
        return code > 0 || code < -1;
    }

    /** The saved key of {@code moduleName}, matched ignoring case and punctuation; null when absent. */
    private static String moduleKey(JsonObject modules, String moduleName) {
        String target = normalize(moduleName);
        for (String key : modules.keySet()) {
            if (normalize(key).equals(target)) return key;
        }
        return null;
    }

    private static JsonObject settingsOf(JsonObject module) {
        JsonElement settings = module.get("settings");
        if (settings != null && settings.isJsonObject()) return settings.getAsJsonObject();
        JsonElement legacy = module.get("IllIIllI32");
        if (legacy != null && legacy.isJsonObject()) return legacy.getAsJsonObject();
        JsonObject created = new JsonObject();
        module.add("settings", created);
        return created;
    }

    private static boolean readFlag(JsonObject module, String[] keys) {
        for (String key : keys) {
            JsonElement value = module.get(key);
            if (value == null || !value.isJsonPrimitive()) continue;
            try {
                return value.getAsBoolean();
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void setFlag(JsonObject module, String[] keys, boolean value) {
        for (int i = 1; i < keys.length; i++) module.remove(keys[i]);
        module.addProperty(keys[0], value);
    }

    private static int readInt(JsonObject module, String[] keys) {
        for (String key : keys) {
            JsonElement value = module.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
            try {
                return value.getAsInt();
            } catch (RuntimeException ignored) {
            }
        }
        return -1;
    }

    private static void setInt(JsonObject module, String[] keys, int value) {
        for (int i = 1; i < keys.length; i++) module.remove(keys[i]);
        module.addProperty(keys[0], value);
    }

    /**
     * Schema 2 -> 3: HUD colours rework. The HUD Style "Follow GUI Theme" switch became the "Colour Mode" choice,
     * and the per-widget "Theme Colours" switch (CPS, Keystrokes; on = follow the HUD) became the shared
     * "Use Own Colours" override (inverted), so a widget that had its own colours keeps them.
     */
    private static void migrateHudToV3(JsonObject hudElements) {
        for (Map.Entry<String, JsonElement> entry : hudElements.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonElement settingsElement = entry.getValue().getAsJsonObject().get("settings");
            if (settingsElement == null || !settingsElement.isJsonObject()) continue;
            JsonObject settings = settingsElement.getAsJsonObject();
            Boolean follow = readBoolean(settings, "Follow GUI Theme");
            if (follow != null) {
                settings.remove("Follow GUI Theme");
                if (!settings.has("Colour Mode")) settings.addProperty("Colour Mode", follow ? "Follow GUI Theme" : "Custom");
            }
            Boolean themed = readBoolean(settings, "Theme Colours");
            if (themed != null) settings.remove("Theme Colours");
            // The old widgets also turned "Theme Colours" off on load whenever a saved colour differed from its
            // default, so any customised colour meant the widget drew its own colours: keep that.
            boolean customised = false;
            Map<String, Integer> defaults = OLD_WIDGET_COLOUR_DEFAULTS.get(entry.getKey());
            if (defaults != null) {
                for (Map.Entry<String, Integer> colour : defaults.entrySet()) {
                    JsonElement value = settings.get(colour.getKey());
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
                    try {
                        if (value.getAsInt() != colour.getValue()) customised = true;
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if ((themed != null || customised) && !settings.has("Use Own Colours")) {
                settings.addProperty("Use Own Colours", customised || !themed);
            }
        }
    }

    /** Per-widget colours of the old "Theme Colours" widgets with their defaults (ARGB as saved). */
    private static final Map<String, Map<String, Integer>> OLD_WIDGET_COLOUR_DEFAULTS = Map.of(
            "CPS", Map.of("Left Color", 0xFF0A84FF, "Right Color", 0xFF32D74B),
            "Keystrokes", Map.of("Key Off Color", 0xDC1C1C1E, "Key On Color", 0xDC0A84FF,
                    "Text Off Color", 0xB4AEAEB2, "Text On Color", 0xFFF2F2F7));

    /** A boolean setting value, or null when absent or not a boolean. */
    private static Boolean readBoolean(JsonObject settings, String name) {
        JsonElement value = settings.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return null;
        return value.getAsBoolean();
    }

    /** The settings object of the module whose key matches {@code moduleName} ignoring case and punctuation. */
    private static JsonObject moduleSettings(JsonObject modules, String moduleName) {
        String target = normalize(moduleName);
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (!normalize(entry.getKey()).equals(target) || !entry.getValue().isJsonObject()) continue;
            JsonElement settings = entry.getValue().getAsJsonObject().get("settings");
            return settings != null && settings.isJsonObject() ? settings.getAsJsonObject() : null;
        }
        return null;
    }

    private static void renameSetting(JsonObject settings, String from, String to, double scale) {
        if (!settings.has(from) || settings.has(to)) return;
        JsonElement value = settings.remove(from);
        if (scale != 1.0 && value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            value = new JsonPrimitive(value.getAsDouble() * scale);
        }
        if (value != null) settings.add(to, value);
        for (String suffix : COMPANION_SUFFIXES) {
            JsonElement companion = settings.remove(from + suffix);
            if (companion != null && !settings.has(to + suffix)) settings.add(to + suffix, companion);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
