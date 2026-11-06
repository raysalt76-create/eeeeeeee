package interstellarTikiTiks.apexx.client.config.maker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the Config Maker: loads the bundled rules, runs {@link ConfigMakerEngine}, and turns the plan
 * into a cloud config blob. Fully offline; nothing here touches the network or the disk (saving goes through the
 * normal cloud config path). Modules or settings the spec names that this build does not have are skipped.
 */
public final class ConfigMaker {
    private static final String SPEC_PATH = "assets/apexx/config_maker.json";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Categories reset to defaults in a generated config. Render, HUD and client modules keep the player's setup. */
    private static final Set<Category> RESET = EnumSet.of(Category.COMBAT, Category.MOVEMENT, Category.AUTOMATIONS,
            Category.PLAYER, Category.BLATANT, Category.SMP, Category.BEDWARS);

    private static volatile JsonObject spec;

    private ConfigMaker() { }

    public record Option(String id, String label) { }

    public record Question(String id, String title, List<Option> options) { }

    /** A module as shown in the preview: only modules this build has. */
    public record PreviewModule(String category, String name, boolean enabled, List<String> highlights) { }

    /** Premium accounts only; see {@link interstellarTikiTiks.apexx.client.util.PremiumGate}. */
    public static boolean isPremium() {
        return interstellarTikiTiks.apexx.client.util.PremiumGate.isPremium();
    }

    /** The bundled rules, or null when they cannot be read. */
    public static JsonObject spec() {
        JsonObject cached = spec;
        if (cached != null) return cached;
        for (ClassLoader loader : new ClassLoader[]{ConfigMaker.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
            if (loader == null) continue;
            try (InputStream in = loader.getResourceAsStream(SPEC_PATH)) {
                if (in == null) continue;
                JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    spec = parsed.getAsJsonObject();
                    return spec;
                }
            } catch (Exception ignored) {
                // try the next loader
            }
        }
        return null;
    }

    public static List<Question> questions() {
        JsonObject s = spec();
        List<Question> out = new ArrayList<>();
        if (s == null) return out;
        for (JsonElement q : s.getAsJsonArray("questions")) {
            JsonObject qo = q.getAsJsonObject();
            List<Option> options = new ArrayList<>();
            for (JsonElement o : qo.getAsJsonArray("options")) {
                options.add(new Option(o.getAsJsonObject().get("id").getAsString(), o.getAsJsonObject().get("label").getAsString()));
            }
            out.add(new Question(qo.get("id").getAsString(), qo.get("title").getAsString(), options));
        }
        return out;
    }

    /** Run the rules; null when the spec is missing or an answer is invalid. */
    public static ConfigMakerEngine.Plan plan(Map<String, String> answers) {
        JsonObject s = spec();
        if (s == null) return null;
        try {
            return ConfigMakerEngine.generate(s, answers);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static ModuleManager modules() {
        return Apexx.INSTANCE == null ? null : Apexx.INSTANCE.getModuleManager();
    }

    /** Runtime guard on top of the spec's safety pass: Blatant only when the plan allows it, never one-shot actions. */
    private static boolean effectiveEnabled(Module module, ConfigMakerEngine.PlanModule pm, ConfigMakerEngine.Plan plan) {
        if (!pm.enabled || module.isOneShot()) return false;
        return module.getCategory() != Category.BLATANT || plan.blatantAllowed;
    }

    /** Enabled modules first (grouped by category in menu order), then modules that are only tuned. */
    public static List<PreviewModule> preview(ConfigMakerEngine.Plan plan) {
        List<PreviewModule> enabled = new ArrayList<>(), tuned = new ArrayList<>();
        ModuleManager mm = modules();
        if (plan == null || mm == null) return enabled;
        for (Category category : Category.values()) {
            for (Map.Entry<String, ConfigMakerEngine.PlanModule> e : plan.modules.entrySet()) {
                Module module = mm.getModule(e.getKey());
                if (module == null || module.getCategory() != category) continue;
                List<String> highlights = new ArrayList<>();
                for (Map.Entry<String, JsonPrimitive> s : e.getValue().settings.entrySet()) {
                    Setting setting = module.getSetting(s.getKey());
                    JsonPrimitive value = setting == null ? null : coerce(setting, s.getValue());
                    if (value != null) highlights.add(s.getKey() + " " + display(value));
                }
                boolean on = effectiveEnabled(module, e.getValue(), plan);
                if (!on && !e.getValue().listed) continue; // only a Silent/Normal preference, nothing to show
                (on ? enabled : tuned).add(new PreviewModule(category.name, module.getName(), on, highlights));
            }
        }
        enabled.addAll(tuned);
        return enabled;
    }

    private static String display(JsonPrimitive value) {
        if (value.isBoolean()) return value.getAsBoolean() ? "on" : "off";
        if (value.isNumber()) {
            double d = value.getAsDouble();
            return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.2f", d);
        }
        return value.getAsString();
    }

    /** The plan value in the setting's own type and range, or null when it does not fit this setting. */
    private static JsonPrimitive coerce(Setting setting, JsonPrimitive value) {
        if (setting instanceof SliderSetting slider && value.isNumber()) {
            double lo = slider.getMin(), hi = slider.getMax();
            if (setting.getName().contains("(ms)")) lo = Math.max(lo, Math.min(1.0, hi));
            return new JsonPrimitive(Math.min(hi, Math.max(lo, value.getAsDouble())));
        }
        if (setting instanceof ModeSetting mode && value.isString()) {
            for (String m : mode.getModes()) {
                if (m.equalsIgnoreCase(value.getAsString())) return new JsonPrimitive(m);
            }
            return null;
        }
        if (setting instanceof BooleanSetting && value.isBoolean()) return value;
        return null;
    }

    /**
     * The generated config as a cloud blob: the player's current config (theme, HUD, GUI, render modules and
     * keybinds) with every gameplay module switched off and reset to its default settings, then the plan applied.
     * Call on the client thread. Null when the module list is not available yet.
     */
    public static String buildConfigJson(ConfigMakerEngine.Plan plan) {
        ModuleManager mm = modules();
        if (plan == null || mm == null) return null;
        JsonObject root;
        try {
            String base = ConfigManager.getInstance().exportCurrentConfigJsonForCloud();
            JsonElement parsed = base == null ? null : JsonParser.parseString(base);
            root = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException malformed) {
            root = new JsonObject();
        }
        JsonObject modules = root.has("modules") && root.get("modules").isJsonObject()
                ? root.getAsJsonObject("modules") : new JsonObject();
        root.add("modules", modules);

        for (Module module : new ArrayList<>(mm.getModules())) {
            if (!RESET.contains(module.getCategory())) continue;
            JsonObject entry = entryFor(modules, module);
            entry.addProperty("enabled", false);
            JsonObject settings = settingsOf(entry);
            for (Setting setting : module.getSettings()) {
                if (setting instanceof SliderSetting slider) settings.addProperty(setting.getName(), slider.getDefaultValue());
                else if (setting instanceof ModeSetting mode) settings.addProperty(setting.getName(), mode.getDefaultMode());
                else if (setting instanceof BooleanSetting bool) settings.addProperty(setting.getName(), bool.isDefaultEnabled());
            }
        }

        for (Map.Entry<String, ConfigMakerEngine.PlanModule> e : plan.modules.entrySet()) {
            Module module = mm.getModule(e.getKey());
            if (module == null) continue; // not in this build
            JsonObject entry = entryFor(modules, module);
            entry.addProperty("enabled", effectiveEnabled(module, e.getValue(), plan));
            JsonObject settings = settingsOf(entry);
            for (Map.Entry<String, JsonPrimitive> s : e.getValue().settings.entrySet()) {
                Setting setting = module.getSetting(s.getKey());
                JsonPrimitive value = setting == null ? null : coerce(setting, s.getValue());
                if (value != null) settings.add(setting.getName(), value);
            }
        }
        return GSON.toJson(root);
    }

    private static JsonObject entryFor(JsonObject modules, Module module) {
        JsonElement existing = modules.get(module.getName());
        if (existing != null && existing.isJsonObject()) return existing.getAsJsonObject();
        JsonObject entry = new JsonObject();
        entry.addProperty("enabled", false);
        entry.addProperty("keyCode", module.getKeyCode());
        entry.addProperty("keyCode2", module.getKeyCode2());
        entry.addProperty("holdMode", module.isHoldMode());
        entry.addProperty("favorite", module.isFavorite());
        modules.add(module.getName(), entry);
        return entry;
    }

    private static JsonObject settingsOf(JsonObject entry) {
        JsonElement settings = entry.get("settings");
        if (settings != null && settings.isJsonObject()) return settings.getAsJsonObject();
        JsonObject created = new JsonObject();
        entry.add("settings", created);
        return created;
    }
}
