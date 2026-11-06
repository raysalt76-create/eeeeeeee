package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;

/**
 * Schema 5 -> 6: the 2026-10-21 menu/settings/module/HUD clean-up. Module display names lose their spaces,
 * the duplicate ESP, item-glow and clutch modules fold into one survivor each, the global Humanization module
 * becomes per-module "Click Sim" toggles, swap-back and colour spellings are unified, the ClickGUI drops its
 * second glass tint, and the Target HUD / Array List modules move into the HUD editor.
 *
 * <p>Kept apart from {@link ConfigMigrations} only for size; it runs from there and shares its JSON helpers'
 * conventions (never throws, missing keys are skipped, an existing new key is never overwritten).</p>
 */
final class ConfigMigrationsV6 {

    private ConfigMigrationsV6() {
    }

    private static final List<String> COMPANION_SUFFIXES = List.of("#key", "#hold", "#rainbow", "#rainbowSpeed");

    /** Module display-name renames (old key -> new key). The whole saved block moves across. */
    private static final Map<String, String> MODULE_RENAMES = Map.ofEntries(
            Map.entry("Pearl Catch", "PearlCatch"),
            Map.entry("Pearl Disrupter", "PearlDisrupter"),
            Map.entry("Pot Refill", "PotRefill"),
            Map.entry("Hit Color", "HitColor"),
            Map.entry("Skeleton ESP", "SkeletonESP"),
            Map.entry("SpearMaceDtap", "SpearMaceDoubleTap"),
            Map.entry("Snaptap", "SnapTap"),
            Map.entry("Anti-AFK", "AntiAFK"),
            Map.entry("Chompza Insta-Catch", "ChompzaInstaCatch"),
            Map.entry("Armor Warnings", "ArmorWarnings"),
            Map.entry("Auto Header", "AutoHeader"),
            Map.entry("Block Outline", "BlockOutline"),
            Map.entry("Cooldown Ring", "CooldownRing"),
            Map.entry("Crit Flash", "CritFlash"),
            Map.entry("Crystal FX", "CrystalFX"),
            Map.entry("Custom Crosshair", "CustomCrosshair"),
            Map.entry("Damage Numbers", "DamageNumbers"),
            Map.entry("Death Coords", "DeathCoords"),
            Map.entry("Friend Glow", "FriendGlow"),
            Map.entry("Hotbar Scroll Lock", "HotbarScrollLock"),
            Map.entry("Jump Circles", "JumpCircles"),
            Map.entry("Kill Effects", "KillEffects"),
            Map.entry("Kit Layout", "KitLayout"),
            Map.entry("Logout Spots", "LogoutSpots"),
            Map.entry("Mace Fall Indicator", "MaceFallIndicator"),
            Map.entry("Mace Impact", "MaceImpact"),
            Map.entry("New Chunks", "NewChunks"),
            Map.entry("Pearl Landing Marker", "PearlLandingMarker"),
            Map.entry("Projectile Trails", "ProjectileTrails"),
            Map.entry("Shield Status", "ShieldStatus"),
            Map.entry("Swing Trails", "SwingTrails"),
            Map.entry("Sword Aura", "SwordAura"),
            Map.entry("Target ESP", "TargetESP"),
            Map.entry("Totem Pop Customizer", "TotemPopCustomizer"),
            Map.entry("Visual Range", "VisualRange"));

    /** One setting rename inside one module (value kept as is unless {@code scale} differs from 1). */
    private record Rename(String module, String from, String to, double scale) {
        Rename(String module, String from, String to) {
            this(module, from, to, 1.0);
        }
    }

    private static final String SWAP_BACK = "Swap Back";
    private static final String SWAP_BACK_DELAY = "Swap Back Delay (ms)";

    private static final List<Rename> SETTING_RENAMES = List.of(
            // -- swap-back naming unified
            new Rename("AutoCart", "Switch Back", SWAP_BACK),
            new Rename("AutoCrystal", "Switch Back", SWAP_BACK),
            new Rename("AutoWeb", "Switch Back", SWAP_BACK),
            new Rename("AutoXBowCart", "Switch Back", SWAP_BACK),
            new Rename("CartBlocker", "Switch Back", SWAP_BACK),
            new Rename("FastCart", "Switch Back", SWAP_BACK),
            new Rename("WebEscape", "Switch Back", SWAP_BACK),
            new Rename("AutoShieldBreak", "Return To Weapon", SWAP_BACK),
            new Rename("AutoShieldBreak", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("Scaffold", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoElytra", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoElytra", "Post-Hit Swap Back (ms)", "Post-Hit Swap Back Delay (ms)"),
            new Rename("AutoMace", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoWeb", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("BreachSwap", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("ChompzaInstaCatch", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("Clutch", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoLunge", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("InstantLunge", "Swap-Back Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoChorus", "Restore Delay (ms)", SWAP_BACK_DELAY),
            new Rename("AutoEat", "Swap Back (ms)", SWAP_BACK_DELAY),
            new Rename("AutoEat", "Head Swap Back (ms)", "Head Swap Back Delay (ms)"),
            new Rename("AutoSoup", "Swap Back (ms)", SWAP_BACK_DELAY),
            // -- aim randomness naming unified
            new Rename("KnockbackDisplacement", "Aim Imperfection", "Aim Randomness"),
            new Rename("ChompzaInstaCatch", "Aim Imperfection", "Aim Randomness"),
            new Rename("Scaffold", "Aim Randomization", "Aim Randomness"),
            new Rename("AutoShieldBreak", "Aim Imperfection %", "Aim Randomness %"),
            // -- per-module click simulation
            new Rename("ChompzaInstaCatch", "ClickSim", "Click Sim"),
            new Rename("AutoPearl", "ClickSim", "Click Sim"),
            // -- units: ticks -> ms, names gain their unit
            new Rename("SwordHit", "Swap Back Ticks", SWAP_BACK_DELAY, 50.0),
            new Rename("AutoTool", "Swap Delay (ticks)", "Swap Delay (ms)", 50.0),
            new Rename("AutoTool", "Swap Back Delay (ticks)", SWAP_BACK_DELAY, 50.0),
            new Rename("WebEscape", "Pickup Delay", "Pickup Delay (ms)"),
            new Rename("WebEscape", "Max Hold", "Max Hold (ms)"),
            new Rename("AutoSprint", "Tap Cooldown", "Tap Cooldown (ms)"),
            // -- Clutch absorbs ClutchMLG (its own old water settings)
            new Rename("Clutch", "Pick Up Water", "Auto Retract"),
            new Rename("Clutch", "Water Pickup Delay (ms)", "Retract Delay (ms)"),
            // -- ItemESP's single colour became the Custom colour of a colour mode
            new Rename("ItemESP", "Color", "Custom Color"),
            // -- Trajectory moved to the shared colour system
            new Rename("Trajectory", "Custom Primary", "Custom Color"),
            new Rename("Trajectory", "Custom Secondary", "Gradient End"),
            // -- ClickGUI: blur naming and American spelling
            new Rename("ClickGUI", "Blur BG", "Backdrop Blur"),
            new Rename("ClickGUI", "Blur Strength", "Backdrop Blur Strength"),
            new Rename("ClickGUI", "Glass Blur", "Panel Blur"),
            new Rename("ClickGUI", "Outer stroke", "Outer Stroke"),
            new Rename("ClickGUI", "Water Colours", "Water Colors"),
            new Rename("ClickGUI", "Water Colour 1", "Water Color 1"),
            new Rename("ClickGUI", "Water Colour 2", "Water Color 2"),
            new Rename("ClickGUI", "Water Colour 3", "Water Color 3"),
            new Rename("ClickGUI", "Water Colour Mode", "Water Color Mode"),
            new Rename("ClickGUI", "Text Colour", "Text Color"),
            new Rename("ClickGUI", "Muted Text Colour", "Muted Text Color"),
            new Rename("ClickGUI", "Selection Colour", "Selection Color"),
            new Rename("ClickGUI", "Rim Colour", "Rim Color"),
            new Rename("ClickGUI", "Custom Header Colour", "Custom Header Color"));

    /** Settings that no longer exist; dropping them keeps the saved block honest, though the loader ignores them anyway. */
    private static final Map<String, List<String>> REMOVED_SETTINGS = Map.of(
            "AutoDrain", List.of("Drain Webs"),
            "AutoSprint", List.of("Retap On Hit"),
            "ClickGUI", List.of("Display Name", "Profile Icon", "Create Your Link Key", "Best-Effort Log Blank"),
            "SelfDestruct", List.of("Ocean Safe", "Wipe Config", "Delete Jar", "Replace Jar", "Decoy URL",
                    "Best-Effort Log Blank"),
            "HudRestyle", List.of("Background Opacity", "Corner Radius"),
            "Notifications", List.of("Background Opacity", "Corner Radius"));

    /** HUD widget key renames (whole entry incl. x/y/enabled/settings moves). */
    private static final Map<String, String> HUD_RENAMES = Map.of(
            "TargetHUD", "Target HUD",
            "EffectHUD", "Effect HUD",
            "Keybinded Modules", "Bound Modules");

    private static final List<String> TEXT_SIZE_WIDGETS = List.of("FPS", "Coordinates", "CPS", "Last Reach");

    static void migrate(JsonObject root) {
        JsonElement modulesElement = root.get("modules");
        JsonObject modules = modulesElement != null && modulesElement.isJsonObject() ? modulesElement.getAsJsonObject() : null;
        JsonElement hudElement = root.get("hudElements");
        JsonObject hud = hudElement != null && hudElement.isJsonObject() ? hudElement.getAsJsonObject() : null;
        if (modules != null) {
            renameModules(modules);
            for (Rename rename : SETTING_RENAMES) {
                JsonObject settings = moduleSettings(modules, rename.module());
                if (settings != null) renameSetting(settings, rename.from(), rename.to(), rename.scale());
            }
            migrateClickGui(modules);
            migrateHumanization(modules);
            migrateAutoMaceSpear(modules);
            migrateTrajectory(modules);
            migrateEsp(modules);
            migrateItemEsp(modules);
            migrateClutch(modules);
            for (Map.Entry<String, List<String>> removed : REMOVED_SETTINGS.entrySet()) {
                JsonObject settings = moduleSettings(modules, removed.getKey());
                if (settings == null) continue;
                for (String name : removed.getValue()) removeSetting(settings, name);
            }
            renameStDevCompanions(modules);
        }
        if (hud != null) migrateHud(hud, modules);
        migrateGuiPrefs(root);
    }

    /** Where a module's saved name points after this migration: the new name, or null when the module is gone. */
    static String moduleNameAfter(String name) {
        if (name == null) return null;
        String exact = MODULE_RENAMES.get(name);
        if (exact != null) return exact;
        String target = normalize(name);
        for (Map.Entry<String, String> rename : MODULE_RENAMES.entrySet()) {
            if (normalize(rename.getKey()).equals(target)) return rename.getValue();
        }
        return switch (target) {
            case "chams", "2desp", "mobesp" -> "ShaderESP";
            case "itemglow" -> "ItemESP";
            case "clutchmlg" -> "Clutch";
            case "humanization", "targethud", "arraylist" -> null;
            default -> name;
        };
    }

    /**
     * The GUI prefs (module order, folders and quick pins) name modules and settings by display name; they follow
     * the renames so a pinned or folded module does not silently vanish from the menu.
     */
    private static void migrateGuiPrefs(JsonObject root) {
        JsonElement prefsElement = root.get(ClientPrefs.KEY);
        if (prefsElement == null || !prefsElement.isJsonObject()) return;
        JsonElement guiElement = prefsElement.getAsJsonObject().get("gui");
        if (guiElement == null || !guiElement.isJsonObject()) return;
        JsonObject gui = guiElement.getAsJsonObject();
        JsonElement order = gui.get("order");
        if (order != null && order.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : order.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonArray()) renameNames(entry.getValue().getAsJsonArray());
            }
        }
        JsonElement folders = gui.get("folders");
        if (folders != null && folders.isJsonArray()) {
            for (JsonElement folder : folders.getAsJsonArray()) {
                if (!folder.isJsonObject()) continue;
                JsonElement members = folder.getAsJsonObject().get("modules");
                if (members != null && members.isJsonArray()) renameNames(members.getAsJsonArray());
            }
        }
        JsonElement pins = gui.get("pins");
        if (pins != null && pins.isJsonArray()) {
            com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
            for (JsonElement pin : pins.getAsJsonArray()) {
                if (!pin.isJsonObject()) continue;
                JsonObject p = pin.getAsJsonObject();
                JsonElement m = p.get("m");
                JsonElement s = p.get("s");
                if (m == null || !m.isJsonPrimitive() || s == null || !s.isJsonPrimitive()) continue;
                String module = moduleNameAfter(m.getAsString());
                if (module == null) continue;
                String setting = s.getAsString();
                for (Rename rename : SETTING_RENAMES) {
                    if (normalize(rename.module()).equals(normalize(module)) && rename.from().equals(setting)) {
                        setting = rename.to();
                        break;
                    }
                }
                p.addProperty("m", module);
                p.addProperty("s", setting);
                kept.add(p);
            }
            gui.add("pins", kept);
        }
    }

    private static void renameNames(com.google.gson.JsonArray names) {
        com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
        for (JsonElement name : names) {
            if (!name.isJsonPrimitive()) continue;
            String after = moduleNameAfter(name.getAsString());
            if (after != null && !kept.contains(new JsonPrimitive(after))) kept.add(after);
        }
        while (names.size() > 0) names.remove(0);
        names.addAll(kept);
    }

    /**
     * AutoMace's own spear-reach switch and reaction moved to the AutoSpearReach module, which is now the single
     * owner: a config that had the switch on turns that module on and carries the reaction across.
     */
    private static void migrateAutoMaceSpear(JsonObject modules) {
        JsonObject mace = moduleSettings(modules, "AutoMace");
        if (mace == null) return;
        Boolean on = readBoolean(mace, "AutoSpear Reach");
        JsonElement reaction = mace.remove("Spear Swap Reaction (ms)");
        JsonElement reactionStDev = mace.remove("Spear Swap Reaction (ms) StDev");
        if (reactionStDev == null) reactionStDev = mace.remove("Spear Swap Reaction StDev (ms)");
        removeSetting(mace, "AutoSpear Reach");
        if (!Boolean.TRUE.equals(on)) return;
        JsonObject spear = moduleBlock(modules, "AutoSpearReach");
        if (spear == null) {
            spear = new JsonObject();
            modules.add("AutoSpearReach", spear);
        }
        spear.addProperty("enabled", true);
        JsonObject s = settingsOf(spear);
        putIfAbsent(s, "Swap Reaction (ms)", reaction);
        putIfAbsent(s, "Swap Reaction StDev (ms)", reactionStDev);
    }

    /** Every auto StDev companion "Base (unit) StDev" is now labelled "Base StDev (unit)". */
    private static void renameStDevCompanions(JsonObject modules) {
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject settings = settingsOf(entry.getValue().getAsJsonObject());
            for (String key : List.copyOf(settings.keySet())) {
                java.util.regex.Matcher m = OLD_STDEV.matcher(key);
                if (!m.matches()) continue;
                String renamed = m.group(1) + " StDev (" + m.group(2) + ")";
                if (!settings.has(renamed)) settings.add(renamed, settings.remove(key));
                else settings.remove(key);
            }
        }
    }

    private static final java.util.regex.Pattern OLD_STDEV = java.util.regex.Pattern.compile("^(.*\\S) \\(([^()]+)\\) StDev$");

    /** The StDev companion label of a slider named {@code name} in the current (unit-last) convention. */
    private static String stDevName(String name) {
        if (name.endsWith(")")) {
            int open = name.lastIndexOf(" (");
            if (open > 0) return name.substring(0, open) + " StDev" + name.substring(open);
        }
        return name + " StDev";
    }

    // ------------------------------------------------------------------ modules

    private static void renameModules(JsonObject modules) {
        for (Map.Entry<String, String> rename : MODULE_RENAMES.entrySet()) {
            String oldKey = exactOrNormalizedKey(modules, rename.getKey());
            if (oldKey == null || oldKey.equals(rename.getValue())) continue;
            if (modules.has(rename.getValue())) {
                modules.remove(oldKey);
            } else {
                modules.add(rename.getValue(), modules.remove(oldKey));
            }
        }
    }

    /**
     * ClickGUI: the second glass tint folds into Panel Tint (only when it was switched on), Mist's choice names
     * change, and the hidden SelfDestruct module's own option copies go (ClickGUI's are the one set).
     */
    private static void migrateClickGui(JsonObject modules) {
        JsonObject gui = moduleSettings(modules, "ClickGUI");
        if (gui == null) return;
        Boolean custom = readBoolean(gui, "Custom BG Color");
        if (Boolean.TRUE.equals(custom) && !isModified(gui, "Panel Tint", 0x44050812)) {
            Integer argb = readInt(gui, "Background Color");
            Double opacity = readDouble(gui, "Background Opacity");
            if (argb != null) {
                int alpha = (argb >>> 24) & 0xFF;
                if (opacity != null) alpha = (int) Math.round(alpha * clamp(opacity, 0.0, 100.0) / 100.0);
                gui.addProperty("Panel Tint", (alpha << 24) | (argb & 0x00FFFFFF));
                gui.addProperty("Panel Tint#rainbow", false);
            }
        }
        removeSetting(gui, "Custom BG Color");
        removeSetting(gui, "Background Color");
        removeSetting(gui, "Background Opacity");
        JsonElement mist = gui.get("Mist");
        if (mist != null && mist.isJsonPrimitive()) {
            String value = mist.getAsString().trim();
            if (value.equalsIgnoreCase("None") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")) {
                gui.addProperty("Mist", "Off");
            } else if (value.equalsIgnoreCase("Mist") || value.equalsIgnoreCase("Deep Mist") || value.equalsIgnoreCase("true")
                    || value.equalsIgnoreCase("on")) {
                gui.addProperty("Mist", "On");
            }
        }
        JsonElement waterMode = gui.get("Water Color Mode");
        if (waterMode != null && waterMode.isJsonPrimitive() && "My Colours".equalsIgnoreCase(waterMode.getAsString())) {
            gui.addProperty("Water Color Mode", "My Colors");
        }
    }

    /**
     * The global Humanization module is gone. Its only switch with a per-module successor is Keystrokes: when it
     * was on, every click-emitting module's own "Click Sim" turns on, which is what the global switch did. Modules
     * without the toggle ignore the extra key.
     */
    private static void migrateHumanization(JsonObject modules) {
        String key = exactOrNormalizedKey(modules, "Humanization");
        if (key == null) return;
        JsonElement removed = modules.remove(key);
        if (removed == null || !removed.isJsonObject()) return;
        JsonObject settings = settingsOf(removed.getAsJsonObject());
        if (!Boolean.TRUE.equals(readBoolean(settings, "Keystrokes"))) return;
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject target = settingsOf(entry.getValue().getAsJsonObject());
            if (!target.has("Click Sim")) target.addProperty("Click Sim", true);
        }
    }

    /** Trajectory's private "Theme" choice becomes the shared "Color" choice. */
    private static void migrateTrajectory(JsonObject modules) {
        JsonObject settings = moduleSettings(modules, "Trajectory");
        if (settings == null) return;
        JsonElement theme = settings.remove("Theme");
        if (theme == null || !theme.isJsonPrimitive() || settings.has("Color")) return;
        String value = theme.getAsString().trim();
        String color;
        if (value.equalsIgnoreCase("Chroma")) color = "Rainbow";
        else if (value.equalsIgnoreCase("Custom")) color = "Custom";
        else color = "Theme";
        settings.addProperty("Color", color);
    }

    /**
     * Chams, 2D ESP and MobESP fold into ShaderESP. Precedence when several were on with clashing values:
     * ShaderESP's own values, then Chams, then 2D ESP, then MobESP. A survivor that was off takes over an absorbed
     * module's role (enabled + its style switch) only when that module was on.
     */
    private static void migrateEsp(JsonObject modules) {
        JsonObject shader = moduleBlock(modules, "ShaderESP");
        JsonObject chams = takeModule(modules, "Chams");
        JsonObject esp2d = takeModule(modules, "2D ESP");
        JsonObject mobEsp = takeModule(modules, "MobESP");
        if (shader == null && chams == null && esp2d == null && mobEsp == null) return;
        boolean created = false;
        if (shader == null) {
            shader = new JsonObject();
            shader.addProperty("enabled", false);
            modules.add("ShaderESP", shader);
            created = true;
        }
        JsonObject s = settingsOf(shader);

        // ShaderESP's own old combo choice.
        JsonElement style = s.remove("ESP Style");
        if (style != null && style.isJsonPrimitive()) {
            String v = style.getAsString().trim();
            boolean hasShader = v.startsWith("Shader");
            String boxes2d = v.contains("2DFull") || v.equals("2D Full") ? "Full"
                    : v.contains("2DCorners") || v.equals("2D Corners") ? "Corners" : "Off";
            boolean boxes3d = v.contains("3D");
            putIfAbsent(s, "Shader", hasShader);
            putIfAbsent(s, "2D Boxes", boxes2d);
            putIfAbsent(s, "3D Boxes", boxes3d);
        }
        Boolean mobs = readBoolean(s, "Mobs");
        if (mobs != null) {
            s.remove("Mobs");
            putIfAbsent(s, "Hostile", mobs);
            putIfAbsent(s, "Passive", mobs);
        }
        renameSetting(s, "3D Crystal/Item Spread", "3D Crystal Spread", 1.0);
        Boolean items = readBoolean(s, "Items");
        JsonElement itemLabels = s.remove("Item Labels");
        s.remove("Items");
        if (Boolean.TRUE.equals(items) && readFlag(shader)) {
            JsonObject itemEsp = moduleBlock(modules, "ItemESP");
            if (itemEsp == null) {
                itemEsp = new JsonObject();
                modules.add("ItemESP", itemEsp);
            }
            itemEsp.addProperty("enabled", true);
            if (itemLabels != null) putIfAbsent(settingsOf(itemEsp), "Labels", itemLabels);
        }

        boolean shaderWasOn = !created && readFlag(shader);
        if (chams != null) {
            JsonObject c = settingsOf(chams);
            boolean on = readFlag(chams);
            copyBool(c, "Players", s, "Players", on);
            copyBool(c, "Self", s, "Self", on);
            copyBool(c, "Friends", s, "Friends", on);
            copyBool(c, "Invisible", s, "Invisible", on);
            Boolean cMobs = readBoolean(c, "Mobs");
            if (cMobs != null && on) {
                putIfAbsent(s, "Hostile", cMobs);
                putIfAbsent(s, "Passive", cMobs);
            }
            JsonElement mode = c.get("Color Mode");
            if (mode != null && mode.isJsonPrimitive() && on) {
                putIfAbsent(s, "Mode", "Custom".equalsIgnoreCase(mode.getAsString()) ? "Custom" : "Theme Sync");
            }
            copyWithCompanions(c, "Occluded Color", s, "Custom Color");
            copyWithCompanions(c, "Occluded Opacity", s, "Occluded Opacity");
            copyWithCompanions(c, "Tint Visible", s, "Tint Visible");
            copyWithCompanions(c, "Visible Color", s, "Visible Color");
            copyWithCompanions(c, "Visible Opacity", s, "Visible Opacity");
            copyWithCompanions(c, "Friend Color", s, "Friend Color");
            if (c.has("Mob Color")) {
                copyWithCompanions(c, "Mob Color", s, "Hostile Color");
                putIfAbsent(s, "Color Mobs By Kind", true);
            }
            copyWithCompanions(c, "Glowing", s, "Chams Glowing");
            copyWithCompanions(c, "Glow Layers", s, "Chams Glow Layers");
            copyWithCompanions(c, "Glow Size", s, "Chams Glow Size");
            if (on) {
                shader.addProperty("enabled", true);
                s.addProperty("Chams", true);
                if (!shaderWasOn) putIfAbsent(s, "Shader", false);
            }
        }
        if (esp2d != null) {
            JsonObject e = settingsOf(esp2d);
            boolean on = readFlag(esp2d);
            copyWithCompanions(e, "Range", s, "Range");
            copyBool(e, "Players", s, "Players", on);
            copyBool(e, "Friends", s, "Friends", on);
            copyBool(e, "Invisible", s, "Invisible", on);
            Boolean eMobs = readBoolean(e, "Mobs");
            if (eMobs != null && on) {
                putIfAbsent(s, "Hostile", eMobs);
                putIfAbsent(s, "Passive", eMobs);
            }
            copyWithCompanions(e, "Line Width", s, "2D Line Width");
            copyWithCompanions(e, "Outline", s, "2D Outline");
            Double fill = readDouble(e, "Fill Opacity");
            if (fill != null) putIfAbsent(s, "2D Box Fill", fill > 0.0);
            JsonElement mode = e.get("Color Mode");
            if (mode != null && mode.isJsonPrimitive() && on) {
                putIfAbsent(s, "Mode", "Custom".equalsIgnoreCase(mode.getAsString()) ? "Custom" : "Theme Sync");
            }
            copyWithCompanions(e, "Custom Color", s, "Custom Color");
            copyWithCompanions(e, "Friend Color", s, "Friend Color");
            copyWithCompanions(e, "Health Bar", s, "Health Bar");
            copyWithCompanions(e, "Armor Bar", s, "Armor Bar");
            copyWithCompanions(e, "Distance", s, "Distance");
            copyWithCompanions(e, "Health Text", s, "Health Text");
            if (on) {
                shader.addProperty("enabled", true);
                JsonElement style2d = e.get("Style");
                String boxes = style2d != null && style2d.isJsonPrimitive() && "Full".equalsIgnoreCase(style2d.getAsString())
                        ? "Full" : "Corners";
                if (!shaderWasOn) {
                    s.addProperty("2D Boxes", boxes);
                    putIfAbsent(s, "Shader", false);
                } else {
                    putIfAbsent(s, "2D Boxes", boxes);
                }
            }
        }
        if (mobEsp != null) {
            JsonObject m = settingsOf(mobEsp);
            boolean on = readFlag(mobEsp);
            copyWithCompanions(m, "Range", s, "Range");
            for (String name : List.of("Hostile", "Passive", "Villagers & Traders", "Water Mobs", "Ambient", "Invisible")) {
                copyBool(m, name, s, name, on);
            }
            for (String name : List.of("Hostile Color", "Passive Color", "Villager Color", "Water Color", "Ambient Color", "Tracers")) {
                copyWithCompanions(m, name, s, name);
            }
            JsonElement style3d = m.get("Style");
            Double fill = readDouble(m, "Fill Opacity");
            if (style3d != null && style3d.isJsonPrimitive()) {
                double alpha = "Outline".equalsIgnoreCase(style3d.getAsString()) || fill == null ? 0.0 : clamp(fill, 0.0, 0.65);
                putIfAbsent(s, "3D Fill Alpha", alpha);
            }
            if (on) {
                shader.addProperty("enabled", true);
                if (!shaderWasOn) {
                    s.addProperty("3D Boxes", true);
                    putIfAbsent(s, "Shader", false);
                    putIfAbsent(s, "2D Boxes", "Off");
                } else {
                    putIfAbsent(s, "3D Boxes", true);
                }
            }
        }
        // Ran only when an absorbed module was on and the survivor was not: the survivor's role is that module's.
        if (!shaderWasOn && readFlag(shader)) {
            JsonObject bound = chams != null && readFlag(chams) ? chams : esp2d != null && readFlag(esp2d) ? esp2d
                    : mobEsp != null && readFlag(mobEsp) ? mobEsp : null;
            if (bound != null) carryBind(bound, shader);
        }
    }

    /** Item Glow folds into ItemESP; a glow-only user keeps the glow look (no box). */
    private static void migrateItemEsp(JsonObject modules) {
        JsonObject glow = takeModule(modules, "Item Glow");
        JsonObject itemEsp = moduleBlock(modules, "ItemESP");
        // ItemESP's old single colour (already renamed to Custom Color) only shows through the Custom mode.
        boolean espHadOwnColor = itemEsp != null && settingsOf(itemEsp).has("Custom Color") && !settingsOf(itemEsp).has("Color");
        if (glow != null) {
            boolean glowOn = readFlag(glow);
            JsonObject g = settingsOf(glow);
            boolean espWasOn = itemEsp != null && readFlag(itemEsp);
            if (itemEsp == null) {
                itemEsp = new JsonObject();
                itemEsp.addProperty("enabled", false);
                modules.add("ItemESP", itemEsp);
            }
            JsonObject s = settingsOf(itemEsp);
            JsonElement filter = g.get("Filter");
            if (filter != null && filter.isJsonPrimitive()) {
                putIfAbsent(s, "Show", "All".equalsIgnoreCase(filter.getAsString()) ? "All" : "Valuables");
            }
            for (String name : List.of("Netherite", "Diamond", "Totem", "Elytra", "Enchanted", "Rare", "Glow Outline", "Highlight",
                    "Range", "Custom Color", "Netherite Color", "Diamond Color", "Totem Color", "Elytra Color", "Enchanted Color",
                    "Rare Color", "Common Color")) {
                copyWithCompanions(g, name, s, name);
            }
            // The colour mode: Item Glow's when it was the module in use, otherwise ItemESP's own colour.
            JsonElement color = g.get("Color");
            boolean glowWins = glowOn && !espWasOn;
            if (color != null && color.isJsonPrimitive() && !s.has("Color") && (glowWins || !espHadOwnColor)) {
                String v = color.getAsString();
                s.addProperty("Color", "Per Category".equalsIgnoreCase(v) ? "Per Category"
                        : "Custom".equalsIgnoreCase(v) ? "Custom" : "Theme");
            }
            if (glowOn) {
                itemEsp.addProperty("enabled", true);
                if (!espWasOn) {
                    // Glow-only before: keep it glow-only now.
                    putIfAbsent(s, "Box", false);
                    carryBind(glow, itemEsp);
                }
            }
        }
        if (espHadOwnColor) putIfAbsent(settingsOf(itemEsp), "Color", "Custom");
    }

    /** ClutchMLG folds into Clutch as its MLG fall method. */
    private static void migrateClutch(JsonObject modules) {
        JsonObject clutch = moduleBlock(modules, "Clutch");
        if (clutch != null) {
            JsonObject s = settingsOf(clutch);
            Boolean water = readBoolean(s, "Water Fallback");
            if (water != null) {
                s.remove("Water Fallback");
                putIfAbsent(s, "Fall Method", water ? "Blocks + MLG" : "Blocks");
            }
        }
        JsonObject mlg = takeModule(modules, "ClutchMLG");
        if (mlg == null) return;
        boolean mlgOn = readFlag(mlg);
        JsonObject m = settingsOf(mlg);
        boolean hadClutch = clutch != null;
        boolean clutchWasOn = hadClutch && readFlag(clutch);
        if (clutch == null) {
            clutch = new JsonObject();
            clutch.addProperty("enabled", false);
            modules.add("Clutch", clutch);
        }
        JsonObject s = settingsOf(clutch);
        renameSetting(m, "Min Fall Distance", "MLG Min Fall Distance", 1.0);
        renameSetting(m, "Cooldown (ms)", "MLG Cooldown (ms)", 1.0);
        renameSetting(m, "Switch Back", SWAP_BACK, 1.0);
        renameSetting(m, "Silent Aim", "Fire Silent Aim", 1.0);
        renameSetting(m, "Swap Delay (ms)", "MLG Swap Delay (ms)", 1.0);
        m.remove("Use Timing Profile");
        JsonElement rotation = m.remove("Rotation Mode");
        if (rotation != null && rotation.isJsonPrimitive()) {
            putIfAbsent(s, "Silent Rotations", "Silent".equalsIgnoreCase(rotation.getAsString()));
        }
        for (String name : List.of("MLG Min Fall Distance", "Auto Retract", "Trigger on Fire", "Trigger in Lava", "Auto Switch",
                "MLG Cooldown (ms)", SWAP_BACK, "Skip in Nether", "Skip with Fire Resistance", "Fire Silent Aim",
                "Fire Reaction Delay (ms)", "Fire Pickup Delay (ms)", "Fire Cooldown (ms)", "MLG Swap Delay (ms)",
                "Retract Delay (ms)", "Click Sim")) {
            copyWithCompanions(m, name, s, name);
        }
        if (mlgOn) {
            clutch.addProperty("enabled", true);
            s.addProperty("Fall Method", clutchWasOn ? "Blocks + MLG" : "MLG");
            if (!clutchWasOn) carryBind(mlg, clutch);
        }
    }

    // ------------------------------------------------------------------ HUD

    private static void migrateHud(JsonObject hud, JsonObject modules) {
        for (Map.Entry<String, String> rename : HUD_RENAMES.entrySet()) {
            if (!hud.has(rename.getKey())) continue;
            JsonElement entry = hud.remove(rename.getKey());
            if (!hud.has(rename.getValue())) hud.add(rename.getValue(), entry);
        }
        // "Stats" split into the widgets that already showed each datum.
        JsonElement stats = hud.remove("Stats");
        if (stats != null && stats.isJsonObject() && readFlag(stats.getAsJsonObject())) {
            JsonObject st = settingsOf(stats.getAsJsonObject());
            if (Boolean.TRUE.equals(readBoolean(st, "FPS"))) enableWidget(hud, "FPS");
            if (Boolean.TRUE.equals(readBoolean(st, "Ping"))) enableWidget(hud, "Ping & TPS");
            if (Boolean.TRUE.equals(readBoolean(st, "Server"))) enableWidget(hud, "Server Info");
        }
        JsonObject serverInfo = widget(hud, "Server Info");
        if (serverInfo != null) {
            JsonObject st = settingsOf(serverInfo);
            Boolean sessionTime = readBoolean(st, "Session Time");
            st.remove("Session Time");
            if (Boolean.TRUE.equals(sessionTime) && readFlag(serverInfo)) enableWidget(hud, "Session Stats");
        }
        for (String name : TEXT_SIZE_WIDGETS) {
            JsonObject w = widget(hud, name);
            if (w != null) renameSetting(settingsOf(w), "Font Size", "Text Size", 1.0);
        }
        JsonObject style = widget(hud, "HUD Style");
        if (style != null) {
            JsonObject st = settingsOf(style);
            renameSetting(st, "Override Accent", "Custom Accent", 1.0);
            renameSetting(st, "Accent Override", "Accent Color", 1.0);
            Double glass = readDouble(st, "Glass Opacity");
            // Old range 0..2.5 (multiplier); a value above that is already a percent.
            if (glass != null && glass <= 2.5) st.addProperty("Glass Opacity", Math.round(clamp(glass * 100.0, 0.0, 200.0)));
        }
        if (modules == null) return;
        // Target HUD / Array List modules became HUD-editor widgets.
        JsonObject targetHud = takeModule(modules, "TargetHud");
        if (targetHud != null) {
            JsonObject w = widgetOrCreate(hud, "Target HUD");
            w.addProperty("enabled", readFlag(targetHud));
        }
        JsonObject arrayList = takeModule(modules, "ArrayList");
        if (arrayList != null) {
            JsonObject w = widgetOrCreate(hud, "Array List");
            w.addProperty("enabled", readFlag(arrayList));
            JsonObject from = settingsOf(arrayList);
            JsonObject to = settingsOf(w);
            renameSetting(from, "Font Size", "Text Size", 1.0);
            for (String name : List.of("Text Size", "BG Opacity", "Accent Text", "Sort", "Animation", "Animation Speed", "Padding",
                    "Spacing", "Background", "Outline", "Show Suffix")) {
                copyWithCompanions(from, name, to, name);
            }
            JsonElement colorMode = from.get("Color Mode");
            if (colorMode != null && colorMode.isJsonPrimitive()) {
                putIfAbsent(to, "Use Own Colours", "Custom".equalsIgnoreCase(colorMode.getAsString()));
            }
            copyWithCompanions(from, "Custom Color", to, "Accent Colour");
            copyWithCompanions(from, "BG Color", to, "Background Colour");
        }
    }

    private static JsonObject widget(JsonObject hud, String name) {
        JsonElement w = hud.get(name);
        return w != null && w.isJsonObject() ? w.getAsJsonObject() : null;
    }

    private static JsonObject widgetOrCreate(JsonObject hud, String name) {
        JsonObject w = widget(hud, name);
        if (w == null) {
            w = new JsonObject();
            hud.add(name, w);
        }
        return w;
    }

    private static void enableWidget(JsonObject hud, String name) {
        JsonObject w = widget(hud, name);
        if (w != null) w.addProperty("enabled", true);
    }

    // ------------------------------------------------------------------ helpers

    /** The saved block of a module, found by exact key or (case/punctuation-insensitive) normalized key. */
    private static JsonObject moduleBlock(JsonObject modules, String name) {
        String key = exactOrNormalizedKey(modules, name);
        if (key == null) return null;
        JsonElement block = modules.get(key);
        return block != null && block.isJsonObject() ? block.getAsJsonObject() : null;
    }

    private static JsonObject moduleSettings(JsonObject modules, String name) {
        JsonObject block = moduleBlock(modules, name);
        return block == null ? null : settingsOf(block);
    }

    /** Removes and returns a module's block, or null when absent. */
    private static JsonObject takeModule(JsonObject modules, String name) {
        String key = exactOrNormalizedKey(modules, name);
        if (key == null) return null;
        JsonElement block = modules.remove(key);
        return block != null && block.isJsonObject() ? block.getAsJsonObject() : null;
    }

    private static String exactOrNormalizedKey(JsonObject object, String name) {
        if (object.has(name)) return name;
        String target = normalize(name);
        for (String key : object.keySet()) {
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

    private static boolean readFlag(JsonObject module) {
        for (String key : new String[]{"enabled", "IlIllIlI32"}) {
            JsonElement value = module.get(key);
            if (value == null || !value.isJsonPrimitive()) continue;
            try {
                return value.getAsBoolean();
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** Moves the absorbed module's key bind to the survivor when the survivor has none. */
    private static void carryBind(JsonObject from, JsonObject to) {
        int key = readBind(from, "keyCode", "lIIllIlI32");
        if (key == -1 || key == 0) return;
        int existing = readBind(to, "keyCode", "lIIllIlI32");
        if (existing != -1 && existing != 0) return;
        to.remove("lIIllIlI32");
        to.addProperty("keyCode", key);
        int key2 = readBind(from, "keyCode2", "lIlIIlIl32");
        to.remove("lIlIIlIl32");
        to.addProperty("keyCode2", key2);
        JsonElement hold = from.get("holdMode");
        if (hold != null) to.add("holdMode", hold);
    }

    private static int readBind(JsonObject module, String... keys) {
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

    private static Boolean readBoolean(JsonObject settings, String name) {
        JsonElement value = settings.get(name);
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) {
            String s = p.getAsString().trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return null;
    }

    private static Integer readInt(JsonObject settings, String name) {
        JsonElement value = settings.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsInt();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Double readDouble(JsonObject settings, String name) {
        JsonElement value = settings.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsDouble();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean isModified(JsonObject settings, String name, int defaultArgb) {
        Integer value = readInt(settings, name);
        return value != null && value != defaultArgb;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void putIfAbsent(JsonObject settings, String name, boolean value) {
        if (!settings.has(name)) settings.addProperty(name, value);
    }

    private static void putIfAbsent(JsonObject settings, String name, double value) {
        if (!settings.has(name)) settings.addProperty(name, value);
    }

    private static void putIfAbsent(JsonObject settings, String name, String value) {
        if (!settings.has(name)) settings.addProperty(name, value);
    }

    private static void putIfAbsent(JsonObject settings, String name, JsonElement value) {
        if (!settings.has(name) && value != null) settings.add(name, value);
    }

    /** Copies a boolean only when the absorbed module was on (an unused module's filters must not start applying). */
    private static void copyBool(JsonObject from, String fromName, JsonObject to, String toName, boolean sourceWasOn) {
        if (!sourceWasOn) return;
        Boolean value = readBoolean(from, fromName);
        if (value != null) putIfAbsent(to, toName, value);
    }

    /** Copies a value and its companion keys when the survivor has no value of its own. */
    private static void copyWithCompanions(JsonObject from, String fromName, JsonObject to, String toName) {
        JsonElement value = from.get(fromName);
        if (value == null || to.has(toName)) return;
        to.add(toName, value);
        for (String suffix : COMPANION_SUFFIXES) {
            JsonElement companion = from.get(fromName + suffix);
            if (companion != null && !to.has(toName + suffix)) to.add(toName + suffix, companion);
        }
    }

    private static void removeSetting(JsonObject settings, String name) {
        settings.remove(name);
        for (String suffix : COMPANION_SUFFIXES) settings.remove(name + suffix);
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
        // The slider's StDev companion follows it (old label: unit inside the base name; new: unit last).
        for (String oldStDev : List.of(from + " StDev", stDevName(from))) {
            if (!settings.has(oldStDev)) continue;
            JsonElement stDev = settings.remove(oldStDev);
            String newStDev = stDevName(to);
            if (settings.has(newStDev) || stDev == null) continue;
            if (scale != 1.0 && stDev.isJsonPrimitive() && stDev.getAsJsonPrimitive().isNumber()) {
                stDev = new JsonPrimitive(stDev.getAsDouble() * scale);
            }
            settings.add(newStDev, stDev);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
